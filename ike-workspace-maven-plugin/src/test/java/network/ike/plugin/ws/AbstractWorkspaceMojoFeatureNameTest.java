package network.ike.plugin.ws;

import network.ike.workspace.FeatureName;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@code AbstractWorkspaceMojo.validateFeatureName}, the
 * shared boundary check used by every feature-* Mojo.
 *
 * <p>Covers the wiring side of ike-issues#205: rule violations must
 * surface as a clean {@link MojoException} (Maven build failure) rather
 * than a raw {@link IllegalArgumentException} stack trace.
 */
class AbstractWorkspaceMojoFeatureNameTest {

    @Test
    void valid_name_returns_FeatureName() throws Exception {
        FeatureName fn = invoke("reasoner");
        assertThat(fn.value()).isEqualTo("reasoner");
    }

    @Test
    void invalid_name_throws_MojoException_with_clean_message() {
        assertThatThrownBy(() -> invoke("feature/x"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("filesystem-safe")
                .hasMessageNotContaining("IllegalArgumentException");
    }

    @Test
    void null_name_throws_MojoException() {
        assertThatThrownBy(() -> invoke(null))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void empty_name_throws_MojoException() {
        assertThatThrownBy(() -> invoke(""))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("non-empty");
    }

    /** Invoke the static helper reflectively (it's protected). */
    private static FeatureName invoke(String feature) throws Exception {
        Method m = AbstractWorkspaceMojo.class.getDeclaredMethod(
                "validateFeatureName", String.class);
        m.setAccessible(true);
        try {
            return (FeatureName) m.invoke(null, feature);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }
}
