package app.ultraslim.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "app.ultraslim.benchmark"

internal fun UiDevice.awaitHome() {
    check(wait(Until.hasObject(By.desc("设置")), 10_000)) { "Home screen did not become ready" }
}

/** Navigation workload; it intentionally makes no claim to represent a populated history library. */
internal fun UiDevice.navigateApp() {
    awaitHome()
    findObject(By.desc("设置")).click()
    check(wait(Until.hasObject(By.text("设置")), 5_000)) { "Settings did not open" }
    pressBack()
    awaitHome()
    findObject(By.text("历史")).click()
    waitForIdle()
    findObject(By.text("转换")).click()
    awaitHome()
}
