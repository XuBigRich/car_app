$ErrorActionPreference = 'Stop'
$sdk = 'D:/AndroidStudioProjects/sdk'
$bt = "$sdk/build-tools/36.0.0"
$api = "$sdk/platforms/android-36/android.jar"
function Invoke-Build($exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$exe failed: $LASTEXITCODE" }
}
New-Item -ItemType Directory -Force build/manual/classes | Out-Null
& javac --release 8 -encoding UTF-8 -cp $api -d build/manual/classes (Get-ChildItem app/src/main/java/local/car/*.java).FullName
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Invoke-Build jar cf build/manual/classes.jar -C build/manual/classes .
Invoke-Build "$bt/d8.bat" --min-api 23 --lib $api --output build/manual build/manual/classes.jar
(Get-Content app/src/main/AndroidManifest.xml -Raw).Replace('<manifest ', '<manifest package="local.car" ') | Set-Content -Encoding utf8 build/manual/AndroidManifest.xml
Invoke-Build "$bt/aapt2.exe" link -I $api --manifest build/manual/AndroidManifest.xml --min-sdk-version 23 --target-sdk-version 34 --version-code 1 --version-name 1.0 -o build/manual/unsigned.apk
Push-Location build/manual
try {
    Invoke-Build jar uf unsigned.apk classes.dex
    Invoke-Build "$bt/zipalign.exe" -f 4 unsigned.apk aligned.apk
    if (!(Test-Path debug.p12)) {
        Invoke-Build keytool -genkeypair -keystore debug.p12 -storepass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug'
    }
    Invoke-Build "$bt/apksigner.bat" sign --ks debug.p12 --ks-pass pass:android --out ../../CarRemote-debug.apk aligned.apk
    Invoke-Build "$bt/apksigner.bat" verify --print-certs ../../CarRemote-debug.apk
} finally { Pop-Location }



