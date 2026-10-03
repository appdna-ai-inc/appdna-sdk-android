package ai.appdna.sdk

import ai.appdna.sdk.core.*
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for shared core style config types used across all SDK modules
 * (paywalls, onboarding, surveys, messages).
 *
 * Tests both direct data class construction and end-to-end parsing
 * through PaywallConfigParser (section-level style parsing).
 *
 * Covers: TextStyleConfig, ElementStyleConfig, AnimationConfig,
 * HapticConfig, ParticleEffect, BlurConfig, GradientConfig,
 * BackgroundStyleConfig, BorderStyleConfig, ShadowStyleConfig,
 * SpacingConfig, SectionStyleConfig.
 */
class CoreStyleConfigParsingTest {

    // -----------------------------------------------------------------------
    // Helpers — parse a paywall with a styled section to exercise style pipeline
    // -----------------------------------------------------------------------

    private fun parsePaywallWithSectionStyle(
        styleMap: Map<String, Any>,
    ): SectionStyleConfig? {
        val paywallMap = mapOf<String, Any>(
            "id" to "styled_pw",
            "name" to "Styled",
            "layout" to mapOf("type" to "stack"),
            "sections" to listOf(
                mapOf(
                    "type" to "header",
                    "data" to mapOf("title" to "Test"),
                    "style" to styleMap,
                ),
            ),
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("styled_pw" to paywallMap))
        return result["styled_pw"]!!.sections[0].style
    }

    private fun parsePaywallWithExtras(extras: Map<String, Any>): PaywallConfig {
        val paywallMap = mutableMapOf<String, Any>(
            "id" to "test_pw",
            "name" to "Test",
            "layout" to mapOf("type" to "stack"),
            "sections" to emptyList<Map<String, Any>>(),
        )
        paywallMap.putAll(extras)
        val result = PaywallConfigParser.parsePaywalls(mapOf("test_pw" to paywallMap))
        return result["test_pw"]!!
    }

    // -----------------------------------------------------------------------
    // 1. TextStyleConfig
    // -----------------------------------------------------------------------

    @Test
    fun textStyleConfig_allFields() {
        val ts = TextStyleConfig(
            font_family = "Inter",
            font_size = 18.0,
            font_weight = 700,
            color = "#1A1A2E",
            alignment = "center",
            line_height = 1.5,
            letter_spacing = 0.5,
            opacity = 0.9,
        )
        assertEquals("Inter", ts.font_family)
        assertEquals(18.0, ts.font_size!!, 0.001)
        assertEquals(700, ts.font_weight)
        assertEquals("#1A1A2E", ts.color)
        assertEquals("center", ts.alignment)
        assertEquals(1.5, ts.line_height!!, 0.001)
        assertEquals(0.5, ts.letter_spacing!!, 0.001)
        assertEquals(0.9, ts.opacity!!, 0.001)
    }

    @Test
    fun textStyleConfig_defaults() {
        val ts = TextStyleConfig()
        assertNull(ts.font_family)
        assertNull(ts.font_size)
        assertNull(ts.font_weight)
        assertNull(ts.color)
        assertNull(ts.alignment)
        assertNull(ts.line_height)
        assertNull(ts.letter_spacing)
        assertNull(ts.opacity)
    }

    @Test
    fun textStyleConfig_parsedThroughSectionStyle() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "text_style" to mapOf(
                    "font_family" to "Roboto",
                    "font_size" to 16,
                    "font_weight" to 400,
                    "color" to "#333333",
                    "alignment" to "left",
                    "line_height" to 1.4,
                    "letter_spacing" to 0.2,
                    "opacity" to 0.85,
                ),
            ),
        ))
        assertNotNull(style)
        val ts = style!!.container!!.text_style!!
        assertEquals("Roboto", ts.font_family)
        assertEquals(16.0, ts.font_size!!, 0.001)
        assertEquals(400, ts.font_weight)
        assertEquals("#333333", ts.color)
        assertEquals("left", ts.alignment)
        assertEquals(1.4, ts.line_height!!, 0.001)
        assertEquals(0.2, ts.letter_spacing!!, 0.001)
        assertEquals(0.85, ts.opacity!!, 0.001)
    }

    @Test
    fun textStyleConfig_partialFontOnly() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "text_style" to mapOf(
                    "font_family" to "Helvetica",
                    "font_size" to 24,
                ),
            ),
        ))
        val ts = style!!.container!!.text_style!!
        assertEquals("Helvetica", ts.font_family)
        assertEquals(24.0, ts.font_size!!, 0.001)
        assertNull(ts.font_weight)
        assertNull(ts.color)
        assertNull(ts.alignment)
        assertNull(ts.line_height)
        assertNull(ts.letter_spacing)
        assertNull(ts.opacity)
    }

    @Test
    fun textStyleConfig_alignmentRight() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "text_style" to mapOf("alignment" to "right"),
            ),
        ))
        assertEquals("right", style!!.container!!.text_style!!.alignment)
    }

    // -----------------------------------------------------------------------
    // 2. ElementStyleConfig
    // -----------------------------------------------------------------------

    @Test
    fun elementStyleConfig_allFields() {
        val es = ElementStyleConfig(
            background = BackgroundStyleConfig(type = "color", color = "#FFFFFF"),
            border = BorderStyleConfig(width = 1.0, color = "#CCCCCC"),
            shadow = ShadowStyleConfig(x = 0.0, y = 2.0, blur = 4.0, spread = 0.0, color = "#00000033"),
            padding = SpacingConfig(top = 16.0, right = 12.0, bottom = 16.0, left = 12.0),
            corner_radius = 8.0,
            opacity = 0.95,
            text_style = TextStyleConfig(font_size = 14.0, color = "#000000"),
        )
        assertNotNull(es.background)
        assertNotNull(es.border)
        assertNotNull(es.shadow)
        assertNotNull(es.padding)
        assertEquals(8.0, es.corner_radius!!, 0.001)
        assertEquals(0.95, es.opacity!!, 0.001)
        assertNotNull(es.text_style)
    }

    @Test
    fun elementStyleConfig_defaults() {
        val es = ElementStyleConfig()
        assertNull(es.background)
        assertNull(es.border)
        assertNull(es.shadow)
        assertNull(es.padding)
        assertNull(es.corner_radius)
        assertNull(es.opacity)
        assertNull(es.text_style)
    }

    @Test
    fun elementStyleConfig_parsedThroughSectionStyle() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "corner_radius" to 12,
                "opacity" to 0.8,
                "background" to mapOf("type" to "color", "color" to "#F5F5F5"),
                "border" to mapOf("width" to 2, "color" to "#6366F1"),
                "shadow" to mapOf("x" to 0, "y" to 4, "blur" to 8, "spread" to 1, "color" to "#00000020"),
                "padding" to mapOf("top" to 20, "right" to 16, "bottom" to 20, "left" to 16),
                "text_style" to mapOf("font_size" to 14),
            ),
        ))
        val es = style!!.container!!
        assertEquals(12.0, es.corner_radius!!, 0.001)
        assertEquals(0.8, es.opacity!!, 0.001)
        assertNotNull(es.background)
        assertEquals("color", es.background!!.type)
        assertEquals("#F5F5F5", es.background!!.color)
        assertNotNull(es.border)
        assertEquals(2.0, es.border!!.width!!, 0.001)
        assertEquals("#6366F1", es.border!!.color)
        assertNotNull(es.shadow)
        assertEquals(0.0, es.shadow!!.x!!, 0.001)
        assertEquals(4.0, es.shadow!!.y!!, 0.001)
        assertEquals(8.0, es.shadow!!.blur!!, 0.001)
        assertEquals(1.0, es.shadow!!.spread!!, 0.001)
        assertEquals("#00000020", es.shadow!!.color)
        assertNotNull(es.padding)
        assertEquals(20.0, es.padding!!.top!!, 0.001)
        assertEquals(16.0, es.padding!!.right!!, 0.001)
        assertEquals(20.0, es.padding!!.bottom!!, 0.001)
        assertEquals(16.0, es.padding!!.left!!, 0.001)
        assertNotNull(es.text_style)
        assertEquals(14.0, es.text_style!!.font_size!!, 0.001)
    }

    @Test
    fun elementStyleConfig_cornerRadiusOnly() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf("corner_radius" to 24),
        ))
        assertEquals(24.0, style!!.container!!.corner_radius!!, 0.001)
        assertNull(style.container!!.background)
        assertNull(style.container!!.border)
        assertNull(style.container!!.shadow)
        assertNull(style.container!!.padding)
        assertNull(style.container!!.text_style)
    }

    // -----------------------------------------------------------------------
    // 3. AnimationConfig
    // -----------------------------------------------------------------------

    @Test
    fun animationConfig_allFields() {
        val ac = AnimationConfig(
            entry_animation = "slide_up",
            entry_duration_ms = 400,
            section_stagger = "fade_in",
            section_stagger_delay_ms = 100,
            cta_animation = "pulse",
            plan_selection_animation = "scale",
            dismiss_animation = "slide_down",
        )
        assertEquals("slide_up", ac.entry_animation)
        assertEquals(400, ac.entry_duration_ms)
        assertEquals("fade_in", ac.section_stagger)
        assertEquals(100, ac.section_stagger_delay_ms)
        assertEquals("pulse", ac.cta_animation)
        assertEquals("scale", ac.plan_selection_animation)
        assertEquals("slide_down", ac.dismiss_animation)
    }

    @Test
    fun animationConfig_defaults() {
        val ac = AnimationConfig()
        assertNull(ac.entry_animation)
        assertNull(ac.entry_duration_ms)
        assertNull(ac.section_stagger)
        assertNull(ac.section_stagger_delay_ms)
        assertNull(ac.cta_animation)
        assertNull(ac.plan_selection_animation)
        assertNull(ac.dismiss_animation)
    }

    @Test
    fun animationConfig_parsedThroughPaywall() {
        val pw = parsePaywallWithExtras(mapOf(
            "animation" to mapOf(
                "entry_animation" to "scale_in",
                "entry_duration_ms" to 600,
                "section_stagger" to "slide_in_left",
                "section_stagger_delay_ms" to 150,
                "cta_animation" to "glow",
                "plan_selection_animation" to "border_highlight",
                "dismiss_animation" to "scale_out",
            ),
        ))
        assertNotNull(pw.animation)
        assertEquals("scale_in", pw.animation!!.entry_animation)
        assertEquals(600, pw.animation!!.entry_duration_ms)
        assertEquals("slide_in_left", pw.animation!!.section_stagger)
        assertEquals(150, pw.animation!!.section_stagger_delay_ms)
        assertEquals("glow", pw.animation!!.cta_animation)
        assertEquals("border_highlight", pw.animation!!.plan_selection_animation)
        assertEquals("scale_out", pw.animation!!.dismiss_animation)
    }

    @Test
    fun animationConfig_partialEntryOnly() {
        val pw = parsePaywallWithExtras(mapOf(
            "animation" to mapOf(
                "entry_animation" to "fade_in",
                "entry_duration_ms" to 300,
            ),
        ))
        assertEquals("fade_in", pw.animation!!.entry_animation)
        assertEquals(300, pw.animation!!.entry_duration_ms)
        assertNull(pw.animation!!.section_stagger)
        assertNull(pw.animation!!.cta_animation)
        assertNull(pw.animation!!.dismiss_animation)
    }

    @Test
    fun animationConfig_absent() {
        val pw = parsePaywallWithExtras(emptyMap())
        assertNull(pw.animation)
    }

    @Test
    fun animationConfig_bounceStagger() {
        val pw = parsePaywallWithExtras(mapOf(
            "animation" to mapOf(
                "section_stagger" to "bounce",
                "section_stagger_delay_ms" to 200,
            ),
        ))
        assertEquals("bounce", pw.animation!!.section_stagger)
        assertEquals(200, pw.animation!!.section_stagger_delay_ms)
    }

    // -----------------------------------------------------------------------
    // 4. HapticConfig
    // -----------------------------------------------------------------------

    @Test
    fun hapticConfig_allTriggers() {
        val hc = HapticConfig(
            enabled = true,
            triggers = HapticTriggers(
                on_step_advance = "light",
                on_button_tap = "medium",
                on_plan_select = "heavy",
                on_option_select = "selection",
                on_toggle = "light",
                on_form_submit = "success",
                on_error = "error",
                on_success = "success",
            ),
        )
        assertTrue(hc.enabled)
        assertEquals("light", hc.triggers.on_step_advance)
        assertEquals("medium", hc.triggers.on_button_tap)
        assertEquals("heavy", hc.triggers.on_plan_select)
        assertEquals("selection", hc.triggers.on_option_select)
        assertEquals("light", hc.triggers.on_toggle)
        assertEquals("success", hc.triggers.on_form_submit)
        assertEquals("error", hc.triggers.on_error)
        assertEquals("success", hc.triggers.on_success)
    }

    @Test
    fun hapticConfig_disabled() {
        val hc = HapticConfig(enabled = false)
        assertFalse(hc.enabled)
        assertNull(hc.triggers.on_button_tap)
        assertNull(hc.triggers.on_plan_select)
        assertNull(hc.triggers.on_success)
        assertNull(hc.triggers.on_step_advance)
        assertNull(hc.triggers.on_option_select)
        assertNull(hc.triggers.on_toggle)
        assertNull(hc.triggers.on_form_submit)
        assertNull(hc.triggers.on_error)
    }

    @Test
    fun hapticConfig_defaults() {
        val hc = HapticConfig()
        assertFalse(hc.enabled)
        assertNull(hc.triggers.on_button_tap)
    }

    @Test
    fun hapticConfig_parsedThroughPaywall() {
        val pw = parsePaywallWithExtras(mapOf(
            "haptic" to mapOf(
                "enabled" to true,
                "triggers" to mapOf(
                    "on_button_tap" to "light",
                    "on_plan_select" to "medium",
                    "on_success" to "heavy",
                ),
            ),
        ))
        assertNotNull(pw.haptic)
        assertTrue(pw.haptic!!.enabled)
        assertEquals("light", pw.haptic!!.triggers.on_button_tap)
        assertEquals("medium", pw.haptic!!.triggers.on_plan_select)
        assertEquals("heavy", pw.haptic!!.triggers.on_success)
    }

    @Test
    fun hapticConfig_parsedDisabled() {
        val pw = parsePaywallWithExtras(mapOf(
            "haptic" to mapOf("enabled" to false),
        ))
        assertNotNull(pw.haptic)
        assertFalse(pw.haptic!!.enabled)
        assertNull(pw.haptic!!.triggers.on_button_tap)
    }

    @Test
    fun hapticConfig_parsedMissingEnabled_defaultsFalse() {
        val pw = parsePaywallWithExtras(mapOf(
            "haptic" to mapOf(
                "triggers" to mapOf("on_button_tap" to "light"),
            ),
        ))
        assertNotNull(pw.haptic)
        assertFalse(pw.haptic!!.enabled)
        assertEquals("light", pw.haptic!!.triggers.on_button_tap)
    }

    @Test
    fun hapticConfig_absent() {
        val pw = parsePaywallWithExtras(emptyMap())
        assertNull(pw.haptic)
    }

    @Test
    fun hapticType_fromString_allTypes() {
        assertEquals(HapticType.LIGHT, HapticType.fromString("light"))
        assertEquals(HapticType.MEDIUM, HapticType.fromString("medium"))
        assertEquals(HapticType.HEAVY, HapticType.fromString("heavy"))
        assertEquals(HapticType.SELECTION, HapticType.fromString("selection"))
        assertEquals(HapticType.SUCCESS, HapticType.fromString("success"))
        assertEquals(HapticType.WARNING, HapticType.fromString("warning"))
        assertEquals(HapticType.ERROR, HapticType.fromString("error"))
    }

    @Test
    fun hapticType_fromString_caseInsensitive() {
        assertEquals(HapticType.LIGHT, HapticType.fromString("LIGHT"))
        assertEquals(HapticType.MEDIUM, HapticType.fromString("Medium"))
        assertEquals(HapticType.SUCCESS, HapticType.fromString("SUCCESS"))
    }

    @Test
    fun hapticType_fromString_unknown() {
        assertNull(HapticType.fromString("vibrate"))
        assertNull(HapticType.fromString(""))
        assertNull(HapticType.fromString(null))
    }

    // -----------------------------------------------------------------------
    // 5. ParticleEffect
    // -----------------------------------------------------------------------

    @Test
    fun particleEffect_allFields() {
        val pe = ParticleEffect(
            type = "fireworks",
            trigger = "on_purchase",
            duration_ms = 5000,
            intensity = "heavy",
            colors = listOf("#FF0000", "#00FF00", "#0000FF"),
        )
        assertEquals("fireworks", pe.type)
        assertEquals("on_purchase", pe.trigger)
        assertEquals(5000, pe.duration_ms)
        assertEquals("heavy", pe.intensity)
        assertEquals(3, pe.colors!!.size)
        assertEquals("#FF0000", pe.colors!![0])
    }

    @Test
    fun particleEffect_defaults() {
        // ConfettiOverlay.kt:20 data class default is 2000
        // (R71 changed from 2500 to match iOS ConfettiOverlay.swift:89,101
        // `effect.duration_ms ?? 2000`). iOS-emitted unstyled effects
        // animated 500ms shorter on iOS pre-R71.
        val pe = ParticleEffect()
        assertEquals("confetti", pe.type)
        assertEquals("on_appear", pe.trigger)
        assertEquals(2000, pe.duration_ms)
        assertEquals("medium", pe.intensity)
        assertNull(pe.colors)
    }

    @Test
    fun particleEffect_allTypes() {
        listOf("confetti", "sparkle", "fireworks", "snow", "hearts").forEach { type ->
            val pe = ParticleEffect(type = type)
            assertEquals(type, pe.type)
        }
    }

    @Test
    fun particleEffect_allTriggers() {
        listOf("on_appear", "on_step_complete", "on_purchase", "on_flow_complete").forEach { trigger ->
            val pe = ParticleEffect(trigger = trigger)
            assertEquals(trigger, pe.trigger)
        }
    }

    @Test
    fun particleEffect_allIntensities() {
        listOf("light", "medium", "heavy").forEach { intensity ->
            val pe = ParticleEffect(intensity = intensity)
            assertEquals(intensity, pe.intensity)
        }
    }

    @Test
    fun particleEffect_parsedThroughPaywall() {
        val pw = parsePaywallWithExtras(mapOf(
            "particle_effect" to mapOf(
                "type" to "sparkle",
                "trigger" to "on_flow_complete",
                "duration_ms" to 4000,
                "intensity" to "light",
                "colors" to listOf("#FFD700", "#FFA500"),
            ),
        ))
        assertNotNull(pw.particle_effect)
        assertEquals("sparkle", pw.particle_effect!!.type)
        assertEquals("on_flow_complete", pw.particle_effect!!.trigger)
        assertEquals(4000, pw.particle_effect!!.duration_ms)
        assertEquals("light", pw.particle_effect!!.intensity)
        assertEquals(2, pw.particle_effect!!.colors!!.size)
        assertEquals("#FFD700", pw.particle_effect!!.colors!![0])
        assertEquals("#FFA500", pw.particle_effect!!.colors!![1])
    }

    @Test
    fun particleEffect_parsedDefaults() {
        val pw = parsePaywallWithExtras(mapOf(
            "particle_effect" to mapOf<String, Any>(),
        ))
        assertNotNull(pw.particle_effect)
        assertEquals("confetti", pw.particle_effect!!.type)
        assertEquals("on_purchase", pw.particle_effect!!.trigger) // parser default is "on_purchase"
        assertEquals(2500, pw.particle_effect!!.duration_ms)
        assertEquals("medium", pw.particle_effect!!.intensity)
        assertNull(pw.particle_effect!!.colors)
    }

    @Test
    fun particleEffect_parsedNoColors() {
        val pw = parsePaywallWithExtras(mapOf(
            "particle_effect" to mapOf(
                "type" to "snow",
                "trigger" to "on_appear",
            ),
        ))
        assertEquals("snow", pw.particle_effect!!.type)
        assertNull(pw.particle_effect!!.colors)
    }

    @Test
    fun particleEffect_absent() {
        val pw = parsePaywallWithExtras(emptyMap())
        assertNull(pw.particle_effect)
    }

    // -----------------------------------------------------------------------
    // 6. BlurConfig
    // -----------------------------------------------------------------------

    @Test
    fun blurConfig_allFields() {
        val bc = BlurConfig(
            radius = 20f,
            tint = "#00000080",
            saturation = 1.8f,
        )
        assertEquals(20f, bc.radius, 0.01f)
        assertEquals("#00000080", bc.tint)
        assertEquals(1.8f, bc.saturation!!, 0.01f)
    }

    @Test
    fun blurConfig_radiusOnly() {
        val bc = BlurConfig(radius = 10f)
        assertEquals(10f, bc.radius, 0.01f)
        assertNull(bc.tint)
        assertNull(bc.saturation)
    }

    @Test
    fun blurConfig_zeroRadius() {
        val bc = BlurConfig(radius = 0f)
        assertEquals(0f, bc.radius, 0.01f)
    }

    // -----------------------------------------------------------------------
    // 7. GradientConfig
    // -----------------------------------------------------------------------

    @Test
    fun gradientConfig_linearAllFields() {
        val gc = GradientConfig(
            type = "linear",
            angle = 135.0,
            stops = listOf(
                GradientStopConfig(color = "#6366F1", position = 0.0),
                GradientStopConfig(color = "#8B5CF6", position = 0.5),
                GradientStopConfig(color = "#A855F7", position = 1.0),
            ),
        )
        assertEquals("linear", gc.type)
        assertEquals(135.0, gc.angle!!, 0.001)
        assertEquals(3, gc.stops!!.size)
        assertEquals("#6366F1", gc.stops!![0].color)
        assertEquals(0.0, gc.stops!![0].position, 0.001)
        assertEquals("#8B5CF6", gc.stops!![1].color)
        assertEquals(0.5, gc.stops!![1].position, 0.001)
        assertEquals("#A855F7", gc.stops!![2].color)
        assertEquals(1.0, gc.stops!![2].position, 0.001)
    }

    @Test
    fun gradientConfig_radial() {
        val gc = GradientConfig(
            type = "radial",
            stops = listOf(
                GradientStopConfig(color = "#FFFFFF", position = 0.0),
                GradientStopConfig(color = "#000000", position = 1.0),
            ),
        )
        assertEquals("radial", gc.type)
        assertNull(gc.angle)
        assertEquals(2, gc.stops!!.size)
    }

    @Test
    fun gradientConfig_defaults() {
        val gc = GradientConfig()
        assertNull(gc.type)
        assertNull(gc.angle)
        assertNull(gc.stops)
    }

    @Test
    fun gradientConfig_parsedThroughSectionStyle() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "gradient",
                    "gradient" to mapOf(
                        "type" to "linear",
                        "angle" to 180,
                        "stops" to listOf(
                            mapOf("color" to "#FF5722", "position" to 0.0),
                            mapOf("color" to "#E91E63", "position" to 1.0),
                        ),
                    ),
                ),
            ),
        ))
        val bg = style!!.container!!.background!!
        assertEquals("gradient", bg.type)
        assertNotNull(bg.gradient)
        assertEquals("linear", bg.gradient!!.type)
        assertEquals(180.0, bg.gradient!!.angle!!, 0.001)
        assertEquals(2, bg.gradient!!.stops!!.size)
        assertEquals("#FF5722", bg.gradient!!.stops!![0].color)
        assertEquals(0.0, bg.gradient!!.stops!![0].position, 0.001)
        assertEquals("#E91E63", bg.gradient!!.stops!![1].color)
        assertEquals(1.0, bg.gradient!!.stops!![1].position, 0.001)
    }

    @Test
    fun gradientConfig_parsedRadial() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "gradient",
                    "gradient" to mapOf(
                        "type" to "radial",
                        "stops" to listOf(
                            mapOf("color" to "#FFFFFF", "position" to 0.0),
                            mapOf("color" to "#000000", "position" to 1.0),
                        ),
                    ),
                ),
            ),
        ))
        val grad = style!!.container!!.background!!.gradient!!
        assertEquals("radial", grad.type)
        assertNull(grad.angle)
        assertEquals(2, grad.stops!!.size)
    }

    @Test
    fun gradientStopConfig_missingColorDefaultsToBlack() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "gradient",
                    "gradient" to mapOf(
                        "type" to "linear",
                        "stops" to listOf(
                            mapOf("position" to 0.0),
                            mapOf("color" to "#FFFFFF", "position" to 1.0),
                        ),
                    ),
                ),
            ),
        ))
        val stops = style!!.container!!.background!!.gradient!!.stops!!
        assertEquals("#000000", stops[0].color) // default
        assertEquals(0.0, stops[0].position, 0.001)
        assertEquals("#FFFFFF", stops[1].color)
    }

    @Test
    fun gradientStopConfig_missingPositionDefaultsToZero() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "gradient",
                    "gradient" to mapOf(
                        "type" to "linear",
                        "stops" to listOf(
                            mapOf("color" to "#FF0000"),
                        ),
                    ),
                ),
            ),
        ))
        val stops = style!!.container!!.background!!.gradient!!.stops!!
        assertEquals(0.0, stops[0].position, 0.001)
    }

    // -----------------------------------------------------------------------
    // 8. BackgroundStyleConfig
    // -----------------------------------------------------------------------

    @Test
    fun backgroundStyleConfig_color() {
        val bg = BackgroundStyleConfig(type = "color", color = "#FFFFFF")
        assertEquals("color", bg.type)
        assertEquals("#FFFFFF", bg.color)
        assertNull(bg.gradient)
        assertNull(bg.image_url)
        assertNull(bg.image_fit)
        assertNull(bg.overlay)
    }

    @Test
    fun backgroundStyleConfig_gradient() {
        val bg = BackgroundStyleConfig(
            type = "gradient",
            gradient = GradientConfig(
                type = "linear",
                angle = 90.0,
                stops = listOf(
                    GradientStopConfig("#000000", 0.0),
                    GradientStopConfig("#FFFFFF", 1.0),
                ),
            ),
        )
        assertEquals("gradient", bg.type)
        assertNotNull(bg.gradient)
        assertEquals("linear", bg.gradient!!.type)
    }

    @Test
    fun backgroundStyleConfig_image() {
        val bg = BackgroundStyleConfig(
            type = "image",
            image_url = "https://cdn.example.com/bg.jpg",
            image_fit = "cover",
            overlay = "#00000066",
        )
        assertEquals("image", bg.type)
        assertEquals("https://cdn.example.com/bg.jpg", bg.image_url)
        assertEquals("cover", bg.image_fit)
        assertEquals("#00000066", bg.overlay)
    }

    @Test
    fun backgroundStyleConfig_defaults() {
        val bg = BackgroundStyleConfig()
        assertNull(bg.type)
        assertNull(bg.color)
        assertNull(bg.gradient)
        assertNull(bg.image_url)
        assertNull(bg.image_fit)
        assertNull(bg.overlay)
    }

    @Test
    fun backgroundStyleConfig_parsedColorType() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "color",
                    "color" to "#1A1A2E",
                ),
            ),
        ))
        val bg = style!!.container!!.background!!
        assertEquals("color", bg.type)
        assertEquals("#1A1A2E", bg.color)
        assertNull(bg.gradient)
    }

    @Test
    fun backgroundStyleConfig_parsedImageType() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "image",
                    "image_url" to "https://cdn.example.com/pattern.png",
                    "image_fit" to "fill",
                    "overlay" to "#FFFFFF33",
                ),
            ),
        ))
        val bg = style!!.container!!.background!!
        assertEquals("image", bg.type)
        assertEquals("https://cdn.example.com/pattern.png", bg.image_url)
        assertEquals("fill", bg.image_fit)
        assertEquals("#FFFFFF33", bg.overlay)
    }

    // -----------------------------------------------------------------------
    // 9. BorderStyleConfig
    // -----------------------------------------------------------------------

    @Test
    fun borderStyleConfig_allFields() {
        val bs = BorderStyleConfig(
            width = 2.0,
            color = "#6366F1",
            style = "solid",
            radius = 12.0,
            radius_top_left = 16.0,
            radius_top_right = 16.0,
            radius_bottom_left = 0.0,
            radius_bottom_right = 0.0,
        )
        assertEquals(2.0, bs.width!!, 0.001)
        assertEquals("#6366F1", bs.color)
        assertEquals("solid", bs.style)
        assertEquals(12.0, bs.radius!!, 0.001)
        assertEquals(16.0, bs.radius_top_left!!, 0.001)
        assertEquals(16.0, bs.radius_top_right!!, 0.001)
        assertEquals(0.0, bs.radius_bottom_left!!, 0.001)
        assertEquals(0.0, bs.radius_bottom_right!!, 0.001)
    }

    @Test
    fun borderStyleConfig_defaults() {
        val bs = BorderStyleConfig()
        assertNull(bs.width)
        assertNull(bs.color)
        assertNull(bs.style)
        assertNull(bs.radius)
        assertNull(bs.radius_top_left)
        assertNull(bs.radius_top_right)
        assertNull(bs.radius_bottom_left)
        assertNull(bs.radius_bottom_right)
    }

    @Test
    fun borderStyleConfig_uniformRadius() {
        val bs = BorderStyleConfig(width = 1.0, color = "#CCCCCC", radius = 8.0)
        assertEquals(8.0, bs.radius!!, 0.001)
        assertNull(bs.radius_top_left)
        assertNull(bs.radius_top_right)
        assertNull(bs.radius_bottom_left)
        assertNull(bs.radius_bottom_right)
    }

    @Test
    fun borderStyleConfig_parsedAllFields() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "border" to mapOf(
                    "width" to 3,
                    "color" to "#FF5722",
                    "style" to "dashed",
                    "radius" to 10,
                    "radius_top_left" to 20,
                    "radius_top_right" to 20,
                    "radius_bottom_left" to 4,
                    "radius_bottom_right" to 4,
                ),
            ),
        ))
        val bs = style!!.container!!.border!!
        assertEquals(3.0, bs.width!!, 0.001)
        assertEquals("#FF5722", bs.color)
        assertEquals("dashed", bs.style)
        assertEquals(10.0, bs.radius!!, 0.001)
        assertEquals(20.0, bs.radius_top_left!!, 0.001)
        assertEquals(20.0, bs.radius_top_right!!, 0.001)
        assertEquals(4.0, bs.radius_bottom_left!!, 0.001)
        assertEquals(4.0, bs.radius_bottom_right!!, 0.001)
    }

    @Test
    fun borderStyleConfig_parsedMinimal() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "border" to mapOf(
                    "width" to 1,
                    "color" to "#000000",
                ),
            ),
        ))
        val bs = style!!.container!!.border!!
        assertEquals(1.0, bs.width!!, 0.001)
        assertEquals("#000000", bs.color)
        assertNull(bs.style)
        assertNull(bs.radius)
        assertNull(bs.radius_top_left)
    }

    // -----------------------------------------------------------------------
    // 10. ShadowStyleConfig
    // -----------------------------------------------------------------------

    @Test
    fun shadowStyleConfig_allFields() {
        val ss = ShadowStyleConfig(
            x = 2.0,
            y = 4.0,
            blur = 8.0,
            spread = 1.0,
            color = "#00000033",
        )
        assertEquals(2.0, ss.x!!, 0.001)
        assertEquals(4.0, ss.y!!, 0.001)
        assertEquals(8.0, ss.blur!!, 0.001)
        assertEquals(1.0, ss.spread!!, 0.001)
        assertEquals("#00000033", ss.color)
    }

    @Test
    fun shadowStyleConfig_defaults() {
        val ss = ShadowStyleConfig()
        assertNull(ss.x)
        assertNull(ss.y)
        assertNull(ss.blur)
        assertNull(ss.spread)
        assertNull(ss.color)
    }

    @Test
    fun shadowStyleConfig_parsed() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "shadow" to mapOf(
                    "x" to 0,
                    "y" to 6,
                    "blur" to 12,
                    "spread" to 2,
                    "color" to "#6366F140",
                ),
            ),
        ))
        val ss = style!!.container!!.shadow!!
        assertEquals(0.0, ss.x!!, 0.001)
        assertEquals(6.0, ss.y!!, 0.001)
        assertEquals(12.0, ss.blur!!, 0.001)
        assertEquals(2.0, ss.spread!!, 0.001)
        assertEquals("#6366F140", ss.color)
    }

    @Test
    fun shadowStyleConfig_parsedPartial() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "shadow" to mapOf(
                    "blur" to 4,
                    "color" to "#00000020",
                ),
            ),
        ))
        val ss = style!!.container!!.shadow!!
        assertNull(ss.x)
        assertNull(ss.y)
        assertEquals(4.0, ss.blur!!, 0.001)
        assertNull(ss.spread)
        assertEquals("#00000020", ss.color)
    }

    // -----------------------------------------------------------------------
    // 11. SpacingConfig
    // -----------------------------------------------------------------------

    @Test
    fun spacingConfig_allFields() {
        val sc = SpacingConfig(
            top = 16.0,
            right = 12.0,
            bottom = 16.0,
            left = 12.0,
        )
        assertEquals(16.0, sc.top!!, 0.001)
        assertEquals(12.0, sc.right!!, 0.001)
        assertEquals(16.0, sc.bottom!!, 0.001)
        assertEquals(12.0, sc.left!!, 0.001)
    }

    @Test
    fun spacingConfig_defaults() {
        val sc = SpacingConfig()
        assertNull(sc.top)
        assertNull(sc.right)
        assertNull(sc.bottom)
        assertNull(sc.left)
    }

    @Test
    fun spacingConfig_asymmetric() {
        val sc = SpacingConfig(top = 24.0, right = 8.0, bottom = 0.0, left = 8.0)
        assertEquals(24.0, sc.top!!, 0.001)
        assertEquals(8.0, sc.right!!, 0.001)
        assertEquals(0.0, sc.bottom!!, 0.001)
        assertEquals(8.0, sc.left!!, 0.001)
    }

    @Test
    fun spacingConfig_parsedAllDirections() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "padding" to mapOf(
                    "top" to 24,
                    "right" to 16,
                    "bottom" to 32,
                    "left" to 16,
                ),
            ),
        ))
        val p = style!!.container!!.padding!!
        assertEquals(24.0, p.top!!, 0.001)
        assertEquals(16.0, p.right!!, 0.001)
        assertEquals(32.0, p.bottom!!, 0.001)
        assertEquals(16.0, p.left!!, 0.001)
    }

    @Test
    fun spacingConfig_parsedPartial() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "padding" to mapOf(
                    "top" to 8,
                    "bottom" to 8,
                ),
            ),
        ))
        val p = style!!.container!!.padding!!
        assertEquals(8.0, p.top!!, 0.001)
        assertNull(p.right)
        assertEquals(8.0, p.bottom!!, 0.001)
        assertNull(p.left)
    }

    // -----------------------------------------------------------------------
    // 12. SectionStyleConfig — container + elements
    // -----------------------------------------------------------------------

    @Test
    fun sectionStyleConfig_containerOnly() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "corner_radius" to 16,
                "opacity" to 0.9,
            ),
        ))
        assertNotNull(style)
        assertNotNull(style!!.container)
        assertEquals(16.0, style.container!!.corner_radius!!, 0.001)
        assertEquals(0.9, style.container!!.opacity!!, 0.001)
        assertNull(style.elements)
    }

    @Test
    fun sectionStyleConfig_elementsMap() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "elements" to mapOf(
                "title" to mapOf(
                    "text_style" to mapOf("font_size" to 24, "font_weight" to 700, "color" to "#1A1A2E"),
                ),
                "subtitle" to mapOf(
                    "text_style" to mapOf("font_size" to 14, "color" to "#666666"),
                    "opacity" to 0.8,
                ),
                "button" to mapOf(
                    "background" to mapOf("type" to "color", "color" to "#6366F1"),
                    "corner_radius" to 8,
                ),
            ),
        ))
        assertNotNull(style)
        assertNull(style!!.container)
        assertNotNull(style.elements)
        assertEquals(3, style.elements!!.size)

        // Title element
        val title = style.elements!!["title"]!!
        assertEquals(24.0, title.text_style!!.font_size!!, 0.001)
        assertEquals(700, title.text_style!!.font_weight)
        assertEquals("#1A1A2E", title.text_style!!.color)

        // Subtitle element
        val subtitle = style.elements!!["subtitle"]!!
        assertEquals(14.0, subtitle.text_style!!.font_size!!, 0.001)
        assertEquals("#666666", subtitle.text_style!!.color)
        assertEquals(0.8, subtitle.opacity!!, 0.001)

        // Button element
        val button = style.elements!!["button"]!!
        assertEquals("color", button.background!!.type)
        assertEquals("#6366F1", button.background!!.color)
        assertEquals(8.0, button.corner_radius!!, 0.001)
    }

    @Test
    fun sectionStyleConfig_containerAndElements() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf("type" to "color", "color" to "#F0F0F0"),
                "padding" to mapOf("top" to 16, "bottom" to 16, "left" to 12, "right" to 12),
                "corner_radius" to 12,
            ),
            "elements" to mapOf(
                "heading" to mapOf(
                    "text_style" to mapOf("font_size" to 20, "font_weight" to 600),
                ),
            ),
        ))
        assertNotNull(style!!.container)
        assertNotNull(style.elements)
        assertEquals("color", style.container!!.background!!.type)
        assertEquals("#F0F0F0", style.container!!.background!!.color)
        assertEquals(12.0, style.container!!.corner_radius!!, 0.001)
        assertEquals(20.0, style.elements!!["heading"]!!.text_style!!.font_size!!, 0.001)
    }

    @Test
    fun sectionStyleConfig_absent() {
        val paywallMap = mapOf<String, Any>(
            "id" to "no_style",
            "name" to "No Style",
            "layout" to mapOf("type" to "stack"),
            "sections" to listOf(
                mapOf(
                    "type" to "header",
                    "data" to mapOf("title" to "Plain"),
                ),
            ),
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("no_style" to paywallMap))
        assertNull(result["no_style"]!!.sections[0].style)
    }

    @Test
    fun sectionStyleConfig_emptyMap() {
        val style = parsePaywallWithSectionStyle(emptyMap())
        assertNotNull(style)
        assertNull(style!!.container)
        assertNull(style.elements)
    }

    // -----------------------------------------------------------------------
    // 13. Edge cases — partial configs, null fields, numeric types
    // -----------------------------------------------------------------------

    @Test
    fun edgeCase_partialContainerNoBackground() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "corner_radius" to 8,
            ),
        ))
        val es = style!!.container!!
        assertEquals(8.0, es.corner_radius!!, 0.001)
        assertNull(es.background)
        assertNull(es.border)
        assertNull(es.shadow)
        assertNull(es.padding)
        assertNull(es.text_style)
        assertNull(es.opacity)
    }

    @Test
    fun edgeCase_numericTypesFromFirestore_intToDouble() {
        // Firestore may return Int, Long, or Double for number fields
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "corner_radius" to 12, // Int
                "opacity" to 0.5, // Double
                "padding" to mapOf(
                    "top" to 16L, // Long
                    "right" to 8, // Int
                ),
                "border" to mapOf(
                    "width" to 2, // Int
                    "radius" to 6.5, // Double
                ),
            ),
        ))
        val es = style!!.container!!
        assertEquals(12.0, es.corner_radius!!, 0.001)
        assertEquals(0.5, es.opacity!!, 0.001)
        assertEquals(16.0, es.padding!!.top!!, 0.001)
        assertEquals(8.0, es.padding!!.right!!, 0.001)
        assertEquals(2.0, es.border!!.width!!, 0.001)
        assertEquals(6.5, es.border!!.radius!!, 0.001)
    }

    @Test
    fun edgeCase_emptyContainerMap() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to emptyMap<String, Any>(),
        ))
        assertNotNull(style!!.container)
        assertNull(style.container!!.background)
        assertNull(style.container!!.border)
        assertNull(style.container!!.shadow)
        assertNull(style.container!!.padding)
        assertNull(style.container!!.corner_radius)
        assertNull(style.container!!.opacity)
        assertNull(style.container!!.text_style)
    }

    @Test
    fun edgeCase_emptyElementsMap() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "elements" to emptyMap<String, Any>(),
        ))
        assertNotNull(style)
        assertNull(style!!.container)
        assertNotNull(style.elements)
        assertTrue(style.elements!!.isEmpty())
    }

    @Test
    fun edgeCase_backgroundNoGradient() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "gradient",
                ),
            ),
        ))
        val bg = style!!.container!!.background!!
        assertEquals("gradient", bg.type)
        assertNull(bg.gradient)
        assertNull(bg.color)
    }

    @Test
    fun edgeCase_gradientEmptyStops() {
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "gradient",
                    "gradient" to mapOf(
                        "type" to "linear",
                        "stops" to emptyList<Map<String, Any>>(),
                    ),
                ),
            ),
        ))
        val grad = style!!.container!!.background!!.gradient!!
        assertEquals("linear", grad.type)
        assertNotNull(grad.stops)
        assertTrue(grad.stops!!.isEmpty())
    }

    @Test
    fun edgeCase_complexNestedStyle() {
        // Full real-world style config with all nested levels
        val style = parsePaywallWithSectionStyle(mapOf(
            "container" to mapOf(
                "background" to mapOf(
                    "type" to "gradient",
                    "gradient" to mapOf(
                        "type" to "linear",
                        "angle" to 135,
                        "stops" to listOf(
                            mapOf("color" to "#667EEA", "position" to 0.0),
                            mapOf("color" to "#764BA2", "position" to 1.0),
                        ),
                    ),
                ),
                "border" to mapOf(
                    "width" to 1,
                    "color" to "#FFFFFF33",
                    "style" to "solid",
                    "radius_top_left" to 24,
                    "radius_top_right" to 24,
                    "radius_bottom_left" to 0,
                    "radius_bottom_right" to 0,
                ),
                "shadow" to mapOf(
                    "x" to 0,
                    "y" to 8,
                    "blur" to 24,
                    "spread" to -4,
                    "color" to "#667EEA66",
                ),
                "padding" to mapOf(
                    "top" to 32,
                    "right" to 24,
                    "bottom" to 32,
                    "left" to 24,
                ),
                "corner_radius" to 24,
                "opacity" to 0.98,
                "text_style" to mapOf(
                    "font_family" to "Inter",
                    "font_size" to 16,
                    "font_weight" to 500,
                    "color" to "#FFFFFF",
                    "alignment" to "center",
                    "line_height" to 1.5,
                    "letter_spacing" to 0.3,
                    "opacity" to 0.95,
                ),
            ),
            "elements" to mapOf(
                "title" to mapOf(
                    "text_style" to mapOf(
                        "font_family" to "Inter",
                        "font_size" to 28,
                        "font_weight" to 800,
                        "color" to "#FFFFFF",
                    ),
                ),
                "cta_button" to mapOf(
                    "background" to mapOf("type" to "color", "color" to "#FFFFFF"),
                    "corner_radius" to 12,
                    "padding" to mapOf("top" to 14, "right" to 24, "bottom" to 14, "left" to 24),
                    "text_style" to mapOf("color" to "#667EEA", "font_weight" to 700, "font_size" to 16),
                ),
            ),
        ))

        assertNotNull(style)
        // Container checks
        val container = style!!.container!!
        assertEquals("gradient", container.background!!.type)
        assertEquals("linear", container.background!!.gradient!!.type)
        assertEquals(135.0, container.background!!.gradient!!.angle!!, 0.001)
        assertEquals(2, container.background!!.gradient!!.stops!!.size)
        assertEquals(1.0, container.border!!.width!!, 0.001)
        assertEquals("#FFFFFF33", container.border!!.color)
        assertEquals(24.0, container.border!!.radius_top_left!!, 0.001)
        assertEquals(0.0, container.border!!.radius_bottom_left!!, 0.001)
        assertEquals(8.0, container.shadow!!.y!!, 0.001)
        assertEquals(24.0, container.shadow!!.blur!!, 0.001)
        assertEquals(-4.0, container.shadow!!.spread!!, 0.001)
        assertEquals(32.0, container.padding!!.top!!, 0.001)
        assertEquals(24.0, container.padding!!.right!!, 0.001)
        assertEquals(24.0, container.corner_radius!!, 0.001)
        assertEquals(0.98, container.opacity!!, 0.001)
        assertEquals("Inter", container.text_style!!.font_family)
        assertEquals(16.0, container.text_style!!.font_size!!, 0.001)
        assertEquals(500, container.text_style!!.font_weight)
        assertEquals("#FFFFFF", container.text_style!!.color)
        assertEquals("center", container.text_style!!.alignment)
        assertEquals(1.5, container.text_style!!.line_height!!, 0.001)
        assertEquals(0.3, container.text_style!!.letter_spacing!!, 0.001)
        assertEquals(0.95, container.text_style!!.opacity!!, 0.001)

        // Elements checks
        val elements = style.elements!!
        assertEquals(2, elements.size)
        assertEquals(28.0, elements["title"]!!.text_style!!.font_size!!, 0.001)
        assertEquals(800, elements["title"]!!.text_style!!.font_weight)
        assertEquals("#FFFFFF", elements["cta_button"]!!.background!!.color)
        assertEquals(12.0, elements["cta_button"]!!.corner_radius!!, 0.001)
        assertEquals(14.0, elements["cta_button"]!!.padding!!.top!!, 0.001)
        assertEquals("#667EEA", elements["cta_button"]!!.text_style!!.color)
    }

    @Test
    fun edgeCase_multipleStylesAcrossSections() {
        val paywallMap = mapOf<String, Any>(
            "id" to "multi_style",
            "name" to "Multi Style",
            "layout" to mapOf("type" to "stack"),
            "sections" to listOf(
                mapOf(
                    "type" to "header",
                    "data" to mapOf("title" to "Premium"),
                    "style" to mapOf(
                        "container" to mapOf("corner_radius" to 0, "opacity" to 1.0),
                    ),
                ),
                mapOf(
                    "type" to "features",
                    "data" to mapOf("features" to listOf("Feature A")),
                    "style" to mapOf(
                        "container" to mapOf(
                            "padding" to mapOf("top" to 20, "bottom" to 20),
                            "background" to mapOf("type" to "color", "color" to "#F9FAFB"),
                        ),
                    ),
                ),
                mapOf(
                    "type" to "cta",
                    "data" to mapOf("cta" to mapOf("text" to "Buy")),
                ),
            ),
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("multi_style" to paywallMap))
        val pw = result["multi_style"]!!
        assertEquals(3, pw.sections.size)

        // First section has style
        assertNotNull(pw.sections[0].style)
        assertEquals(0.0, pw.sections[0].style!!.container!!.corner_radius!!, 0.001)

        // Second section has style
        assertNotNull(pw.sections[1].style)
        assertEquals(20.0, pw.sections[1].style!!.container!!.padding!!.top!!, 0.001)
        assertEquals("#F9FAFB", pw.sections[1].style!!.container!!.background!!.color)

        // Third section has no style
        assertNull(pw.sections[2].style)
    }
}
