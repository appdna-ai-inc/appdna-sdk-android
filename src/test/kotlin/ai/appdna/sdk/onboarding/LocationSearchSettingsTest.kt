package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #963 — the Console's four location settings, resolved once for every surface.
 *
 * 🔴 WHAT WENT WRONG. There are four location surfaces, not two: a form-step field and an
 * `input_location` content block, on each platform. The form-step field read all four settings; the
 * content block read none, because it had grown its own copy of the search call that took only the
 * query. An author set Type = "City" on a content block and got street addresses, and an authored
 * Min characters did nothing — silently, with nothing logged, on both platforms.
 *
 * The duplication WAS the defect, so the fix is one resolver both surfaces call, and these are its
 * semantics.
 */
class LocationSearchSettingsTest {

    // ── from a content block's free-form field_config ────────────────────────────────────────────

    @Test
    fun `an authored type, bias and language are carried through`() {
        val s = LocationSearchSettings.from(
            mapOf(
                "location_type" to "city",
                "location_bias_country" to "PL",
                "location_language" to "pl",
                "location_min_chars" to 5,
            ),
            defaultMinChars = 3,
        )
        assertEquals("city", s.type)
        assertEquals("PL", s.biasCountry)
        assertEquals("pl", s.language)
        assertEquals(5, s.minChars)
    }

    @Test
    fun `an unauthored type stays NULL rather than becoming address`() {
        /*
         * 🔴 THE ONE THAT WOULD HAVE BEEN A SILENT BEHAVIOUR CHANGE. The server narrows results
         * only when `type` is PRESENT (`GeocodingService.ts:230-232` adds a `types` param; `:318-323`
         * maps it for the other provider). Defaulting to "address" here would have turned every
         * unauthored field from an unfiltered search into an address-only one — on iOS, where it has
         * always been unfiltered. Each surface applies its own historical default at its call site.
         */
        val s = LocationSearchSettings.from(emptyMap(), defaultMinChars = 3)
        assertNull(s.type)
        assertNull(s.biasCountry)
        assertNull(s.language)
    }

    @Test
    fun `a blank value counts as unauthored`() {
        // The Console writes "" for a cleared text input, and "" must not be sent as a country bias
        // — the proxy would filter on nothing and return nothing.
        val s = LocationSearchSettings.from(
            mapOf("location_type" to "", "location_bias_country" to "   ", "location_language" to ""),
            defaultMinChars = 3,
        )
        assertNull(s.type)
        assertNull(s.biasCountry)
        assertNull(s.language)
    }

    @Test
    fun `min chars falls back to the SURFACE's own default`() {
        // 2 in a form-step field, 3 in a content block — each surface's long-standing threshold,
        // and 3 is what the Console seeds on a new block. Preserved rather than unified, so no
        // published flow changes.
        assertEquals(3, LocationSearchSettings.from(emptyMap(), defaultMinChars = 3).minChars)
        assertEquals(2, LocationSearchSettings.from(emptyMap(), defaultMinChars = 2).minChars)
    }

    @Test
    fun `min chars never drops below one`() {
        // 0 would search an empty field: a request the proxy rejects and a dropdown nobody asked for.
        assertEquals(1, LocationSearchSettings.from(mapOf("location_min_chars" to 0), defaultMinChars = 3).minChars)
        assertEquals(1, LocationSearchSettings.from(mapOf("location_min_chars" to -4), defaultMinChars = 3).minChars)
    }

    @Test
    fun `min chars accepts the number shapes a JSON decode produces`() {
        // Firestore and a JSON round-trip both hand back a Double where the Console wrote an Int.
        assertEquals(4, LocationSearchSettings.from(mapOf("location_min_chars" to 4.0), defaultMinChars = 3).minChars)
        assertEquals(4, LocationSearchSettings.from(mapOf("location_min_chars" to 4L), defaultMinChars = 3).minChars)
        // A non-number is not a threshold: fall back rather than crash or search from 1.
        assertEquals(3, LocationSearchSettings.from(mapOf("location_min_chars" to "four"), defaultMinChars = 3).minChars)
    }

    @Test
    fun `a null config resolves to the surface's defaults`() {
        val s = LocationSearchSettings.from(null as Map<String, Any?>?, defaultMinChars = 3)
        assertNull(s.type)
        assertEquals(3, s.minChars)
    }

    // ── from a form-step field's typed FormFieldConfig ───────────────────────────────────────────

    @Test
    fun `the typed overload resolves identically to the map one`() {
        /*
         * The two surfaces take their config from different shapes — a content block carries a
         * free-form map, a form field a typed `FormFieldConfig` — and must still land on the same
         * answer. This is the assertion that keeps the two overloads from drifting, which is the
         * same failure as the two search calls that caused this issue.
         */
        val fromMap = LocationSearchSettings.from(
            mapOf(
                "location_type" to "region",
                "location_bias_country" to "DE",
                "location_language" to "de",
                "location_min_chars" to 4,
            ),
            defaultMinChars = 2,
        )
        val fromTyped = LocationSearchSettings.from(
            FormFieldConfig(
                location_type = "region",
                location_bias_country = "DE",
                location_language = "de",
                location_min_chars = 4,
            ),
            defaultMinChars = 2,
        )
        assertEquals(fromMap, fromTyped)
    }

    @Test
    fun `the typed overload treats blanks and nulls the same way`() {
        val s = LocationSearchSettings.from(
            FormFieldConfig(location_type = "", location_bias_country = null, location_language = "  "),
            defaultMinChars = 2,
        )
        assertNull(s.type)
        assertNull(s.biasCountry)
        assertNull(s.language)
        assertEquals(2, s.minChars)
    }

    @Test
    fun `a type the PROXY does not accept resolves to UNSET, not forwarded`() {
        /*
         * 🔴 THE REGRESSION #963 WOULD HAVE SHIPPED. The proxy validates `type` against
         * `z.enum(['city','address','region','country'])` and answers anything else with a
         * ValidationError — which both SDKs read as "no suggestions". So forwarding a bad value
         * turns the whole autocomplete OFF rather than degrading it.
         *
         * `coordinates` is the real one: content-block `field_config` is `.passthrough()` and so
         * UNVALIDATED, and the Console's content-block Location Type select offered it. Those
         * blocks were harmless only while this surface sent no `type` at all.
         */
        assertNull(LocationSearchSettings.from(mapOf("location_type" to "coordinates"), defaultMinChars = 3).type)
        assertNull(LocationSearchSettings.from(mapOf("location_type" to "Address"), defaultMinChars = 3).type)
        assertNull(LocationSearchSettings.from(mapOf("location_type" to "anything"), defaultMinChars = 3).type)
        // ...and the four it DOES accept still pass through, including `region`, which the
        // content-block picker was missing while offering `coordinates`.
        for (t in listOf("city", "address", "region", "country")) {
            assertEquals(t, LocationSearchSettings.from(mapOf("location_type" to t), defaultMinChars = 3).type)
        }
    }
}
