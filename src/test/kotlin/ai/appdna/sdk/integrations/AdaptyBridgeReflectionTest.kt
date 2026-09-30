package ai.appdna.sdk.integrations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adapty 4.x shape: only `activate(Context, AdaptyConfig)`. */
object FakeAdapty4 {
    var activatedWith: String? = null
    @JvmStatic fun activate(context: Context, config: FakeAdaptyConfig) { activatedWith = config.apiKey }
}

/** Adapty 2.x shape (before AdaptyConfig): only `activate(Context, String)`. */
object FakeAdapty2 {
    var activatedWith: String? = null
    @JvmStatic fun activate(context: Context, apiKey: String) { activatedWith = apiKey }
}

/** Adapty 2.11 / 3.x shape: both. The AdaptyConfig form must win. */
object FakeAdapty3 {
    var via: String? = null
    @JvmStatic fun activate(context: Context, apiKey: String) { via = "string:$apiKey" }
    @JvmStatic fun activate(context: Context, config: FakeAdaptyConfig) { via = "config:${config.apiKey}" }
}

class FakeAdaptyConfig private constructor(val apiKey: String) {
    class Builder(private val apiKey: String) {
        fun build(): FakeAdaptyConfig = FakeAdaptyConfig(apiKey)
    }
}

/** Adapty's `com.adapty.utils.ImmutableMap`: NOT a java.util.Map — `keySet()` (Iterable) + `get(K)`. */
class FakeImmutableMap(private val m: Map<String, Any>) {
    fun keySet(): Iterable<String> = m.keys
    fun get(key: String): Any? = m[key]
    fun size(): Int = m.size
}

class FakeAccessLevel(private val active: Boolean) {
    fun isActive(): Boolean = active
}

class FakeProfile(private val levels: Any) {
    fun getAccessLevels(): Any = levels
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptyBridgeReflectionTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val configName = FakeAdaptyConfig::class.java.name

    @Before fun reset() { FakeAdapty4.activatedWith = null; FakeAdapty2.activatedWith = null; FakeAdapty3.via = null }

    /**
     * NEGATIVE CONTROL: with only the `activate(Context, String)` lookup (the old bridge), this throws
     * NoSuchMethodException — Adapty 4.x was never activated.
     */
    @Test fun `Adapty 4 - activates with AdaptyConfig built from the key`() {
        val form = AdaptyBridge.activateReflectively(FakeAdapty4::class.java, ctx, "pk_live_1", configName)
        assertEquals("AdaptyConfig", form)
        assertEquals("pk_live_1", FakeAdapty4.activatedWith)
    }

    @Test fun `Adapty 2 without AdaptyConfig - falls back to the string overload`() {
        val form = AdaptyBridge.activateReflectively(FakeAdapty2::class.java, ctx, "pk_live_2", configName)
        assertEquals("apiKey", form)
        assertEquals("pk_live_2", FakeAdapty2.activatedWith)
        // No AdaptyConfig class linked at all (the real name is absent from the test classpath).
        FakeAdapty2.activatedWith = null
        assertEquals("apiKey", AdaptyBridge.activateReflectively(FakeAdapty2::class.java, ctx, "pk_live_3"))
        assertEquals("pk_live_3", FakeAdapty2.activatedWith)
    }

    @Test fun `Adapty 3 with both - the AdaptyConfig form wins`() {
        AdaptyBridge.activateReflectively(FakeAdapty3::class.java, ctx, "pk_live_4", configName)
        assertEquals("config:pk_live_4", FakeAdapty3.via)
    }

    /**
     * NEGATIVE CONTROL: with the old `as? Map<String, Any> ?: return emptyList()`, Adapty's
     * ImmutableMap reads as no access levels — every Adapty user had no entitlement.
     */
    @Test fun `access levels are read from Adapty's ImmutableMap`() {
        val entries = AdaptyBridge.accessLevelEntries(
            FakeImmutableMap(linkedMapOf("premium" to FakeAccessLevel(true), "old" to FakeAccessLevel(false))),
        )
        assertEquals(listOf("premium", "old"), entries.map { it.first })
        assertEquals(1, AdaptyBridge.accessLevelEntries(mapOf("plain" to FakeAccessLevel(true))).size)
        assertTrue(AdaptyBridge.accessLevelEntries(null).isEmpty())
    }

    @Test fun `profileToEntitlements keeps only active access levels`() {
        val bridge = AdaptyBridge()
        val m = AdaptyBridge::class.java.getDeclaredMethod("profileToEntitlements", Any::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val out = m.invoke(bridge, FakeProfile(FakeImmutableMap(linkedMapOf(
            "premium" to FakeAccessLevel(true), "old" to FakeAccessLevel(false),
        )))) as List<ai.appdna.sdk.billing.Entitlement>
        assertEquals(listOf("premium"), out.map { it.productId })
    }

    private fun captureOut(block: () -> Unit): String {
        val old = System.out
        val buf = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buf, true))
        try { block() } finally { System.setOut(old) }
        return buf.toString()
    }

    /**
     * An adapty provider with an empty key is refused AND logged (the wrapper docs promise "refused
     * (logged)" on both platforms). NEGATIVE CONTROL: without the `Log.error` in `fromWire`, nothing
     * is printed.
     */
    @Test fun `an adapty provider with an empty key is refused and logged`() {
        for (wire in listOf<Any>(mapOf("type" to "adapty", "apiKey" to ""), mapOf("type" to "adapty"), "adapty")) {
            var decoded: Any? = "unset"
            val out = captureOut { decoded = ai.appdna.sdk.BillingProvider.fromWire(wire) }
            assertEquals(null, decoded)
            assertTrue("logged for $wire: $out", out.contains("[AppDNA][ERROR]") && out.contains("empty apiKey"))
        }
    }

    @Test fun `the bridge refuses an empty key without touching Adapty`() {
        val out = captureOut { AdaptyBridge().configure("") }
        assertTrue(out, out.contains("Adapty API key is empty"))
    }
}
