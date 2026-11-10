# 仅构建手机回归测试安装包，不在此脚本中触发行驶或转向。
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$testOutput='build/board-device-tests'
$sdkPath='D:/AndroidStudioProjects/sdk'
$toolPath="$sdkPath/build-tools/36.0.0"
function Invoke-Checked($exe) { & $exe @args; if ($LASTEXITCODE -ne 0) { throw "$exe failed" } }
New-Item -ItemType Directory -Force "$testOutput/classes" | Out-Null
Invoke-Checked javac --release 8 -encoding UTF-8 -cp "$sdkPath/platforms/android-36/android.jar;build/manual/classes" -d "$testOutput/classes" tests/device/SkillStoreInstrumentation.java tests/device/ControlStabilityInstrumentation.java tests/device/BoardWireInstrumentation.java
Invoke-Checked jar cf "$testOutput/tests.jar" -C "$testOutput/classes" .
Invoke-Checked "$toolPath/d8.bat" --min-api 23 --lib "$sdkPath/platforms/android-36/android.jar" --classpath build/manual/classes.jar --output $testOutput "$testOutput/tests.jar"
Invoke-Checked "$toolPath/aapt2.exe" link -I "$sdkPath/platforms/android-36/android.jar" --manifest tests/device/AndroidManifest.xml --min-sdk-version 23 --target-sdk-version 34 -o "$testOutput/unsigned.apk"
Invoke-Checked jar uf "$testOutput/unsigned.apk" -C $testOutput classes.dex
Invoke-Checked "$toolPath/zipalign.exe" -f 4 "$testOutput/unsigned.apk" "$testOutput/aligned.apk"
Invoke-Checked "$toolPath/apksigner.bat" sign --ks (Join-Path $env:USERPROFILE '.android/debug.keystore') --ks-pass pass:android --key-pass pass:android --out "$testOutput/tests.apk" "$testOutput/aligned.apk"
