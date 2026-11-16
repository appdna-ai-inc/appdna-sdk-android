package ai.appdna.sdk.onboarding

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail

/**
 * Live-validation shared-fixture drivers (dispatched from SharedFixtureTest):
 *
 *  - `remote_validation_script` — the REAL [RemoteValidationCoordinator] (the object the step composable owns), each
 *    field's config read through the REAL step parser + [RemoteValidationConfig.of], on a scripted clock (the
 *    coordinator's own `schedule` seam) against a scripted host (its `startCall` seam).
 *  - `decode_validation_result` — the REAL [InputValidationResult.decodeBridgeReply] every wrapper bridge calls.
 */
internal object RemoteValidationFixtureDriver {

    /** Work scheduled at now + ms runs when [advance] passes it, in time order, ties in insertion order. */
    class VirtualClock {
        var nowMs = 0L
            private set
        private var seq = 0
        private val items = mutableListOf<Triple<Long, Int, () -> Unit>>()

        fun schedule(ms: Long, fire: () -> Unit): () -> Unit {
            val id = ++seq
            items += Triple(nowMs + ms, id, fire)
            return { items.removeAll { it.second == id } }
        }

        fun advance(ms: Long) {
            val end = nowMs + ms
            while (true) {
                val next = items.filter { it.first <= end }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                items.removeAll { it.second == next.second }
                nowMs = next.first
                next.third()
            }
            nowMs = end
        }
    }

    fun runScript(name: String, config: JSONObject?, session: JSONObject, action: JSONObject, fixture: JSONObject): Map<String, Any?> {
        val fieldsJson = config?.optJSONObject("fields") ?: fail("[$name] remote_validation_script needs setup.config.fields").let { JSONObject() }
        val script = action.optJSONArray("script") ?: fail("[$name] remote_validation_script needs action.script").let { JSONArray() }
        val host = session.optJSONObject("host") ?: JSONObject()
        val implements = host.optString("mode") != "none"
        val replies = host.optJSONObject("replies") ?: JSONObject()

        // Each field's config through the REAL step parser.
        val configs = sortedMapOf<String, RemoteValidationConfig>()
        for (id in fieldsJson.keys()) {
            val block = mapOf(
                "id" to id, "type" to "input_text", "field_id" to id,
                "field_config" to mapOf("remote_validation" to toKotlin(fieldsJson.get(id))),
            )
            val step = mapOf("id" to "fixture_step", "type" to "custom", "content_blocks" to listOf(block))
            val parsed = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks?.firstOrNull()
                ?: fail("[$name] field $id did not parse").let { return emptyMap() }
            configs[id] = RemoteValidationConfig.of(parsed) ?: fail("[$name] field $id has no remote_validation").let { return emptyMap() }
        }

        val clock = VirtualClock()
        var online = true
        val calls = mutableListOf<List<String>>()
        val events = mutableListOf<Map<String, Any?>>()
        var warnings = 0
        val values = mutableMapOf<String, String>()
        val outcomes = mutableListOf<Map<String, Any?>>()

        val coordinator = RemoteValidationCoordinator(
            schedule = { ms, fire -> clock.schedule(ms, fire) },
            hasHost = { implements },
            startCall = { fieldId, value, _, complete ->
                calls += listOf(fieldId, value)
                val reply = replies.optJSONObject(value)
                if (reply == null) {
                    fail("[$name] the scripted host has no reply for \"$value\"")
                    ({})
                } else if (reply.optBoolean("never")) {
                    ({})
                } else {
                    clock.schedule(reply.optLong("after_ms")) {
                        if (reply.optBoolean("throws")) {
                            complete(RemoteValidationCoordinator.HostOutcome.Threw)
                        } else {
                            val result = reply.opt("result")?.takeIf { it != JSONObject.NULL }?.let { toKotlin(it) }
                            complete(RemoteValidationCoordinator.HostOutcome.Answered(InputValidationResult.decodeBridgeReply(result)))
                        }
                    }
                }
            },
            isOnline = { online },
            now = { clock.nowMs },
            track = { props -> events += mapOf("field_id" to props["field_id"], "status" to props["status"], "reason" to props["reason"]) },
            warn = { warnings += 1 },
        )
        val entries = { configs.keys.map { RemoteValidationCoordinator.Entry(it, values[it] ?: "", configs.getValue(it)) } }

        fun observe(): Map<String, Any?> = mapOf(
            "fields" to configs.keys.associateWith { id ->
                val s = coordinator.state(id)
                mapOf("state" to (s?.phase?.wire ?: "idle"), "message" to s?.message, "reason" to s?.reason)
            },
            "call_count" to calls.size,
            "cta_waiting" to coordinator.ctaWaiting,
            "cta_outcomes" to outcomes.toList(),
            "warnings" to warnings,
        )

        val expected = fixture.optJSONObject("expect")?.optJSONObject("state_after")?.optJSONObject("checkpoints") ?: JSONObject()
        val checkpoints = linkedMapOf<String, Any?>()
        for (i in 0 until script.length()) {
            val o = script.getJSONObject(i)
            when (val op = o.optString("op")) {
                "type" -> {
                    val id = o.getString("field")
                    val value = o.getString("value")
                    values[id] = value
                    coordinator.valueChanged(id, value, configs.getValue(id))
                }
                "advance_clock_ms" -> clock.advance(o.getLong("ms"))
                "confirm" -> coordinator.confirm(o.getString("field"))
                "set_online" -> {
                    val next = o.getBoolean("online")
                    val regained = next && !online
                    online = next
                    if (regained) coordinator.connectivityRegained()
                }
                "tap_cta" -> coordinator.tap(entries) { d ->
                    outcomes += if (d is RemoteValidationCoordinator.Decision.Block) mapOf("outcome" to "blocked", "message" to d.message)
                        else mapOf("outcome" to "advanced", "message" to null)
                }
                "checkpoint" -> {
                    val cp = o.getString("name")
                    // Only the keys the fixture lists are asserted for this checkpoint.
                    val keys = expected.optJSONObject(cp)?.keys()?.asSequence()?.toSet()
                    checkpoints[cp] = observe().filterKeys { keys == null || it in keys }
                }
                else -> fail("[$name] unknown op $op")
            }
        }
        return mapOf("checkpoints" to checkpoints, "calls" to calls.toList(), "events" to events.toList(), "warnings" to warnings)
    }

    fun decode(action: JSONObject): Map<String, Any?> {
        val cases = action.optJSONArray("cases") ?: return emptyMap<String, Any?>().also { fail("decode_validation_result needs action.cases") }
        return mapOf("results" to (0 until cases.length()).map { i ->
            val reply = cases.getJSONObject(i).opt("reply")?.takeIf { it != JSONObject.NULL }?.let { toKotlin(it) }
            when (val r = InputValidationResult.decodeBridgeReply(reply)) {
                is InputValidationResult.Valid -> mapOf("status" to "valid", "message" to r.message)
                is InputValidationResult.Invalid -> mapOf("status" to "invalid", "message" to r.message)
                InputValidationResult.NotImplemented -> mapOf("status" to "not_implemented", "message" to null)
                null -> null
            }
        })
    }

    private fun toKotlin(v: Any?): Any? = when (v) {
        JSONObject.NULL, null -> null
        is JSONObject -> v.keys().asSequence().associateWith { toKotlin(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { toKotlin(v.get(it)) }
        else -> v
    }
}
