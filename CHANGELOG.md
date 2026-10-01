# Changelog

All notable changes to the AppDNA Android SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [1.0.54] — unreleased

Wraps iOS 1.0.82. Ships maps and host data (including "Show more").

### Added
- **Push action buttons and custom sound from the Console.** A notification the SDK builds shows the
  push's buttons. A button that opens the app routes its own action through `handlePushTap`
  (`open_url` now routes like `deep_link`). `dismiss` and buttons set not to open the app go to the
  new `PushActionReceiver`, which is declared in the SDK manifest. A text-reply button delivers its
  text as `data["reply_text"]` in `onPushTapped`. The notification is removed when one of its buttons
  is tapped (for a button that opens the app, by `handlePushTap`). A custom sound plays from `res/raw/<name>` on its own `appdna_sound_<name>` channel, which
  the SDK creates the first time it sees the sound.
- **Maps.** A `map` block can use Google as well as Mapbox (the customer's own key,
  delivered like the Mapbox token), draw a real pannable map through the bundled Google Maps SDK
  where the app can (it needs your key in the `com.google.android.geo.API_KEY` manifest meta-data;
  without it the map is static), style with Google map types / styled-map JSON, and be placed as a
  fullscreen backdrop or anchored to the top or bottom of the step instead of inline. The route line is
  the encoded polyline (Google, precision 5), otherwise straight segments between the stops. With
  auto-fit on (the default) the camera fits the route and its stops, and a single point uses zoom 15;
  with auto-fit off it centres on the authored centre at the authored zoom.
- **Host data for every element.** `{{hook_data.…}}`, `data_templates` and bindings
  resolve on the raw step JSON for every onboarding field; an `input_select` `repeat` generates its
  options from a host list, with a pending state until `onBeforeStepRender` answers.
- **"Show more".** `ElementInteractionResult.dataContext` merges into the step's
  `hook_data` per top-level key (a `null` value removes the key), so a `refresh_step` button can load
  the next page into a repeat Select. `refresh_step` now also fires from buttons nested in rows,
  stacks, section backgrounds and carousel pages (before, it silently did nothing there). The tapped
  button shows a spinner; a refresh ends after at most 8 s. New `ElementInteractionResult.
  decodeDataContext` and `minimumBridgeTimeoutMs` for wrapper bridges.
- **Device time zone on every event.** The event envelope carries `device.timezone`, the IANA id
  from `TimeZone.getDefault()` read per event (omitted when the platform reports none). The AppDNA
  server keeps the latest one on the identified user's profile and uses it for pushes sent in the
  user's time zone, push quiet hours and journey waits — after a `timezone` identify trait, which still
  takes precedence. No code change needed.

### Changed
- `onElementInteraction` behaviour for every flow: `inputValuePatches` merge onto the answers as they
  are when the reply lands (a pick made during the call is kept); a reply that arrives after the user
  left the step — or started a newer presentation of it — is dropped, `advance` included; the
  in-flight lock is per step presentation; a call started from the outgoing step during the exit
  transition is refused (it used to be sent with the incoming step's id); interaction overlays reset
  when the same view is reused for a new presentation.

### Fixed
- `push_dismissed` was never tracked: `PushDismissReceiver` was not declared in the manifest, so the
  notification's delete intent reached no receiver. It is declared now.
- A throwing `onElementInteraction` no longer crashes the app; it is treated as `null`.
- A text-reply button's typed text reaches `onPushTapped` on Android 7–11: its PendingIntent carried
  `FLAG_IMMUTABLE` there, so the system could not fill the text in. It is mutable now (`FLAG_MUTABLE`
  on Android 12+, no mutability flag below); every other button stays immutable.
- A push button with **Open app in foreground** off (and `dismiss`), tapped while the app was not
  running, is tracked: the receiver waits up to 8 s for `configure` instead of dropping the tap. As on
  iOS it does not open the app; the button's action is routed, and a route that needs the app on
  screen takes effect only if the app is already in front.
- The tap intent of a notification posted by SDK 1.0.53 or earlier is recognised by its exact extras
  only; a host's own notification that merely carries `push_id` / `action_type` / `action_value` is no
  longer tracked and routed as an AppDNA tap.
- Adapty bridge: its entitlement and restore lists name the store product (`AccessLevel.vendorProductId`), as on iOS;
  they named the access level (e.g. `premium`).
- One renewal seen by two overlapping reconciles emits one `subscription_renewed` (it could emit two).
  A reconcile now reads Play inside the same lock as its diff, and an older read of a subscription
  (an earlier purchase time) is never a renewal: a reconcile that read Play before a renewal but
  finished after one that read it later used to emit a second, backwards `subscription_renewed` and
  store the older state.
- The default push channel, `appdna_push`, is created with the SDK's own settings (name "Push
  Notifications", importance high). It was created from the first push's `channel_importance` /
  `channel_name` / `channel_description`, so one campaign sent at Low or Min importance fixed every
  later push without a custom sound at that importance. Those keys now apply to a custom-sound channel
  only. The channel a campaign's Android Channel ID names (the server sends it as `channel_id` in the
  message data; custom data cannot set that key) is created the same way and named
  "Push Notifications (<id>)"; it was named "Push Notifications" too, like the default channel.
- An unreadable billing-ledger value is kept under `<key>.corrupt.<epoch-ms>`; the newest three copies
  per key are kept (listed under `<key>.corrupt.index`). A second unreadable value used to overwrite
  the single `<key>.corrupt` copy. A `<key>.corrupt` copy left by an earlier SDK is listed in the index
  as the oldest copy and is removed like one.
- `diagnose()` reports the veto timeout a wrapper was given (`0.5`, not the whole-second `1`): new
  `AppDNAOptions.vetoTimeoutSeconds` (a body property, so the constructors and `copy` are unchanged).

### Binary compatibility
- `ElementInteractionResult` gains `dataContext` and `AppliedInteraction` gains `inputValuePatches`
  and `dataContext`, each as a defaulted LAST constructor parameter. Kotlin source is unchanged, and
  Java callers keep compiling: both constructors are `@JvmOverloads`, and
  `ElementInteractionResult(Map, Map, boolean)` — the pre-1.0.51 shape — is restored as an explicit
  constructor. What is NOT preserved for **prebuilt** Kotlin binaries compiled against ≤ 1.0.53:
  both are data classes, so their `copy` / `copy$default` signatures changed, and so did the
  synthetic default-arguments constructor `<init>(…, int, DefaultConstructorMarker)` that Kotlin
  emits for a call using defaulted or named arguments. A prebuilt Kotlin library that calls `copy`
  on either type, or constructs either one with defaulted or named arguments, must be recompiled, or
  it fails with `NoSuchMethodError`.

### Billing ownership, sign-in timeout, push forwarding, consumables, location

Needs the AppDNA server from the same release (purchase verification and push tracking rely on it).

#### Changed — billing ownership
- **`billingProvider` now decides who owns a Play transaction.** Only `StoreKit2` (the default, and
  what you get when the option is omitted) lets the SDK buy, verify, acknowledge and consume. Under
  `RevenueCat` or `Adapty(apiKey)` the SDK never verifies, acknowledges or consumes anything and
  makes no verify / restore call; its entitlement cache is built read-only from Play's own purchase
  list. Under `None` there is no billing manager or entitlement cache at all
  (`hasActiveSubscription()` is `false`). Before, a `RevenueCat` / `Adapty` host's paywall bought directly through Play.
- **A paywall tap under `RevenueCat`, `Adapty` or `None` fails loudly:** one `purchase_failed`
  (`error_type: "providerNotAvailable"`), then `onPaywallPurchaseFailed(errorType =
  "providerNotAvailable", productId = <the tapped plan>)`, then the paywall's failure route. No
  `purchase_started` and no `onPaywallPurchaseStarted`. Start the purchase with your provider from
  that callback.
- `AppDNA.billing.purchase` under `None` throws `BillingError.ProviderNotAvailable` (it threw
  `IllegalStateException`) and emits one `purchase_failed`. `purchase` / `restorePurchases` called
  before `configure` has set billing up fail with "AppDNA SDK not configured yet — call configure()
  first" (`errorType` `unknown`).
- **Lifecycle events under `RevenueCat` stop.** `subscription_renewed`, `subscription_canceled` and
  `subscription_renewal_failed` are no longer emitted by the device; the RevenueCat webhook is the
  single source. `Adapty` keeps them.
- **`restorePurchases()` now throws on failure** (it returned `[]`): `providerNotAvailable` under
  `RevenueCat`, `Adapty` or `None` (restore through your provider); on `StoreKit2`, `networkError`
  when the restore call fails on the network or Play's query fails with `NETWORK_ERROR` /
  `SERVICE_UNAVAILABLE`, and `serverError` for any other failed Play query, no Play connection within
  30 s, or a non-2xx reply. Entitlements stay unchanged and `onRestoreCompleted([])` no longer fires;
  `purchase_restore_failed` is tracked on every failed restore once billing has initialised; a
  restore before that (including right after `configure()` returns) throws the `unknown` "not
  configured yet" error untracked (a cancelled coroutine's `CancellationException` is rethrown untracked). The Java `restorePurchasesAsync()`
  now completes exceptionally.
- The SDK sends **`billing_owner`** on its verify and restore calls. An app with a connected
  RevenueCat or Adapty integration that still runs an older SDK on `StoreKit2` gets its
  verifications refused (409 `provider_owned`) until it updates.
- **Verification failures report `errorType` `verificationFailed`** (was `unknown`) —
  `networkError` when the verify does not answer within 30 s — on `purchase_failed` and on the paywall delegate's `onPaywallPurchaseFailed`; `purchase_failed.reason`
  is the server's code when there is one (e.g. `store_credentials_missing`), else
  `verification_failed`. `productNotFound` now reaches you as the purchase error type. A purchase
  started while the Play connection is failing fails within 30 s with `serverError`.
- **`TransactionInfo.transactionId`** — including `AppDNA.billing.purchase()`'s result — is the Play
  order id, falling back to the **purchase token** when Play gives no `orderId`. Before,
  `purchase()` always returned the product id, and the delegates fell back to the product id. Grant idempotently by it.
- **`appAccountToken` derivation matches iOS and the server.** Only a canonical
  8-4-4-4-12 UUID user id is used as-is; any other id — including a dash-separated short form such
  as `1-2-3-4-5`, which Java's lenient `UUID.fromString` used to accept and zero-pad
  (`00000001-0002-0003-0004-000000000005`) instead of hashing — is hashed. A
  purchase with a custom `PurchaseOptions.appAccountToken` is now verified and granted to the caller
  unless another user owns it. Before, the server refused every Android verify call with 401 (the SDK
  sent no credential the route accepted), so the purchase was never acknowledged and Play refunded it
  after 3 days.

#### Added
- `AppDNA.billing.setDelegate(delegate, deliversPurchases)` (the one-argument
  `setDelegate(delegate)` is kept); pass `false` for a delegate that should not receive queued late
  purchases.
- `PurchaseResult.Failed.errorType` and `PurchaseFailedException.errorType` — the
  `billingErrorType` of the underlying failure (`verificationFailed`, `serverError`, …), or `null`
  for a failure Play's purchase listener reported and for a shutdown.
- **Push forwarding for apps that own Firebase Messaging:** `AppDNA.push.isAppDNAMessage(data)`,
  `handleMessage(remoteMessage)`, `handleMessageData(data)` (never displays) and
  `handleTapData(data, actionId)`. Each is marker-gated and idempotent (one tracked delivery / tap
  per `delivery_id ?: push_id`). The SDK's messaging service can be turned off with the resource
  bool `appdna_messaging_service_enabled` = `false` (or `tools:node="remove"`).
- `StepAdvanceResult.minimumBridgeTimeoutMs(stepData)` and `AUTH_BRIDGE_TIMEOUT_MS` (120 s) for
  wrapper bridges: sign-in actions in `onBeforeStepAdvance` get at least 120 s on Flutter and React
  Native.
- **New event properties:** `emitted_by` on the SDK's billing events (and the public `AppDNA.track`
  drops a host-passed `emitted_by` / `_appdna_origin`, also before `configure`); `transaction_id` /
  `original_transaction_id` / `cancel_semantics` on lifecycle events; `original_transaction_id` on
  `purchase_completed` and `subscription_started`; `is_consumable` on `purchase_completed`;
  `delivery_id` on `push_delivered` / `push_tapped`; `quantity` (only when > 1) on
  `purchase_completed`; `error_type` (and `reason: "not_configured"` on the refusal) on
  `purchase_restore_failed`; `reason: "item_already_owned"` on the `purchase_restored` a re-buy of an owned
  item emits (a restore's `purchase_restored` carries no `reason`). `purchase_failed.reason` now carries
  the server's code for a verification failure.

#### Changed — purchases reported late, consumables
- **Consumables are consumed immediately** on every path, so they can be bought again at once. Mark
  them Consumable in the console's product settings. `TransactionInfo` has no quantity: for a
  quantity > 1 a host under-grants (known).
- **Late purchases are reported.** A purchase whose verification failed, or that was made outside
  the app (Play Store resubscribe, a promo redemption, a PENDING purchase completing while the app
  was killed, a plan change), is reported once at the next return to the foreground, `identify`, restore or
  `refreshEntitlementCache()` as
  `purchase_completed` (+ `subscription_started` for a subscription) with an empty `paywall_id`
  (`""`). Every report that did not resolve a live `purchase()` caller is queued
  (`appdna.pending_deliveries_v1`, 100 entries / 30 days) and delivered through `onPurchaseCompleted`
  while a delivering delegate is set: when it is set, once billing has initialised during
  `configure`'s bootstrap, and after each return to the foreground, `identify`, restore and
  `refreshEntitlementCache()`. Delivery is at
  least once — grant idempotently by `transactionId`. A reinstall does not re-report purchases
  acknowledged before it. A late-reported plan change looks like a new subscription (known).
- The public `BillingModule.refreshEntitlementCache()` now also completes unreported purchases and
  may emit / call `onPurchaseCompleted`.
- Re-tapping an owned item gives one `purchase_restored{reason: "item_already_owned"}`, not a
  second conversion; restore emits no `purchase_restored` for a product the same restore just reported.
  `ITEM_ALREADY_OWNED` returned synchronously by `launchBillingFlow` is handled like the listener
  result (it was `launch_billing_flow_failed`).
- `ITEM_ALREADY_OWNED` can also resolve `purchase()` as `Failed`: `Failed("item_already_owned")` when
  an owned consumable's pending consume fails again, and `Failed` with `errorType`
  `verificationFailed` (`networkError` on a 30 s timeout; one `purchase_failed`) when the owned
  purchase's verification fails.
- A paywall tap made after `configure` but before billing has initialised reports `errorType`
  `unknown` with "AppDNA SDK not configured yet — call configure() first" (not
  `providerNotAvailable`, which now means only "this provider does not buy through AppDNA").
  A paywall **restore** tap in that window, or after `shutdown()`, fails the same way:
  `onPaywallRestoreFailed` with that `unknown` error and one `purchase_restore_failed`
  (`reason: "not_configured"`, `error_type: "unknown"`), without `onPaywallRestoreStarted`. Before,
  `onPaywallRestoreStarted` fired first and the failure was an `IllegalStateException("Billing bridge
  not configured")` with no `error_type`.
- A paywall restore tap under `RevenueCat`, `Adapty` or `None` is now refused with
  `onPaywallRestoreFailed(providerNotAvailable)`, without `onPaywallRestoreStarted` — as on iOS, and as
  the purchase tap (no `onPaywallPurchaseStarted`). Before, `onPaywallRestoreStarted` fired first; under
  `RevenueCat` / `Adapty` AppDNA then ran a Play restore itself, and under `None` the restore failed
  with `IllegalStateException("Billing bridge not configured")` and no `error_type`.
- `AppDNA.shutdown()` while a paywall purchase or restore is waiting no longer reports it as failed:
  no `onPaywallPurchaseFailed` / `onPaywallRestoreFailed` and no `purchase_restore_failed`. A purchase
  that completes afterwards is delivered through the delivery queue. The one-shot
  `skipNextAutoDismissOnRestore` flag is cleared when such a restore is cancelled. This also holds
  when `shutdown()` is called off the main thread (the paywall is now shut down before billing).
- A `purchase()` whose calling coroutine is cancelled before Play's sheet opens (while it waits for
  the Play connection or the product lookup) is cleared as it is cancelled and never launches the
  sheet; the next `purchase()` starts normally. A late Play answer for a cancelled purchase never
  resolves, relaunches or clears a newer one while the newer purchase is still waiting for the Play
  connection or its product lookup (before it opens its own Play sheet). A late `ITEM_ALREADY_OWNED` with
  no purchase to resolve now runs the owned-purchase rescue at once instead of waiting for the next
  foreground.
- A refused purchase or restore under `RevenueCat` / `Adapty` — the direct API and the paywall tap
  alike — carries the same message as on iOS: "RevenueCat: purchases are made by RevenueCat in your
  app" ("Adapty: …" for Adapty).
- The paywall's purchase button is enabled again after every failed, cancelled or pending purchase
  (whatever its `on_failure` action), after a successful purchase when it has no `on_success` action
  or one this SDK version does not know (the paywall stays up for your app to close), and when the
  purchase is cancelled by `shutdown()` (it stayed disabled with its spinner).
- A free-trial purchase reports `is_trial: true` and price 0 (from the verify / restore reply).
- A renewal is also detected by the Play order id's `..N` suffix, so new `subscription_renewed`
  events appear, including on a trial conversion (known: a same-product base-plan change counts as a
  renewal, and a 7-day trial conversion found this way is timed when the device notices it — mostly in
  the d30 trial-to-paid window rather than d7).
- `reset()`, and `identify` with a user different from the one already identified, clear the cached
  entitlements (they refill from the next refresh / snapshot). The first `identify` after an
  anonymous session keeps the cache; its refresh only drops Play entries tagged for another user. `onEntitlementsChanged([])` fires if the cache held any entitlements.
- A Play subscription **on hold** or **pending** reads as `on_hold` / `pending` server-side and is
  not entitled until paid.
- A destroyed billing connection never leaves a request waiting: an `AppDNA.billing.getProducts` that
  starts waiting for the Play connection while `shutdown()` tears it down returns an empty list at once
  instead of waiting forever.

#### Changed — push
- `AppDNAMessagingService` handles **only AppDNA-marked messages** (`appdna: "1"`). Other messages
  are ignored entirely: no tracking, no `onPushReceived`, no notification. A data-only AppDNA message
  with no title and no body posts no notification.
- `AppDNA.handlePushTap(intent)` returns `false` for an intent without the `appdna` extra, and routes
  by the same ladder as `handleTapData`: the tapped button, then the canonical `action`, then flat
  `action_type` / `action_value`, then `screen_id`, then `deep_link` (a payload with both an action
  and a `screen_id` now follows the action). An action (tapped button or body action) with a blank
  value routes nowhere; it no longer falls through to `screen_id` / `deep_link`.
- Once it has handled a tap, `AppDNA.handlePushTap(intent)` removes the `appdna`, `push_id` and
  `delivery_id` extras from the intent, so handling the same intent again (an Activity re-created with
  it) returns `false` and tracks nothing.
- **Upgrade note:** a notification that the previous SDK version posted and that is still in the
  tray when the app updates carries no `appdna` extra. `handlePushTap` recognises that version's tap
  intent (exactly the extras that version set — `push_id`, `action_type`, `action_value`, and either
  `screen_id` and `deep_link` or a button's `action_id` — and no `appdna`) and still routes it and
  fires `onPushTapped`; it tracks `push_tapped` only when the notification carries a non-empty `push_id` (servers
  before this release sent none). A handled intent loses those extras, so it is not routed twice.

#### Fixed
- **A lifetime purchase (under `StoreKit2`) no longer disappears after an entitlement update**; `hasActiveSubscription()`
  counts it. Older cached entries without a type are read as lifetime when they are active Play
  entries without an expiry.
- **Server-side refunds and renewals now reach the device.** The Firestore entitlement listener
  never started (it ran before the org id was known); it now starts after the bootstrap and on
  `identify`.
- **`getLocationData` returns something useful.** A selected suggestion carries
  `formatted_address`, city, state, country, coordinates and timezone (the stored answer keeps
  `address` as an alias); text typed without selecting returns `{formatted_address, raw_query}` with
  null coordinates (it returned `null`). The form-step Location field and the Location content block
  now store the same shape as iOS: a selection also stores `timezone_offset` and `raw_query` (the typed
  search text) in the content block, and a string / number field of the wrong JSON type is absent
  rather than coerced (a numeric-string coordinate is no longer read as a number).
- An `onBeforeStepAdvance` reply `skipTo` / `skipToWithData` without a `stepId`, or with a blank one, is
  no longer a skip: a Flutter / React Native bridge blocks it on a sign-in step as no answer (it
  advanced the user past the sign-in step) and otherwise proceeds (with its `data`, if any). New
  `StepAdvanceResult.isExplicitBridgeDecision` / `bridgeSkipTarget` hold that rule for both bridges.
- `setSessionData` / an onboarding answer holding a value that is not valid JSON (e.g. `Double.NaN`)
  no longer throws `JSONException` into the caller: that value is not saved to disk and a warning
  naming it is logged. Every other saved value is kept.
- `StoreKit2` purchases are now verified and acknowledged (the verify call was refused), instead of
  being refunded by Play after 3 days.
- Location autocomplete no longer stores `0,0` for a suggestion that comes back without
  coordinates: the suggestion is kept and the stored answer omits `latitude` / `longitude`.
- The event queue no longer spins the IO thread pool when `flushInterval` is set to a very large
  value (the auto-flush delay overflowed).

#### Cross-platform notes
- The iOS permission-API and `NSLocationWhenInUseUsageDescription` change does not apply to this
  Android SDK. The Flutter plugin that wraps it now ships a `consumer-rules.pro`, so an
  R8-minified Flutter release build keeps the plugin's classes.

#### Fixed — billing, push, onboarding, maps
- `onPaywallPurchaseCompleted` fires **once** per paywall purchase. It fired twice when your paywall
  delegate was also the one the paywall was presented with (the billing layer called it too).
- `EntitlementCache` counts `billing_retry` as active, like the server: `hasActiveSubscription()` and
  `isEntitled` are true while the store is still retrying a renewal payment.
- `subscription_renewed` carries `price` and `currency` (the base plan's recurring price, the same
  names as `purchase_completed`) when Play returns the product's price. Play's client API has no
  charged amount for an order, so this is the catalogue price, not a promotional or grandfathered
  amount; iOS reports the amount charged (`transaction.price`).
- `TransactionInfo.environment` is `"sandbox"` for a Play test purchase — the value the AppDNA server
  decided when it verified the purchase (it was `"production"` for every purchase). It stays
  `"production"` when there is no verified answer (an older server, an already-owned re-tap,
  RevenueCat / Adapty).
- An AppDNA push with a `notification` block that arrives while the app is in the **foreground** is
  now displayed (by the SDK's service and by `AppDNA.push.handleMessage`), with the same channel and
  look as a data-only message. FCM displays such a message itself only in the background, so it was
  never shown in the foreground. A message already forwarded is not shown twice.
- `social_login`: a form field with the id `action` or `provider` can no longer replace the SDK's own
  `action: "social_login"` / `provider` in the step data.
- Adapty: activated with `Adapty.activate(context, AdaptyConfig)` (Adapty 2.11+, 3.x, 4.x), falling
  back to `activate(context, apiKey)`; before, only the latter was tried, so Adapty 4.x was never
  activated. Adapty's access levels are now read (they are Adapty's own `ImmutableMap`, which the
  bridge read as empty). An empty Adapty key is refused with an error log instead of silently.
- `destroy()` / `AppDNA.shutdown()` called off the main thread now detaches the billing manager's
  process-lifecycle observer (the removal was rejected off Main and the old manager leaked).
- The billing ledger logs an unreadable value and keeps a copy of it (see Fixed above for the key)
  instead of silently reading it as empty and overwriting it.
- An interactive Google map that has not finished loading after 15 s logs an error naming the usual
  cause (the key's Google Cloud project without "Maps SDK for Android", or an app restriction without
  this app). It stays a map; it is not swapped for the still image.

#### Source / binary compatibility
- **`LocationData`'s fields other than `formatted_address` are nullable** (`city`, `state`,
  `latitude`, `timezone`, `timezone_offset`, `raw_query`, …): a Kotlin **source change** for code
  that reads them as non-null.
- `AppDNA.billing.purchase` under `None` throws `BillingError.ProviderNotAvailable`, not
  `IllegalStateException` — a `catch (e: IllegalStateException)` no longer matches.
- `PurchaseResult.Failed` and `PurchaseFailedException` gain a defaulted `errorType`:
  source-compatible; **binary-incompatible for precompiled callers of `PurchaseResult.Failed.copy`**.
- **Binary-incompatible for prebuilt Kotlin/Java callers compiled against ≤ 1.0.53:**
  - `LocationData`: the JVM signatures of its constructor, `copy`, the `componentN` functions and
    the getters of the fields that became nullable primitives (`getLatitude()`, `getLongitude()`,
    `getTimezone_offset()` now return boxed `Double` / `Integer`) all changed.
  - `VerificationException` gains `httpStatus` and `serverError`. The `(String, Throwable)`
    constructor is kept; `@JvmOverloads` adds `(String)`, `(String, Throwable, Integer)` and the
    four-argument constructor for Java callers (before, Java could only pass both arguments). The synthetic default-arguments constructor that
    Kotlin emits for a call using defaulted or named arguments (for example
    `VerificationException("…")`) changed.

  A prebuilt library that uses either type must be recompiled, or it fails with `NoSuchMethodError`.
- Every other addition is additive.

## [1.0.51] — 2026-09-23

Wraps iOS 1.0.79. Publishable version and feature-parity marker move independently — see
`gradle.properties`.

### Fixed
- A stray grey **"Skip"** drew under the CTA of *every* screen. The render was gated on the skip
  handler being non-null, and SPEC-476 had just made that handler unconditional — so the proxy for
  "is skipping allowed" stopped meaning anything. It reads `skip_enabled` now, which is the flag the
  console actually writes. (#653)
- **`horizontal_align` did nothing on a width-constrained block.** A block with `element_width` of
  `75%` or `300px` got its sizing box first, and the alignment was then applied *inside* a box
  already shrunk to that width — so there was no room left to move in. A width-constrained block now
  gets an outer full-width box that carries the alignment, matching SwiftUI's frame order. Affected
  any width-constrained block (for example a 75%-wide button) authored `center` or `right`. (#654, #659)
- **Content sat behind the pinned bottom zone** and could not be scrolled to. The scroll area
  reserved a hardcoded 80dp under a zone that is routinely ~140dp (two buttons plus a legal line),
  so the last lines of a summary were unreachable — the scroll range ended before them. The reserve
  is the zone's measured height now, and stays 0 when there is no bottom zone so short centred
  screens stay centred. (#655)
- **Summary cards vanished** when a single token did not resolve. SPEC-446 dropped the whole stat,
  so one missing key in the host payload took the row with it. A card is dropped only when it would
  say nothing at all: an unresolved *value* keeps the card and shows an em-dash, an unresolved
  *label* keeps the value and drops the caption. (#660)
- The **"or" text inside a social-login divider** was pinned to the theme's 60%-alpha on-surface
  colour, so the authored colour applied to the line and not to the word between its segments.
  Reads `divider_text_color` now. (#615)

### Added
- **Multi-buttons** — a group that lays its children out 1–3 per row, with the short last row either
  centred or stretched, and its own background and corner radius. The children are real `button`
  blocks carrying every Button setting, because each one triggers something different in the host
  app. (#609)
- A button action that **refreshes the step in place**. Until now `onElementInteraction` was fired
  only by a fixed set of interactive blocks and never by a button, so a "Show more options" control
  had no way to ask the host for new content without also advancing. The host's reply can now
  replace a named block's `field_options`, which is where a select's options actually live. (#657)
- Paywalls: a **standalone Back button** with its own style, position, colour, size, delay and text,
  independent of the close affordance. (#652)
- Paywalls: **Restore background colour and corner radius**, and a corner radius on each extra CTA
  button. (#651)

## [1.0.50] — 2026-09-17

Wraps iOS 1.0.78. Publishable version and feature-parity marker move independently — see
`gradle.properties`.

### Fixed
- A CTA whose `action` was `"skip"` was a **dead button**: the block-based step view received a
  `null` skip handler whenever `config.skip_enabled` was not `true`, so `onSkip?.invoke()` silently
  did nothing. iOS never had that gate, so identical config advanced on iOS and died here. Measured
  on a live flow where `skip_enabled` was undefined on all 18 steps — every skip CTA in it was dead.
- The **social-login divider** ignored its authored position: it was read from `field_config`, while
  the console writes the top-level field. The console now mirrors it at the sync boundary, so
  already-published flows are fixed on their next sync with no SDK release needed.
- The **device mockup** was the authored height tall by a hardcoded 260dp wide, so at the default
  height of 200 the "phone" was a landscape box and raising Height only made it taller. The screen
  now takes a fixed 9:19.5 aspect and the height scales the whole device. `image_fit` is honoured
  inside the frame too — it was hardcoded to `Crop`.
- A **legal section embedded in a Screen** showed `[label](url)` markdown as literal brackets and
  ignored its alignment setting. Both Android legal renderers now share one parser.
- The paywall **legal link colour** never reached the device: the console writes `link_color` and
  this SDK reads `accent_color`, with nothing mapping between them. Mapped at the wire boundary, so
  every published paywall is fixed on its next sync.

### Added
- **Warning banner**: `banner_subtitle`, `banner_text_align`, `banner_border_width`,
  `banner_border_color`, `banner_corner_radius`, `banner_title_size`, `banner_subtitle_size` and
  `banner_font_family`, all in `field_config` because `ContentBlock` sits at 245 of the JVM's
  255-parameter ceiling. Every default reproduces the previous render.
- **Sticky-footer subtitle**: `legal_font_size` and `legal_text_color`. It was pinned at 10sp and
  honoured no authored size, which is why a 13pt legal section could render larger than a "16px"
  footer line.
- **Paywall CTA section**: `restore_gap` (the CTA↔Restore spacing, previously a hardcoded 8dp),
  `extra_buttons` (up to six, each with its own action and styling), and `cta_action` /
  `restore_action`. Actions are `purchase | restore | dismiss | link`; `dismiss` leaves the paywall,
  returning the user to the previous screen.
- **Paywall back button**: `dismiss.style = "back_button"` draws a chevron that leaves the paywall,
  plus `dismiss.position`, `dismiss.color` and `dismiss.size` for the ✕ and the chevron alike.
- **Paywall divider**: `margin_horizontal` is now authorable (it was already read here).

## [1.0.49] — 2026-09-01

### Fixed
- A navigation rule whose condition read an **earlier step's** answer never matched. Conditions
  were evaluated against the current step's own responses only, so a cross-step `answer_key`
  resolved to nothing, every rule returned false, and advance fell back to sequential order —
  which is frequently the first rule's own target, making a dead branch look like a working one.
  Keys resolve by step id and by authored step name, so existing flows need no re-authoring.
- Option id↔value aliases are now read from the step that owns the field rather than the step
  being left, so a rule written against an option's id matches an answer stored as its value.
- `sdkVersion` was a release behind the published artifact, so events under-reported the version.

## [1.0.45] — 2026-08-27

### Added
- Summary Screen stats can host a `slider` or `stepper` control (`input`, `field_id`, `min`,
  `max`, `step`, `default`, `required`). The value is written to the step's inputs, so a later
  step — or another stat on the same card via `{{step.field_id}}` — can read it live.
- Image tiles gained `tile_image_layout`: `full_bleed` (unchanged default), `image_strip` and
  `contained`, with `tile_strip_ratio`, `tile_surface_color`, `tile_image_inset`,
  `tile_image_frame_width` and `tile_image_frame_color`.

### Fixed
- `{{var}}` in an option's `label`, `subtitle` or `leading_text` was not resolved at all.
- A summary screen whose variables lived only in its stats skipped resolution entirely, because
  the has-templates gate did not look inside `summary_stats`.
- `{{var | fallback}}` and hyphenated field ids (`{{responses.party-size}}`) did not match the
  token pattern, so the literal reached the screen.
- A stat binding that cannot resolve and has no fallback now hides the stat instead of printing
  the raw token in the card's numeral.
- The image overlay tinted the text band in the two surfaced layouts; it now covers the image only.
- A stat with `step: 0` produced a slider with 290,001 tick marks. A step at or below zero now
  means continuous.
- A required stat with an authored `default` no longer blocks the CTA before the control has been
  scrolled into view.

## [1.0.41] — 2026-07-06

Revoking analytics consent (`AppDNA.setConsent(analytics = false)`) now purges any
queued-but-unsent events from both the in-memory queue and on-disk storage, so events
captured before consent was revoked are never uploaded.

## [1.0.40] — 2026-07-03

Version bump keeping the native SDK aligned with the expanded surface used by the
Flutter and React Native wrappers (host-approval callbacks, inline screen slots,
brand/runtime/session reads). `AppDNA.diagnose()` now also reports the wrapper's
version and platform when the SDK is driven through a wrapper; native Android callers
are unchanged.

## [1.0.38] — 2026-05-30

SPEC-036-H — per-item remote-config serving (read side). Feature flags and
in-app messages are now read via their per-item index (`config/flag_index`,
`config/message_index`) with per-item documents fetched on demand, falling
back to the legacy mega-doc when no index is present. The index is
authoritative: an item dropped from the index stops serving, and an empty
index clears both the in-memory and on-disk cache (prune-to-empty also fires
change listeners). Flag values resolve through the `{value, type, …}` wrapper
the backend serves (raw value, with explicit `null` treated as unset). This
removes the single app-wide flag/message documents as a scaling ceiling while
staying backward compatible with apps still on the mega-doc.

Feature parity marker: `appdnaFeatureParity=1.0.66`.

## [1.0.37] — 2026-05-26

SPEC-036-F Phase 1 — experiment-aware presentation. The four served surface
managers (`PaywallManager`, `OnboardingFlowManager`, `MessageManager`,
`SurveyManager`) now consult `ExperimentManager` at present-time: when a
`running` experiment's served `type` matches the surface and its control
variant's `config_ref` equals the entity being presented, the user is
bucketed via the existing deterministic `ExperimentBucketer`. A treatment
bucket renders the variant's inlined `payload` config (decoded through the
same typed-config parser the live config uses); control, non-bucketed, and
old-SDK users render the current active entity, so the treatment config never
leaks outside its bucket (cohort isolation). `ExperimentConfig` gains the
served `type`; `ExperimentVariant` gains `config_ref` + `is_control`.

Feature parity marker: `appdnaFeatureParity=1.0.65`.

## [1.0.36] — 2026-05-18

SPEC-404 — hard SDK suspension. When the backend signals the SDK is in
locked mode (per-key suspended at day 20+ via the SPEC-322 sweep, OR org
cancelled), the SDK enters runtime-locked mode and pauses paywall /
message / survey presentation. The lock is delivered via the
`/sdk/bootstrap` response's new optional `runtime_lock` field; SDK
exposes it as `AppDNA.runtimeLock` and fires the new generated
`AppDNALifecycleDelegate` callbacks (`onSdkRuntimeLocked` /
`onSdkRuntimeUnlocked`) on state transitions. Event uploads continue
attempting and cleanly stop after the first 401 via the existing
`eventUploadPermanentlyFailed` path. Identify continues to work
locally so `EntitlementOwnerFilter` keeps gating per-user correctly.

Feature parity marker: `appdnaFeatureParity=1.0.64`.

## [1.0.35] — 2026-05-15

Follow-up to the 1.0.34 cross-account-entitlement-leak hotfix. The
write-side per-user binding from 1.0.34 ships every purchase tagged
with the current user — but in SDK-driven onboarding paywall flows the
purchase fires BEFORE the host has called `AppDNA.identify(userId)`,
so the resulting Play purchase is **untagged** (no
`obfuscatedAccountId`). 1.0.34's `EntitlementOwnerFilter` granted any
untagged purchase to whoever happened to be identified at read time
(migration-tolerant policy intended for legacy upgrades). Device QA
reproduced the resulting leak on iOS: user A buys via onboarding, user
B signs in on the same device, B taps Restore, B inherits A's purchase
under the migration policy. Android shipped the same logic and the
same leak.

### What 1.0.35 changes

The migration-tolerant grant is now **scoped to the device's first
identified user**. Decision matrix (see `EntitlementOwnerFilter.kt`):

```
expectedToken null                                                  → GrantAnonymousPolicy   (unchanged)
expectedToken set, purchase == match                                → Grant                  (unchanged)
expectedToken set, purchase mismatch                                → DenyOtherUser          (unchanged)
expectedToken set, untagged, firstIdentifier == expected            → GrantUntaggedMigration (legitimate self-claim)
expectedToken set, untagged, firstIdentifier != expected            → DenyUntaggedOtherUser  (cross-account close — NEW)
expectedToken set, untagged, firstIdentifier null                   → DenyUntaggedOtherUser  (no anchor — NEW)
```

`AppDNA.identify(userId)` records the userId as the first-identifier
on this device the FIRST time it runs (idempotent — later
`identify(B)` does NOT change the anchor). `AppDNA.reset()`
**deliberately does NOT** clear the anchor (clearing on sign-out
would re-open the leak: the next user identifying on the same
device would become the new first-identifier and inherit untagged
purchases). App uninstall / clear-data is the only path that wipes
the anchor.

Effect: the user who legitimately owns the untagged onboarding
purchase (the device's first-identified user) keeps it on Restore; any
other user on the same device is denied. iOS 1.0.63 ships the same
fix.

Files: `EntitlementOwnerFilter.kt` (new `DenyUntaggedOtherUser` case +
3rd `firstIdentifiedToken` parameter), `AppAccountTokenResolver.kt`
(SharedPreferences-backed first-identifier persistence with test
hooks), `AppDNA.kt` (identify records anchor; reset deliberately
leaves it intact — the anchor's natural lifecycle is the app
installation, so uninstall/clear-data is the correct invalidation
event, not sign-out), `NativeBillingManager.kt` (threads
firstIdentifier into the filter across all 3 active call sites:
`reconcileSubscriptionState`, `refreshEntitlementCache`,
`restorePurchases`).

`appdnaFeatureParity` bumps to `1.0.63`. CI enforces lockstep.

## [1.0.34] — 2026-05-15

Cross-platform hotfix mirroring iOS 1.0.62. Closes the cross-account
entitlement leak — same shape as the bug reproduced on iOS: User A
purchases on the device, User B signs in to the host app on the same
device, B taps Restore (or just identifies), and `queryPurchasesAsync`
returns A's purchase unfiltered, granting B a fake-premium state. The
`appdnaFeatureParity` marker bumps to `1.0.62`.

### Write side — every purchase now binds to the current app user

`NativeBillingManager.purchase` already passed `BillingFlowParams.setObfuscatedAccountId`
when the host supplied an explicit `options.appAccountToken`, but had no
fallback for callers using the convenience API
(`AppDNA.billing.purchase(productId)` with no options). Now resolves the
token via the new `AppAccountTokenResolver` (deterministic UUID derived from
`AppDNA.identify(userId)` — UUID pass-through if the userId is already a
UUID, otherwise SHA-256(NAMESPACE || userId) → RFC-4122-shaped UUID). Same
algorithm runs on iOS + (TODO) the backend receipt verifier so a cross-
platform user binds to the same token everywhere. If no user is identified,
the purchase still proceeds untagged with a warning (preserves first-launch
flows; hosts should call `AppDNA.identify(userId)` BEFORE letting the user
purchase).

### Read side — every device-level purchase read is filtered

The 3 sites that consume `BillingClient.queryPurchasesAsync`
(`reconcileSubscriptionState`, `refreshEntitlementCache`,
`restorePurchases`) now decode `Purchase.accountIdentifiers.obfuscatedAccountId`
and route through the new `EntitlementOwnerFilter` before any purchase
reaches the entitlement cache or the server-side `receiptVerifier.restore`.
Decision matrix:

```
expectedToken null                    → grantAnonymousPolicy   (preserves pre-identify flows)
expectedToken set, tx == expected     → grant
expectedToken set, tx == nil          → grantUntaggedMigration (server claims ownership)
expectedToken set, tx != expected     → DENY                   ← the headline fix
```

### Server-side defence (primary)

`ReceiptVerifier.verify` and `ReceiptVerifier.restore` now send `app_user_id`
in every request body. The backend MUST decode each transaction's
`obfuscatedAccountId` and compare to the authenticated user — denying on
mismatch and claiming ownership on null (migration-tolerant). The
client-side filter above is belt-and-suspenders for the cached / silent
paths.

### Tests

`src/test/kotlin/ai/appdna/sdk/billing/EntitlementOwnerFilterTest.kt` (5
decision-matrix tests, including the headline cross-account-deny case) and
`AppAccountTokenResolverTest.kt` (6 tests on determinism, UUID fast-path,
and RFC-4122 shape — paired with the iOS frozen-vector test for
cross-platform parity).

## [1.0.33] — 2026-05-13

Android-only hotfix to close iOS parity gaps in onboarding form rendering and
auth-action validation.

- `FormInputSelectBlock` (stacked + grid display styles) now honors per-option
  styling fields from `field_options[]` — `bg_color`, `selected_bg_color`,
  `border_color`, `selected_border_color`, `text_color`, `selected_text_color`
  — and block-level `field_config` fields including `selection_indicator`,
  `radio_position` (left/right), `selected_border_width`, `unselected_border_width`,
  `option_spacing`, `bg_opacity`, and `grid_columns`. Previously every option
  rendered with the same default colors regardless of console config, so
  flows with a stacked select showed one uniform tile instead of
  four differently colored answers.
- Grid display now renders `selected_icon` / `unselected_icon` toggle badges
  in the top-end corner and supports configurable `grid_columns` (default 2)
  with empty-slot fillers to keep the final row balanced.
- `AUTH_ACTIONS_REQUIRING_VALIDATION` now includes `resend_verification`,
  `enable_biometric`, `logout`, and `delete_account` so the form-validation
  gate fires before these actions just like on iOS.

## [1.0.32] — 2026-05-06

Android SDK uses its own version sequence (independent of iOS — Android
last released `1.0.31`). The `appdnaFeatureParity=1.0.61` marker in
`gradle.properties` declares which iOS SDK version this Android release
wraps for cross-platform feature parity: SPEC-070-A brought Android to iOS
1.0.60 parity, and SPEC-401 Phase D mirrored the 4 iOS 1.0.61
entitlement-aware paywall fixes (entitlement gate, bridge restore,
auto-dismiss, identify-time cache refresh) onto Android in the same
release tag. Note: an earlier draft of this changelog tagged this release
as `1.0.60`; that was a mis-merge of the iOS version into the Android
sequence and has been corrected.

Cross-platform feature parity catch-up to iOS — closes the gap from previous
Android-only follow-up debt across SPEC-038, SPEC-082, SPEC-083, SPEC-084,
SPEC-085, SPEC-205, SPEC-206, and SPEC-400 in a single release.

### Added

- **Onboarding** — full `form` step type port (13 P0 input types + 8 P1
  extended types), strict-typed action passthrough, async step hooks
  (`onBeforeStepAdvance`, `onBeforeStepRender`), and `StepAdvanceResult.Stay`
  semantics with optional banner message.
- **Paywalls** — strict-typed restore lifecycle (`onPaywallRestoreStarted`/
  `Completed`/`Failed`) and 12-method `OnboardingPaywallBridge` forwarding so
  paywall actions invoked from an onboarding step pass through correctly.
- **Rich media (SPEC-085)** — Lottie + Rive (always bundled), SVG, GIF, and
  inline video via Media3 ExoPlayer; Lucide / Material / SF-Symbols-equivalent
  icon library; blur / glassmorphism modifiers; haptics; confetti and particle
  overlays across messages, surveys, and push notifications.
- **Theming (SPEC-205)** — light / dark theme tokens with deterministic
  resolution and system-appearance follow.
- **Auth + restore (SPEC-206)** — strict-typed auth context and restore flow
  parity with iOS.
- **Push** — strict-typed action buttons, NSE-equivalent rich content
  (image / GIF / video) helper, and per-action delegate routing.
- **Network + push hardening** — 25 SPEC-070-A H-series items: typed retry
  policy, jitter, hard-cap reconnect on `BillingConnectionManager`, push
  delegate integration, etc.
- **Identity + events** — 22 SPEC-070-A G-series items: anonymous-id
  persistence via EncryptedSharedPreferences, structured event envelope,
  retry policy, in-memory caps.
- **Build / packaging (Phase J)** — Maven Central / Sonatype OSSRH publish
  config with sources + javadoc jars, `gradle/libs.versions.toml` version
  catalog, debug/release `buildTypes` with log gating, LeakCanary in debug,
  AOSP-template `.gitignore`, library-friendly `gradle.properties` flags
  (`android.nonTransitiveRClass=true`, `android.enableJetifier=false`,
  `kotlin.code.style=official`), CHANGELOG, and a CI matrix (API 24 / 28 /
  33 / 34) running `assembleDebug` + `lint` + `test`.
- **kotlinx-serialization-json** is now on the classpath so future DTOs can
  opt in to `@Serializable` incrementally; existing DTOs continue to use
  `org.json` unchanged.
- **`BillingClientFactory` injectable seam** so unit tests can substitute a
  fake `BillingClient`, unblocking JVM tests of the connect / retry /
  surface-billing-unavailable paths.

### Changed

- **Compose BoM** bumped to `2024.02.02` (was `2024.01.00`). Stops at the
  2024.02 line because Compose Compiler 1.5.8 + Kotlin 1.9.22 don't yet
  support 2024.09.x. A follow-up SPEC will bundle the Kotlin + compiler
  bump together.
- **kotlinx-coroutines** bumped to `1.8.1` (was `1.7.3`).
- **`Log` (Configuration.kt)** — INFO + DEBUG levels now no-op in release
  builds even when callers leave `LogLevel.DEBUG` configured. ERROR + WARN
  always print. R8 inlines `BuildConfig.DEBUG` so the gate costs nothing at
  runtime in release.

### Tests

- New unit tests in `src/test/kotlin/ai/appdna/sdk/`:
  - `delegates/DelegateWiringTest`
  - `events/PushEventTest`
  - `events/IdentityEventTest`
  - `events/EventQueuePolicyDocTest`
  - `messages/InAppMessagingTest`
  - `billing/RestoreTest`
  - `onboarding/OnboardingActionPassthroughTest`
  - `onboarding/OnboardingPaywallBridgeForwardingTest`
  - `onboarding/StepAdvanceResultStayTest`
