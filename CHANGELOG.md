# Changelog

All notable changes to the AppDNA Android SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [1.0.57] — 2026-11-03

Wraps iOS 1.0.84.

### Fixed

- **Push token on first launch.** `configure()` asked Firebase for the push token before the SDK had
  created its own `appdna` Firebase app, so the request fell back to the app's default Firebase app. An
  app without Firebase of its own got no token on that launch; an app with its own Firebase got a token
  from its own project, which AppDNA cannot send to. The token is now requested right after the `appdna`
  app is created.

## [1.0.56] — 2026-11-03

Wraps iOS 1.0.84. Builds on Play Billing Library 8, which Google Play now requires. Paywalls show
Google Play's own price, billing period and free trial; location fields can search with Google as
well as Mapbox; an onboarding flow survives a rotation and the OS ending the app.

### Added

**SDK and host API**

- **`AppDNA.isReady`**. Answers whether `configure()` has finished. A `present…` call
  made before then used to log "Onboarding flow not found", which sent integrators to check a flow
  id that was fine; the log now says the SDK is not ready yet. A bundled config with no section the
  SDK recognises now logs a warning naming the keys it found.
- **`AppDNALifecycleDelegate.onConfigurationIssue(surface, id, detail)`**. Called when
  part of a published flow, paywall, survey or message is the wrong type and is dropped while the
  rest still renders. `detail` names the field's path and the reason. The SDK also tracks
  `sdk_decode_dropped` with the same values and logs the detail at warning level. Each distinct
  problem is reported once per process.
- **`AppDNALifecycleDelegate.onEventTracked(name, properties)`**. Called for every event the
  SDK tracks, right after it is queued, so you can forward AppDNA analytics into your own pipeline.
- **`LocationData.mapbox_id` and `LocationData.place_id`**. Set on an answer resolved from
  a tapped Mapbox or Google suggestion (see Location fields below).
- **Main-thread stall logging in debuggable builds**. While an AppDNA screen is in front, a
  main-thread stall of 700 ms or more is logged with the screen and the blocking stack. Release
  builds are unaffected.

**Paywalls**

- **Prices come from Google Play**. A plan shows Play's localized price for its product, in
  the user's own currency. The authored `price_display` is used whenever Play cannot answer, and a
  plan with `use_store_price: false` always shows the authored price. `paywall_view` now fires
  after the price lookup and carries `price_source` and `store_prices_resolved`.
- **Billing period and free trial from Google Play**. A plan that authors no period
  shows Play's billing period next to its price, phrased in the ten languages the console can
  localize into (English otherwise). The trial line uses, in order: `trial.label`,
  `trial_duration`, `trial.duration_days`, then a free-trial offer Play says the user is eligible
  for (an introductory price is never shown as a trial). An ISO duration such as `P7D` is now
  phrased ("7 days free trial") instead of printed.
- **Any section can be pinned to the bottom zone** with `zone: "bottom"`. Pinned
  sections sit above the purchase button in authored order. If the pinned group would take more
  than 55% of the screen, every pinned section except the purchase button goes back into the
  scrolling body.
- **Per-element container style**. An element's `element_style` (border,
  radius, padding, opacity, shadow) is now drawn on plan labels, prices and badges, feature items,
  header title and subtitle, legal text, the purchase button, the restore link, reviews,
  testimonials, icon grids, guarantees and social proof. It used to be dropped.
- **`tab_groups` plan layout**, and a dedicated **`carousel_cards`** layout with page dots.
 `tab_groups` used to fall back to a vertical stack, and `carousel_cards` drew exactly
  like `horizontal_scroll`.
- **Countdown variants and styling**. A paywall countdown draws its `variant`
  (`digital`, `circular`, `flip`, `bar`) as the onboarding countdown does, and honours
  `label_font_weight`, `digit_font_size`, `digit_font_weight` and `text_color`. Unset keys draw as
  before.
- **Plans draw in `sort_order`**. A plan without a rank keeps its position.
- **A purchase button on a multi-plan paywall names the plan it will buy**, for example
  "Continue — Annual". A single-plan paywall, a plan's own `cta_text`, and a label that already
  contains the plan's name are left alone. See `cta.name_plan` / `cta.plan_format` below to turn
  it off or reword it.
- **`onPaywallAction` reports every action**. `CTA_TAPPED` is reported at the tap,
  also when the tap cannot proceed (it used to be reported only once a plan resolved), and
  `PLAN_CHANGED`, `LINK_TAPPED`, `CUSTOM` (an authored action the SDK does not recognise) and
  `FEATURE_SELECTED` (a tapped feature row) are now sent.
- **Social proof draws its caption and author**: the `app_rating` variant's `text`,
  and `author_name` wherever it is set.

**Onboarding**

- **A flow survives the OS ending the app**. When the app calls `AppDNA.configure()` in
  `Application.onCreate`, a flow the OS ended in the background resumes on its step with its
  answers. Otherwise it now closes at once with a warning, instead of after a 15-second wait.
- **What the user typed survives a rotation and the OS ending the app**. Text not yet
  submitted on the open step is kept; password and one-time-code fields are kept across a rotation
  only.
- **An interactive chat step keeps its conversation** across a rotation and the OS ending the app.
 A reply in flight during a rotation is still delivered; one lost with the process is
  asked for again, once.
- **Steps that advance without a button**. `auto_advance_seconds` (0.1–60) advances
  the step after a delay; `wait_for_host: true` marks a step your app advances, so it never times
  out; `tap_anywhere_advances: true` advances on any tap a child element does not handle. All
  three go through the same advance as Continue (rules, events, required fields).
- **`skip_to_step`** sets where Skip goes.
- **`dismiss_button_style`** styles the ✕ that closes a flow: glyph, size, colour,
  background, radius and inset. The tap target does not shrink with the glyph. Unset keeps today's
  look.
- **Store reviews**. `request_store_review: true` on a step asks Play for the in-app review
  as the step appears; a button with `action: "rate_app"` opens the app's Play Store listing.
- **The flow's Celebration Effect is drawn**, on its first step (`on_appear`) or on every
  advance (`on_step_complete`).
- **Sign in with Google draws Google's official mark** instead of a bold "G", on a
  white chip when the button's own fill is too dark for it.
- **Info banner options**: `banner_hide_icon`, `banner_icon_align`, `banner_title_color`,
  `banner_subtitle_color`, a link (`banner_link_text`, `banner_link_url`, `banner_link_color`,
  `banner_link_size`), and an uploaded icon (`banner_icon_url`, `banner_icon_size`). Defaults draw
  the banner as before.
- **Form-field style keys**: `error_border_color` (while the field is in error),
  `font_weight`, `field_style.font_size`, and the compact picker's `field_style.height`.
- **`min_length` and `validate_format` are enforced** on text and URL fields.
- **Category rows and live conditions**. `input_select` with `display_style:
  "bubble_scroll"` draws one horizontally scrolling row of pills, opening on its first option. A
  block's `visibility_condition` can name a field on the same step and reacts as it changes. A
  Segmented block honours its `display_style`.
- **A dropdown-style Select can be multi-select.** With `multi_select`, the default dropdown is a
  multi-pick menu: each pick toggles that option, a check marks each picked option, and the field
  shows the picked labels in pick order. The menu closes after each pick. The answer is a list, the
  options are recorded for `{{selected.<field_id>.<n>.…}}` in pick order, and `max_selections` caps
  the picks, exactly as in the list styles. Before, a dropdown stored one value whatever
  `multi_select` said.
- **A media gallery can carry a title** (`gallery_title`, `gallery_title_color`).
- **Bundled assets**. A config whose images were bundled into the app (`appdna-assets/…`)
  loads them from the app's assets, falling back to the network for any file that is not there.

**Location fields**

- **A tapped suggestion is resolved once more through AppDNA's proxy**. The stored answer
  is what that lookup returns and carries `mapbox_id` or `place_id`. A tap that cannot be resolved,
  or a step that moves on while it is resolving, stores the typed text. A selection is restored
  when the user comes back to the field or rotates the device.
- **Google as a location-search provider**. With a Google key under Settings >
  Location search, the dropdown shows the Google Maps logo at the top of its list, directly under
  the field (so it stays on screen with the keyboard up), a field holding a Google answer keeps
  the logo in view with it (on a short screen, such as landscape, the step scrolls them above the
  pinned buttons together), and a step that displays a Google
  answer draws a small "Google Maps" credit (an option's bottom sheet draws its own). Where an
  answer is stored or sent rather than shown (an option's value, a default value, a link or media
  URL, a header, and any template on a paywall, message, survey or push) the user's typed text is
  used. Editing a selected Google answer restarts from the typed text. See the onboarding docs for
  the full rule.

**Events**

- **`store_listing_opened` carries `store`** (`app_store` / `google_play`), a property both
  platforms send.

### Changed

- **A failed purchase now shows a banner above the purchase button, the same one iOS draws**. For
  `post_purchase.on_failure` actions `show_error` and `retry`, Android used to draw a centred card
  over a dimmed, full-screen backdrop that covered the plans and the purchase button. It now draws a
  red banner above the button and nothing else, so the user can pick another plan or tap the
  purchase button again straight away. Tapping the banner hides it. Successful-purchase messages are
  unchanged.
- **The banner's retry button is now exactly a second tap on the purchase button**. It used to start
  the purchase without the button's in-progress state (so the button stayed tappable mid-purchase) and
  without the purchase metadata (a validated promo code and toggle states were dropped). It now also
  reports `CTA_TAPPED` to your paywall action listener, as a second tap would. iOS's retry button now
  starts the purchase too; it used to only hide the banner.
- **Naming the selected plan on a multi-plan purchase button is now optional, and its wording is
  yours**. The paywall's top-level `cta` gains `name_plan` (`false` shows exactly the text you
  configured; default: the plan is named, as before) and `plan_format` (`{cta}` and `{plan}`, default
  `{cta} — {plan}`), which is translatable as `cta.plan_format` so a locale can reorder the two.
  The sticky-footer button now names the plan too, as it already did on iOS.
- **The purchase button behaves the same wherever it is drawn**. A purchase button inside a paywall
  section or a sticky footer started the paywall's particle effect on the tap rather than after the
  purchase succeeded — the pinned button already waited for the purchase.

- **Play Billing Library 7.0.0 → 8.0.0**. Google Play refuses an app update built on Play
  Billing Library 7 ("must update to at least version 8.0.0"), and this SDK is where an integrating
  app gets it from, so every app on 1.0.55 or earlier is blocked from publishing. Nothing in the
  public API changes.
- **The SDK depends on `com.android.billingclient:billing`, no longer on `billing-ktx`.** `billing-ktx`
  8.x is compiled with Kotlin 2.1 metadata, which a Kotlin 1.9 toolchain cannot read; the two suspend
  helpers the SDK used from it are now internal. An app on Kotlin 1.9 keeps building. If your own code
  uses `billing-ktx`, add it to your app's dependencies directly. (8.0.0 rather than a later 8.x:
  `billing-ktx` 8.1.0+ requires kotlin-stdlib 2.2.)
- **Unstyled text follows the step's background**. Text, headings, field
  labels, typed text, placeholders, the cursor, the password field and the back / close / Skip
  controls default to light ink on a dark, gradient or image step, and stay dark on a light one. An
  unstyled password field used to be white, so invisible on a light step; text and labels used to
  be dark, so nearly invisible on a dark one. Authored colours still win.
- **A paywall's colours follow its page**. Text,
  icons, tiles and dots nobody coloured are read off the page background. Android used hard-coded
  white and a dark theme, so on a light paywall the selected plan, feature rows, cards, countdowns,
  legal text, timelines and carousels could draw nothing visible, and an unset page background was
  black where iOS draws white.
- **A block-based step has no step-level Skip**. With `skip_enabled`, Android drew a Skip
  button over the bottom of the step that took a bottom-zone Continue's taps, so tapping Continue
  skipped the step without storing its answer. On a block step, Skip is a Button block with
  `action: "skip"`, as on iOS. Legacy step types keep their Skip.
- **Store reviews are never pre-screened**. If any branch of a survey asks for a review,
  every respondent who finishes is eligible and Play decides the rest; the respondent's own branch
  still runs. `review_prompt_accepted` and `review_prompt_declined` are no longer sent, because
  neither store reports the outcome.
- **A survey trigger's unknown `frequency` means `once`**, the documented default. Android
  treated it as unlimited.
- **A button's "Text" colour wins over the Typography colour**, as on iOS.
- **Markdown outside the supported subset is converted, not printed**: `# Heading`
  draws bold, `> quote` italic, `- item` as "• item", a table row as "a · b", and inline code
  without its backticks.
- **A user trait counts as present only when it holds a non-null value**, in audience
  rules, matching the server.

### Fixed

**Paywalls & billing**

- **A subscription purchase now starts the free trial the paywall shows**. With no
  `offerToken`, the purchase launched the base plan every time while the paywall read its "1 week free
  trial" sentence from Play's eligible offers, so Play's sheet said "Starting today" at the full price.
  It now uses the same free-trial offer the sentence came from, and the base plan when Play returns
  none. An explicit `offerToken` is still bought as given; an introductory price is never chosen
  automatically.
- **`onEntitlementsChanged` fires on a renewal, and on the main thread**. Only a change of
  product id was reported, so a renewal, a grace period or a billing-retry recovery fired nothing,
  and the lambda could run on a background thread.
- **A paywall whose purchase button is set only at the document root draws it**. The plans
  could be selected and nothing bought them.
- **The particle effect fires on its authored trigger**: `on_appear` when the paywall
  shows, `on_purchase` once a purchase has succeeded. It used to start on the purchase-button tap
  whatever its trigger, and keep running over a payment-failed message.
- **An authored shadow draws as authored**: offset, blur, spread and colour. Android drew the
  same faint elevation shadow for every value, including a fully transparent one.
- **A colour is parsed the same way everywhere**. Paywalls and surveys read `#RRGGBBAA` as
  ARGB and drew an unreadable colour as opaque black or white; they now use the SDK's one parser.
- **A comparison table draws its column headers** when `columns` is a list of labels.
- **A named feature icon draws**. `icon: "check"` printed the word "check".
- **A numeric plan price is shown**. A price authored as a number drew empty, and an integral
  one as "9.0"; it now reads "9", as on iOS.
- **The sticky footer honours `blur_background`** with a translucent layer, and a rounded
  plan badge has the same 6 dp corner as on iOS.
- **The close button no longer covers the header title**. The header reserves room for it.
- **The default confetti and fireworks colours are the same as on iOS**.

**Onboarding**

- **A button with an action the SDK does not recognise (for example `continue`) advances with the
  step's answers**. It used to advance and discard them.
- **Coming back to a question step shows the options the user chose**, with Continue enabled.
- **A dropdown-style select opens the picked option's bottom sheet**.
- **A multi-select field's stored value draws and records the same way on iOS and Android.** A list
  counts as the selection only when every element is a String. A single answer, a number or a list
  holding one (`[1]`, `["a", 2]`) draws nothing checked, also when a host writes it mid-step, so the
  next tap starts from an empty list. A selection on a host-data-scoped Select is pruned only when it
  is such a list.
- **A chips field follows the same rule when a host writes a value mid-step.** A list counts only when
  every element is a String, and a lone String as one chip. A mixed `["a", 2]` drew `a` checked, and
  the next tap stored it again; it now draws nothing, as on iOS.
- **A step whose `layout.content_blocks` is empty falls back to the step's root blocks**, as
  on iOS. It used to render blank.
- **A circular countdown's ring sweeps**. It sat at 75% for the whole countdown.
- **A Multi-buttons border colour draws a border, and `background_full_bleed` reaches the screen
  edges**.
- **Rive state-machine inputs apply**. `rive_inputs` was never read; a string input
  now fires a trigger, as on iOS.
- **A Google map pans with a drag**. The step's scroll took vertical drags that began on the
  map.
- **Opening a step with a map no longer blocks the main thread**. The Maps renderer is now
  loaded in the background when a flow containing a map opens; it used to stall the app for up to
  about a second.
- **A selected location answer, and any list or map answer, reads back the same after a relaunch**.
 Nested values were persisted as their `toString()`, so after a restart `getLocationData`
  returned `"{formatted_address=…, city=…}"` as the address and nothing else, and a multi-select
  answer came back as the text `"[a, b]"`. They are now stored as JSON and read back as the `Map` /
  `List` they were, as on iOS; the same applies to `setSessionData`. An answer saved by an earlier
  version still reads as typed text. A value JSON cannot hold (NaN, infinity, a non-JSON type) is
  kept for the session but not persisted.
- **`{{onboarding.<step>.<field>.city}}` now resolves**. The token path was split at most
  three ways, so the field id became `user_location.city`. A token now walks into a structured answer
  to any depth, on `onboarding.*` and `responses.*`.

**Location fields**

- **The Location content block honours its settings**. Location Type, bias country,
  language and minimum characters now apply, as on the form-step field. An unset type sends no type,
  as on iOS (it sent `address`), and a type the proxy does not accept is dropped instead of returning
  no suggestions. A suggestion no longer reads "Kyoto / Kyoto, Japan".
- **Minimum characters counts the characters a user sees**. An emoji or a flag counted as
  two or four, so the search started early.
- **The keyboard's Search key leaves a location field**, closing the dropdown and the
  keyboard as return does on iOS. Leaving the field also cancels a pending search.

**Rich text**

- **Rich text and an agreement's copy draw markdown as iOS draws it**. Android knew
  only `**bold**`, `*italic*` and `++underline++`, so `_emphasis_`, `__strong__`, `~~struck~~`, a code
  span and a backslash escape were drawn as typed, nested markers paired differently, a link with an
  invalid URL showed its raw markdown and an image showed `![alt](…)`. Inline markdown now follows
  CommonMark as iOS's parser does: `_` and `*` emphasis (an `_` inside a word stays text),
  strikethrough, code spans in a monospaced face, escapes, an invalid link's label as plain text, and
  an image's alt text. An agreement's copy no longer draws `++underline++`, which iOS never drew there.
- **An HTML entity draws as its character, as on iOS**. `&amp;`, `&copy;`, `&nbsp;` and
  `&#35;` were drawn as typed; every HTML5 named reference (with its `;`) and every numeric one is now
  decoded, in text, a link's label and address, and an `<…>` autolink, but not inside a code span or
  after a backslash. An inline HTML tag is still drawn as typed, but the markdown inside it is no
  longer read.
- **A link to an international host opens the same host as on iOS**. The host was converted
  with IDNA2003, so a link to `straße.de` opened `strasse.de`, a different domain. It is now converted
  as iOS converts it (UTS #46, non-transitional): `straße.de` opens `xn--strae-oqa.de`, a zero-width
  joiner is kept only where its script allows it (otherwise the text draws without a link), and every
  label of an international host is lowercased.

**Surveys**

- **An emoji answer's sentiment is read against the question's own scale**. Answers
  on the console's default scale or any custom scale were neutral, and a CSAT question answered with
  an emoji was always neutral, so `on_positive` / `on_negative` follow-ups never ran.
- **The feedback form is shown on the screen underneath the survey**. It was built on the
  closing survey screen and leaked its window.
- **The feedback form records how it ended, and which survey it was**. A back press, a tap
  outside, or the host taking the screen away emitted nothing, and `survey_feedback_submitted` had
  no `survey_id`.
- **A choice option's icon object draws its icon**. It was ignored.
- **A survey trigger's `neq`, `in` and `not_in` conditions are implemented**.
- **A survey skipped for its love-score or minimum-sessions rule says so** in the debug log.

**Screens**

- **A screen section draws its style**: background, gradient, padding, margin, radius,
  border, shadow and opacity, and the screen's `layout.padding`. A section's bare `thumbnail_url` and
  `speed` keys are honoured.

**Identity, targeting & networking**

- **`identify` sends a `LocationData` trait as its answer map**. It used to send
  null and store the object's `toString()`. Any other non-JSON trait is dropped with a warning. A null
  trait (including the nulls in a React Native location answer) is kept, across a relaunch, and
  `{{user.x | fallback}}` takes its fallback for it.
- **An audience operator the SDK does not implement matches nobody**. It used to match every
  user. `not_exists` is implemented.
- **A multi-select Select records its picks the same way everywhere**. Tapping an option over a
  stored single answer now leaves only the tapped option, as on iOS (it used to keep both), and picking an
  option whose `value` is empty records it, so a later `{{selected.<field_id>.label}}` reads it.
- **A deferred deep link survives a failed first lookup**. A launch with no network used to
  lose the link for good; up to four failed attempts are retried on later launches.
- **Compressed server responses are read**. The SDK set `Accept-Encoding` itself, which stops
  OkHttp decompressing, so any gzip response failed to parse.

### Removed

- **Inert config fields**: `affects_price`, `justify`, and the Lottie `lottie_json` /
  `color_overrides` keys. None of them was drawn; use `lottie_url` and `row_distribution`. A published
  document that still carries them decodes as before.

## [1.0.55] — released

Wraps iOS 1.0.83. Adds host-reported paying users.

### Added

- **`AppDNA.reportPayingUser(productId:priceCents:currency:)`** — tell AppDNA that the current user
  is in a paid state for a subscription the SDK never sold: billing run by your own server, a web
  checkout, a seat sold by a salesperson. Emits `paying_user_reported` with `source: "host"`.
  - Every argument is optional, and the price is analytics only — the paying-user meter counts
    distinct users, not money.
  - **Safe to call on every launch.** The meter counts distinct users, so repeating it in a month
    costs nothing and misses nothing; reporting the current state is reliable where trying to catch
    the upgrade transition is not.
  - **Call `identify()` first.** The SDK attaches the identity it already holds — there is no
    user-id argument, so an app can only ever report its own users.
  - Before `configure()` it logs at debug and returns, like every other method: an assertion that
    cannot be attributed to an app must not be queued as one.

### Fixed

- **`shutdown()` now clears the pre-init entitlement listener queue.** A listener registered through
  `onEntitlementsChanged` before billing had finished starting was queued, and the queue outlived
  `shutdown()` — so a later `configure()` flushed it into the new entitlement cache and delivered
  entitlement changes to a host that had shut the SDK down. Listeners that had already been attached
  were never affected: they go with the cache that `shutdown()` destroys.

  This brings Android in line with the contract iOS already states in
  `removeAllEntitlementsChangedHandlers()` — once `shutdown()` returns, the pre-shutdown handlers are
  gone, and anything registered afterwards belongs to the caller and survives. Registering during or
  after `shutdown()` is unchanged and still works.

## [1.0.54]

Wraps iOS 1.0.82. Ships maps and host data (including "Show more").

### Added
- `AppDNA.isPushTapIntent(intent)`: whether `handlePushTap(intent)` would answer `true`, without tracking,
  routing or changing anything; it works before `configure`.
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
- **`flushInterval`, `batchSize` and `configTTL` follow one precedence:** your option > the value the server
  returns with the bootstrap request (if positive) > the built-in default (30 s, no cap, 3600 s). The three
  properties keep their non-null types (`Long` / `Int` / `Long`); a value left at its default — `30L`, `100`,
  `3600L` — is "not set" (passing the default is the same as not passing it, on iOS too). `batchSize`'s default
  is now `100` (was `20`, which had no effect). `batchSize` now works: it caps the network-sized batch
  (100 / 50 / 20) — the flush threshold and the most one upload sends, in the WorkManager uploader too; a value
  below 1 is ignored (logged), as if not set. Before, `batchSize` was used only without a connectivity monitor
  (never), and the bootstrap's settings were not read.
- ⚠️ **If you already pass `batchSize`, it is now a hard cap.** Before this release the value had no effect;
  with `batchSize = 10`, every upload now sends at most 10 events. Remove it, or raise it, unless that is what
  you want.
- `onElementInteraction` behaviour for every flow: `inputValuePatches` merge onto the answers as they
  are when the reply lands (a pick made during the call is kept); a reply that arrives after the user
  left the step — or started a newer presentation of it — is dropped, `advance` included; the
  in-flight lock is per step presentation; a call started from the outgoing step during the exit
  transition is refused (it used to be sent with the incoming step's id); interaction overlays reset
  when the same view is reused for a new presentation.

### Fixed
- **Experiments: Traffic Allocation and targeting are applied on the device.** The experiment's traffic
  allocation and its targeting rules (countries — the device region; minimum app version — `versionName`;
  new users only — `PackageInfo.firstInstallTime` at or after the experiment's start; user trait
  conditions) now decide whether the user is in the experiment before a variant is assigned. A user who is
  not in gets `null` from `getVariant` / `getExperimentConfig`, `false` from `isInVariant`, the live
  entity on a servable surface, and no `experiment_exposure`. Before, every user on a targeted platform
  got a variant (the SDK read a top-level `segments` key nothing writes). Allocation uses its own hash
  (`<experimentId>.<salt>.<userId>.allocation`), so the variant split is unchanged and raising the
  allocation keeps everyone who was in. `ExperimentConfig` gains `trafficAllocation`, `targeting` and
  `startedAtMs`; a doc without them still serves everyone.
- **Event uploads during an outage.** After 5 consecutive upload failures the queue pauses, but every
  `track()` that filled a batch cleared the pause again and ran a full upload cycle (one attempt and up
  to three retries). Now only the app coming to the foreground and `AppDNA.flush()` clear the pause, as on
  iOS; the batch threshold, the interval timer, backgrounding and `shutdown()` keep it. The pause now also
  holds for the WorkManager upload: backgrounding a paused app schedules none, and one that runs while paused
  uploads nothing (the pause is persisted, so a run in a fresh process sees it); before, backgrounding
  started exactly the uploads the pause stops. The persisted pause belongs to the newest queue: the last
  upload of a queue `shutdown()` ended can no longer pause the next `configure()`'s session.
- **A large event backlog no longer slows every upload or `configure()`.** Removing a delivered batch and
  dropping events older than 7 days parsed every stored event (the second inside a write transaction that a
  `configure()` running at the same time waited on), and building the event queue in `configure()` read and
  parsed every stored event. The event database now keeps each event's id and timestamp in their own
  columns, filled once from the stored events on the first launch after the update, so both are single SQL
  statements; `configure()` reads only the 1,000 newest events, the ones the queue holds in memory.
- **Wi-Fi dropping while cellular is up no longer stops uploads.** Any lost network set the connection to
  "none" — an upload batch size of 0 — even with another network still up, until the next network change.
  The SDK now re-reads the active network when one is lost.
- `AppDNA.shutdown()` now makes one last attempt to upload the queued events, as iOS does. Before, it only
  scheduled the background upload. When the attempt has finished, the events it did not send are handed to
  the background upload, which sends every remaining batch; they also stay on the device for the next
  `configure()`. The background upload no longer gives up when another upload is running (it ran during the
  attempt, found it running and ended without sending anything — the rest waited for the next
  `configure()`): it retries after WorkManager's backoff. It also used to send one batch of 100 per run.
- `AppDNA.shutdown()` no longer closes the event database under that last attempt, which reopened it (a
  connection left open on every shutdown — every React Native reload) or failed to remove the events it had
  sent, so they were sent again. The attempt closes the database when it has finished.
- After `shutdown()` and a new `configure()`, the new session no longer sends again the events the old
  session's last upload delivered, and no upload sends an event another upload already delivered.
- **A failed bootstrap no longer lasts the whole session.** When the bootstrap request fails (for example,
  the app starts offline), the SDK still becomes ready on cached and bundled config, now reports
  `AppDNAInitError.BootstrapFailed` through `onInitDegraded` / `lastInitError` (as iOS does), and retries the
  bootstrap: when the network comes back or the app comes to the foreground — every trigger (foreground or
  network regained) waits a random 0–1 s — and otherwise after 5 s, doubling to 5 min, each wait ±25 % — at most 10 retries per `configure()`,
  none while offline. A 401 or 403 (the server refused the API key) ends the retries, and one at
  `configure()` starts none; a 429's `Retry-After` (up to 120 s) holds the next retry back. A retry that succeeds is
  applied as a first success would be: remote config is fetched, the Firestore listeners of a signed-in user
  start, the runtime lock applies, and `lastInitError` returns to `null`; `onReady` does not fire again.
  Before, the SDK stayed on cached config, without listeners, until the next `configure()`.
- A `shutdown()` that landed after the bootstrap response had arrived but before `configure` finished (for
  example from the runtime-lock delegate) left the SDK marked ready: `onReady` fired, and every later
  `configure()` was ignored as "called multiple times" until the app restarted. After `shutdown()` and a new
  `configure()`, the first configure's late bootstrap could also mark the new one ready, and fire its
  `onReady`, before the new bootstrap had answered. A bootstrap that finishes after `shutdown()`, or after a
  newer `configure()`, is now ignored.
- **Android 8.0 / 8.1 crash on a push with a channel group.** A push whose data carried
  `channel_group` called `NotificationManager.getNotificationChannelGroup`, which is API 28, under an
  API 26 guard: the messaging service threw `NoSuchMethodError` and the app crashed. Below API 28 the
  SDK now reads the existing groups from `notificationChannelGroups`. Lint `NewApi` is now a CI gate.
- Dismissing a paywall, onboarding flow, screen, message or survey while another presentation was
  queued behind it crashed on every Android version below 15 (`NoSuchMethodError`:
  `List.removeFirst`, an API 35 method). The queued presentation now runs.
- On Android 7 (API 24 / 25) ISO-8601 dates with a zone are parsed with their zone. `java.time` is
  API 26, and without it each caller fell back differently: screen schedule windows and auto-trigger
  start dates were read in the device's time zone (the zone was ignored); in-app message schedule
  windows kept only the date, at UTC midnight; and the timestamps of a restored chat transcript were
  replaced with the current time.
- `AppDNA.features.onChanged` fires: it registered nothing (the listener was a stub). It delivers every
  flag with its `isEnabled` value.
- `AppDNA.remoteConfig.onChanged` and `AppDNA.features.onChanged` callbacks registered before
  `configure`, or before `shutdown` and a new `configure`, are kept and fire for the configured SDK.
  They were attached only to the manager that existed at the time, so they were dropped.
- The in-app message delegate (`AppDNA.inAppMessages.setDelegate`) and async veto
  (`setAsyncShouldShowMessage`) set before `configure`, or before `shutdown` and a new `configure`, are
  kept and applied to every message manager `configure` builds, as on iOS. They were dropped.
- A push delegate set before `configure` (`AppDNA.push.setDelegate` or `AppDNA.pushDelegate`) is kept
  and used once `configure` runs, and again after `shutdown` and a new `configure`, as on iOS. It was
  dropped, so `onPushReceived` / `onPushTapped` never fired for a host that set it first, while
  `AppDNA.pushDelegate` still returned it.
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
- Schedule windows read dates as iOS does. A screen's or auto-trigger's `start_date` / `end_date` is an
  ISO-8601 instant with its zone (`Z` or an offset); anything else is no constraint. A bad `end_date` used
  to hide an auto-triggered screen for good, and a zone-less one was read in the device's time zone. An
  in-app message's window also takes a whole `yyyy-MM-dd` date (UTC midnight; it must be a real date),
  and no longer takes the date part of a zone-less instant or rolls an impossible date over.
- Every date the SDK reads from its config is read exactly as iOS reads it, on every API level: schedule
  windows, chat transcript timestamps, a countdown block's `target_datetime` and a form step date
  field's `min_date` / `max_date`. The SDK used `java.time` on API 26+ and `SimpleDateFormat`
  on API 24 / 25, and both differed from iOS and from each other: `2026-5-7T10:00:00Z` parsed only on API
  24 / 25; a space around an instant was trimmed (iOS skips only leading whitespace and ignores anything
  after the zone); `+02` and a lowercase `z` failed on API 26+; a date-only value with a 5-digit year,
  Arabic-Indic or full-width digits or a trailing NBSP differed. As on iOS: seconds are required
  (`T10:00Z` is no date), `T` must be upper case, and an instant's day rolls over (`2026-02-30T…` is
  2 March). An instant whose date and time as written fall outside iOS's range — from
  `-5877520-03-03T00:00:00` up to, not including, `5874898-06-04T00:00:00` — is no date, as on iOS.
- A countdown's `target_datetime` is a zone-less `yyyy-MM-dd'T'HH:mm:ss` in UTC, as on iOS: a value
  with a zone (`…T10:00:00Z`, `+02:00`), a fraction or an impossible date (`2026-02-30T…`) now counts
  down `duration_seconds` (default 300), as iOS does. Android read the date-time prefix of such a value
  and rolled an impossible date over.
- A countdown whose `target_datetime` is more than about 68 years away counts the real time, as on iOS: the
  remaining seconds were capped at `Int.MAX_VALUE` (2,147,483,647 s, shown as 24855 days).
- After `identify` switches to another user, a Firestore entitlement snapshot of the previous user that was
  already on its way to the main thread is dropped; it could land in the new user's freshly cleared
  entitlement cache.
- A form step date field's `min_date` / `max_date` is a real `yyyy-MM-dd` date at midnight in the
  device's time zone, as on iOS; a value that is not one (`2026-02-30`, `2026-05-07T10:00:00Z`) is no
  bound, and a `min_date` after the `max_date` applies neither. Android rolled an impossible date over,
  read the date prefix of a longer value and passed a descending pair to the picker. (A date before
  the device's zone had standard time — before 1883 in Los Angeles — can be under an hour from iOS's
  midnight: iOS applies the zone's local mean time there.)
- After `shutdown()`, `remoteConfig` and `features` no longer keep the torn-down manager: an `onChanged`
  callback registered before the next `configure()` goes to the new manager only.
- An in-app message delegate or async veto set on another thread while `configure()` runs is no longer
  lost: the manager is published first, then given them.
- The consumer ProGuard rules keep the names of the SDK's exception classes, so a minified app reports the same
  error names as a debug build (the Flutter and React Native wrappers send them to Dart / JS).
- The event database's v2 → v3 upgrade (lookup columns for a 10,000-event backlog) runs as one chunked
  back-fill with one compiled statement, not a statement per row, on the thread that first opens the database.
- Two helpers upgrading the same event database (the queue's and the WorkManager uploader's) no longer fail
  with "duplicate column": every upgrade checks the table before it changes it.
- From this version on, going back to an older SDK (1.0.54 or later) keeps the queued events instead of failing to
  open the event database; a table that version cannot use is rebuilt and its events counted as dropped. Going back
  to 1.0.53 or earlier still fails to open the newer database (`Can't downgrade database`), so events are not
  recorded until the app is updated again.
- The 5 MB event quota measures the queued events' bytes (each event's JSON plus one byte, as iOS) instead of
  the SQLite file, and evicts the oldest 10 % until they fit (it evicted 10 % once per insert, measuring pages,
  indexes and the free list — a small quota emptied the table).
- The WorkManager uploader drops and counts a permanently rejected batch and ends its run; a fresh run (after
  WorkManager's backoff) sends the rest, as on iOS. It used to go on with up to 50 more batches at once. A 408 is
  retried (it was dropped as permanent).
- **A 401 or 403 (an invalid or revoked API key) pauses uploads at once** until the next foreground or
  `AppDNA.flush()`, in the in-process queue and the WorkManager uploader alike (iOS the same). Every later batch
  would be rejected the same way; the queue used to pause only after 5 failed cycles, dropping a batch on each, and
  the uploader did not pause. Any other permanent 4xx still drops only its batch.
- The in-process queue reloads its in-memory window from the database once it drains, so a backlog larger than
  1,000 events is sent without waiting for the background uploader or the next launch. Events another upload had
  already delivered but that were still stored are removed when the reload skips them; a front of 1,000 of them
  used to stop the reload for good.
- Queuing an event (`track()`) no longer reads the whole event table: the disk quota reads running totals the
  database keeps (it summed the table on every insert, on the calling thread — usually the main one). The totals
  are rebuilt from the table when an open finds them missing or off.
- An eviction counts its dropped events once, after it commits: a rolled-back eviction counts nothing (it counted
  each pass inside its transaction).
- Two helpers creating a new event database at once (the queue's and the WorkManager uploader's) no longer fail
  with "table pending_events already exists": creation is idempotent.
- A 429 from the bootstrap at `configure()` holds the first bootstrap retry back for its `Retry-After` (only a
  retry's own 429 was honoured), and a network-back or foreground trigger that arrives while a retry is in
  flight no longer starts the next retry 0–1 s later: a failed retry is followed by its backoff.
- **Offline cold start serves the cached config again.** Paywalls, onboarding flows, surveys, in-app messages
  and experiments cached by a previous session were skipped on load (each cache was read back as JSON objects
  its parser did not accept); object / list remote-config values keep the shapes an online session returns,
  and cached surveys reach the survey manager at start-up instead of after the next fetch. Cached values keep
  the number types they had online (a whole-number double such as `2.0` came back as the integer `2`, so a
  remote-config value read as a `Double` was null).
- Experiment targeting answers as on iOS: the device country falls back to the locale list (a region-less
  default locale gave none; an area code such as `419` is not a country) — the SIM's and the mobile network's
  country are not read, as iOS has none; a malformed entry of `variants` (an id that is not a string, a weight
  that is not a number …) is dropped on its own (a bad id read as "unknown" and a bad weight as 0, so users
  bucketed into a variant iOS did not have); a `started_at_ms` outside the 64-bit range is absent (it saturated, so
  "new users only" let everyone in); a
  `traffic_allocation` that is not a number allocates no one (was everyone); a trait condition with a missing or
  non-string trait, or a non-string operator, excludes the user.
- Losing Wi-Fi while cellular is up no longer leaves the SDK offline (batch size 0) when the system has not yet
  switched its default network, and a VPN or other connected network counts as connected on every path. A
  capabilities change on a network that is not the default one (cellular staying up behind Wi-Fi) no longer sets
  the connection type: the default network decides it.

### Rolling back

- **Do not roll back to 1.0.53 or earlier after shipping this version.** This version's event database is schema
  version 4; 1.0.53 writes version 2 and has no downgrade path. Opened by 1.0.53 or earlier, the event database
  fails to open: no event is recorded or sent until the app updates again to a version with Android SDK 1.0.54 or later (or is reinstalled, or its data is cleared). Roll back to 1.0.54 or later only; from 1.0.54 on, an older
  SDK keeps a newer database's events (see Fixed).
- The Flutter and React Native wrappers carry the same database: Flutter plugin 1.0.19 and React Native 1.0.18 and
  earlier use Android SDK 1.0.53 or earlier.

### Binary compatibility

- **`AppDNAOptions` is binary- and source-compatible with 1.0.53 again.** An unreleased change had made
  `flushInterval`, `batchSize` and `configTTL` nullable, which changed the getters, `componentN()`, `copy(...)`
  and the constructors' JVM signatures (a host built against 1.0.53 would fail with `NoSuchMethodError`). They
  are non-null with their 1.0.53 types again (`batchSize`'s default is now `100`); "set by the host" is the
  value differing from the default. Proven by a Java and a Kotlin consumer test against the 1.0.53 signatures.
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
  handler being non-null, and an earlier change had just made that handler unconditional — so the proxy for
  "is skipping allowed" stopped meaning anything. It reads `skip_enabled` now, which is the flag the
  console actually writes.
- **`horizontal_align` did nothing on a width-constrained block.** A block with `element_width` of
  `75%` or `300px` got its sizing box first, and the alignment was then applied *inside* a box
  already shrunk to that width — so there was no room left to move in. A width-constrained block now
  gets an outer full-width box that carries the alignment, matching SwiftUI's frame order. Affected
  any width-constrained block (for example a 75%-wide button) authored `center` or `right`.
- **Content sat behind the pinned bottom zone** and could not be scrolled to. The scroll area
  reserved a hardcoded 80dp under a zone that is routinely ~140dp (two buttons plus a legal line),
  so the last lines of a summary were unreachable — the scroll range ended before them. The reserve
  is the zone's measured height now, and stays 0 when there is no bottom zone so short centred
  screens stay centred.
- **Summary cards vanished** when a single token did not resolve. The SDK dropped the whole stat,
  so one missing key in the host payload took the row with it. A card is dropped only when it would
  say nothing at all: an unresolved *value* keeps the card and shows an em-dash, an unresolved
  *label* keeps the value and drops the caption.
- The **"or" text inside a social-login divider** was pinned to the theme's 60%-alpha on-surface
  colour, so the authored colour applied to the line and not to the word between its segments.
  Reads `divider_text_color` now.

### Added
- **Multi-buttons** — a group that lays its children out 1–3 per row, with the short last row either
  centred or stretched, and its own background and corner radius. The children are real `button`
  blocks carrying every Button setting, because each one triggers something different in the host
  app.
- A button action that **refreshes the step in place**. Until now `onElementInteraction` was fired
  only by a fixed set of interactive blocks and never by a button, so a "Show more options" control
  had no way to ask the host for new content without also advancing. The host's reply can now
  replace a named block's `field_options`, which is where a select's options actually live.
- Paywalls: a **standalone Back button** with its own style, position, colour, size, delay and text,
  independent of the close affordance.
- Paywalls: **Restore background colour and corner radius**, and a corner radius on each extra CTA
  button.

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

per-item remote-config serving (read side). Feature flags and
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

experiment-aware presentation. The four served surface
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

Hard SDK suspension. When the backend signals the SDK is in
locked mode (per-key suspended at day 20+ via the sweep, OR org
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
wraps for cross-platform feature parity: an earlier release brought Android to iOS
1.0.60 parity, and a later one mirrored the 4 iOS 1.0.61
entitlement-aware paywall fixes (entitlement gate, bridge restore,
auto-dismiss, identify-time cache refresh) onto Android in the same
release tag. Note: an earlier draft of this changelog tagged this release
as `1.0.60`; that was a mis-merge of the iOS version into the Android
sequence and has been corrected.

Cross-platform feature parity catch-up to iOS — closes the gap from previous
Android-only follow-up debt (rich media, theming, auth and restore, network,
push, identity and events) in a single release.

### Added

- **Onboarding** — full `form` step type port (13 P0 input types + 8 P1
  extended types), strict-typed action passthrough, async step hooks
  (`onBeforeStepAdvance`, `onBeforeStepRender`), and `StepAdvanceResult.Stay`
  semantics with optional banner message.
- **Paywalls** — strict-typed restore lifecycle (`onPaywallRestoreStarted`/
  `Completed`/`Failed`) and 12-method `OnboardingPaywallBridge` forwarding so
  paywall actions invoked from an onboarding step pass through correctly.
- **Rich media** — Lottie + Rive (always bundled), SVG, GIF, and
  inline video via Media3 ExoPlayer; Lucide / Material / SF-Symbols-equivalent
  icon library; blur / glassmorphism modifiers; haptics; confetti and particle
  overlays across messages, surveys, and push notifications.
- **Theming** — light / dark theme tokens with deterministic
  resolution and system-appearance follow.
- **Auth + restore** — strict-typed auth context and restore flow
  parity with iOS.
- **Push** — strict-typed action buttons, NSE-equivalent rich content
  (image / GIF / video) helper, and per-action delegate routing.
- **Network + push hardening** — 25 items: typed retry
  policy, jitter, hard-cap reconnect on `BillingConnectionManager`, push
  delegate integration, etc.
- **Identity + events** — 22 items: anonymous-id
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
