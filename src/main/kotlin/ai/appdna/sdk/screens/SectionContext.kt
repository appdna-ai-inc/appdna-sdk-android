package ai.appdna.sdk.screens

data class SectionContext(
    val screenId: String,
    val flowId: String? = null,
    var responses: MutableMap<String, Any> = mutableMapOf(),
    var hookData: Map<String, Any>? = null,
    var userTraits: Map<String, Any>? = null,
    val onAction: (SectionAction) -> Unit,
    val onNavigate: (String) -> Unit = {},
    val currentScreenIndex: Int = 0,
    val totalScreens: Int = 1,
    val locale: String = "en",
    val localizations: Map<String, Map<String, String>>? = null,
) {
    fun localize(key: String): String {
        return localizations?.get(locale)?.get(key)
            ?: localizations?.get("en")?.get(key)
            ?: key
    }

    fun buildEvaluationContext(): Map<String, Any> {
        val ctx = mutableMapOf<String, Any>("responses" to responses)
        hookData?.let { ctx["hook_data"] = it }
        userTraits?.let { ctx["user"] = it }
        ctx["session"] = mapOf("screen_index" to currentScreenIndex, "total_screens" to totalScreens)
        return ctx
    }
}

sealed class SectionAction {
    data object Next : SectionAction()
    data object Back : SectionAction()
    data object Dismiss : SectionAction()
    data class Navigate(val screenId: String) : SectionAction()
    data class OpenURL(val url: String) : SectionAction()
    data class OpenWebview(val url: String) : SectionAction()
    data object OpenAppSettings : SectionAction()
    data class Share(val text: String) : SectionAction()
    data class DeepLink(val url: String) : SectionAction()
    data class ShowPaywall(val id: String?) : SectionAction()
    data class ShowSurvey(val id: String?) : SectionAction()
    data class ShowScreen(val id: String) : SectionAction()
    data class SubmitForm(val data: Map<String, Any>) : SectionAction()
    data class Track(val event: String, val properties: Map<String, Any>? = null) : SectionAction()
    data class Haptic(val type: String) : SectionAction()
    data class Custom(val type: String, val value: String? = null) : SectionAction()

    // Extended cases for FlowManager.handleAction parity
    // with iOS Screens/FlowManager.swift handleAction(_:). The SDUI layer
    // already produces the existing 16 verbs above; these duplicate-named
    // verbs cover the FlowManager-specific routing surface (restart,
    // complete, set_response, present/dismiss paywall, show message,
    // set_user_property, purchase, restore).
    data object Restart : SectionAction()
    data object Complete : SectionAction()
    data class SetResponse(val key: String, val value: Any?) : SectionAction()
    data class PresentPaywall(val id: String?) : SectionAction()
    data object DismissPaywall : SectionAction()
    data class ShowMessage(val id: String?) : SectionAction()
    data class SetUserProperty(val key: String, val value: Any?) : SectionAction()
    data class Purchase(val productId: String) : SectionAction()
    data object Restore : SectionAction()
}

/**
 * Encode a [SectionAction] as a `{type, <payload fields>}` map,
 * matching iOS `AppdnaPlugin.sectionActionToMap` 1:1 (lowercase discriminator
 * + identical field names) so the cross-platform screen-action veto payload is
 * byte-identical on both platforms. Host veto logic reads `action["type"]` and
 * the type-specific payload fields (e.g. `action["url"]`).
 *
 * The 16 base verbs mirror iOS's `SectionAction` cases exactly. The 9 extended
 * FlowManager verbs have no iOS `SectionAction` counterpart; they use
 * lowerCamelCase discriminators + native field names for exhaustiveness.
 */
fun SectionAction.toActionMap(): Map<String, Any?> = when (this) {
    is SectionAction.Next -> mapOf("type" to "next")
    is SectionAction.Back -> mapOf("type" to "back")
    is SectionAction.Dismiss -> mapOf("type" to "dismiss")
    is SectionAction.Navigate -> mapOf("type" to "navigate", "screenId" to screenId)
    is SectionAction.OpenURL -> mapOf("type" to "openURL", "url" to url)
    is SectionAction.OpenWebview -> mapOf("type" to "openWebview", "url" to url)
    is SectionAction.OpenAppSettings -> mapOf("type" to "openAppSettings")
    is SectionAction.Share -> mapOf("type" to "share", "text" to text)
    is SectionAction.DeepLink -> mapOf("type" to "deepLink", "url" to url)
    is SectionAction.ShowPaywall -> mapOf("type" to "showPaywall", "id" to id)
    is SectionAction.ShowSurvey -> mapOf("type" to "showSurvey", "id" to id)
    is SectionAction.ShowScreen -> mapOf("type" to "showScreen", "id" to id)
    is SectionAction.SubmitForm -> mapOf("type" to "submitForm", "data" to data)
    is SectionAction.Track -> mapOf("type" to "track", "event" to event, "properties" to properties)
    is SectionAction.Haptic -> mapOf("type" to "haptic", "hapticType" to type)
    is SectionAction.Custom -> mapOf("type" to "custom", "customType" to type, "value" to value)
    // Extended FlowManager verbs (no iOS SectionAction counterpart).
    is SectionAction.Restart -> mapOf("type" to "restart")
    is SectionAction.Complete -> mapOf("type" to "complete")
    is SectionAction.SetResponse -> mapOf("type" to "setResponse", "key" to key, "value" to value)
    is SectionAction.PresentPaywall -> mapOf("type" to "presentPaywall", "id" to id)
    is SectionAction.DismissPaywall -> mapOf("type" to "dismissPaywall")
    is SectionAction.ShowMessage -> mapOf("type" to "showMessage", "id" to id)
    is SectionAction.SetUserProperty -> mapOf("type" to "setUserProperty", "key" to key, "value" to value)
    is SectionAction.Purchase -> mapOf("type" to "purchase", "productId" to productId)
    is SectionAction.Restore -> mapOf("type" to "restore")
}
