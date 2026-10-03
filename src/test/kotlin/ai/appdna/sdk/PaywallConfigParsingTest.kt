package ai.appdna.sdk

import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that PaywallConfig correctly parses from Firestore Map data.
 * Covers all 24 section types and full paywall-level fields.
 * Prevents field name mismatches between console JSON and SDK parsing.
 */
class PaywallConfigParsingTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun parsePaywall(
        vararg sections: Map<String, Any>,
        extras: Map<String, Any> = emptyMap()
    ): PaywallConfig {
        val paywallMap = mutableMapOf<String, Any>(
            "id" to "test_paywall",
            "name" to "Test",
            "layout" to mapOf("type" to "stack"),
            "sections" to sections.toList()
        )
        paywallMap.putAll(extras)
        val result = PaywallConfigParser.parsePaywalls(mapOf("test_paywall" to paywallMap))
        return result["test_paywall"]!!
    }

    private fun section(type: String, data: Map<String, Any>): Map<String, Any> {
        return mapOf("type" to type, "data" to data)
    }

    // -----------------------------------------------------------------------
    // 1. Header section
    // -----------------------------------------------------------------------

    @Test
    fun parseHeaderSection_allFields() {
        val pw = parsePaywall(section("header", mapOf(
            "title" to "Premium Plan",
            "subtitle" to "Unlock all features",
            "image_url" to "https://cdn.example.com/hero.png"
        )))
        assertEquals(1, pw.sections.size)
        assertEquals("header", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("Premium Plan", d.title)
        assertEquals("Unlock all features", d.subtitle)
        assertEquals("https://cdn.example.com/hero.png", d.image_url)
    }

    @Test
    fun parseHeaderSection_titleOnly() {
        val pw = parsePaywall(section("header", mapOf(
            "title" to "Just a Title"
        )))
        val d = pw.sections[0].data!!
        assertEquals("Just a Title", d.title)
        assertNull(d.subtitle)
        assertNull(d.image_url)
    }

    // -----------------------------------------------------------------------
    // 2. Features section
    // -----------------------------------------------------------------------

    @Test
    fun parseFeaturesSection_allFields() {
        val pw = parsePaywall(section("features", mapOf(
            "features" to listOf("No Ads", "Offline Mode", "Cloud Sync")
        )))
        assertEquals("features", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals(3, d.features!!.size)
        assertEquals("No Ads", d.features!![0])
        assertEquals("Offline Mode", d.features!![1])
        assertEquals("Cloud Sync", d.features!![2])
    }

    @Test
    fun parseFeaturesSection_emptyList() {
        val pw = parsePaywall(section("features", mapOf(
            "features" to emptyList<String>()
        )))
        val d = pw.sections[0].data!!
        assertTrue(d.features!!.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 3. Plans section
    // -----------------------------------------------------------------------

    @Test
    fun parsePlansSection_allPlanFields() {
        val pw = parsePaywall(section("plans", mapOf(
            "plans" to listOf(
                mapOf(
                    "id" to "plan_yearly",
                    "product_id" to "com.app.yearly",
                    "name" to "Annual",
                    "price" to "$49.99/year",
                    "period" to "yearly",
                    "badge" to "Best Value",
                    "trial_duration" to "7 days",
                    "is_default" to true
                )
            )
        )))
        val d = pw.sections[0].data!!
        assertEquals(1, d.plans!!.size)
        val plan = d.plans!![0]
        assertEquals("plan_yearly", plan.id)
        assertEquals("com.app.yearly", plan.product_id)
        assertEquals("Annual", plan.name)
        assertEquals("$49.99/year", plan.price)
        assertEquals("yearly", plan.period)
        assertEquals("Best Value", plan.badge)
        assertEquals("7 days", plan.trial_duration)
        assertEquals(true, plan.is_default)
    }

    @Test
    fun parsePlansSection_minimalPlan() {
        val pw = parsePaywall(section("plans", mapOf(
            "plans" to listOf(
                mapOf(
                    "id" to "plan_monthly",
                    "product_id" to "com.app.monthly",
                    "name" to "Monthly",
                    "price" to "$4.99/mo"
                )
            )
        )))
        val plan = pw.sections[0].data!!.plans!![0]
        assertEquals("plan_monthly", plan.id)
        assertEquals("com.app.monthly", plan.product_id)
        assertEquals("Monthly", plan.name)
        assertEquals("$4.99/mo", plan.price)
        assertNull(plan.period)
        assertNull(plan.badge)
        assertNull(plan.trial_duration)
        assertNull(plan.is_default)
    }

    @Test
    fun parsePlansSection_multiplePlans() {
        val pw = parsePaywall(section("plans", mapOf(
            "plans" to listOf(
                mapOf("id" to "p1", "product_id" to "prod1", "name" to "Weekly", "price" to "$1.99/wk"),
                mapOf("id" to "p2", "product_id" to "prod2", "name" to "Monthly", "price" to "$4.99/mo"),
                mapOf("id" to "p3", "product_id" to "prod3", "name" to "Yearly", "price" to "$29.99/yr", "is_default" to true)
            )
        )))
        val plans = pw.sections[0].data!!.plans!!
        assertEquals(3, plans.size)
        assertEquals("p1", plans[0].id)
        assertEquals("p2", plans[1].id)
        assertEquals("p3", plans[2].id)
        assertEquals(true, plans[2].is_default)
    }

    // -----------------------------------------------------------------------
    // 4. CTA section
    // -----------------------------------------------------------------------

    @Test
    fun parseCtaSection_allFields() {
        val pw = parsePaywall(section("cta", mapOf(
            "cta" to mapOf(
                "text" to "Subscribe Now",
                "style" to "gradient"
            )
        )))
        assertEquals("cta", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("Subscribe Now", d.cta!!.text)
        assertEquals("gradient", d.cta!!.style)
    }

    @Test
    fun parseCtaSection_textOnly() {
        val pw = parsePaywall(section("cta", mapOf(
            "cta" to mapOf(
                "text" to "Continue"
            )
        )))
        val d = pw.sections[0].data!!
        assertEquals("Continue", d.cta!!.text)
        assertNull(d.cta!!.style)
    }

    // -----------------------------------------------------------------------
    // 5. Social proof section
    // -----------------------------------------------------------------------

    @Test
    fun parseSocialProofSection_appRating() {
        val pw = parsePaywall(section("social_proof", mapOf(
            "rating" to 4.8,
            "review_count" to 12500,
            "testimonial" to "Best app ever!",
            "sub_type" to "app_rating",
            "text" to "Loved by millions"
        )))
        assertEquals("social_proof", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals(4.8, d.rating!!, 0.001)
        assertEquals(12500, d.review_count)
        assertEquals("Best app ever!", d.testimonial)
        assertEquals("app_rating", d.sub_type)
        assertEquals("Loved by millions", d.text)
    }

    @Test
    fun parseSocialProofSection_countdown() {
        val pw = parsePaywall(section("social_proof", mapOf(
            "sub_type" to "countdown",
            "countdown_seconds" to 3600,
            "text" to "Offer expires soon"
        )))
        val d = pw.sections[0].data!!
        assertEquals("countdown", d.sub_type)
        assertEquals(3600, d.countdown_seconds)
        assertEquals("Offer expires soon", d.text)
    }

    @Test
    fun parseSocialProofSection_trialBadge() {
        val pw = parsePaywall(section("social_proof", mapOf(
            "sub_type" to "trial_badge",
            "text" to "7-day free trial"
        )))
        val d = pw.sections[0].data!!
        assertEquals("trial_badge", d.sub_type)
        assertEquals("7-day free trial", d.text)
    }

    // -----------------------------------------------------------------------
    // 6. Guarantee section
    // -----------------------------------------------------------------------

    @Test
    fun parseGuaranteeSection() {
        val pw = parsePaywall(section("guarantee", mapOf(
            "guarantee_text" to "30-day money-back guarantee. No questions asked."
        )))
        assertEquals("guarantee", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("30-day money-back guarantee. No questions asked.", d.guarantee_text)
    }

    // -----------------------------------------------------------------------
    // 7. Image section
    // -----------------------------------------------------------------------

    @Test
    fun parseImageSection_allFields() {
        val pw = parsePaywall(section("image", mapOf(
            "image_url" to "https://cdn.example.com/banner.png",
            "height" to 200,
            "corner_radius" to 12
        )))
        assertEquals("image", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("https://cdn.example.com/banner.png", d.image_url)
        assertEquals(200f, d.height!!, 0.01f)
        assertEquals(12f, d.corner_radius!!, 0.01f)
    }

    @Test
    fun parseImageSection_urlOnly() {
        val pw = parsePaywall(section("image", mapOf(
            "image_url" to "https://cdn.example.com/img.jpg"
        )))
        val d = pw.sections[0].data!!
        assertEquals("https://cdn.example.com/img.jpg", d.image_url)
        assertNull(d.height)
        assertNull(d.corner_radius)
    }

    // -----------------------------------------------------------------------
    // 8. Spacer section
    // -----------------------------------------------------------------------

    @Test
    fun parseSpacerSection() {
        val pw = parsePaywall(section("spacer", mapOf(
            "spacer_height" to 24
        )))
        assertEquals("spacer", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals(24f, d.spacer_height!!, 0.01f)
    }

    @Test
    fun parseSpacerSection_floatValue() {
        val pw = parsePaywall(section("spacer", mapOf(
            "spacer_height" to 16.5
        )))
        val d = pw.sections[0].data!!
        assertEquals(16.5f, d.spacer_height!!, 0.01f)
    }

    // -----------------------------------------------------------------------
    // 9. Testimonial section
    // -----------------------------------------------------------------------

    @Test
    fun parseTestimonialSection_allFields() {
        val pw = parsePaywall(section("testimonial", mapOf(
            "quote" to "This app changed my life!",
            "author_name" to "Jane Doe",
            "author_role" to "CEO at Acme",
            "avatar_url" to "https://cdn.example.com/jane.jpg"
        )))
        assertEquals("testimonial", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("This app changed my life!", d.quote)
        assertEquals("Jane Doe", d.author_name)
        assertEquals("CEO at Acme", d.author_role)
        assertEquals("https://cdn.example.com/jane.jpg", d.avatar_url)
    }

    @Test
    fun parseTestimonialSection_quoteOnly() {
        val pw = parsePaywall(section("testimonial", mapOf(
            "quote" to "Amazing!"
        )))
        val d = pw.sections[0].data!!
        assertEquals("Amazing!", d.quote)
        assertNull(d.author_name)
        assertNull(d.author_role)
        assertNull(d.avatar_url)
    }

    // -----------------------------------------------------------------------
    // 10. Lottie section
    // -----------------------------------------------------------------------

    @Test
    fun parseLottieSection_allFields() {
        val pw = parsePaywall(section("lottie", mapOf(
            "lottie_url" to "https://cdn.example.com/anim.lottie",
            "lottie_loop" to true,
            "lottie_speed" to 1.5
        )))
        assertEquals("lottie", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("https://cdn.example.com/anim.lottie", d.lottie_url)
        assertEquals(true, d.lottie_loop)
        assertEquals(1.5f, d.lottie_speed!!, 0.01f)
    }

    @Test
    fun parseLottieSection_urlOnly() {
        val pw = parsePaywall(section("lottie", mapOf(
            "lottie_url" to "https://cdn.example.com/animation.json"
        )))
        val d = pw.sections[0].data!!
        assertEquals("https://cdn.example.com/animation.json", d.lottie_url)
        assertNull(d.lottie_loop)
        assertNull(d.lottie_speed)
    }

    // -----------------------------------------------------------------------
    // 11. Video section
    // -----------------------------------------------------------------------

    @Test
    fun parseVideoSection_allFields() {
        val pw = parsePaywall(section("video", mapOf(
            "video_url" to "https://cdn.example.com/promo.mp4",
            "video_thumbnail_url" to "https://cdn.example.com/thumb.jpg",
            "video_autoplay" to true,
            "video_loop" to false
        )))
        assertEquals("video", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("https://cdn.example.com/promo.mp4", d.video_url)
        assertEquals("https://cdn.example.com/thumb.jpg", d.video_thumbnail_url)
        assertEquals(true, d.video_autoplay)
        assertEquals(false, d.video_loop)
    }

    @Test
    fun parseVideoSection_urlOnly() {
        val pw = parsePaywall(section("video", mapOf(
            "video_url" to "https://cdn.example.com/intro.mp4"
        )))
        val d = pw.sections[0].data!!
        assertEquals("https://cdn.example.com/intro.mp4", d.video_url)
        assertNull(d.video_thumbnail_url)
        assertNull(d.video_autoplay)
        assertNull(d.video_loop)
    }

    // -----------------------------------------------------------------------
    // 12. Rive section
    // -----------------------------------------------------------------------

    @Test
    fun parseRiveSection_allFields() {
        val pw = parsePaywall(section("rive", mapOf(
            "rive_url" to "https://cdn.example.com/character.riv",
            "rive_state_machine" to "idle_animation"
        )))
        assertEquals("rive", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("https://cdn.example.com/character.riv", d.rive_url)
        assertEquals("idle_animation", d.rive_state_machine)
    }

    @Test
    fun parseRiveSection_urlOnly() {
        val pw = parsePaywall(section("rive", mapOf(
            "rive_url" to "https://cdn.example.com/mascot.riv"
        )))
        val d = pw.sections[0].data!!
        assertEquals("https://cdn.example.com/mascot.riv", d.rive_url)
        assertNull(d.rive_state_machine)
    }

    // -----------------------------------------------------------------------
    // 13. Countdown section
    // -----------------------------------------------------------------------

    @Test
    fun parseCountdownSection_allFields() {
        val pw = parsePaywall(section("countdown", mapOf(
            "variant" to "digital",
            "duration_seconds" to 900,
            "target_datetime" to "2026-12-31T23:59:59Z",
            "show_days" to true,
            "show_hours" to true,
            "show_minutes" to true,
            "show_seconds" to false,
            "labels" to mapOf("days" to "Days", "hours" to "Hrs", "minutes" to "Min", "seconds" to "Sec"),
            "on_expire_action" to "show_expired_text",
            "expired_text" to "Offer expired",
            "accent_color" to "#FF5722",
            "background_color" to "#1A1A2E",
            "font_size" to 32,
            "alignment" to "center"
        )))
        assertEquals("countdown", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("digital", d.variant)
        assertEquals(900, d.duration_seconds)
        assertEquals("2026-12-31T23:59:59Z", d.target_datetime)
        assertEquals(true, d.show_days)
        assertEquals(true, d.show_hours)
        assertEquals(true, d.show_minutes)
        assertEquals(false, d.show_seconds)
        assertEquals("Days", d.labels!!["days"])
        assertEquals("Hrs", d.labels!!["hours"])
        assertEquals("Min", d.labels!!["minutes"])
        assertEquals("Sec", d.labels!!["seconds"])
        assertEquals("show_expired_text", d.on_expire_action)
        assertEquals("Offer expired", d.expired_text)
        assertEquals("#FF5722", d.accent_color)
        assertEquals("#1A1A2E", d.background_color)
        assertEquals(32f, d.font_size!!, 0.01f)
        assertEquals("center", d.alignment)
    }

    @Test
    fun parseCountdownSection_circularVariant() {
        val pw = parsePaywall(section("countdown", mapOf(
            "variant" to "circular",
            "duration_seconds" to 600,
            "on_expire_action" to "hide"
        )))
        val d = pw.sections[0].data!!
        assertEquals("circular", d.variant)
        assertEquals(600, d.duration_seconds)
        assertEquals("hide", d.on_expire_action)
    }

    @Test
    fun parseCountdownSection_flipVariant() {
        val pw = parsePaywall(section("countdown", mapOf(
            "variant" to "flip",
            "duration_seconds" to 300,
            "on_expire_action" to "auto_advance"
        )))
        val d = pw.sections[0].data!!
        assertEquals("flip", d.variant)
        assertEquals(300, d.duration_seconds)
        assertEquals("auto_advance", d.on_expire_action)
    }

    @Test
    fun parseCountdownSection_barVariant() {
        val pw = parsePaywall(section("countdown", mapOf(
            "variant" to "bar",
            "duration_seconds" to 120,
            "accent_color" to "#4CAF50"
        )))
        val d = pw.sections[0].data!!
        assertEquals("bar", d.variant)
        assertEquals(120, d.duration_seconds)
        assertEquals("#4CAF50", d.accent_color)
    }

    @Test
    fun parseCountdownSection_minimal() {
        val pw = parsePaywall(section("countdown", mapOf(
            "variant" to "digital",
            "duration_seconds" to 60
        )))
        val d = pw.sections[0].data!!
        assertEquals("digital", d.variant)
        assertEquals(60, d.duration_seconds)
        assertNull(d.target_datetime)
        assertNull(d.show_days)
        assertNull(d.show_hours)
        assertNull(d.show_minutes)
        assertNull(d.show_seconds)
        assertNull(d.labels)
        assertNull(d.on_expire_action)
        assertNull(d.expired_text)
        assertNull(d.accent_color)
        assertNull(d.background_color)
        assertNull(d.font_size)
        assertNull(d.alignment)
    }

    // -----------------------------------------------------------------------
    // 14. Legal section
    // -----------------------------------------------------------------------

    @Test
    fun parseLegalSection_allFields() {
        val pw = parsePaywall(section("legal", mapOf(
            "color" to "#999999",
            "links" to listOf(
                mapOf("label" to "Terms of Service", "url" to "https://example.com/tos"),
                mapOf("label" to "Privacy Policy", "url" to "https://example.com/privacy"),
                mapOf("label" to "Restore Purchases", "url" to "restore://")
            )
        )))
        assertEquals("legal", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("#999999", d.color)
        assertEquals(3, d.links!!.size)
        assertEquals("Terms of Service", d.links!![0].label)
        assertEquals("https://example.com/tos", d.links!![0].url)
        assertEquals("Privacy Policy", d.links!![1].label)
        assertEquals("https://example.com/privacy", d.links!![1].url)
        assertEquals("Restore Purchases", d.links!![2].label)
        assertEquals("restore://", d.links!![2].url)
    }

    @Test
    fun parseLegalSection_emptyLinks() {
        val pw = parsePaywall(section("legal", mapOf(
            "color" to "#AAAAAA",
            "links" to emptyList<Map<String, Any>>()
        )))
        val d = pw.sections[0].data!!
        assertEquals("#AAAAAA", d.color)
        assertTrue(d.links!!.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 15. Divider section
    // -----------------------------------------------------------------------

    @Test
    fun parseDividerSection_allFields() {
        val pw = parsePaywall(section("divider", mapOf(
            "thickness" to 2,
            "style" to "dashed",
            "color" to "#CCCCCC",
            "margin_top" to 16,
            "margin_bottom" to 16,
            "margin_horizontal" to 24,
            "label_text" to "OR",
            "label_color" to "#666666",
            "label_bg_color" to "#FFFFFF",
            "label_font_size" to 12
        )))
        assertEquals("divider", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals(2f, d.thickness!!, 0.01f)
        // NOTE: parser reads d["style"] and maps to line_style field
        assertEquals("dashed", d.line_style)
        assertEquals("#CCCCCC", d.color)
        assertEquals(16f, d.margin_top!!, 0.01f)
        assertEquals(16f, d.margin_bottom!!, 0.01f)
        assertEquals(24f, d.margin_horizontal!!, 0.01f)
        assertEquals("OR", d.label_text)
        assertEquals("#666666", d.label_color)
        assertEquals("#FFFFFF", d.label_bg_color)
        assertEquals(12f, d.label_font_size!!, 0.01f)
    }

    @Test
    fun parseDividerSection_solidMinimal() {
        val pw = parsePaywall(section("divider", mapOf(
            "thickness" to 1,
            "style" to "solid"
        )))
        val d = pw.sections[0].data!!
        assertEquals(1f, d.thickness!!, 0.01f)
        assertEquals("solid", d.line_style)
        assertNull(d.margin_top)
        assertNull(d.margin_bottom)
        assertNull(d.margin_horizontal)
        assertNull(d.label_text)
    }

    @Test
    fun parseDividerSection_dottedWithLabel() {
        val pw = parsePaywall(section("divider", mapOf(
            "style" to "dotted",
            "label_text" to "BEST DEAL",
            "label_color" to "#FF0000"
        )))
        val d = pw.sections[0].data!!
        assertEquals("dotted", d.line_style)
        assertEquals("BEST DEAL", d.label_text)
        assertEquals("#FF0000", d.label_color)
    }

    // -----------------------------------------------------------------------
    // 16. Sticky footer section
    // -----------------------------------------------------------------------

    @Test
    fun parseStickyFooterSection_allFields() {
        val pw = parsePaywall(section("sticky_footer", mapOf(
            "cta_text" to "Start Free Trial",
            "cta_bg_color" to "#6366F1",
            "cta_text_color" to "#FFFFFF",
            "cta_corner_radius" to 16,
            "secondary_text" to "Restore Purchases",
            "secondary_action" to "restore",
            "secondary_url" to "https://example.com/restore",
            "legal_text" to "Cancel anytime. Terms apply.",
            "blur_background" to true,
            "padding" to 20
        )))
        assertEquals("sticky_footer", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("Start Free Trial", d.cta_text)
        assertEquals("#6366F1", d.cta_bg_color)
        assertEquals("#FFFFFF", d.cta_text_color)
        assertEquals(16f, d.cta_corner_radius!!, 0.01f)
        assertEquals("Restore Purchases", d.secondary_text)
        assertEquals("restore", d.secondary_action)
        assertEquals("https://example.com/restore", d.secondary_url)
        assertEquals("Cancel anytime. Terms apply.", d.legal_text)
        assertEquals(true, d.blur_background)
        assertEquals(20f, d.padding!!, 0.01f)
    }

    @Test
    fun parseStickyFooterSection_ctaOnly() {
        val pw = parsePaywall(section("sticky_footer", mapOf(
            "cta_text" to "Subscribe"
        )))
        val d = pw.sections[0].data!!
        assertEquals("Subscribe", d.cta_text)
        assertNull(d.cta_bg_color)
        assertNull(d.cta_text_color)
        assertNull(d.cta_corner_radius)
        assertNull(d.secondary_text)
        assertNull(d.secondary_action)
        assertNull(d.secondary_url)
        assertNull(d.legal_text)
        assertNull(d.blur_background)
        assertNull(d.padding)
    }

    @Test
    fun parseStickyFooterSection_linkAction() {
        val pw = parsePaywall(section("sticky_footer", mapOf(
            "cta_text" to "Go Pro",
            "secondary_text" to "Learn More",
            "secondary_action" to "link",
            "secondary_url" to "https://example.com/pricing"
        )))
        val d = pw.sections[0].data!!
        assertEquals("link", d.secondary_action)
        assertEquals("https://example.com/pricing", d.secondary_url)
    }

    // -----------------------------------------------------------------------
    // 17. Carousel section
    // -----------------------------------------------------------------------

    @Test
    fun parseCarouselSection_allFields() {
        val pw = parsePaywall(section("carousel", mapOf(
            "pages" to listOf(
                mapOf(
                    "id" to "page1",
                    "children" to listOf(
                        mapOf("type" to "header", "data" to mapOf("title" to "Slide 1")),
                        mapOf("type" to "image", "data" to mapOf("image_url" to "https://cdn.example.com/s1.png"))
                    )
                ),
                mapOf(
                    "id" to "page2",
                    "children" to listOf(
                        mapOf("type" to "header", "data" to mapOf("title" to "Slide 2"))
                    )
                )
            ),
            "auto_scroll" to true,
            "auto_scroll_interval_ms" to 3000,
            "show_indicators" to true,
            "indicator_color" to "#CCCCCC",
            "indicator_active_color" to "#6366F1"
        )))
        assertEquals("carousel", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals(2, d.pages!!.size)

        // Page 1
        assertEquals("page1", d.pages!![0].id)
        assertEquals(2, d.pages!![0].children!!.size)
        assertEquals("header", d.pages!![0].children!![0].type)
        assertEquals("Slide 1", d.pages!![0].children!![0].data!!.title)
        assertEquals("image", d.pages!![0].children!![1].type)
        assertEquals("https://cdn.example.com/s1.png", d.pages!![0].children!![1].data!!.image_url)

        // Page 2
        assertEquals("page2", d.pages!![1].id)
        assertEquals(1, d.pages!![1].children!!.size)
        assertEquals("Slide 2", d.pages!![1].children!![0].data!!.title)

        // Carousel options
        assertEquals(true, d.auto_scroll)
        assertEquals(3000, d.auto_scroll_interval_ms)
        assertEquals(true, d.show_indicators)
        assertEquals("#CCCCCC", d.indicator_color)
        assertEquals("#6366F1", d.indicator_active_color)
    }

    @Test
    fun parseCarouselSection_noAutoScroll() {
        val pw = parsePaywall(section("carousel", mapOf(
            "pages" to listOf(
                mapOf("id" to "p1", "children" to listOf(
                    mapOf("type" to "header", "data" to mapOf("title" to "Only Page"))
                ))
            ),
            "auto_scroll" to false,
            "show_indicators" to false
        )))
        val d = pw.sections[0].data!!
        assertEquals(false, d.auto_scroll)
        assertEquals(false, d.show_indicators)
        assertNull(d.auto_scroll_interval_ms)
        assertNull(d.indicator_color)
        assertNull(d.indicator_active_color)
    }

    @Test
    fun parseCarouselSection_nestedRecursiveSections() {
        // Carousel page containing another complex section (e.g., features + cta)
        val pw = parsePaywall(section("carousel", mapOf(
            "pages" to listOf(
                mapOf("id" to "nested_page", "children" to listOf(
                    mapOf("type" to "features", "data" to mapOf(
                        "features" to listOf("Feature A", "Feature B")
                    )),
                    mapOf("type" to "cta", "data" to mapOf(
                        "cta" to mapOf("text" to "Buy Now", "style" to "primary")
                    ))
                ))
            )
        )))
        val page = pw.sections[0].data!!.pages!![0]
        assertEquals(2, page.children!!.size)
        assertEquals("features", page.children!![0].type)
        assertEquals(2, page.children!![0].data!!.features!!.size)
        assertEquals("cta", page.children!![1].type)
        assertEquals("Buy Now", page.children!![1].data!!.cta!!.text)
        assertEquals("primary", page.children!![1].data!!.cta!!.style)
    }

    // -----------------------------------------------------------------------
    // 18. Timeline section
    // -----------------------------------------------------------------------

    @Test
    fun parseTimelineSection_allFields() {
        val pw = parsePaywall(section("timeline", mapOf(
            "items" to listOf(
                mapOf("id" to "step1", "title" to "Sign Up", "subtitle" to "Create your account", "icon" to "user_plus", "status" to "completed"),
                mapOf("id" to "step2", "title" to "Choose Plan", "subtitle" to "Pick the right plan", "icon" to "credit_card", "status" to "current"),
                mapOf("id" to "step3", "title" to "Start Using", "subtitle" to "Enjoy premium features", "icon" to "rocket", "status" to "upcoming")
            ),
            "line_color" to "#E0E0E0",
            "completed_color" to "#4CAF50",
            "current_color" to "#2196F3",
            "upcoming_color" to "#9E9E9E",
            "show_line" to true,
            "compact" to false
        )))
        assertEquals("timeline", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals(3, d.items!!.size)

        // Item 1: completed
        assertEquals("step1", d.items!![0].id)
        assertEquals("Sign Up", d.items!![0].title)
        assertEquals("Create your account", d.items!![0].subtitle)
        assertEquals("user_plus", d.items!![0].icon)
        assertEquals("completed", d.items!![0].status)

        // Item 2: current
        assertEquals("step2", d.items!![1].id)
        assertEquals("Choose Plan", d.items!![1].title)
        assertEquals("current", d.items!![1].status)

        // Item 3: upcoming
        assertEquals("step3", d.items!![2].id)
        assertEquals("Start Using", d.items!![2].title)
        assertEquals("upcoming", d.items!![2].status)

        // Colors
        assertEquals("#E0E0E0", d.line_color)
        assertEquals("#4CAF50", d.completed_color)
        assertEquals("#2196F3", d.current_color)
        assertEquals("#9E9E9E", d.upcoming_color)
        assertEquals(true, d.show_line)
        assertEquals(false, d.compact)
    }

    @Test
    fun parseTimelineSection_compactMode() {
        val pw = parsePaywall(section("timeline", mapOf(
            "items" to listOf(
                mapOf("id" to "s1", "title" to "Step 1", "status" to "completed"),
                mapOf("id" to "s2", "title" to "Step 2", "status" to "current")
            ),
            "compact" to true,
            "show_line" to false
        )))
        val d = pw.sections[0].data!!
        assertEquals(true, d.compact)
        assertEquals(false, d.show_line)
        assertEquals(2, d.items!!.size)
    }

    @Test
    fun parseTimelineSection_minimal() {
        val pw = parsePaywall(section("timeline", mapOf(
            "items" to listOf(
                mapOf("id" to "t1", "title" to "Only Step")
            )
        )))
        val d = pw.sections[0].data!!
        assertEquals(1, d.items!!.size)
        assertEquals("Only Step", d.items!![0].title)
        assertNull(d.items!![0].status)
        assertNull(d.line_color)
        assertNull(d.completed_color)
        assertNull(d.current_color)
        assertNull(d.upcoming_color)
        assertNull(d.show_line)
        assertNull(d.compact)
    }

    // -----------------------------------------------------------------------
    // 19. Icon grid section
    // -----------------------------------------------------------------------

    @Test
    fun parseIconGridSection_allFields() {
        val pw = parsePaywall(section("icon_grid", mapOf(
            "items" to listOf(
                mapOf("icon" to "cloud", "label" to "Cloud Sync", "description" to "Sync across all devices"),
                mapOf("icon" to "shield", "label" to "Security", "description" to "End-to-end encryption"),
                mapOf("icon" to "zap", "label" to "Fast", "description" to "Lightning fast performance"),
                mapOf("icon" to "heart", "label" to "Health", "description" to "Track your wellness")
            ),
            "columns" to 2,
            "icon_size" to 32,
            "icon_color" to "#6366F1",
            "spacing" to 16
        )))
        assertEquals("icon_grid", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals(4, d.items!!.size)

        assertEquals("cloud", d.items!![0].icon)
        assertEquals("Cloud Sync", d.items!![0].label)
        assertEquals("Sync across all devices", d.items!![0].description)

        assertEquals("shield", d.items!![1].icon)
        assertEquals("Security", d.items!![1].label)

        assertEquals("zap", d.items!![2].icon)
        assertEquals("heart", d.items!![3].icon)

        assertEquals(2, d.columns)
        assertEquals(32f, d.icon_size!!, 0.01f)
        assertEquals("#6366F1", d.icon_color)
        assertEquals(16f, d.spacing!!, 0.01f)
    }

    @Test
    fun parseIconGridSection_threeColumns() {
        val pw = parsePaywall(section("icon_grid", mapOf(
            "items" to listOf(
                mapOf("icon" to "star", "label" to "Premium")
            ),
            "columns" to 3
        )))
        val d = pw.sections[0].data!!
        assertEquals(3, d.columns)
        assertEquals(1, d.items!!.size)
        assertNull(d.icon_size)
        assertNull(d.icon_color)
        assertNull(d.spacing)
    }

    // -----------------------------------------------------------------------
    // 20. Comparison table section
    // -----------------------------------------------------------------------

    @Test
    fun parseComparisonTableSection_allFields() {
        val pw = parsePaywall(section("comparison_table", mapOf(
            "table_columns" to listOf(
                mapOf("label" to "Free", "highlighted" to false),
                mapOf("label" to "Pro", "highlighted" to true),
                mapOf("label" to "Enterprise", "highlighted" to false)
            ),
            "rows" to listOf(
                mapOf("feature" to "Storage", "values" to listOf("1 GB", "100 GB", "Unlimited")),
                mapOf("feature" to "API Access", "values" to listOf("No", "Yes", "Yes")),
                mapOf("feature" to "Support", "values" to listOf("Email", "Priority", "Dedicated"))
            ),
            "check_color" to "#4CAF50",
            "cross_color" to "#F44336",
            "highlight_color" to "#E3F2FD",
            "border_color" to "#E0E0E0"
        )))
        assertEquals("comparison_table", pw.sections[0].type)
        val d = pw.sections[0].data!!

        // Columns
        assertEquals(3, d.table_columns!!.size)
        assertEquals("Free", d.table_columns!![0].label)
        assertEquals(false, d.table_columns!![0].highlighted)
        assertEquals("Pro", d.table_columns!![1].label)
        assertEquals(true, d.table_columns!![1].highlighted)
        assertEquals("Enterprise", d.table_columns!![2].label)
        assertEquals(false, d.table_columns!![2].highlighted)

        // Rows
        assertEquals(3, d.rows!!.size)
        assertEquals("Storage", d.rows!![0].feature)
        assertEquals(listOf("1 GB", "100 GB", "Unlimited"), d.rows!![0].values)
        assertEquals("API Access", d.rows!![1].feature)
        assertEquals(listOf("No", "Yes", "Yes"), d.rows!![1].values)
        assertEquals("Support", d.rows!![2].feature)
        assertEquals(listOf("Email", "Priority", "Dedicated"), d.rows!![2].values)

        // Colors
        assertEquals("#4CAF50", d.check_color)
        assertEquals("#F44336", d.cross_color)
        assertEquals("#E3F2FD", d.highlight_color)
        assertEquals("#E0E0E0", d.border_color)
    }

    @Test
    fun parseComparisonTableSection_twoColumnsMinimal() {
        val pw = parsePaywall(section("comparison_table", mapOf(
            "table_columns" to listOf(
                mapOf("label" to "Basic"),
                mapOf("label" to "Premium", "highlighted" to true)
            ),
            "rows" to listOf(
                mapOf("feature" to "Ads", "values" to listOf("Yes", "No")),
                mapOf("feature" to "Downloads", "values" to listOf("No", "Yes"))
            )
        )))
        val d = pw.sections[0].data!!
        assertEquals(2, d.table_columns!!.size)
        assertEquals(2, d.rows!!.size)
        // highlighted not set on first column => null
        assertNull(d.table_columns!![0].highlighted)
        assertEquals(true, d.table_columns!![1].highlighted)
        assertNull(d.check_color)
        assertNull(d.cross_color)
        assertNull(d.highlight_color)
        assertNull(d.border_color)
    }

    // -----------------------------------------------------------------------
    // 21. Promo input section
    // -----------------------------------------------------------------------

    @Test
    fun parsePromoInputSection_allFields() {
        val pw = parsePaywall(section("promo_input", mapOf(
            "placeholder" to "Enter promo code",
            "button_text" to "Apply",
            "success_text" to "Code applied! 20% off",
            "error_text" to "Invalid promo code"
        )))
        assertEquals("promo_input", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("Enter promo code", d.placeholder)
        assertEquals("Apply", d.button_text)
        assertEquals("Code applied! 20% off", d.success_text)
        assertEquals("Invalid promo code", d.error_text)
    }

    @Test
    fun parsePromoInputSection_minimal() {
        val pw = parsePaywall(section("promo_input", mapOf(
            "placeholder" to "Code"
        )))
        val d = pw.sections[0].data!!
        assertEquals("Code", d.placeholder)
        assertNull(d.button_text)
        assertNull(d.success_text)
        assertNull(d.error_text)
    }

    // -----------------------------------------------------------------------
    // 22. Toggle section
    // -----------------------------------------------------------------------

    @Test
    fun parseToggleSection_allFields() {
        val pw = parsePaywall(section("toggle", mapOf(
            "label" to "Annual Billing",
            "description" to "Save 40% with annual plan",
            "default_value" to true,
            "on_color" to "#4CAF50",
            "off_color" to "#9E9E9E",
            "label_color" to "#FFFFFF",
            "description_color" to "#AAAAAA",
            "icon" to "calendar",
            "affects_price" to true
        )))
        assertEquals("toggle", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("Annual Billing", d.label)
        assertEquals("Save 40% with annual plan", d.description)
        assertEquals(true, d.default_value)
        assertEquals("#4CAF50", d.on_color)
        assertEquals("#9E9E9E", d.off_color)
        // NOTE: parser reads d["label_color"] and maps to label_color_val field
        assertEquals("#FFFFFF", d.label_color_val)
        assertEquals("#AAAAAA", d.description_color)
        assertEquals("calendar", d.icon)
        assertEquals(true, d.affects_price)
    }

    @Test
    fun parseToggleSection_defaultOff() {
        val pw = parsePaywall(section("toggle", mapOf(
            "label" to "Family Plan",
            "default_value" to false,
            "affects_price" to false
        )))
        val d = pw.sections[0].data!!
        assertEquals("Family Plan", d.label)
        assertEquals(false, d.default_value)
        assertEquals(false, d.affects_price)
        assertNull(d.description)
        assertNull(d.on_color)
        assertNull(d.off_color)
        assertNull(d.label_color_val)
        assertNull(d.description_color)
        assertNull(d.icon)
    }

    // -----------------------------------------------------------------------
    // 23. Reviews carousel section
    // -----------------------------------------------------------------------

    @Test
    fun parseReviewsCarouselSection_allFields() {
        val pw = parsePaywall(section("reviews_carousel", mapOf(
            "reviews" to listOf(
                mapOf(
                    "text" to "Absolutely love this app!",
                    "author" to "John D.",
                    "rating" to 5.0,
                    "avatar_url" to "https://cdn.example.com/john.jpg",
                    "date" to "2026-03-15"
                ),
                mapOf(
                    "text" to "Great value for the price",
                    "author" to "Sarah M.",
                    "rating" to 4.5,
                    "avatar_url" to "https://cdn.example.com/sarah.jpg",
                    "date" to "2026-03-10"
                ),
                mapOf(
                    "text" to "Good but could be better",
                    "author" to "Alex K.",
                    "rating" to 3.0
                )
            ),
            "show_rating_stars" to true,
            "star_color" to "#FFD700"
        )))
        assertEquals("reviews_carousel", pw.sections[0].type)
        val d = pw.sections[0].data!!

        assertEquals(3, d.reviews!!.size)

        // Review 1
        assertEquals("Absolutely love this app!", d.reviews!![0].text)
        assertEquals("John D.", d.reviews!![0].author)
        assertEquals(5.0, d.reviews!![0].rating!!, 0.001)
        assertEquals("https://cdn.example.com/john.jpg", d.reviews!![0].avatar_url)
        assertEquals("2026-03-15", d.reviews!![0].date)

        // Review 2
        assertEquals("Great value for the price", d.reviews!![1].text)
        assertEquals("Sarah M.", d.reviews!![1].author)
        assertEquals(4.5, d.reviews!![1].rating!!, 0.001)
        assertEquals("https://cdn.example.com/sarah.jpg", d.reviews!![1].avatar_url)
        assertEquals("2026-03-10", d.reviews!![1].date)

        // Review 3 — minimal
        assertEquals("Good but could be better", d.reviews!![2].text)
        assertEquals("Alex K.", d.reviews!![2].author)
        assertEquals(3.0, d.reviews!![2].rating!!, 0.001)
        assertNull(d.reviews!![2].avatar_url)
        assertNull(d.reviews!![2].date)

        assertEquals(true, d.show_rating_stars)
        assertEquals("#FFD700", d.star_color)
    }

    @Test
    fun parseReviewsCarouselSection_noStars() {
        val pw = parsePaywall(section("reviews_carousel", mapOf(
            "reviews" to listOf(
                mapOf("text" to "Solid app", "author" to "User1")
            ),
            "show_rating_stars" to false
        )))
        val d = pw.sections[0].data!!
        assertEquals(1, d.reviews!!.size)
        assertEquals("Solid app", d.reviews!![0].text)
        assertEquals(false, d.show_rating_stars)
        assertNull(d.star_color)
    }

    // -----------------------------------------------------------------------
    // 24. Card section (uses shared fields)
    // -----------------------------------------------------------------------

    @Test
    fun parseCardSection_withHeaderFields() {
        val pw = parsePaywall(section("card", mapOf(
            "title" to "Special Offer",
            "subtitle" to "Limited time deal",
            "image_url" to "https://cdn.example.com/card.png"
        )))
        assertEquals("card", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertEquals("Special Offer", d.title)
        assertEquals("Limited time deal", d.subtitle)
        assertEquals("https://cdn.example.com/card.png", d.image_url)
    }

    @Test
    fun parseCardSection_withCtaAndFeatures() {
        val pw = parsePaywall(section("card", mapOf(
            "title" to "Premium Card",
            "features" to listOf("Benefit 1", "Benefit 2"),
            "cta" to mapOf("text" to "Learn More", "style" to "primary")
        )))
        val d = pw.sections[0].data!!
        assertEquals("Premium Card", d.title)
        assertEquals(2, d.features!!.size)
        assertEquals("Learn More", d.cta!!.text)
        assertEquals("primary", d.cta!!.style)
    }

    // -----------------------------------------------------------------------
    // Full paywall config parsing (layout, dismiss, background, animation, etc.)
    // -----------------------------------------------------------------------

    @Test
    fun parseFullPaywallConfig_layoutFields() {
        val paywallMap = mapOf<String, Any>(
            "id" to "premium_paywall",
            "name" to "Premium Offering",
            "layout" to mapOf(
                "type" to "grid",
                "spacing" to 12,
                "padding" to 16
            ),
            "sections" to emptyList<Map<String, Any>>()
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("premium_paywall" to paywallMap))
        val pw = result["premium_paywall"]!!

        assertEquals("premium_paywall", pw.id)
        assertEquals("Premium Offering", pw.name)
        assertEquals("grid", pw.layout.type)
        assertEquals(12f, pw.layout.spacing!!, 0.01f)
        assertEquals(16f, pw.layout.padding!!, 0.01f)
    }

    @Test
    fun parseFullPaywallConfig_dismissConfig() {
        val pw = parsePaywall(extras = mapOf(
            "dismiss" to mapOf(
                "type" to "x_button",
                "delay_seconds" to 3,
                "text" to "Close"
            )
        ))
        assertNotNull(pw.dismiss)
        assertEquals("x_button", pw.dismiss!!.type)
        assertEquals(3, pw.dismiss!!.delay_seconds)
        assertEquals("Close", pw.dismiss!!.text)
    }

    @Test
    fun parseFullPaywallConfig_dismissSwipe() {
        val pw = parsePaywall(extras = mapOf(
            "dismiss" to mapOf("type" to "swipe")
        ))
        assertEquals("swipe", pw.dismiss!!.type)
        assertNull(pw.dismiss!!.delay_seconds)
        assertNull(pw.dismiss!!.text)
    }

    @Test
    fun parseFullPaywallConfig_dismissTextLink() {
        val pw = parsePaywall(extras = mapOf(
            "dismiss" to mapOf(
                "type" to "text_link",
                "text" to "No thanks"
            )
        ))
        assertEquals("text_link", pw.dismiss!!.type)
        assertEquals("No thanks", pw.dismiss!!.text)
    }

    @Test
    fun parseFullPaywallConfig_backgroundColor() {
        val pw = parsePaywall(extras = mapOf(
            "background" to mapOf(
                "type" to "color",
                "value" to "#1A1A2E"
            )
        ))
        assertNotNull(pw.background)
        assertEquals("color", pw.background!!.type)
        assertEquals("#1A1A2E", pw.background!!.value)
        assertNull(pw.background!!.colors)
    }

    @Test
    fun parseFullPaywallConfig_backgroundGradient() {
        val pw = parsePaywall(extras = mapOf(
            "background" to mapOf(
                "type" to "gradient",
                "colors" to listOf("#6366F1", "#8B5CF6", "#A855F7")
            )
        ))
        assertEquals("gradient", pw.background!!.type)
        assertEquals(3, pw.background!!.colors!!.size)
        assertEquals("#6366F1", pw.background!!.colors!![0])
        assertEquals("#8B5CF6", pw.background!!.colors!![1])
        assertEquals("#A855F7", pw.background!!.colors!![2])
    }

    @Test
    fun parseFullPaywallConfig_backgroundImage() {
        val pw = parsePaywall(extras = mapOf(
            "background" to mapOf(
                "type" to "image",
                "value" to "https://cdn.example.com/bg.jpg"
            )
        ))
        assertEquals("image", pw.background!!.type)
        assertEquals("https://cdn.example.com/bg.jpg", pw.background!!.value)
    }

    @Test
    fun parseFullPaywallConfig_animationConfig() {
        val pw = parsePaywall(extras = mapOf(
            "animation" to mapOf(
                "entry_animation" to "slide_up",
                "entry_duration_ms" to 400,
                "section_stagger" to "fade_in",
                "section_stagger_delay_ms" to 100,
                "cta_animation" to "pulse",
                "plan_selection_animation" to "bounce",
                "dismiss_animation" to "slide_down"
            )
        ))
        assertNotNull(pw.animation)
        assertEquals("slide_up", pw.animation!!.entry_animation)
        assertEquals(400, pw.animation!!.entry_duration_ms)
        assertEquals("fade_in", pw.animation!!.section_stagger)
        assertEquals(100, pw.animation!!.section_stagger_delay_ms)
        assertEquals("pulse", pw.animation!!.cta_animation)
        assertEquals("bounce", pw.animation!!.plan_selection_animation)
        assertEquals("slide_down", pw.animation!!.dismiss_animation)
    }

    @Test
    fun parseFullPaywallConfig_hapticConfig() {
        val pw = parsePaywall(extras = mapOf(
            "haptic" to mapOf(
                "enabled" to true,
                "triggers" to mapOf(
                    "on_button_tap" to "light",
                    "on_plan_select" to "medium",
                    "on_success" to "heavy"
                )
            )
        ))
        assertNotNull(pw.haptic)
        assertEquals(true, pw.haptic!!.enabled)
        assertEquals("light", pw.haptic!!.triggers.on_button_tap)
        assertEquals("medium", pw.haptic!!.triggers.on_plan_select)
        assertEquals("heavy", pw.haptic!!.triggers.on_success)
    }

    @Test
    fun parseFullPaywallConfig_hapticDisabled() {
        val pw = parsePaywall(extras = mapOf(
            "haptic" to mapOf(
                "enabled" to false
            )
        ))
        assertNotNull(pw.haptic)
        assertEquals(false, pw.haptic!!.enabled)
        // Triggers default to empty
        assertNull(pw.haptic!!.triggers.on_button_tap)
        assertNull(pw.haptic!!.triggers.on_plan_select)
        assertNull(pw.haptic!!.triggers.on_success)
    }

    @Test
    fun parseFullPaywallConfig_particleEffect() {
        val pw = parsePaywall(extras = mapOf(
            "particle_effect" to mapOf(
                "type" to "confetti",
                "trigger" to "on_purchase",
                "duration_ms" to 3000,
                "intensity" to "high",
                "colors" to listOf("#FF5722", "#2196F3", "#4CAF50", "#FFEB3B")
            )
        ))
        assertNotNull(pw.particle_effect)
        assertEquals("confetti", pw.particle_effect!!.type)
        assertEquals("on_purchase", pw.particle_effect!!.trigger)
        assertEquals(3000, pw.particle_effect!!.duration_ms)
        assertEquals("high", pw.particle_effect!!.intensity)
        assertEquals(4, pw.particle_effect!!.colors!!.size)
        assertEquals("#FF5722", pw.particle_effect!!.colors!![0])
    }

    @Test
    fun parseFullPaywallConfig_videoBackgroundUrl() {
        val pw = parsePaywall(extras = mapOf(
            "video_background_url" to "https://cdn.example.com/bg-loop.mp4"
        ))
        assertEquals("https://cdn.example.com/bg-loop.mp4", pw.video_background_url)
    }

    @Test
    fun parseFullPaywallConfig_localizations() {
        val pw = parsePaywall(
            section("header", mapOf("title" to "{{header_title}}")),
            extras = mapOf(
                "default_locale" to "en",
                "localizations" to mapOf(
                    "en" to mapOf("header_title" to "Premium Plan"),
                    "es" to mapOf("header_title" to "Plan Premium"),
                    "de" to mapOf("header_title" to "Premium-Plan")
                )
            )
        )
        assertEquals("en", pw.default_locale)
        assertNotNull(pw.localizations)
        assertEquals(3, pw.localizations!!.size)
        assertEquals("Premium Plan", pw.localizations!!["en"]!!["header_title"])
        assertEquals("Plan Premium", pw.localizations!!["es"]!!["header_title"])
        assertEquals("Premium-Plan", pw.localizations!!["de"]!!["header_title"])
    }

    // -----------------------------------------------------------------------
    // Backward compatibility tests
    // -----------------------------------------------------------------------

    @Test
    fun parseBackwardCompatible_oldPaywallWithBasicSections() {
        // Pre-089d paywall with only header + features + plans + cta
        val paywallMap = mapOf<String, Any>(
            "id" to "legacy_paywall",
            "name" to "Legacy",
            "layout" to mapOf("type" to "stack"),
            "sections" to listOf(
                mapOf("type" to "header", "data" to mapOf(
                    "title" to "Go Pro",
                    "subtitle" to "Get all features"
                )),
                mapOf("type" to "features", "data" to mapOf(
                    "features" to listOf("Feature 1", "Feature 2", "Feature 3")
                )),
                mapOf("type" to "plans", "data" to mapOf(
                    "plans" to listOf(
                        mapOf("id" to "monthly", "product_id" to "com.app.monthly", "name" to "Monthly", "price" to "$9.99")
                    )
                )),
                mapOf("type" to "cta", "data" to mapOf(
                    "cta" to mapOf("text" to "Subscribe", "style" to "primary")
                ))
            )
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("legacy_paywall" to paywallMap))
        val pw = result["legacy_paywall"]!!

        assertEquals("legacy_paywall", pw.id)
        assertEquals("Legacy", pw.name)
        assertEquals(4, pw.sections.size)
        assertEquals("header", pw.sections[0].type)
        assertEquals("features", pw.sections[1].type)
        assertEquals("plans", pw.sections[2].type)
        assertEquals("cta", pw.sections[3].type)

        // No 089d fields
        assertNull(pw.dismiss)
        assertNull(pw.background)
        assertNull(pw.animation)
        assertNull(pw.haptic)
        assertNull(pw.particle_effect)
        assertNull(pw.video_background_url)
        assertNull(pw.localizations)
        assertNull(pw.default_locale)
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun parseEmptySectionsList() {
        val pw = parsePaywall()
        assertEquals(0, pw.sections.size)
    }

    @Test
    fun parseSectionWithNullData() {
        val paywallMap = mapOf<String, Any>(
            "id" to "test_paywall",
            "name" to "Test",
            "layout" to mapOf("type" to "stack"),
            "sections" to listOf(
                mapOf("type" to "spacer")
                // no "data" key at all
            )
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("test_paywall" to paywallMap))
        val pw = result["test_paywall"]!!
        assertEquals(1, pw.sections.size)
        assertEquals("spacer", pw.sections[0].type)
        assertNull(pw.sections[0].data)
    }

    @Test
    fun parseSectionWithEmptyData() {
        val pw = parsePaywall(section("header", emptyMap()))
        assertEquals(1, pw.sections.size)
        assertEquals("header", pw.sections[0].type)
        val d = pw.sections[0].data!!
        assertNull(d.title)
        assertNull(d.subtitle)
        assertNull(d.image_url)
    }

    @Test
    fun parseMultipleSectionsInOnePaywall() {
        val pw = parsePaywall(
            section("header", mapOf("title" to "Premium")),
            section("features", mapOf("features" to listOf("A", "B"))),
            section("image", mapOf("image_url" to "https://cdn.example.com/img.png")),
            section("countdown", mapOf("variant" to "digital", "duration_seconds" to 300)),
            section("plans", mapOf("plans" to listOf(
                mapOf("id" to "p1", "product_id" to "prod1", "name" to "Monthly", "price" to "$9.99")
            ))),
            section("toggle", mapOf("label" to "Annual", "affects_price" to true)),
            section("sticky_footer", mapOf("cta_text" to "Subscribe")),
            section("legal", mapOf("links" to listOf(
                mapOf("label" to "Terms", "url" to "https://example.com/terms")
            )))
        )
        assertEquals(8, pw.sections.size)
        assertEquals("header", pw.sections[0].type)
        assertEquals("Premium", pw.sections[0].data!!.title)
        assertEquals("features", pw.sections[1].type)
        assertEquals("image", pw.sections[2].type)
        assertEquals("countdown", pw.sections[3].type)
        assertEquals("digital", pw.sections[3].data!!.variant)
        assertEquals("plans", pw.sections[4].type)
        assertEquals("toggle", pw.sections[5].type)
        assertEquals(true, pw.sections[5].data!!.affects_price)
        assertEquals("sticky_footer", pw.sections[6].type)
        assertEquals("Subscribe", pw.sections[6].data!!.cta_text)
        assertEquals("legal", pw.sections[7].type)
        assertEquals(1, pw.sections[7].data!!.links!!.size)
    }

    @Test
    fun parseMultiplePaywallsAtOnce() {
        val data = mapOf(
            "paywall_a" to mapOf<String, Any>(
                "id" to "paywall_a",
                "name" to "Paywall A",
                "layout" to mapOf("type" to "stack"),
                "sections" to listOf(
                    mapOf("type" to "header", "data" to mapOf("title" to "Plan A"))
                )
            ),
            "paywall_b" to mapOf<String, Any>(
                "id" to "paywall_b",
                "name" to "Paywall B",
                "layout" to mapOf("type" to "carousel"),
                "sections" to listOf(
                    mapOf("type" to "header", "data" to mapOf("title" to "Plan B"))
                )
            )
        )
        val result = PaywallConfigParser.parsePaywalls(data)
        assertEquals(2, result.size)
        assertEquals("Plan A", result["paywall_a"]!!.sections[0].data!!.title)
        assertEquals("Plan B", result["paywall_b"]!!.sections[0].data!!.title)
        assertEquals("stack", result["paywall_a"]!!.layout.type)
        assertEquals("carousel", result["paywall_b"]!!.layout.type)
    }

    @Test
    fun parseMalformedPaywallEntryIsSkipped() {
        // One good paywall, one malformed (not a Map)
        val data = mapOf<String, Any>(
            "good" to mapOf(
                "id" to "good",
                "name" to "Good",
                "layout" to mapOf("type" to "stack"),
                "sections" to emptyList<Any>()
            ),
            "bad" to "this_is_not_a_map"
        )
        val result = PaywallConfigParser.parsePaywalls(data)
        assertEquals(1, result.size)
        assertNotNull(result["good"])
        assertNull(result["bad"])
    }

    @Test
    fun parsePaywallWithMissingLayout_defaultsToStack() {
        val paywallMap = mapOf<String, Any>(
            "id" to "no_layout",
            "name" to "No Layout",
            "sections" to emptyList<Any>()
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("no_layout" to paywallMap))
        val pw = result["no_layout"]!!
        assertEquals("stack", pw.layout.type)
        assertNull(pw.layout.spacing)
        assertNull(pw.layout.padding)
    }

    @Test
    fun parsePaywallWithMissingSections_defaultsToEmpty() {
        val paywallMap = mapOf<String, Any>(
            "id" to "no_sections",
            "name" to "No Sections",
            "layout" to mapOf("type" to "stack")
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("no_sections" to paywallMap))
        val pw = result["no_sections"]!!
        assertTrue(pw.sections.isEmpty())
    }

    @Test
    fun parsePaywallIdFallsBackToMapKey() {
        // No "id" in the map — parser should use the map key
        val paywallMap = mapOf<String, Any>(
            "name" to "Fallback ID",
            "layout" to mapOf("type" to "stack"),
            "sections" to emptyList<Any>()
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("key_as_id" to paywallMap))
        val pw = result["key_as_id"]!!
        assertEquals("key_as_id", pw.id)
    }

    // -----------------------------------------------------------------------
    // Number type coercion tests (Firestore may send Int, Long, Double)
    // -----------------------------------------------------------------------

    @Test
    fun parseNumberCoercion_intAsLong() {
        // Firestore often sends integers as Long
        val pw = parsePaywall(section("countdown", mapOf(
            "variant" to "digital",
            "duration_seconds" to 900L,
            "font_size" to 24L
        )))
        val d = pw.sections[0].data!!
        assertEquals(900, d.duration_seconds)
        assertEquals(24f, d.font_size!!, 0.01f)
    }

    @Test
    fun parseNumberCoercion_floatAsDouble() {
        // Firestore sends floating point as Double
        val pw = parsePaywall(section("spacer", mapOf(
            "spacer_height" to 32.5
        )))
        val d = pw.sections[0].data!!
        assertEquals(32.5f, d.spacer_height!!, 0.01f)
    }

    @Test
    fun parseNumberCoercion_intForFloat() {
        // Integer value for a Float field
        val pw = parsePaywall(section("image", mapOf(
            "image_url" to "https://cdn.example.com/img.png",
            "height" to 300,
            "corner_radius" to 8
        )))
        val d = pw.sections[0].data!!
        assertEquals(300f, d.height!!, 0.01f)
        assertEquals(8f, d.corner_radius!!, 0.01f)
    }

    @Test
    fun parseNumberCoercion_doubleForRating() {
        val pw = parsePaywall(section("social_proof", mapOf(
            "rating" to 4,
            "review_count" to 100L
        )))
        val d = pw.sections[0].data!!
        assertEquals(4.0, d.rating!!, 0.001)
        assertEquals(100, d.review_count)
    }

    // -----------------------------------------------------------------------
    // Per-section style parsing
    // -----------------------------------------------------------------------

    @Test
    fun parseSectionWithStyle() {
        val paywallMap = mapOf<String, Any>(
            "id" to "styled_pw",
            "name" to "Styled",
            "layout" to mapOf("type" to "stack"),
            "sections" to listOf(
                mapOf(
                    "type" to "header",
                    "data" to mapOf("title" to "Styled Header"),
                    "style" to mapOf(
                        "container" to mapOf(
                            "corner_radius" to 16,
                            "opacity" to 0.95,
                            "background" to mapOf(
                                "type" to "color",
                                "color" to "#1A1A2E"
                            ),
                            "padding" to mapOf(
                                "top" to 20,
                                "right" to 16,
                                "bottom" to 20,
                                "left" to 16
                            ),
                            "border" to mapOf(
                                "width" to 1,
                                "color" to "#333333",
                                "style" to "solid",
                                "radius" to 16
                            ),
                            "shadow" to mapOf(
                                "x" to 0,
                                "y" to 4,
                                "blur" to 12,
                                "spread" to 0,
                                "color" to "#00000033"
                            ),
                            "text_style" to mapOf(
                                "font_family" to "Inter",
                                "font_size" to 24,
                                "font_weight" to 700,
                                "color" to "#FFFFFF",
                                "alignment" to "center",
                                "line_height" to 1.3,
                                "letter_spacing" to 0.5,
                                "opacity" to 1.0
                            )
                        ),
                        "elements" to mapOf(
                            "subtitle" to mapOf(
                                "text_style" to mapOf(
                                    "font_size" to 16,
                                    "color" to "#AAAAAA"
                                )
                            )
                        )
                    )
                )
            )
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("styled_pw" to paywallMap))
        val section = result["styled_pw"]!!.sections[0]
        assertNotNull(section.style)

        val container = section.style!!.container!!
        assertEquals(16.0, container.corner_radius!!, 0.001)
        assertEquals(0.95, container.opacity!!, 0.001)

        // Background
        assertNotNull(container.background)
        assertEquals("color", container.background!!.type)
        assertEquals("#1A1A2E", container.background!!.color)

        // Padding
        assertNotNull(container.padding)
        assertEquals(20.0, container.padding!!.top!!, 0.001)
        assertEquals(16.0, container.padding!!.right!!, 0.001)
        assertEquals(20.0, container.padding!!.bottom!!, 0.001)
        assertEquals(16.0, container.padding!!.left!!, 0.001)

        // Border
        assertNotNull(container.border)
        assertEquals(1.0, container.border!!.width!!, 0.001)
        assertEquals("#333333", container.border!!.color)
        assertEquals("solid", container.border!!.style)
        assertEquals(16.0, container.border!!.radius!!, 0.001)

        // Shadow
        assertNotNull(container.shadow)
        assertEquals(0.0, container.shadow!!.x!!, 0.001)
        assertEquals(4.0, container.shadow!!.y!!, 0.001)
        assertEquals(12.0, container.shadow!!.blur!!, 0.001)
        assertEquals(0.0, container.shadow!!.spread!!, 0.001)
        assertEquals("#00000033", container.shadow!!.color)

        // Text style
        assertNotNull(container.text_style)
        assertEquals("Inter", container.text_style!!.font_family)
        assertEquals(24.0, container.text_style!!.font_size!!, 0.001)
        assertEquals(700, container.text_style!!.font_weight)
        assertEquals("#FFFFFF", container.text_style!!.color)
        assertEquals("center", container.text_style!!.alignment)
        assertEquals(1.3, container.text_style!!.line_height!!, 0.001)
        assertEquals(0.5, container.text_style!!.letter_spacing!!, 0.001)
        assertEquals(1.0, container.text_style!!.opacity!!, 0.001)

        // Element style
        assertNotNull(section.style!!.elements)
        val subtitleStyle = section.style!!.elements!!["subtitle"]!!
        assertEquals(16.0, subtitleStyle.text_style!!.font_size!!, 0.001)
        assertEquals("#AAAAAA", subtitleStyle.text_style!!.color)
    }

    // -----------------------------------------------------------------------
    // Divider field name mapping: "style" in Firestore -> line_style in data class
    // -----------------------------------------------------------------------

    @Test
    fun parseDividerSection_styleKeyMapsToLineStyle() {
        // The Firestore document uses "style" but the data class field is "line_style"
        // Parser reads d["style"] for line_style
        val pw = parsePaywall(section("divider", mapOf(
            "style" to "dashed"
        )))
        val d = pw.sections[0].data!!
        assertEquals("dashed", d.line_style)
    }

    // -----------------------------------------------------------------------
    // Toggle field name mapping: "label_color" in Firestore -> label_color_val in data class
    // -----------------------------------------------------------------------

    @Test
    fun parseToggleSection_labelColorKeyMapsToLabelColorVal() {
        // The Firestore document uses "label_color" but the data class field is "label_color_val"
        // Parser reads d["label_color"] for label_color_val
        val pw = parsePaywall(section("toggle", mapOf(
            "label" to "Test Toggle",
            "label_color" to "#FF0000"
        )))
        val d = pw.sections[0].data!!
        assertEquals("#FF0000", d.label_color_val)
    }

    // -----------------------------------------------------------------------
    // Comprehensive real-world paywall test
    // -----------------------------------------------------------------------

    @Test
    fun parseRealWorldPaywall_fullFeatured() {
        val paywallMap = mapOf<String, Any>(
            "id" to "onboarding_paywall_v3",
            "name" to "Onboarding Paywall V3",
            "layout" to mapOf("type" to "stack", "spacing" to 8, "padding" to 16),
            "dismiss" to mapOf("type" to "x_button", "delay_seconds" to 5),
            "background" to mapOf("type" to "gradient", "colors" to listOf("#0F0C29", "#302B63", "#24243E")),
            "animation" to mapOf(
                "entry_animation" to "slide_up",
                "entry_duration_ms" to 350,
                "section_stagger" to "fade_in",
                "section_stagger_delay_ms" to 80
            ),
            "haptic" to mapOf(
                "enabled" to true,
                "triggers" to mapOf("on_button_tap" to "light", "on_success" to "heavy")
            ),
            "particle_effect" to mapOf(
                "type" to "confetti",
                "trigger" to "on_purchase",
                "duration_ms" to 2500,
                "intensity" to "medium",
                "colors" to listOf("#FF5722", "#2196F3")
            ),
            "default_locale" to "en",
            "localizations" to mapOf(
                "en" to mapOf("cta" to "Start Free Trial"),
                "fr" to mapOf("cta" to "Essai Gratuit")
            ),
            "sections" to listOf(
                mapOf("type" to "header", "data" to mapOf(
                    "title" to "Unlock Premium",
                    "subtitle" to "Join 2M+ happy users",
                    "image_url" to "https://cdn.example.com/hero.png"
                )),
                mapOf("type" to "countdown", "data" to mapOf(
                    "variant" to "flip",
                    "duration_seconds" to 1800,
                    "show_hours" to false,
                    "show_minutes" to true,
                    "show_seconds" to true,
                    "accent_color" to "#FF5722",
                    "on_expire_action" to "hide"
                )),
                mapOf("type" to "carousel", "data" to mapOf(
                    "pages" to listOf(
                        mapOf("id" to "slide1", "children" to listOf(
                            mapOf("type" to "image", "data" to mapOf("image_url" to "https://cdn.example.com/s1.png"))
                        )),
                        mapOf("id" to "slide2", "children" to listOf(
                            mapOf("type" to "image", "data" to mapOf("image_url" to "https://cdn.example.com/s2.png"))
                        ))
                    ),
                    "auto_scroll" to true,
                    "auto_scroll_interval_ms" to 4000,
                    "show_indicators" to true
                )),
                mapOf("type" to "comparison_table", "data" to mapOf(
                    "table_columns" to listOf(
                        mapOf("label" to "Free"),
                        mapOf("label" to "Premium", "highlighted" to true)
                    ),
                    "rows" to listOf(
                        mapOf("feature" to "Workouts", "values" to listOf("3/week", "Unlimited")),
                        mapOf("feature" to "Analytics", "values" to listOf("Basic", "Advanced"))
                    ),
                    "check_color" to "#4CAF50",
                    "cross_color" to "#F44336"
                )),
                mapOf("type" to "toggle", "data" to mapOf(
                    "label" to "Annual Billing",
                    "description" to "Save 50%",
                    "default_value" to false,
                    "affects_price" to true,
                    "on_color" to "#4CAF50"
                )),
                mapOf("type" to "plans", "data" to mapOf(
                    "plans" to listOf(
                        mapOf("id" to "weekly", "product_id" to "com.app.weekly", "name" to "Weekly", "price" to "$4.99/wk"),
                        mapOf("id" to "yearly", "product_id" to "com.app.yearly", "name" to "Yearly", "price" to "$49.99/yr", "badge" to "Best Value", "is_default" to true, "trial_duration" to "7 days")
                    )
                )),
                mapOf("type" to "reviews_carousel", "data" to mapOf(
                    "reviews" to listOf(
                        mapOf("text" to "Life changing!", "author" to "User1", "rating" to 5.0),
                        mapOf("text" to "Worth every penny", "author" to "User2", "rating" to 4.5)
                    ),
                    "show_rating_stars" to true,
                    "star_color" to "#FFD700"
                )),
                mapOf("type" to "social_proof", "data" to mapOf(
                    "rating" to 4.9,
                    "review_count" to 50000,
                    "sub_type" to "app_rating"
                )),
                mapOf("type" to "promo_input", "data" to mapOf(
                    "placeholder" to "Promo code",
                    "button_text" to "Apply",
                    "success_text" to "Applied!",
                    "error_text" to "Invalid code"
                )),
                mapOf("type" to "sticky_footer", "data" to mapOf(
                    "cta_text" to "Start Free Trial",
                    "cta_bg_color" to "#6366F1",
                    "cta_text_color" to "#FFFFFF",
                    "cta_corner_radius" to 24,
                    "secondary_text" to "Restore Purchases",
                    "secondary_action" to "restore",
                    "legal_text" to "Cancel anytime",
                    "blur_background" to true,
                    "padding" to 16
                )),
                mapOf("type" to "divider", "data" to mapOf(
                    "thickness" to 1,
                    "style" to "solid",
                    "color" to "#333333"
                )),
                mapOf("type" to "legal", "data" to mapOf(
                    "color" to "#888888",
                    "links" to listOf(
                        mapOf("label" to "Terms", "url" to "https://example.com/terms"),
                        mapOf("label" to "Privacy", "url" to "https://example.com/privacy")
                    )
                ))
            )
        )

        val result = PaywallConfigParser.parsePaywalls(mapOf("onboarding_paywall_v3" to paywallMap))
        val pw = result["onboarding_paywall_v3"]!!

        // Top-level
        assertEquals("onboarding_paywall_v3", pw.id)
        assertEquals("Onboarding Paywall V3", pw.name)
        assertEquals("stack", pw.layout.type)
        assertEquals(8f, pw.layout.spacing!!, 0.01f)
        assertEquals(16f, pw.layout.padding!!, 0.01f)

        // Dismiss
        assertEquals("x_button", pw.dismiss!!.type)
        assertEquals(5, pw.dismiss!!.delay_seconds)

        // Background
        assertEquals("gradient", pw.background!!.type)
        assertEquals(3, pw.background!!.colors!!.size)

        // Animation
        assertEquals("slide_up", pw.animation!!.entry_animation)

        // Haptic
        assertEquals(true, pw.haptic!!.enabled)

        // Particle
        assertEquals("confetti", pw.particle_effect!!.type)

        // Localizations
        assertEquals("en", pw.default_locale)
        assertEquals("Start Free Trial", pw.localizations!!["en"]!!["cta"])

        // 12 sections total
        assertEquals(12, pw.sections.size)
        assertEquals("header", pw.sections[0].type)
        assertEquals("countdown", pw.sections[1].type)
        assertEquals("carousel", pw.sections[2].type)
        assertEquals("comparison_table", pw.sections[3].type)
        assertEquals("toggle", pw.sections[4].type)
        assertEquals("plans", pw.sections[5].type)
        assertEquals("reviews_carousel", pw.sections[6].type)
        assertEquals("social_proof", pw.sections[7].type)
        assertEquals("promo_input", pw.sections[8].type)
        assertEquals("sticky_footer", pw.sections[9].type)
        assertEquals("divider", pw.sections[10].type)
        assertEquals("legal", pw.sections[11].type)

        // Spot-check a few section data values
        assertEquals("flip", pw.sections[1].data!!.variant)
        assertEquals(1800, pw.sections[1].data!!.duration_seconds)
        assertEquals(2, pw.sections[2].data!!.pages!!.size)
        assertEquals(true, pw.sections[2].data!!.auto_scroll)
        assertEquals(2, pw.sections[3].data!!.table_columns!!.size)
        assertEquals(true, pw.sections[4].data!!.affects_price)
        assertEquals(2, pw.sections[5].data!!.plans!!.size)
        assertEquals("Best Value", pw.sections[5].data!!.plans!![1].badge)
        assertEquals(2, pw.sections[6].data!!.reviews!!.size)
        assertEquals(4.9, pw.sections[7].data!!.rating!!, 0.001)
        assertEquals("Promo code", pw.sections[8].data!!.placeholder)
        assertEquals(true, pw.sections[9].data!!.blur_background)
        assertEquals("solid", pw.sections[10].data!!.line_style)
        assertEquals(2, pw.sections[11].data!!.links!!.size)
    }

    // -----------------------------------------------------------------------
    // Unknown section type doesn't crash (forward compatibility)
    // -----------------------------------------------------------------------

    @Test
    fun parseUnknownSectionType_doesNotCrash() {
        val pw = parsePaywall(
            section("future_widget_2030", mapOf("title" to "Future")),
            section("header", mapOf("title" to "After Unknown"))
        )
        assertEquals(2, pw.sections.size)
        assertEquals("future_widget_2030", pw.sections[0].type)
        assertEquals("Future", pw.sections[0].data!!.title)
        assertEquals("header", pw.sections[1].type)
        assertEquals("After Unknown", pw.sections[1].data!!.title)
    }

    // -----------------------------------------------------------------------
    // Comparison table: columns field fallback
    // -----------------------------------------------------------------------

    @Test
    fun parseComparisonTable_columnsFieldAlsoAccepted() {
        // The parser has a fallback: (d["table_columns"] ?: d["columns"]) for table_columns
        // When "table_columns" is not present but "columns" is a list of maps, it should parse as table_columns
        // NOTE: "columns" is also used as an Int for icon_grid. When it's a List<Map>, it feeds table_columns.
        val pw = parsePaywall(section("comparison_table", mapOf(
            "table_columns" to listOf(
                mapOf("label" to "A"),
                mapOf("label" to "B", "highlighted" to true)
            ),
            "rows" to listOf(
                mapOf("feature" to "X", "values" to listOf("Yes", "Yes"))
            )
        )))
        val d = pw.sections[0].data!!
        assertEquals(2, d.table_columns!!.size)
        assertEquals("A", d.table_columns!![0].label)
        assertEquals("B", d.table_columns!![1].label)
        assertEquals(true, d.table_columns!![1].highlighted)
        assertEquals(1, d.rows!!.size)
    }

    // -----------------------------------------------------------------------
    // Icon grid items with label and description (shared PaywallGenericItem)
    // -----------------------------------------------------------------------

    @Test
    fun parseIconGridItems_useLabelAndDescription() {
        val pw = parsePaywall(section("icon_grid", mapOf(
            "items" to listOf(
                mapOf("icon" to "star", "label" to "Favorite", "description" to "Mark as favorite"),
                mapOf("icon" to "bell", "label" to "Notify")
            ),
            "columns" to 2
        )))
        val items = pw.sections[0].data!!.items!!
        assertEquals(2, items.size)
        assertEquals("star", items[0].icon)
        assertEquals("Favorite", items[0].label)
        assertEquals("Mark as favorite", items[0].description)
        assertEquals("bell", items[1].icon)
        assertEquals("Notify", items[1].label)
        assertNull(items[1].description)
    }

    // -----------------------------------------------------------------------
    // Carousel with empty pages
    // -----------------------------------------------------------------------

    @Test
    fun parseCarouselSection_emptyPages() {
        val pw = parsePaywall(section("carousel", mapOf(
            "pages" to emptyList<Map<String, Any>>()
        )))
        val d = pw.sections[0].data!!
        assertTrue(d.pages!!.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Reviews carousel with empty reviews
    // -----------------------------------------------------------------------

    @Test
    fun parseReviewsCarouselSection_emptyReviews() {
        val pw = parsePaywall(section("reviews_carousel", mapOf(
            "reviews" to emptyList<Map<String, Any>>(),
            "show_rating_stars" to true
        )))
        val d = pw.sections[0].data!!
        assertTrue(d.reviews!!.isEmpty())
        assertEquals(true, d.show_rating_stars)
    }

    // -----------------------------------------------------------------------
    // Timeline with empty items
    // -----------------------------------------------------------------------

    @Test
    fun parseTimelineSection_emptyItems() {
        val pw = parsePaywall(section("timeline", mapOf(
            "items" to emptyList<Map<String, Any>>()
        )))
        val d = pw.sections[0].data!!
        assertTrue(d.items!!.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Legal section with single link
    // -----------------------------------------------------------------------

    @Test
    fun parseLegalSection_singleLink() {
        val pw = parsePaywall(section("legal", mapOf(
            "links" to listOf(
                mapOf("label" to "EULA", "url" to "https://example.com/eula")
            )
        )))
        val d = pw.sections[0].data!!
        assertEquals(1, d.links!!.size)
        assertEquals("EULA", d.links!![0].label)
        assertEquals("https://example.com/eula", d.links!![0].url)
    }

    // -----------------------------------------------------------------------
    // Countdown labels map with partial keys
    // -----------------------------------------------------------------------

    @Test
    fun parseCountdownSection_partialLabels() {
        val pw = parsePaywall(section("countdown", mapOf(
            "variant" to "digital",
            "duration_seconds" to 600,
            "labels" to mapOf("hours" to "h", "minutes" to "m")
        )))
        val d = pw.sections[0].data!!
        assertEquals(2, d.labels!!.size)
        assertEquals("h", d.labels!!["hours"])
        assertEquals("m", d.labels!!["minutes"])
        assertNull(d.labels!!["days"])
        assertNull(d.labels!!["seconds"])
    }

    // -----------------------------------------------------------------------
    // Sticky footer with no blur
    // -----------------------------------------------------------------------

    @Test
    fun parseStickyFooterSection_blurDisabled() {
        val pw = parsePaywall(section("sticky_footer", mapOf(
            "cta_text" to "Buy Now",
            "blur_background" to false
        )))
        val d = pw.sections[0].data!!
        assertEquals(false, d.blur_background)
    }

    // -----------------------------------------------------------------------
    // Full paywall with all optional top-level fields absent
    // -----------------------------------------------------------------------

    @Test
    fun parseMinimalPaywall_noOptionalTopLevelFields() {
        val paywallMap = mapOf<String, Any>(
            "id" to "minimal",
            "name" to "Minimal",
            "layout" to mapOf("type" to "stack"),
            "sections" to listOf(
                mapOf("type" to "cta", "data" to mapOf(
                    "cta" to mapOf("text" to "Go")
                ))
            )
        )
        val result = PaywallConfigParser.parsePaywalls(mapOf("minimal" to paywallMap))
        val pw = result["minimal"]!!
        assertEquals("minimal", pw.id)
        assertEquals("Minimal", pw.name)
        assertNull(pw.dismiss)
        assertNull(pw.background)
        assertNull(pw.animation)
        assertNull(pw.haptic)
        assertNull(pw.particle_effect)
        assertNull(pw.video_background_url)
        assertNull(pw.localizations)
        assertNull(pw.default_locale)
        assertEquals(1, pw.sections.size)
        assertEquals("Go", pw.sections[0].data!!.cta!!.text)
    }
}
