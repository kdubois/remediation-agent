package dev.kevindubois.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the result-JSON extraction from sandbox pod logs, and the
 * failure reason reported when a sandbox never finishes.
 */
class SandboxRunnerTest {

    private final SandboxRunner runner = new SandboxRunner(null, new ObjectMapper(),
            new SandboxConfig("img", "remediation", "opencode", null, null, "s", "k", "secret", "key", 60, false));

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

    @Test
    void reportsTimeoutWhenThePodNeverReachesATerminalPhase() {
        // timeoutSeconds 0 expires the deadline immediately, so the pod poll
        // loop never runs; the mocked client accepts the create and delete
        var client = mock(KubernetesClient.class);
        var resource = fakeSandboxOperations(client);
        var config = new SandboxConfig("img", "remediation", "opencode", null, null,
                "llm-secret", "key", "gh-secret", "key", 0, false);
        var runner = new SandboxRunner(client, new ObjectMapper(), config);
        var request = new RemediationRequest(
                "https://github.com/kdubois/argo-rollouts-quarkus-demo", "main", "CODE_BUG",
                "NPE in getStatus", "null dereference", "error rate spiked", "trace");

        var outcome = runner.run("remediation-timeout-test", request, "prompt");

        assertThat(outcome).isEqualTo(SandboxOutcome.failed(SandboxOutcome.Reason.TIMEOUT));
        // a timed-out sandbox is deleted, not kept — the pod may still be running
        verify(resource).delete();
    }

    /** Wires the genericKubernetesResources fluent chain the runner uses for create and delete. */
    @SuppressWarnings("unchecked")
    private static Resource<GenericKubernetesResource> fakeSandboxOperations(KubernetesClient client) {
        var sandboxes = mock(MixedOperation.class);
        var namespaced = mock(NonNamespaceOperation.class);
        var resource = mock(Resource.class);
        when(client.genericKubernetesResources(SandboxManifest.API_VERSION, SandboxManifest.KIND))
                .thenReturn(sandboxes);
        when(sandboxes.inNamespace(anyString())).thenReturn(namespaced);
        when(namespaced.resource(any(GenericKubernetesResource.class))).thenReturn(resource);
        when(namespaced.withName(anyString())).thenReturn(resource);
        return resource;
    }
}
