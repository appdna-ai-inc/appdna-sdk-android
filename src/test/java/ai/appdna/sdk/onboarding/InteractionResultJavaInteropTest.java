package ai.appdna.sdk.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * SPEC-496 §5b C4.1 — Java interop. A Java host constructs {@link ElementInteractionResult} and
 * {@link AppliedInteraction} directly; each SDK release that added a Kotlin-defaulted parameter would
 * otherwise have removed the constructor that host compiled against. This file FAILS TO COMPILE if any
 * shape below is lost:
 *
 * <ul>
 *   <li>pre-#657 (Android 1.0.50): {@code ElementInteractionResult(Map, Map, boolean)} — restored by an
 *       explicit secondary constructor, because #657 inserted {@code fieldOptions} BEFORE {@code advance};
 *       {@code AppliedInteraction(Map, Map, boolean)} — restored by {@code @JvmOverloads};</li>
 *   <li>#657 (1.0.51–1.0.53): {@code ElementInteractionResult(Map, Map, Map, boolean)} and
 *       {@code AppliedInteraction(Map, Map, Map, boolean)};</li>
 *   <li>P1b (1.0.54): the full constructors with {@code dataContext} (and {@code inputValuePatches} on
 *       {@code AppliedInteraction}).</li>
 * </ul>
 */
public class InteractionResultJavaInteropTest {

    private static Map<String, Map<String, Object>> patches() {
        Map<String, Object> patch = new HashMap<>();
        patch.put("highlight_color", "#00FF00");
        Map<String, Map<String, Object>> out = new HashMap<>();
        out.put("cal", patch);
        return out;
    }

    private static Map<String, Object> inputs() {
        Map<String, Object> m = new HashMap<>();
        m.put("otp", "1234");
        return m;
    }

    @Test
    public void elementInteractionResultPre657Shape() {
        ElementInteractionResult r = new ElementInteractionResult(patches(), inputs(), true);
        assertTrue(r.getAdvance());
        assertNull(r.getFieldOptions());
        assertNull(r.getDataContext());
        assertEquals("1234", r.getInputValuePatches().get("otp"));
    }

    @Test
    public void elementInteractionResult657Shape() {
        Map<String, List<InputOption>> options = Collections.emptyMap();
        ElementInteractionResult r = new ElementInteractionResult(patches(), inputs(), options, false);
        assertFalse(r.getAdvance());
        assertEquals(options, r.getFieldOptions());
        assertNull(r.getDataContext());
    }

    @Test
    public void elementInteractionResultP1bShape() {
        Map<String, Object> data = new HashMap<>();
        data.put("recommendations", Collections.singletonList("a"));
        data.put("banner", null); // a null member removes the key
        ElementInteractionResult r = new ElementInteractionResult(patches(), inputs(), null, false, data);
        assertTrue(r.getDataContext().containsKey("banner"));
        assertEquals(Collections.singletonList("a"), r.getDataContext().get("recommendations"));
        // The no-arg Java constructor still exists too.
        assertNull(new ElementInteractionResult().getDataContext());
    }

    @Test
    public void appliedInteractionAllShapes() {
        Map<String, List<InputOption>> options = Collections.emptyMap();
        AppliedInteraction pre657 = new AppliedInteraction(inputs(), patches(), true);
        assertTrue(pre657.getAdvance());
        AppliedInteraction v657 = new AppliedInteraction(inputs(), patches(), options, false);
        assertFalse(v657.getAdvance());
        Map<String, Object> data = new HashMap<>();
        data.put("k", "v");
        AppliedInteraction p1b = new AppliedInteraction(inputs(), patches(), options, false, inputs(), data);
        assertEquals("1234", p1b.getInputValuePatches().get("otp"));
        assertEquals("v", p1b.getDataContext().get("k"));
    }

    @Test
    public void coreStaticsAreReachableFromJava() {
        assertEquals(Long.valueOf(8000L), ElementInteractionResult.minimumBridgeTimeoutMs("refresh"));
        assertNull(ElementInteractionResult.minimumBridgeTimeoutMs("otp_entered"));
        Map<String, Object> raw = new HashMap<>();
        raw.put("banner", null);
        assertTrue(ElementInteractionResult.decodeDataContext(raw).containsKey("banner"));
    }
}
