# Changelog

All notable changes to the AppDNA Android SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [1.0.60] — 2026-11-21

### Added
- In-app messages and surveys honour audience rules. A message's or survey's `audience_rules` (the same shape as an
  onboarding flow's or a paywall's: user traits, and `attribution.<field>` / `attribution.param.<key>` keys over the
  stored install attribution) decide whether it triggers for this user; a message or survey with no rules is shown to
  everyone, as before. With no attribution stored, an attribution rule does not match (except `not_exists`).
  `surveys.presentByPlacement(placement)` now orders the surveys on a name like onboarding flows (higher audience priority, then a
  targeted survey before a catch-all, then lowest id) and skips one whose rules do not match. See
  [Target content by acquisition source](https://docs.appdna.ai/sdks/android/api-reference#target-content-by-acquisition-source).
  Shared fixtures `audience_eval/message_audience_attribution_network`, `audience_eval/survey_audience_attribution_param`,
  `audience_eval/content_audience_trait_or_attribution`.
- `AppDNA.setAttribution(map)` and `AppDNA.waitForAttribution(timeoutMs) { }`: hand AppDNA the attribution your MMP's
  on-device callback delivers, and audience rules on onboarding flows, paywalls and experiments can target any
  acquisition field (`attribution.<field>`, `attribution.param.<key>`, with `starts_with`) on the first launch. Stored on
  the device and sent as the `attribution_set` event. Onboarding selection now evaluates audience rules for a user with
  no traits when attribution is stored. See
  [Target content by acquisition source](https://docs.appdna.ai/sdks/android/api-reference#target-content-by-acquisition-source).
- `AppDNA.getAnonymousId(): String?`: the anonymous ID AppDNA keys this install's events by. Pass it to your mobile
  measurement partner (AppsFlyer, Adjust, Singular) as its customer user ID before the MMP starts, so AppDNA can join
  each install and its campaign to the person. Stable for the life of the install (it survives restarts, `identify`
  and `reset()`); `null` until `configure` has been called. See
  [Mobile measurement partners](https://docs.appdna.ai/sdks/android/api-reference#mobile-measurement-partners).
  Shared fixtures `identity/anonymous_id_*`.

### Fixed
- Audience rules: `gte` and `lte` no longer match a user who has no value for the trait, or a value that is not a number. A paywall or onboarding flow targeted at `seat_count gte 10` was shown to every user who never sent `seat_count`. `gt`, `gte`, `lt` and `lte` now match only when both the trait and the rule value are numbers (a numeric string such as `"10"` still counts). Shared fixtures `audience_eval/gte_fails_closed_for_absent_trait`, `lte_fails_closed_for_non_numeric_trait`, `gte_lte_match_at_the_boundary`.
- **Published screens and flows reach the device.** `showScreen`, `showFlow`, screen slots, navigation
  interceptions and screen triggers used to end in `screen_not_found` unless the host had called `previewScreen`
  first: the SDK read the screen index but never the screens in it. The config load now loads every screen and flow
  the index lists (including slot and interception screens), and a cache miss fetches that one screen, or the flow
  and its screens, before giving up. Shared fixture `dto_parsing/screen_served_document`.
- **Screen triggers run on both platforms, and their caps survive a restart.** No tracked event ever reached the
  screen triggers, so a screen authored with a trigger never presented itself. Every tracked event (yours and the
  SDK's own) now runs them after in-app messages and surveys, and a host screen appearing runs the `on_screen` ones.
  A days-since-install trigger uses the app's real install time (it was always 0). Max impressions and cooldowns
  are kept on the device. Shared fixtures
  `event_emission/sdk_events_reach_message_and_survey_triggers` and `trigger_hold_is_bounded`.
- **What a user enters on a screen reaches your app.** Values typed or picked in a screen's content blocks stayed in
  the view, so `onScreenDismissed` and `onFlowCompleted` got empty `responses`. They are now keyed by the block's
  field ID, and a flow's navigation rules can branch on them.
- **`{{…}}` tokens resolve in a screen's content blocks**, as on iOS: a block's text, labels and options now resolve
  the screen's responses, hook data and live inputs. They used to be drawn literally, such as `Hi {{user.first_name}}`.
- An onboarding flow no longer crashes a debug host built with Android Gradle Plugin 8.2 (`VerifyError` in
  `WidgetActionChannelKt.withHowtoBlocks`). D8 8.2 mis-dexed that one method; every flow was affected, release builds
  and newer AGP were not.
- A survey question taller than the survey now scrolls, and Back / Next / "Not now" stay on screen. A bottom-sheet
  survey with a long choice question used to push them off the sheet, so it could not be finished.
- A date answer drawn through `{{responses.<step>.<field>}}` (a `date_wheel_picker` or `input_date` in `date` mode, or
  a form step's `date` field) now shows in the phone's locale, medium date style (`Mar 15, 1990`, `15.03.1990`), the
  same day in every time zone. It used to show as stored (`1990-03-15`). The stored value, and what reaches your app,
  hooks and webhooks, stays `yyyy-MM-dd`. Shared fixture `template_engine/date_answer_draws_in_device_locale`.

## [1.0.59] — 2026-11-19

### Added
- Every onboarding flow, paywall, in-app message and survey event now carries `content_version`, the version of the
  content on screen, and `content_source_id` while the item shows another item's content (Serve instead). Purchases
  started from a paywall carry the paywall's two properties. Both keys are reserved: values supplied through
  `PaywallContext.customData` or other app properties under either name are dropped. See
  [Content properties](https://docs.appdna.ai/api-reference/auto-tracked-events#content-properties-which-content-was-on-screen).

## [1.0.58] — 2026-11-17

Feature parity: iOS 1.0.86.

### Added

- **A plan badge can be a gradient.** `badge_gradient` on the `plans` section (`{ type, angle, stops: [{ color,
  position }] }`, the `cta_gradient` shape) fills every plan badge with a linear gradient when it has two or more
  coloured stops; otherwise the badge keeps the solid `badge_bg_color`, then the brand accent. `angle` is CSS degrees
  (default 90, left to right). Shared fixture `dto_parsing/paywall_badge_fill`.

- **Sheets inside a step.** A step's `sheets` slide up over it, opened by an `open_sheet` button or by Continue when
  a required answer it is missing is in a sheet (then no "Please fill in" pill; the sheet's `next` re-runs Continue).
  A sheet's inputs are the step's answers, live, and the step's gate counts them; a swipe, a scrim tap or Back closes
  it without declining anything. Drawn inside the step's composable, not a `ModalBottomSheet`. Events
  `onboarding_sheet_opened` and `onboarding_sheet_closed`. `close_sheet` closes it from inside.

- **A text input can be checked by your app while the user types (`onValidateInput`).** Turn on "Check with your app"
  on a Text, Email, Phone, Number or URL input in the console, and the SDK calls
  `suspend fun AppDNAOnboardingDelegate.onValidateInput(flowId, stepId, fieldId, value): InputValidationResult?` after the
  field's debounce (600 ms by default) or on the IME action / leaving the field. Answer `InputValidationResult.Valid(message)` or
  `Invalid(message)` (null or a throw is no answer); the field draws a green or red border with the message. A newer value cancels the check in
  flight, a late reply is discarded, and no answer within the field's timeout (5 s by default) means "no answer". An
  invalid value blocks Continue; a tap while a check runs waits for the answer; with no answer the user continues
  unless the author chose "Block until it's checked". Offline, the field says so and checks again when the connection
  returns. An app that does not implement the hook gets a plain text input and one warning per field. Each check tracks
  `input_validation_result` (never the value). New `field_style` keys `valid_border_color`, `checking_border_color`,
  `valid_text_color`. For wrapper bridges, `InputValidationResult.decodeBridgeReply(reply)` and `bridgeTimeoutMs()`.

- **Social Login draws Meta's official Facebook mark.** The `facebook` provider drew a bold letter "f". It now draws the
  Facebook logo from Meta's brand asset pack, unmodified: the blue mark on a light, outlined or minimal button, and the
  white mark on a dark fill (the default Facebook fill is dark). `icon_style: monochrome_light` gives the white mark and
  `monochrome_dark` the blue one, because Meta allows the logo only in Facebook Blue or white. It is drawn at 20 points
  with half a logo's width of clear space to the label. Provenance is in NOTICE.md.

- **Part of a heading in its own colour, and Justify for headings and rich text.** A `heading` whose text carries a
  `[word](color:EE6885)` run draws that word in that colour, as `rich_text` already did; a heading with no such run
  draws exactly as before. `style.alignment` (heading) and `base_style.alignment` (rich text) accept `justify`, which
  fills every line but the last edge to edge. On iOS a justified block is drawn through UIKit, keeping its bold,
  italic, colour runs and links (which still open in the in-app browser).

- **One Agreement element can hold up to five consents.** With `field_config.consents`, each consent has its own
  text, colours and `required`, drawn one under another. Each answer is stored as its own boolean under
  `<field_id>.<consent_id>`, so rules and `{{responses.*}}` read a consent like any other answer, and each required
  consent gates the CTA until it is checked. An Agreement without `consents` is unchanged.

- **An app with its own Firebase messaging service can let the SDK display what it forwards.**
  `AppDNA.push.setSdkDisplaysForwardedMessages(true)` makes the token registration report `push_display: "sdk"`, so
  AppDNA sends the device data-only messages that reach the app's service in the background too, and
  `handleMessage` / `handleMessageData` then display each forwarded AppDNA message and confirm its delivery. Stored
  on the device; `false` (the default) keeps notification messages FCM displays.

- **Push deliveries are confirmed by the device, also in the background.** The token registration reports
  `push_display: "sdk"` when the SDK's own `AppDNAMessagingService` (or a subclass) receives FCM messages in the app.
  AppDNA then sends that device data-only messages, which reach the service in the foreground and the background, so
  the SDK draws every AppDNA push (with its buttons) and tracks `push_delivered`. A data-only push uses the campaign's
  small icon (`data.icon`, a drawable name) and, without a campaign colour, the app's
  `default_notification_color`. A host with its own messaging service reports nothing and keeps notification messages.

- **A Share button action.** A button can now open the phone's share sheet (iOS `UIActivityViewController`, Android an
  `ACTION_SEND` chooser) with text and an optional link set in the console: on an onboarding button or a
  Multi-buttons child (`action: "share"`, `share_text`, `share_url`; the step stays), on an in-app message CTA
  (`cta_action: { type: "share", text, url }`; the message closes, then the sheet opens) and on a paywall extra
  button (`action: "share"`, `share_text`, `url`; the paywall stays). Text and link are trimmed; with neither,
  nothing opens. No event is tracked, because Android cannot report whether a share was completed.

- **A step that advances by itself can show a countdown.** `auto_advance_indicator` on the step (beside `auto_advance_seconds`): `show` (default false), `style` (`text`, `bar`, `both`), `text` (`{seconds}` = whole seconds left), `text_color`, `font_size`, `bar_color`, `track_color`, `bar_height`, `position` (`top`, `bottom`). The countdown takes its own space above or below the step, so it never covers the heading or a pinned button. Off by default, so published flows are unchanged.

- **An onboarding button can add the app's widget to the home screen.** A button with the `add_widget` action asks the launcher to pin the app's own home-screen widget (`AppWidgetManager.requestPinAppWidget`, Android 8 and later); the SDK ships no widget, it pins the one the app declares. With no code in the app, the SDK finds the app's widget provider; an app with several names one in the button's `action_value` (the class name, fully qualified or simple), otherwise the first by class name is used. Where the launcher cannot or will not show a pin dialog (Android 7, a launcher without pinning, a refused request, or no dialog within a short wait), the button's `howto_blocks_android` sheet is shown instead: its Done button ends the attempt, and closing it any other way (swipe, back, a tap outside, a `dismiss` button) returns to the step. The step then advances once with `widget_status` in its answers, `added`, `not_added` or `unavailable` (the app has no widget), so next-step rules can branch on it. A widget counts as added when the launcher confirms it or a new widget id of that provider appears. A widget with a configuration activity is pinned without that activity being shown, so it must draw a sensible default. Events: `widget_add_tapped`, `widget_pin_requested`, `widget_pin_unavailable`, `widget_howto_shown`, `widget_howto_dismissed`, `widget_added`, `widget_not_added` and `widget_add_unavailable`, each with `flow_id` and `step_id`. The library manifest declares one non-exported receiver for the launcher's success callback.

- **A Select or segmented question can start with an answer chosen by the author.** `field_config.default_value` on
  an `input_select` (single or multi, every display style) or an `input_segmented` names the option(s) drawn selected
  when the question shows. The default is written as the answer at that moment, as a tap writes it, so a required
  question with a default lets the user continue at once and a summary tile draws the option's label. A restored or
  host-set answer wins over it, a value that names no option is ignored, a number is read as its string, and a
  multi-select keeps the default's order and `max_selections`. Without a default nothing changes.

### Changed

- **A stack child's width is honoured.** A child of a `stack` with an `element_width` (a percentage of the stack, `px` or
  a bare number) is drawn at that width and placed by the stack's alignment, as the console preview draws it; it was drawn
  full width. Flows published with `settings.placement_rules: 2` only.

- **A block with no horizontal alignment that is narrower than its row is centred, as on iOS.** They sat at the leading edge; the console preview moves the same way. That covers
  a width under 100% or in `px`, an `auto` button, a list, a badge, a rating and a lottie with a width, top level and
  inside a vertical row. An offset now moves the block with its background. It applies only to flows the console has
  published with `settings.placement_rules: 2`; a flow published before keeps the leading edge for these blocks until it is published again.

- **The first-launch deferred-link check no longer shows Android 12+'s "pasted from your clipboard" toast for text
  that is not a link.** It read the clipboard's content on every first launch. It now reads it only when Android has
  classified the clip as a URL, which it checks from the clip's description without reading the content (Android 11
  and below have no toast and no classification, and read as before). The visitor id is taken from a link carrying
  `appdna_visitor`, or the older `appdna:visitor:<id>` text, and only that content is cleared; anything else is left
  where the user put it.

- **Every date picker works in the app's time zone, a date-only one too.** A `date_wheel_picker` or `input_date` in
  `date` mode, its calendar, and a form step's `date` field number their days in the time zone set for the app in the
  console (UTC unless changed), as the time and date-and-time pickers already did. So the day a picker opens on,
  `today` and offsets such as `-18y` in `default_date_value` / `min_date` / `max_date`, and the "today" that
  `allow_past: false` / `allow_future: false` compare against are the app's, not the phone's; near midnight they named
  different days. A form step's `time` and `datetime` fields now store the shapes every other time answer stores, the
  wall time in the app's zone with its offset (`23:03:00+09:00`, `2026-11-15T23:03:00+09:00`); they stored `HH:mm` and
  `yyyy-MM-ddTHH:mm:00` with no zone. A time picker with nothing picked yet opens on the current time in the app's zone.

- **A date-and-time or time wheel with no default opens at 12:00 on both platforms.** With nothing saved and no
  `default_date_value`, the wheel opens on today at noon in the app's time zone. iOS meant noon but took it in the
  phone's zone and drew it in the app's, so it could open on another hour (20:00 for a phone in California with
  the default UTC app zone); Android opened at 00:00. Nothing is stored until the wheel is moved, as before.

- **An image with no description is decorative, and screen readers skip it.** An onboarding image block, a
  paywall image section or a screen image section whose Alt text is blank or unset is now hidden from TalkBack
  (it was announced as "Image", or with no description); one with Alt text is read as that text, as an image. An
  in-app message's image and a survey question's image have no description field, so they are always skipped.

- **A required segmented question starts with nothing selected.** An `input_segmented` with `field_required: true`
  no longer preselects its first option, so Continue waits until the user picks one. An optional one still starts on
  its first option, and an author-set `default_value` is drawn selected either way.

### Fixed

- **A paywall comparison table draws the styling the console sets, and reads on a dark paywall.** The header row was a
  fixed `#F9FAFB` while its text followed the page, so on a dark paywall the column headers were white on near-white.
  The header row is now the page's text colour at 6% unless `header_bg_color` is set, and the section reads
  `header_text_color`, `header_font_size`, `header_font_weight`, `cell_text_color`, `cell_font_size`,
  `cell_font_weight`, `border_radius` (`corner_radius` wins), `row_alt_bg_color` (striped rows), `feature_header`
  (the top-left header, empty unless named) and a column's own `table_columns[].color` (its header and ticks). A row
  with fewer values than columns keeps its cells under their headers. Shared fixture
  `dto_parsing/paywall_comparison_table_style`.

- **System Back on an onboarding step goes to the previous step, as the flow's back arrow does.** It closed the
  whole flow from any step, losing every answer. Where the arrow is not drawn (the first step, `allow_back` off,
  `hide_back`) Back still closes the flow when `dismiss_allowed` is on, and an open step sheet still takes it first.

- **Onboarding links open in a Custom Tab, over the app.** A `link` button, a link in rich text or an option's title, an
  Agreement's links and a warning banner's link opened the external browser and left the app; they now open in a Custom
  Tab (a plain browser intent only when the device has no Custom Tabs provider), and Back returns to the flow. Matches
  iOS's in-app browser. **New dependency:** `androidx.browser:browser:1.8.0`.

- **A consent ticks from its words, not only its box.** A tap anywhere on an Agreement consent's text ticks or unticks it,
  as the 22-point box does; a link inside the text still opens the link and does not tick. On a step and inside a step's
  sheet alike.

- **A long segmented answer stops at two lines, centred.** An `input_segmented` label wrapped to any number of lines,
  aligned to the start, while iOS cut it to one. A label now wraps to at most two lines, centred, as on iOS, and a longer
  one is cut there. The selected fill already covers the whole cell.

- **A bare-number `element_width` / `element_height` is dp, as iOS and the console preview draw it.** A width
  written without a unit (`120` or `"120"`) was ignored, so the block filled its row. Both forms are now 120 dp, and an
  aligned block with a bare-number width is placed by its alignment, as one with `120px` is.

- **A rule on a later step that reads an earlier step's consent now matches.** A multi-consent Agreement answers each
  consent under `<field_id>.<consent_id>`, so a later step addresses it as `<step_id>.<field_id>.<consent_id>`. The
  key was split at its last dot, which named no step, so the rule never matched and the flow fell through to the next
  step in order. Every dot is now tried, last first; keys that matched before match the same way.

- **A text input draws its error border colour.** A single-line text input honoured `focused_border_color` and ignored
  `error_border_color`. It now strokes `error_border_color` while the field is in error, else `focused_border_color`
  while it has focus, else `border_color`, the same rule as iOS. Nothing puts a text input in error yet; the error
  colour applies once a setting does.

- **A selected segment's fill covers its whole cell when a neighbour's label wraps.** In an `input_segmented` row
  where one option's label runs to two lines, the selected segment's fill kept the height of its own (one-line) label
  and covered only the top of its cell. Every cell is now as tall as the row, and the fill covers it.

- **The rule for a button inside a bottom sheet is shared with iOS.** No behaviour change on Android: a `link` button in
  an option's `sheet_blocks` opens its URL and keeps the sheet open, and the "Add widget" how-to opens nothing. iOS
  now does the same.

- **"Maybe later" on a permission step no longer raises the OS permission prompt.** On a `permission` step that has a
  button with the `permission` action, a second button authored as `next` (a "Maybe later" or "Not now") ran the
  permission request too, so the user who declined saw the OS sheet anyway, and on iOS the app's one notification ask
  was spent on them. A `next` button now asks only when the step has no `permission` button, which is the case of a
  step whose one CTA was authored as `next`. When the step has a `permission` button, `next` advances without asking
  and records no `permission_<type>` outcome. A `permission` button inside a Multi-buttons, row or stack counts too.

- **An in-app banner or tooltip is filled with exactly its `background_color`.** Material tinted a message card by its
  elevation whenever the card's colour was the theme's surface, which the message's own background always is, so an
  authored white banner or tooltip drew grey-purple and every other colour drew darker. The card keeps its shadow and
  now draws the authored colour, as on iOS and in the console preview.

- **An in-app banner or tooltip no longer dims the app behind it, and a modal is dimmed once.** The window a message is
  shown in kept Android's default dialog dim, so every banner and tooltip drew over a darkened app like a blocking
  modal, and a modal was darkened twice: by the window and by its own scrim. The window no longer dims. A modal draws
  its own backdrop only (black at 40%, or black at 30% under a blur with `blur_backdrop`), and a banner, a tooltip and
  a fullscreen message draw none, as on iOS and in the console preview.

- **A pale gradient step has no dark band at the top.** The status-bar scrim was drawn on every gradient step, so a step
  whose gradient is light all the way down showed a dark band over its top, which iOS does not draw. A gradient whose
  every stop is light now takes no scrim, the same call that gives its unstyled text dark ink.

- **Icons the console offers draw an icon instead of a "?".** The SF Symbol table had 35 names, so most SF Symbols an
  author picked (`bolt.fill`, `gift.fill`, `trophy.fill`, `sparkles`, `crown.fill`, `paintpalette.fill`, `info.circle`,
  …) drew a "?". Every SF Symbol, Lucide and Material name the console's icon picker offers now maps to a Material
  icon, as do every Lucide and Material name iOS maps. A Lucide component name (`CheckCircle`) is read as
  `check-circle`. A paywall `icon_grid` item's icon is read as an SF Symbol name, as on iOS; a Material name still
  draws.

- **Text inputs are named for VoiceOver and TalkBack.** A text, number, email, phone, URL, password, textarea or
  location input, as a content block or a form step's field, now carries its label (else its placeholder) as its
  accessibility name. The label was drawn as a separate text above the field, so a screen reader landed on an unnamed
  text field, and UI automation could not find the field by name.

- **A required field's asterisk trails its label when the label wraps.** The `*` was drawn beside the label, so a
  label on two lines left it at the edge of the row next to the first line. It is now part of the label's text, on
  content-block fields and on the legacy form step's fields, on both platforms.

- **The selected segment of `input_segmented` draws its authored colour.** iOS drew the system grey pill whatever
  was authored, because a segmented `Picker` ignores `.tint`. The control is now drawn by the SDK, as on Android:
  the selected segment takes `field_style.fill_color`, else `active_color`, else the brand accent, and its label
  contrasts with a light fill on both platforms.

- **A chip is as wide as its label, on one line.** `input_chips` laid its chips out on iOS in an adaptive grid where
  every chip got an equal cell, so a label longer than the cell wrapped inside its chip, sometimes mid-word. Chips now
  hug their labels and flow onto the next row, as on Android; a label wider than the whole row truncates on one line.

- **`survey_question_answered` is sent once per question, not once per keystroke.** A typed answer is sent when the user
  moves on with Next or Submit, with the full text (not if it is blank); a tap answer (NPS, choice, rating, …) is sent
  when it is picked, as before, and an answer the same as the one already sent is not sent again. A typed answer sent
  one event per character, so per-question analytics counted partial strings as answers.

- **A survey stays above the keyboard while an answer is typed.** A bottom-sheet or modal survey now moves up when the
  keyboard opens for a free-text question, so the field and Submit (or Next) stay visible and tappable. The keyboard
  covered them, and a tap where Submit had been typed a letter into the answer instead.

- **A screen's or survey's unstyled text follows its own background, not the phone's theme.** A screen with a white
  background drew its unstyled text in the phone's theme (white on white in dark mode), and a section with its own
  fill did not change ink for it; a white survey drew its Yes and No labels white in dark mode. Default text now
  resolves against the authored background — dark ink on a light one, light ink on a dark, gradient or image one —
  and follows the phone only when no background is authored. Authored colours still win.

- **A legacy step's button label is white on its accent, on every step.** On a dark step the Welcome, Question,
  Value-prop, Custom and Form buttons drew their label in the step theme's dark `onPrimary` — dark purple on the
  indigo accent. They draw white, as on iOS.

- **A paywall's status-bar and navigation-bar icons contrast with the paywall.** They followed the phone's theme, so
  a white paywall in dark mode drew white icons on white. They now follow the paywall's own background: dark icons on
  a light one, light icons on a dark one, a gradient, an image or a video.

- **The `animated_loading` spinner takes the block's colour.** The checklist's current-row spinner now uses
  `progress_color`, then `accent_color`, then the app's brand accent, as on iOS.

- **A hand-off to your own billing provider shows no "Payment failed" banner.** Under a provider that owns purchases
  (Adapty, or RevenueCat when its SDK is not linked) a paywall tap reports `providerNotAvailable` so your app can buy
  with the provider. With no `post_purchase.on_failure` authored, the paywall now shows nothing for it, as for a
  cancel; an authored `on_failure` still runs. Every other failed purchase still shows the default banner. Shared
  fixtures `billing/paywall_failure_default_by_error_type` and `billing/paywall_failure_provider_handoff_is_silent`.
  A paywall tap before billing is ready now routes as the `unknown` failure it reports (it routed as a hand-off), so
  it shows the banner, as on iOS.

- **Every card plan layout shows which plan is selected with a circle.** `grid`, `horizontal_scroll` / `carousel`,
  `mini_cards`, `carousel_cards` and `single_hero` drew no selection circle; they now draw a filled check on the
  selected card and an empty ring on the others, at the card's top trailing corner, as the vertical stack and iOS do.

- **The trial badge, a plan's trial line and its feature checks match the paywall's own colours.** With no colour
  of their own they were drawn in the brand accent, or indigo when none is set, whatever the paywall's theme. They now
  take the paywall's accent: the CTA's authored colour (`cta.style.bg_color`, then `cta.bg_color`), then the brand
  accent. The social-proof trial badge also honours its own `badge_color` (the console's "Badge Color"), which was
  ignored. Shared fixture `dto_parsing/paywall_trial_badge_color`.

- **A failed purchase always tells the user.** A paywall with no `post_purchase.on_failure` showed nothing when a
  purchase failed (a product the store does not have, a declined card, a store outage): the button simply became
  tappable again. It now shows the error banner, "Payment failed. Please try again.", as `show_error` does. A
  cancelled or pending purchase stays silent. An authored `on_failure` is unchanged.

- **A generated message's or paywall's animation and video play on both platforms.** An in-app modal or
  full-screen video hero loops with no controls, as on iOS (it played once, with controls). A paywall section that
  autoplays its video shows no player controls, as on iOS, and an animation section with no height is 180 high, as on
  iOS and in the docs (it was 200). A blank `lottie_url`, `rive_url` or `video_url` on a message hero, or a blank URL
  on a paywall animation or video section, is no media: the next hero draws, or the section draws nothing. Shared
  fixtures `dto_parsing/message_hero_media` and `paywall_media_section_playback`.

- **When several served experiments name one paywall, flow, message or survey, the same one decides on every launch,
  and on both platforms.** The surface resolver walked the served experiments in map order, so with a served
  config that had diverged from the rows (an experiment whose removal never landed, still listed as running) the arm a
  user saw could change between launches, and iOS and Android could draw different experiments. They are now tried
  most recently started first (`started_at_ms`), ties by experiment id; the first one the user is in decides. Shared
  fixtures `experiment_serving/several_experiments_on_one_entity_*`.

- **A tapped row of the date wheel moves into the band.** Tapping a row that was not in the band (or double-tapping
  it with TalkBack) stored that value while the wheel stayed where it was, so the band showed one date and the answer
  held another. The tapped row now glides into the band, as on iOS. The hour and minute columns are now announced
  with their value in the band, as the date columns are.

- **A survey's Next and Submit labels use `button_text_color`.** They were always white, so a light button colour
  made them hard to read. They now use the theme's `button_text_color` (white when it is not set), as on iOS.

- **A paywall presented by placement reports its placement.** `presentPaywallByPlacement(activity, "after_workout")`
  sent `paywall_view` with `placement: "unknown"`, so per-placement paywall analytics counted every Android
  presentation as unknown. It now sends the placement it was asked for, as iOS does; a `PaywallContext` you pass keeps
  its experiment, variant and custom data.

- **A queued event batch is deleted only when the server refuses its content.** An event upload answered 401, 403,
  404 or with any other 4xx (except 408 and 429) used to delete its batch from memory and disk and count it as dropped,
  so an outage behind a tunnel or proxy that answered 401 lost every event queued during it. Now a batch is dropped,
  and counted, only on a 400, 413 or 422 that carries the AppDNA API's JSON error body. A 401 or 403 keeps the events
  and pauses uploads until the next foreground or `AppDNA.flush()`; a 404, 409, or an answer that is not the API's own
  (an HTML page from a tunnel, captive portal, proxy or load balancer, whatever its status) keeps them and retries. The consent signal follows the same rule: a 401 / 403 / 404 or a
  proxy's page is retried instead of dropping the signal until the next launch.

- **An in-app message triggered while the app has no activity is shown when the user opens the app.** A process started with no activity (an FCM data message, a background job) can track an event that triggers a message, and the message was dropped because there was nothing to draw over. It is now held, not counted, and shown the next time an activity resumes, if it is still published, inside its dates and allowed by its frequency. It is shown once however often it triggered, and several held messages are shown one at a time in trigger order, as on iOS. A message whose Share button opens the share sheet leaves the next held message until the user comes back from the sheet, as on iOS.

- **A journey's in-app message delivered during a background start is shown when the user opens the app.** When the process was running with no activity (started by an FCM data message or a background job), the SDK tried to show a message a journey sent for about 10 seconds, then stopped. The message stayed unshown until the app was next launched from cold. It is now shown the next time an activity resumes, unless it has expired, and it is still shown only once.

- **A tapped push opens its own screen when Android has bundled several.** Android groups an app's notifications automatically once it has four that carry no group of their own, and a tap on that collapsed group opened the app through the group's summary, which carries no push data: no `push_tapped`, no `onPushTapped`, and the push's deep link or screen was not opened. Each notification the SDK draws now has a group of its own, keyed by its delivery, so Android no longer bundles them and every tap is tracked and routed. A notification message that FCM draws itself (an app with its own `FirebaseMessagingService` and without `setSdkDisplaysForwardedMessages`, in the background) can still be bundled by Android; a tap on that bundle counts as an app open only.

- **A journey's in-app messages reach users who have not signed in.** The SDK listened for messages a journey sends only after `identify`, so a journey's "Send in-app message" step to an anonymous user was counted as sent and never shown. The SDK now listens from `configure` on the device's anonymous ID; after `identify` on the anonymous ID and the user ID (a journey that started before sign-in keeps sending to the anonymous ID); after `reset()` on the anonymous ID again. A message that arrives while another is on screen is shown when that one closes, a message that cannot be drawn yet (no foreground activity at a cold launch) is retried, and a message is never shown twice.

- **The Date Picker draws the style chosen in the console.** `field_config.picker_variant` (`graphical` · `wheel` · `compact`) decides what a `date_wheel_picker` draws — Android drew the wheel whatever it said, and now draws the calendar for a date with no style set, as iOS always has; unset, a date is the calendar and a date-and-time or a year the wheel. A **Date** field (`input_date` / `input_datetime`) with `picker_presentation: inline` — the console's default — always drew the calendar, so its Picker Variant changed nothing: it now draws the variant chosen.

- **Every colour of a measurement wheel picker can be set.** The `wheel` and `dial` styles drew their band, the value on the band and the rows around it from the ruler's keys, and the unit label, the field label and both unit-toggle labels in colours no key reached. Each part now has its own `field_config` key: `band_color`, `band_text_color`, `row_text_color`, `unit_color`, `label_color`, `toggle_active_text_color` and `toggle_text_color`, beside the existing `needle_color`, `tick_color`, `track_color` and `toggle_active_color`. Unset keys draw what was drawn before. The rows around the band were tinted with the accent colour; they now take `row_text_color`, else `tick_color`, and fade by distance as on iOS.

- **In-app messages and surveys trigger on the SDK's own events.** A message or survey whose trigger is an event the SDK tracks itself (`app_open`, which is the console's default message trigger, `session_start`, `onboarding_flow_completed`, the purchase events, and the others on the auto-tracked events page) never appeared: only events passed to `track()` were checked against triggers. Every tracked event is now checked once, message first. Events tracked before the SDK has its config, such as `app_open` on a cold launch, are held (up to 32) and checked once the config is ready; a config cached from an earlier launch, or bundled in the app, is used at once. A message never triggers on the `in_app_message_*` events and a survey never triggers on the `survey_*` events, so showing one cannot show another of the same kind.

- **A select answer drawn in text shows the option's label, not its stored value.** A `{{responses.…}}`,
  `{{onboarding.<step>.<field>}}`, step or input token that names a Select, Segmented or chips answer now draws the
  picked option's label (a multi-select: the picked labels, comma-joined), where it drew the option id or the
  token's fallback. This holds for an answer with no tap behind it (a resumed flow, a default, a host-set answer), on a
  later launch's paywall, message or screen, and when two steps share a field id (each answer draws its own step's
  option). A stored value, a URL and a hook's headers keep the option id.

- **A list on its own card draws the card across the row.** A `list` with an `element_width` and a `block_style` card
  hugged its longest row, so its card was narrower than the copy above it while the console preview drew it full width.
  A list with an authored width now fills that width before its card is drawn; an unsized list still hugs.

- **A summary tile keeps its words whole, and a row of tiles shares one height.** A word wider than its tile broke
  mid-word. A tile's value now shrinks until its longest word fits (down to half size) and wraps between words, and
  every tile in a row is as tall as the tallest.

- **A paywall with no background draws a readable header.** With no authored background the paywall is drawn on the
  theme's background, but its header text was judged against white, so it could be white on a light page. The header
  now reads the page the paywall really paints.

- **Unstyled controls follow the step's background, not the phone's theme.** Option labels, segmented controls,
  chips and other unstyled parts of a step took the phone's theme while the step's background was authored, so a
  light step on a phone in dark mode drew white labels on white. Each block now takes its theme from what is drawn
  behind it; an option or dropdown with its own fill is judged by that fill, a sheet keeps the phone's theme, and
  authored colours still win. Steps built without content blocks (the older step types) follow the same rule.

- **The close control and Skip read on a light step in dark mode.** An unstyled close (X) and Skip followed the
  phone's theme and drew pale grey on a white step in dark mode. They now use a fixed colour for a light step; an
  authored `icon_color` still wins.

- **The status-bar and navigation-bar icons follow the step's background.** They followed the phone's theme, so a dark
  flow on a phone in light mode drew dark icons on dark, and a light flow in dark mode lost the clock. They now follow
  the background the step draws (its own, else the flow's); only a step with no background follows the theme.

- **A rule on a number answer matches however the number was written.** A rating answer is stored as a number, a
  published rule value of `4` is read as a whole number, and the console can write `"4"`; they were compared by type, so
  a 4-star answer could fall to the fallback rule. Next-step rules, block visibility, form dependencies, a survey's
  `show_if` and event conditions on surveys and messages now compare numbers by value. Two pieces of text still compare
  exactly.

- **The paywall's close and back circles are the size iOS draws.** The circle took the button's 48dp touch target as
  its size; it is now twice the glyph (32dp by default), inside the same 48dp touch target, so it no longer crowds the
  header title.

- **A plan card's selection circle has its own column, and its badge is drawn whole.** The layouts drawn on the shared
  plan card put the circle over the end of the plan name and clipped a badge that straddles the card's edge. The circle
  now sits beside the content, as on iOS and in the vertical stack, and the badge is not clipped.

- **A paywall feature list draws its check in `icon_color`.** The feature list section drew its checks in the brand
  accent whatever `icon_color` said; it now takes `icon_color` when set, as iOS and the console preview do.

- **The date wheel's rows fade at its edges while it scrolls.** Rows were drawn at full ink up to the wheel's edge and
  cut there, so a half-cut row slid along the top and bottom mid-drag. The rows now fade out at the wheel's top and
  bottom and stay inside it; the band is not faded.

### Added

- **A Date Picker can pick a range of days.** `field_config.selection_mode: "range"` (date mode) draws a month calendar where two taps pick a range, joined by a bar in `field_config.range_color` (unset: `highlight_color` at 20%). The answer is `<field_id>` = `start/end` (`2026-11-07/2026-11-15`) with `<field_id>_start`, `<field_id>_end` and `<field_id>_days` (both ends included); until both ends are picked `<field_id>` is unset. Tapping the month and year opens a list of years to jump to.

- **A linear style for the measurement wheel picker.** `measurement_style: "linear"` draws a vertical slider on the left, filled from the bottom (the unit's `min`) to the value, with the value and its unit on the right — the simplest height picker. With one unit the unit is locked on screen. A tap or drag picks the value under it, snapped to the unit's step, and the interaction fires when the finger lifts; the slider is adjustable with VoiceOver/TalkBack. Sizes: `linear_height` (240), `track_width` (8), `thumb_size` (28); `thumb_color` defaults to `needle_color`.

- **A button can depend on other elements on its screen.** `field_config.cta_dependencies` lists
  `{ block_id, condition }` entries (`answered`, `all_selected`, `min_selected` with `count`, `correct`, `equals` with
  `value`). Until every one holds the button is inactive (a tap does nothing) and draws in `cta_disabled_bg_color`, or its
  own colour faded; then it draws in `cta_enabled_bg_color` and acts. An entry naming an element not on the step holds.
- **A Select that advances on the pick can ask first.** `field_config.advance_confirm: true` beside `auto_advance` opens
  a dialog (`advance_confirm_text`, default "Are you sure?") before the step moves on; its confirm button
  (`advance_confirm_yes`, default "Yes") advances, and its cancel button (`advance_confirm_no`, default "No") stays with
  the pick kept.
- **A Select can say whether the pick was right.** `field_config.correct_values` marks the correct option(s); once
  the user has answered, `feedback_correct_text` (default "Correct!") in `feedback_correct_color` (default #16A34A) or
  `feedback_wrong_text` (default "Not quite.") in `feedback_wrong_color` (default #DC2626) shows under the options. A
  multi-select is judged once as many options are picked as are marked.
- **`animated_image`: one long image scrolling in a seamless loop.** Set `image_url`, `height` (the visible height; the block fills the width), `corner_radius`, `alt`, and in `field_config` `scroll_direction` (`left`, `right`, `up`, `down`) and `scroll_speed` (points per second, 1–400, default 30). The image fills the block across the scroll direction and repeats along it, so the loop never jumps.

- **A block's background can reach past it on each side.** `field_config.background_bleed_top`, `background_bleed_bottom`, `background_bleed_left` and `background_bleed_right` take `"edge"` (reach the screen edge on that side) or a number of points (up to 400). The block's content stays where it was; only the background moves. Left and right still follow `background_full_bleed`, and an authored value wins over it. Set from the console on Multi-buttons: to the edges for a group at the bottom of a screen, by a set amount for a group in the middle.

- **Foldables, tablets and wide windows.** Onboarding steps, paywalls, full-screen messages and surveys lay out in a centred column at most 560dp wide, with the background still filling the window; nothing changes at 560dp or narrower. On a window that is wider than tall, at least 720dp wide and 600dp or more in width class, an onboarding step that opens with a visual shows it in a leading pane beside the rest of the step, a paywall shows its pitch beside its plans and call to action, and a full-screen message shows its hero beside its copy (a container at most 1040dp wide; on a foldable in book posture the panes meet on the hinge, read through Jetpack WindowManager). Modals and banners are at most 560dp wide; surveys stay one column.

- **The SDK tells AppDNA when a user declines analytics.** Declined users' events never leave the device, so the
  server could not honour the decision on messages it sends itself. The SDK now sends one small consent signal
  (`POST /api/v1/ingest/consent`: the device's anonymous ID, the user's ID if known, the decision and a timestamp)
  when the decision is set or changes, when the user changes while analytics is declined, and once at each launch
  while analytics is declined. A failed signal is retried up to three times, then at the next launch; an
  acknowledged one is not sent again. `setConsent` called before `configure()` is now kept and applied when the SDK
  configures (it was dropped before). A signal not yet acknowledged at `shutdown()` is not retried after it; it is sent again at the
  next `configure`.

- **A plan's savings figure is computed from the prices drawn.** Write `{{savings_percent}}` in a plan's `badge` or `savings_text` ("Best value – save {{savings_percent}}"). The SDK fills it from the prices the paywall shows (Google Play's once it answers, the authored ones otherwise), per month and rounded down, and hides the text when no figure can be worked out. A typed percentage is drawn as written.

- **Onboarding flows and surveys can be asked for by name.** `presentOnboardingByPlacement(activity, placement)` and `surveys.presentByPlacement(placement)` show the item whose placement matches, set in the console. Several flows can share a name: higher audience priority first, then a targeted flow before a catch-all, then lowest id. `false` when nothing answers the name.

- **A stacked option's title can carry links and coloured words.** `label_markdown` on an option draws inline markdown: `[Terms](https://example.com)` is a link in the block's `option_link_color`, `[word](color:EE6885)` colours a word, and `label` stays the stored answer.

### Added

- **The date wheel follows the locale and the calendar.** Its columns use the device's date order, months show their real length, and the answer is held inside `min_date` and `max_date` as a whole date.

- **A date wheel can ask for a year only.** With `picker_mode` set to `year` the wheel shows only the year column and answers with four digits, for example `1994`.

### Fixed

- **A fold, an unfold or a rotation no longer restarts a surface.** The onboarding, paywall, survey and screen activities handle size and density configuration changes themselves, so the step, answers, selected plan, survey score and scroll position are kept. An in-app message shown over an activity that is recreated by such a change moves to the new activity instead of disappearing, and the next message is no longer blocked behind it.

- **`app_open` is tracked on a cold launch too.** It was tracked only when the app returned from the
  background, so a user who opened the app from cold never sent it: journeys and funnels that start or wait on
  `app_open` missed every cold launch. It is now tracked once per foreground entry: on the launch's first
  `ON_START` and on each return. A second `ON_START` without an `ON_STOP` sends nothing, a process started in the
  background (a push, a broadcast) sends it when its UI first appears, and a `shutdown()` and `configure()` inside
  one foreground sends none. No properties, as before.

- **`input_date`, `input_time` and `input_datetime` store the date wheel's shapes.** A date is `yyyy-MM-dd`; a time or
  date-and-time is the wall time picked in the app's time zone with its offset, and a saved answer is shown in that zone.
  They stored a UTC instant, which west of UTC showed a saved date as the day before; those answers still read.

- **Time and date-and-time answers work in the app's time zone.** Set in the console under Settings → Apps (UTC
  unless changed), it is the zone a `time` or `datetime` wheel shows and takes, and answers are stored with its offset,
  as on iOS: `2026-11-10T15:03:00+01:00`, `15:03:00+01:00` (they were phone-local `yyyy-MM-dd HH:mm` / `HH:mm`, still
  read). A drawn `{{responses…}}` shows them as `2026-11-10 15:03` / `15:03`. A bare date default seeds 00:00, as on iOS.

- **The date wheel's band shows the date it stores.** A month dragged past `min_date` or `max_date` snaps back to the
  clamped date; a wheel with no default and no answer opens on today inside the bounds (it opened on 1 January 2000);
  and a year-only answer is shown again on Back.

- **A date wheel reads back an answer saved as an ISO 8601 instant, or as a year.** Each is shown as the local day it
  was picked on, as iOS reads it.

- **A multi-select option's indicator is round.** With Radio Fill set to Circle or Checkmark, a multi-select draws a filled check-circle when picked and an empty circle when not, as iOS does. It used to draw a square checkbox.

### Added

- **A placement can be a name of your own.** A paywall's `placement` may be any name you set in the console, and the app asks for that exact string. When several live paywalls answer one placement they are now tried in a fixed order, the same on every launch: highest priority, then a paywall with audience conditions before one without, then lowest paywall id. Before, a tie went to whichever came first in memory.

- **A navigation rule can route by the button pressed.** A rule that compares the reserved answer key `__button_pressed` to
  a button's id matches the press that left the step. The press is seen only while the rules are evaluated, and never reaches
  the responses, the hook or a webhook.

- **A Select can move the user on when picked.** `auto_advance: true` on an `input_select` advances the step on the pick
  (a multi-select: on the pick that reaches `field_config.max_selections`), through the step's own `next`, so a screen with
  only a Select has a way forward.

### Fixed

- **An archived, unpublished or deleted flow, paywall or survey stops showing.** After a config fetch, every
  onboarding flow, paywall and survey the server no longer lists is dropped from memory and from the cached
  config, so it does not come back after a restart. An empty list means none are live. A failed fetch
  (offline, an error) removes nothing. Before, a device kept serving every one it had ever fetched.
- **An event upload is one request.** When a reused connection dropped after a batch was sent, OkHttp
  re-sent the batch on its own, outside the queue's retry and backoff, so a batch could reach the server more
  times than the retry policy allows. The upload (in the app and in the background worker) now makes one
  request per attempt and leaves the retry to the queue.
- **An email or custom social-login button with a cleared accent colour uses the block's background colour.**
  A block `accent_color` of `""` drew the button transparent; it now falls through to `bg_color`, then the
  brand accent, as on iOS.
- **A social-login provider with a cleared colour keeps its brand colours.** A provider whose `bg_color` or
  `text_color` was `""` (a cleared picker) drew a transparent button; an empty colour is now unset, as on iOS.
- **A summary stat label keeps a translucent text colour's alpha.** The default label is drawn at 0.7 of the
  block's `text_color`; it replaced that colour's own alpha instead of multiplying it, as iOS does.
- **A translucent container colour keeps its alpha.** A block's `container_bg_color` written as `#RRGGBBAA` was
  drawn with the container opacity in place of its own alpha (`#00000080` drew opaque); the opacity now
  multiplies it, as on iOS.
- **A paywall plan's styled colours are drawn.** A plans section's Style-tab colour for the plan name or the
  price, and a plan's own `price_color` in the default vertical-stack layout, lost to the tile's text colour.
  The order is now iOS's: `price_color`, then `selected_text_color` on the selected plan, then the Style-tab
  colour, then the tile's colour.
- **A block's `block_style` shows over its container fill, as on iOS and in the console preview.** A block
  with both a `block_style` background and a `field_config.container_bg_color` drew the container fill on top,
  so a `block_style` background (and text chosen for it) could disappear.
- **Push tokens name their user.** The token registration now sends the device's anonymous ID and, once
  `identify` ran, the user ID, and registers again after `identify` and `reset`. The server stored every SDK
  token without a user, so a push to a user, a list or a segment reached no SDK device.
- **A gallery title is readable on a dark step.** A `media_gallery` title with no `gallery_title_color` was the
  theme's dark ink everywhere; it now follows what is behind the gallery (white on a dark step), as on iOS.
- **A dropdown Select is readable on a dark step.** Its placeholder, picked label and arrow were the theme's
  dark ink; they now follow the field's own fill, else what is behind it.
- **A row fill with its own alpha matches iOS.** `background_opacity` now multiplies a `#RRGGBBAA` row
  colour's alpha, as on iOS; it replaced it, so `#2563EB80` at 0.5 drew twice as opaque. An opacity outside
  0–1 is clamped.
- **A transparent row or container fill draws nothing.** A row `bg_color` or a block `container_bg_color`
  that reads as transparent (`transparent`, `clear`, `""`, `#FFF`, `white`, `rgba(…)`, `#RRGGBB00`) was
  drawn as an opaque black box in 1.0.57, because the opacity replaced the colour's alpha instead of
  multiplying it. If your app ships 1.0.57, avoid these values on a row or container until you update.

- **A button's refresh spinner matches its label.** The spinner shown while a `refresh_step` button waits
  took `style.color` before `text_color`; it now takes the label's own colour (`text_color`, else
  `style.color`, else the variant's).
- **An unstyled warning banner is readable on a light step.** A `warning_banner` with no `text_color` drew
  its message and subtitle white on its translucent tint, which on a light step measured about 1.1:1. They
  now take their ink from what is behind the banner (dark `#111827` on a light step, white on a dark one), or
  from the banner's own `bg_color` when it sets one.
- **A wrapped in-app message title no longer overlaps itself.** Titles and bodies of every message type
  use a line height of 1.2× their size; a fullscreen title (34sp) used the theme's shorter default, so its
  lines ran into each other.
- **Option labels and summary headlines are readable on any step.** An unstyled option label in a Select
  (options without their own fill) and a `summary_screen` headline with no `text_color` take their ink from
  what is behind them; before, labels were dark on a dark step and the headline white on a light one.
- **Unstyled rich text and lists are readable on dark screens.** A `rich_text` or `list` block with no colour
  takes its ink from what is behind it, as text does; before, it was dark on a dark step or fill.
- **An unregistered custom view no longer shows its debug key to users.** A `custom_view` block whose
  `view_key` the app has not registered drew the literal `[view_key]` whenever it had no `placeholder_text`.
  A non-debuggable build now draws the authored placeholder image or text, and nothing when neither is set
  (the missing key is logged); a debuggable build still shows `[view_key]`.
- **Text inside a zoned section background is readable.** Unstyled text in a `section_background` takes its ink
  from the zones behind it (one zone: its colour; several: any dark zone means light text). Before, it took the
  step's background, so it could be dark on a dark zone.
- **Onboarding image alt text is set in the Console**, in the image block's new "Alt text" field, and read
  out as the image's accessibility label. A blank value is now announced as "Image", as an unset one is
  (it was announced as nothing).
- **Paywall images use their Alt Text as the accessibility label.** The Console's "Alt Text" on an image
  section (`alt`) is now read out by TalkBack; before, it was saved and ignored.
- **In-app messages honour "Allow Dismiss" and "Dismiss Delay".** With dismissal off, a message draws no
  close button or dismiss text and ignores a tap on its backdrop and the **Back** button; with a delay of N seconds, the close
  button appears after N seconds and the message can't be dismissed before then. The CTA and the secondary
  button keep working, and a message with neither stays dismissable, so the user always has a way out.
  Applies to banner, modal, fullscreen and tooltip messages. Before, the SDK always showed the close button.
- **Back closes a dismissable message the way the close button does**, sending `in_app_message_dismissed`
  and `onMessageDismissed`. Before, Back closed the message without either.

### Added

- **A flow-wide background.** `settings.background` on an onboarding flow (the same shape as a step
  background: colour, gradient, image, Lottie or Rive) is drawn by every step that sets no background of
  its own. Before, a step without one drew the theme background, so a theme had to be repeated on every
  step.

### Fixed

- **In-app message buttons open their link.** A CTA's link is read from `cta_action.url` and also from
  `cta_action.value`; the action type `url` reads as `open_url`; a bare-string `cta_action` is read as a type,
  or as a deep link when it is a link. Before, a CTA saved as `{type, value}` (the shape the console writes)
  only dismissed the message. An action type the SDK does not know now reads as `unknown`, as on iOS.

### Added (onboarding)

- **A toggle's settings.** A standalone `toggle` reads `field_config.toggle_on_color`, `toggle_label_color` and
  `toggle_description_color`, and `toggle_action` names the element interaction your app receives, with `"true"` or
  `"false"`, when the user switches it.

### Fixed (onboarding and paywalls)

- **A warning banner draws one background.** `block_style` (the console's Design panel) now styles the banner's card:
  its background, border and corner radius replace the banner's defaults instead of drawing a second layer behind it.

- **Text on a pale gradient is readable.** Text nobody coloured is drawn dark on a gradient whose every
  stop is light; it was white on every gradient, so on a pale one it disappeared.
- **The paywall guarantee badge draws its shield.** Its default symbol (now `checkmark.shield.fill`; the
  old `shield.checkmark.fill` is not an SF Symbol and is read as it) and the shield names authors write
  (`shield.fill`, `lock.shield.fill`) had no Material equivalent, so the badge drew a "?".

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
