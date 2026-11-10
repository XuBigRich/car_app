package local.car;

import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

// 技能的本地存储层：把动作帧列表编码成 JSON，保存在 App 私有 SharedPreferences 中。
// 界面在主线程调用此类；它不参与网络控制，也不会自动执行保存的动作。
final class SkillStore {
    // 限制技能数量，避免保存无限列表影响加载和显示。
    static final int MAX_SKILLS = 32;
    // 一个技能包含名称和按时间顺序排列的动作帧；Frame 内保存方向、油门和持续毫秒数。
    static final class Skill {
        // 技能显示名称，新增时去掉两端空白，长度最多24个 Java 字符单位。
        final String name;
        // 技能动作序列，按原顺序回放，区别于倒序执行的轨迹返回。
        final List<MotionEngine.Frame> frames;
        // 复制列表以隔离录制引擎的临时缓存；Frame 字段为 final，可安全共享单帧对象。
        Skill(String name, List<MotionEngine.Frame> frames) {
            this.name = name;
            this.frames = new ArrayList<>(frames);
        }
    }
    // 磁盘存储入口；实际数据使用 skills_v1 键，后续升级格式可使用新的版本键。
    private final SharedPreferences prefs;
    // 当前已加载的技能缓存；保存失败时会回滚缓存，保持与磁盘一致。
    private final ArrayList<Skill> skills = new ArrayList<>();
    // 创建存储对象时恢复技能，因此关闭并重新打开 App 后仍能使用已保存的记录。
    SkillStore(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            // 没有保存记录时使用空数组；最多读取32条，每条单独校验，坏数据不会拖垮整个技能库。
            JSONArray all = new JSONArray(prefs.getString("skills_v1", "[]"));
            for (int i = 0; i < Math.min(all.length(), MAX_SKILLS); i++) {
                try {
                    JSONObject item = all.getJSONObject(i);
                    String name = item.getString("name");
                    if (name.trim().isEmpty() || name.length() > 24) continue;
                    // 限制帧数并复用 Frame 构造器检查协议和时长，避免非法内容进入回放。
                    JSONArray data = item.getJSONArray("frames");
                    if (data.length() == 0 || data.length() > 6000) continue;
                    List<MotionEngine.Frame> frames = new ArrayList<>();
                    long total = 0;
                    for (int j = 0; j < data.length(); j++) {
                        JSONObject frame = data.getJSONObject(j);
                        // 新技能保留板载微秒值；旧版本技能仍按 ms 字段无损迁移到微秒单位。
                        MotionEngine.Frame value = frame.has("us") ? MotionEngine.Frame.fromMicros(frame.getString("command"), frame.getLong("us")) :
                            new MotionEngine.Frame(frame.getString("command"), frame.getLong("ms"));
                        frames.add(value); total += value.micros;
                    }
                    // 除单帧限制外，再校验整段技能总时长不超过60秒。
                    if (total <= MotionEngine.MAX_MS * 1000) skills.add(new Skill(name, frames));
                } catch (JSONException | IllegalArgumentException ignored) { /* 跳过损坏的单条技能，继续尝试恢复后面的记录。 */ }
            }
        } catch (JSONException ignored) { /* 整体 JSON 损坏时先不覆写原值，等用户主动保存时再写入新内容。 */ }
    }
    // 返回列表副本，调用方增删返回列表时不会直接改变存储缓存。
    List<Skill> all() { return new ArrayList<>(skills); }
    // 保存新技能：拒绝空名称、超长名称、重名和超出数量上限的情况，不静默覆盖旧技能。
    boolean add(String name, List<MotionEngine.Frame> frames) {
        name = name.trim();
        if (name.isEmpty() || name.length() > 24 || skills.size() >= MAX_SKILLS) return false;
        for (Skill skill : skills) if (skill.name.equals(name)) return false;
        Skill skill = new Skill(name, frames);
        skills.add(skill);
        if (persist()) return true;
        // 写盘失败时撤销刚才加入的项，避免界面显示已保存但重启后丢失。
        skills.remove(skill);
        return false;
    }
    // 删除指定技能并同步写盘；失败时按原位置恢复，避免列表与磁盘状态不一致。
    boolean remove(Skill skill) {
        int index = skills.indexOf(skill);
        if (index < 0) return false;
        skills.remove(index);
        if (persist()) return true;
        skills.add(index, skill);
        return false;
    }
    // 序列化结构：[{name: 技能名, frames: [{command: 指令, ms: 毫秒}, ...]}, ...]。
    private boolean persist() {
        JSONArray all = new JSONArray();
        try {
            for (Skill skill : skills) {
                JSONArray frames = new JSONArray();
                for (MotionEngine.Frame frame : skill.frames)
                    frames.put(new JSONObject().put("command", frame.command).put("ms", frame.ms).put("us", frame.micros));
                all.put(new JSONObject().put("name", skill.name).put("frames", frames));
            }
            // 使用 commit() 获取真实写盘结果；只有返回 true，界面才提示保存或删除成功。
            return prefs.edit().putString("skills_v1", all.toString()).commit();
        } catch (JSONException failure) { return false; }
    }
}
