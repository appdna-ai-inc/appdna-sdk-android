package ai.appdna.sdk.consent

import ai.appdna.sdk.consent.ConsentSyncTable.Ack
import ai.appdna.sdk.consent.ConsentSyncTable.Slot
import ai.appdna.sdk.consent.ConsentSyncTable.State
import ai.appdna.sdk.consent.ConsentSyncTable.Triple
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import org.json.JSONObject

/**
 * Where the consent sender's persisted state lives:
 *  - the **ack record** (`ack` and `dropped`) in the encrypted prefs, beside `anon_id` ([LocalStorage], a
 *    `SENSITIVE_KEYS` member), so it goes wherever the anon goes;
 *  - the **slot**, `last_body_ts` and the generation counter in the consent prefs file, beside the decision.
 * Every write is synchronous (`commit`): the sender writes "ack := unknown" BEFORE a request goes out.
 */
internal interface ConsentSignalPersistence {
    fun load(): State
    fun save(state: State)
}

internal class PrefsConsentSignalPersistence(private val context: Context) : ConsentSignalPersistence {
    private val storage = LocalStorage(context)
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): State {
        val rec = storage.getString(ACK_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val slot = prefs.getString(SLOT_KEY, null)?.let { runCatching { slotFrom(JSONObject(it)) }.getOrNull() }
        return State(
            ack = rec?.let { ackFrom(it) } ?: Ack.None,
            dropped = rec?.optJSONObject("dropped")?.let { tripleFrom(it) },
            slot = slot,
            lastBodyTs = if (prefs.contains(LAST_TS_KEY)) prefs.getLong(LAST_TS_KEY, 0) else null,
            lastGeneration = prefs.getLong(GEN_KEY, 0),
        )
    }

    override fun save(state: State) {
        val e = prefs.edit()
        if (state.slot != null) e.putString(SLOT_KEY, slotJson(state.slot).toString()) else e.remove(SLOT_KEY)
        if (state.lastBodyTs != null) e.putLong(LAST_TS_KEY, state.lastBodyTs) else e.remove(LAST_TS_KEY)
        e.putLong(GEN_KEY, state.lastGeneration)
        e.commit()
        storage.setStringNow(ACK_KEY, ackRecordJson(state.ack, state.dropped).toString())
    }

    companion object {
        /** The consent prefs file `ConsentStore` keeps the decision in. */
        const val PREFS_NAME = "ai.appdna.sdk.consent"
        const val SLOT_KEY = "consent_signal_slot"
        const val LAST_TS_KEY = "consent_signal_last_body_ts"
        const val GEN_KEY = "consent_signal_generation"
        const val ACK_KEY = "consent_ack_record"

        fun tripleJson(t: Triple): JSONObject = JSONObject()
            .put("anon_id", t.anonId)
            .put("user_id", t.userId ?: JSONObject.NULL)
            .put("analytics", t.analytics)

        fun tripleFrom(o: JSONObject): Triple = Triple(
            anonId = o.getString("anon_id"),
            userId = if (o.isNull("user_id")) null else o.getString("user_id"),
            analytics = o.getBoolean("analytics"),
        )

        fun ackRecordJson(ack: Ack, dropped: Triple?): JSONObject {
            val o = JSONObject()
            when (ack) {
                Ack.None -> o.put("ack", "none")
                Ack.Unknown -> o.put("ack", "unknown")
                is Ack.Known -> o.put("ack", tripleJson(ack.triple))
            }
            o.put("dropped", dropped?.let { tripleJson(it) } ?: JSONObject.NULL)
            return o
        }

        fun ackFrom(o: JSONObject): Ack = when (val a = o.opt("ack")) {
            "unknown" -> Ack.Unknown
            is JSONObject -> Ack.Known(tripleFrom(a))
            else -> Ack.None
        }

        fun slotJson(s: Slot): JSONObject = JSONObject()
            .put("g", s.generation).put("t", tripleJson(s.triple)).put("attempts", s.attempts).put("ts_ms", s.tsMs)

        fun slotFrom(o: JSONObject): Slot = Slot(
            generation = o.getLong("g"), triple = tripleFrom(o.getJSONObject("t")),
            attempts = o.getInt("attempts"), tsMs = o.getLong("ts_ms"),
        )

        /** The wire body: `user_id` is an explicit JSON null for a device with no user. */
        fun body(slot: Slot): String = tripleJson(slot.triple).put("ts_ms", slot.tsMs).toString()
    }
}
