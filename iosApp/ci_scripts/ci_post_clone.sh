#!/bin/sh
set -e
set -x

# Xcode Cloud post-clone hook — must live at iosApp/ci_scripts/ci_post_clone.sh, the same
# level as the generated iosApp.xcodeproj (Xcode Cloud looks for ci_scripts next to the
# project/workspace it's building, not at the repo root). Runs right after clone, before
# Xcode Cloud resolves/opens the project, so this is the one place to do everything the
# checked-in source needs before a normal `xcodebuild` can proceed.
#
# $CI_PRIMARY_REPOSITORY_PATH is Xcode Cloud's own env var for the cloned repo root
# (https://developer.apple.com/documentation/xcode/environment-variable-reference).
IOS_DIR="$CI_PRIMARY_REPOSITORY_PATH/iosApp"
cd "$IOS_DIR"

# 1. Generate the Xcode project — iosApp.xcodeproj is deliberately NOT committed (it's
#    xcodegen-generated from project.yml, gitignored, same as every local/SSH build this
#    project already does).
brew install xcodegen
xcodegen generate

# 2. Restore the pinned SwiftPM package versions. Xcode Cloud disables automatic SPM
#    resolution, so without this it either fails outright or silently re-resolves against
#    latest-matching versions instead of the exact ones this project actually tests against.
#    iosApp/Package.resolved (committed) is the source of truth; copy it into the spot the
#    freshly-generated .xcodeproj expects it.
SWIFTPM_DIR="$IOS_DIR/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm"
mkdir -p "$SWIFTPM_DIR"
cp "$IOS_DIR/Package.resolved" "$SWIFTPM_DIR/Package.resolved"

# 3. Install a JDK for the shared Kotlin/Native framework build. The Xcode project's own
#    preBuildScripts phase runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode` on
#    every build (Xcode Cloud's build machine has no JDK by default) — writing
#    org.gradle.java.home directly into ~/.gradle/gradle.properties (rather than relying on
#    a workflow-level JAVA_HOME env var) keeps this self-contained in the repo: nothing to
#    configure by hand in App Store Connect for this part.
#
#    org.gradle.java.home alone isn't enough (issue #235, a real build failure, not
#    theoretical): it only takes effect once Gradle's own JVM is already running. The
#    `./gradlew` wrapper *script* needs a bare `java` (or $JAVA_HOME, which this later
#    Run Script build phase doesn't inherit from this script's own process — Xcode Cloud
#    ci_scripts and the xcodebuild step that runs afterward are separate process
#    environments) just to bootstrap Gradle in the first place, and this VM has no JDK
#    registered with macOS's system Java wrapper (/usr/libexec/java_home) — so that very
#    first launch fails with "Unable to locate a Java Runtime" before gradle.properties is
#    ever read. Symlinking into the standard system JVM location (exactly what Homebrew's
#    own install output suggests as a Caveat) fixes it for every later step, not just Gradle.
brew install openjdk@21
JDK_HOME="$(brew --prefix openjdk@21)"
sudo ln -sfn "$JDK_HOME/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
mkdir -p "$HOME/.gradle"
echo "org.gradle.java.home=$JDK_HOME" >> "$HOME/.gradle/gradle.properties"

# 4. Signing — Release is CODE_SIGN_STYLE: Manual (see project.yml), pointing at the same
#    Apple Distribution certificate + "Dexxicon Reader App Store v2" profile already
#    provisioned for the SSH-build path (see memory: dexxicon-ios-release-signing). Xcode
#    Cloud's build machine is a fresh ephemeral VM with no local keychain state and no access
#    to the SSH-build Mac's keychain, so both are delivered as base64 content via Xcode
#    Cloud's own "Secret" workflow environment variables (set once in App Store Connect —
#    never committed here) rather than re-minting a separate "Xcode Cloud Managed" identity.
#    Both env vars are optional here (a build/archive-only workflow with no distribution
#    step doesn't need them) — only fail loudly if one is set without the other.
if [ -n "$IOS_DISTRIBUTION_CERTIFICATE_P12" ] || [ -n "$IOS_DISTRIBUTION_PROFILE" ]; then
  if [ -z "$IOS_DISTRIBUTION_CERTIFICATE_P12" ] || [ -z "$IOS_DISTRIBUTION_P12_PASSWORD" ] || [ -z "$IOS_DISTRIBUTION_PROFILE" ]; then
    echo "error: set IOS_DISTRIBUTION_CERTIFICATE_P12, IOS_DISTRIBUTION_P12_PASSWORD, and IOS_DISTRIBUTION_PROFILE together, or none of them" >&2
    exit 1
  fi

  KEYCHAIN_PATH="$HOME/build.keychain"
  KEYCHAIN_PASSWORD=""
  security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
  security set-keychain-settings -lut 21600 "$KEYCHAIN_PATH"
  security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
  security list-keychain -d user -s "$KEYCHAIN_PATH" $(security list-keychains -d user | sed 's/"//g')

  echo "$IOS_DISTRIBUTION_CERTIFICATE_P12" | base64 --decode > /tmp/dist.p12
  security import /tmp/dist.p12 -k "$KEYCHAIN_PATH" -P "$IOS_DISTRIBUTION_P12_PASSWORD" -T /usr/bin/codesign -A
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
  rm -f /tmp/dist.p12

  PROFILES_DIR="$HOME/Library/MobileDevice/Provisioning Profiles"
  mkdir -p "$PROFILES_DIR"
  echo "$IOS_DISTRIBUTION_PROFILE" | base64 --decode > "$PROFILES_DIR/xcode-cloud.mobileprovision"
fi

echo "ci_post_clone.sh done"
