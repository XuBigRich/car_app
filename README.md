# 小车遥控器

安装 CarRemote-debug.apk，连接 Control-Car 热点（默认密码 car123456），在手机提示无互联网时选择保持连接，再打开 App。首次联调请架空车轮。

横屏支持双指同时控制油门和转向，松手释放；按住刹车时释放转向。左右绕弯约半秒，可随时按停止。真实转弯半径取决于底盘和地面。

RemoteActivity 管理界面和按键状态；CarLink 通过 Wi-Fi 向 192.168.4.1/api/drive 发指令。协议和接线见 Control_car 的 README。

可用 Android Studio 打开此目录，或在本机运行 build-apk.ps1 生成并校验调试 APK。
