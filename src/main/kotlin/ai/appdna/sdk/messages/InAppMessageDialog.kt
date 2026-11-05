package ai.appdna.sdk.messages

import android.app.Activity
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
        }
        return dialog
    }
}
