package ai.appdna.sdk

import ai.appdna.sdk.billing.BillingScenario
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.feedback.SurveyActivity
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyConfig
import ai.appdna.sdk.feedback.SurveyManager
import ai.appdna.sdk.feedback.SurveyResult
import ai.appdna.sdk.messages.MessageConfig
import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.messages.MessageManager
import ai.appdna.sdk.messages.MessageSurface
import ai.appdna.sdk.onboarding.OnboardingActivity
import ai.appdna.sdk.onboarding.OnboardingFlowManager
import ai.appdna.sdk.paywalls.DismissReason
import ai.appdna.sdk.paywalls.PaywallActivity
import ai.appdna.sdk.paywalls.PaywallContext
import ai.appdna.sdk.paywalls.PaywallManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Looper
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Drives `emit_surface_events` (`event_emission/content_stamp_*`) through the REAL managers:
 * the served item is parsed by the platform's own config parser, presented by the surface's own manager, and
 * every event is emitted by the SDK's own emission site into the caller's real pipeline. The driver only
 * plays the user (a step viewed, a CTA tapped, a store answering) — it never writes an event itself.
 *
 * Shared by `SharedFixtureTest` and the per-surface Robolectric tests, so both exercise the same code.
 */
internal class ContentStampFixtureDriver(
    private val ctx: Context,
    private val tracker: EventTracker,
    private val action: JSONObject,
    private val unsupported: (String) -> Nothing,
) {
    private val host: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun idle(times: Int = 20) = repeat(times) { shadowOf(Looper.getMainLooper()).idle() }

    /** Present [item] on [surface] and drive the events [drive] names, in order. */
    fun drive(surface: String, item: Map<String, Any>, drive: List<String>) {
        val id = item["id"] as? String ?: unsupported("emit_surface_events: an item without a string id")
        when (surface) {
            "onboarding_flow" -> onboarding(id, item, drive)
            "paywall" -> paywall(id, item, drive)
            "paywall_purchase" -> purchase(id, item, drive)
            "in_app_message" -> message(id, item, drive)
            "survey" -> survey(id, item, drive)
            else -> unsupported("emit_surface_events: no driver for surface=$surface")
        }
    }

    private fun remoteConfig(section: String, data: Map<String, Any>): RemoteConfigManager =
        RemoteConfigManager(firestorePath = null, storage = LocalStorage(ctx), configTTL = 3600L)
            .also { it.loadBundledConfig(mapOf(section to data)) }

    // -- onboarding: OnboardingFlowManager.present, then the manager's own event closures --------------------

    private fun onboarding(id: String, item: Map<String, Any>, drive: List<String>) {
        val rcm = remoteConfig("onboarding", mapOf("active_flow_id" to id, "flows" to mapOf(id to item)))
        val manager = OnboardingFlowManager(rcm, tracker)
        try {
            check(manager.present(host, id)) { "the flow $id did not present" }
            val payload = OnboardingActivity.peekPendingLaunchPayloadForTest()
                ?: unsupported("onboarding: present() queued no launch payload")
            val step = payload.flow.steps.first().id
            for (name in drive) when (name) {
                "onboarding_flow_started" -> Unit // present() emitted it
                "onboarding_step_viewed" -> payload.onStepViewed!!(step, 0)
                "onboarding_step_completed" -> payload.onStepCompleted!!(step, 0, null)
                "onboarding_step_skipped" -> payload.onStepSkipped!!(step, 0)
                "onboarding_flow_completed" -> payload.onFlowCompleted!!(emptyMap())
                "onboarding_flow_dismissed" -> payload.onFlowDismissed!!(step, 0)
                else -> unsupported("onboarding: no driver for $name")
            }
        } finally {
            OnboardingActivity.clearActiveLaunches()
        }
        idle()
    }

    // -- paywall: PaywallManager.present, then the launched activity's own dismiss slot -------------------

    private fun paywall(id: String, item: Map<String, Any>, drive: List<String>) {
        val pm = PaywallManager(remoteConfig("paywalls", mapOf(id to item)), tracker)
        @Suppress("UNCHECKED_CAST")
        val custom = action.optJSONObject("custom_data")?.let { o ->
            o.keys().asSequence().associateWith { o.get(it) } as Map<String, Any>
        }
        pm.present(host, id, PaywallContext(placement = action.optString("placement", "fixture"), customData = custom))
        idle()
        for (name in drive) when (name) {
            "paywall_view" -> Unit // present() emitted it after the store lookup
            "paywall_close" -> {
                val token = generateSequence { shadowOf(host).nextStartedActivity }
                    .firstOrNull { it.component?.className == PaywallActivity::class.java.name }
                    ?.getStringExtra("paywall_launch_token")
                    ?: unsupported("paywall: present() launched no PaywallActivity")
                val slots = PaywallActivity.slotsForToken(token) ?: unsupported("paywall: no launch slots for $token")
                slots.onDismiss!!(DismissReason.DISMISSED)
            }
            else -> unsupported("paywall: no driver for $name")
        }
        PaywallActivity.clearActiveLaunches()
        idle()
    }

    // -- a purchase started from the paywall: PaywallManager.handlePurchase → the real billing manager ---

    private fun purchase(id: String, item: Map<String, Any>, drive: List<String>) {
        val rcm = remoteConfig("paywalls", mapOf(id to item))
        val config = rcm.getPaywallConfig(id) ?: unsupported("purchase: the paywall $id did not parse")
        val plan = config.plans?.firstOrNull() ?: unsupported("purchase: the paywall $id has no plan")
        val type = action.optString("product_type", "inapp")
        val token = "tok_$id"
        // A store that sells the plan's product and a server that verifies it (BillingScenario's shape).
        val scenario = BillingScenario(
            ctx,
            JSONObject(
                """{
                  "product_details":{"${plan.product_id}":{"price_micros":9990000,"currency":"USD","product_type":"$type"}},
                  "owned_purchases":[{"product_id":"${plan.product_id}","token":"$token","order_id":"GPA.$id","acknowledged":false,"product_type":"$type","purchase_state":"PURCHASED"}],
                  "listener_purchase_token":"$token",
                  "verify_replies":{"$token":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
                }""",
            ),
        )
        try {
            for (name in drive) {
                if (name !in setOf("purchase_started", "purchase_completed")) unsupported("purchase: no driver for $name")
            }
            PaywallManager(rcm, tracker).handlePurchase(
                activity = host, paywallId = id, plan = plan, config = config, metadata = emptyMap(), listener = null,
            )
            scenario.idle(200)
        } finally {
            scenario.teardown()
        }
    }

    // -- an in-app message from the served set: MessageManager's trigger path and its surface callbacks ---

    private class CapturingSurface(private val activity: Activity) : MessageSurface {
        var onCTATap: ((Activity) -> Unit)? = null
        var onDismiss: (() -> Unit)? = null
        var onClosed: (() -> Unit)? = null
        override fun host(): Activity = activity
        override fun show(
            messageId: String,
            activity: Activity,
            config: MessageConfig,
            onCTATap: (Activity) -> Unit,
            onDismiss: () -> Unit,
            onClosed: () -> Unit,
        ) {
            this.onCTATap = onCTATap
            this.onDismiss = onDismiss
            this.onClosed = onClosed
        }
        override fun observeActivation(block: () -> Unit): () -> Unit = {}
    }

    private fun message(id: String, item: Map<String, Any>, drive: List<String>) {
        val config = MessageConfigParser.parseSingleMessage(item) ?: unsupported("message: $id did not parse")
        val surface = CapturingSurface(host)
        val manager = MessageManager(ctx, { mapOf(id to config) }, surface = surface)
        manager.onEvent(config.trigger_rules.event, emptyMap())
        idle()
        for (name in drive) when (name) {
            "in_app_message_shown" -> Unit // the trigger showed it
            "in_app_message_clicked" -> (surface.onCTATap ?: unsupported("message: $id was not shown"))(host)
            "in_app_message_dismissed" -> (surface.onDismiss ?: unsupported("message: $id was not shown"))()
            else -> unsupported("message: no driver for $name")
        }
        surface.onClosed?.invoke()
        idle()
    }

    // -- a survey: SurveyManager.present, the activity's answer callback, and its result ------------------

    private fun survey(id: String, item: Map<String, Any>, drive: List<String>) {
        val config = SurveyConfig.fromMap(item) ?: unsupported("survey: $id did not parse")
        val manager = SurveyManager(ctx, tracker)
        manager.updateConfigs(mapOf(id to config))
        manager.present(id)
        idle()
        val answers = action.optJSONObject("answers") ?: JSONObject()
        for (name in drive) when (name) {
            "survey_shown" -> Unit // present() emitted it
            "survey_question_answered" -> for (q in config.questions) {
                val answer = answers.opt(q.id) ?: continue
                (SurveyActivity.questionAnsweredCallback ?: unsupported("survey: $id was not launched"))(q.id, q.type, answer)
            }
            "survey_completed" -> SurveyActivity.finishLaunchedForTest(
                SurveyResult.Completed(config.questions.mapNotNull { q -> answers.opt(q.id)?.let { SurveyAnswer(q.id, it) } }),
            )
            "survey_dismissed" -> SurveyActivity.finishLaunchedForTest(SurveyResult.Dismissed(0))
            else -> unsupported("survey: no driver for $name")
        }
        manager.shutdown()
        idle()
    }
}
