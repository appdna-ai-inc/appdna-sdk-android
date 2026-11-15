package ai.appdna.sdk.messages

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The window an in-app message is shown in — ONE construction, used by both hosts (`MessageManager` for
 * config-triggered messages, `PendingMessageListener` for per-user deliveries), so a rule about how the
 * message leaves the screen cannot hold for one and not the other.
 *
 * the dialog is NOT cancelable. Back reaches the message's own `BackHandler` (in
 * [InAppMessageView]), which dismisses through `onDismiss` only while [MessageDismissPolicy] allows it:
 * a message authored "not dismissable", or still inside its `dismiss_delay`, ignores back. Before, a
 * cancelable dialog closed on back whatever the author set, and without the `in_app_message_dismissed`
 * event the ✕ sends.
 */
internal object InAppMessageDialog {

    fun build(
        activity: Activity,
        config: MessageConfig,
        onCTATap: (ComponentDialog) -> Unit,
        onDismiss: (ComponentDialog) -> Unit,
    ): ComponentDialog {
        val dialog = ComponentDialog(activity)
        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(dialog)
            setViewTreeSavedStateRegistryOwner(dialog)
            // The message's BackHandler must register with the DIALOG's dispatcher: the dialog window
            // has focus, so the activity's dispatcher never sees this back press.
            setViewTreeOnBackPressedDispatcherOwner(dialog)
            setContent {
                InAppMessageView(
                    config = config,
                    onCTATap = { onCTATap(dialog) },
                    onDismiss = { onDismiss(dialog) },
                )
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            // No window dim. A Dialog dims everything behind it by default (FLAG_DIM_BEHIND at the
            // theme's backgroundDimAmount), which made a banner and a tooltip look like blocking modals
            // and darkened a modal twice: once here and once by its own scrim. Each message view draws
            // the only backdrop it has (a modal: black at 0.4, or 0.3 under a blur; a banner and a
            // tooltip: none), as on iOS, where the controller is presented over the app with a clear
            // background.
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        return dialog
    }

    /**
     * Shows the message over [activity] and KEEPS it there across a recreation of that activity.
     *
     * The message is a dialog in the HOST's activity, and a host that does not declare
     * `configChanges` is destroyed and recreated on a fold, an unfold or a rotation. A dialog does
     * not survive its activity, and its dismiss listener never runs when the window is torn down —
     * so the message vanished mid-read and the "a message is presenting" gate stayed shut, which
     * blocked every later message for the rest of the process. The message now moves to the
     * recreated activity (same class), without a second impression, and the gate opens only when
     * the message is actually closed.
     *
     * [onCTATap] gets the activity the message is on NOW (a link opens from it). [onClosed] runs
     * once, when the message is gone for good: the CTA, a dismissal, or its activity finishing.
     */
    fun show(
        activity: Activity,
        config: MessageConfig,
        onCTATap: (Activity) -> Unit,
        onDismiss: () -> Unit,
        onClosed: () -> Unit,
    ) {
        CarriedMessage(activity.application, config, onCTATap, onDismiss, onClosed).showOn(activity)
    }

    private class CarriedMessage(
        private val application: Application,
        private val config: MessageConfig,
        private val onCTATap: (Activity) -> Unit,
        private val onDismiss: () -> Unit,
        private val onClosed: () -> Unit,
    ) : Application.ActivityLifecycleCallbacks {
        private var dialog: ComponentDialog? = null
        private var host: Activity? = null
        /** The class of the activity being recreated, while the message waits for it. */
        private var awaiting: Class<out Activity>? = null
        private var moving = false
        private var closed = false

        fun showOn(activity: Activity) {
            if (dialog == null && awaiting == null) application.registerActivityLifecycleCallbacks(this)
            host = activity
            awaiting = null
            val d = build(
                activity,
                config,
                onCTATap = { onCTATap(activity); close() },
                onDismiss = { onDismiss(); close() },
            )
            d.setOnDismissListener { if (!moving) finish() }
            dialog = d
            d.show()
        }

        private fun close() {
            dialog?.dismiss() ?: finish()
        }

        private fun finish() {
            if (closed) return
            closed = true
            dialog = null
            host = null
            awaiting = null
            application.unregisterActivityLifecycleCallbacks(this)
            onClosed()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity !== host || closed) return
            val d = dialog
            // Destroyed without finishing: the system is recreating it (a configuration change, or
            // an activity the system reclaims and rebuilds when the user returns). Take the window
            // down quietly and wait for the new instance of the same activity.
            if (activity.isChangingConfigurations || !activity.isFinishing) {
                // The dismiss listener runs LATER (a dialog posts it), so it is detached first: the
                // old window going away must not read as the message being closed.
                moving = true
                d?.setOnDismissListener(null)
                try { d?.dismiss() } catch (_: Throwable) {}
                moving = false
                dialog = null
                host = null
                awaiting = activity.javaClass
            } else {
                try { d?.dismiss() } catch (_: Throwable) {}
                finish()
            }
        }

        override fun onActivityResumed(activity: Activity) {
            val cls = awaiting ?: return
            if (closed) return
            // A different screen came up instead of the recreated one: the user is elsewhere, so the
            // message is over (and the gate opens for the next one).
            if (activity.javaClass != cls) { finish(); return }
            try {
                showOn(activity)
            } catch (_: Throwable) {
                finish()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }
}
