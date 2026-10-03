package ai.appdna.sdk.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many host vetoes have timed out and silently fallen back to
 * their default. A timed-out `onPromoCodeSubmit` drops a sale; a timed-out `shouldShowMessage`
 * bypasses the host's guard. Neither is visible today, which is why the counter exists at all.
 * Surfaced through `diagnose()`; incremented by the wrapper's veto timer.
 */
internal object VetoTimeoutCounter {
    private val counter = AtomicInteger(0)

    val count: Int get() = counter.get()

    fun increment() {
        counter.incrementAndGet()
    }

    fun reset() {
        counter.set(0)
    }
}
