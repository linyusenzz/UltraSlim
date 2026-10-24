package app.ultraslim

import app.ultraslim.data.*
import org.junit.Assert.*
import org.junit.Test

class ExportSnapshotTest {
    @Test fun destinationsAndNamesArePartOfImmutableBatchSnapshot() {
        val original = ConversionDefaults(outputTree = "content://provider/tree/one", outputTreeLabel = "照片", retainSourceName = true)
        val snapshot = original.encode()
        val changed = original.copy(outputTree = "content://provider/tree/two", retainSourceName = false)
        assertNotEquals(changed, decodeDefaults(snapshot))
        assertEquals(original, decodeDefaults(snapshot))
        assertEquals("", decodeDefaults("{}").outputTree)
        assertFalse(decodeDefaults("{}").retainSourceName)
    }
}
