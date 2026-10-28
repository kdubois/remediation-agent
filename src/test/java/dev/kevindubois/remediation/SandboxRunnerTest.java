package dev.kevindubois.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the result-JSON extraction from sandbox pod logs.
 */
class SandboxRunnerTest {

    private final SandboxRunner runner = new SandboxRunner(null, new ObjectMapper(),
            new SandboxConfig("img", "remediation", "opencode", null, null, "s", "k", "secret", "key", 60));

    @Test
    void parsesResultFromLastJsonLine() {
        var log = """
                [run-agent] clone failed retry
                [run-agent] agent changed 2 file(s)
                {"status":"success","branch":"fix/k8s-issue-ab12","filesChanged":2,"testSummary":"Tests run: 10, Failures: 0","model":"glm-5","durationSeconds":222}
                """;
        var result = runner.parseResult(log);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("success");
        assertThat(result.branch()).isEqualTo("fix/k8s-issue-ab12");
        assertThat(result.filesChanged()).isEqualTo(2);
        assertThat(result.testSummary()).isEqualTo("Tests run: 10, Failures: 0");
        assertThat(result.success()).isTrue();
    }

    @Test
    void returnsNullWhenLogHasNoJson() {
        assertThat(runner.parseResult("[run-agent] clone failed")).isNull();
        assertThat(runner.parseResult("")).isNull();
        assertThat(runner.parseResult(null)).isNull();
    }

    @Test
    void skipsNonResultJsonLines() {
        var log = """
                {"level":"info","msg":"opencode starting"}
                {"status":"tests_failed","branch":"fix/x","filesChanged":1,"testSummary":"","model":"m","durationSeconds":5}
                """;
        var result = runner.parseResult(log);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("tests_failed");
        assertThat(result.success()).isFalse();
    }
}
