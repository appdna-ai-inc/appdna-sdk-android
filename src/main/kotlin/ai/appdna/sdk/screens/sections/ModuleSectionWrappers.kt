package ai.appdna.sdk.screens.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.screens.SectionAction
import ai.appdna.sdk.screens.SectionContext
import ai.appdna.sdk.screens.ScreenSection
import ai.appdna.sdk.screens.SectionRegistry

/**
 * SPEC-070-A I.1 — Paywall section renderer (Screens SDUI).
 *
 * Ports the iOS `PaywallSectionWrapperImpl.swift` (961 LOC) dispatch table
 * for the 24 `paywall_*` section types so SDUI screens render the same
 * paywall content a stand-alone PaywallActivity would. Sections are
 * decoded from `section.data` and dispatched by the trailing token (so
 * `paywall_features` → `features`).
 *
 * Each renderer keeps state scoped to its own composable so dispatch order
 * remains deterministic. Action dispatch flows through [SectionContext.onAction]
 * — `next` for CTA tap, `custom("restore_purchase", null)` for restore,
 * `custom("plan_selected", planId)` for plan selection. This matches iOS
 * exactly so a host listening to action events handles both platforms with
 * the same router.
 */
@Composable
internal fun PaywallSectionWrapper(section: ScreenSection, context: SectionContext) {
    val type = (section.type).removePrefix("paywall_")
    when (type) {
        "header" -> PaywallHeaderSection(section)
        "features" -> PaywallFeaturesSection(section)
        "plans" -> PaywallPlansSection(section, context)
        "cta" -> PaywallCtaSection(section, context)
        "social_proof" -> PaywallSocialProofSection(section)
        "guarantee" -> PaywallGuaranteeSection(section)
        "testimonial" -> PaywallTestimonialSection(section)
        "countdown" -> PaywallCountdownSection(section)
        "legal" -> PaywallLegalSection(section, context)
        "comparison" -> PaywallComparisonSection(section)
        "promo" -> PaywallPromoInputSection(section, context)
        "reviews" -> PaywallReviewsSection(section)
        "toggle" -> PaywallToggleSection(section)
        "icon_grid" -> PaywallIconGridSection(section)
        "card" -> PaywallCardSection(section)
        "timeline" -> PaywallTimelineSection(section)
        "image" -> PaywallImageSection(section)
        "video" -> PaywallVideoSection(section)
        "lottie" -> PaywallLottieSection(section)
        "rive" -> PaywallRiveSection(section)
        "spacer" -> Spacer(Modifier.height(((section.data["height"] as? Number)?.toFloat() ?: 24f).dp))
        "divider" -> PaywallDividerSection(section)
        "sticky_footer" -> PaywallStickyFooterSection(section, context)
        "carousel" -> PaywallCarouselSection(section, context)
        else -> {
            val title = section.data["title"] as? String
            val subtitle = section.data["subtitle"] as? String
            Column(Modifier.padding(16.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PaywallHeaderSection(section: ScreenSection) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        (section.data["title"] as? String)?.let {
            Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        (section.data["subtitle"] as? String)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaywallFeaturesSection(section: ScreenSection) {
    @Suppress("UNCHECKED_CAST")
    val features = (section.data["features"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val items = (section.data["items"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
    val columns = (section.data["feature_columns"] as? Number)?.toInt() ?: 1
    val gap = (section.data["feature_gap"] as? Number)?.toFloat() ?: 12f
    val iconColor = (section.data["icon_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.primary
    val itemTextColor = (section.data["item_text_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        if (items.isNotEmpty()) {
            // Rich items mode — title + description + icon
            for (item in items) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val icon = item["icon"] as? String
                    if (!icon.isNullOrBlank()) {
                        Text(text = icon, fontSize = 20.sp, color = iconColor)
                    } else {
                        Text(text = "✓", fontSize = 18.sp, color = iconColor)
                    }
                    Column {
                        (item["title"] as? String)?.let {
                            Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = itemTextColor)
                        }
                        (item["description"] as? String)?.let {
                            Text(it, fontSize = 13.sp, color = itemTextColor.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        } else {
            // Simple bulleted features
            if (columns <= 1) {
                for (feature in features) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✓", color = iconColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(feature, color = itemTextColor, fontSize = 14.sp)
                    }
                }
            } else {
                features.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                        for (feature in row) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text("✓", color = iconColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(6.dp))
                                Text(feature, color = itemTextColor, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaywallPlansSection(section: ScreenSection, context: SectionContext) {
    @Suppress("UNCHECKED_CAST")
    val plans = (section.data["plans"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
    var selectedId by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (plan in plans) {
            val id = plan["id"] as? String ?: continue
            val isSelected = selectedId == id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable {
                        selectedId = id
                        context.onAction(SectionAction.Custom("plan_selected", id))
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (isSelected) "●" else "○", color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    (plan["display_name"] as? String)?.let { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                    (plan["description"] as? String)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    (plan["trial_label"] as? String)?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    (plan["display_price"] as? String)?.let { Text(it, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    (plan["period"] as? String)?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    (plan["badge"] as? String)?.let {
                        Text(
                            it,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier
                                .background(Color(0xFFF59E0B), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaywallCtaSection(section: ScreenSection, context: SectionContext) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = { context.onAction(SectionAction.Next) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                section.data["cta_text"] as? String ?: section.data["text"] as? String ?: "Continue",
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (section.data["show_restore"] == true) {
            TextButton(onClick = { context.onAction(SectionAction.Custom("restore_purchase", null)) }) {
                Text(section.data["restore_text"] as? String ?: "Restore Purchases", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PaywallSocialProofSection(section: ScreenSection) {
    val text = section.data["text"] as? String ?: return
    Text(
        text,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PaywallGuaranteeSection(section: ScreenSection) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val accent = (section.data["accent_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: Color(0xFF22C55E)
        Text("🛡️", fontSize = 24.sp)
        (section.data["guarantee_text"] as? String ?: section.data["text"] as? String)?.let {
            Text(it, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        (section.data["title"] as? String)?.let {
            Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        (section.data["description"] as? String)?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PaywallTestimonialSection(section: ScreenSection) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val quote = section.data["quote"] as? String ?: section.data["testimonial"] as? String
        if (quote != null) {
            Text(quote, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        (section.data["author_name"] as? String)?.let {
            Text("— $it", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PaywallCountdownSection(section: ScreenSection) {
    val seconds = (section.data["duration_seconds"] as? Number)?.toLong()
        ?: (section.data["countdown_seconds"] as? Number)?.toLong()
        ?: 3600L
    var remaining by remember { mutableStateOf(seconds) }
    LaunchedEffect(seconds) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining -= 1
        }
    }
    // Round-24 — the SDUI countdown section rendered NO label at all, while iOS + the main paywall
    // countdown render the "Offer ends in" label. Add it (label_text ?? label, 14sp/SemiBold/#7F1D1D
    // defaults, honoring label_font_size + label_color) so the two platforms + the two paywall paths agree.
    val labelText = (section.data["label_text"] as? String) ?: (section.data["label"] as? String)
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!labelText.isNullOrBlank()) {
            val labelColor = (section.data["label_color"] as? String)?.let {
                try { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) } catch (_: Exception) { null }
            } ?: androidx.compose.ui.graphics.Color(0xFF7F1D1D)
            Text(
                labelText,
                fontSize = ((section.data["label_font_size"] as? Number)?.toFloat() ?: 14f).sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val h = remaining / 3600
            val m = (remaining % 3600) / 60
            val s = remaining % 60
            Text(
                String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PaywallLegalSection(section: ScreenSection, context: SectionContext) {
    val color = (section.data["color"] as? String)?.let { StyleEngine.parseColor(it) } ?: Color(0xFF9CA3AF)
    val accent = (section.data["accent_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.primary
    val size = (section.data["font_size"] as? Number)?.toFloat() ?: 11f
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // SPEC-485 (#649) — inline `[label](url)` links, via the SAME parser PaywallActivity uses,
        // and the AUTHORED alignment. This was a plain Text with TextAlign.Center hardcoded, so a
        // legal line reading "see our [Terms](https://…)" showed the literal brackets here while
        // rendering correctly on the other Android path, and the alignment setting did nothing.
        val legalAlign = when (section.data["alignment"] as? String) {
            "left" -> TextAlign.Start
            "right" -> TextAlign.End
            else -> TextAlign.Center
        }
        (section.data["text"] as? String)?.let { legalText ->
            val annotated = ai.appdna.sdk.paywalls.buildAnnotatedStringWithLinks(legalText, accent)
            androidx.compose.foundation.text.ClickableText(
                text = annotated,
                style = androidx.compose.ui.text.TextStyle(
                    color = color,
                    fontSize = size.sp,
                    textAlign = legalAlign,
                ),
                modifier = Modifier.fillMaxWidth(),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { context.onAction(SectionAction.OpenWebview(it.item)) }
                },
            )
        }
        @Suppress("UNCHECKED_CAST")
        val links = (section.data["links"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
        if (!links.isNullOrEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (link in links) {
                    val label = link["label"] as? String ?: continue
                    Text(
                        label,
                        fontSize = size.sp,
                        color = accent,
                        modifier = Modifier.clickable {
                            when (link["action"] as? String) {
                                "restore" -> context.onAction(SectionAction.Custom("restore_purchase", null))
                                else -> (link["url"] as? String)?.let { url -> context.onAction(SectionAction.OpenWebview(url)) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PaywallComparisonSection(section: ScreenSection) {
    @Suppress("UNCHECKED_CAST")
    val cols = (section.data["table_columns"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val rows = (section.data["table_rows"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Spacer(Modifier.weight(1f))
            for (col in cols) {
                Text(col["label"] as? String ?: "", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        @Suppress("DEPRECATION")
        Divider()
        for (row in rows) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(row["feature"] as? String ?: "", modifier = Modifier.weight(1f), fontSize = 12.sp)
                @Suppress("UNCHECKED_CAST")
                val values = (row["values"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                for (v in values) {
                    val symbol = when (v.lowercase()) {
                        "true", "yes", "✓" -> "✓"
                        "false", "no", "✗" -> "✗"
                        else -> v
                    }
                    Text(symbol, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp)
                }
            }
            @Suppress("DEPRECATION")
            Divider()
        }
    }
}

@Composable
private fun PaywallPromoInputSection(section: ScreenSection, context: SectionContext) {
    var code by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        (section.data["title"] as? String)?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                placeholder = { Text(section.data["placeholder"] as? String ?: "Promo code") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { context.onAction(SectionAction.Custom("apply_promo", code)) },
                enabled = code.isNotEmpty(),
            ) {
                Text(section.data["button_text"] as? String ?: "Apply")
            }
        }
    }
}

@Composable
private fun PaywallReviewsSection(section: ScreenSection) {
    @Suppress("UNCHECKED_CAST")
    val reviews = (section.data["reviews"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: return
    val starColor = (section.data["star_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: Color(0xFFF59E0B)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (review in reviews) {
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val rating = (review["rating"] as? Number)?.toInt() ?: 5
                Row { repeat(rating) { Text("★", color = starColor, fontSize = 12.sp) } }
                (review["text"] as? String)?.let { Text(it, fontSize = 12.sp, maxLines = 4) }
                (review["author"] as? String)?.let { Text("— $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun PaywallToggleSection(section: ScreenSection) {
    var isOn by remember { mutableStateOf(section.data["default_value"] as? Boolean ?: false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            (section.data["label"] as? String)?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            (section.data["description"] as? String)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = isOn, onCheckedChange = { isOn = it })
    }
}

@Composable
private fun PaywallIconGridSection(section: ScreenSection) {
    @Suppress("UNCHECKED_CAST")
    val items = (section.data["items"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: return
    val cols = (section.data["columns"] as? Number)?.toInt() ?: 3
    val iconColor = (section.data["icon_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(cols.coerceAtLeast(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (item in row) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        (item["icon"] as? String)?.let { Text(it, fontSize = 24.sp, color = iconColor) }
                        (item["label"] as? String ?: item["title"] as? String)?.let {
                            Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                    }
                }
                repeat((cols - row.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PaywallCardSection(section: ScreenSection) {
    val radius = (section.data["corner_radius"] as? Number)?.toFloat() ?: 16f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(radius.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(radius.dp))
            .padding(16.dp),
    ) {
        (section.data["title"] as? String)?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        (section.data["subtitle"] as? String)?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        (section.data["text"] as? String)?.let { Text(it, fontSize = 13.sp) }
    }
}

@Composable
private fun PaywallTimelineSection(section: ScreenSection) {
    @Suppress("UNCHECKED_CAST")
    val items = (section.data["items"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { idx, item ->
            Row(verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(12.dp))
                Column {
                    (item["title"] as? String)?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    (item["subtitle"] as? String)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun PaywallImageSection(section: ScreenSection) {
    val height = (section.data["height"] as? Number)?.toFloat() ?: 240f
    val radius = (section.data["corner_radius"] as? Number)?.toFloat() ?: 12f
    val url = section.data["image_url"] as? String
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(radius.dp))
            .background(Color.Gray.copy(alpha = 0.1f)),
    ) {
        if (url != null) {
            ai.appdna.sdk.core.NetworkImage(
                url = url,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

/**
 * SPEC-070-A finalization B5 P1 — render real video instead of empty Box.
 * Wires `paywall_video` section data through the same VideoBlockView used
 * by onboarding content_blocks. Mirrors iOS PaywallSectionWrapperImpl
 * which dispatches `video` directly to VideoBlockView.
 */
@Composable
private fun PaywallVideoSection(section: ScreenSection) {
    val url = section.data["video_url"] as? String ?: section.data["url"] as? String
    if (url == null) {
        // Fallback gray box when no URL configured.
        val h = (section.data["video_height"] as? Number)?.toFloat()
            ?: (section.data["height"] as? Number)?.toFloat() ?: 200f
        Box(
            modifier = Modifier.fillMaxWidth().height(h.dp)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Gray.copy(alpha = 0.05f)),
        )
        return
    }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        ai.appdna.sdk.core.VideoBlockView(
            block = ai.appdna.sdk.core.VideoBlock(
                video_url = url,
                video_thumbnail_url = section.data["video_thumbnail_url"] as? String,
                video_height = (section.data["video_height"] as? Number)?.toFloat()
                    ?: (section.data["height"] as? Number)?.toFloat() ?: 200f,
                video_corner_radius = (section.data["video_corner_radius"] as? Number)?.toFloat()
                    ?: (section.data["corner_radius"] as? Number)?.toFloat(),
                autoplay = section.data["video_autoplay"] as? Boolean ?: section.data["autoplay"] as? Boolean,
                loop = section.data["video_loop"] as? Boolean ?: section.data["loop"] as? Boolean,
                muted = section.data["video_muted"] as? Boolean ?: section.data["muted"] as? Boolean,
            ),
        )
    }
}

/**
 * SPEC-070-A finalization B5 P1 — render real Lottie animation instead of
 * empty Box, matching iOS PaywallSectionWrapperImpl.
 */
@Composable
private fun PaywallLottieSection(section: ScreenSection) {
    val url = section.data["lottie_url"] as? String ?: section.data["url"] as? String
    @Suppress("UNCHECKED_CAST")
    val lottieJson = section.data["lottie_json"] as? Map<String, Any>
    if (url == null && lottieJson == null) {
        val h = (section.data["lottie_height"] as? Number)?.toFloat()
            ?: (section.data["height"] as? Number)?.toFloat() ?: 160f
        Box(
            modifier = Modifier.fillMaxWidth().height(h.dp)
                .padding(horizontal = 20.dp),
        )
        return
    }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        ai.appdna.sdk.core.LottieBlockView(
            block = ai.appdna.sdk.core.LottieBlock(
                lottie_url = url,
                lottie_json = lottieJson,
                autoplay = section.data["lottie_autoplay"] as? Boolean ?: section.data["autoplay"] as? Boolean ?: true,
                loop = section.data["lottie_loop"] as? Boolean ?: section.data["loop"] as? Boolean ?: true,
                speed = (section.data["lottie_speed"] as? Number)?.toFloat() ?: 1.0f,
                height = (section.data["lottie_height"] as? Number)?.toFloat()
                    ?: (section.data["height"] as? Number)?.toFloat() ?: 160f,
            ),
        )
    }
}

/**
 * SPEC-070-A finalization B5 P1 — render real Rive animation instead of
 * empty Box, matching iOS PaywallSectionWrapperImpl.
 */
@Composable
private fun PaywallRiveSection(section: ScreenSection) {
    val url = section.data["rive_url"] as? String ?: section.data["url"] as? String ?: return
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        ai.appdna.sdk.core.RiveBlockView(
            block = ai.appdna.sdk.core.RiveBlock(
                rive_url = url,
                artboard = section.data["rive_artboard"] as? String ?: section.data["artboard"] as? String,
                state_machine = section.data["rive_state_machine"] as? String ?: section.data["state_machine"] as? String,
                autoplay = section.data["autoplay"] as? Boolean ?: true,
                height = (section.data["height"] as? Number)?.toFloat() ?: 160f,
            ),
        )
    }
}

@Composable
private fun PaywallDividerSection(section: ScreenSection) {
    val color = (section.data["color"] as? String)?.let { StyleEngine.parseColor(it) } ?: Color(0xFFE5E7EB)
    val thickness = (section.data["thickness"] as? Number)?.toFloat() ?: 1f
    @Suppress("DEPRECATION")
    Divider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = color, thickness = thickness.dp)
}

@Composable
private fun PaywallStickyFooterSection(section: ScreenSection, context: SectionContext) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
    ) {
        (section.data["cta_text"] as? String)?.let {
            Button(onClick = { context.onAction(SectionAction.Next) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(it, fontWeight = FontWeight.SemiBold)
            }
        }
        (section.data["legal_text"] as? String)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PaywallCarouselSection(section: ScreenSection, context: SectionContext) {
    @Suppress("UNCHECKED_CAST")
    val pages = (section.data["pages"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (page in pages) {
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                (page["title"] as? String)?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                (page["body"] as? String)?.let { Text(it, fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
        }
    }
}

/**
 * SPEC-070-A I.1 — Survey section renderer (Screens SDUI). Mirrors iOS
 * `SurveySectionWrapperImpl.swift`. The 6 survey_* section types resolve
 * to either a [SurveyQuestionView] (NPS/CSAT/rating/single/multi/free
 * text/yes-no/emoji) or a thank-you card. Answer changes dispatch a
 * `survey_answer:<id>` custom action so the host flow can persist.
 */
@Composable
internal fun SurveySectionWrapper(section: ScreenSection, context: SectionContext) {
    when (section.type) {
        "survey_thank_you" -> SurveyThankYouSection(section, context)
        else -> SurveyQuestionSection(section, context)
    }
}

@Composable
private fun SurveyQuestionSection(section: ScreenSection, context: SectionContext) {
    val type = (section.data["type"] as? String) ?: when (section.type) {
        "survey_nps" -> "nps"
        "survey_csat" -> "csat"
        "survey_rating" -> "rating"
        "survey_free_text" -> "free_text"
        else -> "free_text"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (section.data["text"] as? String)?.let {
            Text(it, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        when (type) {
            "nps" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    var selected by remember { mutableStateOf<Int?>(null) }
                    for (n in 0..10) {
                        OutlinedButton(
                            onClick = {
                                selected = n
                                context.onAction(SectionAction.Custom("survey_answer:${section.id}", n.toString()))
                            },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.size(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = if (selected == n) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White)
                            else ButtonDefaults.outlinedButtonColors(),
                        ) { Text(n.toString(), fontSize = 11.sp) }
                    }
                }
            }
            "rating" -> {
                var selected by remember { mutableStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (n in 1..5) {
                        Text(
                            text = if (n <= selected) "★" else "☆",
                            fontSize = 28.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                selected = n
                                context.onAction(SectionAction.Custom("survey_answer:${section.id}", n.toString()))
                            },
                        )
                    }
                }
            }
            "free_text" -> {
                var text by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        context.onAction(SectionAction.Custom("survey_answer:${section.id}", it))
                    },
                    placeholder = { Text("Type your answer…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                )
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val options = (section.data["options"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
                var selected by remember { mutableStateOf<String?>(null) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (opt in options) {
                        val id = opt["id"] as? String ?: continue
                        val label = opt["text"] as? String ?: id
                        val isSelected = selected == id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable {
                                    selected = id
                                    context.onAction(SectionAction.Custom("survey_answer:${section.id}", id))
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (isSelected) "●" else "○", color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurveyThankYouSection(section: ScreenSection, context: SectionContext) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("✓", fontSize = 48.sp, color = Color(0xFF22C55E))
        Text(section.data["title"] as? String ?: "Thank you!", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        (section.data["body"] as? String ?: section.data["text"] as? String)?.let {
            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Button(
            onClick = { context.onAction(SectionAction.Next) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(section.data["cta_text"] as? String ?: "Done", fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * SPEC-070-A I.1 — In-app message section renderer (Screens SDUI). Mirrors
 * iOS `MessageSectionWrapperImpl.swift`. Supports `message_banner`,
 * `message_modal`, `message_content`. CTA tap → `next` (or deep_link/openWebview
 * when `cta_action` carries a typed action).
 */
@Composable
internal fun MessageSectionWrapper(section: ScreenSection, context: SectionContext) {
    val title = section.data["title"] as? String
    val body = section.data["body"] as? String
    val ctaText = section.data["cta_text"] as? String
    val imageUrl = section.data["image_url"] as? String
    val bg = (section.data["background_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.surface
    val textColor = (section.data["text_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.onSurface
    val buttonColor = (section.data["button_color"] as? String)?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.primary

    val onCtaTap: () -> Unit = {
        @Suppress("UNCHECKED_CAST")
        val ctaAction = section.data["cta_action"] as? Map<String, Any?>
        when (ctaAction?.get("type") as? String) {
            "deep_link" -> (ctaAction["url"] as? String)?.let { context.onAction(SectionAction.DeepLink(it)) }
            "open_url" -> (ctaAction["url"] as? String)?.let { context.onAction(SectionAction.OpenWebview(it)) }
            else -> context.onAction(SectionAction.Next)
        }
    }

    when (section.type) {
        "message_banner" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (title != null) Text(title, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (body != null) Text(body, color = textColor.copy(alpha = 0.8f), fontSize = 12.sp)
                }
                if (ctaText != null) {
                    TextButton(onClick = onCtaTap) { Text(ctaText, color = buttonColor) }
                }
            }
        }
        "message_modal", "message_content" -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (imageUrl != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Gray.copy(alpha = 0.1f)),
                    ) {
                        ai.appdna.sdk.core.NetworkImage(
                            url = imageUrl,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                }
                if (title != null) Text(title, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (body != null) Text(body, color = textColor.copy(alpha = 0.8f), fontSize = 14.sp)
                if (ctaText != null) {
                    Button(
                        onClick = onCtaTap,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    ) { Text(ctaText, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        else -> {
            Column(Modifier.padding(16.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// Onboarding section wrapper
@Composable
internal fun OnboardingSectionWrapper(section: ScreenSection, context: SectionContext) {
    when (section.type) {
        "progress_indicator" -> {
            val current = (section.data["current"] as? Number)?.toInt() ?: context.currentScreenIndex
            val total = (section.data["total"] as? Number)?.toInt() ?: context.totalScreens
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(total) { i ->
                    Box(
                        modifier = Modifier.weight(1f).height(4.dp)
                            .then(
                                if (i <= current)
                                    Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                else
                                    Modifier.background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                            ),
                    )
                }
            }
        }
        "navigation_controls" -> {
            val showBack = section.data["show_back"] as? Boolean ?: true
            val ctaText = section.data["cta_text"] as? String ?: "Next"
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) TextButton(onClick = { context.onAction(SectionAction.Back) }) { Text("Back") }
                else Spacer(Modifier)
                Button(onClick = { context.onAction(SectionAction.Next) }) { Text(ctaText) }
            }
        }
        "onboarding_step" -> {
            // SPEC-070-A finalization B5#P1 / iOS ModuleSectionWrappers.swift:38-54 —
            // re-dispatch to ContentBlocksSectionRenderer with `blocks` keyed
            // off the section's `content_blocks` payload. Lets onboarding
            // content authors embed an entire content-block step inside an
            // SDUI screen without rebuilding the renderer.
            val rawBlocks = section.data["content_blocks"]
            if (rawBlocks != null) {
                ContentBlocksSectionRenderer(
                    section = ScreenSection(
                        id = section.id,
                        type = "content_blocks",
                        data = mapOf("blocks" to rawBlocks),
                        style = section.style,
                        visibilityCondition = section.visibilityCondition,
                        entranceAnimation = section.entranceAnimation,
                        a11y = section.a11y,
                    ),
                    context = context,
                )
            }
        }
        else -> { /* unknown */ }
    }
}

// SPEC-070-A finalization B6#P0-1 — non-extension wrapper called from
// AppDNA.configure() so callers don't need to import the extension form
// or use a `with(receiver) { ... }` block.
internal fun registerAllModuleSections() = SectionRegistry.registerModuleSections()

// Extension to register all module sections
internal fun SectionRegistry.registerModuleSections() {
    val paywallTypes = listOf(
        "paywall_header", "paywall_features", "paywall_plans", "paywall_cta",
        "paywall_social_proof", "paywall_guarantee", "paywall_testimonial",
        "paywall_countdown", "paywall_legal", "paywall_comparison",
        "paywall_promo", "paywall_reviews", "paywall_toggle",
        "paywall_icon_grid", "paywall_carousel", "paywall_card",
        "paywall_timeline", "paywall_image", "paywall_video",
        "paywall_lottie", "paywall_rive", "paywall_spacer",
        "paywall_divider", "paywall_sticky_footer",
    )
    for (type in paywallTypes) register(type) { s, c -> PaywallSectionWrapper(s, c) }

    val surveyTypes = listOf("survey_question", "survey_nps", "survey_csat", "survey_rating", "survey_free_text", "survey_thank_you")
    for (type in surveyTypes) register(type) { s, c -> SurveySectionWrapper(s, c) }

    val messageTypes = listOf("message_banner", "message_modal", "message_content")
    for (type in messageTypes) register(type) { s, c -> MessageSectionWrapper(s, c) }

    val onboardingTypes = listOf("onboarding_step", "progress_indicator", "navigation_controls")
    for (type in onboardingTypes) register(type) { s, c -> OnboardingSectionWrapper(s, c) }
}
