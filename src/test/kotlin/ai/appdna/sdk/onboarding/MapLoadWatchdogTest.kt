package ai.appdna.sdk.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An interactive Google map whose key lacks "Maps SDK for Android" draws grey and reports nothing to
 * the app. The one visible symptom — `onMapLoaded` never fires — now logs an actionable error.
 * NEGATIVE CONTROL: with the `LaunchedEffect(Unit) { mapLoadWatchdog(...) }` removed nothing logs in
 * the app; here, with the `log(...)` call removed, `logged` stays empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapLoadWatchdogTest {
    @Test fun `a map that never loads logs the Maps SDK hint after 15 s`() = runTest {
        val logged = mutableListOf<String>()
        launch { mapLoadWatchdog(isLoaded = { false }, log = { logged += it }) }
        advanceTimeBy(MAP_LOAD_WATCHDOG_MS - 1); runCurrent()
        assertEquals(emptyList<String>(), logged)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, logged.size)
        assertTrue(logged[0].contains("Maps SDK for Android"))
    }

    @Test fun `a map that loaded logs nothing`() = runTest {
        val logged = mutableListOf<String>()
        var loaded = false
        launch { mapLoadWatchdog(isLoaded = { loaded }, log = { logged += it }) }
        advanceTimeBy(5_000); loaded = true
        advanceTimeBy(MAP_LOAD_WATCHDOG_MS); runCurrent()
        assertEquals(emptyList<String>(), logged)
    }
}
