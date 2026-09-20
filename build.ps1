# 本地构建 LSPosed 模块，不依赖 Android SDK / Gradle / 任何额外下载。
# 工具链: javac + keytool + jarsigner(JDK 自带) + D8(jadx 自带) + aapt2(apktool 自带)
#
# 签名说明: jarsigner 只能做 v1 签名，Android 对 targetSdk>=30 强制要求 v2，
# 所以 Manifest 里 targetSdk 固定为 29。LSPosed 模块不受 targetSdk 影响。
#
# module/stubs 是安全中心类的编译桩（TaskItem/LunchAppItem），只进 javac 的 classpath，
# 不进 dex；运行时由注入到安全中心 ClassLoader 的模块代码直接使用安全中心自己的类。
#
# 用法: 在 PowerShell 里执行  .\build.ps1

$ErrorActionPreference = "Stop"

$ROOT  = $PSScriptRoot
$MOD   = Join-Path $ROOT "module"
$OUT   = Join-Path $ROOT "build\out"
$LIBS  = Join-Path $ROOT "build\libs"
$TOOLS = Join-Path $ROOT "build\tools"

$JDK      = "D:\Environment\java\bin"
$JADX_JAR = "D:\Environment\jadx\lib\jadx-1.5.6-all.jar"
$AAPT2    = Join-Path $TOOLS "aapt2.exe"

$ANDROID_JAR = Join-Path $LIBS "android-4.1.1.4.jar"
$XPOSED_JAR  = Join-Path $LIBS "xposed-api-82.jar"

$KEYSTORE  = Join-Path $ROOT "build\debug.p12"
$STOREPASS = "android"
$ALIAS     = "debug"

$APK_NAME = "HyperAutoTaskEnhance.apk"

foreach ($t in @("$JDK\javac.exe", "$JDK\java.exe", "$JDK\keytool.exe", "$JDK\jarsigner.exe", $JADX_JAR, $AAPT2, $ANDROID_JAR, $XPOSED_JAR)) {
    if (-not (Test-Path $t)) { throw "找不到 $t" }
}

function Invoke-Step($name, [scriptblock]$body) {
    Write-Host "=== $name ===" -ForegroundColor Cyan
    & $body
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { throw "$name 失败 (exit $LASTEXITCODE)" }
}

if (Test-Path $OUT) { Remove-Item -Recurse -Force $OUT }
New-Item -ItemType Directory -Force "$OUT\stubs", "$OUT\classes", "$OUT\dex", "$OUT\apk" | Out-Null

Invoke-Step "1/6 javac" {
    # 先编译桩（只进 classpath），再编译模块本体
    $stubs = Get-ChildItem "$MOD\stubs" -Recurse -Filter *.java | ForEach-Object FullName
    & "$JDK\javac.exe" --release 11 -nowarn -encoding UTF-8 `
        -cp "$ANDROID_JAR" `
        -d "$OUT\stubs" `
        $stubs
    if ($LASTEXITCODE -ne 0) { throw "javac stubs 失败" }

    $srcs = Get-ChildItem "$MOD\src" -Recurse -Filter *.java | ForEach-Object FullName
    & "$JDK\javac.exe" --release 11 -nowarn -encoding UTF-8 `
        -cp "$ANDROID_JAR;$XPOSED_JAR;$OUT\stubs" `
        -d "$OUT\classes" `
        $srcs
}

Invoke-Step "2/6 d8" {
    $classes = Get-ChildItem "$OUT\classes" -Recurse -Filter *.class | ForEach-Object FullName
    & "$JDK\java.exe" -cp $JADX_JAR com.android.tools.r8.D8 `
        --release --min-api 29 `
        --lib $ANDROID_JAR `
        --output "$OUT\dex" `
        $classes
    Get-Item "$OUT\dex\classes.dex" | Select-Object Name, Length | Out-Host
}

Invoke-Step "3/6 aapt2 compile + link" {
    & $AAPT2 compile --dir "$MOD\res" -o "$OUT\res.zip"
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }
    & $AAPT2 link `
        -I $ANDROID_JAR `
        --manifest "$MOD\AndroidManifest.xml" `
        -A "$MOD\assets" `
        --min-sdk-version 29 --target-sdk-version 29 `
        -o "$OUT\apk\unsigned.apk" `
        "$OUT\res.zip"
}

Invoke-Step "4/6 add classes.dex" {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open("$OUT\apk\unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, "$OUT\dex\classes.dex", "classes.dex",
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        Write-Host ("entries: " + (($zip.Entries | ForEach-Object FullName) -join ", "))
    } finally {
        $zip.Dispose()
    }
    $global:LASTEXITCODE = 0
}

Invoke-Step "5/6 keystore" {
    if (-not (Test-Path $KEYSTORE)) {
        & "$JDK\keytool.exe" -genkeypair -v `
            -keystore $KEYSTORE -storetype PKCS12 `
            -storepass $STOREPASS -keypass $STOREPASS `
            -alias $ALIAS -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=HyperosAuto Debug, O=HyperosAuto" 2>&1 | Out-Null
        Write-Host "生成 $KEYSTORE"
    } else {
        Write-Host "复用 $KEYSTORE"
    }
    $global:LASTEXITCODE = 0
}

Invoke-Step "6/6 jarsigner (v1)" {
    Copy-Item "$OUT\apk\unsigned.apk" "$OUT\apk\$APK_NAME" -Force
    & "$JDK\jarsigner.exe" `
        -keystore $KEYSTORE -storetype PKCS12 `
        -storepass $STOREPASS -keypass $STOREPASS `
        -sigalg SHA256withRSA -digestalg SHA-256 `
        "$OUT\apk\$APK_NAME" $ALIAS
    if ($LASTEXITCODE -ne 0) { throw "jarsigner 签名失败" }
    & "$JDK\jarsigner.exe" -verify -certs "$OUT\apk\$APK_NAME" | Select-Object -Last 3
}

Write-Host ""
Write-Host "=== DONE ===" -ForegroundColor Green
Get-ChildItem "$OUT\apk" | Select-Object Name, Length | Out-Host
Write-Host "APK: $OUT\apk\$APK_NAME"
