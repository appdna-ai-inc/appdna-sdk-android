package ai.appdna.sdk.core

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class IconReference(
    val library: String,   // "lucide", "sf-symbols", "material", "emoji"
    val name: String,
    val color: String? = null,
    val size: Float? = null,
)

@Composable
fun IconView(
    ref: IconReference,
    modifier: Modifier = Modifier,
    defaultSize: Float = 24f,
) {
    val iconSize = (ref.size ?: defaultSize).dp
    val iconColor = ref.color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified

    // The vector, when any table reaches one (`iconVector`, the one ladder tests read); else each library's fallback.
    iconVector(ref)?.let { vector ->
        Icon(imageVector = vector, contentDescription = null, modifier = modifier.size(iconSize), tint = iconColor)
        return
    }
    when (ref.library) {
        "material" -> {
            val vector = MaterialIconMapping.resolve(ref.name)
            if (vector != null) {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    modifier = modifier.size(iconSize),
                    tint = iconColor,
                )
            } else {
                // Fallback to emoji mapping
                val emoji = MaterialIconMapping.toEmoji[ref.name]
                Text(
                    text = emoji ?: "?",
                    fontSize = (ref.size ?: defaultSize).sp,
                    modifier = modifier,
                )
            }
        }

        "lucide" -> {
            // Map Lucide -> Material Icons (Android native)
            val materialName = LucideIconMapping.toMaterial[ref.name]
            if (materialName != null) {
                val vector = MaterialIconMapping.resolve(materialName)
                if (vector != null) {
                    Icon(
                        imageVector = vector,
                        contentDescription = null,
                        modifier = modifier.size(iconSize),
                        tint = iconColor,
                    )
                    return
                }
            }
            // Fallback to emoji
            val emoji = LucideIconMapping.toEmoji[lucideKey(ref.name)]
            Text(
                text = emoji ?: "?",
                fontSize = (ref.size ?: defaultSize).sp,
                modifier = modifier,
            )
        }

        "sf-symbols" -> {
            /*
             * Map SF Symbols -> Material Icons, then try the name AS a Material name.
             *
             * The second rung matters because a bare string reaches here now (see `resolveIcon`),
             * and the common bare values are plain words — `star`, `home`, `settings` — which ARE
             * valid SF Symbols, so iOS draws them, and are valid Material names too. Without it
             * they would fall to the emoji table and then to "?".
             */
            val materialName = SFSymbolMapping.toMaterial[ref.name] ?: ref.name
            val vector = MaterialIconMapping.resolve(materialName)
            if (vector != null) {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    modifier = modifier.size(iconSize),
                    tint = iconColor,
                )
                return
            }
            // Fallback to emoji
            val emoji = SFSymbolMapping.toEmoji[ref.name]
            Text(
                text = emoji ?: "?",
                fontSize = (ref.size ?: defaultSize).sp,
                modifier = modifier,
            )
        }

        else -> {
            // "emoji" or unknown -- render as text
            Text(
                text = ref.name,
                fontSize = (ref.size ?: defaultSize).sp,
                modifier = modifier,
            )
        }
    }
}

/**
 * The vector [IconView] draws for [ref], or null when it falls back to an emoji or a "?":
 *  - `material`: the name in the Material table;
 *  - `lucide`: the name (a component name such as `CheckCircle` read as `check-circle`) through the Lucide table;
 *  - `sf-symbols`: the name through the SF Symbol table, else the name itself as a Material name.
 * Shared fixture `icon_name_resolution` holds every name the console offers or writes; iOS draws each of them too.
 */
internal fun iconVector(ref: IconReference): ImageVector? = when (ref.library) {
    "material" -> MaterialIconMapping.resolve(ref.name)
    "lucide" -> LucideIconMapping.toMaterial[lucideKey(ref.name)]?.let { MaterialIconMapping.resolve(it) }
    "sf-symbols" -> MaterialIconMapping.resolve(SFSymbolMapping.toMaterial[ref.name] ?: ref.name)
    else -> null
}

/** A Lucide name as the tables key it: `check-circle` stays; the component name `CheckCircle` becomes `check-circle`. */
internal fun lucideKey(name: String): String {
    if (name.none { it.isUpperCase() }) return name
    val out = StringBuilder()
    name.forEachIndexed { i, ch ->
        if (ch.isUpperCase() && i > 0) out.append('-')
        out.append(ch.lowercaseChar())
    }
    return out.toString()
}

/**
 * Reads the two shapes `IconRefSchema` accepts — a bare string, or `{ library, name, color, size }`.
 *
 * 🔴 A BARE STRING IS NOT ALWAYS AN EMOJI, and calling it one printed the identifier on screen.
 * This returned `library = "emoji"` for every string, and `IconView`'s emoji branch draws the name
 * as text — so an authored `"star.fill"` rendered as a real SF Symbol on iOS and as the literal
 * words `star.fill` on Android, on every surface that takes an icon. iOS classifies by the first
 * scalar (`IconReference.init(from:)`); this is the same test, so the two agree, and the
 * `sf-symbols` branch below then maps the name to a Material icon.
 */
fun resolveIcon(value: Any?): IconReference? {
    if (value is IconReference) return value
    if (value is Map<*, *>) {
        val library = value["library"] as? String ?: return null
        val name = value["name"] as? String ?: return null
        return IconReference(
            library = library,
            name = name,
            color = value["color"] as? String,
            size = (value["size"] as? Number)?.toFloat(),
        )
    }
    if (value is String && value.isNotEmpty()) {
        return IconReference(library = if (isEmojiGlyph(value)) "emoji" else "sf-symbols", name = value)
    }
    return null
}

/**
 * Whether a bare icon string is an emoji rather than a symbol NAME.
 *
 * `OTHER_SYMBOL` is the Unicode category every pictographic emoji and every regional-indicator
 * flag sits in, and no identifier like `star.fill` or `thumb_up` does — which is the same
 * distinction iOS draws with `isEmojiPresentation || generalCategory == .otherSymbol`.
 */
internal fun isEmojiGlyph(value: String): Boolean {
    if (value.isEmpty()) return false
    val type = Character.getType(value.codePointAt(0))
    return type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt()
}

object MaterialIconMapping {
    fun resolve(name: String): ImageVector? = materialIcons[name]

    private val materialIcons: Map<String, ImageVector> = mapOf(
        "check_circle" to Icons.Filled.CheckCircle,
        "cancel" to Icons.Filled.Close,
        "star" to Icons.Filled.Star,
        "favorite" to Icons.Filled.Favorite,
        "home" to Icons.Filled.Home,
        "settings" to Icons.Filled.Settings,
        "person" to Icons.Filled.Person,
        "search" to Icons.Filled.Search,
        "notifications" to Icons.Filled.Notifications,
        "email" to Icons.Filled.Email,
        "phone" to Icons.Filled.Phone,
        "camera_alt" to Icons.Filled.CameraAlt,
        "image" to Icons.Filled.Image,
        "calendar_today" to Icons.Filled.DateRange,
        "schedule" to Icons.Filled.Schedule,
        "place" to Icons.Filled.Place,
        "add" to Icons.Filled.Add,
        "remove" to Icons.Filled.Remove,
        "edit" to Icons.Filled.Edit,
        "delete" to Icons.Filled.Delete,
        "share" to Icons.Filled.Share,
        "lock" to Icons.Filled.Lock,
        "visibility" to Icons.Filled.Visibility,
        "visibility_off" to Icons.Filled.VisibilityOff,
        "thumb_up" to Icons.Filled.ThumbUp,
        "info" to Icons.Filled.Info,
        "warning" to Icons.Filled.Warning,
        "error" to Icons.Filled.Error,
        "send" to Icons.Filled.Send,
        "chat" to Icons.Filled.Chat,
        "language" to Icons.Filled.Language,
        "code" to Icons.Filled.Code,
        "credit_card" to Icons.Filled.CreditCard,
        "shopping_cart" to Icons.Filled.ShoppingCart,
        "account_circle" to Icons.Filled.AccountCircle,
        "arrow_back" to Icons.Filled.ArrowBack,
        "arrow_forward" to Icons.Filled.ArrowForward,
        "refresh" to Icons.Filled.Refresh,
        "menu" to Icons.Filled.Menu,
        "more_vert" to Icons.Filled.MoreVert,
        "done" to Icons.Filled.Done,
        "clear" to Icons.Filled.Clear,
        // The paywall guarantee badge's shields (its default is `checkmark.shield.fill`).
        "verified_user" to Icons.Filled.VerifiedUser,
        "shield" to Icons.Filled.Shield,
        // Every Material name the SF Symbol, Lucide and Material tables below can reach, so each one draws (the
        // shared fixture `icon_name_resolution` holds every name the console offers or writes).
        "all_inclusive" to Icons.Filled.AllInclusive,
        "arrow_downward" to Icons.Filled.ArrowDownward,
        "arrow_upward" to Icons.Filled.ArrowUpward,
        "auto_awesome" to Icons.Filled.AutoAwesome,
        "bar_chart" to Icons.Filled.BarChart,
        "battery_full" to Icons.Filled.BatteryFull,
        "bolt" to Icons.Filled.Bolt,
        "bookmark" to Icons.Filled.Bookmark,
        "card_giftcard" to Icons.Filled.CardGiftcard,
        "check_circle_outline" to Icons.Filled.CheckCircleOutline,
        "chevron_left" to Icons.Filled.ChevronLeft,
        "chevron_right" to Icons.Filled.ChevronRight,
        "cloud" to Icons.Filled.Cloud,
        "content_copy" to Icons.Filled.ContentCopy,
        "dark_mode" to Icons.Filled.DarkMode,
        "description" to Icons.Filled.Description,
        "download" to Icons.Filled.Download,
        "emoji_events" to Icons.Filled.EmojiEvents,
        "expand_less" to Icons.Filled.ExpandLess,
        "expand_more" to Icons.Filled.ExpandMore,
        "filter_list" to Icons.Filled.FilterList,
        "flag" to Icons.Filled.Flag,
        "folder" to Icons.Filled.Folder,
        "grid_view" to Icons.Filled.GridView,
        "group" to Icons.Filled.Group,
        "help" to Icons.Filled.Help,
        "info_outline" to Icons.Filled.Info,
        "inventory_2" to Icons.Filled.Inventory2,
        "layers" to Icons.Filled.Layers,
        "link" to Icons.Filled.Link,
        "list" to Icons.Filled.List,
        "local_fire_department" to Icons.Filled.LocalFireDepartment,
        "lock_open" to Icons.Filled.LockOpen,
        "memory" to Icons.Filled.Memory,
        "monetization_on" to Icons.Filled.MonetizationOn,
        "monitor_heart" to Icons.Filled.MonitorHeart,
        "music_note" to Icons.Filled.MusicNote,
        "near_me" to Icons.Filled.NearMe,
        "open_in_new" to Icons.Filled.OpenInNew,
        "palette" to Icons.Filled.Palette,
        "pie_chart" to Icons.Filled.PieChart,
        "sell" to Icons.Filled.Sell,
        "sentiment_dissatisfied" to Icons.Filled.SentimentDissatisfied,
        "sentiment_satisfied" to Icons.Filled.SentimentSatisfied,
        "storage" to Icons.Filled.Storage,
        "terminal" to Icons.Filled.Terminal,
        "thumb_down" to Icons.Filled.ThumbDown,
        "trending_up" to Icons.Filled.TrendingUp,
        "tune" to Icons.Filled.Tune,
        "upload" to Icons.Filled.Upload,
        "videocam" to Icons.Filled.Videocam,
        "wb_sunny" to Icons.Filled.WbSunny,
        "wifi" to Icons.Filled.Wifi,
        "workspace_premium" to Icons.Filled.WorkspacePremium,
    )

    val toEmoji: Map<String, String> = mapOf(
        "check_circle" to "\u2705",
        "star" to "\u2B50",
        "favorite" to "\u2764\uFE0F",
        "home" to "\uD83C\uDFE0",
        "notifications" to "\uD83D\uDD14",
        "email" to "\uD83D\uDCE7",
        "phone" to "\uD83D\uDCF1",
        "camera_alt" to "\uD83D\uDCF7",
        "calendar_today" to "\uD83D\uDCC5",
        "schedule" to "\uD83D\uDD50",
        "place" to "\uD83D\uDCCD",
        "thumb_up" to "\uD83D\uDC4D",
        "language" to "\uD83C\uDF0D",
        "shopping_cart" to "\uD83D\uDED2",
        "lock" to "\uD83D\uDD12",
    )
}

object LucideIconMapping {
    val toMaterial: Map<String, String> = mapOf(
        "check" to "done",
        "check-circle" to "check_circle",
        "x" to "clear",
        "star" to "star",
        "heart" to "favorite",
        "home" to "home",
        "settings" to "settings",
        "user" to "person",
        "search" to "search",
        "bell" to "notifications",
        "mail" to "email",
        "phone" to "phone",
        "camera" to "camera_alt",
        "image" to "image",
        "calendar" to "calendar_today",
        "clock" to "schedule",
        "map-pin" to "place",
        "plus" to "add",
        "minus" to "remove",
        "edit" to "edit",
        "trash" to "delete",
        "share" to "share",
        "lock" to "lock",
        "eye" to "visibility",
        "eye-off" to "visibility_off",
        "thumbs-up" to "thumb_up",
        "info" to "info",
        "alert-circle" to "error",
        "send" to "send",
        "message-circle" to "chat",
        "globe" to "language",
        "code" to "code",
        "credit-card" to "credit_card",
        "shopping-cart" to "shopping_cart",
        "arrow-left" to "arrow_back",
        "arrow-right" to "arrow_forward",
        "refresh-cw" to "refresh",
        "menu" to "menu",
        "more-vertical" to "more_vert",
        // Every Lucide name iOS draws (`SFSymbolTables.lucide`), through the SF Symbol it draws there.
        "activity" to "monitor_heart",
        "arrow-down" to "arrow_downward",
        "arrow-up" to "arrow_upward",
        "bar-chart" to "bar_chart",
        "battery" to "battery_full",
        "bookmark" to "bookmark",
        "chevron-down" to "expand_more",
        "chevron-left" to "chevron_left",
        "chevron-right" to "chevron_right",
        "chevron-up" to "expand_less",
        "cloud" to "cloud",
        "copy" to "content_copy",
        "cpu" to "memory",
        "crown" to "workspace_premium",
        "dollar-sign" to "monetization_on",
        "download" to "download",
        "external-link" to "open_in_new",
        "file" to "description",
        "filter" to "filter_list",
        "flag" to "flag",
        "folder" to "folder",
        "frown" to "sentiment_dissatisfied",
        "gift" to "card_giftcard",
        "grid" to "grid_view",
        "hard-drive" to "storage",
        "help-circle" to "help",
        "layers" to "layers",
        "link" to "link",
        "list" to "list",
        "moon" to "dark_mode",
        "music" to "music_note",
        "navigation" to "near_me",
        "package" to "inventory_2",
        "palette" to "palette",
        "pie-chart" to "pie_chart",
        "rocket" to "local_fire_department",
        "shield" to "shield",
        "sliders" to "tune",
        "smile" to "sentiment_satisfied",
        "sparkles" to "auto_awesome",
        "sun" to "wb_sunny",
        "tag" to "sell",
        "terminal" to "terminal",
        "thumbs-down" to "thumb_down",
        "trending-up" to "trending_up",
        "trophy" to "emoji_events",
        "unlock" to "lock_open",
        "upload" to "upload",
        "users" to "group",
        "video" to "videocam",
        "wifi" to "wifi",
        "x-circle" to "cancel",
        "zap" to "bolt",
    )

    val toEmoji: Map<String, String> = mapOf(
        "check" to "\u2713",
        "check-circle" to "\u2705",
        "star" to "\u2B50",
        "heart" to "\u2764\uFE0F",
        "home" to "\uD83C\uDFE0",
        "settings" to "\u2699\uFE0F",
        "user" to "\uD83D\uDC64",
        "bell" to "\uD83D\uDD14",
        "mail" to "\uD83D\uDCE7",
        "phone" to "\uD83D\uDCF1",
        "camera" to "\uD83D\uDCF7",
        "calendar" to "\uD83D\uDCC5",
        "clock" to "\uD83D\uDD50",
        "map-pin" to "\uD83D\uDCCD",
        "gift" to "\uD83C\uDF81",
        "shield" to "\uD83D\uDEE1\uFE0F",
        "trophy" to "\uD83C\uDFC6",
        "flag" to "\uD83D\uDEA9",
        "thumbs-up" to "\uD83D\uDC4D",
        "thumbs-down" to "\uD83D\uDC4E",
        "smile" to "\uD83D\uDE0A",
        "zap" to "\u26A1",
        "rocket" to "\uD83D\uDE80",
        "sparkles" to "\u2728",
        "crown" to "\uD83D\uDC51",
        "fire" to "\uD83D\uDD25",
        "lock" to "\uD83D\uDD12",
        "globe" to "\uD83C\uDF0D",
        "dollar-sign" to "\uD83D\uDCB0",
    )
}

object SFSymbolMapping {
    val toMaterial: Map<String, String> = mapOf(
        "checkmark.circle.fill" to "check_circle",
        "xmark.circle.fill" to "cancel",
        "star.fill" to "star",
        "heart.fill" to "favorite",
        "house.fill" to "home",
        "gearshape.fill" to "settings",
        "person.fill" to "person",
        "magnifyingglass" to "search",
        "bell.fill" to "notifications",
        "envelope.fill" to "email",
        "phone.fill" to "phone",
        "camera.fill" to "camera_alt",
        "photo.fill" to "image",
        "calendar" to "calendar_today",
        "clock.fill" to "schedule",
        "mappin.circle.fill" to "place",
        "plus" to "add",
        "minus" to "remove",
        "pencil" to "edit",
        "trash.fill" to "delete",
        "square.and.arrow.up" to "share",
        "lock.fill" to "lock",
        "eye.fill" to "visibility",
        "eye.slash.fill" to "visibility_off",
        "hand.thumbsup.fill" to "thumb_up",
        "info.circle.fill" to "info",
        "exclamationmark.circle.fill" to "error",
        "paperplane.fill" to "send",
        "bubble.left.fill" to "chat",
        "globe" to "language",
        // The guarantee badge's default and the shield names authors write for it.
        "shield.checkmark.fill" to "verified_user",
        "checkmark.shield.fill" to "verified_user",
        "lock.shield.fill" to "verified_user",
        "shield.fill" to "shield",
        "shield" to "shield",
        // Every SF Symbol iOS's Lucide and Material tables draw, the console's SF picker offers, and the SDKs or the
        // console write as a default, so none of them draws "?" here.
        "arrow.clockwise" to "refresh",
        "arrow.down" to "arrow_downward",
        "arrow.down.circle.fill" to "download",
        "arrow.left" to "arrow_back",
        "arrow.right" to "arrow_forward",
        "arrow.up" to "arrow_upward",
        "arrow.up.circle.fill" to "upload",
        "arrow.up.right.square" to "open_in_new",
        "battery.100" to "battery_full",
        "bolt.fill" to "bolt",
        "bookmark.fill" to "bookmark",
        "cart.fill" to "shopping_cart",
        "chart.bar.fill" to "bar_chart",
        "chart.line.uptrend.xyaxis" to "trending_up",
        "chart.pie.fill" to "pie_chart",
        "checkmark" to "done",
        "checkmark.circle" to "check_circle_outline",
        "chevron.down" to "expand_more",
        "chevron.left" to "chevron_left",
        "chevron.left.forwardslash.chevron.right" to "code",
        "chevron.right" to "chevron_right",
        "chevron.up" to "expand_less",
        "cloud.fill" to "cloud",
        "cpu" to "memory",
        "creditcard.fill" to "credit_card",
        "crown.fill" to "workspace_premium",
        "doc.fill" to "description",
        "doc.on.doc" to "content_copy",
        "dollarsign.circle.fill" to "monetization_on",
        "exclamationmark.triangle.fill" to "warning",
        "face.dashed" to "sentiment_dissatisfied",
        "face.smiling" to "sentiment_satisfied",
        "flag.fill" to "flag",
        "flame.fill" to "local_fire_department",
        "folder.fill" to "folder",
        "gift.fill" to "card_giftcard",
        "hand.thumbsdown.fill" to "thumb_down",
        "heart" to "favorite",
        "heart.circle.fill" to "favorite",
        "house" to "home",
        "infinity" to "all_inclusive",
        "info.circle" to "info_outline",
        "internaldrive" to "storage",
        "line.3.horizontal.decrease" to "filter_list",
        "link" to "link",
        "list.bullet" to "list",
        "location.fill" to "near_me",
        "lock.open.fill" to "lock_open",
        "moon.fill" to "dark_mode",
        "music.note" to "music_note",
        "paintpalette.fill" to "palette",
        "person.2.fill" to "group",
        "person.crop.circle.fill" to "account_circle",
        "questionmark.circle.fill" to "help",
        "shippingbox.fill" to "inventory_2",
        "slider.horizontal.3" to "tune",
        "sparkles" to "auto_awesome",
        "square.3.layers.3d" to "layers",
        "square.grid.2x2.fill" to "grid_view",
        "star" to "star",
        "sun.max.fill" to "wb_sunny",
        "tag.fill" to "sell",
        "terminal.fill" to "terminal",
        "trophy.fill" to "emoji_events",
        "video.fill" to "videocam",
        "waveform.path.ecg" to "monitor_heart",
        "wifi" to "wifi",
        "xmark" to "clear",
    )

    val toEmoji: Map<String, String> = mapOf(
        "checkmark.circle.fill" to "\u2705",
        "star.fill" to "\u2B50",
        "heart.fill" to "\u2764\uFE0F",
        "house.fill" to "\uD83C\uDFE0",
        "bell.fill" to "\uD83D\uDD14",
        "envelope.fill" to "\uD83D\uDCE7",
        "phone.fill" to "\uD83D\uDCF1",
        "camera.fill" to "\uD83D\uDCF7",
        "calendar" to "\uD83D\uDCC5",
        "clock.fill" to "\uD83D\uDD50",
        "mappin.circle.fill" to "\uD83D\uDCCD",
        "hand.thumbsup.fill" to "\uD83D\uDC4D",
        "globe" to "\uD83C\uDF0D",
    )
}
