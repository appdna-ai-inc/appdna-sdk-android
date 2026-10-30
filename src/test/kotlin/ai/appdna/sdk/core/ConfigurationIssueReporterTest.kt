package ai.appdna.sdk.core

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.generated.AppDNALifecycleDelegate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * A DROPPED BLOCK REACHES SOMEBODY, AND SAYS WHAT WAS WRONG.
 *
 * The iOS half is `ConfigurationIssueReporterTests.swift`, asserting the same three things: the
 * host is told, the report carries the DETAIL rather than a count, and the same problem is
 * reported once while a different one still gets through.
 *
 * 🔴 The rate limit is the assertion with a real failure mode on both sides. A malformed document
 * is parsed on every bootstrap and every presentation; an unbounded report would bill a customer
 * for thousands of identical events and drown their crash reporter. A mute button would pass the
 * first assertion alone, which is why the control is in the same test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigurationIssueReporterTest {

    private class Recording : AppDNALifecycleDelegate {
        val issues = mutableListOf<Triple<String, String?, String>>()
        override fun onConfigurationIssue(surface: String, id: String?, detail: String) {
            issues += Triple(surface, id, detail)
        }
    }

    private lateinit var delegate: Recording

    @Before
    fun setUp() {
        ConfigurationIssueReporter.resetForTesting()
        delegate = Recording()
        AppDNA.lifecycleDelegateOrNull = delegate
    }

    @After
    fun tearDown() {
        AppDNA.lifecycleDelegateOrNull = null
        ConfigurationIssueReporter.resetForTesting()
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the host is told which field was wrong`() {
        ConfigurationIssueReporter.report(
            surface = "onboarding_flow",
            id = "flow_123",
            detail = "steps[9].content_blocks[3]: Dictionary was null",
        )
        drain()

        assertEquals(1, delegate.issues.size)
        val (surface, id, detail) = delegate.issues.single()
        assertEquals("onboarding_flow", surface)
        assertEquals("flow_123", id)
        // 🔴 THE POINT OF THE WHOLE ISSUE: not "a block was dropped" — WHICH key.
        assertTrue(
            "the report must name the coding path; got: $detail",
            detail.contains("content_blocks[3]"),
        )
    }

    @Test
    fun `the same problem is reported once, and a different one still gets through`() {
        repeat(3) {
            ConfigurationIssueReporter.report("paywall", "pw_1", "layout.sections[0].config.style: expected String")
        }
        drain()
        assertEquals("three identical reports, one delivery", 1, delegate.issues.size)

        ConfigurationIssueReporter.report("paywall", "pw_1", "layout.sections[2].config.plans: was null")
        drain()
        assertEquals(
            "a DIFFERENT failure must still be reported — otherwise the rate limit is a mute button",
            2,
            delegate.issues.size,
        )
    }

    @Test
    fun `an empty detail is not a report`() {
        // A caller with nothing specific to say must not fire: a report with no path is the thing
        // this issue exists to stop, and an empty one is the worst version of it.
        ConfigurationIssueReporter.report("paywall", "pw_1", "")
        drain()
        assertTrue(delegate.issues.isEmpty())
    }

    @Test
    fun `a host that throws does not take the SDK down`() {
        AppDNA.lifecycleDelegateOrNull = object : AppDNALifecycleDelegate {
            override fun onConfigurationIssue(surface: String, id: String?, detail: String) {
                throw IllegalStateException("host blew up")
            }
        }
        ConfigurationIssueReporter.report("onboarding_flow", null, "steps[0]: boom")
        drain() // must not throw
    }
}
