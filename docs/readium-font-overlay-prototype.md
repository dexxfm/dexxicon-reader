# Readium downloaded-font overlay prototype

This is an isolated Android instrumented test, not a user-facing font feature. It downloads
one TTF from a local mock BookOrbit endpoint, adds the file as a virtual publication resource,
and declares the face through Readium 3.3's EPUB navigator. It opens the same generated
reflowable EPUB from a local file and over range-capable HTTP. In both cases the test checks
the original chapter bytes and waits for the WebView's font face to load and become the
body's computed font family. The HTTP fixture is larger than Readium's 5 MiB full-cache
threshold, and the test checks that the remote EPUB is accessed with range requests.

The prototype uses `PublicationOpener.open`'s per-publication builder callback to wrap the
original container and add a manifest link. Readium serves that link to its WebView through
the internal `https://readium_package/` origin. This hostname is an implementation detail,
not a stable public API. The per-open callback is invoked twice in Readium 3.3.0, so the
prototype guards against applying the overlay twice.

Run on an Android emulator with `/system/fonts/Roboto-Regular.ttf`. The test APK is separate
from the Dexxicon application and the mock endpoint does not contact a BookOrbit server.

```powershell
.\gradlew.bat :feature:reader-epub:assembleDebugAndroidTest
adb -s <device> install .\feature\reader-epub\build\outputs\apk\androidTest\debug\reader-epub-debug-androidTest.apk
adb -s <device> shell am instrument -w -e class net.dexxicon.reader.feature.reader.epub.FontOverlayInstrumentedTest net.dexxicon.reader.feature.reader.epub.test/androidx.test.runner.AndroidJUnitRunner
```

Still to validate before a product implementation: real BookOrbit authentication and
versions, multiple faces and font formats, offline caching and lifecycle, font changes,
locators and highlights, nonstandard resource origins, and protected or fixed-layout EPUBs.
