package ai.appdna.sdk.feedback

// Re-wrap interpolated options as ImmutableList for Compose stability.
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.NetworkImage
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.views.*
// Accessibility string resources for survey chrome.
import ai.appdna.sdk.R
import ai.appdna.sdk.core.FontResolver
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.ConfettiOverlay
import ai.appdna.sdk.core.HapticEngine
import ai.appdna.sdk.core.applyBlurBackdrop
import androidx.compose.ui.platform.LocalView

/**
 * Activity to render survey UI using Jetpack Compose.
 * Supports bottom_sheet, modal, and fullscreen presentations.
 */
class SurveyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge so Compose receives IME inset changes
        // via `imePadding()` + `safeDrawingPadding()` modifiers in SurveyScreen.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        val surveyId = intent.getStringExtra(EXTRA_SURVEY_ID) ?: run {
            finish()
            return
        }

        val config = pendingSurveyConfig ?: run {
            // Process-death recovery.
            // After OS killed the process, the static `pendingSurveyConfig`
            // is gone but the OS recreates this Activity from savedInstanceState.
            // Notify analytics + delegate before silent finish() so the host
            // can recover (e.g. show retry banner).
            if (savedInstanceState != null) {
                try {
                    ai.appdna.sdk.AppDNA.track("survey_dismissed", mapOf(
                        "survey_id" to surveyId,
                        "reason" to "process_death",
                    ))
                    ai.appdna.sdk.AppDNA.surveys.surveyListener?.onSurveyDismissed(surveyId)
                } catch (e: Throwable) {
                    ai.appdna.sdk.Log.warning { "Survey process-death recovery delegate fire failed: ${e.message}" }
                }
            }
            finish()
            return
        }

        val callback = pendingCallback
        val qaCallback = questionAnsweredCallback
        val presentation = config.appearance.presentation

        // Apply presentation style
        when (presentation) {
            "bottom_sheet" -> {
                window.setBackgroundDrawableResource(android.R.color.transparent)
            }
            "modal" -> {
                window.setBackgroundDrawableResource(android.R.color.transparent)
            }
            // "fullscreen" -> default full activity
        }

        val onComplete: (List<SurveyAnswer>) -> Unit = { answers ->
            callback?.invoke(SurveyResult.Completed(answers))
            cleanup()
        }
        val onDismiss: (Int) -> Unit = { answeredCount ->
            callback?.invoke(SurveyResult.Dismissed(answeredCount))
            cleanup()
        }

        // System back: when on a question past the first, decrement
        // instead of dismissing. iOS SurveyRenderer pops the question stack on
        // swipe-back; Android maps that to the system back button. The Compose
        // recomposition reads `currentQuestionIndex` from the companion field
        // which is updated by SurveyScreen's "Back" button click handler too.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val cb = surveyBackHandler
                if (cb == null || !cb.invoke()) {
                    // No question to step back to → dismiss.
                    isEnabled = false
                    @Suppress("DEPRECATION")
                    onBackPressedDispatcher.onBackPressed()
                    // Read live answered count
                    // from provider so dismiss event reports real progress
                    // (parity with iOS SurveyRenderer.swift:253-255 which
                    // reads `answers.count` at the dismiss callsite).
                    val answered = surveyAnsweredCountProvider?.invoke() ?: 0
                    pendingCallback?.invoke(SurveyResult.Dismissed(answered))
                    cleanup()
                }
            }
        })

        setContent {
            // Read system color scheme at the
            // Compose root and propagate to renderers so dark-mode overrides
            // on the survey theme apply when the user has dark mode enabled.
            val isDark = isSystemInDarkTheme()
            // Build a MaterialTheme color scheme seeded with
            // the resolved (light/dark-merged) survey theme colors when the
            // console author supplied them, so child Material widgets
            // (TextField, ProgressIndicator, etc.) pick up the brand colors
            // without each composable having to re-thread them. The renderer
            // still resolves per-token colors via [SurveyAppearance.resolveTheme]
            // for full fidelity.
            val resolved = config.appearance.resolveTheme(isDark)
            val baseScheme = if (isDark) darkColorScheme() else lightColorScheme()
            val seeded = baseScheme.copy(
                primary = resolved?.accentColor?.let { parseColor(it) } ?: baseScheme.primary,
                surface = resolved?.backgroundColor?.let { parseColor(it) } ?: baseScheme.surface,
                background = resolved?.backgroundColor?.let { parseColor(it) } ?: baseScheme.background,
                onSurface = resolved?.textColor?.let { parseColor(it) } ?: baseScheme.onSurface,
                onBackground = resolved?.textColor?.let { parseColor(it) } ?: baseScheme.onBackground,
            )
            MaterialTheme(colorScheme = seeded) {
                when (presentation) {
                    "bottom_sheet" -> BottomSheetSurveyWrapper(config, onComplete, onDismiss, qaCallback, isDark)
                    "modal" -> ModalSurveyWrapper(config, onComplete, onDismiss, qaCallback, isDark)
                    else -> SurveyScreen(config, onComplete, onDismiss, qaCallback, isDark)
                }
            }
        }
    }

    private fun cleanup() {
        pendingSurveyConfig = null
        pendingCallback = null
        finish()
    }

    companion object {
        private const val EXTRA_SURVEY_ID = "survey_id"
        private var pendingSurveyConfig: SurveyConfig? = null
        private var pendingCallback: ((SurveyResult) -> Unit)? = null
        internal var questionAnsweredCallback: ((String, String, Any) -> Unit)? = null

        /**
         * Set by SurveyScreen so the Activity's back-press
         * callback can decrement question index instead of dismissing the
         * Activity. Returns true when the question pointer was decremented;
         * false means we're on the first (or only) question and the host
         * should fall through to dismiss.
         */
        @Volatile internal var surveyBackHandler: (() -> Boolean)? = null

        /**
         * Provider for live answered count so
         * Activity-level system-back dismiss can pass the real count to
         * `SurveyResult.Dismissed(answeredCount)` instead of hardcoding 0.
         * iOS SurveyRenderer.swift:253-255 reads `answers.count` at the
         * dismiss callsite. Without this, Android `survey_dismissed` events
         * always reported `questions_answered: 0` regardless of partial
         * progress — broke abandonment funnels mixing platforms.
         */
        @Volatile internal var surveyAnsweredCountProvider: (() -> Int)? = null

        /**
         * Drop pending captures
         * on SDK shutdown so SurveyConfig + callback closures don't leak.
         * Called from `AppDNA.shutdown()`.
         */
        @JvmStatic
        internal fun clearActiveLaunches() {
            pendingSurveyConfig = null
            pendingCallback = null
            questionAnsweredCallback = null
            surveyBackHandler = null
            surveyAnsweredCountProvider = null
        }

        @JvmStatic
        fun launch(context: Context, surveyId: String, config: SurveyConfig, callback: (SurveyResult) -> Unit) {
            pendingSurveyConfig = config
            pendingCallback = callback
            val intent = Intent(context, SurveyActivity::class.java).apply {
                putExtra(EXTRA_SURVEY_ID, surveyId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun SurveyScreen(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null,
    // Optional override; defaults to the system
    // setting when not supplied. Activity callers pass an explicit value so
    // the resolved theme matches the MaterialTheme color scheme they seeded.
    isDark: Boolean = isSystemInDarkTheme(),
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateMapOf<String, SurveyAnswer>() }

    // Survey intro Lottie 2s gating.
    // iOS SurveyRenderer.swift:157-161 holds an `showIntro` state that flips
    // to false 2s after the first render, replacing the intro animation with
    // the first question. Without this gate, Android stacked the intro Lottie
    // on top of question 1 indefinitely.
    var showIntro by remember {
        mutableStateOf(config.appearance.introLottieUrl != null)
    }
    LaunchedEffect(Unit) {
        if (showIntro) {
            kotlinx.coroutines.delay(2000L)
            showIntro = false
        }
    }

    // Register a back-handler so the Activity's
    // OnBackPressedDispatcher can decrement question index instead of
    // dismissing. Cleared on dispose so the handler doesn't survive
    // SurveyScreen recompositions across surveys.
    DisposableEffect(Unit) {
        SurveyActivity.surveyBackHandler = {
            if (currentIndex > 0) {
                currentIndex--
                true
            } else {
                false
            }
        }
        // Expose live answered count so the
        // Activity-level system-back dismiss callsite can pass the real
        // count to SurveyResult.Dismissed instead of hardcoding 0.
        SurveyActivity.surveyAnsweredCountProvider = { answers.size }
        onDispose {
            SurveyActivity.surveyBackHandler = null
            SurveyActivity.surveyAnsweredCountProvider = null
        }
    }
    var showCompletion by remember { mutableStateOf(false) }
    val currentView = LocalView.current
    // 10 — coroutine scope for the
    // thank-you delay (mirrors iOS DispatchQueue.main.asyncAfter 2.5s
    // before completion). Bound to composition so cancellation on
    // Activity destroy is automatic.
    val completionScope = rememberCoroutineScope()
    val visibleQuestions by remember {
        derivedStateOf {
            config.questions.filter { q ->
                val showIf = q.showIf ?: return@filter true
                // A show_if whose questionId is blank references no real question
                // (half-configured condition, or one left on the first question
                // where nothing precedes it) — treat as no condition and SHOW,
                // else the survey silently renders with no visible questions.
                if (showIf.questionId.isNullOrBlank()) return@filter true
                val prev = answers[showIf.questionId] ?: return@filter false
                showIf.answerIn.any { "${prev.answer}" == "$it" }
            }
        }
    }

    // Resolve the theme for the current scheme.
    // In dark mode, fields set on `theme.dark` override the matching field
    // on `theme.light`; unset dark fields fall back to light (sparse merge).
    // Mirrors iOS `SurveyRenderer.theme` accessor.
    val theme = config.appearance.resolveTheme(isDark)
    // Scheme-aware fallback colors so an unstyled survey still
    // looks correct in dark mode (matches iOS defaults #1a1a1a / #FFFFFF).
    val bgColor = theme?.backgroundColor?.let { parseColor(it) }
        ?: if (isDark) Color(0xFF1A1A1A) else Color.White
    val textColor = theme?.textColor?.let { parseColor(it) }
        ?: if (isDark) Color.White else Color(0xFF1A1A1A)
    val accentColor = theme?.accentColor?.let { parseColor(it) } ?: Color(0xFF6366F1)
    val buttonColor = theme?.buttonColor?.let { parseColor(it) } ?: Color(0xFF6366F1)
    // Resolved button_text_color, used as the contrasting label on the accent-filled
    // selected state of yes/no + NPS (avoids white-on-white when accent is white).
    val buttonTextColor = theme?.buttonTextColor?.let { parseColor(it) } ?: Color.White
    val fontFamily = theme?.fontFamily?.let { FontResolver.resolve(it) }
    val containerRadius = (config.appearance.cornerRadius ?: 0).dp
    // Gradient + button_gradient. Mirrors
    // iOS SurveyRenderer.swift:84-100 StyleEngine.linearGradient. Two
    // brushes consumed by host background and Submit/Next button.
    val backgroundBrush: androidx.compose.ui.graphics.Brush? = remember(theme?.gradient) {
        theme?.gradient?.takeIf { (it.stops?.size ?: 0) >= 2 }?.let { g ->
            val cols = g.stops!!.map { parseColor(it.color) }
            val angle = g.angle ?: 180.0
            val rads = Math.toRadians(angle)
            val dx = kotlin.math.sin(rads).toFloat()
            val dy = -kotlin.math.cos(rads).toFloat()
            androidx.compose.ui.graphics.Brush.linearGradient(
                colors = cols,
                start = androidx.compose.ui.geometry.Offset((0.5f - dx / 2f) * 1000f, (0.5f - dy / 2f) * 1000f),
                end = androidx.compose.ui.geometry.Offset((0.5f + dx / 2f) * 1000f, (0.5f + dy / 2f) * 1000f),
            )
        }
    }
    // theme.button_gradient applied to the
    // Next + Submit primary CTAs matching iOS SurveyRenderer.swift:94-100,
    // 233, 243 `buttonBackground(enabled:)`. Was parsed (SurveyConfig.kt:
    // 663) but never read. Surveys silently dropped author button_gradient
    // while paywalls + onboarding honored it.
    val buttonBrush: androidx.compose.ui.graphics.Brush? = remember(theme?.buttonGradient) {
        theme?.buttonGradient?.takeIf { (it.stops?.size ?: 0) >= 2 }?.let { g ->
            val cols = g.stops!!.map { parseColor(it.color) }
            val angle = g.angle ?: 180.0
            val rads = Math.toRadians(angle)
            val dx = kotlin.math.sin(rads).toFloat()
            val dy = -kotlin.math.cos(rads).toFloat()
            androidx.compose.ui.graphics.Brush.linearGradient(
                colors = cols,
                start = androidx.compose.ui.geometry.Offset((0.5f - dx / 2f) * 1000f, (0.5f - dy / 2f) * 1000f),
                end = androidx.compose.ui.geometry.Offset((0.5f + dx / 2f) * 1000f, (0.5f + dy / 2f) * 1000f),
            )
        }
    }
    // `theme.text_align` for question text.
    val themeTextAlign: TextAlign = when (theme?.textAlign?.lowercase()) {
        "left", "leading", "start" -> TextAlign.Start
        "right", "trailing", "end" -> TextAlign.End
        "center" -> TextAlign.Center
        else -> TextAlign.Center
    }
    // theme.questionFontSize.
    val themeQuestionFontSize: androidx.compose.ui.unit.TextUnit =
        theme?.questionFontSize?.sp ?: 18.sp
    // theme.fontWeight string → FontWeight.
    val themeFontWeight: FontWeight = when (theme?.fontWeight?.lowercase()) {
        "regular", "normal" -> FontWeight.Normal
        "medium" -> FontWeight.Medium
        "semibold" -> FontWeight.SemiBold
        "bold" -> FontWeight.Bold
        "black", "extrabold" -> FontWeight.ExtraBold
        else -> FontWeight.SemiBold
    }

    val canAdvance = if (currentIndex < visibleQuestions.size) {
        val q = visibleQuestions[currentIndex]
        if (q.required) answers.containsKey(q.id) else true
    } else false

    val customColors = MaterialTheme.colorScheme.copy(
        onSurface = textColor,
        onBackground = textColor
    )

    val customTypography = fontFamily?.let { ff ->
        val base = MaterialTheme.typography
        base.copy(
            bodyLarge = base.bodyLarge.copy(fontFamily = ff),
            bodyMedium = base.bodyMedium.copy(fontFamily = ff),
            bodySmall = base.bodySmall.copy(fontFamily = ff),
            titleLarge = base.titleLarge.copy(fontFamily = ff),
            titleMedium = base.titleMedium.copy(fontFamily = ff),
            labelLarge = base.labelLarge.copy(fontFamily = ff),
        )
    } ?: MaterialTheme.typography

    MaterialTheme(colorScheme = customColors, typography = customTypography) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // IME insets so keyboard pushes content up; safe
            // drawing keeps content out of system bars while the survey
            // itself paints the background edge-to-edge.
            .imePadding()
            .safeDrawingPadding()
            .clip(RoundedCornerShape(topStart = containerRadius, topEnd = containerRadius))
            // Prefer gradient brush when
            // theme.gradient is configured; falls back to bgColor.
            .let { mod ->
                val brush = backgroundBrush
                if (brush != null) mod.background(brush) else mod.background(bgColor)
            }
            .applyBlurBackdrop(theme?.blurBackdrop)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Intro Lottie shows
        // ALONE (replaces question UI) for 2s, mirrors iOS showIntro gate.
        // NOTE: R86 Lens C F2 intro-hide fade-out deferred — early-bail
        // via `return@Column` after rendering intro is incompatible with
        // AnimatedVisibility's exit-only-when-not-rendered pattern; safer
        // to leave the instant hide than to refactor the bail path.
        if (showIntro && config.appearance.introLottieUrl != null) {
            Spacer(Modifier.weight(1f))
            LottieBlockView(
                // Intro Lottie height 120dp
                // matches iOS SurveyRenderer.swift:154 `height: 120`. Was
                // 200 — Android rendered 67% taller than iOS for the same
                // intro_lottie_url. Visible regression side-by-side.
                block = LottieBlock(
                    lottie_url = config.appearance.introLottieUrl,
                    autoplay = true,
                    loop = false,
                    height = 120f,
                )
            )
            Spacer(Modifier.weight(1f))
            return@Column
        }

        // Progress
        // Hide the whole question UI (progress + question + nav + "Not now") once the
        // thank-you celebration starts, matching iOS SurveyRenderer's `if showThankYou {..} else {..}`.
        // Android left the final question + Submit + "Not now" visible+interactive during the 2.5s window.
        if (config.appearance.showProgress && visibleQuestions.isNotEmpty() && !showCompletion) {
            // Animate progress fill via
            // `animateFloatAsState` (300ms tween) matching iOS SwiftUI
            // ProgressView's implicit ~0.35s easeInOut animation on value
            // change at SurveyRenderer.swift:186. Was passing raw Float
            // each recomposition — bar snapped instantly between segments
            // while iOS smoothly fills. Onboarding ProgressBar already
            // uses the same pattern at OnboardingActivity.kt:~1167.
            val targetProgress = (currentIndex + 1).toFloat() / visibleQuestions.size
            val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 300),
                label = "survey_progress",
            )
            LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                color = accentColor,
            )
        }

        Spacer(Modifier.weight(1f))

        // Current question
        if (currentIndex < visibleQuestions.size && !showCompletion) {
            val question = visibleQuestions[currentIndex]
            val answer = answers[question.id]

            val onAnswer: (SurveyAnswer) -> Unit = { ans ->
                answers[question.id] = ans
                onQuestionAnswered?.invoke(question.id, question.type, ans.answer)
                // Haptic on option select
                HapticEngine.triggerIfEnabled(
                    currentView,
                    config.appearance.haptic?.triggers?.on_option_select,
                    config.appearance.haptic,
                )
            }

            // Gap #20 — resolve question text style from appearance token
            // Apply theme-level text
            // align + question font size + font weight on top of the
            // appearance-token-resolved style. Mirrors iOS SurveyRenderer.swift
            // theme.text_align / questionFontSize / fontWeight.
            val questionTextStyle = StyleEngine.applyTextStyle(
                MaterialTheme.typography.titleMedium,
                config.appearance.questionTextStyle
            ).copy(
                textAlign = themeTextAlign,
                fontSize = themeQuestionFontSize,
                fontWeight = themeFontWeight,
            )

            // Render question.imageUrl
            // (iOS SurveyRenderer.swift:193-197 MediaImageView maxHeight 140).
            if (!question.imageUrl.isNullOrBlank()) {
                NetworkImage(
                    url = question.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .heightIn(max = 140.dp)
                        .padding(bottom = 12.dp),
                )
            }

            // Interpolate question text, option text, NPS labels
            val tCtx = ai.appdna.sdk.core.TemplateEngine.buildContext(ai.appdna.sdk.core.TemplateSurface.OTHER)
            val te = ai.appdna.sdk.core.TemplateEngine
            val q = question.copy(
                text = te.interpolate(question.text, tCtx),
                npsConfig = question.npsConfig?.copy(
                    lowLabel = question.npsConfig.lowLabel?.let { te.interpolate(it, tCtx) },
                    highLabel = question.npsConfig.highLabel?.let { te.interpolate(it, tCtx) }
                ),
                // Interpolate Likert anchor labels too so
                // template tokens like "{{user.name}}" work consistently
                // with the NPS path.
                likertConfig = question.likertConfig?.copy(
                    lowLabel = question.likertConfig.lowLabel?.let { te.interpolate(it, tCtx) },
                    highLabel = question.likertConfig.highLabel?.let { te.interpolate(it, tCtx) },
                ),
                // Re-wrap as ImmutableList after interpolation
                // map (SurveyQuestion.options is ImmutableList<QuestionOption>?).
                options = question.options?.map { opt ->
                    opt.copy(text = te.interpolate(opt.text, tCtx))
                }?.toImmutableList()
            )

            when (q.type) {
                // Thread the resolved theme colors so the selected score honors accent_color.
                "nps" -> NpsQuestionView(q, answer, onAnswer, questionTextStyle, accentColor, buttonTextColor, textColor)
                "csat" -> CsatQuestionView(q, answer, onAnswer, questionTextStyle)
                // Thread the resolved accent so filled rating icons honor accent_color.
                "rating" -> RatingQuestionView(q, answer, onAnswer, questionTextStyle, accentColor)
                // Gap #21 — pass optionStyle to choice views
                "single_choice" -> SingleChoiceView(q, answer, onAnswer, questionTextStyle, config.appearance.optionStyle)
                "multi_choice" -> MultiChoiceView(q, answer, onAnswer, questionTextStyle, config.appearance.optionStyle)
                "free_text" -> FreeTextView(q, answer, onAnswer, questionTextStyle)
                // Thread the resolved theme colors so the selected button honors accent_color.
                "yes_no" -> YesNoView(q, answer, onAnswer, questionTextStyle, accentColor, buttonTextColor, textColor)
                "emoji_scale" -> EmojiScaleView(q, answer, onAnswer, questionTextStyle)
                // Likert scale (numeric scale with optional
                // anchor labels). Accept both `likert` (canonical) and
                // `scale` (legacy alias) so older console payloads still
                // render on newer SDKs.
                "likert", "scale" -> LikertQuestionView(q, answer, onAnswer, questionTextStyle)
            }
        }

        Spacer(Modifier.weight(1f))

        // Navigation
        if (!showCompletion) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (currentIndex > 0) {
                val backCd = stringResource(R.string.appdna_a11y_survey_back)
                TextButton(
                    onClick = { currentIndex-- },
                    // Explicit a11y label so TalkBack
                    // announces "Previous question" instead of just "Back".
                    modifier = Modifier.semantics { contentDescription = backCd },
                ) {
                    Text("Back", color = accentColor)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            if (currentIndex < visibleQuestions.size - 1) {
                val nextCd = stringResource(R.string.appdna_a11y_survey_next)
                // Apply buttonBrush via Box
                // wrapper when theme.button_gradient is set; fall back to
                // solid containerColor otherwise. Mirrors iOS
                // SurveyRenderer.swift:94-100.
                if (buttonBrush != null && canAdvance) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(buttonBrush)
                            // Fire on_step_advance haptic like iOS SurveyRenderer + Android
                            // onboarding; the survey Next button fired no haptic on either variant.
                            .clickable {
                                HapticEngine.triggerIfEnabled(currentView, config.appearance.haptic?.triggers?.on_step_advance, config.appearance.haptic)
                                currentIndex++
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                            .semantics { contentDescription = nextCd },
                        contentAlignment = Alignment.Center,
                    ) { Text("Next", color = Color.White, fontWeight = FontWeight.SemiBold) }
                } else {
                    Button(
                        onClick = {
                            HapticEngine.triggerIfEnabled(currentView, config.appearance.haptic?.triggers?.on_step_advance, config.appearance.haptic)
                            currentIndex++
                        },
                        enabled = canAdvance,
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                        modifier = Modifier.semantics { contentDescription = nextCd },
                    ) { Text("Next") }
                }
            } else {
                val submitCd = stringResource(R.string.appdna_a11y_survey_submit)
                // buttonBrush gradient on
                // Submit when configured.
                val submitOnClick: () -> Unit = {
                        // Haptic on submit
                        HapticEngine.triggerIfEnabled(
                            currentView,
                            config.appearance.haptic?.triggers?.on_form_submit,
                            config.appearance.haptic,
                        )
                        showCompletion = true
                        if (config.appearance.thankyouParticleEffect != null) {
                            HapticEngine.triggerIfEnabled(
                                currentView,
                                "success",
                                config.appearance.haptic,
                            )
                        }
                        val allAnswers = visibleQuestions.mapNotNull { answers[it.id] }
                        // 10 — defer
                        // onComplete by 2.5s when a thank-you Lottie or
                        // particle/confetti effect is configured. Mirrors
                        // iOS SurveyRenderer.swift:372-386 which waits
                        // 2.5s before dismissing so the celebration
                        // actually renders. Without this, calling
                        // onComplete synchronously triggers cleanup() →
                        // finish() and the Lottie/confetti UI never gets
                        // a frame. No thank-you configured → immediate
                        // dispatch (preserves prior behavior).
                        val hasThankYou = config.appearance.thankyouLottieUrl != null
                            || config.appearance.thankyouParticleEffect != null
                        if (hasThankYou) {
                            completionScope.launch {
                                kotlinx.coroutines.delay(2500)
                                onComplete(allAnswers)
                            }
                        } else {
                            onComplete(allAnswers)
                        }
                }
                if (buttonBrush != null && canAdvance) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(buttonBrush)
                            .clickable { submitOnClick() }
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                            .semantics { contentDescription = submitCd },
                        contentAlignment = Alignment.Center,
                    ) { Text("Submit", color = Color.White, fontWeight = FontWeight.SemiBold) }
                } else {
                Button(
                    onClick = submitOnClick,
                    enabled = canAdvance,
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    modifier = Modifier.semantics { contentDescription = submitCd },
                ) { Text("Submit") }
                }
            }
        }
        } // end if (!showCompletion) — hide the nav Row during the thank-you celebration

        // Dismiss
        if (config.appearance.dismissAllowed && !showCompletion) {
            val dismissCd = stringResource(R.string.appdna_a11y_survey_dismiss)
            TextButton(
                onClick = { onDismiss(answers.size) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    // "Not now" is the visible label;
                    // the a11y label disambiguates as "Dismiss survey".
                    .semantics { contentDescription = dismissCd },
            ) {
                Text("Not now", color = Color.Gray)
            }
        }

        // Thank-you Lottie on completion: Interpolated thank-you text
        // Fade-in matches iOS withAnimation at
        // SurveyRenderer.swift:373 (showThankYou). Was popping in instantly.
        // rememberReduceMotion gating happens via the AnimationModifiers
        // helpers; here we use a small tween directly since AnimatedVisibility
        // uses its own enter/exit specs and survey thank-you is a one-shot.
        AnimatedVisibility(
            visible = showCompletion,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (config.appearance.thankyouLottieUrl != null) {
                Spacer(Modifier.height(8.dp))
                LottieBlockView(
                    // Thank-you Lottie height
                    // 140dp matches iOS SurveyRenderer.swift:171 `height: 140`.
                    // Was 100 — Android rendered 30% shorter than iOS for
                    // the same thankyou_lottie_url.
                    block = LottieBlock(
                        lottie_url = config.appearance.thankyouLottieUrl,
                        autoplay = true,
                        loop = false,
                        height = 140f,
                    )
                )
            }
            val thankCtx = ai.appdna.sdk.core.TemplateEngine.buildContext(ai.appdna.sdk.core.TemplateSurface.OTHER)
            val thankText = ai.appdna.sdk.core.TemplateEngine.interpolate(
                config.appearance.thankYouText ?: "Thank you!", thankCtx
            )
            Text(
                text = thankText,
                // Mirror iOS SurveyRenderer.swift:180 `.font(.title2.bold())`
                // (~22pt). titleMedium is 16sp — visibly smaller on Android.
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                ),
                modifier = Modifier.padding(top = 12.dp),
            )
        } // Column
        } // AnimatedVisibility

        // Confetti on completion
        if (showCompletion && config.appearance.thankyouParticleEffect != null) {
            ConfettiOverlay(
                effect = config.appearance.thankyouParticleEffect,
                trigger = showCompletion,
            )
        }
    }
    } // MaterialTheme
}

/**
 * The ONE colour parser. Delegates to [StyleEngine.parseColor] — see the note on
 * `PaywallActivity.parseHexColor`. This copy read 8-char hex as ARGB (iOS reads RGBA) and fell
 * back to `Color.White` on a string it could not read, which was a THIRD answer to the same
 * question: the paywall copy said black, the core engine says transparent, iOS says transparent.
 */
private fun parseColor(hex: String): Color = StyleEngine.parseColor(hex)

@Composable
fun BottomSheetSurveyWrapper(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null,
    isDark: Boolean = isSystemInDarkTheme(),
) {
    // Resolve dark-merged theme for the wrapper
    // chrome (sheet container background) so it matches the inner survey.
    val theme = config.appearance.resolveTheme(isDark)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Read live answered count from
                // SurveyScreen's provider (set in DisposableEffect) so the
                // backdrop-tap dismiss reports real progress, not 0. Mirrors
                // iOS SurveyRenderer.swift:253-255 reading `answers.count`.
                if (config.appearance.dismissAllowed) {
                    val answered = SurveyActivity.surveyAnsweredCountProvider?.invoke() ?: 0
                    onDismiss(answered)
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(
                    theme?.backgroundColor?.let { parseColor(it) }
                        ?: if (isDark) Color(0xFF1A1A1A) else Color.White
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            // Grabber handle
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Gray.copy(alpha = 0.4f))
            )
            SurveyScreen(
                config = config,
                onComplete = onComplete,
                onDismiss = onDismiss,
                onQuestionAnswered = onQuestionAnswered,
                isDark = isDark,
            )
        }
    }
}

@Composable
fun ModalSurveyWrapper(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null,
    isDark: Boolean = isSystemInDarkTheme(),
) {
    // Resolve dark-merged theme for the modal chrome.
    val theme = config.appearance.resolveTheme(isDark)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Read live answered count from
                // SurveyScreen's provider (set in DisposableEffect) so the
                // backdrop-tap dismiss reports real progress, not 0. Mirrors
                // iOS SurveyRenderer.swift:253-255 reading `answers.count`.
                if (config.appearance.dismissAllowed) {
                    val answered = SurveyActivity.surveyAnsweredCountProvider?.invoke() ?: 0
                    onDismiss(answered)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    theme?.backgroundColor?.let { parseColor(it) }
                        ?: if (isDark) Color(0xFF1A1A1A) else Color.White
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            SurveyScreen(
                config = config,
                onComplete = onComplete,
                onDismiss = onDismiss,
                onQuestionAnswered = onQuestionAnswered,
                isDark = isDark,
            )
        }
    }
}

