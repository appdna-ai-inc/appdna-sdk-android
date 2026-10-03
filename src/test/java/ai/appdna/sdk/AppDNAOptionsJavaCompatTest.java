package ai.appdna.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;
import org.junit.Test;

/**
 * A Java host written against the released `AppDNAOptions` (1.0.53: `flushInterval: Long = 30L`,
 * `batchSize: Int = 20`, `configTTL: Long = 3600L`) still compiles and links: the getters, `componentN()`,
 * `copy(...)` and the constructors keep their JVM signatures — primitive `long` / `int`, not boxed nullable
 * `Long` / `Integer`. The unreleased change made the three nullable, which changed every one of these
 * descriptors; a host binary compiled against 1.0.53 would have failed with NoSuchMethodError.
 *
 * NEGATIVE CONTROL (build Mac): with nullable properties, every descriptor assertion fails
 * (`java.lang.Long` / `java.lang.Integer` instead of `long` / `int`).
 */
public class AppDNAOptionsJavaCompatTest {

    private static final Class<?>[] RELEASED_CONSTRUCTOR = {
        long.class, int.class, long.class, LogLevel.class, int.class, String.class, String.class,
        BillingProvider.class, boolean.class, long.class,
    };

    @Test
    public void theReleasedJavaCallsStillCompileAndLink() {
        AppDNAOptions defaults = new AppDNAOptions();
        long flush = defaults.getFlushInterval();
        int batch = defaults.getBatchSize();
        long ttl = defaults.getConfigTTL();
        assertEquals(30L, flush);
        assertEquals(100, batch);
        assertEquals(3600L, ttl);

        AppDNAOptions set = new AppDNAOptions(10L, 5, 60L, LogLevel.DEBUG, 0, "native", null,
            BillingProvider.StoreKit2.INSTANCE, false, 5L);
        assertEquals(10L, set.getFlushInterval());
        assertEquals(5, set.getBatchSize());
        assertEquals(60L, set.getConfigTTL());
        assertEquals(10L, set.component1());
        assertEquals(5, set.component2());
        assertEquals(60L, set.component3());

        AppDNAOptions copied = set.copy(15L, 7, 90L, LogLevel.WARNING, 0, "native", null,
            BillingProvider.StoreKit2.INSTANCE, false, 5L);
        assertEquals(15L, copied.getFlushInterval());
        assertEquals(7, copied.getBatchSize());
    }

    @Test
    public void theJvmDescriptorsAreTheReleasedOnes() throws Exception {
        assertEquals(long.class, AppDNAOptions.class.getMethod("getFlushInterval").getReturnType());
        assertEquals(int.class, AppDNAOptions.class.getMethod("getBatchSize").getReturnType());
        assertEquals(long.class, AppDNAOptions.class.getMethod("getConfigTTL").getReturnType());
        assertEquals(long.class, AppDNAOptions.class.getMethod("component1").getReturnType());
        assertEquals(int.class, AppDNAOptions.class.getMethod("component2").getReturnType());
        assertEquals(long.class, AppDNAOptions.class.getMethod("component3").getReturnType());
        assertNotNull(AppDNAOptions.class.getConstructor(RELEASED_CONSTRUCTOR));
        Method copy = AppDNAOptions.class.getMethod("copy", RELEASED_CONSTRUCTOR);
        assertEquals(AppDNAOptions.class, copy.getReturnType());
        assertNotNull(AppDNAOptions.class.getConstructor());
    }
}
