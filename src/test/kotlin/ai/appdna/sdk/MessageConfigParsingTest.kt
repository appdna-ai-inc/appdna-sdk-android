package ai.appdna.sdk

import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.messages.MessageConfig
import ai.appdna.sdk.messages.MessageType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that MessageConfig correctly parses from Firestore Map data.
 * Covers all message types, content fields, trigger rules, CTA actions,
 * rich media, date constraints, and edge cases.
 * Prevents field name mismatches between console JSON and SDK parsing.
 */
class MessageConfigParsingTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun parseMessage(
        id: String = "msg_1",
        extras: Map<String, Any> = emptyMap(),
    ): MessageConfig {
        val msgMap = mutableMapOf<String, Any>(
            "name" to "Test Message",
            "message_type" to "modal",
            "content" to mapOf(
                "title" to "Hello",
                "body" to "World",
            ),
            "trigger_rules" to mapOf(
                "event" to "app_open",
            ),
        )
        msgMap.putAll(extras)
        val result = MessageConfigParser.parseMessages(mapOf(id to msgMap))
        return result[id]!!
    }

    private fun parseMessageWithContent(
        contentFields: Map<String, Any>,
        extras: Map<String, Any> = emptyMap(),
    ): MessageConfig {
        val msgMap = mutableMapOf<String, Any>(
            "name" to "Content Test",
            "message_type" to "modal",
            "content" to contentFields,
            "trigger_rules" to mapOf("event" to "test_event"),
        )
        msgMap.putAll(extras)
        val result = MessageConfigParser.parseMessages(mapOf("msg_content" to msgMap))
        return result["msg_content"]!!
    }

    // -----------------------------------------------------------------------
    // 1. Full message config — all top-level fields
    // -----------------------------------------------------------------------

    @Test
    fun parseFullMessageConfig_allFields() {
        val msgMap = mapOf<String, Any>(
            "name" to "Welcome Banner",
            "message_type" to "banner",
            "priority" to 10,
            "start_date" to "2026-01-01T00:00:00Z",
            "end_date" to "2026-12-31T23:59:59Z",
            "content" to mapOf(
                "title" to "Welcome!",
                "body" to "Thanks for joining.",
                "image_url" to "https://cdn.example.com/welcome.png",
                "cta_text" to "Get Started",
                "cta_action" to mapOf("type" to "deep_link", "url" to "app://onboarding"),
            ),
            "trigger_rules" to mapOf(
                "event" to "first_open",
                "frequency" to "once",
                "conditions" to listOf(
                    mapOf("field" to "user_type", "operator" to "eq", "value" to "new"),
                ),
            ),
        )
        val result = MessageConfigParser.parseMessages(mapOf("welcome" to msgMap))
        val msg = result["welcome"]!!

        assertEquals("Welcome Banner", msg.name)
        assertEquals(MessageType.BANNER, msg.message_type)
        assertEquals(10, msg.priority)
        assertEquals("2026-01-01T00:00:00Z", msg.start_date)
        assertEquals("2026-12-31T23:59:59Z", msg.end_date)
        assertEquals("Welcome!", msg.content.title)
        assertEquals("Thanks for joining.", msg.content.body)
        assertEquals("https://cdn.example.com/welcome.png", msg.content.image_url)
        assertEquals("Get Started", msg.content.cta_text)
        assertEquals("deep_link", msg.content.cta_action!!.type)
        assertEquals("app://onboarding", msg.content.cta_action!!.url)
        assertEquals("first_open", msg.trigger_rules.event)
        assertEquals("once", msg.trigger_rules.frequency)
        assertEquals(1, msg.trigger_rules.conditions!!.size)
        assertEquals("user_type", msg.trigger_rules.conditions!![0].field)
        assertEquals("eq", msg.trigger_rules.conditions!![0].operator)
        assertEquals("new", msg.trigger_rules.conditions!![0].value)
    }

    @Test
    fun parseMultipleMessages() {
        val data = mapOf<String, Any>(
            "msg_a" to mapOf(
                "name" to "Message A",
                "message_type" to "banner",
                "content" to mapOf("title" to "A"),
                "trigger_rules" to mapOf("event" to "event_a"),
            ),
            "msg_b" to mapOf(
                "name" to "Message B",
                "message_type" to "modal",
                "content" to mapOf("title" to "B"),
                "trigger_rules" to mapOf("event" to "event_b"),
            ),
            "msg_c" to mapOf(
                "name" to "Message C",
                "message_type" to "fullscreen",
                "content" to mapOf("title" to "C"),
                "trigger_rules" to mapOf("event" to "event_c"),
            ),
        )
        val result = MessageConfigParser.parseMessages(data)
        assertEquals(3, result.size)
        assertEquals("Message A", result["msg_a"]!!.name)
        assertEquals("Message B", result["msg_b"]!!.name)
        assertEquals("Message C", result["msg_c"]!!.name)
    }

    // -----------------------------------------------------------------------
    // 2. Message types — banner, modal, fullscreen, tooltip
    // -----------------------------------------------------------------------

    @Test
    fun parseMessageType_banner() {
        val msg = parseMessage(extras = mapOf("message_type" to "banner"))
        assertEquals(MessageType.BANNER, msg.message_type)
    }

    @Test
    fun parseMessageType_modal() {
        val msg = parseMessage(extras = mapOf("message_type" to "modal"))
        assertEquals(MessageType.MODAL, msg.message_type)
    }

    @Test
    fun parseMessageType_fullscreen() {
        val msg = parseMessage(extras = mapOf("message_type" to "fullscreen"))
        assertEquals(MessageType.FULLSCREEN, msg.message_type)
    }

    @Test
    fun parseMessageType_tooltip() {
        val msg = parseMessage(extras = mapOf("message_type" to "tooltip"))
        assertEquals(MessageType.TOOLTIP, msg.message_type)
    }

    @Test
    fun parseMessageType_unknownDefaultsToModal() {
        val msg = parseMessage(extras = mapOf("message_type" to "popup_widget"))
        assertEquals(MessageType.MODAL, msg.message_type)
    }

    @Test
    fun parseMessageType_missingDefaultsToModal() {
        val msgMap = mapOf<String, Any>(
            "name" to "No Type",
            "content" to mapOf("title" to "Hi"),
            "trigger_rules" to mapOf("event" to "test"),
        )
        val result = MessageConfigParser.parseMessages(mapOf("msg_no_type" to msgMap))
        assertEquals(MessageType.MODAL, result["msg_no_type"]!!.message_type)
    }

    // -----------------------------------------------------------------------
    // 3. MessageContent fields
    // -----------------------------------------------------------------------

    @Test
    fun parseMessageContent_allFields() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Sale!",
            "body" to "50% off everything.",
            "image_url" to "https://cdn.example.com/sale.png",
            "cta_text" to "Shop Now",
            "cta_action" to mapOf("type" to "open_url", "url" to "https://shop.example.com"),
            "dismiss_text" to "Not now",
            "background_color" to "#FF5722",
            "text_color" to "#FFFFFF",
            "button_color" to "#4CAF50",
            "corner_radius" to 16,
            "secondary_cta_text" to "Learn More",
            "banner_position" to "top",
            "auto_dismiss_seconds" to 5,
        ))

        assertEquals("Sale!", msg.content.title)
        assertEquals("50% off everything.", msg.content.body)
        assertEquals("https://cdn.example.com/sale.png", msg.content.image_url)
        assertEquals("Shop Now", msg.content.cta_text)
        assertEquals("open_url", msg.content.cta_action!!.type)
        assertEquals("https://shop.example.com", msg.content.cta_action!!.url)
        assertEquals("Not now", msg.content.dismiss_text)
        assertEquals("#FF5722", msg.content.background_color)
        assertEquals("#FFFFFF", msg.content.text_color)
        assertEquals("#4CAF50", msg.content.button_color)
        assertEquals(16, msg.content.corner_radius)
        assertEquals("Learn More", msg.content.secondary_cta_text)
        assertEquals("top", msg.content.banner_position)
        assertEquals(5, msg.content.auto_dismiss_seconds)
    }

    @Test
    fun parseMessageContent_titleAndBodyOnly() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Simple Title",
            "body" to "Simple Body",
        ))
        assertEquals("Simple Title", msg.content.title)
        assertEquals("Simple Body", msg.content.body)
        assertNull(msg.content.image_url)
        assertNull(msg.content.cta_text)
        assertNull(msg.content.cta_action)
        assertNull(msg.content.dismiss_text)
        assertNull(msg.content.background_color)
        assertNull(msg.content.text_color)
        assertNull(msg.content.button_color)
        assertNull(msg.content.corner_radius)
        assertNull(msg.content.secondary_cta_text)
        assertNull(msg.content.banner_position)
        assertNull(msg.content.auto_dismiss_seconds)
    }

    @Test
    fun parseMessageContent_bannerPosition_bottom() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Bottom Banner",
            "banner_position" to "bottom",
        ))
        assertEquals("bottom", msg.content.banner_position)
    }

    @Test
    fun parseMessageContent_autoDismissSeconds() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Auto Dismiss",
            "auto_dismiss_seconds" to 10,
        ))
        assertEquals(10, msg.content.auto_dismiss_seconds)
    }

    // -----------------------------------------------------------------------
    // 4. Rich media fields
    // -----------------------------------------------------------------------

    @Test
    fun parseRichMedia_lottieUrl() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Animated",
            "lottie_url" to "https://cdn.example.com/success.json",
        ))
        assertEquals("https://cdn.example.com/success.json", msg.content.lottie_url)
    }

    @Test
    fun parseRichMedia_riveUrl() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Rive",
            "rive_url" to "https://cdn.example.com/mascot.riv",
        ))
        assertEquals("https://cdn.example.com/mascot.riv", msg.content.rive_url)
    }

    @Test
    fun parseRichMedia_videoUrl() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Video Message",
            "video_url" to "https://cdn.example.com/promo.mp4",
            "video_thumbnail_url" to "https://cdn.example.com/promo-thumb.jpg",
        ))
        assertEquals("https://cdn.example.com/promo.mp4", msg.content.video_url)
        assertEquals("https://cdn.example.com/promo-thumb.jpg", msg.content.video_thumbnail_url)
    }

    @Test
    fun parseRichMedia_ctaIcons() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Icons",
            "cta_icon" to "arrow_right",
            "secondary_cta_icon" to "info_circle",
        ))
        assertEquals("arrow_right", msg.content.cta_icon)
        assertEquals("info_circle", msg.content.secondary_cta_icon)
    }

    @Test
    fun parseRichMedia_hapticConfig() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Haptic",
            "haptic" to mapOf(
                "enabled" to true,
                "triggers" to mapOf(
                    "on_button_tap" to "light",
                    "on_success" to "heavy",
                ),
            ),
        ))
        assertNotNull(msg.content.haptic)
        assertEquals(true, msg.content.haptic!!.enabled)
        assertEquals("light", msg.content.haptic!!.triggers.on_button_tap)
        assertEquals("heavy", msg.content.haptic!!.triggers.on_success)
    }

    @Test
    fun parseRichMedia_hapticDisabled() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Haptic Disabled",
            "haptic" to mapOf(
                "enabled" to false,
            ),
        ))
        assertNotNull(msg.content.haptic)
        assertEquals(false, msg.content.haptic!!.enabled)
        assertNull(msg.content.haptic!!.triggers.on_button_tap)
        assertNull(msg.content.haptic!!.triggers.on_success)
    }

    @Test
    fun parseRichMedia_particleEffect() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Confetti",
            "particle_effect" to mapOf(
                "type" to "confetti",
                "trigger" to "on_appear",
                "duration_ms" to 3000,
                "intensity" to "heavy",
                "colors" to listOf("#FF5722", "#2196F3", "#4CAF50"),
            ),
        ))
        assertNotNull(msg.content.particle_effect)
        assertEquals("confetti", msg.content.particle_effect!!.type)
        assertEquals("on_appear", msg.content.particle_effect!!.trigger)
        assertEquals(3000, msg.content.particle_effect!!.duration_ms)
        assertEquals("heavy", msg.content.particle_effect!!.intensity)
        assertEquals(3, msg.content.particle_effect!!.colors!!.size)
        assertEquals("#FF5722", msg.content.particle_effect!!.colors!![0])
        assertEquals("#2196F3", msg.content.particle_effect!!.colors!![1])
        assertEquals("#4CAF50", msg.content.particle_effect!!.colors!![2])
    }

    @Test
    fun parseRichMedia_particleEffect_sparkleType() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Sparkle",
            "particle_effect" to mapOf(
                "type" to "sparkle",
                "trigger" to "on_step_complete",
                "intensity" to "light",
            ),
        ))
        assertEquals("sparkle", msg.content.particle_effect!!.type)
        assertEquals("on_step_complete", msg.content.particle_effect!!.trigger)
        assertEquals("light", msg.content.particle_effect!!.intensity)
        // Defaults
        assertEquals(2500, msg.content.particle_effect!!.duration_ms)
    }

    @Test
    fun parseRichMedia_particleEffect_defaults() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Particle Defaults",
            "particle_effect" to mapOf<String, Any>(),
        ))
        assertNotNull(msg.content.particle_effect)
        assertEquals("confetti", msg.content.particle_effect!!.type)
        assertEquals("on_appear", msg.content.particle_effect!!.trigger)
        assertEquals(2500, msg.content.particle_effect!!.duration_ms)
        assertEquals("medium", msg.content.particle_effect!!.intensity)
        assertNull(msg.content.particle_effect!!.colors)
    }

    @Test
    fun parseRichMedia_blurBackdrop() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Blur",
            "blur_backdrop" to mapOf(
                "radius" to 20.0,
                "tint" to "#00000066",
                "saturation" to 1.5,
            ),
        ))
        assertNotNull(msg.content.blur_backdrop)
        assertEquals(20.0f, msg.content.blur_backdrop!!.radius, 0.01f)
        assertEquals("#00000066", msg.content.blur_backdrop!!.tint)
        assertEquals(1.5f, msg.content.blur_backdrop!!.saturation!!, 0.01f)
    }

    @Test
    fun parseRichMedia_blurBackdrop_radiusOnly() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Blur Radius Only",
            "blur_backdrop" to mapOf(
                "radius" to 10,
            ),
        ))
        assertNotNull(msg.content.blur_backdrop)
        assertEquals(10.0f, msg.content.blur_backdrop!!.radius, 0.01f)
        assertNull(msg.content.blur_backdrop!!.tint)
        assertNull(msg.content.blur_backdrop!!.saturation)
    }

    @Test
    fun parseRichMedia_blurBackdrop_defaultRadius() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Blur No Radius",
            "blur_backdrop" to mapOf<String, Any>(),
        ))
        assertNotNull(msg.content.blur_backdrop)
        assertEquals(0.0f, msg.content.blur_backdrop!!.radius, 0.01f)
    }

    @Test
    fun parseRichMedia_allMediaFieldsCombined() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "Rich Message",
            "lottie_url" to "https://cdn.example.com/hero.json",
            "video_url" to "https://cdn.example.com/bg.mp4",
            "rive_url" to "https://cdn.example.com/mascot.riv",
            "video_thumbnail_url" to "https://cdn.example.com/thumb.jpg",
            "cta_icon" to "rocket",
            "secondary_cta_icon" to "close",
            "haptic" to mapOf("enabled" to true, "triggers" to mapOf("on_button_tap" to "medium")),
            "particle_effect" to mapOf("type" to "hearts", "trigger" to "on_flow_complete"),
            "blur_backdrop" to mapOf("radius" to 15, "tint" to "#FFFFFF33"),
        ))
        assertEquals("https://cdn.example.com/hero.json", msg.content.lottie_url)
        assertEquals("https://cdn.example.com/bg.mp4", msg.content.video_url)
        assertEquals("https://cdn.example.com/mascot.riv", msg.content.rive_url)
        assertEquals("https://cdn.example.com/thumb.jpg", msg.content.video_thumbnail_url)
        assertEquals("rocket", msg.content.cta_icon)
        assertEquals("close", msg.content.secondary_cta_icon)
        assertEquals(true, msg.content.haptic!!.enabled)
        assertEquals("medium", msg.content.haptic!!.triggers.on_button_tap)
        assertEquals("hearts", msg.content.particle_effect!!.type)
        assertEquals("on_flow_complete", msg.content.particle_effect!!.trigger)
        assertEquals(15.0f, msg.content.blur_backdrop!!.radius, 0.01f)
        assertEquals("#FFFFFF33", msg.content.blur_backdrop!!.tint)
    }

    // -----------------------------------------------------------------------
    // 5. Trigger rules — event, conditions, frequency
    // -----------------------------------------------------------------------

    @Test
    fun parseTriggerRules_allFields() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "purchase_completed",
                "frequency" to "max_times",
                "max_displays" to 3,
                "delay_seconds" to 5,
                "conditions" to listOf(
                    mapOf("field" to "amount", "operator" to "gte", "value" to 100),
                    mapOf("field" to "currency", "operator" to "eq", "value" to "USD"),
                ),
            ),
        ))
        assertEquals("purchase_completed", msg.trigger_rules.event)
        assertEquals("max_times", msg.trigger_rules.frequency)
        assertEquals(3, msg.trigger_rules.max_displays)
        assertEquals(5, msg.trigger_rules.delay_seconds)
        assertEquals(2, msg.trigger_rules.conditions!!.size)
    }

    @Test
    fun parseTriggerRules_frequencyOnce() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "signup",
                "frequency" to "once",
            ),
        ))
        assertEquals("once", msg.trigger_rules.frequency)
        assertNull(msg.trigger_rules.max_displays)
        assertNull(msg.trigger_rules.delay_seconds)
    }

    @Test
    fun parseTriggerRules_frequencyOncePerSession() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "screen_view",
                "frequency" to "once_per_session",
            ),
        ))
        assertEquals("once_per_session", msg.trigger_rules.frequency)
    }

    @Test
    fun parseTriggerRules_frequencyEveryTime() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "level_complete",
                "frequency" to "every_time",
            ),
        ))
        assertEquals("every_time", msg.trigger_rules.frequency)
    }

    @Test
    fun parseTriggerRules_frequencyDefaultsToOnce() {
        // R87 P1-2 changed parse-site default to `once`
        // at InAppMessageConfig.kt:321 to match iOS MessageManager.swift
        // (`triggerRules.frequency ?? .once`). Console-published messages
        // with no frequency configured were showing on every event-match
        // on Android while iOS showed them exactly once.
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "any_event",
            ),
        ))
        assertEquals("once", msg.trigger_rules.frequency)
    }

    @Test
    fun parseTriggerRules_conditionOperator_eq() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "conditions" to listOf(
                    mapOf("field" to "status", "operator" to "eq", "value" to "active"),
                ),
            ),
        ))
        val cond = msg.trigger_rules.conditions!![0]
        assertEquals("status", cond.field)
        assertEquals("eq", cond.operator)
        assertEquals("active", cond.value)
    }

    @Test
    fun parseTriggerRules_conditionOperator_gte() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "conditions" to listOf(
                    mapOf("field" to "score", "operator" to "gte", "value" to 80),
                ),
            ),
        ))
        val cond = msg.trigger_rules.conditions!![0]
        assertEquals("score", cond.field)
        assertEquals("gte", cond.operator)
        assertEquals(80, cond.value)
    }

    @Test
    fun parseTriggerRules_conditionOperator_lte() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "conditions" to listOf(
                    mapOf("field" to "retries", "operator" to "lte", "value" to 3),
                ),
            ),
        ))
        val cond = msg.trigger_rules.conditions!![0]
        assertEquals("retries", cond.field)
        assertEquals("lte", cond.operator)
        assertEquals(3, cond.value)
    }

    @Test
    fun parseTriggerRules_conditionOperator_contains() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "conditions" to listOf(
                    mapOf("field" to "tags", "operator" to "contains", "value" to "premium"),
                ),
            ),
        ))
        val cond = msg.trigger_rules.conditions!![0]
        assertEquals("tags", cond.field)
        assertEquals("contains", cond.operator)
        assertEquals("premium", cond.value)
    }

    @Test
    fun parseTriggerRules_conditionOperator_gt() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "conditions" to listOf(
                    mapOf("field" to "level", "operator" to "gt", "value" to 5),
                ),
            ),
        ))
        val cond = msg.trigger_rules.conditions!![0]
        assertEquals("gt", cond.operator)
        assertEquals(5, cond.value)
    }

    @Test
    fun parseTriggerRules_conditionOperator_lt() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "conditions" to listOf(
                    mapOf("field" to "health", "operator" to "lt", "value" to 20),
                ),
            ),
        ))
        val cond = msg.trigger_rules.conditions!![0]
        assertEquals("lt", cond.operator)
    }

    @Test
    fun parseTriggerRules_multipleConditions() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "checkout",
                "conditions" to listOf(
                    mapOf("field" to "cart_value", "operator" to "gte", "value" to 50),
                    mapOf("field" to "user_tier", "operator" to "eq", "value" to "gold"),
                    mapOf("field" to "item_count", "operator" to "lte", "value" to 10),
                ),
            ),
        ))
        assertEquals(3, msg.trigger_rules.conditions!!.size)
        assertEquals("cart_value", msg.trigger_rules.conditions!![0].field)
        assertEquals("user_tier", msg.trigger_rules.conditions!![1].field)
        assertEquals("item_count", msg.trigger_rules.conditions!![2].field)
    }

    @Test
    fun parseTriggerRules_noConditions() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "app_open",
            ),
        ))
        assertNull(msg.trigger_rules.conditions)
    }

    @Test
    fun parseTriggerRules_maxDisplays() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "frequency" to "max_times",
                "max_displays" to 5,
            ),
        ))
        assertEquals(5, msg.trigger_rules.max_displays)
    }

    @Test
    fun parseTriggerRules_delaySeconds() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "delay_seconds" to 10,
            ),
        ))
        assertEquals(10, msg.trigger_rules.delay_seconds)
    }

    // -----------------------------------------------------------------------
    // 6. CTA actions — deep_link, open_url, dismiss, custom
    // -----------------------------------------------------------------------

    @Test
    fun parseCTAAction_deepLink() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "CTA",
            "cta_action" to mapOf("type" to "deep_link", "url" to "app://settings/premium"),
        ))
        assertEquals("deep_link", msg.content.cta_action!!.type)
        assertEquals("app://settings/premium", msg.content.cta_action!!.url)
    }

    @Test
    fun parseCTAAction_openUrl() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "CTA",
            "cta_action" to mapOf("type" to "open_url", "url" to "https://example.com/promo"),
        ))
        assertEquals("open_url", msg.content.cta_action!!.type)
        assertEquals("https://example.com/promo", msg.content.cta_action!!.url)
    }

    @Test
    fun parseCTAAction_dismiss() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "CTA",
            "cta_action" to mapOf("type" to "dismiss"),
        ))
        assertEquals("dismiss", msg.content.cta_action!!.type)
        assertNull(msg.content.cta_action!!.url)
    }

    @Test
    fun parseCTAAction_custom() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "CTA",
            "cta_action" to mapOf("type" to "custom", "url" to "custom://action/share"),
        ))
        assertEquals("custom", msg.content.cta_action!!.type)
        assertEquals("custom://action/share", msg.content.cta_action!!.url)
    }

    @Test
    fun parseCTAAction_missingTypeDefaultsToDismiss() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "CTA",
            "cta_action" to mapOf("url" to "https://example.com"),
        ))
        assertEquals("dismiss", msg.content.cta_action!!.type)
        assertEquals("https://example.com", msg.content.cta_action!!.url)
    }

    @Test
    fun parseCTAAction_absent() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "No CTA",
        ))
        assertNull(msg.content.cta_action)
    }

    // -----------------------------------------------------------------------
    // 7. Date constraints — start_date, end_date
    // -----------------------------------------------------------------------

    @Test
    fun parseDateConstraints_bothDates() {
        val msg = parseMessage(extras = mapOf(
            "start_date" to "2026-03-01T00:00:00Z",
            "end_date" to "2026-03-31T23:59:59Z",
        ))
        assertEquals("2026-03-01T00:00:00Z", msg.start_date)
        assertEquals("2026-03-31T23:59:59Z", msg.end_date)
    }

    @Test
    fun parseDateConstraints_startOnly() {
        val msg = parseMessage(extras = mapOf(
            "start_date" to "2026-06-15T12:00:00Z",
        ))
        assertEquals("2026-06-15T12:00:00Z", msg.start_date)
        assertNull(msg.end_date)
    }

    @Test
    fun parseDateConstraints_endOnly() {
        val msg = parseMessage(extras = mapOf(
            "end_date" to "2026-12-25T00:00:00Z",
        ))
        assertNull(msg.start_date)
        assertEquals("2026-12-25T00:00:00Z", msg.end_date)
    }

    @Test
    fun parseDateConstraints_neitherDate() {
        val msg = parseMessage()
        assertNull(msg.start_date)
        assertNull(msg.end_date)
    }

    // -----------------------------------------------------------------------
    // 8. Priority
    // -----------------------------------------------------------------------

    @Test
    fun parsePriority_specified() {
        val msg = parseMessage(extras = mapOf("priority" to 42))
        assertEquals(42, msg.priority)
    }

    @Test
    fun parsePriority_defaultsToZero() {
        val msg = parseMessage()
        assertEquals(0, msg.priority)
    }

    // -----------------------------------------------------------------------
    // 9. Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun parseEdgeCase_minimalMessage() {
        val msgMap = mapOf<String, Any>(
            "name" to "",
            "message_type" to "modal",
            "content" to emptyMap<String, Any>(),
            "trigger_rules" to mapOf("event" to ""),
        )
        val result = MessageConfigParser.parseMessages(mapOf("minimal" to msgMap))
        val msg = result["minimal"]!!
        assertEquals("", msg.name)
        assertEquals(MessageType.MODAL, msg.message_type)
        assertNull(msg.content.title)
        assertNull(msg.content.body)
        assertEquals("", msg.trigger_rules.event)
    }

    @Test
    fun parseEdgeCase_emptyMessagesMap() {
        val result = MessageConfigParser.parseMessages(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseEdgeCase_invalidValueIsSkipped() {
        val data = mapOf<String, Any>(
            "valid" to mapOf(
                "name" to "Valid",
                "message_type" to "banner",
                "content" to mapOf("title" to "OK"),
                "trigger_rules" to mapOf("event" to "test"),
            ),
            "invalid" to "not_a_map",
        )
        val result = MessageConfigParser.parseMessages(data)
        assertEquals(1, result.size)
        assertNotNull(result["valid"])
        assertNull(result["invalid"])
    }

    @Test
    fun parseEdgeCase_missingContentMap() {
        val msgMap = mapOf<String, Any>(
            "name" to "No Content",
            "message_type" to "modal",
            "trigger_rules" to mapOf("event" to "test"),
        )
        val result = MessageConfigParser.parseMessages(mapOf("no_content" to msgMap))
        val msg = result["no_content"]!!
        assertNull(msg.content.title)
        assertNull(msg.content.body)
    }

    @Test
    fun parseEdgeCase_missingTriggerRulesMap() {
        // Default frequency is `once` to match iOS
        // (TriggerRules data class default at InAppMessageConfig.kt:171).
        val msgMap = mapOf<String, Any>(
            "name" to "No Triggers",
            "message_type" to "modal",
            "content" to mapOf("title" to "Hi"),
        )
        val result = MessageConfigParser.parseMessages(mapOf("no_triggers" to msgMap))
        val msg = result["no_triggers"]!!
        assertEquals("", msg.trigger_rules.event)
        assertEquals("once", msg.trigger_rules.frequency)
        assertNull(msg.trigger_rules.conditions)
    }

    @Test
    fun parseEdgeCase_missingNameDefaultsToEmpty() {
        val msgMap = mapOf<String, Any>(
            "message_type" to "banner",
            "content" to mapOf("title" to "Hi"),
            "trigger_rules" to mapOf("event" to "test"),
        )
        val result = MessageConfigParser.parseMessages(mapOf("no_name" to msgMap))
        assertEquals("", result["no_name"]!!.name)
    }

    @Test
    fun parseEdgeCase_numericFieldsFromFirestore() {
        // Firestore may return Long/Double for numeric fields
        val msg = parseMessageWithContent(mapOf(
            "title" to "Numerics",
            "auto_dismiss_seconds" to 7L,
            "corner_radius" to 12.0,
        ))
        assertEquals(7, msg.content.auto_dismiss_seconds)
        assertEquals(12, msg.content.corner_radius)
    }

    @Test
    fun parseEdgeCase_conditionWithNullValue() {
        val msg = parseMessage(extras = mapOf(
            "trigger_rules" to mapOf(
                "event" to "test",
                "conditions" to listOf(
                    mapOf("field" to "optional_field", "operator" to "eq"),
                ),
            ),
        ))
        val cond = msg.trigger_rules.conditions!![0]
        assertEquals("optional_field", cond.field)
        assertEquals("eq", cond.operator)
        assertNull(cond.value)
    }

    @Test
    fun parseEdgeCase_richMediaAllNull() {
        val msg = parseMessageWithContent(mapOf(
            "title" to "No Media",
        ))
        assertNull(msg.content.lottie_url)
        assertNull(msg.content.rive_url)
        assertNull(msg.content.video_url)
        assertNull(msg.content.video_thumbnail_url)
        assertNull(msg.content.cta_icon)
        assertNull(msg.content.secondary_cta_icon)
        assertNull(msg.content.haptic)
        assertNull(msg.content.particle_effect)
        assertNull(msg.content.blur_backdrop)
    }
}
