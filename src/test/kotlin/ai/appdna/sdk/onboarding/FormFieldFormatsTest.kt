package ai.appdna.sdk.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL rule, exercised rather than read.
 *
 * 🔴 The cases are the point, not the regex. `android.net.Uri.parse("not a url")` returns a Uri
 * with a null scheme rather than throwing, so a check written the obvious way accepts everything —
 * which is why the rule is a deliberate pattern and why these cases exist to pin what it means.
 *
 * The same list runs against the Swift twin in `FormFieldFormatsTests.swift`, and
 * `scripts/__tests__/default-user-copy-parity.test.ts` asserts the two patterns are identical
 * character for character. Three instruments, because one copy of a contract is a contract and two
 * copies are a divergence waiting.
 */
class FormFieldFormatsTest {

    private val accepted = listOf(
        "example.com",
        "https://example.com",
        "http://example.com",
        "HTTP://Example.COM/a?b=1#c",
        "sub.example.co.uk:8080/path",
        "  example.com  ",
        "a-b.example.com",
    )

    private val rejected = listOf(
        "not a url",
        "example",
        "http://",
        "https://example",
        "a b.com",
        "",
        "   ",
        "example..com",
    )

    @Test
    fun `accepts the shapes the rule describes`() {
        for (value in accepted) {
            assertTrue("$value should be accepted", FormFieldFormats.looksLikeUrl(value))
        }
    }

    @Test
    fun `rejects what is not a url`() {
        for (value in rejected) {
            assertFalse("$value should be rejected", FormFieldFormats.looksLikeUrl(value))
        }
    }

    /**
     * The scheme is optional ON PURPOSE — an author collecting "your website" should not have to
     * make every user type `https://`. Pinned so nobody tightens it without meaning to.
     */
    @Test
    fun `a bare host is a url`() {
        assertTrue(FormFieldFormats.looksLikeUrl("appdna.ai"))
    }
}
