package local.car;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;

// 小车热点连接管理：新系统使用指定网络请求，旧系统使用保存的 Wi-Fi 配置。
// 此类的 start/stop 和回调处理在主线程运行，网络对象通过 volatile 提供给发送线程。
final class CarWifi {
    // 通知界面连接提示；lost=true 时界面取消未完成的录制或自动动作。
    interface Listener { void changed(String message, boolean lost); }
    // 权限请求编号，供 Activity 的 onRequestPermissionsResult 识别本次回调。
    static final int PERMISSION_REQUEST = 41;
    // 目标热点名称与密码，需与 ESP32 固件中的热点配置保持一致。
    private static final String SSID = "Control-Car", PASSWORD = "car123456";
    // Activity 用于申请系统权限；ConnectivityManager 用于申请和释放指定网络。
    private final Activity activity;
    private final ConnectivityManager manager;
    // 把连接状态通知界面，供状态栏显示和动作取消使用。
    private final Listener listener;
    // 系统网络回调可能在后台线程，统一投递到主线程后再操作连接状态和界面。
    private final Handler ui = new Handler(Looper.getMainLooper());
    // 保存当前请求的回调，避免重复请求，并在释放时用同一个对象注销。
    private ConnectivityManager.NetworkCallback callback;
    // 当前小车网络；volatile 保证后台 HTTP 线程能看见主线程更新后的引用。
    private volatile Network network;
    // running 表示允许连接；permissionPending 防止权限弹窗尚未结束时再次申请。
    private boolean running, permissionPending;
    // 连接请求代号；每次释放或新建请求都会变化，使旧回调无法覆盖新连接。
    private int generation;
    // 复用的重连任务，断连后延迟3秒再请求；stop() 可以移除它。
    private final Runnable retry = this::request;
    // 创建连接管理器，此时还不申请权限或切换网络，由 start() 主动启动。
    CarWifi(Activity activity, Listener listener) {
        this.activity = activity; this.listener = listener;
        manager = activity.getSystemService(ConnectivityManager.class);
    }
    // 只暴露指定的小车网络；HTTP 请求直接在此 Network 上建立连接。
    Network network() { return network; }
    // 页面启动或用户点“连接小车”时发起请求；已有请求时 request() 会避免重复申请。
    void start() { running = true; request(); }
    // 已有可用 Wi-Fi 时只让控制层重新确认接口，不主动断开热点制造新的中断。
    void reconnect() {
        running = true;
        if (network != null) listener.changed("小车 Wi-Fi 已连接，正在重试通信", false);
        else request();
    }
    // 停止自动连接，移除延迟重试，并释放当前请求。
    void stop() { running = false; ui.removeCallbacks(retry); release(); }
    // 先使旧请求失效，再清空网络并注销回调；已超时自动注销时允许忽略重复注销异常。
    private void release() {
        generation++;
        network = null;
        if (callback != null) {
            try { manager.unregisterNetworkCallback(callback); } catch (IllegalArgumentException ignored) {}
            callback = null;
        }
    }
    // 权限弹窗结束后重新检查实际授权，拒绝时只提示重试，不反复弹窗。
    void permissionResult() {
        permissionPending = false;
        if (running && granted()) request();
        else listener.changed("请允许附近设备／定位权限，再点“连接小车”", false);
    }
    // Android 13 起用附近 Wi-Fi 设备权限；更早版本需要精确定位权限来连接指定热点。
    private String permission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }
    // 每次请求前检查权限，防止用户在系统设置中撤销权限后继续调用受保护接口。
    private boolean granted() { return activity.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED; }
    // 连接入口：先处理权限，再按 Android 版本选择新旧连接方式。
    private void request() {
        if (!running || callback != null || permissionPending) return;
        if (!granted()) {
            permissionPending = true;
            // 旧系统同时申请精确和粗略定位，兼容 Android 12 的权限申请规则。
            String[] permissions = Build.VERSION.SDK_INT >= 33 ? new String[]{permission()} :
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
            activity.requestPermissions(permissions, PERMISSION_REQUEST);
            return;
        }
        if (Build.VERSION.SDK_INT < 29) { legacy(); return; }
        // 把本次请求代号捕获到回调中；之后所有回调都先核对代号和 running 状态。
        final int token = ++generation;
        callback = new ConnectivityManager.NetworkCallback() {
            // 系统已提供匹配热点的 Network；此时仅表示 Wi-Fi 可用，还需 HTTP 响应确认小车通信。
            @Override public void onAvailable(Network available) {
                ui.post(() -> {
                    if (!running || generation != token) return;
                    network = available;
                    listener.changed("已连接小车 Wi-Fi，正在确认通信", false);
                });
            }
            // 已连上的网络丢失：先通知界面取消动作，再释放旧请求，3秒后重新尝试连接。
            @Override public void onLost(Network lost) {
                ui.post(() -> {
                    if (!running || generation != token) return;
                    // 若系统刚切换到同一请求的新 Network，旧 Network 的迟到断连不能清掉新连接。
                    if (network != null && !network.equals(lost)) return;
                    network = null;
                    listener.changed("小车连接断开，正在重连", true);
                    release();
                    // 已连接的热点断开后，延迟3秒重新申请；释放请求时已使旧回调失效。
                    ui.postDelayed(retry, 3000);
                });
            }
            // 用户拒绝或30秒内未连上时的终止回调，释放请求后等待用户主动重试。
            @Override public void onUnavailable() {
                ui.post(() -> {
                    if (!running || generation != token) return;
                    release();
                    // 用户拒绝后不循环弹连接确认框，等待点击“连接小车”再试。
                    listener.changed("连接未成功，请开启小车后点“连接小车”重试", true);
                });
            }
        };
        try {
            // 精确匹配热点名称并提供 WPA2 密码；系统可能要求首次确认，App 不绕过授权。
            WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(SSID).setWpa2Passphrase(PASSWORD).build();
            // 仅申请 Wi-Fi 传输，并移除“必须能上互联网”的要求，因为小车热点没有互联网。
            NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier).build();
            listener.changed("正在连接 Control-Car，请允许系统连接提示", false);
            // 发起最长30秒的连接请求，结果由上面的 NetworkCallback 返回。
            manager.requestNetwork(request, callback, 30000);
        // 权限、系统开关或厂商限制可能导致请求异常；清理资源并把原因提示交给界面。
        } catch (RuntimeException failure) {
            release();
            listener.changed("无法自动连接，请检查Wi-Fi、权限及定位开关", true);
        }
    }
    // Android 10 以下的兼容路径；这里使用旧 Wi-Fi API，所以单独抑制弃用提示。
    @SuppressWarnings("deprecation") private void legacy() {
        WifiManager wifi = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // 保存旧网络用于判断断连；每轮重新核实当前热点，不能继续使用失效的网络引用。
        Network previous = network;
        network = null;
        try {
            // 旧系统 Wi-Fi 关闭时打开设置页面，由用户启用，不在后台反复请求连接。
            if (!wifi.isWifiEnabled()) {
                listener.changed("请先开启Wi-Fi，再点“连接小车”", false);
                activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                return;
            }
            // 旧 API 要求 SSID 和密码为带双引号的字符串；先找已保存的配置，找不到再新增。
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + SSID + "\"";
            config.preSharedKey = "\"" + PASSWORD + "\"";
            int id = -1;
            java.util.List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
            if (saved != null) for (WifiConfiguration item : saved) if (config.SSID.equals(item.SSID)) id = item.networkId;
            if (id < 0) id = wifi.addNetwork(config);
            if (id >= 0) wifi.enableNetwork(id, true);
            // 只有系统报告当前热点确实是 Control-Car，才挑选可用的 Wi-Fi 网络供控制请求使用。
            if (config.SSID.equals(wifi.getConnectionInfo().getSSID())) {
                for (Network candidate : manager.getAllNetworks()) {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(candidate);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { network = candidate; break; }
                }
            }
            listener.changed(network == null ? "正在连接小车 Wi-Fi" : "已连接小车 Wi-Fi", previous != null && !previous.equals(network));
            // 旧系统没有使用指定网络回调，定时复查连接情况；stop() 会移除此任务。
            ui.postDelayed(retry, 3000);
        } catch (RuntimeException failure) {
            listener.changed("请在系统Wi-Fi中连接 Control-Car", false);
        }
    }
}
