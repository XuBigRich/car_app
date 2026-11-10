# 引擎测试入口：只运行虚拟时钟和模拟网络，不连接手机，也不会让小车行驶。
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
New-Item -ItemType Directory -Force build/engine-tests | Out-Null
# 单独编译纯 Java 引擎和测试，不依赖 Android 界面与 SDK。
# 执行测试主函数；断言失败时退出码非0，脚本将报错。
& javac --release 8 -encoding UTF-8 -d build/engine-tests app/src/main/java/local/car/MotionEngine.java app/src/main/java/local/car/ManualControls.java app/src/main/java/local/car/DriveHttp.java tests/MotionEngineTest.java tests/ManualControlsTest.java tests/DriveHttpTest.java
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp build/engine-tests local.car.MotionEngineTest
if ($LASTEXITCODE -ne 0) { throw 'Motion engine tests failed' }
& java -cp build/engine-tests local.car.ManualControlsTest
if ($LASTEXITCODE -ne 0) { throw 'Manual controls tests failed' }
& java -cp build/engine-tests local.car.DriveHttpTest
if ($LASTEXITCODE -ne 0) { throw 'HTTP transport tests failed' }
