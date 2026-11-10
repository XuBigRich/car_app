# 手动打包入口：编译 Java → 转换 DEX → 打包清单 → 对齐 → 签名 → 校验。遇到错误立即停止。
$ErrorActionPreference = 'Stop'
# 本机 Android SDK、构建工具与 Android API 类库的位置；更换电脑时需调整这些路径。
$sdk = 'D:/AndroidStudioProjects/sdk'
$bt = "$sdk/build-tools/36.0.0"
$api = "$sdk/platforms/android-36/android.jar"
# 统一调用外部构建工具，并检查退出码，防止前一步失败却继续生成不完整安装包。
function Invoke-Build($exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$exe failed: $LASTEXITCODE" }
}
# 以脚本所在目录为项目根目录，从其他目录启动时也使用相同的相对路径。
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot
# 清理旧 class 前，先校验绝对路径位于项目 build 目录，避免误删项目外的文件。
$classesPath = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build/manual/classes'))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build')) + [IO.Path]::DirectorySeparatorChar
if (!$classesPath.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid build output path' }
if (Test-Path -LiteralPath $classesPath) { Remove-Item -LiteralPath $classesPath -Recurse -Force }
New-Item -ItemType Directory -Force $classesPath | Out-Null
# 使用 Java 8 语言级别和 UTF-8 编码编译全部源码；中文注释和界面文字都按 UTF-8 读取。
& javac --release 8 -encoding UTF-8 -cp $api -d build/manual/classes (Get-ChildItem app/src/main/java/local/car/*.java).FullName
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
# 把 Java 字节码打成中间 jar，再交给 d8 转成 Android 执行的 classes.dex。
Invoke-Build jar cf build/manual/classes.jar -C build/manual/classes .
Invoke-Build "$bt/d8.bat" --min-api 23 --lib $api --output build/manual build/manual/classes.jar
# 只在生成目录给清单补上包名，源清单保持供 Android Studio 使用的格式。
(Get-Content app/src/main/AndroidManifest.xml -Raw).Replace('<manifest ', '<manifest package="local.car" ') | Set-Content -Encoding utf8 build/manual/AndroidManifest.xml
# 把清单打进未签名 APK，并明确最低系统版本、目标版本与 App 版本号。
Invoke-Build "$bt/aapt2.exe" link -I $api --manifest build/manual/AndroidManifest.xml --min-sdk-version 23 --target-sdk-version 34 --version-code 4 --version-name 2.0 -o build/manual/unsigned.apk
# 进入临时打包目录处理 APK；finally 保证退出时恢复工作目录。
Push-Location build/manual
try {
    # 把 DEX 加入 APK；zipalign 在签名前对齐文件，便于 Android 高效读取。
    Invoke-Build jar uf unsigned.apk classes.dex
    Invoke-Build "$bt/zipalign.exe" -f 4 unsigned.apk aligned.apk
    # 优先复用 Android Studio 的调试签名，让手动打包版本能覆盖同签名的已安装版本。
    $signingKey = Join-Path $env:USERPROFILE '.android/debug.keystore'
    if (!(Test-Path -LiteralPath $signingKey)) { $signingKey = Join-Path (Get-Location) 'debug.p12' }
    # 只有没有可复用的调试密钥时才创建备用密钥；覆盖安装要求前后签名一致。
    if (!(Test-Path -LiteralPath $signingKey)) {
        Invoke-Build keytool -genkeypair -keystore debug.p12 -storepass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug'
    }
    # 生成正式可安装的调试包；随后校验签名，避免交付损坏或未签名的 APK。
    Invoke-Build "$bt/apksigner.bat" sign --ks $signingKey --ks-pass pass:android --key-pass pass:android --out ../../CarRemote-debug.apk aligned.apk
    Invoke-Build "$bt/apksigner.bat" verify --print-certs ../../CarRemote-debug.apk
} finally { Pop-Location }



