#!/bin/zsh
# App Store archive → export for one release, run through mac_run.py:
#
#   zsh tools/ios/archive_release.sh <version> <worktree>
#   e.g. zsh ~/dexxicon-v130/tools/ios/archive_release.sh 1.3.0 ~/dexxicon-v130
#
# The worktree is a checkout of the release tag with xcodegen already run. Artifacts and logs
# go to ~/dexxicon-release-<version>, which must hold ExportOptions.plist (app-store-connect,
# manual signing, profile "Dexxicon Reader App Store v3"). The upload (altool) is a separate
# step. Signing uses the dedicated keychain (setup_signing_keychain.sh), so the login keychain
# doesn't need to be unlocked.
set -uo pipefail

VERSION=${1:?version, e.g. 1.3.0}
SRC=${2:?release worktree, e.g. ~/dexxicon-v130}
R=$HOME/dexxicon-release-$VERSION
KC=$HOME/.dexxicon-ci/signing.keychain-db
HERE=${0:A:h}

export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/bin:$PATH"

[[ -f $R/ExportOptions.plist ]] || { echo "Missing $R/ExportOptions.plist" >&2; exit 1; }
zsh "$HERE/unlock_signing_keychain.sh" || exit 1

cd "$SRC/iosApp" || exit 1
rm -rf "$R/Dexxicon.xcarchive" "$R/export"
xcodebuild archive -project Dexxicon.xcodeproj -scheme Dexxicon -configuration Release \
  -archivePath "$R/Dexxicon.xcarchive" -destination 'generic/platform=iOS' \
  OTHER_CODE_SIGN_FLAGS="--keychain $KC" > "$R/archive.log" 2>&1
echo "ARCHIVE_EXIT=$?" >> "$R/archive.log"
grep -q "ARCHIVE_EXIT=0" "$R/archive.log" || { tail -20 "$R/archive.log"; exit 1; }

xcodebuild -exportArchive -archivePath "$R/Dexxicon.xcarchive" -exportPath "$R/export" \
  -exportOptionsPlist "$R/ExportOptions.plist" > "$R/export.log" 2>&1
echo "EXPORT_EXIT=$?" >> "$R/export.log"
grep -q "EXPORT_EXIT=0" "$R/export.log" || { tail -20 "$R/export.log"; exit 1; }
ls "$R/export"/*.ipa
