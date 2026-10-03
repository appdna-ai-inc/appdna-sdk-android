package ai.appdna.sdk

import ai.appdna.sdk.feedback.SurveyConfig
import ai.appdna.sdk.feedback.SurveyQuestion
import ai.appdna.sdk.feedback.SurveyTriggerRules
import ai.appdna.sdk.feedback.SurveyAppearance
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that SurveyConfig correctly parses from Firestore Map data.
 * Covers all question types, trigger rules, appearance, rich media,
 * follow-up actions, show-if conditions, and edge cases.
 * Prevents field name mismatches between console JSON and SDK parsing.
 */
class SurveyConfigParsingTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal valid trigger rules map — required for every SurveyConfig. */
    private fun minimalTriggerRules(): Map<String, Any> = mapOf(
        "event" to "app_open",
        "frequency" to "once"
    )

    /** Minimal valid appearance map — required for every SurveyConfig. */
    private fun minimalAppearance(): Map<String, Any> = mapOf(
        "presentation" to "bottom_sheet",
        "dismiss_allowed" to true,
        "show_progress" to false
    )

    /** Build a full survey map with the given questions and optional overrides. */
    private fun surveyMap(
        questions: List<Map<String, Any>>,
        triggerRules: Map<String, Any> = minimalTriggerRules(),
        appearance: Map<String, Any> = minimalAppearance(),
        followUpActions: Map<String, Any>? = null,
        extras: Map<String, Any> = emptyMap()
    ): Map<String, Any> {
        val base = mutableMapOf<String, Any>(
            "name" to "Test Survey",
            "survey_type" to "custom",
            "questions" to questions,
            "trigger_rules" to triggerRules,
            "appearance" to appearance
        )
        followUpActions?.let { base["follow_up_actions"] = it }
        base.putAll(extras)
        return base
    }

    /** Parse a full SurveyConfig from a map, asserting non-null. */
    private fun parseSurvey(map: Map<String, Any>): SurveyConfig {
        val result = SurveyConfig.fromMap(map)
        assertNotNull("SurveyConfig.fromMap() returned null", result)
        return result!!
    }

    /** Convenience to build a single-question survey and return the parsed question. */
    private fun parseSingleQuestion(questionMap: Map<String, Any>): SurveyQuestion {
        val survey = parseSurvey(surveyMap(listOf(questionMap)))
        assertEquals(1, survey.questions.size)
        return survey.questions[0]
    }

    // =======================================================================
    // 1. Full survey config parsing
    // =======================================================================

    @Test
    fun parseFullSurveyConfig_allTopLevelFields() {
        val map = surveyMap(
            questions = listOf(
                mapOf("id" to "q1", "type" to "nps", "text" to "How likely?", "required" to true),
                mapOf("id" to "q2", "type" to "free_text", "text" to "Comments", "required" to false)
            ),
            extras = mapOf("name" to "Satisfaction Survey", "survey_type" to "nps")
        )
        val survey = parseSurvey(map)
        assertEquals("Satisfaction Survey", survey.name)
        assertEquals("nps", survey.surveyType)
        assertEquals(2, survey.questions.size)
        assertNotNull(survey.triggerRules)
        assertNotNull(survey.appearance)
    }

    @Test
    fun parseFullSurveyConfig_withFollowUpActions() {
        val followUp = mapOf<String, Any>(
            "on_positive" to mapOf("action" to "redirect", "message" to "Thanks!"),
            "on_negative" to mapOf("action" to "show_message", "message" to "Sorry to hear that"),
            "on_neutral" to mapOf("action" to "dismiss")
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate us")),
            followUpActions = followUp
        ))
        assertNotNull(survey.followUpActions)
        assertEquals("redirect", survey.followUpActions!!.onPositive!!.action)
        assertEquals("Thanks!", survey.followUpActions!!.onPositive!!.message)
        assertEquals("show_message", survey.followUpActions!!.onNegative!!.action)
        assertEquals("Sorry to hear that", survey.followUpActions!!.onNegative!!.message)
        assertEquals("dismiss", survey.followUpActions!!.onNeutral!!.action)
        assertNull(survey.followUpActions!!.onNeutral!!.message)
    }

    @Test
    fun parseFullSurveyConfig_noFollowUpActions() {
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate us"))
        ))
        assertNull(survey.followUpActions)
    }

    // =======================================================================
    // 2. Question types — one test per type
    // =======================================================================

    // ---- NPS ----

    @Test
    fun parseQuestion_nps_withLabels() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_nps",
            "type" to "nps",
            "text" to "How likely are you to recommend us?",
            "required" to true,
            "nps_config" to mapOf(
                "low_label" to "Not at all",
                "high_label" to "Extremely likely"
            )
        ))
        assertEquals("q_nps", q.id)
        assertEquals("nps", q.type)
        assertEquals("How likely are you to recommend us?", q.text)
        assertTrue(q.required)
        assertNotNull(q.npsConfig)
        assertEquals("Not at all", q.npsConfig!!.lowLabel)
        assertEquals("Extremely likely", q.npsConfig!!.highLabel)
    }

    @Test
    fun parseQuestion_nps_noConfig() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_nps2",
            "type" to "nps",
            "text" to "NPS question"
        ))
        assertEquals("nps", q.type)
        assertNull(q.npsConfig)
    }

    // ---- CSAT ----

    @Test
    fun parseQuestion_csat_allFields() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_csat",
            "type" to "csat",
            "text" to "How satisfied are you?",
            "required" to true,
            "csat_config" to mapOf(
                "max_rating" to 7,
                "style" to "smiley"
            )
        ))
        assertEquals("csat", q.type)
        assertNotNull(q.csatConfig)
        assertEquals(7, q.csatConfig!!.maxRating)
        assertEquals("smiley", q.csatConfig!!.style)
    }

    @Test
    fun parseQuestion_csat_defaults() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_csat2",
            "type" to "csat",
            "text" to "Satisfaction?",
            "csat_config" to mapOf<String, Any>()
        ))
        assertNotNull(q.csatConfig)
        assertEquals(5, q.csatConfig!!.maxRating)
        assertEquals("star", q.csatConfig!!.style)
    }

    // ---- Rating ----

    @Test
    fun parseQuestion_rating_allFields() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_rating",
            "type" to "rating",
            "text" to "Rate your experience",
            "required" to true,
            "rating_config" to mapOf(
                "max_rating" to 10,
                "style" to "heart"
            )
        ))
        assertEquals("rating", q.type)
        assertNotNull(q.ratingConfig)
        assertEquals(10, q.ratingConfig!!.maxRating)
        assertEquals("heart", q.ratingConfig!!.style)
    }

    @Test
    fun parseQuestion_rating_defaults() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_rating2",
            "type" to "rating",
            "text" to "Rate us",
            "rating_config" to mapOf<String, Any>()
        ))
        assertNotNull(q.ratingConfig)
        assertEquals(5, q.ratingConfig!!.maxRating)
        assertEquals("star", q.ratingConfig!!.style)
    }

    // ---- Single choice ----

    @Test
    fun parseQuestion_singleChoice_withOptions() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_single",
            "type" to "single_choice",
            "text" to "Pick one",
            "required" to true,
            "options" to listOf(
                mapOf("id" to "opt1", "text" to "Option A", "icon" to "star"),
                mapOf("id" to "opt2", "text" to "Option B"),
                mapOf("id" to "opt3", "text" to "Option C", "icon" to "heart")
            )
        ))
        assertEquals("single_choice", q.type)
        assertNotNull(q.options)
        assertEquals(3, q.options!!.size)
        assertEquals("opt1", q.options!![0].id)
        assertEquals("Option A", q.options!![0].text)
        assertEquals("star", q.options!![0].icon)
        assertEquals("opt2", q.options!![1].id)
        assertEquals("Option B", q.options!![1].text)
        assertNull(q.options!![1].icon)
        assertEquals("opt3", q.options!![2].id)
    }

    // ---- Multi choice ----

    @Test
    fun parseQuestion_multiChoice_withOptions() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_multi",
            "type" to "multi_choice",
            "text" to "Select all that apply",
            "required" to false,
            "options" to listOf(
                mapOf("id" to "m1", "text" to "Feature A"),
                mapOf("id" to "m2", "text" to "Feature B"),
                mapOf("id" to "m3", "text" to "Feature C"),
                mapOf("id" to "m4", "text" to "Feature D")
            )
        ))
        assertEquals("multi_choice", q.type)
        assertFalse(q.required)
        assertNotNull(q.options)
        assertEquals(4, q.options!!.size)
        assertEquals("m1", q.options!![0].id)
        assertEquals("Feature D", q.options!![3].text)
    }

    // ---- Free text ----

    @Test
    fun parseQuestion_freeText_allFields() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_free",
            "type" to "free_text",
            "text" to "Tell us more",
            "required" to false,
            "free_text_config" to mapOf(
                "placeholder" to "Type your feedback here...",
                "max_length" to 1000
            )
        ))
        assertEquals("free_text", q.type)
        assertNotNull(q.freeTextConfig)
        assertEquals("Type your feedback here...", q.freeTextConfig!!.placeholder)
        assertEquals(1000, q.freeTextConfig!!.maxLength)
    }

    @Test
    fun parseQuestion_freeText_defaults() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_free2",
            "type" to "free_text",
            "text" to "Comments",
            "free_text_config" to mapOf<String, Any>()
        ))
        assertNotNull(q.freeTextConfig)
        assertNull(q.freeTextConfig!!.placeholder)
        assertEquals(500, q.freeTextConfig!!.maxLength)
    }

    // ---- Yes/No ----

    @Test
    fun parseQuestion_yesNo() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_yn",
            "type" to "yes_no",
            "text" to "Would you recommend us?",
            "required" to true
        ))
        assertEquals("yes_no", q.type)
        assertEquals("Would you recommend us?", q.text)
        assertTrue(q.required)
    }

    // ---- Emoji ----

    @Test
    fun parseQuestion_emoji_customEmojis() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_emoji",
            "type" to "emoji",
            "text" to "How do you feel?",
            "required" to true,
            "emoji_config" to mapOf(
                "emojis" to listOf("\uD83D\uDE00", "\uD83D\uDE22", "\uD83D\uDE0D", "\uD83E\uDD14")
            )
        ))
        assertEquals("emoji", q.type)
        assertNotNull(q.emojiConfig)
        assertEquals(4, q.emojiConfig!!.emojis.size)
        assertEquals("\uD83D\uDE00", q.emojiConfig!!.emojis[0])
        assertEquals("\uD83E\uDD14", q.emojiConfig!!.emojis[3])
    }

    @Test
    fun parseQuestion_emoji_defaults() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_emoji2",
            "type" to "emoji",
            "text" to "Mood?",
            "emoji_config" to mapOf<String, Any>()
        ))
        assertNotNull(q.emojiConfig)
        assertEquals(5, q.emojiConfig!!.emojis.size)
        // Default emojis: angry, confused, neutral, smile, heart_eyes
        assertEquals("\uD83D\uDE21", q.emojiConfig!!.emojis[0])
        assertEquals("\uD83D\uDE0D", q.emojiConfig!!.emojis[4])
    }

    // =======================================================================
    // 3. Show-if conditions
    // =======================================================================

    @Test
    fun parseShowIfCondition_basic() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_conditional",
            "type" to "free_text",
            "text" to "Why did you choose that?",
            "show_if" to mapOf(
                "question_id" to "q_rating",
                "answer_in" to listOf(1, 2, 3)
            )
        ))
        assertNotNull(q.showIf)
        assertEquals("q_rating", q.showIf!!.questionId)
        assertEquals(3, q.showIf!!.answerIn.size)
        assertEquals(1, q.showIf!!.answerIn[0])
        assertEquals(3, q.showIf!!.answerIn[2])
    }

    @Test
    fun parseShowIfCondition_stringAnswers() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_cond2",
            "type" to "free_text",
            "text" to "Please elaborate",
            "show_if" to mapOf(
                "question_id" to "q_single",
                "answer_in" to listOf("opt1", "opt3")
            )
        ))
        assertNotNull(q.showIf)
        assertEquals("q_single", q.showIf!!.questionId)
        assertEquals(2, q.showIf!!.answerIn.size)
        assertEquals("opt1", q.showIf!!.answerIn[0])
    }

    @Test
    fun parseShowIfCondition_absent() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_no_cond",
            "type" to "nps",
            "text" to "Rate us"
        ))
        assertNull(q.showIf)
    }

    // =======================================================================
    // 4. Trigger rules
    // =======================================================================

    @Test
    fun parseTriggerRules_allFields() {
        val rules = mapOf<String, Any>(
            "event" to "purchase_completed",
            "conditions" to listOf(
                mapOf("field" to "plan", "operator" to "eq", "value" to "pro"),
                mapOf("field" to "amount", "operator" to "gt", "value" to 99)
            ),
            "love_score_range" to mapOf("min" to 20, "max" to 80),
            "frequency" to "max_times",
            "max_displays" to 3,
            "delay_seconds" to 5,
            "min_sessions" to 2
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            triggerRules = rules
        ))
        val tr = survey.triggerRules
        assertEquals("purchase_completed", tr.event)
        assertEquals(2, tr.conditions.size)
        assertEquals("plan", tr.conditions[0].field)
        assertEquals("eq", tr.conditions[0].operator)
        assertEquals("pro", tr.conditions[0].value)
        assertEquals("amount", tr.conditions[1].field)
        assertEquals("gt", tr.conditions[1].operator)
        assertEquals(99, tr.conditions[1].value)
        assertNotNull(tr.loveScoreRange)
        assertEquals(20, tr.loveScoreRange!!.min)
        assertEquals(80, tr.loveScoreRange!!.max)
        assertEquals("max_times", tr.frequency)
        assertEquals(3, tr.maxDisplays)
        assertEquals(5, tr.delaySeconds)
        assertEquals(2, tr.minSessions)
    }

    @Test
    fun parseTriggerRules_frequencyOnce() {
        val rules = mapOf<String, Any>(
            "event" to "app_open",
            "frequency" to "once"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            triggerRules = rules
        ))
        assertEquals("once", survey.triggerRules.frequency)
        assertNull(survey.triggerRules.maxDisplays)
        assertNull(survey.triggerRules.delaySeconds)
        assertNull(survey.triggerRules.minSessions)
    }

    @Test
    fun parseTriggerRules_frequencyOncePerSession() {
        val rules = mapOf<String, Any>(
            "event" to "screen_view",
            "frequency" to "once_per_session"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            triggerRules = rules
        ))
        assertEquals("once_per_session", survey.triggerRules.frequency)
    }

    @Test
    fun parseTriggerRules_frequencyEveryTime() {
        val rules = mapOf<String, Any>(
            "event" to "feedback_tap",
            "frequency" to "every_time"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            triggerRules = rules
        ))
        assertEquals("every_time", survey.triggerRules.frequency)
    }

    @Test
    fun parseTriggerRules_noConditions() {
        val rules = mapOf<String, Any>(
            "event" to "app_open",
            "frequency" to "once"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            triggerRules = rules
        ))
        assertTrue(survey.triggerRules.conditions.isEmpty())
        assertNull(survey.triggerRules.loveScoreRange)
    }

    @Test
    fun parseTriggerRules_defaultFrequency() {
        // frequency not provided — should default to "once"
        val rules = mapOf<String, Any>(
            "event" to "app_open"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            triggerRules = rules
        ))
        assertEquals("once", survey.triggerRules.frequency)
    }

    // =======================================================================
    // 5. Appearance
    // =======================================================================

    @Test
    fun parseAppearance_allThemeFields() {
        val appearance = mapOf<String, Any>(
            "presentation" to "full_screen",
            "dismiss_allowed" to false,
            "show_progress" to true,
            "corner_radius" to 16,
            "theme" to mapOf(
                "background_color" to "#1A1A2E",
                "text_color" to "#FFFFFF",
                "accent_color" to "#6366F1",
                "button_color" to "#818CF8",
                "font_family" to "Inter"
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val a = survey.appearance
        assertEquals("full_screen", a.presentation)
        assertFalse(a.dismissAllowed)
        assertTrue(a.showProgress)
        assertEquals(16, a.cornerRadius)
        assertNotNull(a.theme)
        assertEquals("#1A1A2E", a.theme!!.backgroundColor)
        assertEquals("#FFFFFF", a.theme!!.textColor)
        assertEquals("#6366F1", a.theme!!.accentColor)
        assertEquals("#818CF8", a.theme!!.buttonColor)
        assertEquals("Inter", a.theme!!.fontFamily)
    }

    @Test
    fun parseAppearance_defaults() {
        val appearance = mapOf<String, Any>()
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val a = survey.appearance
        assertEquals("bottom_sheet", a.presentation)
        assertTrue(a.dismissAllowed)
        assertFalse(a.showProgress)
        assertNull(a.theme)
        assertNull(a.cornerRadius)
    }

    @Test
    fun parseAppearance_questionTextStyle() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "question_text_style" to mapOf(
                "font_family" to "Roboto",
                "font_size" to 18,
                "font_weight" to 600,
                "color" to "#333333",
                "alignment" to "center",
                "line_height" to 1.5,
                "letter_spacing" to 0.5,
                "opacity" to 0.9
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val qts = survey.appearance.questionTextStyle
        assertNotNull(qts)
        assertEquals("Roboto", qts!!.font_family)
        assertEquals(18.0, qts.font_size!!, 0.01)
        assertEquals(600, qts.font_weight)
        assertEquals("#333333", qts.color)
        assertEquals("center", qts.alignment)
        assertEquals(1.5, qts.line_height!!, 0.01)
        assertEquals(0.5, qts.letter_spacing!!, 0.01)
        assertEquals(0.9, qts.opacity!!, 0.01)
    }

    @Test
    fun parseAppearance_optionStyle() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "option_style" to mapOf(
                "corner_radius" to 12,
                "opacity" to 0.95,
                "background" to mapOf(
                    "type" to "solid",
                    "color" to "#F0F0FF"
                ),
                "border" to mapOf(
                    "width" to 1,
                    "color" to "#6366F1",
                    "style" to "solid",
                    "radius" to 12
                ),
                "shadow" to mapOf(
                    "x" to 0,
                    "y" to 2,
                    "blur" to 8,
                    "spread" to 0,
                    "color" to "#00000020"
                ),
                "padding" to mapOf(
                    "top" to 12,
                    "right" to 16,
                    "bottom" to 12,
                    "left" to 16
                )
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val os = survey.appearance.optionStyle
        assertNotNull(os)
        assertEquals(12.0, os!!.corner_radius!!, 0.01)
        assertEquals(0.95, os.opacity!!, 0.01)
        // Background
        assertNotNull(os.background)
        assertEquals("solid", os.background!!.type)
        assertEquals("#F0F0FF", os.background!!.color)
        // Border
        assertNotNull(os.border)
        assertEquals(1.0, os.border!!.width!!, 0.01)
        assertEquals("#6366F1", os.border!!.color)
        assertEquals("solid", os.border!!.style)
        assertEquals(12.0, os.border!!.radius!!, 0.01)
        // Shadow
        assertNotNull(os.shadow)
        assertEquals(0.0, os.shadow!!.x!!, 0.01)
        assertEquals(2.0, os.shadow!!.y!!, 0.01)
        assertEquals(8.0, os.shadow!!.blur!!, 0.01)
        assertEquals(0.0, os.shadow!!.spread!!, 0.01)
        assertEquals("#00000020", os.shadow!!.color)
        // Padding
        assertNotNull(os.padding)
        assertEquals(12.0, os.padding!!.top!!, 0.01)
        assertEquals(16.0, os.padding!!.right!!, 0.01)
        assertEquals(12.0, os.padding!!.bottom!!, 0.01)
        assertEquals(16.0, os.padding!!.left!!, 0.01)
    }

    @Test
    fun parseAppearance_thankYouText() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "thank_you_text" to "Thanks for your feedback, {{user_name}}!"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        assertEquals("Thanks for your feedback, {{user_name}}!", survey.appearance.thankYouText)
    }

    // =======================================================================
    // 6. Rich media fields in appearance
    // =======================================================================

    @Test
    fun parseAppearance_introLottieUrl() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "intro_lottie_url" to "https://cdn.example.com/anim/intro.json"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        assertEquals("https://cdn.example.com/anim/intro.json", survey.appearance.introLottieUrl)
    }

    @Test
    fun parseAppearance_thankyouLottieUrl() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "thankyou_lottie_url" to "https://cdn.example.com/anim/thankyou.json"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        assertEquals("https://cdn.example.com/anim/thankyou.json", survey.appearance.thankyouLottieUrl)
    }

    @Test
    fun parseAppearance_particleEffect_allFields() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "thankyou_particle_effect" to mapOf(
                "type" to "confetti",
                "trigger" to "on_flow_complete",
                "duration_ms" to 3000,
                "intensity" to "heavy",
                "colors" to listOf("#FF0000", "#00FF00", "#0000FF", "#FFD700")
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val pe = survey.appearance.thankyouParticleEffect
        assertNotNull(pe)
        assertEquals("confetti", pe!!.type)
        assertEquals("on_flow_complete", pe.trigger)
        assertEquals(3000, pe.duration_ms)
        assertEquals("heavy", pe.intensity)
        assertNotNull(pe.colors)
        assertEquals(4, pe.colors!!.size)
        assertEquals("#FF0000", pe.colors!![0])
        assertEquals("#FFD700", pe.colors!![3])
    }

    @Test
    fun parseAppearance_particleEffect_defaults() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "thankyou_particle_effect" to mapOf<String, Any>()
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val pe = survey.appearance.thankyouParticleEffect
        assertNotNull(pe)
        assertEquals("confetti", pe!!.type)
        assertEquals("on_flow_complete", pe.trigger)
        assertEquals(2500, pe.duration_ms)
        assertEquals("medium", pe.intensity)
    }

    @Test
    fun parseAppearance_blurBackdrop_allFields() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "blur_backdrop" to mapOf(
                "radius" to 20,
                "tint" to "#00000080",
                "saturation" to 1.2
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val blur = survey.appearance.blurBackdrop
        assertNotNull(blur)
        assertEquals(20.0f, blur!!.radius, 0.01f)
        assertEquals("#00000080", blur.tint)
        assertEquals(1.2f, blur.saturation!!, 0.01f)
    }

    @Test
    fun parseAppearance_blurBackdrop_radiusOnly() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "blur_backdrop" to mapOf(
                "radius" to 10
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val blur = survey.appearance.blurBackdrop
        assertNotNull(blur)
        assertEquals(10.0f, blur!!.radius, 0.01f)
        assertNull(blur.tint)
        assertNull(blur.saturation)
    }

    @Test
    fun parseAppearance_haptic_allTriggers() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "haptic" to mapOf(
                "enabled" to true,
                "triggers" to mapOf(
                    "on_option_select" to "light",
                    "on_button_tap" to "medium",
                    "on_form_submit" to "success",
                    "on_success" to "heavy"
                )
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val haptic = survey.appearance.haptic
        assertNotNull(haptic)
        assertTrue(haptic!!.enabled)
        assertEquals("light", haptic.triggers.on_option_select)
        assertEquals("medium", haptic.triggers.on_button_tap)
        assertEquals("success", haptic.triggers.on_form_submit)
        assertEquals("heavy", haptic.triggers.on_success)
    }

    @Test
    fun parseAppearance_haptic_disabled() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "haptic" to mapOf(
                "enabled" to false
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val haptic = survey.appearance.haptic
        assertNotNull(haptic)
        assertFalse(haptic!!.enabled)
    }

    @Test
    fun parseAppearance_allRichMediaCombined() {
        val appearance = mapOf<String, Any>(
            "presentation" to "full_screen",
            "dismiss_allowed" to false,
            "show_progress" to true,
            "intro_lottie_url" to "https://cdn.example.com/intro.json",
            "thankyou_lottie_url" to "https://cdn.example.com/thanks.json",
            "thankyou_particle_effect" to mapOf(
                "type" to "sparkle",
                "trigger" to "on_flow_complete",
                "duration_ms" to 2000,
                "intensity" to "light"
            ),
            "blur_backdrop" to mapOf(
                "radius" to 15,
                "tint" to "#1A1A2E80"
            ),
            "haptic" to mapOf(
                "enabled" to true,
                "triggers" to mapOf(
                    "on_option_select" to "selection",
                    "on_success" to "success"
                )
            ),
            "thank_you_text" to "You rock!"
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val a = survey.appearance
        assertEquals("https://cdn.example.com/intro.json", a.introLottieUrl)
        assertEquals("https://cdn.example.com/thanks.json", a.thankyouLottieUrl)
        assertNotNull(a.thankyouParticleEffect)
        assertEquals("sparkle", a.thankyouParticleEffect!!.type)
        assertNotNull(a.blurBackdrop)
        assertEquals(15.0f, a.blurBackdrop!!.radius, 0.01f)
        assertNotNull(a.haptic)
        assertTrue(a.haptic!!.enabled)
        assertEquals("You rock!", a.thankYouText)
    }

    @Test
    fun parseAppearance_noRichMedia() {
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = minimalAppearance()
        ))
        val a = survey.appearance
        assertNull(a.introLottieUrl)
        assertNull(a.thankyouLottieUrl)
        assertNull(a.thankyouParticleEffect)
        assertNull(a.blurBackdrop)
        assertNull(a.haptic)
        assertNull(a.thankYouText)
    }

    // =======================================================================
    // 7. Follow-up actions
    // =======================================================================

    @Test
    fun parseFollowUpActions_onlyPositive() {
        val followUp = mapOf<String, Any>(
            "on_positive" to mapOf("action" to "redirect", "message" to "Rate us on the App Store!")
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            followUpActions = followUp
        ))
        assertNotNull(survey.followUpActions)
        assertNotNull(survey.followUpActions!!.onPositive)
        assertEquals("redirect", survey.followUpActions!!.onPositive!!.action)
        assertEquals("Rate us on the App Store!", survey.followUpActions!!.onPositive!!.message)
        assertNull(survey.followUpActions!!.onNegative)
        assertNull(survey.followUpActions!!.onNeutral)
    }

    @Test
    fun parseFollowUpActions_allSentiments() {
        val followUp = mapOf<String, Any>(
            "on_positive" to mapOf("action" to "redirect", "message" to "Leave a review!"),
            "on_negative" to mapOf("action" to "show_message", "message" to "We'll do better"),
            "on_neutral" to mapOf("action" to "dismiss")
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            followUpActions = followUp
        ))
        val fa = survey.followUpActions!!
        assertEquals("redirect", fa.onPositive!!.action)
        assertEquals("show_message", fa.onNegative!!.action)
        assertEquals("dismiss", fa.onNeutral!!.action)
        assertNull(fa.onNeutral!!.message)
    }

    @Test
    fun parseFollowUpActions_actionWithNoMessage() {
        val followUp = mapOf<String, Any>(
            "on_positive" to mapOf("action" to "dismiss")
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            followUpActions = followUp
        ))
        assertNotNull(survey.followUpActions!!.onPositive)
        assertEquals("dismiss", survey.followUpActions!!.onPositive!!.action)
        assertNull(survey.followUpActions!!.onPositive!!.message)
    }

    // =======================================================================
    // 8. Edge cases
    // =======================================================================

    @Test
    fun edgeCase_singleQuestion() {
        val survey = parseSurvey(surveyMap(
            questions = listOf(
                mapOf("id" to "only_q", "type" to "nps", "text" to "Rate us", "required" to true)
            )
        ))
        assertEquals(1, survey.questions.size)
        assertEquals("only_q", survey.questions[0].id)
    }

    @Test
    fun edgeCase_emptyQuestionsList() {
        val survey = parseSurvey(surveyMap(questions = emptyList()))
        assertTrue(survey.questions.isEmpty())
    }

    @Test
    fun edgeCase_unknownQuestionType_doesNotCrash() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_future",
            "type" to "slider_v2_future",
            "text" to "Rate on a slider",
            "required" to false
        ))
        assertEquals("slider_v2_future", q.type)
        assertEquals("Rate on a slider", q.text)
        // All optional configs should be null
        assertNull(q.npsConfig)
        assertNull(q.csatConfig)
        assertNull(q.ratingConfig)
        assertNull(q.options)
        assertNull(q.emojiConfig)
        assertNull(q.freeTextConfig)
        assertNull(q.showIf)
    }

    @Test
    fun edgeCase_questionWithNoOptions() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_empty_opts",
            "type" to "single_choice",
            "text" to "Pick one"
            // no options key at all
        ))
        assertNull(q.options)
    }

    @Test
    fun edgeCase_questionWithEmptyOptions() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_empty_opts2",
            "type" to "single_choice",
            "text" to "Pick one",
            "options" to emptyList<Map<String, Any>>()
        ))
        assertNotNull(q.options)
        assertTrue(q.options!!.isEmpty())
    }

    @Test
    fun edgeCase_missingRequiredField_defaultsToFalse() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_no_req",
            "type" to "nps",
            "text" to "Rate"
            // "required" not present
        ))
        assertFalse(q.required)
    }

    @Test
    fun edgeCase_missingName_defaultsToEmpty() {
        val map = surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate"))
        ).toMutableMap()
        map.remove("name")
        val survey = parseSurvey(map)
        assertEquals("", survey.name)
    }

    @Test
    fun edgeCase_missingSurveyType_defaultsToCustom() {
        val map = surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate"))
        ).toMutableMap()
        map.remove("survey_type")
        val survey = parseSurvey(map)
        assertEquals("custom", survey.surveyType)
    }

    @Test
    fun edgeCase_missingTriggerRules_returnsNull() {
        val map = mutableMapOf<String, Any>(
            "name" to "Bad Survey",
            "questions" to listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            "appearance" to minimalAppearance()
            // no trigger_rules
        )
        val result = SurveyConfig.fromMap(map)
        assertNull(result)
    }

    @Test
    fun edgeCase_missingAppearance_returnsNull() {
        val map = mutableMapOf<String, Any>(
            "name" to "Bad Survey",
            "questions" to listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            "trigger_rules" to minimalTriggerRules()
            // no appearance
        )
        val result = SurveyConfig.fromMap(map)
        assertNull(result)
    }

    @Test
    fun edgeCase_missingQuestionId_defaultsToEmpty() {
        val q = parseSingleQuestion(mapOf(
            "type" to "nps",
            "text" to "Rate"
            // no id
        ))
        assertEquals("", q.id)
    }

    @Test
    fun edgeCase_missingQuestionText_defaultsToEmpty() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_no_text",
            "type" to "nps"
            // no text
        ))
        assertEquals("", q.text)
    }

    @Test
    fun edgeCase_missingQuestionType_defaultsToEmpty() {
        val q = parseSingleQuestion(mapOf(
            "id" to "q_no_type",
            "text" to "Rate us"
            // no type
        ))
        assertEquals("", q.type)
    }

    @Test
    fun edgeCase_optionStyleWithGradientBackground() {
        val appearance = mapOf<String, Any>(
            "presentation" to "bottom_sheet",
            "dismiss_allowed" to true,
            "show_progress" to false,
            "option_style" to mapOf(
                "background" to mapOf(
                    "type" to "gradient",
                    "gradient" to mapOf(
                        "type" to "linear",
                        "angle" to 135,
                        "stops" to listOf(
                            mapOf("color" to "#6366F1", "position" to 0.0),
                            mapOf("color" to "#818CF8", "position" to 1.0)
                        )
                    )
                )
            )
        )
        val survey = parseSurvey(surveyMap(
            questions = listOf(mapOf("id" to "q1", "type" to "nps", "text" to "Rate")),
            appearance = appearance
        ))
        val bg = survey.appearance.optionStyle!!.background!!
        assertEquals("gradient", bg.type)
        assertNotNull(bg.gradient)
        assertEquals("linear", bg.gradient!!.type)
        assertEquals(135.0, bg.gradient!!.angle!!, 0.01)
        assertNotNull(bg.gradient!!.stops)
        assertEquals(2, bg.gradient!!.stops!!.size)
        assertEquals("#6366F1", bg.gradient!!.stops!![0].color)
        assertEquals(0.0, bg.gradient!!.stops!![0].position, 0.01)
        assertEquals("#818CF8", bg.gradient!!.stops!![1].color)
        assertEquals(1.0, bg.gradient!!.stops!![1].position, 0.01)
    }

    // =======================================================================
    // 9. Multi-question survey (integration)
    // =======================================================================

    @Test
    fun parseMultiQuestionSurvey_mixedTypes() {
        val survey = parseSurvey(surveyMap(
            questions = listOf(
                mapOf(
                    "id" to "q1",
                    "type" to "nps",
                    "text" to "How likely to recommend?",
                    "required" to true,
                    "nps_config" to mapOf("low_label" to "Not at all", "high_label" to "Definitely")
                ),
                mapOf(
                    "id" to "q2",
                    "type" to "single_choice",
                    "text" to "What did you like?",
                    "required" to true,
                    "options" to listOf(
                        mapOf("id" to "o1", "text" to "Speed"),
                        mapOf("id" to "o2", "text" to "Design"),
                        mapOf("id" to "o3", "text" to "Features")
                    )
                ),
                mapOf(
                    "id" to "q3",
                    "type" to "free_text",
                    "text" to "Any other feedback?",
                    "required" to false,
                    "show_if" to mapOf(
                        "question_id" to "q1",
                        "answer_in" to listOf(0, 1, 2, 3, 4, 5, 6)
                    ),
                    "free_text_config" to mapOf(
                        "placeholder" to "Help us improve...",
                        "max_length" to 2000
                    )
                ),
                mapOf(
                    "id" to "q4",
                    "type" to "emoji",
                    "text" to "Overall mood?",
                    "emoji_config" to mapOf(
                        "emojis" to listOf("\uD83D\uDE00", "\uD83D\uDE10", "\uD83D\uDE22")
                    )
                ),
                mapOf(
                    "id" to "q5",
                    "type" to "rating",
                    "text" to "Rate our support",
                    "rating_config" to mapOf("max_rating" to 5, "style" to "star")
                )
            ),
            triggerRules = mapOf(
                "event" to "session_end",
                "frequency" to "once_per_session",
                "delay_seconds" to 3,
                "conditions" to listOf(
                    mapOf("field" to "session_count", "operator" to "gte", "value" to 3)
                )
            ),
            appearance = mapOf(
                "presentation" to "bottom_sheet",
                "dismiss_allowed" to true,
                "show_progress" to true,
                "corner_radius" to 20,
                "theme" to mapOf(
                    "background_color" to "#FFFFFF",
                    "text_color" to "#111827",
                    "accent_color" to "#6366F1",
                    "button_color" to "#6366F1"
                ),
                "thankyou_lottie_url" to "https://cdn.example.com/thanks.json",
                "thankyou_particle_effect" to mapOf(
                    "type" to "confetti",
                    "duration_ms" to 2000,
                    "intensity" to "light"
                ),
                "haptic" to mapOf(
                    "enabled" to true,
                    "triggers" to mapOf("on_success" to "success")
                )
            ),
            followUpActions = mapOf(
                "on_positive" to mapOf("action" to "redirect", "message" to "Rate us!"),
                "on_negative" to mapOf("action" to "show_message", "message" to "We'll improve.")
            )
        ))

        // Top level
        assertEquals("Test Survey", survey.name)
        assertEquals(5, survey.questions.size)

        // Question types
        assertEquals("nps", survey.questions[0].type)
        assertEquals("single_choice", survey.questions[1].type)
        assertEquals("free_text", survey.questions[2].type)
        assertEquals("emoji", survey.questions[3].type)
        assertEquals("rating", survey.questions[4].type)

        // Conditional question
        assertNotNull(survey.questions[2].showIf)
        assertEquals("q1", survey.questions[2].showIf!!.questionId)
        assertEquals(7, survey.questions[2].showIf!!.answerIn.size)

        // Trigger rules
        assertEquals("session_end", survey.triggerRules.event)
        assertEquals("once_per_session", survey.triggerRules.frequency)
        assertEquals(3, survey.triggerRules.delaySeconds)
        assertEquals(1, survey.triggerRules.conditions.size)

        // Appearance
        assertTrue(survey.appearance.showProgress)
        assertEquals(20, survey.appearance.cornerRadius)
        assertNotNull(survey.appearance.thankyouLottieUrl)
        assertNotNull(survey.appearance.thankyouParticleEffect)
        assertNotNull(survey.appearance.haptic)

        // Follow-up
        assertNotNull(survey.followUpActions)
        assertNotNull(survey.followUpActions!!.onPositive)
        assertNotNull(survey.followUpActions!!.onNegative)
        assertNull(survey.followUpActions!!.onNeutral)
    }
}
