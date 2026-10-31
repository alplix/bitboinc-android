#!/bin/bash
# Publish the release APK on the website: html/user/android/bitboinc-<version>.apk + latest.json (read by android.php and by the app).
set -e
# Needs BB_DEPLOY_HOST (user@host with sudo rights) and BB_DEPLOY_DIR (the web server's android download directory).
: "${BB_DEPLOY_HOST:?set BB_DEPLOY_HOST}"; : "${BB_DEPLOY_DIR:?set BB_DEPLOY_DIR}"
cd "$(dirname "$0")/.."
VN=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
VC=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
APK=app/build/outputs/apk/release/app-release.apk
F=bitboinc-$VN.apk
SHA=$(sha256sum $APK | cut -d' ' -f1)
SIZE=$(stat -c %s $APK)
NOTES=""   # the download page shows this text to everyone: leave it empty
printf '{"versionCode":%s,"versionName":"%s","file":"%s","sha256":"%s","size":%s,"notes":"%s"}\n' "$VC" "$VN" "$F" "$SHA" "$SIZE" "$NOTES" > /tmp/bb_latest.json
scp -q $APK $BB_DEPLOY_HOST:/tmp/$F
scp -q /tmp/bb_latest.json $BB_DEPLOY_HOST:/tmp/bb_latest.json
ssh $BB_DEPLOY_HOST "sudo bash -c 'D=$BB_DEPLOY_DIR; mkdir -p \$D; cp /tmp/$F \$D/$F; cp /tmp/bb_latest.json \$D/latest.json; chmod 644 \$D/*'; rm -f /tmp/$F /tmp/bb_latest.json"
echo "published $F ($SIZE bytes, sha256 $SHA)"
