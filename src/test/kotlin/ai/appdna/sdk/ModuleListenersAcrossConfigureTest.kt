package ai.appdna.sdk

import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.messages.MessageManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/**
 * What a host registers on `AppDNA.inAppMessages`, `AppDNA.remoteConfig` and `AppDNA.features` must
 * reach the managers `configure()` builds — when it was registered before `configure()`, and across
 * `shutdown()` → `configure()`. iOS keeps all of it on the module.
 *
 * They were written to the CURRENT manager only (`AppDNA.messageManager?.delegate = …`,
 * `manager?.addChangeListener(…)`): a null-safe DROP before `configure()`, and a new manager after
 * `shutdown()` → `configure()` did not have them. `FeatureFlagManager.addChangeListener` was a stub,
 * so `features.onChanged` never fired at all.
 *
 * Drives the REAL `AppDNA.configure` / `AppDNA.shutdown`.
 * NEGATIVE CONTROL: with `AppDNAModules.kt`, `AppDNA.kt` and `FeatureFlagManager.kt` as they were,
 * every test here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModuleListenersAcrossConfigureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val delegate = object : AppDNAInAppMessageDelegate {}
    private val veto: suspend (String) -> Boolean = { true }

    @Before
    fun setUp() {
        AppDNA.shutdown()
        AppDNA.inAppMessages.setDelegate(null)
        AppDNA.inAppMessages.setAsyncShouldShowMessage(null)
    }

    @After
    fun tearDown() {
        AppDNA.beforeMessageManagerPublishForTesting = null
        AppDNA.inAppMessages.setDelegate(null)
        AppDNA.inAppMessages.setAsyncShouldShowMessage(null)
        AppDNA.shutdown()
    }

    private fun configure() = AppDNA.configure(context, "adn_test_module_listeners")

    private fun messageManager(): MessageManager {
        val m = AppDNA.messageManager
        assertNotNull("configure builds the MessageManager", m)
        return m!!
    }

    /** The RemoteConfigManager the running SDK owns (private on `AppDNA`). */
    private fun remoteConfigManager(): RemoteConfigManager {
        val f = AppDNA::class.java.getDeclaredField("remoteConfigManager").apply { isAccessible = true }
        return f.get(AppDNA) as RemoteConfigManager
    }

    // ── In-app messages (delegate + async veto) ─────────────────────────────

    @Test
    fun `an in-app delegate and async veto set before configure reach the configured MessageManager`() {
        assertNull("precondition: no MessageManager before configure", AppDNA.messageManager)
        AppDNA.inAppMessages.setDelegate(delegate)
        AppDNA.inAppMessages.setAsyncShouldShowMessage(veto)

        configure()

        assertSame(delegate, messageManager().delegate)
        assertSame(veto, messageManager().asyncShouldShowMessage)
    }

    @Test
    fun `the in-app delegate and async veto survive shutdown and are applied by the next configure`() {
        configure()
        AppDNA.inAppMessages.setDelegate(delegate)
        AppDNA.inAppMessages.setAsyncShouldShowMessage(veto)
        val first = messageManager()
        assertSame(delegate, first.delegate)

        AppDNA.shutdown()
        configure()

        val second = messageManager()
        assert(second !== first) { "configure after shutdown builds a new MessageManager" }
        assertSame("re-configure must re-apply the host's delegate", delegate, second.delegate)
        assertSame("re-configure must re-apply the host's async veto", veto, second.asyncShouldShowMessage)
    }

    @Test
    fun `clearing the in-app delegate and veto before configure leaves the configured manager without them`() {
        AppDNA.inAppMessages.setDelegate(delegate)
        AppDNA.inAppMessages.setAsyncShouldShowMessage(veto)
        AppDNA.inAppMessages.setDelegate(null)
        AppDNA.inAppMessages.setAsyncShouldShowMessage(null)

        configure()

        assertNull(messageManager().delegate)
        assertNull(messageManager().asyncShouldShowMessage)
    }

    // ── remoteConfig.onChanged / features.onChanged ─────────────────────────

    @Test
    fun `remoteConfig and features onChanged registered before configure fire for the configured SDK`() {
        val configChanges = mutableListOf<Map<String, Any>>()
        val flagChanges = mutableListOf<Map<String, Boolean>>()
        AppDNA.remoteConfig.onChanged { configChanges += it }
        AppDNA.features.onChanged { flagChanges += it }

        configure()
        remoteConfigManager().parseFlagDocForTesting("beta_checkout", mapOf("value" to true, "type" to "boolean"))

        assertEquals(true, configChanges.lastOrNull()?.get("beta_checkout"))
        assertEquals(true, flagChanges.lastOrNull()?.get("beta_checkout"))
    }

    @Test
    fun `remoteConfig and features onChanged survive shutdown and fire for the next configure, once each`() {
        configure()
        val configChanges = mutableListOf<Map<String, Any>>()
        val flagChanges = mutableListOf<Map<String, Boolean>>()
        AppDNA.remoteConfig.onChanged { configChanges += it }
        AppDNA.features.onChanged { flagChanges += it }

        AppDNA.shutdown()
        configure()
        configChanges.clear(); flagChanges.clear()
        remoteConfigManager().parseFlagDocForTesting("dark_mode", mapOf("value" to "off", "type" to "string"))

        assertEquals("one change, one call (not attached twice)", 1, configChanges.count { it.containsKey("dark_mode") })
        assertEquals("off", configChanges.last()["dark_mode"])
        assertEquals(1, flagChanges.count { it.containsKey("dark_mode") })
        assertEquals("a flag map carries isEnabled values", false, flagChanges.last()["dark_mode"])
    }

    // ── ────────────────────────────────────────────────────────────

    /**
     * `shutdown()` nulls `remoteConfig.manager` / `features.manager`, but `attachedTo` kept the torn-down
     * manager: it stayed reachable from the module, and a callback registered after shutdown (before the
     * next configure) was attached to the dead manager too.
     * NEGATIVE CONTROL: with the setter's `value?.let { attach(it) }` (no detach) `attachedTo` is the
     * old manager after shutdown and the dead manager fires the late callback.
     */
    @Test
    fun `shutdown forgets the torn-down remoteConfig and features manager`() {
        configure()
        val dead = remoteConfigManager()
        AppDNA.shutdown()

        fun attachedTo(module: Any): Any? =
            module.javaClass.getDeclaredField("attachedTo").apply { isAccessible = true }.get(module)
        assertNull("remoteConfig must not pin the torn-down manager", attachedTo(AppDNA.remoteConfig))
        assertNull("features must not pin the torn-down manager", attachedTo(AppDNA.features))

        val late = mutableListOf<Map<String, Any>>()
        AppDNA.remoteConfig.onChanged { late += it }
        dead.parseFlagDocForTesting("after_shutdown", mapOf("value" to true, "type" to "boolean"))
        assertEquals("a callback registered after shutdown is not attached to the dead manager", 0, late.size)

        configure()
        remoteConfigManager().parseFlagDocForTesting("next_sdk", mapOf("value" to true, "type" to "boolean"))
        assertEquals("…and reaches the next one", true, late.lastOrNull()?.get("next_sdk"))
    }

    /**
     * A host thread's `setDelegate` landing between `configure()` building the MessageManager and
     * publishing it: `applyTo` ran inside the build (before the window) and `setDelegate` found no
     * manager yet, so the configured manager never got the delegate.
     * NEGATIVE CONTROL: with `.also { inAppMessages.applyTo(it) }` back on the builder (applied before
     * the publish) the manager's delegate and veto are null.
     */
    @Test
    fun `an in-app delegate set while configure publishes the manager is not lost`() {
        AppDNA.beforeMessageManagerPublishForTesting = {
            AppDNA.inAppMessages.setDelegate(delegate)
            AppDNA.inAppMessages.setAsyncShouldShowMessage(veto)
        }
        configure()
        assertSame(delegate, messageManager().delegate)
        assertSame(veto, messageManager().asyncShouldShowMessage)
    }

    /** The module's delegate is read and written on different threads: volatile, like the manager's. */
    @Test
    fun `the in-app delegate fields are volatile and the publish is volatile`() {
        val module = AppDNA.inAppMessages.javaClass.getDeclaredField("delegate")
        assert(java.lang.reflect.Modifier.isVolatile(module.modifiers)) { "InAppMessagesModule.delegate must be @Volatile" }
        val published = AppDNA::class.java.getDeclaredField("messageManager")
        assert(java.lang.reflect.Modifier.isVolatile(published.modifiers)) { "AppDNA.messageManager must be @Volatile" }
    }
}
