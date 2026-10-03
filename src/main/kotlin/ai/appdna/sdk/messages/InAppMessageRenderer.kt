package ai.appdna.sdk.messages

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.NetworkImage
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.FontResolver
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.RiveBlock
import ai.appdna.sdk.core.RiveBlockView
import ai.appdna.sdk.core.VideoBlock as CoreVideoBlock
import ai.appdna.sdk.core.VideoBlockView as CoreVideoBlockView
import ai.appdna.sdk.core.ConfettiOverlay
import ai.appdna.sdk.core.HapticEngine
import ai.appdna.sdk.core.IconView
import ai.appdna.sdk.core.resolveIcon
import ai.appdna.sdk.core.applyBlurBackdrop
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
// Accessibility string resources for in-app message chrome.
import ai.appdna.sdk.R

/**
 * Complete in-app message renderer for Android.
 * Renders banner, modal, fullscreen, and tooltip message types
 * with configurable styling (text_color, button_color, corner_radius, background_color).
 */

@Composable
fun InAppMessageView(
    config: MessageConfig,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
    // Optional override; defaults to system setting.
    // Callers (MessageManager / PendingMessageListener) wrap the renderer in a
    // ComposeView that does not host MaterialTheme, so we self-host both the
    // dark-mode read and the MaterialTheme seeding here.
    isDark: Boolean = isSystemInDarkTheme(),
) {
    // Interpolate text fields via TemplateEngine before rendering
    val ctx = ai.appdna.sdk.core.TemplateEngine.buildContext()
    val e = ai.appdna.sdk.core.TemplateEngine
    val interpolated = config.content.copy(
        title = config.content.title?.let { e.interpolate(it, ctx) },
        body = config.content.body?.let { e.interpolate(it, ctx) },
        cta_text = config.content.cta_text?.let { e.interpolate(it, ctx) },
        secondary_cta_text = config.content.secondary_cta_text?.let { e.interpolate(it, ctx) },
        dismiss_text = config.content.dismiss_text?.let { e.interpolate(it, ctx) },
    )

    // Apply dark overrides BEFORE handing to the
    // type-specific sub-views, so they see the final resolved colors/images
    // for the current scheme. Mirrors iOS `MessageRenderer.body` which calls
    // `interpolatedContent.resolved(for: colorScheme)`.
    val content = interpolated.resolved(isDark)

    // Seed a MaterialTheme color scheme from the resolved
    // content colors so child Material widgets pick up the brand colors. The
    // sub-views still resolve per-token colors directly off `content` so
    // un-styled spots use the platform fallback rather than a brand color
    // when authors leave a field blank.
    val baseScheme = if (isDark) darkColorScheme() else lightColorScheme()
    val seeded = baseScheme.copy(
        primary = content.button_color?.let { StyleEngine.parseColor(it) } ?: baseScheme.primary,
        surface = content.background_color?.let { StyleEngine.parseColor(it) } ?: baseScheme.surface,
        background = content.background_color?.let { StyleEngine.parseColor(it) } ?: baseScheme.background,
        onSurface = content.text_color?.let { StyleEngine.parseColor(it) } ?: baseScheme.onSurface,
        onBackground = content.text_color?.let { StyleEngine.parseColor(it) } ?: baseScheme.onBackground,
        onPrimary = content.button_text_color?.let { StyleEngine.parseColor(it) } ?: baseScheme.onPrimary,
    )

    MaterialTheme(colorScheme = seeded) {
        when (config.message_type) {
            MessageType.BANNER -> BannerMessageView(content, onCTATap, onDismiss)
            MessageType.MODAL -> ModalMessageView(content, onCTATap, onDismiss)
            MessageType.FULLSCREEN -> FullscreenMessageView(content, onCTATap, onDismiss)
            MessageType.TOOLTIP -> TooltipMessageView(content, onCTATap, onDismiss)
        }
    }
}

// MARK: - Banner

@Composable
private fun BannerMessageView(
    content: MessageContent,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTop = content.banner_position != "bottom"
    var isVisible by remember { mutableStateOf(false) }
    val currentView = LocalView.current
    val dismissScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        isVisible = true
        // iOS parity — haptic on appear,
        // matches iOS BannerView.swift:29.
        HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
        content.auto_dismiss_seconds?.let { seconds ->
            if (seconds > 0) {
                delay(seconds * 1000L)
                isVisible = false
                delay(300L)
                onDismiss()
            }
        }
    }

    val textColor = content.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val buttonColor = content.button_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF6366F1)
    // Fall back to MaterialTheme.surface so dark
    // mode picks up the seeded scheme color rather than hard-coded white.
    val bgColor = content.background_color?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.surface
    val cornerRadius = content.corner_radius ?: 12

    Box(modifier = Modifier.fillMaxSize()) {
        // Calibrated spring matches iOS
        // BannerView.swift:30 `withAnimation(.spring(response: 0.4,
        // dampingFraction: 0.8))`. Compose default spring is
        // `StiffnessMediumLow / NoBouncy` — non-bouncy + slower-settling.
        val bannerSpring = spring<IntOffset>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
        val bannerFadeSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(
                initialOffsetY = { if (isTop) -it else it },
                animationSpec = bannerSpring,
            ) + fadeIn(animationSpec = bannerFadeSpring),
            exit = slideOutVertically(
                targetOffsetY = { if (isTop) -it else it },
                animationSpec = bannerSpring,
            ) + fadeOut(animationSpec = bannerFadeSpring),
            modifier = Modifier.align(if (isTop) Alignment.TopCenter else Alignment.BottomCenter),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(if (isTop) PaddingValues(top = 8.dp) else PaddingValues(bottom = 8.dp)),
                shape = RoundedCornerShape(cornerRadius.dp),
                colors = CardDefaults.cardColors(containerColor = bgColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Text content
                        Column(modifier = Modifier.weight(1f)) {
                            content.title?.let {
                                Text(
                                    text = it,
                                    // 11 M-35
                                    // — apply title_font_size + font_family
                                    // (parsed but previously ignored).
                                    // Banner title default 15sp matches
                                    // iOS BannerView.swift:49 `.subheadline.bold`
                                    // (.subheadline = 15pt). Was 14.
                                    fontSize = (content.title_font_size ?: 15.0).sp,
                                    fontFamily = FontResolver.resolve(content.font_family),
                                    fontWeight = FontWeight.Bold,
                                    color = textColor,
                                    // Banner title acts as
                                    // the heading for the message surface.
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            content.body?.let {
                                Text(
                                    text = it,
                                    fontSize = (content.body_font_size ?: 12.0).sp,
                                    fontFamily = FontResolver.resolve(content.font_family),
                                    color = if (textColor != Color.Unspecified)
                                        textColor.copy(alpha = 0.7f)
                                    else Color.Gray,
                                )
                            }
                        }

                        // CTA button with optional icon
                        content.cta_text?.let { ctaText ->
                            Button(
                                onClick = {
                                    // Fire the on_button_tap haptic like modal/fullscreen +
                                    // iOS BannerView; banner CTA silently dropped a configured haptic.
                                    HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
                                    onCTATap()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                                // CTA shape uses
                                // `content.button_corner_radius ?? 8` matching
                                // iOS BannerView.swift:78. Was reusing the
                                // banner card corner_radius — silently dropped
                                // every console-set button_corner_radius.
                                shape = RoundedCornerShape((content.button_corner_radius ?: 8).dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                // CTA icon
                                val ctaIcon = resolveIcon(content.cta_icon)
                                if (ctaIcon != null) {
                                    IconView(ref = ctaIcon, defaultSize = 12f)
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(ctaText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Dismiss button
                        val dismissCd = stringResource(R.string.appdna_a11y_message_close)
                        IconButton(
                            onClick = {
                                // \u2014 flip visible + delay 300ms so the slide/fade exit plays
                                // before the ComposeView is torn down, matching iOS BannerView + Android's
                                // own banner auto-dismiss path (was tearing down synchronously \u2192 instant).
                                dismissScope.launch {
                                    isVisible = false
                                    delay(300L)
                                    onDismiss()
                                }
                            },
                            // \u2014 close X uses Material Close
                            // icon (was Text glyph); mirrors iOS SF Symbol
                            // `xmark`. a11y label attached for TalkBack.
                            modifier = Modifier
                                .size(24.dp)
                                .semantics { contentDescription = dismissCd },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }

                    // Secondary CTA (Gap #18)
                    content.secondary_cta_text?.let { secondaryText ->
                        TextButton(
                            onClick = { onDismiss() },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = secondaryText,
                                color = if (textColor != Color.Unspecified)
                                    textColor.copy(alpha = 0.6f)
                                else Color(0xFF1A1A1A).copy(alpha = 0.6f),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Modal

@Composable
private fun ModalMessageView(
    content: MessageContent,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textColor = content.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val buttonColor = content.button_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF6366F1)
    // Fall back to MaterialTheme.surface so dark
    // mode picks up the seeded scheme color rather than hard-coded white.
    val bgColor = content.background_color?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.surface
    // corner_radius default 12 matches iOS ModalView.swift:134
    // `content.corner_radius ?? 12`. Was 20 — modal cards rendered with
    // dramatically rounder corners than iOS.
    val cornerRadius = content.corner_radius ?: 12
    val currentView = LocalView.current
    var showConfetti by remember { mutableStateOf(false) }

    // Haptic on appear
    LaunchedEffect(Unit) {
        HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
        // Auto-trigger confetti on appear
        if (content.particle_effect?.trigger == "on_appear") {
            showConfetti = true
        }
    }

    // Backdrop
    val backdropInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                // Blur backdrop for glassmorphism
                if (content.blur_backdrop != null) {
                    // iOS ModalView keeps a 0.3 black dim UNDER the blur material; Android
                    // had only the blur tint (no dark dim). Layer the dim then the blur, matching iOS.
                    Modifier
                        .background(Color.Black.copy(alpha = 0.3f))
                        .applyBlurBackdrop(content.blur_backdrop, 0f)
                } else {
                    // Non-blur scrim = black @ 0.4, matching iOS ModalView:21 (was 0.5).
                    Modifier.background(Color.Black.copy(alpha = 0.4f))
                }
            )
            // Null indication suppresses Material
            // ripple on the dimmed scrim, matching iOS ModalView.swift:23
            // `.onTapGesture` (no ripple). Was flashing a ripple highlight
            // over the dim layer on tap-to-dismiss.
            .clickable(
                interactionSource = backdropInteractionSource,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Modal card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clickable(enabled = false, onClick = {}), // Prevent click-through
            shape = RoundedCornerShape(cornerRadius.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Dismiss button
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val modalDismissCd = stringResource(R.string.appdna_a11y_message_close)
                    IconButton(
                        onClick = onDismiss,
                        // \u2014 modal close X uses Material
                        // Close icon (mirrors iOS SF Symbol `xmark`).
                        modifier = Modifier
                            .size(32.dp)
                            .semantics { contentDescription = modalDismissCd },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Lottie/Rive hero (preferred over static image)
                if (content.lottie_url != null) {
                    LottieBlockView(
                        block = LottieBlock(
                            lottie_url = content.lottie_url,
                            autoplay = true,
                            loop = true,
                            height = 160f,
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (content.rive_url != null) {
                    RiveBlockView(
                        block = RiveBlock(
                            rive_url = content.rive_url,
                            height = 160f,
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (content.video_url != null) {
                    // Video hero
                    CoreVideoBlockView(
                        block = CoreVideoBlock(
                            video_url = content.video_url,
                            video_thumbnail_url = content.video_thumbnail_url ?: content.image_url,
                            video_height = 160f,
                            video_corner_radius = 8f,
                            autoplay = true,
                            muted = true,
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    // Fallback: Optional static image
                    content.image_url?.let { imageUrl ->
                        NetworkImage(
                            url = imageUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            // Mirror iOS ModalView.swift:66 `.scaledToFit()` so
                            // portrait posters render fully (Crop was cutting
                            // top/bottom on tall images).
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // Title
                content.title?.let {
                    Text(
                        text = it,
                        // 11 M-35 — apply
                        // title_font_size + font_family.
                        fontSize = (content.title_font_size ?: 20.0).sp,
                        fontFamily = FontResolver.resolve(content.font_family),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = textColor,
                        // Modal message title is the screen
                        // heading for accessibility.
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Body
                content.body?.let {
                    Text(
                        text = it,
                        // Modal body default 17sp matches iOS
                        // ModalView.swift:84 `.body` font (.body = 17pt).
                        // Was 16sp.
                        fontSize = (content.body_font_size ?: 17.0).sp,
                        fontFamily = FontResolver.resolve(content.font_family),
                        textAlign = TextAlign.Center,
                        color = if (textColor != Color.Unspecified)
                            textColor.copy(alpha = 0.7f)
                        else Color.Gray,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // CTA button with optional icon
                content.cta_text?.let { ctaText ->
                    Button(
                        onClick = {
                            // Haptic on CTA tap
                            HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
                            onCTATap()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                        // CTA shape uses
                        // `content.button_corner_radius ?? 8` matching iOS
                        // ModalView.swift:107. Was reusing modal card
                        // corner_radius default 12 — dropped every
                        // console-set button_corner_radius.
                        shape = RoundedCornerShape((content.button_corner_radius ?: 8).dp),
                    ) {
                        // CTA icon
                        val ctaIcon = resolveIcon(content.cta_icon)
                        if (ctaIcon != null) {
                            IconView(ref = ctaIcon, defaultSize = 18f)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(ctaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                // Secondary CTA with optional icon (Gap #18)
                content.secondary_cta_text?.let { secondaryText ->
                    TextButton(onClick = { onDismiss() }) {
                        val secondaryIcon = resolveIcon(content.secondary_cta_icon)
                        if (secondaryIcon != null) {
                            IconView(ref = secondaryIcon, defaultSize = 14f)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = secondaryText,
                            color = if (textColor != Color.Unspecified)
                                textColor.copy(alpha = 0.6f)
                            else Color(0xFF1A1A1A).copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                }

                // Dismiss text
                content.dismiss_text?.let { dismissText ->
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text(dismissText, fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        }

        // Confetti overlay
        if (showConfetti && content.particle_effect != null) {
            ConfettiOverlay(
                effect = content.particle_effect,
                trigger = showConfetti,
            )
        }
    }
}

// MARK: - Fullscreen

@Composable
private fun FullscreenMessageView(
    content: MessageContent,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textColor = content.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val buttonColor = content.button_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF6366F1)
    // Fall back to MaterialTheme.surface so dark
    // mode picks up the seeded scheme color rather than hard-coded white.
    val bgColor = content.background_color?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.surface
    val cornerRadius = content.corner_radius ?: 14
    val currentView = LocalView.current
    var showConfetti by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // iOS parity — haptic on appear,
        // matches iOS FullscreenView.swift:145.
        HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
        if (content.particle_effect?.trigger == "on_appear") {
            showConfetti = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Lottie/Rive/Video hero (preferred over static image)
            if (content.lottie_url != null) {
                LottieBlockView(
                    block = LottieBlock(
                        lottie_url = content.lottie_url,
                        autoplay = true,
                        loop = true,
                        height = 280f,
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))
            } else if (content.rive_url != null) {
                RiveBlockView(
                    block = RiveBlock(
                        rive_url = content.rive_url,
                        height = 280f,
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))
            } else if (content.video_url != null) {
                CoreVideoBlockView(
                    block = CoreVideoBlock(
                        video_url = content.video_url,
                        video_thumbnail_url = content.video_thumbnail_url ?: content.image_url,
                        video_height = 280f,
                        video_corner_radius = 16f,
                        autoplay = true,
                        muted = true,
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))
            } else {
                // Fallback: Optional static image
                content.image_url?.let { imageUrl ->
                    NetworkImage(
                        url = imageUrl,
                        modifier = Modifier
                            .size(300.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Title
            content.title?.let {
                Text(
                    text = it,
                    // 11 M-35
                    // Fullscreen title 34sp matches iOS FullscreenView
                    // .swift:64 `.largeTitle.bold` (.largeTitle = 34pt).
                    // Was 32.
                    fontSize = (content.title_font_size ?: 34.0).sp,
                    fontFamily = FontResolver.resolve(content.font_family),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = textColor,
                    // Fullscreen message title is the
                    // screen heading for accessibility.
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Body
            content.body?.let {
                Text(
                    text = it,
                    // Fullscreen body 17sp matches iOS FullscreenView
                    // .swift:73 `.body` font (.body = 17pt). Was 16.
                    fontSize = (content.body_font_size ?: 17.0).sp,
                    fontFamily = FontResolver.resolve(content.font_family),
                    textAlign = TextAlign.Center,
                    color = if (textColor != Color.Unspecified)
                        textColor.copy(alpha = 0.7f)
                    else Color.Gray,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // CTA button with optional icon
            content.cta_text?.let { ctaText ->
                Button(
                    onClick = {
                        HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
                        onCTATap()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    // CTA shape uses
                    // `content.button_corner_radius ?? 8` matching iOS
                    // FullscreenView.swift:99. Was reusing fullscreen card
                    // corner_radius — dropped console-set button radius.
                    shape = RoundedCornerShape((content.button_corner_radius ?: 8).dp),
                ) {
                    val ctaIcon = resolveIcon(content.cta_icon)
                    if (ctaIcon != null) {
                        IconView(ref = ctaIcon, defaultSize = 18f)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(ctaText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // Secondary CTA with optional icon (Gap #18)
            content.secondary_cta_text?.let { secondaryText ->
                TextButton(onClick = { onDismiss() }) {
                    val secondaryIcon = resolveIcon(content.secondary_cta_icon)
                    if (secondaryIcon != null) {
                        IconView(ref = secondaryIcon, defaultSize = 14f)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = secondaryText,
                        color = if (textColor != Color.Unspecified)
                            textColor.copy(alpha = 0.6f)
                        else Color(0xFF1A1A1A).copy(alpha = 0.6f),
                        fontSize = 13.sp,
                    )
                }
            }

            // Dismiss text
            content.dismiss_text?.let { dismissText ->
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(dismissText, fontSize = 14.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Close button
        val fullscreenCloseCd = stringResource(R.string.appdna_a11y_message_close)
        IconButton(
            onClick = onDismiss,
            // \u2014 fullscreen close X (Text glyph).
            // \u2014 no background fill,
            // matches iOS FullscreenView.swift:129-135 which renders just the
            // xmark glyph inside a clipShape(Circle()) with no .background.
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(36.dp)
                .semantics { contentDescription = fullscreenCloseCd },
        ) {
            // Mirror iOS SF Symbol `xmark` (was Text glyph).
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = Color.DarkGray,
                modifier = Modifier.size(18.dp),
            )
        }

        // Confetti overlay
        if (showConfetti && content.particle_effect != null) {
            ConfettiOverlay(
                effect = content.particle_effect,
                trigger = showConfetti,
            )
        }
    }
}

// MARK: - Tooltip

@Composable
private fun TooltipMessageView(
    content: MessageContent,
    onCTATap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textColor = content.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val buttonColor = content.button_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF6366F1)
    // Fall back to MaterialTheme.surface so dark
    // mode picks up the seeded scheme color rather than hard-coded white.
    val bgColor = content.background_color?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.surface
    val cornerRadius = content.corner_radius ?: 12
    val currentView = LocalView.current
    var isDismissed by remember { mutableStateOf(false) }
    val dismissScope = rememberCoroutineScope()

    // iOS parity:
    // - haptic on appear (iOS TooltipView.swift:23)
    // - auto_dismiss_seconds timer (iOS TooltipView.swift:28-33)
    LaunchedEffect(Unit) {
        HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
        content.auto_dismiss_seconds?.let { seconds ->
            if (seconds > 0) {
                delay(seconds * 1000L)
                if (!isDismissed) {
                    isDismissed = true
                    onDismiss()
                }
            }
        }
    }

    // Tooltip is a NON-BLOCKING popover, not a
    // modal. iOS TooltipView.swift:20 uses `Color.black.opacity(0.01)`
    // (tap-through transparent) with NO `.onTapGesture` on the backdrop.
    // Android was rendering 30% opaque scrim AND tap-outside-to-dismiss —
    // visually + behaviorally a modal. Drop both: scrim opacity → 0
    // (transparent, no visual change) + remove `clickable(onDismiss)` so
    // host-app taps fall through. Dismiss remains via inline xmark / cta /
    // auto-dismiss — same surface area as iOS.
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Entry transition matches iOS
        // TooltipView.swift:15-26 `if isVisible { … }.transition(.move(edge:
        // .bottom).combined(with: .opacity))`. Drives a `var isVisible by
        // remember` flipped to true via LaunchedEffect on first composition,
        // so the tooltip slides up + fades in instead of popping in instant.
        var tooltipVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { tooltipVisible = true }
        AnimatedVisibility(
            visible = tooltipVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
            ) + fadeIn(
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
            ),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(bottom = 32.dp),
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 280.dp),
                shape = RoundedCornerShape(cornerRadius.dp),
                colors = CardDefaults.cardColors(containerColor = bgColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Title row with xmark close button — matches iOS
                    // TooltipView.swift:56-63 `HStack { Text(title); xmark }`.
                    content.title?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = it,
                                // Tooltip title default 15sp matches
                                // iOS TooltipView.swift:44 `.subheadline.bold`
                                // (.subheadline = 15pt). Was 14.
                                fontSize = (content.title_font_size ?: 15.0).sp,
                                fontFamily = FontResolver.resolve(content.font_family),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Start,
                                color = textColor,
                                // Tooltip title acts as the
                                // heading for the tooltip content.
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            // iOS parity
                            // inline xmark close button mirrors iOS
                            // TooltipView.swift:56-63 so the user has a
                            // visible affordance even if backdrop tap is
                            // blocked or unclear.
                            IconButton(onClick = {
                                // Animate the fade/scale-out before teardown (matches iOS
                                // TooltipView), rather than tearing the ComposeView down instantly.
                                dismissScope.launch {
                                    tooltipVisible = false
                                    delay(300L)
                                    onDismiss()
                                }
                            }, modifier = Modifier.size(24.dp)) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close",
                                    tint = if (textColor != Color.Unspecified)
                                        textColor.copy(alpha = 0.6f)
                                    else Color.Gray,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Body — mirror iOS TooltipView.swift:47-51 (no
                    // multilineTextAlignment → leading); centering the
                    // tooltip body diverged from iOS card.
                    content.body?.let {
                        Text(
                            text = it,
                            fontSize = (content.body_font_size ?: 12.0).sp,
                            fontFamily = FontResolver.resolve(content.font_family),
                            color = if (textColor != Color.Unspecified)
                                textColor.copy(alpha = 0.7f)
                            else Color.Gray,
                        )
                    }

                    // CTA button with optional icon — corner_radius follows
                    // button_corner_radius (was reusing the tooltip card
                    // corner_radius, diverging from banner/modal/fullscreen).
                    content.cta_text?.let { ctaText ->
                        Spacer(modifier = Modifier.height(12.dp))
                        // The tooltip CTA is a colored TEXT-LINK on iOS (TooltipView:
                        // `button_color` colors the TEXT, no fill/shape), NOT a filled pill like the
                        // other three message types. Android had unified it into a filled Button, so
                        // `button_color` meant the opposite (background fill, white text). Match iOS:
                        // unfilled TextButton, button_color as the text color.
                        TextButton(
                            onClick = {
                                // on_button_tap haptic like modal/fullscreen + iOS TooltipView.
                                HapticEngine.triggerIfEnabled(currentView, content.haptic?.triggers?.on_button_tap, content.haptic)
                                onCTATap()
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                        ) {
                            // CTA icon
                            val ctaIcon = resolveIcon(content.cta_icon)
                            if (ctaIcon != null) {
                                IconView(ref = ctaIcon, defaultSize = 12f)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(ctaText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = buttonColor)
                        }
                    }

                    // Secondary CTA with optional icon (Gap #18)
                    content.secondary_cta_text?.let { secondaryText ->
                        TextButton(onClick = { onDismiss() }) {
                            val secondaryIcon = resolveIcon(content.secondary_cta_icon)
                            if (secondaryIcon != null) {
                                IconView(ref = secondaryIcon, defaultSize = 13f)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = secondaryText,
                                color = if (textColor != Color.Unspecified)
                                    textColor.copy(alpha = 0.6f)
                                else Color(0xFF1A1A1A).copy(alpha = 0.6f),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            // Pointer arrow (Gap #17).
            // — soft drop-shadow matches iOS TooltipView shadow(0.08 opacity).
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .shadow(
                        elevation = 2.dp,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color.Black.copy(alpha = 0.08f),
                    )
                    .rotate(45f)
                    .offset(y = (-6).dp)
                    .background(bgColor),
            )
        }
        } // AnimatedVisibility — entry transition
    }
}
