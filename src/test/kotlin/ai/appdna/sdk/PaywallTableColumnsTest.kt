package ai.appdna.sdk

import ai.appdna.sdk.paywalls.PaywallConfig
import ai.appdna.sdk.paywalls.PaywallConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison table's column headers, from both shapes `columns` arrives in.
 *
 * ## The defect this locks
 *
 * `columns` is the fallback key for `table_columns`, and the two platforms accepted different
 * shapes for it. Android's `mapNotNull` kept only `Map` elements, so a plain array of labels —
 * `["Free", "Premium"]` — was dropped in its entirety and the table rendered its rows with NO
 * COLUMN HEADERS, while iOS drew them from the same document.
 *
 * 🔴 That was not a latent asymmetry. Measured on dev: 2 of 2 comparison tables carry `columns` as
 * a string array, one of them on an ACTIVE paywall. A comparison table that does not say what it
 * is comparing has lost the point of the section.
 *
 * ## Why it is the parser under test
 *
 * On Android the shape is resolved at PARSE time (`PaywallConfigParser`), so the real parser is the
 * real subject — no renderer and no device needed to prove which columns survive. The iOS twin is
 * `PaywallTableColumnsTests.swift`, where the resolution had to be lifted out of a `some View`
 * body to be callable at all; the two are different code, so one green says nothing about the other.
 */
class PaywallTableColumnsTest {

    private fun parse(data: Map<String, Any>): PaywallConfig =
        PaywallConfigParser.parsePaywalls(
            mapOf(
                "test_paywall" to mapOf(
                    "id" to "test_paywall",
                    "name" to "Test",
                    "layout" to mapOf("type" to "stack"),
                    "sections" to listOf(mapOf("type" to "comparison_table", "data" to data)),
                ),
            ),
        )["test_paywall"]!!

    private fun columnsOf(data: Map<String, Any>) = parse(data).sections[0].data!!.table_columns

    // ── The live shape, which Android used to drop entirely ──────────────────

    @Test
    fun `a plain array of label strings becomes the column headers`() {
        val cols = columnsOf(mapOf("columns" to listOf("Free", "Premium")))
        assertEquals("the string array was dropped — this is the shape live paywalls carry", 2, cols!!.size)
        assertEquals("Free", cols[0].label)
        assertEquals("Premium", cols[1].label)
        assertNull("a plain label carries no highlight", cols[0].highlighted)
    }

    // ── The shape it already took, which must keep working ───────────────────

    @Test
    fun `an array of objects keeps its labels and its highlight`() {
        val cols = columnsOf(mapOf(
            "columns" to listOf(
                mapOf("label" to "Free"),
                mapOf("label" to "Premium", "highlighted" to true),
            ),
        ))
        assertEquals(2, cols!!.size)
        assertEquals("Premium", cols[1].label)
        assertEquals("the object form is the only one that can highlight", true, cols[1].highlighted)
    }

    /**
     * A mixed array is honoured element by element. Refusing the whole array because one element
     * has the other spelling would turn a single hand-edited entry into an erased header row —
     * the defect this fixes, in a stricter costume.
     */
    @Test
    fun `a mixed array is honoured element by element`() {
        val cols = columnsOf(mapOf(
            "columns" to listOf("Free", mapOf("label" to "Premium", "highlighted" to true)),
        ))
        assertEquals(2, cols!!.size)
        assertEquals("Free", cols[0].label)
        assertEquals("Premium", cols[1].label)
        assertEquals(true, cols[1].highlighted)
    }

    // ── Precedence ───────────────────────────────────────────────────────────

    @Test
    fun `table_columns wins over the columns fallback`() {
        val cols = columnsOf(mapOf(
            "table_columns" to listOf(mapOf("label" to "Starter", "highlighted" to true)),
            "columns" to listOf("Free", "Premium"),
        ))
        assertEquals(1, cols!!.size)
        assertEquals("table_columns is canonical and must win", "Starter", cols[0].label)
    }

    // ── The negative controls ────────────────────────────────────────────────

    /**
     * No columns at all must resolve to NO headers, not to a default pair. The console preview
     * invents `['Free', 'Pro']` here (`PaywallPreview.tsx`), which is why the gap was invisible in
     * the console; the SDK must not join it, or a table with no authored headers would claim two.
     */
    @Test
    fun `no columns resolves to no headers`() {
        assertTrue(columnsOf(emptyMap()).isNullOrEmpty())
        assertTrue(columnsOf(mapOf("columns" to emptyList<Any>())).isNullOrEmpty())
    }

    /**
     * `columns` as a NUMBER is the icon-grid / features column count sharing the same key. It must
     * not become a header, and it must not throw on the cast.
     */
    @Test
    fun `a numeric columns is the icon-grid count, not a header`() {
        val section = parse(mapOf("columns" to 3)).sections[0].data!!
        assertTrue(section.table_columns.isNullOrEmpty())
        assertEquals("the icon-grid column count still parses", 3, section.columns)
    }

    @Test
    fun `an element that is neither a string nor an object is skipped, not blank-labelled`() {
        val cols = columnsOf(mapOf("columns" to listOf("Free", 7, mapOf("label" to "Pro"))))
        assertEquals("a junk element must not become an unnamed column", 2, cols!!.size)
        assertEquals(listOf("Free", "Pro"), cols.map { it.label })
    }
}
