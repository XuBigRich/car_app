package local.car;

import android.app.*;
import android.content.Intent;
import android.net.Network;
import android.os.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/** ESP 实物接口检查：只发送停止/零油门帧，不请求前进、倒车、转向或刹车。 */
public final class BoardWireInstrumentation extends Instrumentation {
    // 系统连接弹窗可能引起页面重建，必须跟踪最新 Activity，不能持有已经销毁页面的旧 Network。
    private volatile Activity latestActivity;
    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity instanceof RemoteActivity) latestActivity = activity;
    }
    @Override public void onCreate(Bundle arguments) { start(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static MotionEngine.Reply request(Network network, String path, String body) {
        MotionEngine.Reply reply = CarLink.exchange(network, path, body);
        // 新建 Wi-Fi 会话先允许 ARP/DHCP 收尾；相同操作带任务编号，重试不会重启执行。
        for (int attempt = 0; attempt < 4 && !reply.ok && !reply.unsupported; attempt++) {
            SystemClock.sleep(100);
            reply = CarLink.exchange(network, path, body);
        }
        if (!reply.ok || reply.unsupported) {
            try {
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) network.openConnection(new java.net.URL("http://192.168.4.1" + path), java.net.Proxy.NO_PROXY);
                // 诊断探针采用更长等待，区分冷连接慢与板子根本没有响应；只用于零油门测试。
                connection.setConnectTimeout(3000); connection.setReadTimeout(1000);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.getOutputStream().write(bytes);
                int code = connection.getResponseCode();
                java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    byte[] chunk = new byte[1024]; int count;
                    while (stream != null && (count = stream.read(chunk)) != -1) {
                        if (received.size() > 8192) break;
                        received.write(chunk, 0, count);
                    }
                } finally { connection.disconnect(); }
                String text = received.toString("UTF-8");
                if (code != 200) throw new AssertionError("Board HTTP " + code + ": " + text);
                try { BoardProtocol.parse(text); }
                catch (Exception parse) { throw new AssertionError("Invalid board reply: " + text + " / " + parse); }
                throw new AssertionError("Initial request failed; repeat succeeded: " + text);
            } catch (java.io.IOException error) { throw new AssertionError("Board HTTP error: " + error); }
        }
        return reply;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity launched = startActivitySync(new Intent(getTargetContext(), RemoteActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity current = launched;
            Network selected = null;
            for (int i = 0; i < 800; i++) {
                current = latestActivity == null ? launched : latestActivity;
                CarWifi wifi = (CarWifi) field(current, "wifi");
                if (wifi.network() != null && current.hasWindowFocus()) { selected = wifi.network(); break; }
                SystemClock.sleep(150);
            }
            final Activity activity = current;
            Network network = selected;
            check(network != null, "No car Wi-Fi network; approve the system connection prompt first");
            AtomicReference<Throwable> error = new AtomicReference<>();
            runOnMainSync(() -> {
                try { ((MotionEngine) field(activity, "engine")).enable(false); }
                catch (Throwable failure) { error.set(failure); }
            });
            if (error.get() != null) throw new AssertionError(error.get());
            // 等待页面原有的取消请求收尾，此后只有本测试发保活，才能真正测试板载超时。
            SystemClock.sleep(600);
            MotionEngine.Reply idle = request(network, "/api/motion", "cancel");
            check("idle".equals(idle.state), "ESP cancelled and idle");
            int token = 100000 + (int)(SystemClock.uptimeMillis() % 100000000);
            MotionEngine.Reply recording = request(network, "/api/motion", "start," + token + ",r");
            check("recording".equals(recording.state), "Board owns recording");
            request(network, "/api/drive", "s,0");
            MotionEngine.Reply empty = request(network, "/api/motion", "end," + token);
            check("idle".equals(empty.state) && "empty".equals(empty.reason), "Stationary recording rejected");

            String stopSkill = (token + 1) + "\ns,0,200000\n";
            request(network, "/api/sequence", stopSkill);
            SystemClock.sleep(300);
            MotionEngine.Reply done = request(network, "/api/motion", "ping");
            check("idle".equals(done.state) && "done".equals(done.reason), "Board finishes zero-output sequence locally");
            MotionEngine.Reply duplicate = request(network, "/api/sequence", stopSkill);
            check("idle".equals(duplicate.state), "Repeated upload does not restart completed skill");

            request(network, "/api/sequence", (token + 2) + "\ns,0,2000000\n");
            SystemClock.sleep(1000);
            MotionEngine.Reply timeout = request(network, "/api/motion", "ping");
            check("idle".equals(timeout.state) && "timeout".equals(timeout.reason), "Board heartbeat timeout works independently of phone");
            request(network, "/api/sequence", (token + 3) + "\ns,0,2000000\n");
            check("idle".equals(request(network, "/api/motion", "cancel").state), "Cancel stops and clears active sequence");
            result.putString("stream", "PASS: real ESP protocol 2, board recording, local zero-output playback, idempotent upload, watchdog and cancel; no motor motion requested\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n"); finish(0, result);
        }
    }
}
