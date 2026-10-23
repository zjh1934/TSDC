#!/usr/bin/env bash
# 本地校验用构建脚本（不进交付物，也可删掉）：
# 沙箱里没有 Gradle／网络 Maven，用 SDK 自带工具走一遍完整链路，确认代码真的能编译。
#
# 路径全部按脚本自身位置推导，这样把源码包解压到任何目录下都能直接跑。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT=$SDK/build-tools/30.0.3
AJ=$SDK/platforms/android-30/android.jar
SRC="$HERE/app/src/main"
OUT="${OUT_DIR:-/tmp/lbbuild}"
rm -rf "$OUT"; mkdir -p "$OUT"/res-zip "$OUT"/gen "$OUT"/obj "$OUT"/dex

echo "== 1/5 aapt2 compile =="
$BT/aapt2 compile --dir "$SRC"/res -o "$OUT"/res-zip/res.zip

echo "== 2/5 aapt2 link =="
$BT/aapt2 link -I "$AJ" \
  --manifest "$SRC"/AndroidManifest.xml \
  --java "$OUT"/gen -o "$OUT"/base.apk \
  --min-sdk-version 21 --target-sdk-version 30 \
  "$OUT"/res-zip/res.zip

echo "== 3/5 javac =="
find "$SRC"/java "$OUT"/gen -name "*.java" > "$OUT"/sources.txt
javac -nowarn -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$AJ" -classpath "$OUT"/obj \
  -d "$OUT"/obj @"$OUT"/sources.txt

echo "== 4/5 d8 =="
cd "$OUT"/obj
find . -name "*.class" > "$OUT"/classes.txt
$BT/d8 --lib "$AJ" --release --output "$OUT"/dex @"$OUT"/classes.txt

echo "== 5/5 pack =="
mkdir -p "$OUT"/apk
cp "$OUT"/base.apk "$OUT"/apk/aligned.apk
cd "$OUT"/dex
zip -q -r "$OUT"/apk/aligned.apk classes.dex
$BT/zipalign -f 4 "$OUT"/apk/aligned.apk "$OUT"/apk/app.apk
keytool -genkeypair -v -keystore "$OUT"/debug.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || true
$BT/apksigner sign --ks "$OUT"/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out "$OUT"/apk/signed.apk "$OUT"/apk/app.apk
echo "OK -> $OUT/apk/signed.apk"
ls -la "$OUT"/apk/signed.apk
