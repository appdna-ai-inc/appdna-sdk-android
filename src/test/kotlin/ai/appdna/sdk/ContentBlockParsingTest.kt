package ai.appdna.sdk

import ai.appdna.sdk.onboarding.OnboardingConfigParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that ContentBlock correctly parses from Firestore Map data.
 * Prevents field name mismatches between editor JSON and SDK parsing.
 */
class ContentBlockParsingTest {

    private fun parseBlocks(vararg blocks: Map<String, Any>): List<ai.appdna.sdk.onboarding.ContentBlock> {
        val stepMap = mapOf<String, Any>(
            "type" to "custom",
            "name" to "test",
            "analytics_name" to "test",
            "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to blocks.toList()
            )
        )
        val step = OnboardingConfigParser.parseStepForTest(stepMap)
        return step?.config?.content_blocks ?: emptyList()
    }

    // MARK: - Unknown block type doesn't crash

    @Test
    fun unknownBlockTypeRendersEmpty() {
        val blocks = parseBlocks(
            mapOf("id" to "b1", "type" to "future_block_type_2030")
        )
        assertEquals(1, blocks.size)
        assertEquals("future_block_type_2030", blocks[0].type)
    }

    // MARK: - Basic block types

    @Test
    fun parseHeadingBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "h1",
                "type" to "heading",
                "text" to "Welcome",
                "level" to 1,
                "style" to mapOf("font_size" to 28, "font_weight" to 700, "color" to "#ffffff")
            )
        )
        assertEquals(1, blocks.size)
        assertEquals("heading", blocks[0].type)
        assertEquals("Welcome", blocks[0].text)
        assertEquals(1, blocks[0].level)
    }

    @Test
    fun parseButtonBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "btn1",
                "type" to "button",
                "text" to "Continue",
                "variant" to "primary",
                "action" to "next",
                "bg_color" to "#6366f1",
                "text_color" to "#ffffff",
                "button_corner_radius" to 12
            )
        )
        assertEquals("button", blocks[0].type)
        assertEquals("Continue", blocks[0].text)
        assertEquals("primary", blocks[0].variant)
        assertEquals("#6366f1", blocks[0].bg_color)
        assertEquals(12.0, blocks[0].button_corner_radius ?: 0.0, 0.01)
    }

    @Test
    fun parseImageBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "img1",
                "type" to "image",
                "image_url" to "https://example.com/photo.jpg",
                "height" to 200,
                "corner_radius" to 16
            )
        )
        assertEquals("image", blocks[0].type)
        assertEquals("https://example.com/photo.jpg", blocks[0].image_url)
        assertEquals(200.0, blocks[0].height ?: 0.0, 0.01)
    }

    // MARK: - new block types

    @Test
    fun parsePageIndicator() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pi1",
                "type" to "page_indicator",
                "dot_count" to 5,
                "active_index" to 2,
                "active_color" to "#6366f1",
                "inactive_color" to "#d1d5db",
                "dot_size" to 8,
                "dot_spacing" to 6,
                "active_dot_width" to 24
            )
        )
        assertEquals("page_indicator", blocks[0].type)
        assertEquals(5, blocks[0].dot_count)
        assertEquals(2, blocks[0].active_index)
        assertEquals("#6366f1", blocks[0].active_color)
        assertEquals(8.0, blocks[0].dot_size ?: 0.0, 0.01)
    }

    @Test
    fun parseSocialLogin() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "sl1",
                "type" to "social_login",
                "providers" to listOf(
                    mapOf("type" to "apple", "enabled" to true),
                    mapOf("type" to "google", "enabled" to true),
                    mapOf("type" to "email", "label" to "Continue with Email", "enabled" to true)
                ),
                "button_style" to "filled",
                "button_height" to 52,
                "show_divider" to true,
                "divider_text" to "or continue with"
            )
        )
        assertEquals("social_login", blocks[0].type)
        assertEquals(3, blocks[0].providers?.size)
        assertEquals("apple", blocks[0].providers?.get(0)?.type)
        assertEquals("Continue with Email", blocks[0].providers?.get(2)?.label)
        assertEquals("filled", blocks[0].button_style)
        assertEquals(true, blocks[0].show_divider)
    }

    @Test
    fun parseCountdownTimer() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "ct1",
                "type" to "countdown_timer",
                "duration_seconds" to 900,
                "show_hours" to true,
                "show_minutes" to true,
                "show_seconds" to true,
                "on_expire_action" to "auto_advance",
                "accent_color" to "#ef4444"
            )
        )
        assertEquals("countdown_timer", blocks[0].type)
        assertEquals(900, blocks[0].duration_seconds)
        assertEquals("auto_advance", blocks[0].on_expire_action)
    }

    @Test
    fun parseRating() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "r1",
                "type" to "rating",
                "max_stars" to 5,
                "star_size" to 32,
                "filled_color" to "#f59e0b",
                "empty_color" to "#d1d5db",
                "allow_half" to true,
                "field_id" to "satisfaction"
            )
        )
        assertEquals("rating", blocks[0].type)
        assertEquals(5, blocks[0].max_stars)
        // Editor writes "filled_color", Android maps to active_rating_color
        assertEquals("#f59e0b", blocks[0].active_rating_color)
        assertEquals(true, blocks[0].allow_half)
    }

    @Test
    fun parseTimeline() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "tl1",
                "type" to "timeline",
                "timeline_items" to listOf(
                    mapOf("id" to "s1", "title" to "Sign up", "subtitle" to "Create account", "status" to "completed"),
                    mapOf("id" to "s2", "title" to "Profile", "status" to "current"),
                    mapOf("id" to "s3", "title" to "Ready!", "status" to "upcoming")
                ),
                "line_color" to "#e5e7eb",
                "completed_color" to "#10b981",
                "show_line" to true
            )
        )
        assertEquals("timeline", blocks[0].type)
        assertEquals(3, blocks[0].timeline_items?.size)
        assertEquals("completed", blocks[0].timeline_items?.get(0)?.status)
        assertEquals("Profile", blocks[0].timeline_items?.get(1)?.title)
        assertEquals(true, blocks[0].show_line)
    }

    @Test
    fun parseProgressBar() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pb1",
                "type" to "progress_bar",
                "progress_variant" to "segmented",
                "total_segments" to 5,
                "filled_segments" to 3,
                "bar_height" to 6,
                "bar_color" to "#6366f1",
                "track_color" to "#e5e7eb",
                "show_label" to true,
                "segment_gap" to 4
            )
        )
        assertEquals("progress_bar", blocks[0].type)
        assertEquals(5, blocks[0].segment_count)
        assertEquals(3, blocks[0].active_segments)
    }

    // MARK: - Block Style Design Tokens

    @Test
    fun parseBlockStyle() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "styled1",
                "type" to "text",
                "text" to "Styled",
                "block_style" to mapOf(
                    "background_color" to "#1a1a2e",
                    "border_color" to "#374151",
                    "border_width" to 1,
                    "border_radius" to 12,
                    "shadow" to mapOf("x" to 0, "y" to 4, "blur" to 12, "spread" to 0, "color" to "rgba(0,0,0,0.15)"),
                    "padding_top" to 16,
                    "padding_right" to 16,
                    "padding_bottom" to 16,
                    "padding_left" to 16,
                    "margin_top" to 8,
                    "opacity" to 0.95
                )
            )
        )
        val bs = blocks[0].block_style
        assertNotNull(bs)
        assertEquals("#1a1a2e", bs?.background_color)
        assertEquals("#374151", bs?.border_color)
        assertEquals(1.0, bs?.border_width ?: 0.0, 0.01)
        assertEquals(12.0, bs?.border_radius ?: 0.0, 0.01)
        assertNotNull(bs?.shadow)
        assertEquals(12.0, bs?.shadow?.blur ?: 0.0, 0.01)
        assertEquals(16.0, bs?.padding_top ?: 0.0, 0.01)
        assertEquals(0.95, bs?.opacity ?: 0.0, 0.01)
    }

    // MARK: - 2D Positioning + Relative Sizing

    @Test
    fun parsePositioningAndSizing() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pos1",
                "type" to "button",
                "text" to "Centered",
                "vertical_align" to "center",
                "horizontal_align" to "center",
                "vertical_offset" to 10,
                "horizontal_offset" to -5
            )
        )
        assertEquals("center", blocks[0].vertical_align)
        assertEquals("center", blocks[0].horizontal_align)
        assertEquals(10.0, blocks[0].vertical_offset ?: 0.0, 0.01)
        assertEquals(-5.0, blocks[0].horizontal_offset ?: 0.0, 0.01)
    }

    // MARK: - Form Input Blocks

    @Test
    fun parseFormInputText() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fi1",
                "type" to "input_text",
                "field_id" to "full_name",
                "field_label" to "Your Name",
                "field_placeholder" to "Enter your name",
                "field_required" to true
            )
        )
        assertEquals("input_text", blocks[0].type)
        assertEquals("full_name", blocks[0].field_id)
        assertEquals("Your Name", blocks[0].field_label)
        assertEquals(true, blocks[0].field_required)
    }

    @Test
    fun parseFormInputSelect() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fi2",
                "type" to "input_select",
                "field_id" to "gender",
                "field_label" to "Gender",
                "field_options" to listOf(
                    mapOf("id" to "m", "label" to "Male"),
                    mapOf("id" to "f", "label" to "Female")
                )
            )
        )
        assertEquals("input_select", blocks[0].type)
        assertEquals(2, blocks[0].field_options?.size)
        assertEquals("m", blocks[0].field_options?.get(0)?.value)
    }

    // MARK: - Minimal block doesn't crash

    @Test
    fun parseMinimalBlock() {
        val blocks = parseBlocks(
            mapOf("id" to "min1", "type" to "spacer")
        )
        assertEquals("spacer", blocks[0].type)
        assertNull(blocks[0].text)
        assertNull(blocks[0].block_style)
    }

    // MARK: - Extra unknown fields don't crash

    @Test
    fun parseBlockWithUnknownFields() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "extra1",
                "type" to "text",
                "text" to "Hello",
                "future_field_2030" to "some value",
                "another_unknown" to 42
            )
        )
        assertEquals("text", blocks[0].type)
        assertEquals("Hello", blocks[0].text)
    }

    // MARK: - Missing block types (Phase F)

    @Test
    fun parseAnimatedLoading() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "al1",
                "type" to "animated_loading",
                "loading_items" to listOf(
                    mapOf("label" to "Analyzing data...", "duration_ms" to 1500, "icon" to "spinner"),
                    mapOf("label" to "Building profile...", "duration_ms" to 2000, "icon" to "check")
                ),
                "progress_color" to "#22c55e",
                "check_color" to "#10b981",
                "total_duration_ms" to 3500,
                "auto_advance" to true
            )
        )
        assertEquals("animated_loading", blocks[0].type)
        assertEquals(2, blocks[0].loading_items?.size)
        assertEquals("Analyzing data...", blocks[0].loading_items?.get(0)?.label)
        assertEquals(1500, blocks[0].loading_items?.get(0)?.duration_ms)
        assertEquals("spinner", blocks[0].loading_items?.get(0)?.icon)
        assertEquals("Building profile...", blocks[0].loading_items?.get(1)?.label)
        assertEquals("#22c55e", blocks[0].progress_color)
        assertEquals("#10b981", blocks[0].check_color)
        assertEquals(3500, blocks[0].total_duration_ms)
        assertEquals(true, blocks[0].auto_advance)
    }

    @Test
    fun parseCircularGauge() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "cg1",
                "type" to "circular_gauge",
                "gauge_value" to 75.0,
                "max_gauge_value" to 100.0,
                "sublabel" to "Health Score",
                "stroke_width" to 12.0,
                "animate" to true
            )
        )
        assertEquals("circular_gauge", blocks[0].type)
        // Phase F decoder; the max_gauge_value decoder gap is closed.
        assertEquals(75.0, blocks[0].gauge_value ?: 0.0, 0.01)
        assertEquals(100.0, blocks[0].max_gauge_value ?: 0.0, 0.01)
        assertEquals("Health Score", blocks[0].sublabel)
        assertEquals(12.0, blocks[0].stroke_width ?: 0.0, 0.01)
        assertEquals(true, blocks[0].animate)
    }

    @Test
    fun parsePulsingAvatar() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pa1",
                "type" to "pulsing_avatar",
                "image_url" to "https://example.com/avatar.jpg",
                "height" to 120,
                "pulse_color" to "#6366f1",
                "pulse_ring_count" to 3,
                "pulse_speed" to 1.5
            )
        )
        assertEquals("pulsing_avatar", blocks[0].type)
        assertEquals("https://example.com/avatar.jpg", blocks[0].image_url)
        assertEquals(120.0, blocks[0].height ?: 0.0, 0.01)
        // Phase F decoder wired in batches;
        // OnboardingConfig.kt:1561-1563 reads all 3 fields.
        assertEquals("#6366f1", blocks[0].pulse_color)
        assertEquals(3, blocks[0].pulse_ring_count)
        assertEquals(1.5, blocks[0].pulse_speed ?: 0.0, 0.01)
    }

    @Test
    fun parseWheelPicker() {
        // `max_value_picker` is the iOS canonical name
        // (ContentBlockTypes.swift); `max_value` is reserved for the
        // CircularGauge block. JSON test fixture renamed accordingly.
        val blocks = parseBlocks(
            mapOf(
                "id" to "wp1",
                "type" to "wheel_picker",
                "field_id" to "age",
                "min_value" to 18.0,
                "max_value_picker" to 99.0,
                "step_value" to 1.0,
                "default_picker_value" to 25.0,
                "unit" to "years",
                "visible_items" to 5
            )
        )
        assertEquals("wheel_picker", blocks[0].type)
        assertEquals("age", blocks[0].field_id)
        // Phase F decoder wired in batches;
        // OnboardingConfig.kt:1528-1533 reads all 5 fields.
        assertEquals(99.0, blocks[0].max_value_picker ?: 0.0, 0.01)
        assertEquals(1.0, blocks[0].step_value ?: 0.0, 0.01)
        assertEquals(25.0, blocks[0].default_picker_value ?: 0.0, 0.01)
        assertEquals("years", blocks[0].unit)
        assertEquals(5, blocks[0].visible_items)
    }

    @Test
    fun parseDateWheelPicker() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "dwp1",
                "type" to "date_wheel_picker",
                "field_id" to "birthdate",
                "columns" to listOf(
                    mapOf("type" to "month", "label" to "Month"),
                    mapOf("type" to "day", "label" to "Day"),
                    mapOf("type" to "year", "label" to "Year")
                ),
                "default_date_value" to "2000-01-15",
                "min_date" to "1920-01-01",
                "max_date" to "2010-12-31"
            )
        )
        assertEquals("date_wheel_picker", blocks[0].type)
        assertEquals("birthdate", blocks[0].field_id)
        // Phase F decoder; the `columns` decoder gap is closed.
        assertEquals(3, blocks[0].columns?.size)
        assertEquals("month", blocks[0].columns?.get(0)?.type)
        assertEquals("Month", blocks[0].columns?.get(0)?.label)
        assertEquals("day", blocks[0].columns?.get(1)?.type)
        assertEquals("Day", blocks[0].columns?.get(1)?.label)
        assertEquals("year", blocks[0].columns?.get(2)?.type)
        assertEquals("Year", blocks[0].columns?.get(2)?.label)
        assertEquals("2000-01-15", blocks[0].default_date_value)
        assertEquals("1920-01-01", blocks[0].min_date)
        assertEquals("2010-12-31", blocks[0].max_date)
    }

    @Test
    fun parseBadgeBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "badge1",
                "type" to "badge",
                "badge_text" to "NEW",
                "badge_bg_color" to "#ef4444",
                "badge_text_color" to "#ffffff",
                "badge_corner_radius" to 8
            )
        )
        assertEquals("badge", blocks[0].type)
        assertEquals("NEW", blocks[0].badge_text)
        assertEquals("#ef4444", blocks[0].badge_bg_color)
        assertEquals("#ffffff", blocks[0].badge_text_color)
        assertEquals(8.0, blocks[0].badge_corner_radius ?: 0.0, 0.01)
    }

    @Test
    fun parseRichText() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "rt1",
                "type" to "rich_text",
                "content" to "**Bold** and *italic* with [link](https://example.com)",
                "link_color" to "#6366f1"
            )
        )
        assertEquals("rich_text", blocks[0].type)
        assertEquals("**Bold** and *italic* with [link](https://example.com)", blocks[0].content)
        assertEquals("#6366f1", blocks[0].link_color)
    }

    @Test
    fun parseToggleBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "tgl1",
                "type" to "toggle",
                "toggle_label" to "Enable notifications",
                "toggle_description" to "Get push updates about your progress",
                "toggle_default" to true
            )
        )
        assertEquals("toggle", blocks[0].type)
        assertEquals("Enable notifications", blocks[0].toggle_label)
        assertEquals("Get push updates about your progress", blocks[0].toggle_description)
        assertEquals(true, blocks[0].toggle_default)
    }

    @Test
    fun parseStackBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "stack1",
                "type" to "stack",
                "children" to listOf(
                    mapOf("id" to "c1", "type" to "text", "text" to "Layer 1"),
                    mapOf("id" to "c2", "type" to "image", "image_url" to "https://example.com/bg.jpg")
                ),
                "z_index" to 5.0
            )
        )
        assertEquals("stack", blocks[0].type)
        assertEquals(2, blocks[0].children?.size)
        assertEquals("text", blocks[0].children?.get(0)?.type)
        assertEquals("Layer 1", blocks[0].children?.get(0)?.text)
        assertEquals("image", blocks[0].children?.get(1)?.type)
    }

    @Test
    fun parseRowBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "row1",
                "type" to "row",
                "children" to listOf(
                    mapOf("id" to "rc1", "type" to "button", "text" to "Left"),
                    mapOf("id" to "rc2", "type" to "button", "text" to "Right")
                ),
                "gap" to 12.0,
                "wrap" to true,
                "justify" to "space_between",
                "align_items" to "center"
            )
        )
        assertEquals("row", blocks[0].type)
        assertEquals(2, blocks[0].children?.size)
        assertEquals("button", blocks[0].children?.get(0)?.type)
        assertEquals("Left", blocks[0].children?.get(0)?.text)
        assertEquals("button", blocks[0].children?.get(1)?.type)
        assertEquals("Right", blocks[0].children?.get(1)?.text)
    }

    @Test
    fun parseCustomView() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "cv1",
                "type" to "custom_view",
                "view_key" to "onboarding_map",
                "custom_config" to mapOf("zoom" to 12, "center_lat" to 37.7749, "center_lng" to -122.4194)
            )
        )
        assertEquals("custom_view", blocks[0].type)
        // Phase F decoder wired in batches;
        // OnboardingConfig.kt:1667-1668 reads view_key + custom_config.
        assertEquals("onboarding_map", blocks[0].view_key)
        assertEquals(3, blocks[0].custom_config?.size)
        assertEquals(12, blocks[0].custom_config?.get("zoom"))
    }

    @Test
    fun parseLottieBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "lot1",
                "type" to "lottie",
                "lottie_url" to "https://example.com/animation.json",
                "lottie_autoplay" to true,
                "lottie_loop" to true,
                "lottie_speed" to 1.5
            )
        )
        assertEquals("lottie", blocks[0].type)
        assertEquals("https://example.com/animation.json", blocks[0].lottie_url)
        assertEquals(true, blocks[0].lottie_autoplay)
        assertEquals(true, blocks[0].lottie_loop)
        assertEquals(1.5f, blocks[0].lottie_speed ?: 0f, 0.01f)
    }

    @Test
    fun parseRiveBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "rive1",
                "type" to "rive",
                "rive_url" to "https://example.com/animation.riv",
                "rive_artboard" to "MainArtboard",
                "rive_state_machine" to "State Machine 1"
            )
        )
        assertEquals("rive", blocks[0].type)
        assertEquals("https://example.com/animation.riv", blocks[0].rive_url)
        assertEquals("MainArtboard", blocks[0].rive_artboard)
        assertEquals("State Machine 1", blocks[0].rive_state_machine)
    }

    // MARK: - Entrance animation parsing

    @Test
    fun parseEntranceAnimation() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "anim1",
                "type" to "text",
                "text" to "Animated text",
                "entrance_animation" to mapOf(
                    "type" to "slide_up",
                    "duration_ms" to 500,
                    "delay_ms" to 200,
                    "easing" to "spring",
                    "spring_damping" to 0.7
                )
            )
        )
        val anim = blocks[0].entrance_animation
        assertNotNull(anim)
        assertEquals("slide_up", anim?.type)
        assertEquals(500, anim?.duration_ms)
        assertEquals(200, anim?.delay_ms)
        assertEquals("spring", anim?.easing)
        assertEquals(0.7, anim?.spring_damping ?: 0.0, 0.01)
    }

    // MARK: - Visibility condition parsing

    @Test
    fun parseVisibilityCondition() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "vis1",
                "type" to "button",
                "text" to "Conditional Button",
                "visibility_condition" to mapOf(
                    "type" to "when_equals",
                    "variable" to "user_role",
                    "value" to "premium",
                    "expression" to "responses.user_role == 'premium'"
                )
            )
        )
        val vc = blocks[0].visibility_condition
        assertNotNull(vc)
        assertEquals("when_equals", vc?.type)
        assertEquals("user_role", vc?.variable)
        assertEquals("premium", vc?.value)
        assertEquals("responses.user_role == 'premium'", vc?.expression)
    }

    // MARK: - Pressed style parsing

    @Test
    fun parsePressedStyle() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "press1",
                "type" to "button",
                "text" to "Pressable",
                "pressed_style" to mapOf(
                    "scale" to 0.95,
                    "opacity" to 0.8,
                    "bg_color" to "#4f46e5",
                    "text_color" to "#e0e7ff"
                )
            )
        )
        val ps = blocks[0].pressed_style
        assertNotNull(ps)
        assertEquals(0.95, ps?.scale ?: 0.0, 0.01)
        assertEquals(0.8, ps?.opacity ?: 0.0, 0.01)
        assertEquals("#4f46e5", ps?.bg_color)
        assertEquals("#e0e7ff", ps?.text_color)
    }

    // MARK: - Bindings parsing

    @Test
    fun parseBindingsMap() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "bind1",
                "type" to "text",
                "text" to "Hello {{name}}",
                "bindings" to mapOf(
                    "text" to "responses.full_name",
                    "visible" to "responses.show_greeting",
                    "bg_color" to "theme.primary"
                )
            )
        )
        val bindings = blocks[0].bindings
        assertNotNull(bindings)
        assertEquals(3, bindings?.size)
        assertEquals("responses.full_name", bindings?.get("text"))
        assertEquals("responses.show_greeting", bindings?.get("visible"))
        assertEquals("theme.primary", bindings?.get("bg_color"))
    }

    // MARK: - z_index parsing

    @Test
    fun parseZIndex() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "zi1",
                "type" to "image",
                "image_url" to "https://example.com/overlay.png",
                "z_index" to 10.0
            )
        )
        assertEquals("image", blocks[0].type)
        // Phase F decoder wired in batches;
        // OnboardingConfig.kt:1665 reads z_index.
        assertEquals(10.0, blocks[0].z_index ?: 0.0, 0.01)
    }

    // MARK: - Element sizing parsing

    @Test
    fun parseElementSizing() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "size1",
                "type" to "button",
                "text" to "Full Width",
                "element_width" to "100%",
                "element_height" to "48px"
            )
        )
        assertEquals("100%", blocks[0].element_width)
        assertEquals("48px", blocks[0].element_height)
    }

    // MARK: - More form input types

    @Test
    fun parseFormInputEmail() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fie1",
                "type" to "input_email",
                "field_id" to "email",
                "field_label" to "Email Address",
                "field_required" to true
            )
        )
        assertEquals("input_email", blocks[0].type)
        assertEquals("email", blocks[0].field_id)
        assertEquals("Email Address", blocks[0].field_label)
        assertEquals(true, blocks[0].field_required)
    }

    @Test
    fun parseFormInputNumber() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fin1",
                "type" to "input_number",
                "field_id" to "age",
                "field_label" to "Your Age"
            )
        )
        assertEquals("input_number", blocks[0].type)
        assertEquals("age", blocks[0].field_id)
        assertEquals("Your Age", blocks[0].field_label)
    }

    @Test
    fun parseFormInputSlider() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fis1",
                "type" to "input_slider",
                "field_id" to "budget",
                "field_label" to "Monthly Budget"
            )
        )
        assertEquals("input_slider", blocks[0].type)
        assertEquals("budget", blocks[0].field_id)
        assertEquals("Monthly Budget", blocks[0].field_label)
    }

    @Test
    fun parseFormInputToggle() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fit1",
                "type" to "input_toggle",
                "field_id" to "notifications_enabled",
                "field_label" to "Enable Notifications"
            )
        )
        assertEquals("input_toggle", blocks[0].type)
        assertEquals("notifications_enabled", blocks[0].field_id)
        assertEquals("Enable Notifications", blocks[0].field_label)
    }

    @Test
    fun parseFormInputMultiSelect() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fims1",
                "type" to "input_multi_select",
                "field_id" to "interests",
                "field_options" to listOf(
                    mapOf("id" to "fitness", "label" to "Fitness"),
                    mapOf("id" to "nutrition", "label" to "Nutrition"),
                    mapOf("id" to "wellness", "label" to "Wellness")
                )
            )
        )
        assertEquals("input_multi_select", blocks[0].type)
        assertEquals("interests", blocks[0].field_id)
        assertEquals(3, blocks[0].field_options?.size)
        assertEquals("fitness", blocks[0].field_options?.get(0)?.value)
        assertEquals("Nutrition", blocks[0].field_options?.get(1)?.label)
    }

    @Test
    fun parseFormInputDate() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "fid1",
                "type" to "input_date",
                "field_id" to "birthdate",
                "field_label" to "Date of Birth"
            )
        )
        assertEquals("input_date", blocks[0].type)
        assertEquals("birthdate", blocks[0].field_id)
        assertEquals("Date of Birth", blocks[0].field_label)
    }

    // MARK: - InputOption fallback behavior

    @Test
    fun parseInputOptionWithOnlyId() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "iof1",
                "type" to "input_select",
                "field_id" to "color",
                "field_options" to listOf(
                    mapOf("id" to "red", "label" to "Red"),
                    mapOf("id" to "blue", "label" to "Blue")
                )
            )
        )
        // When option has only "id" (no "value"), id is used as value
        assertEquals("red", blocks[0].field_options?.get(0)?.value)
        assertEquals("blue", blocks[0].field_options?.get(1)?.value)
    }

    @Test
    fun parseInputOption_valueTakesPriorityOverId() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "iof2",
                "type" to "input_select",
                "field_id" to "plan",
                "field_options" to listOf(
                    mapOf("id" to "starter_plan", "value" to "starter", "label" to "Starter"),
                    mapOf("id" to "pro_plan", "value" to "pro", "label" to "Pro")
                )
            )
        )
        // `value` takes priority over `id` to match iOS
        // ContentBlockTypes.swift:358-360 (`rawValue ?? rawId`). Parser at
        // OnboardingConfig.kt:1373: `fm["value"] ?: fm["id"]`. Was id-first,
        // breaking server-side answer-code joins for authors who set both.
        assertEquals("starter", blocks[0].field_options?.get(0)?.value)
        assertEquals("pro", blocks[0].field_options?.get(1)?.value)
    }

    // MARK: - Field mismatch fallbacks

    @Test
    fun parseRatingWithActiveColorFallback() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "rfb1",
                "type" to "rating",
                "max_stars" to 5,
                "active_color" to "#fbbf24"
            )
        )
        // Parser falls back: active_rating_color ?: filled_color ?: active_color
        assertEquals("#fbbf24", blocks[0].active_rating_color)
    }

    @Test
    fun parseProgressBarWithSegmentCountDirectly() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pbsc1",
                "type" to "progress_bar",
                "segment_count" to 6,
                "active_segments" to 4
            )
        )
        // Parser reads segment_count directly (not via total_segments fallback)
        assertEquals(6, blocks[0].segment_count)
        assertEquals(4, blocks[0].active_segments)
    }

    @Test
    fun parseProgressBarWithFilledSegmentsFallback() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "pbfs1",
                "type" to "progress_bar",
                "total_segments" to 8,
                "filled_segments" to 5
            )
        )
        // Parser falls back: segment_count ?: total_segments, active_segments ?: filled_segments
        assertEquals(8, blocks[0].segment_count)
        assertEquals(5, blocks[0].active_segments)
    }

    // MARK: - Edge cases

    @Test
    fun parseBlockWithOnlyIdAndType() {
        val blocks = parseBlocks(
            mapOf("id" to "bare1", "type" to "text")
        )
        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].type)
        assertEquals("bare1", blocks[0].id)
        assertNull(blocks[0].text)
        assertNull(blocks[0].image_url)
        assertNull(blocks[0].block_style)
        assertNull(blocks[0].entrance_animation)
        assertNull(blocks[0].visibility_condition)
        assertNull(blocks[0].pressed_style)
        assertNull(blocks[0].bindings)
        assertNull(blocks[0].element_width)
        assertNull(blocks[0].element_height)
        assertNull(blocks[0].field_id)
        assertNull(blocks[0].field_label)
        assertNull(blocks[0].field_options)
        assertNull(blocks[0].lottie_url)
        assertNull(blocks[0].rive_url)
    }

    @Test
    fun parseStackWithEmptyChildrenList() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "empty_stack1",
                "type" to "stack",
                "children" to emptyList<Map<String, Any>>()
            )
        )
        assertEquals("stack", blocks[0].type)
        // Phase F children not yet wired; parser ignores the field
        assertNull(blocks[0].children)
    }

    @Test
    fun parseInputSelectWithEmptyFieldOptions() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "empty_opts1",
                "type" to "input_select",
                "field_id" to "empty_field",
                "field_options" to emptyList<Map<String, Any>>()
            )
        )
        assertEquals("input_select", blocks[0].type)
        assertEquals("empty_field", blocks[0].field_id)
        // Empty list parses as empty list (not null)
        assertNotNull(blocks[0].field_options)
        assertEquals(0, blocks[0].field_options?.size)
    }

    // MARK: - Video block (AC-COV-001)

    @Test
    fun parseVideoBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "vid1",
                "type" to "video",
                "video_url" to "https://example.com/intro.mp4",
                "video_height" to 280,
                "video_corner_radius" to 16,
                "video_autoplay" to true,
                "video_loop" to false,
                "video_muted" to true,
                "video_thumbnail_url" to "https://example.com/thumb.jpg"
            )
        )
        assertEquals("video", blocks[0].type)
        assertEquals("https://example.com/intro.mp4", blocks[0].video_url)
        assertEquals(280.0, blocks[0].video_height ?: 0.0, 0.01)
        assertEquals(16.0, blocks[0].video_corner_radius ?: 0.0, 0.01)
        assertEquals(true, blocks[0].video_autoplay)
        assertEquals(false, blocks[0].video_loop)
        assertEquals(true, blocks[0].video_muted)
        assertEquals("https://example.com/thumb.jpg", blocks[0].video_thumbnail_url)
    }

    // MARK: - Divider block (AC-COV-002)

    @Test
    fun parseDividerBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "div1",
                "type" to "divider",
                "divider_color" to "#e5e7eb",
                "divider_thickness" to 2,
                "divider_margin_y" to 12
            )
        )
        assertEquals("divider", blocks[0].type)
        assertEquals("#e5e7eb", blocks[0].divider_color)
        assertEquals(2.0, blocks[0].divider_thickness ?: 0.0, 0.01)
        assertEquals(12.0, blocks[0].divider_margin_y ?: 0.0, 0.01)
    }

    @Test
    fun parseDividerBlockMinimal() {
        val blocks = parseBlocks(
            mapOf("id" to "div2", "type" to "divider")
        )
        assertEquals("divider", blocks[0].type)
        assertNull(blocks[0].divider_color)
        assertNull(blocks[0].divider_thickness)
    }

    // MARK: - List block (AC-COV-003)

    @Test
    fun parseListBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "list1",
                "type" to "list",
                "items" to listOf("First item", "Second item", "Third item"),
                "list_style" to "ordered",
                "active_color" to "#6366f1"
            )
        )
        assertEquals("list", blocks[0].type)
        assertEquals(3, blocks[0].items?.size)
        assertEquals("First item", blocks[0].items?.get(0))
        assertEquals("Second item", blocks[0].items?.get(1))
        assertEquals("Third item", blocks[0].items?.get(2))
        assertEquals("ordered", blocks[0].list_style)
        assertEquals("#6366f1", blocks[0].active_color)
    }

    @Test
    fun parseListBlockUnordered() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "list2",
                "type" to "list",
                "items" to listOf("Apples", "Bananas"),
                "list_style" to "unordered"
            )
        )
        assertEquals("unordered", blocks[0].list_style)
        assertEquals(2, blocks[0].items?.size)
    }

    // MARK: - Icon block (AC-COV-004)

    @Test
    fun parseIconBlock() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "icon1",
                "type" to "icon",
                "icon_ref" to mapOf("library" to "lucide", "name" to "heart"),
                "icon_size" to 48,
                "icon_alignment" to "center"
            )
        )
        assertEquals("icon", blocks[0].type)
        assertNotNull(blocks[0].icon_ref)
        assertEquals(48.0, blocks[0].icon_size ?: 0.0, 0.01)
        assertEquals("center", blocks[0].icon_alignment)
        assertNull(blocks[0].icon_emoji)
    }

    @Test
    fun parseIconBlockWithEmojiString() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "icon2",
                "type" to "icon",
                "icon_ref" to "star",
                "icon_size" to 32,
                "icon_alignment" to "left"
            )
        )
        assertEquals("icon", blocks[0].type)
        assertEquals("star", blocks[0].icon_ref)
        assertEquals(32.0, blocks[0].icon_size ?: 0.0, 0.01)
        assertEquals("left", blocks[0].icon_alignment)
    }

    // MARK: - Per-block text style (AC-COV-005)

    @Test
    fun parsePerBlockTextStyle() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "ts1",
                "type" to "text",
                "text" to "Styled Text",
                "style" to mapOf(
                    "font_size" to 18,
                    "font_weight" to 600,
                    "color" to "#1f2937",
                    "alignment" to "center",
                    "line_height" to 1.5,
                    "letter_spacing" to 0.5,
                    "font_family" to "Inter",
                    "opacity" to 0.9
                )
            )
        )
        val style = blocks[0].style
        assertNotNull(style)
        assertEquals(18.0, style?.font_size ?: 0.0, 0.01)
        assertEquals(600, style?.font_weight)
        assertEquals("#1f2937", style?.color)
        assertEquals("center", style?.alignment)
        assertEquals(1.5, style?.line_height ?: 0.0, 0.01)
        assertEquals(0.5, style?.letter_spacing ?: 0.0, 0.01)
        assertEquals("Inter", style?.font_family)
        assertEquals(0.9, style?.opacity ?: 0.0, 0.01)
    }

    // MARK: - Block style partial — margin_top only (AC-COV-006)

    @Test
    fun parseBlockStylePartialMarginTopOnly() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "bsp1",
                "type" to "text",
                "text" to "Margin Top Only",
                "block_style" to mapOf(
                    "margin_top" to 24
                )
            )
        )
        val bs = blocks[0].block_style
        assertNotNull(bs)
        assertEquals(24.0, bs?.margin_top ?: 0.0, 0.01)
        assertNull(bs?.background_color)
        assertNull(bs?.border_color)
        assertNull(bs?.border_width)
        assertNull(bs?.border_radius)
        assertNull(bs?.shadow)
        assertNull(bs?.padding_top)
        assertNull(bs?.padding_right)
        assertNull(bs?.padding_bottom)
        assertNull(bs?.padding_left)
        assertNull(bs?.margin_bottom)
        assertNull(bs?.opacity)
    }

    // MARK: - Block style shadow only (AC-COV-007)

    @Test
    fun parseBlockStyleShadowOnly() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "bss1",
                "type" to "button",
                "text" to "Shadow Button",
                "block_style" to mapOf(
                    "shadow" to mapOf(
                        "x" to 2,
                        "y" to 6,
                        "blur" to 16,
                        "spread" to 1,
                        "color" to "rgba(0,0,0,0.25)"
                    )
                )
            )
        )
        val bs = blocks[0].block_style
        assertNotNull(bs)
        assertNull(bs?.background_color)
        assertNull(bs?.border_color)
        assertNull(bs?.opacity)
        val shadow = bs?.shadow
        assertNotNull(shadow)
        assertEquals(2.0, shadow?.x ?: 0.0, 0.01)
        assertEquals(6.0, shadow?.y ?: 0.0, 0.01)
        assertEquals(16.0, shadow?.blur ?: 0.0, 0.01)
        assertEquals(1.0, shadow?.spread ?: 0.0, 0.01)
        assertEquals("rgba(0,0,0,0.25)", shadow?.color)
    }

    // MARK: - Entrance animation types (AC-COV-008)

    @Test
    fun parseEntranceAnimationFadeIn() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_fi", "type" to "text", "text" to "Fade",
                "entrance_animation" to mapOf("type" to "fade_in", "duration_ms" to 400))
        )
        assertEquals("fade_in", blocks[0].entrance_animation?.type)
        assertEquals(400, blocks[0].entrance_animation?.duration_ms)
    }

    @Test
    fun parseEntranceAnimationSlideDown() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_sd", "type" to "text", "text" to "Slide",
                "entrance_animation" to mapOf("type" to "slide_down"))
        )
        assertEquals("slide_down", blocks[0].entrance_animation?.type)
    }

    @Test
    fun parseEntranceAnimationSlideLeft() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_sl", "type" to "text", "text" to "Slide",
                "entrance_animation" to mapOf("type" to "slide_left"))
        )
        assertEquals("slide_left", blocks[0].entrance_animation?.type)
    }

    @Test
    fun parseEntranceAnimationSlideRight() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_sr", "type" to "text", "text" to "Slide",
                "entrance_animation" to mapOf("type" to "slide_right"))
        )
        assertEquals("slide_right", blocks[0].entrance_animation?.type)
    }

    @Test
    fun parseEntranceAnimationScaleUp() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_su", "type" to "text", "text" to "Scale",
                "entrance_animation" to mapOf("type" to "scale_up"))
        )
        assertEquals("scale_up", blocks[0].entrance_animation?.type)
    }

    @Test
    fun parseEntranceAnimationScaleDown() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_sdn", "type" to "text", "text" to "Scale",
                "entrance_animation" to mapOf("type" to "scale_down"))
        )
        assertEquals("scale_down", blocks[0].entrance_animation?.type)
    }

    @Test
    fun parseEntranceAnimationBounce() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_b", "type" to "text", "text" to "Bounce",
                "entrance_animation" to mapOf("type" to "bounce"))
        )
        assertEquals("bounce", blocks[0].entrance_animation?.type)
    }

    @Test
    fun parseEntranceAnimationFlip() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_f", "type" to "text", "text" to "Flip",
                "entrance_animation" to mapOf("type" to "flip"))
        )
        assertEquals("flip", blocks[0].entrance_animation?.type)
    }

    @Test
    fun parseEntranceAnimationNone() {
        val blocks = parseBlocks(
            mapOf("id" to "ea_n", "type" to "text", "text" to "None",
                "entrance_animation" to mapOf("type" to "none"))
        )
        assertEquals("none", blocks[0].entrance_animation?.type)
    }

    // MARK: - Visibility condition types (AC-COV-009)

    @Test
    fun parseVisibilityConditionAlways() {
        val blocks = parseBlocks(
            mapOf("id" to "vc_a", "type" to "text", "text" to "Always",
                "visibility_condition" to mapOf("type" to "always"))
        )
        assertEquals("always", blocks[0].visibility_condition?.type)
        assertNull(blocks[0].visibility_condition?.variable)
        assertNull(blocks[0].visibility_condition?.value)
    }

    @Test
    fun parseVisibilityConditionWhenNotEquals() {
        val blocks = parseBlocks(
            mapOf("id" to "vc_ne", "type" to "text", "text" to "NotEq",
                "visibility_condition" to mapOf(
                    "type" to "when_not_equals",
                    "variable" to "plan",
                    "value" to "free"
                ))
        )
        assertEquals("when_not_equals", blocks[0].visibility_condition?.type)
        assertEquals("plan", blocks[0].visibility_condition?.variable)
        assertEquals("free", blocks[0].visibility_condition?.value)
    }

    @Test
    fun parseVisibilityConditionWhenNotEmpty() {
        val blocks = parseBlocks(
            mapOf("id" to "vc_nne", "type" to "text", "text" to "NotEmpty",
                "visibility_condition" to mapOf(
                    "type" to "when_not_empty",
                    "variable" to "email"
                ))
        )
        assertEquals("when_not_empty", blocks[0].visibility_condition?.type)
        assertEquals("email", blocks[0].visibility_condition?.variable)
    }

    @Test
    fun parseVisibilityConditionWhenEmpty() {
        val blocks = parseBlocks(
            mapOf("id" to "vc_e", "type" to "text", "text" to "Empty",
                "visibility_condition" to mapOf(
                    "type" to "when_empty",
                    "variable" to "phone"
                ))
        )
        assertEquals("when_empty", blocks[0].visibility_condition?.type)
        assertEquals("phone", blocks[0].visibility_condition?.variable)
    }

    @Test
    fun parseVisibilityConditionWhenGt() {
        val blocks = parseBlocks(
            mapOf("id" to "vc_gt", "type" to "text", "text" to "Gt",
                "visibility_condition" to mapOf(
                    "type" to "when_gt",
                    "variable" to "age",
                    "value" to 18
                ))
        )
        assertEquals("when_gt", blocks[0].visibility_condition?.type)
        assertEquals("age", blocks[0].visibility_condition?.variable)
        assertEquals(18, blocks[0].visibility_condition?.value)
    }

    @Test
    fun parseVisibilityConditionWhenLt() {
        val blocks = parseBlocks(
            mapOf("id" to "vc_lt", "type" to "text", "text" to "Lt",
                "visibility_condition" to mapOf(
                    "type" to "when_lt",
                    "variable" to "score",
                    "value" to 50
                ))
        )
        assertEquals("when_lt", blocks[0].visibility_condition?.type)
        assertEquals("score", blocks[0].visibility_condition?.variable)
        assertEquals(50, blocks[0].visibility_condition?.value)
    }

    // MARK: - Color parsing edge cases (AC-COV-010)

    @Test
    fun parseBlockWithShortHexColor() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "color1",
                "type" to "button",
                "text" to "Short Hex",
                "bg_color" to "#fff",
                "text_color" to "#000"
            )
        )
        assertEquals("#fff", blocks[0].bg_color)
        assertEquals("#000", blocks[0].text_color)
    }

    @Test
    fun parseBlockWithFullHexColor() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "color2",
                "type" to "button",
                "text" to "Full Hex",
                "bg_color" to "#ffffff",
                "text_color" to "#1f2937"
            )
        )
        assertEquals("#ffffff", blocks[0].bg_color)
        assertEquals("#1f2937", blocks[0].text_color)
    }

    @Test
    fun parseBlockWithAlphaHexColor() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "color3",
                "type" to "button",
                "text" to "Alpha Hex",
                "bg_color" to "#ff6366f1",
                "text_color" to "#80ffffff"
            )
        )
        assertEquals("#ff6366f1", blocks[0].bg_color)
        assertEquals("#80ffffff", blocks[0].text_color)
    }

    @Test
    fun parseBlockWithRgbaColor() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "color4",
                "type" to "text",
                "text" to "RGBA",
                "block_style" to mapOf(
                    "shadow" to mapOf(
                        "x" to 0,
                        "y" to 2,
                        "blur" to 8,
                        "color" to "rgba(255,0,0,0.5)"
                    )
                )
            )
        )
        assertEquals("rgba(255,0,0,0.5)", blocks[0].block_style?.shadow?.color)
    }

    // MARK: - Numeric edge cases (AC-COV-011)

    @Test
    fun parseBlockWithZeroValues() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "zero1",
                "type" to "page_indicator",
                "dot_count" to 0,
                "active_index" to 0,
                "dot_size" to 0
            )
        )
        assertEquals(0, blocks[0].dot_count)
        assertEquals(0, blocks[0].active_index)
        assertEquals(0.0, blocks[0].dot_size ?: -1.0, 0.01)
    }

    @Test
    fun parseBlockWithZeroDuration() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "zero2",
                "type" to "countdown_timer",
                "duration_seconds" to 0,
                "on_expire_action" to "none"
            )
        )
        assertEquals(0, blocks[0].duration_seconds)
    }

    @Test
    fun parseBlockWithNegativeOffset() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "neg1",
                "type" to "image",
                "image_url" to "https://example.com/img.png",
                "vertical_offset" to -100,
                "horizontal_offset" to -50
            )
        )
        assertEquals(-100.0, blocks[0].vertical_offset ?: 0.0, 0.01)
        assertEquals(-50.0, blocks[0].horizontal_offset ?: 0.0, 0.01)
    }

    @Test
    fun parseBlockWithLargeStarCount() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "large1",
                "type" to "rating",
                "max_stars" to 100,
                "star_size" to 64,
                "default_value" to 99.5,
                "allow_half" to true
            )
        )
        assertEquals(100, blocks[0].max_stars)
        assertEquals(64.0, blocks[0].star_size ?: 0.0, 0.01)
        assertEquals(99.5, blocks[0].default_value ?: 0.0, 0.01)
    }

    // MARK: - Multiple blocks in one step (AC-COV-012)

    @Test
    fun parseMultipleBlocksInOneStep() {
        val blocks = parseBlocks(
            mapOf("id" to "mb1", "type" to "heading", "text" to "Welcome", "level" to 1),
            mapOf("id" to "mb2", "type" to "image", "image_url" to "https://example.com/hero.jpg", "height" to 200),
            mapOf("id" to "mb3", "type" to "button", "text" to "Get Started", "action" to "next", "bg_color" to "#6366f1")
        )
        assertEquals(3, blocks.size)

        // Block 1: heading
        assertEquals("heading", blocks[0].type)
        assertEquals("Welcome", blocks[0].text)
        assertEquals(1, blocks[0].level)

        // Block 2: image
        assertEquals("image", blocks[1].type)
        assertEquals("https://example.com/hero.jpg", blocks[1].image_url)
        assertEquals(200.0, blocks[1].height ?: 0.0, 0.01)

        // Block 3: button
        assertEquals("button", blocks[2].type)
        assertEquals("Get Started", blocks[2].text)
        assertEquals("next", blocks[2].action)
        assertEquals("#6366f1", blocks[2].bg_color)
    }

    // MARK: - Complete form step (AC-COV-013)

    @Test
    fun parseCompleteFormStep() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "form_h",
                "type" to "heading",
                "text" to "Tell us about yourself",
                "level" to 2,
                "style" to mapOf("font_size" to 24, "font_weight" to 700, "color" to "#111827")
            ),
            mapOf(
                "id" to "form_name",
                "type" to "input_text",
                "field_id" to "full_name",
                "field_label" to "Full Name",
                "field_placeholder" to "Enter your name",
                "field_required" to true
            ),
            mapOf(
                "id" to "form_role",
                "type" to "input_select",
                "field_id" to "role",
                "field_label" to "Your Role",
                "field_options" to listOf(
                    mapOf("id" to "dev", "label" to "Developer"),
                    mapOf("id" to "designer", "label" to "Designer"),
                    mapOf("id" to "pm", "label" to "Product Manager")
                )
            ),
            mapOf(
                "id" to "form_submit",
                "type" to "button",
                "text" to "Continue",
                "action" to "next",
                "bg_color" to "#6366f1",
                "text_color" to "#ffffff",
                "button_corner_radius" to 12
            )
        )
        assertEquals(4, blocks.size)

        // Heading
        assertEquals("heading", blocks[0].type)
        assertNotNull(blocks[0].style)
        assertEquals(24.0, blocks[0].style?.font_size ?: 0.0, 0.01)

        // Input text
        assertEquals("input_text", blocks[1].type)
        assertEquals("full_name", blocks[1].field_id)
        assertEquals(true, blocks[1].field_required)

        // Input select
        assertEquals("input_select", blocks[2].type)
        assertEquals(3, blocks[2].field_options?.size)
        assertEquals("dev", blocks[2].field_options?.get(0)?.value)
        assertEquals("Designer", blocks[2].field_options?.get(1)?.label)

        // Button
        assertEquals("button", blocks[3].type)
        assertEquals("#6366f1", blocks[3].bg_color)
        assertEquals(12.0, blocks[3].button_corner_radius ?: 0.0, 0.01)
    }

    // MARK: - Social login with disabled provider (AC-COV-014)

    @Test
    fun parseSocialLoginWithDisabledProvider() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "sl_dis1",
                "type" to "social_login",
                "providers" to listOf(
                    mapOf("type" to "apple", "enabled" to true),
                    mapOf("type" to "google", "enabled" to false),
                    mapOf("type" to "facebook", "enabled" to true, "label" to "Continue with Facebook"),
                    mapOf("type" to "twitter", "enabled" to false, "label" to "Use Twitter")
                ),
                "button_style" to "outlined"
            )
        )
        assertEquals("social_login", blocks[0].type)
        assertEquals(4, blocks[0].providers?.size)

        // Provider 0: apple — enabled
        assertEquals("apple", blocks[0].providers?.get(0)?.type)
        assertEquals(true, blocks[0].providers?.get(0)?.enabled)

        // Provider 1: google — disabled
        assertEquals("google", blocks[0].providers?.get(1)?.type)
        assertEquals(false, blocks[0].providers?.get(1)?.enabled)

        // Provider 2: facebook — enabled with custom label
        assertEquals("facebook", blocks[0].providers?.get(2)?.type)
        assertEquals(true, blocks[0].providers?.get(2)?.enabled)
        assertEquals("Continue with Facebook", blocks[0].providers?.get(2)?.label)

        // Provider 3: twitter — disabled with custom label
        assertEquals("twitter", blocks[0].providers?.get(3)?.type)
        assertEquals(false, blocks[0].providers?.get(3)?.enabled)
        assertEquals("Use Twitter", blocks[0].providers?.get(3)?.label)

        assertEquals("outlined", blocks[0].button_style)
    }

    // MARK: - Countdown timer variants (AC-COV-015)

    @Test
    fun parseCountdownTimerCircularVariant() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "ct_circ1",
                "type" to "countdown_timer",
                "variant" to "circular",
                "duration_seconds" to 600,
                "show_days" to true,
                "show_hours" to true,
                "show_minutes" to true,
                "show_seconds" to true,
                "accent_color" to "#3b82f6",
                "font_size" to 32,
                "alignment" to "center",
                "labels" to mapOf(
                    "days" to "Days",
                    "hours" to "Hrs",
                    "minutes" to "Min",
                    "seconds" to "Sec"
                ),
                "expired_text" to "Time is up!",
                "target_type" to "duration"
            )
        )
        assertEquals("countdown_timer", blocks[0].type)
        assertEquals("circular", blocks[0].variant)
        assertEquals(600, blocks[0].duration_seconds)
        assertEquals(true, blocks[0].show_days)
        assertEquals(true, blocks[0].show_hours)
        assertEquals(true, blocks[0].show_minutes)
        assertEquals(true, blocks[0].show_seconds)
        assertEquals("#3b82f6", blocks[0].accent_color)
        assertEquals(32.0, blocks[0].font_size ?: 0.0, 0.01)
        assertEquals("center", blocks[0].alignment)
        assertNotNull(blocks[0].labels)
        assertEquals("Days", blocks[0].labels?.days)
        assertEquals("Hrs", blocks[0].labels?.hours)
        assertEquals("Min", blocks[0].labels?.minutes)
        assertEquals("Sec", blocks[0].labels?.seconds)
        assertEquals("Time is up!", blocks[0].expired_text)
        assertEquals("duration", blocks[0].target_type)
    }

    @Test
    fun parseCountdownTimerTargetDatetime() {
        val blocks = parseBlocks(
            mapOf(
                "id" to "ct_dt1",
                "type" to "countdown_timer",
                "target_type" to "datetime",
                "target_datetime" to "2026-12-31T23:59:59Z",
                "show_days" to true,
                "on_expire_action" to "hide"
            )
        )
        assertEquals("countdown_timer", blocks[0].type)
        assertEquals("datetime", blocks[0].target_type)
        assertEquals("2026-12-31T23:59:59Z", blocks[0].target_datetime)
        assertEquals(true, blocks[0].show_days)
        assertEquals("hide", blocks[0].on_expire_action)
    }
}
