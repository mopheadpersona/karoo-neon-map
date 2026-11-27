#!/bin/sh
# Builds the optional colour overlay for the ride app's route lines:
#   sh overlay/build.sh   ->  overlay/build/neon-colours.apk
# Needs the Android SDK (ANDROID_HOME, build tools 34.0.0, platform 34) and Java.
# It is signed with extension/keystore/local.jks if that exists, else with Android's debug key.
# Put it on the Karoo:   adb install -r overlay/build/neon-colours.apk
# Then restart the Karoo once, and switch it on:
#                        adb shell cmd overlay enable local.karoo.gtavicolours
# Take it off again:     adb uninstall local.karoo.gtavicolours
set -e
cd "$(dirname "$0")"
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
BT="$ANDROID_HOME/build-tools/34.0.0"
JAR="$ANDROID_HOME/platforms/android-34/android.jar"
if [ -f ../extension/keystore/local.jks ]; then
    KS=../extension/keystore/local.jks; ALIAS=gtavimap; PASS=gtavimap
else
    KS="$HOME/.android/debug.keystore"; ALIAS=androiddebugkey; PASS=android
fi
rm -rf build && mkdir -p build
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --out build/neon-colours.apk build/aligned.apk
rm -f build/unsigned.apk build/aligned.apk build/res.zip build/*.idsig
ls -l build/neon-colours.apk
