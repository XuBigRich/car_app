package local.car;

import android.net.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class CarLink {
    static boolean send(ConnectivityManager manager, String command) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://192.168.4.1/api/drive");
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    connection = (HttpURLConnection) network.openConnection(url, java.net.Proxy.NO_PROXY);
                    break;
                }
            }
            if (connection == null) return false;
            connection.setConnectTimeout(300);
            connection.setReadTimeout(350);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            byte[] body = command.getBytes(StandardCharsets.US_ASCII);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            return connection.getResponseCode() == 200;
        } catch (java.io.IOException | RuntimeException failure) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

