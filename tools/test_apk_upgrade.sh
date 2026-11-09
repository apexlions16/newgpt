#!/usr/bin/env bash
set -euo pipefail
package=com.gflix.archive.next
old_apk=$(find old-apk -name '*.apk' -print -quit)
new_apk=$(find new-apk -name '*.apk' -print -quit)
test -n "$old_apk"
test -n "$new_apk"
signer="$ANDROID_HOME/build-tools/35.0.0/apksigner"
old_cert=$("$signer" verify --print-certs "$old_apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
new_cert=$("$signer" verify --print-certs "$new_apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
test -n "$old_cert"
test "$old_cert" = "$new_cert"
adb install "$old_apk"
# Seed realistic private settings, a saved queue, in v27.
adb shell am start -W -n "$package/com.gflix.app.MainActivity"
adb shell am force-stop "$package"
adb shell run-as "$package" mkdir -p shared_prefs files
printf '%s' '<map><string name="items">saved-queue-upgrade-test</string><string name="site">https://example.org</string></map>' | adb shell run-as "$package" sh -c '"cat > shared_prefs/upgrade_probe.xml"'
printf '%s' 'saved-admin-settings-and-source-files' | adb shell run-as "$package" sh -c '"cat > files/upgrade-probe"'
adb install -r "$new_apk"
adb shell run-as "$package" cat shared_prefs/upgrade_probe.xml | grep -q saved-queue-upgrade-test
adb shell run-as "$package" cat files/upgrade-probe | grep -q saved-admin-settings-and-source-files
adb shell am start -W -n "$package/com.gflix.app.MainActivity"
adb shell pidof "$package"
echo 'PASS: identical signing certificate, Android update install, private settings and source files retained, app launched.'

# Exercise the exact production MKV mapping and native statistics callback on Android.
adb install -r test-apk/*.apk
adb shell am instrument -w -r com.gflix.archive.next.test/androidx.test.runner.AndroidJUnitRunner | tee native-device-tests.txt
grep -q '^OK (' native-device-tests.txt
