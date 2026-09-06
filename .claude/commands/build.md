---
description: Assemble the debug APK and report the output path
---

Run `./gradlew :app:assembleDebug` with `JAVA_HOME` set to the Android Studio JBR.
If it fails, show the first real error (not the Gradle boilerplate) and stop.
On success, print the APK path under `app/build/outputs/apk/debug/`.
