package network.ike.plugin.ws;

import org.apache.maven.api.plugin.Log;
import java.util.function.Supplier;

/**
 * Simple Log for tests, replacing Maven 3's SystemStreamLog.
 * Also provides {@link #injectInto(Object)} for setting the
 * {@code @Inject Log} field on Maven 4 Mojos constructed in tests.
 */
public class TestLog implements Log {

    /**
     * Create a Mojo and inject a TestLog into its {@code log} field.
     *
     * @param <T>  the Mojo type
     * @param type the Mojo class
     * @return a new Mojo instance with log injected
     */
    public static <T> T createMojo(Class<T> type) {
        try {
            T mojo = type.getDeclaredConstructor().newInstance();
            injectInto(mojo);
            return mojo;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot create " + type.getSimpleName(), e);
        }
    }

    /**
     * Inject a TestLog into an existing Mojo's private {@code log} field.
     *
     * @param mojo the Mojo to inject into
     */
    public static void injectInto(Object mojo) {
        try {
            // Walk up the class hierarchy to find the log field
            Class<?> cls = mojo.getClass();
            while (cls != null) {
                try {
                    var field = cls.getDeclaredField("log");
                    if (field.getType().isAssignableFrom(Log.class)) {
                        field.setAccessible(true);
                        field.set(mojo, new TestLog());
                        return;
                    }
                } catch (NoSuchFieldException ignored) {
                    cls = cls.getSuperclass();
                }
            }
            throw new NoSuchFieldException("log field not found in " + mojo.getClass());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot inject log into " + mojo.getClass(), e);
        }
    }
    @Override public boolean isDebugEnabled() { return false; }
    @Override public void debug(CharSequence c) {}
    @Override public void debug(CharSequence c, Throwable e) {}
    @Override public void debug(Throwable e) {}
    @Override public void debug(Supplier<String> c) {}
    @Override public void debug(Supplier<String> c, Throwable e) {}
    @Override public boolean isInfoEnabled() { return true; }
    @Override public void info(CharSequence c) { System.out.println("[INFO] " + c); }
    @Override public void info(CharSequence c, Throwable e) { System.out.println("[INFO] " + c); }
    @Override public void info(Throwable e) { System.out.println("[INFO] " + e); }
    @Override public void info(Supplier<String> c) { System.out.println("[INFO] " + c.get()); }
    @Override public void info(Supplier<String> c, Throwable e) { System.out.println("[INFO] " + c.get()); }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public void warn(CharSequence c) { System.err.println("[WARN] " + c); }
    @Override public void warn(CharSequence c, Throwable e) { System.err.println("[WARN] " + c); }
    @Override public void warn(Throwable e) { System.err.println("[WARN] " + e); }
    @Override public void warn(Supplier<String> c) { System.err.println("[WARN] " + c.get()); }
    @Override public void warn(Supplier<String> c, Throwable e) { System.err.println("[WARN] " + c.get()); }
    @Override public boolean isErrorEnabled() { return true; }
    @Override public void error(CharSequence c) { System.err.println("[ERROR] " + c); }
    @Override public void error(CharSequence c, Throwable e) { System.err.println("[ERROR] " + c); }
    @Override public void error(Throwable e) { System.err.println("[ERROR] " + e); }
    @Override public void error(Supplier<String> c) { System.err.println("[ERROR] " + c.get()); }
    @Override public void error(Supplier<String> c, Throwable e) { System.err.println("[ERROR] " + c.get()); }
}
