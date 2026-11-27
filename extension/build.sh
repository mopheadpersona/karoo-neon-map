#!/bin/sh
# Builds the extension:  sh extension/build.sh   ->  extension/app/build/outputs/apk/debug/app-debug.apk
# Needs Java 17 or newer and the Android SDK (platform 34, build tools 34.0.0), found through
# ANDROID_HOME or sdk.dir in extension/local.properties. Gradle comes with the wrapper.
cd "$(dirname "$0")" || exit 1
if [ -n "$ANDROID_HOME" ] && [ ! -f local.properties ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi
# A folder that iCloud syncs can leave copies named "x 2.xml" in the build output; the build
# stops on them. Only build output is touched here.
find app/build karoo-ext-lib/build -name "* 2*" -delete 2>/dev/null
exec ./gradlew --console=plain "${@:-assembleDebug}"
