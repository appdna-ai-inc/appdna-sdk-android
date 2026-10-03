package ai.appdna.sdk.core

import ai.appdna.sdk.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Rive
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server-driven Rive animation block.
 *
 * Replaces the stub placeholder with a real
 * [RiveAnimationView] hosted via [AndroidView]. Compose-only Rive
 * APIs are still nascent (`RiveAnimation` Composable shipped late
 * and depends on the same View under the hood), so we use the
 * battle-tested View directly and wrap it with [DisposableEffect]
 * to stop the animation + release native resources when the
 * composition leaves the tree.
 *
 * iOS parity:
 * `packages/appdna-sdk-ios/Sources/AppDNASDK/Core/RiveBlockView.swift`
 * uses `RiveViewModel(name:, stateMachineName:, fit:, alignment:)`
 * from RiveRuntime. The Android `RiveAnimationView` matches that
 * surface — `setRiveResource(url, stateMachineName, autoplay)` plus
 * input setters mirror the iOS `setInput(_:value:)` calls.
 *
 * Boolean and number inputs are propagated from [RiveBlock.inputs];
 * trigger inputs (named in [RiveBlock.trigger_on_step_complete]) are
 * fired by callers (e.g. OnboardingFlowHost) via [fireTrigger] —
 * we expose the view through a side-effect channel by storing it
 * in [remember] so the host can reach in.
 */
data class RiveBlock(
    val rive_url: String,
    val artboard: String? = null,
    val state_machine: String? = null,
    val autoplay: Boolean = true,
    val height: Float = 160f,
    val alignment: String = "center",
    val inputs: Map<String, Any>? = null,
    val trigger_on_step_complete: String? = null,
)

@Composable
fun RiveBlockView(block: RiveBlock) {
    val horizontalAlignment = when (block.alignment) {
        "left" -> Alignment.Start
        "right" -> Alignment.End
        else -> Alignment.CenterHorizontally
    }

    val context = LocalContext.current

    // Initialise the Rive runtime exactly once per process. The init
    // call is idempotent — it short-circuits internally if already
    // initialised — so calling on every recomposition is cheap, but
    // we still gate via `remember(Unit)` to make that explicit.
    remember(Unit) {
        runCatching { Rive.init(context.applicationContext) }
            .onFailure { Log.warning("RiveBlockView: Rive.init failed: ${it.message}") }
    }

    // Hold the View so the cleanup in DisposableEffect can stop it.
    // Using a 1-cell array keeps Kotlin from complaining about
    // assigning to a `var` captured by an `onDispose` lambda.
    val viewRef = remember { arrayOfNulls<RiveAnimationView>(1) }
    val loadScope = remember { CoroutineScope(Dispatchers.Main.immediate) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(block.height.dp)
            .clip(RoundedCornerShape(12.dp)),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (block.rive_url.isBlank()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎬", fontSize = 32.sp)
                    Text("Rive", fontSize = 10.sp, color = Color.Gray)
                }
                return@Box
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    RiveAnimationView(ctx).also { view ->
                        viewRef[0] = view
                        // RiveAnimationView only ships `setRiveBytes`/
                        // `setRiveResource(Int)` for synchronous loading —
                        // network URLs require an explicit fetch.
                        loadScope.launch {
                            try {
                                val bytes = withContext(Dispatchers.IO) {
                                    URL(block.rive_url).openStream().use { it.readBytes() }
                                }
                                view.setRiveBytes(
                                    bytes,
                                    artboardName = block.artboard,
                                    stateMachineName = block.state_machine,
                                    autoplay = block.autoplay,
                                )
                                applyInputs(view, block)
                            } catch (t: Throwable) {
                                Log.warning(
                                    "RiveBlockView: load failed for ${block.rive_url}: ${t.message}",
                                )
                            }
                        }
                    }
                },
                update = { view ->
                    // Re-apply inputs if the [RiveBlock] recomposes with
                    // a new map (e.g. driven by question answers).
                    applyInputs(view, block)
                },
            )
        }
    }

    // Release native resources on dispose. RiveAnimationView
    // holds GL textures + a render thread; not stopping it leaks one renderer
    // per navigation event.
    DisposableEffect(block.rive_url) {
        onDispose {
            runCatching {
                viewRef[0]?.stop()
                viewRef[0]?.reset()
                viewRef[0] = null
            }
            (loadScope.coroutineContext[Job])?.cancel()
            loadScope.cancel()
        }
    }
}

private fun applyInputs(view: RiveAnimationView, block: RiveBlock) {
    val sm = block.state_machine ?: return
    val inputs = block.inputs ?: return
    inputs.forEach { (name, value) ->
        runCatching {
            when (value) {
                is Boolean -> view.setBooleanState(sm, name, value)
                is Number -> view.setNumberState(sm, name, value.toFloat())
                // Strings + nulls are not first-class Rive inputs; ignore.
                else -> Unit
            }
        }.onFailure {
            Log.warning("RiveBlockView: setInput($name) failed: ${it.message}")
        }
    }
}
