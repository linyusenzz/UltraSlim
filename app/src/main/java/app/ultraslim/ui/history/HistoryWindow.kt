package app.ultraslim.ui.history

internal data class HistoryCursor(val createdAt: Long, val id: Long)

internal data class HistoryWindow(
    val filter: String = "",
    val before: HistoryCursor? = null,
    val limit: Int = PAGE_SIZE,
) {
    companion object {
        const val PAGE_SIZE = 100
        const val MAX_ITEMS = 500
    }
}

/** Keeps only navigation cursors; previously visited windows do not retain image records. */
internal class HistoryWindowNavigation {
    var current = HistoryWindow()
        private set
    private val previous = ArrayDeque<HistoryWindow>()

    fun filter(value: String): HistoryWindow {
        if (current.filter != value) {
            previous.clear()
            current = HistoryWindow(filter = value)
        }
        return current
    }

    fun more(last: HistoryCursor?, hasMore: Boolean): HistoryWindow {
        if (!hasMore || last == null) return current
        current = if (current.limit < HistoryWindow.MAX_ITEMS) {
            current.copy(limit = (current.limit + HistoryWindow.PAGE_SIZE).coerceAtMost(HistoryWindow.MAX_ITEMS))
        } else {
            previous.addLast(current)
            HistoryWindow(filter = current.filter, before = last)
        }
        return current
    }

    fun newer(): HistoryWindow {
        current = previous.removeLastOrNull() ?: HistoryWindow(filter = current.filter)
        return current
    }

    fun latest(): HistoryWindow {
        previous.clear()
        current = HistoryWindow(filter = current.filter)
        return current
    }
}
