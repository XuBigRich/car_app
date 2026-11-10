package local.car;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 用真实本地 HTTP 服务验证请求体、响应读取、长连接和异常响应，不触发任何真实小车。 */
public final class DriveHttpTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Integer> ports = new ArrayList<>();
        server.createContext("/api/drive", exchange -> {
            ports.add(exchange.getRemoteAddress().getPort());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                int value; while ((value = in.read()) != -1) body.write(value);
            }
            String command = new String(body.toByteArray(), StandardCharsets.US_ASCII);
            byte[] reply = (command.equals("s,0") ? "OK" : command.equals("f,40") ? "WRONG" : "error").getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(command.equals("bad") ? 400 : 200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(reply); }
        });
        server.start();
        try {
            URL url = new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/api/drive");
            // 连续多次请求，确认响应完整消费后，同一连接可被平台复用，不占满服务器连接槽。
            for (int i = 0; i < 30; i++) check(DriveHttp.send((HttpURLConnection) url.openConnection(), "s,0"), "Valid OK response");
            check(new HashSet<>(ports).size() < 30, "Connections must be reused after consuming response");
            check(!DriveHttp.send((HttpURLConnection) url.openConnection(), "f,40"), "200 with wrong body rejected");
            boolean rejected = false;
            try { DriveHttp.send((HttpURLConnection) url.openConnection(), "bad"); }
            catch (DriveHttp.StatusException error) { rejected = error.code == 400; }
            check(rejected, "400 error consumed and rejected");
            check(DriveHttp.send((HttpURLConnection) url.openConnection(), "s,0"), "Can recover after rejected response");
            System.out.println("PASS: HTTP response handling and reuse (" + new HashSet<>(ports).size() + " connections / " + ports.size() + " requests)");
        } finally { server.stop(0); }
    }
}
