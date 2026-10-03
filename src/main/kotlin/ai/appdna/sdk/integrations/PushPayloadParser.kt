package ai.appdna.sdk.integrations

import ai.appdna.sdk.Log
import ai.appdna.sdk.PushAction
import ai.appdna.sdk.PushActionButton
import ai.appdna.sdk.PushPayload
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parsing half of [AppDNAMessagingService].
 *
 * WHY: the FCM payload parse (action buttons, canonical action, the payload the host delegate
 * receives) lived as private methods on a `FirebaseMessagingService` subclass, so it could not be
 * unit-tested without standing up Firebase — the most schema-sensitive code in the push path had
 * zero coverage. Moved verbatim; the Service now delegates to this object.
 */
internal object PushPayloadParser {

    /**
     * SPEC-070-A H.10: parse action buttons from a push data payload.
     *
     * Accepts BOTH schemas the AppDNA backend may emit:
     *   1. **Canonical (iOS-shaped)**: `actions` is a JSON array of
     *      `{ id, label, type, value, icon? }` objects.
     *   2. **Legacy flat**: `action_0_id`, `action_0_label`, ... (no cap).
     *
     * Uncapped by design: the system tray caps at 3, but the backend may send more so future OS
     * versions / wear surfaces can show them.
     */
    fun parseActionButtons(data: Map<String, String>): List<PushActionButton> {
        val buttons = mutableListOf<PushActionButton>()

        // Canonical schema first.
        data["actions"]?.let { rawActions ->
            try {
                val arr = JSONArray(rawActions)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isEmpty()) continue
                    val label = obj.optString("label", "")
                    // The codegen'd DTO spells these `action_type` / `action_value`; the FCM wire
                    // format spells them `type` / `value`. Accept both so neither side is dead.
                    val type = obj.optString("type", null) ?: obj.optString("action_type", "dismiss")
                    val value = obj.optString("value", null) ?: obj.optString("action_value", null)
                    val icon = obj.optString("icon", null)
                    buttons.add(PushActionButton(id, label, type, value, icon))
                }
            } catch (e: Exception) {
                Log.warning("Push: failed to parse 'actions' JSON: ${e.message}")
            }
        }
        if (buttons.isNotEmpty()) return buttons

        // Legacy flat schema. Walk indices until a gap appears (action_${i}_id missing).
        var i = 0
        while (true) {
            val id = data["action_${i}_id"] ?: break
            val label = data["action_${i}_label"] ?: ""
            val type = data["action_${i}_type"] ?: "dismiss"
            val value = data["action_${i}_value"]
            val icon = data["action_${i}_icon"]
            buttons.add(PushActionButton(id, label, type, value, icon))
            i++
        }
        return buttons
    }

    /**
     * SPEC-070-A H.10: read the canonical `action` field if present
     * (`{"type": "show_screen", "value": "settings"}`) — falls back to the flat
     * `action_type` / `action_value` keys for legacy senders.
     */
    fun parseCanonicalAction(data: Map<String, String>): PushAction? {
        data["action"]?.let { rawAction ->
            try {
                val obj = JSONObject(rawAction)
                val type = obj.optString("type", "").ifEmpty { return@let null }
                val value = obj.optString("value", "")
                return PushAction(type, value)
            } catch (e: Exception) {
                Log.warning("Push: failed to parse 'action' JSON: ${e.message}")
            }
        }
        val type = data["action_type"]?.takeIf { it.isNotEmpty() } ?: return null
        val value = data["action_value"] ?: ""
        return PushAction(type, value)
    }

    /**
     * The push-tap ROUTING LADDER, one function for every Android tap entry point
     * (`AppDNA.handlePushTap(intent)` and `AppDNA.push.handleTapData(data, actionId)`), so both
     * route identically. First match wins:
     *   (0) a tapped BUTTON (`actionId` matches an entry of `actions`) → that button's own action;
     *   (1) the canonical `action` (a JSON string `{type, value}`);
     *   (2) the flat `action_type` / `action_value`;
     *   (3) `screen_id` → `show_screen`;
     *   (4) `deep_link` → `deep_link`.
     * The first rung that is PRESENT decides, as on iOS (`PushTapRouter.route`): a tapped
     * button, or a body action with a type, whose value is blank routes nowhere — it does not fall
     * through to `screen_id` / `deep_link`. Returns null when nothing routes. Pure.
     */
    fun resolveTapAction(data: Map<String, String>, actionId: String?): PushAction? {
        if (!actionId.isNullOrBlank()) {
            val button = parseActionButtons(data).firstOrNull { it.id == actionId }
            if (button != null) {
                val value = button.value
                return if (value.isNullOrBlank()) null else PushAction(button.type, value)
            }
        }
        parseCanonicalAction(data)?.let { return if (it.value.isNotBlank()) it else null }
        data["screen_id"]?.takeIf { it.isNotBlank() }?.let { return PushAction("show_screen", it) }
        data["deep_link"]?.takeIf { it.isNotBlank() }?.let { return PushAction("deep_link", it) }
        return null
    }

    /**
     * Build the [PushPayload] handed to `AppDNAPushDelegate.onPushReceived`.
     *
     * SPEC-070-B — the payload now carries the full `actions` list. Previously a host could see the
     * single primary `action` only, so a push with three buttons reached `onPushReceived` looking
     * identical to one with none, and the host could not pre-load anything for a button it could
     * not see. The buttons the SDK actually registers on the notification are exactly these.
     */
    fun buildPayload(
        data: Map<String, String>,
        fallbackTitle: String? = null,
        fallbackBody: String? = null,
    ): PushPayload = PushPayload(
        pushId = data["push_id"] ?: "",
        title = data["title"] ?: fallbackTitle ?: "",
        body = data["body"] ?: fallbackBody ?: "",
        imageUrl = data["image_url"],
        data = data.toMap(),
        action = parseCanonicalAction(data),
        actions = parseActionButtons(data),
    )
}
