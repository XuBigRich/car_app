package local.car;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.Arrays;
import java.util.List;

/** 在手机上分两次启动测试进程，验证技能在进程结束后仍能完整恢复；不发送任何电机指令。 */
public final class SkillStoreInstrumentation extends Instrumentation {
    // phase=write 只保存测试数据；phase=read 在后续独立进程读取并清理测试数据。
    private String phase;
    // 从测试启动参数读取阶段，start() 会在测试工作线程回调 onStart()。
    @Override public void onCreate(Bundle args) { phase = args.getString("phase", "read"); start(); }
    // 使用独立的 skill_store_smoke 配置文件，不覆盖用户实际的 car 技能库。
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            SharedPreferences prefs = getTargetContext().getSharedPreferences("skill_store_smoke", 0);
            // 写入带有转弯、直行、倒车转弯和不同油门的三帧技能，并检查重名被拒绝。
            if ("write".equals(phase)) {
                prefs.edit().clear().commit();
                SkillStore store = new SkillStore(prefs);
                check(store.add("转弯测试", Arrays.asList(
                    MotionEngine.Frame.fromMicros("q,60", 123456), new MotionEngine.Frame("f,40", 456),
                    new MotionEngine.Frame("c,55", 789))), "save");
                check(!store.add("转弯测试", Arrays.asList(new MotionEngine.Frame("f,20", 100))), "duplicate rejected");
            } else {
                // 重新构建存储对象，逐项核对跨进程保存的名称、指令和时长，再验证删除确实写入磁盘。
                SkillStore store = new SkillStore(prefs);
                check(store.all().size() == 1, "survives process restart");
                SkillStore.Skill skill = store.all().get(0);
                check(skill.name.equals("转弯测试"), "name retained");
                List<MotionEngine.Frame> frames = skill.frames;
                check(frames.size() == 3 && frames.get(0).command.equals("q,60") && frames.get(0).ms == 123 && frames.get(0).micros == 123456, "turn and speed retained");
                check(frames.get(1).command.equals("f,40") && frames.get(1).ms == 456, "drive state retained");
                check(frames.get(2).command.equals("c,55") && frames.get(2).ms == 789, "reverse turn retained");
                check(store.remove(skill) && new SkillStore(prefs).all().isEmpty(), "delete persisted");
                prefs.edit().clear().commit();
                // 直接验证线上协议解析：保留微秒、拒绝缺帧，兼容旧技能的毫秒字段。
                MotionEngine.Reply reply = BoardProtocol.parse("{\"protocol\":2,\"state\":\"export\",\"session\":12,\"reason\":\"none\",\"count\":1,\"frames\":[{\"command\":\"q,60\",\"us\":123456}]}");
                check(reply.frames.get(0).micros == 123456, "wire microseconds retained");
                boolean rejected = false;
                try { BoardProtocol.parse("{\"protocol\":2,\"state\":\"export\",\"session\":12,\"reason\":\"none\",\"count\":2,\"frames\":[]}"); }
                catch (org.json.JSONException expected) { rejected = true; }
                check(rejected, "incomplete transfer rejected");
                prefs.edit().putString("skills_v1", "[{\"name\":\"legacy\",\"frames\":[{\"command\":\"f,40\",\"ms\":321}]}]").commit();
                check(new SkillStore(prefs).all().get(0).frames.get(0).micros == 321000, "legacy milliseconds migrated");
                prefs.edit().clear().commit();
            }
            // 把测试结果返回给 am instrument，便于在电脑端确认通过。
            result.putString("stream", "PASS: skill persistence " + phase + "\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(0, result);
        }
    }
    // 任何检查失败都抛异常，再通过测试结果返回失败原因。
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
