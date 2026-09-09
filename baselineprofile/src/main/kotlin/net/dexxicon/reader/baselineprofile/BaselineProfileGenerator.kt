package net.dexxicon.reader.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods exercised at startup and while using each top-level
 * screen. The resulting profiles are bundled into the release APK/AAB and let ART
 * pre-compile those paths, cutting cold start and first-frame jank.
 *
 * Run: `./gradlew :app:generateBaselineProfile` (needs one connected API 33+ device).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Just the cold-start path — kept small so install-time compilation stays cheap. */
    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    /** The wider profile: startup plus a scroll of Browse / Library / Settings. */
    @Test
    fun screens() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        device.wait(Until.hasObject(By.textContains("Library")), 5_000)

        listOf("Browse", "Library", "Settings").forEach { tab ->
            device.findObject(By.text(tab))?.let {
                it.click()
                device.waitForIdle()
                repeat(2) {
                    device.swipe(
                        device.displayWidth / 2,
                        (device.displayHeight * 0.7).toInt(),
                        device.displayWidth / 2,
                        (device.displayHeight * 0.25).toInt(),
                        8,
                    )
                    device.waitForIdle()
                }
            }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.dexxfm.dexxicon_reader"
    }
}
