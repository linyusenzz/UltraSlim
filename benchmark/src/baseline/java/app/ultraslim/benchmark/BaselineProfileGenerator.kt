package app.ultraslim.benchmark

import android.os.Build
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only against the unminified baseline target. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun startupAndNavigation() {
        // API 34+ profile capture works without rooting or changing the daily-use app.
        assumeTrue("Profile generation requires API 34+", Build.VERSION.SDK_INT >= 34)
        profile.collect(packageName = TARGET_PACKAGE) {
            pressHome()
            startActivityAndWait()
            device.navigateApp()
        }
    }
}
