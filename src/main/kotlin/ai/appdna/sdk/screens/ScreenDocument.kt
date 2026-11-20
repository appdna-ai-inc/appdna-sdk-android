package ai.appdna.sdk.screens

/**
 * The served document of one screen or flow, as the console publishes it
 * (`ScreenConfigSyncService.buildScreenConfig` / `buildFlowConfig`, written to
 * `config/screen_index/screens/{id}` and `config/screen_index/flows/{id}`):
 *
 *     { id, version, name, config: { presentation, layout, sections, … }, audience_rules?, trigger_rules?,
 *       start_date?, end_date?, min_sdk_version?, default_locale, analytics_name?, updated_at }
 *
 * The renderable config is nested under `config`, and the identity and scheduling fields sit beside it. This
 * flattens it into the shape [ScreenConfig.fromMap] / [FlowConfig.fromMap] read: the nested config, with the
 * document's identity and scheduling fields filled in where the config does not carry its own. The document id
 * is the cache key, so it always wins. `audience_rules` / `trigger_rules` are NOT copied: the index carries them
 * for trigger and slot decisions, and the document's own shape for them differs from the config's.
 *
 * A document with no `config` map is taken as an already-flat config (the shape `previewScreen` takes).
 * Mirrors iOS `ScreenDocument.normalize`; shared fixture `dto_parsing/screen_served_document.fixture.json`.
 */
internal object ScreenDocument {
    private val CARRIED = listOf(
        "name", "version", "start_date", "end_date", "min_sdk_version", "default_locale", "analytics_name",
    )

    @Suppress("UNCHECKED_CAST")
    fun normalize(document: Map<String, Any?>, id: String): Map<String, Any?> {
        val nested = document["config"] as? Map<String, Any?>
        val out = (nested ?: document).toMutableMap()
        if (nested != null) {
            for (key in CARRIED) {
                if (out[key] == null && document[key] != null) out[key] = document[key]
            }
        }
        out["id"] = id
        return out
    }
}
