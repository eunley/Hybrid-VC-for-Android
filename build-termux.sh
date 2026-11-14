#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
LIBS_DIR=${LIBS_DIR:-$ROOT/libs}
ANDROID_JAR=${ANDROID_JAR:-$LIBS_DIR/android-34/android.jar}
ORT_JAR=${ORT_JAR:-$LIBS_DIR/ort/classes.jar}
R8_JAR=${R8_JAR:-$LIBS_DIR/r8.jar}
ORT_NATIVE_DIR=${ORT_NATIVE_DIR:-$LIBS_DIR/ort/jni/arm64-v8a}
QNN_NATIVE_DIR=${QNN_NATIVE_DIR:-$LIBS_DIR/qnnsdk/jni/arm64-v8a}
AAPT=${AAPT:-aapt}
for tool in "$AAPT" javac java clang zip zipalign apksigner keytool python3; do command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }; done
for f in "$ANDROID_JAR" "$ORT_JAR" "$R8_JAR"; do test -f "$f" || { echo "Missing dependency: $f" >&2; exit 1; }; done
python3 scripts/verify-qnn.py "$QNN_NATIVE_DIR"
for d in src res; do test -d "$d" || { echo "Missing $d: run scripts/sync-final.py after editing finishes" >&2; exit 1; }; done
# Build only in this checkout; never modify tracked assets or external libraries.
OUT=$ROOT/.build
rm -rf "$OUT"
mkdir -p "$OUT"/{classes,gen,dex,hout,hdex,lib/arm64-v8a}
cp -R assets "$OUT/assets"
clang -O3 -fPIC -shared -DRNNOISE_BUILD ${PREFIX:+-I"$PREFIX/include"} -Inative/rnnoise/include -Inative/rnnoise/src native/rnnoise_jni.c native/rnnoise/src/{denoise,rnn,pitch,kiss_fft,celt_lpc,nnet,nnet_default,parse_lpcnet_weights,rnnoise_data,rnnoise_tables}.c -lm -o "$OUT/lib/arm64-v8a/libsvc_rnnoise.so"
javac -encoding UTF-8 -source 8 -target 8 -cp "$ANDROID_JAR" -d "$OUT/hout" hsrc/VmicPolicy.java
find "$OUT/hout" -name '*.class' > "$OUT/helper.list"
java -Xmx800m -cp "$R8_JAR" com.android.tools.r8.D8 --lib "$ANDROID_JAR" --min-api 30 --output "$OUT/hdex" @"$OUT/helper.list"
cp "$OUT/hdex/classes.dex" "$OUT/assets/policy.dex"
# Templates are required for fresh installs and automatic restoration.
TEMPLATE_ASSETS_DIR=${TEMPLATE_ASSETS_DIR:-$ROOT/assets/tpl}
for f in enc.onnx enc.onnx.data dec.onnx; do
 test -s "$TEMPLATE_ASSETS_DIR/$f" || { echo "Missing import template: $TEMPLATE_ASSETS_DIR/$f" >&2; exit 1; }
done
if [[ "$TEMPLATE_ASSETS_DIR" != "$ROOT/assets/tpl" ]]; then cp -R "$TEMPLATE_ASSETS_DIR" "$OUT/assets/tpl"; fi
# Optional private model directory; do not publish it with the source.
if [[ -n ${MODEL_ASSETS_DIR:-} ]]; then cp -R "$MODEL_ASSETS_DIR" "$OUT/assets/models"; fi
"$AAPT" package -f -0 onnx -0 bin -M AndroidManifest.xml -S res -A "$OUT/assets" -I "$ANDROID_JAR" -F "$OUT/app.apk" --min-sdk-version 26 --target-sdk-version 34 -J "$OUT/gen"
# javac argument-file quotes support checkout paths containing spaces.
python3 - <<'PY2'
from pathlib import Path
p=Path('.build'); fs=list(Path('src').rglob('*.java'))+list((p/'gen').rglob('*.java'))
(p/'sources.list').write_text('\n'.join('"'+str(f).replace('\\','\\\\').replace('"','\\"')+'"' for f in fs))
PY2
javac -encoding UTF-8 -source 8 -target 8 -cp "$ANDROID_JAR:$ORT_JAR" -d "$OUT/classes" @"$OUT/sources.list"
find "$OUT/classes" -name '*.class' > "$OUT/classes.list"
java -Xmx1500m -cp "$R8_JAR" com.android.tools.r8.D8 --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" @"$OUT/classes.list" "$ORT_JAR"
cp "$ORT_NATIVE_DIR"/*.so "$OUT/lib/arm64-v8a/"
# 多 HTP 架构：V69/73/75/79/81/85（完整官方 QAIRT 2.51.0.260929；SHA-256 校验后打包）
cp "$QNN_NATIVE_DIR/libQnnHtp.so" "$QNN_NATIVE_DIR/libQnnHtpPrepare.so" "$QNN_NATIVE_DIR/libQnnSystem.so" "$QNN_NATIVE_DIR/libQnnGpu.so" "$OUT/lib/arm64-v8a/"
for v in 69 73 75 79 81 85; do
  cp "$QNN_NATIVE_DIR/libQnnHtpV${v}Skel.so" "$QNN_NATIVE_DIR/libQnnHtpV${v}Stub.so" "$OUT/lib/arm64-v8a/"
done
cp "$OUT"/dex/classes*.dex "$OUT/"
(cd "$OUT"; zip -q app.apk classes*.dex; zip -qr app.apk lib)
zipalign -f 4 "$OUT/app.apk" "$OUT/aligned.apk"
if [[ -n ${KEYSTORE_PATH:-} ]]; then
 : "${KEYSTORE_ALIAS:?Set KEYSTORE_ALIAS}" "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD}"
 export KEYSTORE_PASSWORD KEY_PASSWORD=${KEY_PASSWORD:-$KEYSTORE_PASSWORD}
else
 KEYSTORE_PATH=$ROOT/.private/debug.jks; KEYSTORE_ALIAS=debug
 export KEYSTORE_PASSWORD=android KEY_PASSWORD=android
 mkdir -p .private; chmod 700 .private
 if [[ ! -f $KEYSTORE_PATH ]]; then
  (umask 077; keytool -genkeypair -keystore "$KEYSTORE_PATH" -alias "$KEYSTORE_ALIAS" -keyalg RSA -keysize 2048 -validity 3650 -storepass:env KEYSTORE_PASSWORD -keypass:env KEY_PASSWORD -dname 'CN=Local Debug')
 fi
fi
apksigner sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEYSTORE_ALIAS" --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD --out "$OUT/hybrid-vc.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/hybrid-vc.apk"
echo "Built $OUT/hybrid-vc.apk (models and QNN redistribution still require permission)"
