package ai.appdna.sdk.onboarding

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real markdown title: a coloured run is drawn in its colour with no link to follow, a real link keeps its target, and a
 * tap on plain text still selects the option. THE CONTROL: an ordinary link does have an annotation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class OptionMarkdownTitleTest {
    @get:Rule
    val compose = createComposeRule()

    private val style = TextStyle(fontSize = 16.sp, color = Color.Black)
    private fun annotated(md: String) = parseMarkdownToAnnotatedString(md, style, Color.Blue, underlineMarkers = false)

    @Test fun aColourRunIsColouredAndNotALink() {
        val a = annotated("Read [this](color:EE6885) first")
        assertEquals(emptyList<Any>(), a.getStringAnnotations("URL", 0, a.length))
        val span = a.spanStyles.single { a.text.substring(it.start, it.end) == "this" }
        assertEquals(Color(0xFFEE6885), span.item.color)
        assertEquals("Read this first", a.text)
    }

    @Test fun theControlARealLinkKeepsItsUrl() {
        val a = annotated("Accept [Terms](https://example.com/t)")
        assertEquals("https://example.com/t", a.getStringAnnotations("URL", 0, a.length).single().item)
    }

    @Test fun aTapOnPlainTextSelects() {
        var selected = 0
        compose.setContent {
            OptionMarkdownTitle("Plain words only", Modifier.testTag("t"), style, Color.Blue) { selected++ }
        }
        compose.onNodeWithTag("t").performClick()
        compose.waitForIdle()
        assertTrue(selected == 1)
    }
}
