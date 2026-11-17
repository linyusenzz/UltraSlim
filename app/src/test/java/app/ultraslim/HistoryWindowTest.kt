package app.ultraslim

import app.ultraslim.ui.history.HistoryCursor
import app.ultraslim.ui.history.HistoryWindow
import app.ultraslim.ui.history.HistoryWindowNavigation
import org.junit.Assert.*
import org.junit.Test

class HistoryWindowTest {
    @Test fun boundedWindowsKeepTieBreakingCursorAndCanNavigateBack() {
        val navigation = HistoryWindowNavigation()
        val boundary = HistoryCursor(1234, 500)
        repeat(4) { navigation.more(boundary, true) }
        assertEquals(HistoryWindow.MAX_ITEMS, navigation.current.limit)
        assertNull(navigation.current.before)
        val older = navigation.more(boundary, true)
        assertEquals(HistoryWindow.PAGE_SIZE, older.limit)
        assertEquals(boundary, older.before)
        repeat(4) { navigation.more(HistoryCursor(1234, 1), true) }
        navigation.more(HistoryCursor(1233, 999), true)
        assertEquals(HistoryCursor(1233, 999), navigation.current.before)
        assertEquals(boundary, navigation.newer().before)
        assertEquals(HistoryWindow.MAX_ITEMS, navigation.current.limit)
        assertNull(navigation.newer().before)
    }

    @Test fun filterChangesResetWindowButReapplyingSameFilterPreservesPosition() {
        val navigation = HistoryWindowNavigation()
        navigation.filter("PENDING")
        repeat(5) { navigation.more(HistoryCursor(10, 5), true) }
        val older = navigation.current
        assertEquals(older, navigation.filter("PENDING"))
        assertEquals(HistoryWindow(filter = "SAVED"), navigation.filter("SAVED"))
        assertEquals(HistoryWindow(filter = "SAVED"), navigation.newer())
    }

    @Test fun endOfListAndLatestDoNotLeaveUnreachableNavigationState() {
        val navigation = HistoryWindowNavigation()
        assertEquals(HistoryWindow(), navigation.more(null, true))
        assertEquals(HistoryWindow(), navigation.more(HistoryCursor(1, 1), false))
        repeat(5) { navigation.more(HistoryCursor(10, 5), true) }
        assertNotNull(navigation.current.before)
        assertEquals(HistoryWindow(), navigation.latest())
        assertEquals(HistoryWindow(), navigation.newer())
    }
}
