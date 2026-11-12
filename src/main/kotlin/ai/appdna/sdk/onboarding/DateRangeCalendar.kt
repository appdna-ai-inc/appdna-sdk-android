package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.StyleEngine
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Locale

/**
 * The range calendar a Date Picker draws for `field_config.selection_mode: "range"` (iOS `DateRangeCalendarView`):
 * a month grid with the week's days across the top, the picked days joined by a bar in `range_color`, the two ends
 * filled with the accent. Tapping the month and year opens a list of years to jump to; the arrows move a month. Days
 * outside the picker's bounds (`min`/`max`, as `yyyy-MM-dd`) are dimmed and cannot be picked.
 */
@Composable
internal fun DateRangeCalendar(
    selection: DateRangeSelection,
    onSelection: (DateRangeSelection) -> Unit,
    minDay: String,
    maxDay: String,
    initialDay: String,
    accent: Color,
    rangeFill: Color,
    text: Color,
) {
    fun ymd(day: String) = day.split("-").map { it.toInt() }
    var shownYear by remember { mutableStateOf(ymd(initialDay)[0]) }
    var shownMonth by remember { mutableStateOf(ymd(initialDay)[1]) } // 1..12
    var showingYears by remember { mutableStateOf(false) }
    val (minY, minM) = ymd(minDay).let { it[0] to it[1] }
    val (maxY, maxM) = ymd(maxDay).let { it[0] to it[1] }
    val monthIndex = shownYear * 12 + shownMonth
    val title = remember(shownYear, shownMonth) {
        val c = Calendar.getInstance().apply { clear(); set(shownYear, shownMonth - 1, 1) }
        java.text.SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(c.time)
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.testTag("date_range.header").clickable { showingYears = !showingYears }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (showingYears) "  ▾" else "  ›", color = accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            if (!showingYears) {
                val canBack = monthIndex > minY * 12 + minM
                val canFwd = monthIndex < maxY * 12 + maxM
                Text("‹", color = accent, fontSize = 26.sp, modifier = Modifier.testTag("date_range.previous")
                    .alpha(if (canBack) 1f else 0.3f)
                    .clickable(enabled = canBack) { val i = monthIndex - 1; shownYear = (i - 1) / 12; shownMonth = (i - 1) % 12 + 1 }
                    .padding(horizontal = 12.dp))
                Text("›", color = accent, fontSize = 26.sp, modifier = Modifier.testTag("date_range.next")
                    .alpha(if (canFwd) 1f else 0.3f)
                    .clickable(enabled = canFwd) { val i = monthIndex + 1; shownYear = (i - 1) / 12; shownMonth = (i - 1) % 12 + 1 }
                    .padding(horizontal = 12.dp))
            }
        }
        if (showingYears) {
            val years = (minY..maxOf(minY, maxY)).toList()
            val state = rememberLazyGridState(initialFirstVisibleItemIndex = maxOf(0, years.indexOf(shownYear) - 6))
            LazyVerticalGrid(GridCells.Fixed(3), state = state, modifier = Modifier.fillMaxWidth().height(280.dp)) {
                items(years) { y ->
                    val current = y == shownYear
                    Box(
                        Modifier.padding(4.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (current) accent else Color.Transparent)
                            .testTag("date_range.year.$y")
                            .clickable {
                                val idx = (y * 12 + shownMonth).coerceIn(minY * 12 + minM, maxY * 12 + maxM)
                                shownYear = (idx - 1) / 12; shownMonth = (idx - 1) % 12 + 1
                                showingYears = false
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$y", color = if (current) Color.White else text, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        } else {
            MonthGrid(shownYear, shownMonth, selection, onSelection, minDay, maxDay, accent, rangeFill, text)
        }
    }
}

@Composable
private fun MonthGrid(
    year: Int, month: Int, selection: DateRangeSelection, onSelection: (DateRangeSelection) -> Unit,
    minDay: String, maxDay: String, accent: Color, rangeFill: Color, text: Color,
) {
    val first = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
    val firstWeekday = Calendar.getInstance().firstDayOfWeek
    val lead = (first.get(Calendar.DAY_OF_WEEK) - firstWeekday + 7) % 7
    val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val symbols = java.text.DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays // index 1..7
    val header = (0 until 7).map { symbols[(firstWeekday - 1 + it) % 7 + 1].take(2).uppercase(Locale.getDefault()) }
    val cells: List<Int?> = List(lead) { null } + (1..days).toList()
    val s = selection.start
    val e = selection.end
    val complete = s != null && e != null && s != e
    Column {
        Row(Modifier.fillMaxWidth()) {
            header.forEach { Text(it, color = text.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val key = "%04d-%02d-%02d".format(Locale.US, year, month, day)
                            val enabled = key >= minDay && key <= maxDay
                            val isStart = key == s
                            val isEnd = key == e
                            val inside = s != null && e != null && key > s && key < e
                            Row(Modifier.fillMaxWidth().height(34.dp)) {
                                Box(Modifier.weight(1f).height(34.dp).background(if (inside || (isEnd && complete)) rangeFill else Color.Transparent))
                                Box(Modifier.weight(1f).height(34.dp).background(if (inside || (isStart && complete)) rangeFill else Color.Transparent))
                            }
                            if (isStart || isEnd) Box(Modifier.size(34.dp).clip(CircleShape).background(accent))
                            Box(
                                Modifier.size(40.dp).alpha(if (enabled) 1f else 0.3f)
                                    .testTag("date_range.day.$key")
                                    .semantics { selected = isStart || isEnd || inside; contentDescription = key }
                                    .clickable(enabled = enabled) { onSelection(selection.tapping(key)) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$day", color = if (isStart || isEnd) Color.White else text,
                                    fontWeight = if (isStart || isEnd) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The Date Picker's range mode: the calendar above, its answer written through [DateRangeSelection.answer]. */
@Composable
internal fun DateRangeBlock(block: ContentBlock, inputValues: MutableMap<String, Any>, minDay: String, maxDay: String, initialDay: String) {
    val fieldId = block.field_id ?: block.id
    var selection by remember { mutableStateOf(DateRangeSelection.restore(inputValues, fieldId)) }
    val accent = StyleEngine.parseColor(block.highlight_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val rangeFill = (block.field_config?.get("range_color") as? String)?.let { StyleEngine.parseColor(it) } ?: accent.copy(alpha = 0.2f)
    val textColor = ((block.field_config?.get("wheel_text_color") as? String) ?: block.text_color ?: block.field_style?.text_color)
        ?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.onSurface
    val bg = ((block.field_config?.get("calendar_bg_color") as? String) ?: block.calendar_bg_color)
        ?.takeIf { it.isNotBlank() && it.lowercase() != "transparent" }?.let { StyleEngine.parseColor(it) } ?: Color.Transparent
    val radius = ((block.field_config?.get("picker_corner_radius") as? Number)?.toFloat() ?: 12f).dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.text?.takeIf { it.isNotEmpty() }?.let { Text(it, color = textColor.copy(alpha = 0.7f), fontSize = 15.sp) }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(radius)).background(bg).padding(8.dp).testTag("date_range")) {
            DateRangeCalendar(
                selection = selection,
                onSelection = { next ->
                    selection = next
                    for ((k, v) in next.answer(fieldId)) if (v == null) inputValues.remove(k) else inputValues[k] = v
                },
                minDay = minDay, maxDay = maxDay,
                initialDay = selection.start ?: initialDay,
                accent = accent, rangeFill = rangeFill, text = textColor,
            )
        }
    }
}
