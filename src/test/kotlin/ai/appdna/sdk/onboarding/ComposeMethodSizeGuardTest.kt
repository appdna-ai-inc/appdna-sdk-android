package ai.appdna.sdk.onboarding

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream

/**
 * A guard against the ART VerifyError that shipped on this branch.
 *
 * `FormInputSelectBlock` had grown into ONE ~1,200-line composable. Compose inlines every
 * Column/Row/Box body into the enclosing method, so it compiled to an enormous JVM method. The JVM
 * bytecode was valid — Robolectric, which runs the JVM bytecode, passed 1088/1088 — but D8 (AGP 8.6)
 * mis-allocated a register when dexing it, and ART's verifier then rejected the WHOLE
 * `ContentBlockRendererKt` class on device: every block-based onboarding step crashed.
 *
 * No JVM-side test can reproduce the dex verifier, so this one guards the cause instead: it reads
 * the compiled facade classes' method tables (without loading them) and fails when a single method
 * grows past a size ceiling. It also prints the largest methods, so a reviewer can see headroom.
 *
 * The ceilings are deliberately generous first cuts, not measurements: tighten them to ~1.25x the
 * largest method this test prints once the numbers are known.
 */
class ComposeMethodSizeGuardTest {

    private data class MethodInfo(val name: String, val codeBytes: Int, val maxLocals: Int)

    /** Every method in the renderer facade: half the JVM's hard 64 KiB per-method limit. */
    private val maxBytesAnyMethod = 32 * 1024

    /** The pieces FormInputSelectBlock was split into must each stay well below that. */
    private val maxBytesSelectFamily = 16 * 1024
    private val selectFamily = Regex(
        "^(FormInputSelectBlock|rememberSelectOptionsState|Select(SearchField|CategoryChips|StackedOptions|" +
            "StackedOptionRow|GridOptions|GridOptionCell|ImageTileOptions|ImageTile|BubbleOptions|" +
            "ListOptions|DropdownOptions|GridTooltip))(\\$.*)?$",
    )

    @Test
    fun `no method in ContentBlockRendererKt is large enough to trip D8`() {
        val methods = readMethods("ai/appdna/sdk/onboarding/ContentBlockRendererKt.class")
        report("ContentBlockRendererKt", methods)

        val tooBig = methods.filter { it.codeBytes > maxBytesAnyMethod }
        if (tooBig.isNotEmpty()) {
            fail(
                "Methods over $maxBytesAnyMethod bytes in ContentBlockRendererKt — split them into smaller " +
                    "@Composable helpers (see FormInputSelectBlock / SPEC-496): $tooBig",
            )
        }

        val select = methods.filter { selectFamily.matches(it.name) }
        assertTrue(
            "FormInputSelectBlock and its helpers must all compile (found: ${select.map { it.name }})",
            select.any { it.name == "FormInputSelectBlock" },
        )
        val selectTooBig = select.filter { it.codeBytes > maxBytesSelectFamily }
        if (selectTooBig.isNotEmpty()) {
            fail(
                "The Select renderer is growing back toward the method that D8 miscompiled " +
                    "(ceiling $maxBytesSelectFamily bytes): $selectTooBig",
            )
        }

        // ButtonBlock carries the refresh loading state now; the spinner is a
        // separate composable (RefreshLoadingContent) so this method does not grow toward D8's limit.
        // Measured on the pre-P1b build (HEAD 63ad606eb, 2026-09-28): ButtonBlock = 3,038 bytes
        // (max_locals 76). Ceiling = max(16 KiB, 1.25 × 3,038) = 16,384 bytes.
        assertMethodCeiling(methods, "ButtonBlock", maxBytesButtonBlock)
    }

    /** max(16 KiB, 1.25 × 3,038 measured pre-P1b). */
    private val maxBytesButtonBlock = maxOf(16 * 1024, (3_038 * 1.25).toInt())

    /** max(16 KiB, 1.25 × 2,535 measured pre-P1b). */
    private val maxBytesBlockBasedStepView = maxOf(16 * 1024, (2_535 * 1.25).toInt())

    @Test
    fun `BlockBasedStepView stays below its ceiling, and report the largest methods in OnboardingActivityKt`() {
        val methods = readMethods("ai/appdna/sdk/onboarding/OnboardingActivityKt.class")
        report("OnboardingActivityKt", methods)
        // Promoted from report-only. The P1b interaction logic lives in
        // InteractionCoordinator / applyInteractionReply (HostDataInteraction.kt), not inline here.
        // Measured on the pre-P1b build (HEAD 63ad606eb, 2026-09-28): BlockBasedStepView = 2,535 bytes
        // (max_locals 58). Ceiling = max(16 KiB, 1.25 × 2,535) = 16,384 bytes.
        assertMethodCeiling(methods, "BlockBasedStepView", maxBytesBlockBasedStepView)
        // Audit — the flow host's own method. Measured pre-P1b (63ad606eb): 11,683 bytes
        // (max_locals 254). Ceiling = max(16 KiB, 1.25 × 11,683) = 16,384 bytes.
        assertMethodCeiling(methods, "OnboardingFlowHost", maxOf(16 * 1024, (11_683 * 1.25).toInt()))
    }

    /**
     * The composable's BODY is not only its own method: Compose
     * compiles the content lambdas (`CompositionLocalProvider { … }`, `Button { … }`) into inner classes
     * `<Facade>$<Fn>$…`, and local functions into `<Fn>$<name>` methods of the facade. D8 mis-dexes a
     * method by its size wherever it lives, so the largest method of that whole family is guarded too.
     *
     * Measured on the pre-P1b build (HEAD 63ad606eb, 2026-09-28):
     *  - BlockBasedStepView family: 5,943 bytes (`OnboardingActivityKt$BlockBasedStepView$6$1$2.invoke`);
     *  - ButtonBlock family: 1,415 bytes (`ContentBlockRendererKt.ButtonBlock$ButtonContent`);
     *  - OnboardingFlowHost family (P1b wrapped the `AnimatedContent` content in a new
     *    `CompositionLocalProvider` lambda): 1,288 bytes
     *    (`OnboardingActivityKt$OnboardingFlowHost$presentPaywallTriggerNode$routeOutcome$1.invoke`).
     * Ceiling for all three = max(16 KiB, 1.25 × measured) = 16,384 bytes.
     */
    @Test
    fun `the lambda and local-function families of ButtonBlock and BlockBasedStepView stay below their ceilings`() {
        for ((facade, fn, measured) in listOf(
            Triple("OnboardingActivityKt", "BlockBasedStepView", 5_943),
            Triple("ContentBlockRendererKt", "ButtonBlock", 1_415),
            Triple("OnboardingActivityKt", "OnboardingFlowHost", 1_288),
        )) {
            val ceiling = maxOf(16 * 1024, (measured * 1.25).toInt())
            val family = familyMethods(facade, fn)
            assertTrue("$fn: found no lambda / local-function methods — the guard would measure nothing", family.isNotEmpty())
            val largest = family.maxByOrNull { it.codeBytes }!!
            println("[ComposeMethodSizeGuard] $fn family — ${family.size} methods, largest ${largest.codeBytes} B: ${largest.name}")
            if (largest.codeBytes > ceiling) {
                fail("$fn's family grew past its D8 ceiling of $ceiling bytes — extract helpers: $largest")
            }
        }
    }

    /** `<facade>.<fn>$*` methods plus every method of every `<facade>$<fn>$*` inner class. */
    private fun familyMethods(facade: String, fn: String): List<MethodInfo> {
        val pkg = "ai/appdna/sdk/onboarding"
        val out = readMethods("$pkg/$facade.class").filter { it.name.startsWith("$fn$") }.toMutableList()
        val url = javaClass.classLoader?.getResource("$pkg/$facade.class") ?: error("$facade not on the classpath")
        val prefix = "$facade\$$fn\$"
        // The compiled classes reach the unit-test classpath as a directory or as a jar, by variant.
        val names: List<String> = when (url.protocol) {
            "file" -> java.io.File(url.toURI()).parentFile.list().orEmpty().toList()
            "jar" -> (url.openConnection() as java.net.JarURLConnection).jarFile.use { jar ->
                jar.entries().asSequence().map { it.name }.filter { it.startsWith("$pkg/") && it.lastIndexOf('/') == pkg.length }
                    .map { it.substringAfterLast('/') }.toList()
            }
            else -> error("cannot list the classes next to $url")
        }
        names.filter { it.startsWith(prefix) && it.endsWith(".class") }.forEach { n ->
            out += readMethods("$pkg/$n").map { it.copy(name = "${n.removeSuffix(".class")}.${it.name}") }
        }
        return out
    }

    private fun assertMethodCeiling(methods: List<MethodInfo>, name: String, ceiling: Int) {
        val m = methods.filter { it.name == name }
        assertTrue("$name must compile (found none)", m.isNotEmpty())
        val over = m.filter { it.codeBytes > ceiling }
        if (over.isNotEmpty()) {
            fail("$name grew past its D8 ceiling of $ceiling bytes — extract helpers: $over")
        }
    }

    private fun report(owner: String, methods: List<MethodInfo>) {
        println("[ComposeMethodSizeGuard] $owner — largest methods (bytecode bytes / max_locals):")
        methods.sortedByDescending { it.codeBytes }.take(15).forEach {
            println("  %7d  %5d  %s".format(it.codeBytes, it.maxLocals, it.name))
        }
    }

    /**
     * Minimal class-file reader (JVMS): walks the constant pool to resolve names, skips fields,
     * and reads `code_length` + `max_locals` from each method's Code attribute. Reads the bytes as a
     * resource, so the class is never loaded or initialised.
     */
    private fun readMethods(resource: String): List<MethodInfo> {
        val stream = javaClass.classLoader?.getResourceAsStream(resource)
            ?: error("$resource is not on the test classpath")
        DataInputStream(stream.buffered()).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt()) { "$resource is not a class file" }
            input.readUnsignedShort() // minor
            input.readUnsignedShort() // major
            val cpCount = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(cpCount)
            var i = 1
            while (i < cpCount) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[i] = input.readUTF()
                    3, 4 -> input.skipFully(4)
                    5, 6 -> { input.skipFully(8); i++ } // long/double take two slots
                    7, 8, 16, 19, 20 -> input.skipFully(2)
                    9, 10, 11, 12, 17, 18 -> input.skipFully(4)
                    15 -> input.skipFully(3)
                    else -> error("Unknown constant-pool tag $tag in $resource")
                }
                i++
            }
            input.readUnsignedShort() // access_flags
            input.readUnsignedShort() // this_class
            input.readUnsignedShort() // super_class
            input.skipFully(2 * input.readUnsignedShort()) // interfaces

            repeat(input.readUnsignedShort()) { // fields
                input.skipFully(6)
                repeat(input.readUnsignedShort()) { input.skipAttribute() }
            }

            val methods = mutableListOf<MethodInfo>()
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort() // access_flags
                val name = utf8[input.readUnsignedShort()] ?: "?"
                input.readUnsignedShort() // descriptor
                repeat(input.readUnsignedShort()) {
                    val attrName = utf8[input.readUnsignedShort()]
                    val length = input.readInt()
                    if (attrName == "Code") {
                        input.readUnsignedShort() // max_stack
                        val maxLocals = input.readUnsignedShort()
                        val codeLength = input.readInt()
                        methods += MethodInfo(name, codeLength, maxLocals)
                        input.skipFully(length - 8)
                    } else {
                        input.skipFully(length)
                    }
                }
            }
            return methods
        }
    }

    private fun DataInputStream.skipAttribute() {
        readUnsignedShort()
        skipFully(readInt())
    }

    private fun DataInputStream.skipFully(n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) {
                readByte() // force progress or EOFException
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
