package ai.appdna.sdk.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.locks.ReentrantLock

internal class PresentationCoordinator private constructor() {

    companion object {
        val shared = PresentationCoordinator()
    }

    private var isPresenting = false
    private val presentationQueue = mutableListOf<QueueEntry>()
    private var lastAutoTriggerTime: Long = 0
    private val maxQueueSize = 3
    private val autoTriggerCooldownMs = 60_000L
    private val lock = ReentrantLock()
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class PresentationType(val priority: Int) {
        PAYWALL(0),
        ONBOARDING(1),
        SCREEN(2),
        MESSAGE(3),
        SURVEY(4),
    }

    private data class QueueEntry(
        val type: PresentationType,
        val action: () -> Unit,
    )

    fun requestPresentation(
        type: PresentationType,
        isAutoTriggered: Boolean = false,
        action: () -> Unit,
    ): Boolean {
        lock.lock()
        try {
            if (isAutoTriggered) {
                val now = System.currentTimeMillis()
                if (now - lastAutoTriggerTime < autoTriggerCooldownMs) {
                    return false
                }
            }

            if (!isPresenting) {
                isPresenting = true
                if (isAutoTriggered) {
                    lastAutoTriggerTime = System.currentTimeMillis()
                }
                mainHandler.post(action)
                return true
            }

            if (presentationQueue.size < maxQueueSize) {
                presentationQueue.add(QueueEntry(type, action))
                presentationQueue.sortBy { it.type.priority }
                return false
            }

            return false
        } finally {
            lock.unlock()
        }
    }

    fun onDismissed() {
        lock.lock()
        val next: QueueEntry?
        try {
            if (presentationQueue.isEmpty()) {
                isPresenting = false
                return
            }
            // `removeAt(0)`, never `removeFirst()`: compiled against API 35, `removeFirst()` binds to
            // `java.util.List.removeFirst` (API 35 / JDK 21) and throws NoSuchMethodError below that.
            next = presentationQueue.removeAt(0)
        } finally {
            lock.unlock()
        }

        next?.let { mainHandler.post(it.action) }
    }

    fun canPresent(type: PresentationType, isAutoTriggered: Boolean = false): Boolean {
        lock.lock()
        try {
            if (isAutoTriggered) {
                val now = System.currentTimeMillis()
                if (now - lastAutoTriggerTime < autoTriggerCooldownMs) {
                    return false
                }
            }
            return !isPresenting || presentationQueue.size < maxQueueSize
        } finally {
            lock.unlock()
        }
    }

    fun reset() {
        lock.lock()
        try {
            isPresenting = false
            presentationQueue.clear()
            lastAutoTriggerTime = 0
        } finally {
            lock.unlock()
        }
    }
}
