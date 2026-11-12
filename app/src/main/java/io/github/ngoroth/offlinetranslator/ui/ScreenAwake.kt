package io.github.ngoroth.offlinetranslator.ui

/** Activity-scoped display demand. Callers and the window writer are confined to the main thread. */
class ScreenAwake(private val onChanged: (Boolean) -> Unit) : AutoCloseable {
    private val demands = mutableSetOf<Demand>()
    private var started = false
    private var closed = false
    var keepScreenOn: Boolean = false
        private set

    fun owner(isCurrent: () -> Boolean = { true }): Owner = Owner(isCurrent)

    fun setStarted(value: Boolean) {
        started = value && !closed
        refresh()
    }

    fun refresh() {
        demands.removeAll { !it.owner.isCurrent() }
        val keep = started && demands.isNotEmpty()
        if (keep != keepScreenOn) {
            keepScreenOn = keep
            onChanged(keep)
        }
    }

    override fun close() {
        closed = true
        started = false
        demands.clear()
        refresh()
    }

    inner class Owner internal constructor(private val current: () -> Boolean) : AutoCloseable {
        private var disposed = false
        internal fun isCurrent(): Boolean = !closed && !disposed && current()

        fun acquire(): Demand {
            val demand = Demand(this)
            if (isCurrent()) demands.add(demand)
            refresh()
            return demand
        }

        /** Revoke this foreground's work; late releases cannot affect new demands after re-entry. */
        fun clear() {
            demands.removeAll { it.owner === this }
            refresh()
        }

        override fun close() {
            disposed = true
            clear()
        }
    }

    inner class Demand internal constructor(internal val owner: Owner) : AutoCloseable {
        override fun close() {
            demands.remove(this)
            refresh()
        }
    }
}
