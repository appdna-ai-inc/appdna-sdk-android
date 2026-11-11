package ai.appdna.sdk.consent

import ai.appdna.sdk.consent.ConsentSyncTable.Ack
import ai.appdna.sdk.consent.ConsentSyncTable.Event
import ai.appdna.sdk.consent.ConsentSyncTable.State
import ai.appdna.sdk.consent.ConsentSyncTable.Triple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the consent state table, enumerated exhaustively (breadth-first over distinct states).
 *
 * Every sequence of up to [DEPTH] events from the alphabet below, from 18 start states (ack none / known true /
 * known false × decision none / true / false × configured or not), drives the PRODUCTION [ConsentSyncTable.next]
 * inside a model of one device, its sender epochs and a server that applies each request to one row per
 * (anon, user) by `(ts_ms, seq)` with `>=`, as `sdk_consent_signals` does. Each event takes zero or one tick.
 *
 * Alphabet: setConsent(true/false); identify(U2); reset; for the live request a 2xx, a transient the server
 * applied, a transient it did not, or a 400; a retry fire; shutdown (ends the epoch: the request stays on the wire,
 * dead); each dead request delivered, applied or not; relaunch (the live request abandoned, applied or not),
 * optionally followed by a row-0 setConsent; configure.
 *
 * Invariant 1: whenever nothing is outstanding (configured, nothing in flight, no timer, no slot, no dead request),
 * the server's latest row for the anon equals the device's last decision (the newest slot generation's triple),
 * unless that decision was dropped. No row counts as granted.
 * Invariant 2: from any state, relaunch + configure with every later request answered 2xx leaves the server's latest
 * row equal to E, unless the dropped triple equals E.
 *
 * Red controls (each must FAIL the enumeration, and the test prints its shortest failing sequence):
 *  - TIE: `ts_ms` is the send time (no `last_body_ts`), so a dead request and a new one can share a millisecond;
 *  - NO_FENCE: a dead sender's 2xx writes the ack and clears an equal slot, as a live one would;
 *  - NO_WRITE_AHEAD: a SEND leaves the ack as it was instead of marking it unknown.
 */
class ConsentSyncTableEnumerationTest {

    enum class Variant { PRODUCTION, TIE, NO_FENCE, NO_WRITE_AHEAD }

    private data class Row(val analytics: Boolean, val ts: Long, val seq: Int)
    private data class Req(val triple: Triple, val ts: Long, val generation: Long)

    private data class World(
        val table: State,
        val decision: Boolean?,
        val user: String?,
        val configured: Boolean,
        val rowZero: Boolean,
        val clock: Long,
        val dead: List<Req>,
        val server: Map<String, Row>,
        val seq: Int,
        val lastDecision: Triple?,
    ) {
        fun e() = Triple(ANON, user, decision ?: true)
    }

    private class Sim(val variant: Variant) {

        fun step(w: World, ev: Event): World {
            val prev = w.table
            val input = if (variant == Variant.TIE) prev.copy(lastBodyTs = null) else prev
            val r = ConsentSyncTable.next(input, ev)
            var t = r.state
            if (variant == Variant.NO_WRITE_AHEAD && r.effects.any { it is ConsentSyncTable.Effect.Send }) t = t.copy(ack = prev.ack)
            val newDecision = if (t.lastGeneration > prev.lastGeneration) when (ev) {
                is Event.SetConsent -> ev.effective
                is Event.UserChanged -> ev.effective
                is Event.Configure -> ev.effective
                else -> w.lastDecision
            } else w.lastDecision
            return w.copy(table = t, lastDecision = newDecision)
        }

        fun apply(w: World, req: Req): World {
            val key = req.triple.userId ?: ""
            val row = w.server[key]
            val seq = w.seq + 1
            val next = if (row == null || req.ts >= row.ts) Row(req.triple.analytics, req.ts, seq) else row
            return w.copy(server = w.server + (key to next), seq = seq)
        }

        /** Every valid event: (label, successor). */
        fun successors(w: World): List<Pair<String, World>> {
            val out = mutableListOf<Pair<String, World>>()
            fun add(label: String, f: (World) -> World?) {
                for (tick in listOf(0L, 1L)) {
                    val start = w.copy(clock = w.clock + tick)
                    f(start)?.let { out += "$label${if (tick == 1L) "+1" else ""}" to it }
                }
            }
            for (v in listOf(true, false)) add("setConsent($v)") { s ->
                val d = s.copy(decision = v)
                if (s.configured) step(d, Event.SetConsent(d.e(), s.clock)) else d.copy(rowZero = true)
            }
            if (w.configured && w.user != U2) add("identify(U2)") { s ->
                val d = s.copy(user = U2); step(d, Event.UserChanged(d.e(), s.clock))
            }
            if (w.configured && w.user != null) add("reset") { s ->
                val d = s.copy(user = null); step(d, Event.UserChanged(d.e(), s.clock))
            }
            val f = w.table.inFlight
            if (f != null) {
                val req = Req(f.triple, f.tsMs, f.generation)
                add("2xx") { s -> step(apply(s, req), Event.Success(f.generation)) }
                add("transient-applied") { s -> step(apply(s, req), Event.Transient(f.generation, null)) }
                add("503") { s -> step(s, Event.Transient(f.generation, null)) }
                add("400") { s -> step(s, Event.Drop(f.generation)) }
            }
            w.table.timer?.let { g -> add("retry-fire") { s -> step(s, Event.RetryFire(g)) } }
            if (w.configured) add("shutdown") { s -> end(s) }
            w.dead.forEachIndexed { i, req ->
                add("dead[$i]-2xx") { s -> deadArrives(apply(s, req), i, req) }
                add("dead[$i]-lost") { s -> s.copy(dead = s.dead.filterIndexed { j, _ -> j != i }) }
            }
            if (!w.configured) add("configure") { s -> configure(s) }
            // relaunch: the live request is abandoned, applied or not; dead requests likewise.
            for (applied in listOf(true, false)) for (rowZero in listOf<Boolean?>(null, true, false)) {
                add("relaunch(applied=$applied${rowZero?.let { ",setConsent($it)" } ?: ""})") { s ->
                    var x = s
                    val all = s.dead + listOfNotNull(s.table.inFlight?.let { Req(it.triple, it.tsMs, it.generation) })
                    if (applied) for (r in all) x = apply(x, r)
                    x = x.copy(dead = emptyList(), table = x.table.copy(inFlight = null, timer = null), configured = false, rowZero = false)
                    if (rowZero != null) x = x.copy(decision = rowZero, rowZero = true)
                    configure(x)
                }
            }
            return out
        }

        private fun end(s: World): World {
            val f = s.table.inFlight
            return s.copy(
                dead = s.dead + listOfNotNull(f?.let { Req(it.triple, it.tsMs, it.generation) }),
                table = s.table.copy(inFlight = null, timer = null),
                configured = false, rowZero = false,
            )
        }

        private fun deadArrives(s: World, i: Int, req: Req): World {
            val rest = s.copy(dead = s.dead.filterIndexed { j, _ -> j != i })
            if (variant != Variant.NO_FENCE || !rest.configured) return rest
            // v52's lifecycle: the dead completion writes the ack, and its EVALUATE clears an equal slot.
            var t = rest.table.copy(ack = Ack.Known(req.triple), dropped = null)
            if (t.inFlight == null && t.timer == null && t.slot?.triple == req.triple) t = t.copy(slot = null)
            return rest.copy(table = t)
        }

        fun configure(s: World): World {
            val c = s.copy(configured = true, rowZero = false)
            return step(c, Event.Configure(c.e(), s.rowZero, s.clock))
        }

        fun latest(w: World): Triple? = w.server.entries
            .maxWithOrNull(compareBy({ it.value.ts }, { it.value.seq }))
            ?.let { Triple(ANON, it.key.ifEmpty { null }, it.value.analytics) }

        fun matches(server: Triple?, expected: Triple): Boolean =
            if (server == null) expected.analytics else server == expected

        fun invariant1(w: World): String? {
            val t = w.table
            if (!w.configured || t.inFlight != null || t.timer != null || t.slot != null || w.dead.isNotEmpty()) return null
            val last = w.lastDecision ?: return null
            if (t.dropped == last) return null
            return if (matches(latest(w), last)) null else "invariant 1: server ${latest(w)} ≠ last decision $last"
        }

        fun invariant2(w: World): String? {
            for (applied in listOf(true, false)) {
                var x = w
                val all = w.dead + listOfNotNull(w.table.inFlight?.let { Req(it.triple, it.tsMs, it.generation) })
                if (applied) for (r in all) x = apply(x, r)
                x = x.copy(dead = emptyList(), table = x.table.copy(inFlight = null, timer = null), configured = false)
                x = configure(x.copy(clock = x.clock + 1))
                var guard = 0
                while (guard++ < 20) {
                    val f = x.table.inFlight
                    x = when {
                        f != null -> step(apply(x, Req(f.triple, f.tsMs, f.generation)), Event.Success(f.generation))
                        x.table.timer != null -> step(x, Event.RetryFire(x.table.timer!!))
                        else -> break
                    }
                }
                val e = x.e()
                if (x.table.dropped == e) continue
                if (!matches(latest(x), e)) return "invariant 2 (relaunch applied=$applied): server ${latest(x)} ≠ E $e"
            }
            return null
        }
    }

    private data class Found(val states: Int, val failure: String?, val path: List<String>)

    private fun enumerate(variant: Variant): Found {
        val sim = Sim(variant)
        val starts = mutableListOf<World>()
        for (ack in listOf<Ack>(Ack.None, Ack.Known(Triple(ANON, U, true)), Ack.Known(Triple(ANON, U, false))))
            for (decision in listOf<Boolean?>(null, true, false))
                for (configured in listOf(false, true)) {
                    val known = (ack as? Ack.Known)?.triple
                    var w = World(
                        table = State(ack = ack), decision = decision, user = U, configured = false, rowZero = false,
                        clock = 1, dead = emptyList(),
                        server = known?.let { mapOf(U to Row(it.analytics, 0, 0)) } ?: emptyMap(), seq = 0,
                        lastDecision = known,
                    )
                    if (configured) w = sim.configure(w)
                    starts += w
                }
        val seen = HashSet<World>()
        val queue = ArrayDeque<Triple3>()
        starts.forEach { queue.addLast(Triple3(it, 0, emptyList())) }
        while (queue.isNotEmpty()) {
            val (w, depth, path) = queue.removeFirst()
            if (!seen.add(w)) continue
            (sim.invariant1(w) ?: sim.invariant2(w))?.let { return Found(seen.size, it, path) }
            if (depth < DEPTH) for ((label, next) in sim.successors(w)) queue.addLast(Triple3(next, depth + 1, path + label))
        }
        return Found(seen.size, null, emptyList())
    }

    private data class Triple3(val w: World, val depth: Int, val path: List<String>)

    @Test
    fun theProductionTableHoldsBothInvariantsOverEveryShortSequence() {
        val r = enumerate(Variant.PRODUCTION)
        println("[consent enumeration] production: ${r.states} distinct states, depth $DEPTH")
        assertEquals("violation after ${r.path}: ${r.failure}", null, r.failure)
        assertTrue("the walk is not trivially small", r.states > 1000)
    }

    @Test
    fun eachRedControlFailsTheEnumeration() {
        for (v in listOf(Variant.TIE, Variant.NO_FENCE, Variant.NO_WRITE_AHEAD)) {
            val r = enumerate(v)
            println("[consent enumeration] red control $v: ${r.failure ?: "NO VIOLATION"} after ${r.path} (${r.states} states)")
            assertTrue("red control $v found no violation in ${r.states} states", r.failure != null)
        }
    }

    companion object {
        const val DEPTH = 5
        const val ANON = "A"
        const val U = "U"
        const val U2 = "U2"
    }
}
