package ai.appdna.sdk.core

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi

enum class HapticType {
    LIGHT, MEDIUM, HEAVY, SELECTION, SUCCESS, WARNING, ERROR;

    companion object {
        fun fromString(value: String?): HapticType? = when (value?.lowercase()) {
            "light" -> LIGHT
            "medium" -> MEDIUM
            "heavy" -> HEAVY
            "selection" -> SELECTION
            "success" -> SUCCESS
            "warning" -> WARNING
            "error" -> ERROR
            else -> null
        }
    }
}

data class HapticConfig(
    val enabled: Boolean = false,
    val triggers: HapticTriggers = HapticTriggers(),
)

data class HapticTriggers(
    val on_step_advance: String? = null,
    val on_button_tap: String? = null,
    val on_plan_select: String? = null,
    val on_option_select: String? = null,
    val on_toggle: String? = null,
    val on_form_submit: String? = null,
    val on_error: String? = null,
    val on_success: String? = null,
)

object HapticEngine {
    fun trigger(view: View, type: HapticType) {
        val feedbackConstant = when (type) {
            HapticType.LIGHT -> HapticFeedbackConstants.CLOCK_TICK
            HapticType.MEDIUM -> HapticFeedbackConstants.CONTEXT_CLICK
            HapticType.HEAVY -> HapticFeedbackConstants.LONG_PRESS
            HapticType.SELECTION -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticType.SUCCESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) confirmConstant() else HapticFeedbackConstants.CONTEXT_CLICK
            HapticType.WARNING -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) rejectConstant() else HapticFeedbackConstants.LONG_PRESS
            HapticType.ERROR -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) rejectConstant() else HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(feedbackConstant)
    }

    fun triggerIfEnabled(view: View, type: String?, config: HapticConfig?) {
        if (config == null || !config.enabled) return
        val hapticType = HapticType.fromString(type) ?: return
        trigger(view, hapticType)
    }

    /**
     * Extracted helper that ASSUMES API 30+ so Android Lint
     * flags any accidental call that isn't behind a
     * `Build.VERSION.SDK_INT >= R` guard. [HapticFeedbackConstants.CONFIRM]
     * was introduced in API 30; on older devices the field is absent and
     * referencing it would `NoSuchFieldError` at class verification.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun confirmConstant(): Int = HapticFeedbackConstants.CONFIRM

    /**
     * See [confirmConstant]. [HapticFeedbackConstants.REJECT]
     * is also API 30+.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun rejectConstant(): Int = HapticFeedbackConstants.REJECT
}
