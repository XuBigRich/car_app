package local.car;

import android.net.Network;
import android.util.Log;
import java.net.*;

/** 只在小车的指定 Network 上发送请求；手机不循环下发自动动作的逐帧指令。 */
final class CarLink {
    static MotionEngine.Reply exchange(Network network, String path, String body) {
        if (network == null) return MotionEngine.Reply.failed();
        try {
            HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://192.168.4.1" + path), Proxy.NO_PROXY);
            String response = DriveHttp.post(connection, body);
            if (path.equals("/api/drive")) return "OK".equals(response.trim()) ? MotionEngine.Reply.manual() : MotionEngine.Reply.failed();
            return BoardProtocol.parse(response);
        } catch (DriveHttp.StatusException error) {
            // 旧固件没有板载录制接口：明确提示升级，绝不悄悄退回手机计时录制。
            if (error.code == 404 && !path.equals("/api/drive"))
                return new MotionEngine.Reply(false, true, null, 0, "", null);
            Log.w("CarLink", error.getMessage());
            return MotionEngine.Reply.failed();
        } catch (Exception failure) {
            Log.w("CarLink", "请求失败: " + failure.getClass().getSimpleName());
            return MotionEngine.Reply.failed();
        }
    }
}
