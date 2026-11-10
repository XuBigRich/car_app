package local.car;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** 一次完整的控制请求；不依赖 Android，便于用真实本地 HTTP 服务测试响应消费和连接复用。 */
final class DriveHttp {
    static final class StatusException extends IOException {
        final int code;
        StatusException(int code) { super("HTTP " + code); this.code = code; }
    }
    // 原有手动接口仍要求返回 OK；新增操作接口返回完整 JSON，不在手机端推算时间轴。
    static boolean send(HttpURLConnection connection, String command) throws IOException {
        return "OK".equals(post(connection, command).trim());
    }
    static String post(HttpURLConnection connection, String command) throws IOException {
        try {
            connection.setConnectTimeout(250);
            connection.setReadTimeout(250);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            byte[] body = command.getBytes(StandardCharsets.US_ASCII);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int code = connection.getResponseCode();
            // 之前只读取响应码就断开：未读完的响应体会妨碍底层连接复用，频繁建连会增加延迟。
            // 必须完整消费并关闭响应流，再 disconnect，让平台可以回收/复用已完成的连接。
            InputStream response = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (response == null) throw new IOException("Missing response");
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            try (InputStream in = response) {
                byte[] chunk = new byte[64];
                int count;
                while ((count = in.read(chunk)) != -1) {
                    if (received.size() + count > 196608) throw new IOException("Unexpected control response size");
                    received.write(chunk, 0, count);
                }
            }
            // 小车协议明确返回 OK；不能把其他网页、重定向入口或无效响应当成小车已接收命令。
            if (code != 200) throw new StatusException(code);
            return new String(received.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
