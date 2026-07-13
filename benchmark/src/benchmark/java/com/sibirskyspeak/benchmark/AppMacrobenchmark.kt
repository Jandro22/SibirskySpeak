package com.sibirskyspeak.benchmark

import android.content.Intent
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.sibirskyspeak"

@RunWith(AndroidJUnit4::class)
@RequiresDevice
class AppMacrobenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStart() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait(Intent("android.intent.action.MAIN").setPackage(PACKAGE)) }
    )

    @Test
    @RequiresDevice
    fun firstCardAndReaderOpen() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
        measureBlock = {
            startActivityAndWait(Intent("android.intent.action.MAIN").setPackage(PACKAGE))
            if (device.wait(Until.hasObject(By.res("onboarding_beginner")), 2_000)) {
                device.findObject(By.res("onboarding_beginner"))?.click()
            }
            device.wait(Until.hasObject(By.res("dashboard_next_action_button")), 12_000)
            device.findObject(By.res("dashboard_next_action_button"))?.click()
            if (device.wait(Until.hasObject(By.res("lesson_got_it")), 12_000)) {
                device.findObject(By.res("lesson_got_it"))?.click()
            }
            if (device.wait(Until.hasObject(By.text("Open Reader")), 8_000)) {
                device.findObject(By.text("Open Reader"))?.click()
            }
        }
    )
}

@RunWith(AndroidJUnit4::class)
@RequiresDevice
class AppBaselineProfile {
    @get:Rule val baselineRule = BaselineProfileRule()

    @Test
    fun startupAndStudyPath() = baselineRule.collect(PACKAGE) {
        pressHome()
        startActivityAndWait(Intent("android.intent.action.MAIN").setPackage(PACKAGE))
        if (device.wait(Until.hasObject(By.res("onboarding_beginner")), 2_000)) {
            device.findObject(By.res("onboarding_beginner"))?.click()
        }
        if (device.wait(Until.hasObject(By.res("lesson_got_it")), 12_000)) {
            device.findObject(By.res("lesson_got_it"))?.click()
        }
    }
}
