package ai.appdna.sdk.core

/**
 * The consents ONE Agreement element holds, and whether each required one is accepted. Mirror of iOS
 * `Core/AgreementConsents.swift`, pinned by the shared `agreement_consents` fixture.
 *
 * With `field_config.consents` an Agreement holds up to five consents, each with its own text, colours and
 * Required. Each answer is stored FLAT under `<field_id>.<consent_id>` as a Boolean. A required consent blocks
 * the CTA until checked. With no `consents` the block keeps its single-consent shape.
 */
object AgreementConsents {
    data class Consent(
        val id: String,
        val text: String,
        val required: Boolean,
        val defaultChecked: Boolean,
        val textColor: String?,
        val linkColor: String?,
        val checkboxColor: String?,
        val checkboxBorderColor: String?,
    )

    const val MAX = 5

    /** The consents in `field_config.consents`; unusable ids dropped, repeats keep the first, at most five. */
    fun parse(raw: Any?): List<Consent> {
        val items = raw as? List<*> ?: return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Consent>()
        for (item in items) {
            if (out.size >= MAX) break
            val d = item as? Map<*, *> ?: continue
            val id = (d["id"] as? String)?.trim().orEmpty()
            if (!isIdShaped(id) || !seen.add(id)) continue
            out += Consent(
                id = id,
                text = d["text"] as? String ?: "",
                required = d["required"] as? Boolean ?: false,
                defaultChecked = d["default_checked"] as? Boolean ?: false,
                textColor = nonEmpty(d["text_color"]),
                linkColor = nonEmpty(d["link_color"]),
                checkboxColor = nonEmpty(d["checkbox_color"]),
                checkboxBorderColor = nonEmpty(d["checkbox_border_color"]),
            )
        }
        return out
    }

    fun key(fieldId: String, consentId: String): String = "$fieldId.$consentId"

    /** The key of the first REQUIRED consent that is not checked, or null when the CTA may advance. */
    fun firstMissing(fieldId: String, consents: List<Consent>, values: Map<String, Any?>): String? {
        for (c in consents) {
            if (!c.required) continue
            val k = key(fieldId, c.id)
            if (values[k] != true) return k
        }
        return null
    }

    internal fun isIdShaped(id: String): Boolean =
        id.length in 1..64 && id.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '_' || it == '-' }

    private fun nonEmpty(v: Any?): String? = (v as? String)?.takeIf { it.isNotBlank() }
}
