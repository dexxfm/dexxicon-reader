#!/bin/zsh
# One-time setup, run by a person in Terminal on the Mac (not through mac_run.py). It creates a
# dedicated signing keychain so iOS releases can be signed unattended, without the login
# keychain being unlocked.
#
#   ~/.dexxicon-ci/signing.keychain-db   holds only the Apple Distribution identity below
#   ~/.dexxicon-ci/keychain-pass         its random password (mode 600, never printed)
#
# Exporting the identity from the login keychain needs the Mac password, which this script
# asks for (macOS may also show an "allow export" dialog). After that, unlock_signing_keychain.sh
# unlocks only this keychain, and the login keychain is never needed for a release.
# Re-running the script is safe: it keeps the existing password and re-imports the identity.
set -euo pipefail

IDENTITY_SHA1=A27705A164006B8657753E96F4AAB1BBF43F5F45   # Apple Distribution: Damien Ball (XG622A3783)
CI=$HOME/.dexxicon-ci
KC=$CI/signing.keychain-db
LOGIN=$HOME/Library/Keychains/login.keychain-db

mkdir -p "$CI" && chmod 700 "$CI"
if [[ ! -s $CI/keychain-pass ]]; then
  (umask 077; openssl rand -base64 32 | tr -d '\n' > "$CI/keychain-pass")
fi
PASS=$(<"$CI/keychain-pass")

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
P12=$TMP/identities.p12
P12PASS=$(openssl rand -base64 24)

echo "Unlocking the login keychain to export the signing identity. Enter your Mac password:"
security unlock-keychain "$LOGIN"
# `security export` can't pick one identity, so export them all to a throwaway file and
# delete the others from the new keychain below.
security export -k "$LOGIN" -t identities -f pkcs12 -P "$P12PASS" -o "$P12"

[[ -f $KC ]] || security create-keychain -p "$PASS" "$KC"
security unlock-keychain -p "$PASS" "$KC"
security set-keychain-settings "$KC"   # no idle auto-lock; the release script unlocks it anyway
security import "$P12" -k "$KC" -P "$P12PASS" -T /usr/bin/codesign -T /usr/bin/security >/dev/null

# Keep only the Dexxicon distribution identity in this keychain.
for hash in ${(f)"$(security find-identity -p codesigning "$KC" | awk '/^ *[0-9]+\)/ {print $2}' | sort -u)"}; do
  [[ $hash == "$IDENTITY_SHA1" ]] || security delete-identity -Z "$hash" "$KC" >/dev/null
done

# Let codesign use the key without a prompt.
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$PASS" "$KC" >/dev/null

# Search this keychain first, keeping every keychain already listed.
current=(${(f)"$(security list-keychains -d user | sed -e 's/^ *"//' -e 's/"$//')"})
security list-keychains -d user -s "$KC" ${current:#$KC}

echo
security find-identity -v -p codesigning "$KC"
echo "Done. Releases can now unlock $KC on their own (tools/ios/unlock_signing_keychain.sh)."
