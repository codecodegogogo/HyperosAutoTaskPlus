# 使用与 build.ps1 相同的 JDK / jadx Gson，测试不连接手机，不触发真实设备操作或认证。
param([switch]$SkipBuild)
$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$classesPath = Join-Path $projectRoot 'build\out\classes'
$testPath = Join-Path $projectRoot 'build\out\tests'
$jdkPath = 'D:\Environment\java\bin'
$jadxPath = 'D:\Environment\jadx\lib\jadx-1.5.6-all.jar'
if (-not $SkipBuild) {
    $checkedOutput = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build\out'))
    if (-not $checkedOutput.StartsWith([IO.Path]::GetFullPath($projectRoot) + [IO.Path]::DirectorySeparatorChar)) {
        throw '构建输出目录不在工作区内'
    }
    & (Join-Path $projectRoot 'build.ps1')
}
if (-not (Test-Path -LiteralPath $classesPath)) { throw '请先运行 build.ps1' }
New-Item -ItemType Directory -Path $testPath -Force | Out-Null
$testClasspath = "$classesPath;$projectRoot\build\out\stubs;$projectRoot\build\libs\android-4.1.1.4.jar;$jadxPath"
$testSources = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tests') -Filter '*.java' | ForEach-Object FullName
& "$jdkPath\javac.exe" --release 11 -encoding UTF-8 -cp $testClasspath -d $testPath $testSources
if ($LASTEXITCODE -ne 0) { throw '测试编译失败' }
& "$jdkPath\java.exe" -cp "$testPath;$testClasspath" io.github.codecodegogogo.HyperosAutoTaskPlus.inject.DeviceActionTest
if ($LASTEXITCODE -ne 0) { throw '设备操作回归测试失败' }
& "$jdkPath\java.exe" -cp "$testPath;$testClasspath" io.github.codecodegogogo.HyperosAutoTaskPlus.inject.SmsMessageTest
if ($LASTEXITCODE -ne 0) { throw '短信位置回归测试失败' }
& "$jdkPath\java.exe" -cp "$testPath;$testClasspath" io.github.codecodegogogo.HyperosAutoTaskPlus.inject.SmsSentHistoryTest
if ($LASTEXITCODE -ne 0) { throw '短信发送记录回归测试失败' }
& "$jdkPath\java.exe" -cp "$testPath;$testClasspath" io.github.codecodegogogo.HyperosAutoTaskPlus.inject.MmsPduTest
if ($LASTEXITCODE -ne 0) { throw '彩信协议回归测试失败' }
& "$jdkPath\java.exe" -cp "$testPath;$testClasspath" io.github.codecodegogogo.HyperosAutoTaskPlus.inject.EmailConfigTest
if ($LASTEXITCODE -ne 0) { throw '邮件配置回归测试失败' }
& "$jdkPath\java.exe" -cp "$testPath;$testClasspath" io.github.codecodegogogo.HyperosAutoTaskPlus.inject.SmtpClientTest
if ($LASTEXITCODE -ne 0) { throw '邮件协议回归测试失败' }
& "$jdkPath\java.exe" -cp "$testPath;$testClasspath" io.github.codecodegogogo.HyperosAutoTaskPlus.inject.UnlockFailureTest
if ($LASTEXITCODE -ne 0) { throw '解锁失败条件回归测试失败' }
