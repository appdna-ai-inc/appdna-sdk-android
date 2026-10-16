# AppDNA Android SDK (v0.3.0)

Kotlin SDK for Android. Native implementation using Activity-based UI rendering, SharedPreferences/LocalStorage for persistence, and Firebase Firestore for remote config.

---

## Public API

### Initialization

- `AppDNA.configure(context: Context, apiKey: String, environment: Environment = Environment.PRODUCTION, options: AppDNAOptions = AppDNAOptions())` -- Configure the SDK. Call once in `Application.onCreate()`. Bootstraps via `/api/v1/sdk/bootstrap`, then fetches Firestore configs.
- `AppDNA.onReady(callback: () -> Unit)` -- Register a callback that fires when the SDK is fully initialized.

### Identity

- `AppDNA.identify(userId: String, traits: Map<String, Any>? = null)` -- Link the anonymous device identity to a known user. Also starts web entitlement observer for this user.
- `AppDNA.reset()` -- Clear user identity (keeps anonymous ID). Resets experiment exposures, survey session state, and stops web entitlement observer.

### Events

- `AppDNA.track(event: String, properties: Map<String, Any>? = null)` -- Track a custom event. Also triggers survey evaluation.
- `AppDNA.flush()` -- Force flush all queued events immediately.

### Remote Config

- `AppDNA.getRemoteConfig(key: String): Any?` -- Get a remote config value by key.
- `AppDNA.isFeatureEnabled(flag: String): Boolean` -- Check if a feature flag is enabled.

### Experiments

- `AppDNA.getExperimentVariant(experimentId: String): String?` -- Get the variant assignment for an experiment. Exposure auto-tracked on first call per session.
- `AppDNA.isInVariant(experimentId: String, variantId: String): Boolean` -- Check if the user is in a specific variant.
- `AppDNA.getExperimentConfig(experimentId: String, key: String): Any?` -- Get a config value from the assigned variant's payload.

### Paywalls

- `AppDNA.presentPaywall(activity: Activity, id: String, context: PaywallContext? = null, listener: AppDNAPaywallListener? = null)` -- Present a paywall by ID from the given Activity. Launches PaywallActivity.

### Onboarding (v0.2)

- `AppDNA.presentOnboarding(activity: Activity, flowId: String? = null, listener: AppDNAOnboardingListener? = null): Boolean` -- Present an onboarding flow. If flowId is null, uses the active flow. Launches OnboardingActivity. Returns false if config unavailable.

### Push Notifications (v0.2)

- `AppDNA.setPushToken(token: String)` -- Set the FCM push token. Call from `FirebaseMessagingService.onNewToken()`.
- `AppDNA.setPushPermission(granted: Boolean)` -- Report push permission status.

### Web Entitlements (v0.3)

- `AppDNA.webEntitlement: WebEntitlement?` -- Current web subscription entitlement (read-only).
- `AppDNA.onWebEntitlementChanged(listener: (WebEntitlement?) -> Unit)` -- Register a listener for entitlement changes.
- `AppDNA.checkDeferredDeepLink(callback: (DeferredDeepLink?) -> Unit)` -- Check for a deferred deep link on first launch.

### Privacy

- `AppDNA.setConsent(analytics: Boolean)` -- Set analytics consent. When false, events are silently dropped.

### Lifecycle

- `AppDNA.shutdown()` -- Shut down the SDK, flush events, cancel coroutines, stop observers. Call from `Application.onTerminate()`.

### Configuration Options (`AppDNAOptions`)

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `flushInterval` | `Long` | 30 | Auto flush interval in seconds |
| `batchSize` | `Int` | 20 | Events per flush batch |
| `configTTL` | `Long` | 300 | Remote config cache TTL in seconds |
| `logLevel` | `LogLevel` | `WARNING` | Log verbosity (NONE/ERROR/WARNING/INFO/DEBUG) |

---

## Firestore Paths (Read)

All paths are relative to the `firestorePath` returned by bootstrap (format: `orgs/{orgId}/apps/{appId}`).

| Path | What It Reads |
|------|---------------|
| `{firestorePath}/config/flags` | Feature flags (key-value pairs) |
| `{firestorePath}/config/experiments` | Experiment definitions (variants, weights, salt, platforms) |
| `{firestorePath}/config/surveys` | Survey definitions (questions, trigger rules, follow-up actions) |
| `{firestorePath}/config/paywalls` | Paywall configurations (layout, plans, pricing) |
| `{firestorePath}/config/onboarding` | Onboarding flow definitions (steps, active_flow_id) |
| `orgs/{orgId}/apps/{appId}/users/{userId}/web_entitlements` | Web subscription entitlement document (real-time listener) |
| `orgs/{orgId}/apps/{appId}/config/deferred_deep_links/{visitorId}` | Deferred deep link context (one-time read, deleted after resolve) |

**Note**: Android does not fetch `{firestorePath}/config/flows` or `{firestorePath}/config/messages` separately (unlike iOS which fetches 7 documents). Messages are not yet independently managed on Android.

---

## API Endpoints (HTTP)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/sdk/bootstrap` | GET | Fetch orgId, appId, firestorePath, settings |
| `/api/v1/ingest/events` | POST | Batch event ingestion |
| `/api/v1/feedback/responses` | POST | Submit survey responses |

---

## Events Emitted

| Event Name | Properties | When |
|------------|------------|------|
| `sdk_initialized` | (none) | SDK is fully configured and ready |
| `experiment_exposure` | `experiment_id`, `variant`, `source` | First variant access per session per experiment |
| `survey_shown` | `survey_id`, `survey_type`, `trigger_event` | Survey presented |
| `survey_question_answered` | `survey_id`, `question_id`, `question_type`, `answer` | Individual survey question answered |
| `survey_completed` | `survey_id`, `survey_type`, `answers` | All survey questions completed |
| `survey_dismissed` | `survey_id`, `questions_answered` | Survey dismissed before completion |
| `survey_feedback_submitted` | `feedback` | Free-text feedback submitted (negative follow-up) |
| `feedback_form_dismissed` | (none) | Feedback form canceled |
| `review_prompt_shown` | `prompt_type` ("direct" or "two_step") | Review prompt displayed |
<!-- #897 — `review_prompt_accepted` / `review_prompt_declined` were removed with the two-step
     pre-screen that emitted them. Both stores forbid gating a review prompt on sentiment, and
     neither reports whether a user actually reviewed, so the outcome is not knowable. -->
| `push_token_registered` | `token_hash`, `platform` | Push token registered or changed |
| `push_permission_granted` | (none) | User granted push permission |
| `push_permission_denied` | (none) | User denied push permission |
| `web_entitlement_activated` | `plan_name`, `status` | Web entitlement became active |
| `web_entitlement_expired` | `plan_name`, `reason` | Web entitlement expired/canceled |
| `deferred_deep_link_resolved` | `path`, `params`, `visitor_id` | Deferred deep link found and resolved |

**Note**: Paywall and onboarding events (e.g., `paywall_view`, `onboarding_flow_started`) are tracked within `PaywallActivity` and `OnboardingActivity` respectively but follow the same naming convention as iOS.

---

## File Structure

### Core

- `src/main/kotlin/ai/appdna/sdk/AppDNA.kt` -- Main singleton entry point; public API surface
- `src/main/kotlin/ai/appdna/sdk/Configuration.kt` -- Environment, LogLevel, AppDNAOptions, internal Log
- `src/main/kotlin/ai/appdna/sdk/Identity.kt` -- IdentityManager and DeviceIdentity model

### Storage

- `src/main/kotlin/ai/appdna/sdk/storage/LocalStorage.kt` -- SharedPreferences wrapper for config cache, identity, push token

### Networking

- `src/main/kotlin/ai/appdna/sdk/network/ApiClient.kt` -- HTTP client for bootstrap, event ingestion, survey responses

### Events

- `src/main/kotlin/ai/appdna/sdk/events/EventTracker.kt` -- Builds event envelopes, respects consent, queues events
- `src/main/kotlin/ai/appdna/sdk/events/EventQueue.kt` -- In-memory + disk event queue, auto-flush, retry
- `src/main/kotlin/ai/appdna/sdk/events/EventSchema.kt` -- Event envelope builder (matches iOS format exactly)

### Config

- `src/main/kotlin/ai/appdna/sdk/config/RemoteConfigManager.kt` -- Fetches config documents from Firestore, parses and caches
- `src/main/kotlin/ai/appdna/sdk/config/FeatureFlagManager.kt` -- Feature flag lookups from remote config
- `src/main/kotlin/ai/appdna/sdk/config/ExperimentManager.kt` -- Experiment variant assignment, MurmurHash3 bucketing, exposure tracking

### Paywalls

- `src/main/kotlin/ai/appdna/sdk/paywalls/PaywallManager.kt` -- Paywall presentation orchestration
- `src/main/kotlin/ai/appdna/sdk/paywalls/PaywallConfig.kt` -- Paywall config model and parser
- `src/main/kotlin/ai/appdna/sdk/paywalls/PaywallActivity.kt` -- Android Activity for paywall UI

### Onboarding

- `src/main/kotlin/ai/appdna/sdk/onboarding/OnboardingFlowManager.kt` -- Onboarding flow orchestration
- `src/main/kotlin/ai/appdna/sdk/onboarding/OnboardingConfig.kt` -- Onboarding flow/step config models and parser
- `src/main/kotlin/ai/appdna/sdk/onboarding/OnboardingActivity.kt` -- Android Activity for onboarding UI

### Feedback & Surveys

- `src/main/kotlin/ai/appdna/sdk/feedback/SurveyManager.kt` -- Survey trigger evaluation, presentation, response submission, follow-up actions
- `src/main/kotlin/ai/appdna/sdk/feedback/SurveyConfig.kt` -- Survey config model (questions, trigger rules, follow-up actions)
- `src/main/kotlin/ai/appdna/sdk/feedback/SurveyActivity.kt` -- Android Activity for survey UI
- `src/main/kotlin/ai/appdna/sdk/feedback/SurveyFrequencyTracker.kt` -- Session/lifetime frequency tracking
- `src/main/kotlin/ai/appdna/sdk/feedback/ReviewPromptManager.kt` -- Google Play In-App Review API, two-step prompt, rate limiting (3/year, 90 days)
- `src/main/kotlin/ai/appdna/sdk/feedback/views/` -- NpsQuestionView, CsatQuestionView, RatingQuestionView, EmojiScaleView, YesNoView, SingleChoiceView, MultiChoiceView, FreeTextView

### Integrations

- `src/main/kotlin/ai/appdna/sdk/integrations/RevenueCatBridge.kt` -- RevenueCat billing integration
- `src/main/kotlin/ai/appdna/sdk/integrations/PushTokenManager.kt` -- FCM push token capture and storage

### Web Entitlements & Deep Links

- `src/main/kotlin/ai/appdna/sdk/webentitlements/WebEntitlementManager.kt` -- Real-time Firestore listener for web entitlements, with SharedPreferences cache
- `src/main/kotlin/ai/appdna/sdk/deeplinks/DeferredDeepLinkManager.kt` -- First-launch deferred deep link resolution (clipboard, Android Install Referrer)

---

## Backend Module Dependencies

- **monetization**: reads paywall configs from `{firestorePath}/config/paywalls`
- **onboarding**: reads onboarding flow configs from `{firestorePath}/config/onboarding`
- **experiments**: reads experiment configs from `{firestorePath}/config/experiments`
- **feature-flags**: reads flags from `{firestorePath}/config/flags`
- **feedback**: reads survey configs from `{firestorePath}/config/surveys`; posts responses to `/api/v1/feedback/responses`
- **web-entitlements**: observes user entitlements at `orgs/{orgId}/apps/{appId}/users/{userId}/web_entitlements`
- **deep-links**: reads deferred deep link context from `orgs/{orgId}/apps/{appId}/config/deferred_deep_links/{visitorId}`
- **ingest**: sends batched events to `/api/v1/ingest/events`
- **sdk-bootstrap**: fetches org/app context from `/api/v1/sdk/bootstrap`

---

## Rule

Any new module feature that writes config to Firestore or adds new events MUST update this SDK. When adding a new Firestore config document, update `RemoteConfigManager.fetchConfigs()` to fetch it and add a corresponding parse method.
