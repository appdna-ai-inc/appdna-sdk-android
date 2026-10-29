package ai.appdna.sdk.onboarding

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A null is not text. Wherever a template token resolves to one (a null trait identify kept as
 * `JSONObject.NULL`, a JSON null in hook data) it is unresolved: the `| fallback` applies, else the
 * token renders as nothing. It never prints "null". The shared fixture `user_trait_null_takes_fallback`
 * covers `TemplateEngine`; this covers the content-block resolver, which prints what it resolves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplateNullValueTest {

    @Test
    fun theContentBlockResolverTakesTheFallbackForANull() {
        val out = resolveTemplateString(
            "Hi {{user.name | there}} [{{hook_data.badge | none}}]",
            hookData = mapOf("badge" to JSONObject.NULL),
            responses = emptyMap(),
            userTraits = mapOf("name" to JSONObject.NULL),
        )
        assertEquals("Hi there [none]", out)
    }
}
