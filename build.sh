#!/usr/bin/env bash
# 本地构建 LSPosed 模块，不依赖 Android SDK / Gradle / 任何额外下载。
# 工具链: javac + keytool + jarsigner(JDK 自带) + D8(jadx 自带) + aapt2(apktool 自带)
#
# 签名说明: jarsigner 只能做 v1 签名，Android 对 targetSdk>=30 强制要求 v2，
# 所以 Manifest 里 targetSdk 固定为 29。LSPosed 模块不受 targetSdk 影响。
#
# module/stubs 是安全中心类的编译桩（TaskItem/LunchAppItem），只进 javac 的 classpath，
# 不进 dex；运行时由注入到安全中心 ClassLoader 的模块代码直接使用安全中心自己的类。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD="$ROOT/module"
OUT="$ROOT/build/out"
LIBS="$ROOT/build/libs"
TOOLS="$ROOT/build/tools"
JADX_JAR="/d/Environment/jadx/lib/jadx-1.5.6-all.jar"

# JDK 用绝对路径，不依赖 PATH（用户 shell 里只有 java 没有 javac）
JDK="/d/Environment/java/bin"
JAVAC="$JDK/javac"
JAVA="$JDK/java"
KEYTOOL="$JDK/keytool"
JARSIGNER="$JDK/jarsigner"
for t in "$JAVAC" "$JAVA" "$KEYTOOL" "$JARSIGNER"; do
  [ -x "$t" ] || [ -x "$t.exe" ] || { echo "找不到 $t"; exit 1; }
done

ANDROID_JAR="$LIBS/android-4.1.1.4.jar"
XPOSED_JAR="$LIBS/xposed-api-82.jar"

KEYSTORE="$ROOT/build/debug.p12"
STOREPASS="android"
ALIAS="debug"

APK_NAME="HyperAutoTaskEnhance.apk"

# javac 的 -cp 多段拼接时 MSYS 不会自动转换路径，这里显式转成 Windows 形式并选对分隔符
if command -v cygpath >/dev/null 2>&1; then
  winpath() { cygpath -w "$1"; }
  CP_SEP=";"
else
  winpath() { printf '%s' "$1"; }
  CP_SEP=":"
fi

rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "=== 1/6 javac ==="
# 先编译桩（只进 classpath），再编译模块本体
"$JAVAC" --release 11 -nowarn -encoding UTF-8 \
  -cp "$(winpath "$ANDROID_JAR")" \
  -d "$OUT/stubs" \
  $(find "$MOD/stubs" -name '*.java')
"$JAVAC" --release 11 -nowarn -encoding UTF-8 \
  -cp "$(winpath "$ANDROID_JAR")${CP_SEP}$(winpath "$XPOSED_JAR")${CP_SEP}$(winpath "$OUT/stubs")" \
  -d "$OUT/classes" \
  $(find "$MOD/src" -name '*.java')

echo "=== 2/6 d8 ==="
"$JAVA" -cp "$JADX_JAR" com.android.tools.r8.D8 \
  --release --min-api 29 \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')
ls -l "$OUT/dex/classes.dex"

echo "=== 3/6 aapt2 compile + link ==="
"$TOOLS/aapt2.exe" compile --dir "$MOD/res" -o "$OUT/res.zip"
"$TOOLS/aapt2.exe" link \
  -I "$ANDROID_JAR" \
  --manifest "$MOD/AndroidManifest.xml" \
  -A "$MOD/assets" \
  --min-sdk-version 29 --target-sdk-version 29 \
  -o "$OUT/apk/unsigned.apk" \
  "$OUT/res.zip"

echo "=== 4/6 add classes.dex ==="
python - "$OUT/apk/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    print("entries:", z.namelist())
PY

echo "=== 5/6 keystore ==="
if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=HyperosAuto Debug, O=HyperosAuto" >/dev/null 2>&1
  echo "生成 $KEYSTORE"
else
  echo "复用 $KEYSTORE"
fi

echo "=== 6/6 jarsigner (v1) ==="
cp "$OUT/apk/unsigned.apk" "$OUT/apk/$APK_NAME"
"$JARSIGNER" \
  -keystore "$KEYSTORE" -storetype PKCS12 \
  -storepass "$STOREPASS" -keypass "$STOREPASS" \
  -sigalg SHA256withRSA -digestalg SHA-256 \
  "$OUT/apk/$APK_NAME" "$ALIAS"
"$JARSIGNER" -verify -certs "$OUT/apk/$APK_NAME" | tail -3

echo
echo "=== DONE ==="
ls -l "$OUT/apk/"
echo
echo "APK: $OUT/apk/$APK_NAME"
