#!/bin/bash
set -e
SDK=/home/desmond/Android/Sdk
BT=$SDK/build-tools/36.0.0
AJ=$SDK/platforms/android-36/android.jar
OUT=build
rm -rf $OUT; mkdir -p $OUT/gen $OUT/classes

echo "[1/6] aapt2 compile"
$BT/aapt2 compile --dir res -o $OUT/res.zip

echo "[2/6] aapt2 link"
$BT/aapt2 link -o $OUT/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml --java $OUT/gen \
  --min-sdk-version 32 --target-sdk-version 36 --auto-add-overlay $OUT/res.zip

echo "[3/6] javac"
find src $OUT/gen -name '*.java' > $OUT/sources.txt
javac -encoding UTF-8 -nowarn -classpath "$AJ" -d $OUT/classes @$OUT/sources.txt

echo "[4/6] d8"
$BT/d8 --min-api 32 --lib "$AJ" --output $OUT $(find $OUT/classes -name '*.class')

echo "[5/6] package dex"
( cd $OUT && zip -q base.apk classes.dex )

echo "[6/6] sign"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
  -storepass android -keypass android -alias probe -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Probe,O=Probe,C=NL" >/dev/null 2>&1
$BT/zipalign -f 4 $OUT/base.apk $OUT/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias probe --out $OUT/gptwakeprobe.apk $OUT/aligned.apk
$BT/apksigner verify $OUT/gptwakeprobe.apk && echo "SIGNATURE OK"
ls -la $OUT/gptwakeprobe.apk
