# Changelog

## 0.5.0 — 2026-09-06

First tagged build. Project scaffold only — feature screens are placeholders.

- Multi-module Android project (22 modules) on AGP 9.4 / Kotlin 2.4 / Gradle 9.6 / KSP2.
- Jetpack Compose + Material 3, dark-blue/grey brand theme, fox adaptive launcher icon.
- App shell: Hilt Application + WorkManager, edge-to-edge activity, bottom-nav NavHost
  (Library / Browse / Settings) with placeholder content.
- Core domain models for servers/auth, OPDS catalog feeds + OPDS-PSE, content-format
  media-type mapping, library items / downloads / reading positions / annotations.
- Signed release APK build wired up (`assembleRelease`).

Not yet implemented: server management, OPDS browsing, downloads, any reader, sync.
