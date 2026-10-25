package app.ultraslim

import app.ultraslim.convert.ConversionBudget
import org.junit.Assert.*
import org.junit.Test

class ConversionBudgetTest {
    private val gib = 1024L * 1024 * 1024
    @Test fun lowMemoryAndSystemReservePreventAdmission() {
        assertFalse(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 8*gib, 16*gib, gib, true))
        // 12MP HDR 估算约 616MiB；1.5GB 可用（预算 512MiB）仍拒，2GB 起放行。
        assertFalse(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 3*gib/2, 16*gib, gib, false))
        assertTrue(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 2*gib, 16*gib, gib, false))
        assertTrue(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 8*gib, 16*gib, gib, false))
    }
    @Test fun largeImagesAndInvalidDimensionsAreRejected() {
        assertTrue(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 4*gib, 8*gib, gib, false))
        // 50MP HDR（估算约 1.9GB）在 8GB 设备仅剩 2.5GB 时拒绝。
        assertFalse(ConversionBudget.canStart(8000, 6000, 4000, 3000, true, 30_000_000, 5*gib/2, 8*gib, gib/2, false))
        // 病态增益图（>1 亿像素守卫）与非法尺寸拒绝。
        assertFalse(ConversionBudget.canStart(4000, 3000, 12000, 12000, true, 10_000_000, 4*gib, 8*gib, gib, false))
        assertFalse(ConversionBudget.canStart(-1, -1, 0, 0, false, 0, 8*gib, 16*gib, gib, false))
        assertFalse(ConversionBudget.canStart(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0, false, 0, 8*gib, 16*gib, gib, false))
    }
    @Test fun estimateStaysAnchoredToMeasuredPeak() {
        // 2026-10-24 实测：25MP(5888x4416) HDR preserve 峰值 RSS 1035MB。
        // 估算必须覆盖实测（留裕量）但不得回退到旧公式的 ~5× 保守度。
        val estimate = ConversionBudget.estimateBytes(5888, 4416, 1472, 1104, true, 30_000_000)
        assertTrue("estimate ${estimate} below measured peak", estimate > 1_035_000_000L)
        assertTrue("estimate ${estimate} overly conservative", estimate < 1_035_000_000L * 3 / 2)
    }
    @Test fun deviceCapacityBoundsAdmissionEvenWithPlentyFree() {
        assertEquals(7*gib, ConversionBudget.deviceCapacityBytes(8*gib, gib))
        assertEquals(8*gib - 512L*1024*1024, ConversionBudget.deviceCapacityBytes(8*gib, 0))
    }
    @Test fun previewBudgetLeavesSystemReserveAndRejectsMemoryPressure() {
        assertEquals(0L, ConversionBudget.previewBytes(8*gib, 16*gib, gib, true))
        assertEquals(0L, ConversionBudget.previewBytes(2*gib, 16*gib, gib, false))
        assertEquals(0L, ConversionBudget.previewBytes(0, 16*gib, gib, false))
        assertEquals(0L, ConversionBudget.previewBytes(gib, 0, 0, false))
        assertEquals(8*gib / 3, ConversionBudget.previewBytes(8*gib, 16*gib, gib, false))
        assertEquals(gib / 10, ConversionBudget.previewBytes(gib, 2*gib, gib - gib / 10, false))
    }
    @Test fun encodingLeavesCpuHeadroomEvenOnManyCoreDevices() {
        assertEquals(1, ConversionBudget.threads(1))
        assertEquals(2, ConversionBudget.threads(4))
        assertEquals(4, ConversionBudget.threads(8))
        assertEquals(4, ConversionBudget.threads(32))
    }
}
