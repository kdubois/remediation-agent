package dev.kevindubois.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the sandbox manifest structure — the security-critical contract.
 */
class SandboxManifestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final SandboxConfig config = new SandboxConfig(
            "quay.io/kevindubois/remediation-sandbox:opencode", "opencode", "glm-5",
            "remediation-agent", "github_token", 1200);

    private final RemediationRequest request = new RemediationRequest(
            "https://github.com/kdubois/demo", "main", "CODE_BUG", "NPE in getStatus",
            "null dereference", "error rate spiked", "stack trace");

    @SuppressWarnings("unchecked")
    private Map<String, Object> podSpec(Map<String, Object> manifest) throws Exception {
        var roundTrip = mapper.readValue(mapper.writeValueAsString(manifest), Map.class);
        var spec = (Map<String, Object>) roundTrip.get("spec");
        var podTemplate = (Map<String, Object>) spec.get("podTemplate");
        return (Map<String, Object>) podTemplate.get("spec");
    }

    @Test
    void buildsHardenedSandboxManifest() throws Exception {
        var manifest = SandboxManifest.build("remediation-test", request, "fix the bug", config);
        var json = mapper.writeValueAsString(manifest);

        assertThat(json).contains("\"kind\":\"Sandbox\"");
        assertThat(manifest.get("apiVersion")).isEqualTo("agents.x-k8s.io/v1beta1");

        var spec = (Map<String, Object>) manifest.get("spec");
        assertThat(spec.get("shutdownPolicy")).isEqualTo("Delete");
        assertThat((String) spec.get("shutdownTime")).isNotBlank();

        var pod = podSpec(manifest);
        assertThat(pod.get("restartPolicy")).isEqualTo("Never");
        assertThat(pod.get("automountServiceAccountToken")).isEqualTo(false);

        var podSecurity = (Map<String, Object>) pod.get("securityContext");
        assertThat(podSecurity.get("runAsNonRoot")).isEqualTo(true);
        assertThat(podSecurity.get("fsGroup")).isEqualTo(1001);

        var containers = (java.util.List<Map<String, Object>>) pod.get("containers");
        assertThat(containers).hasSize(1);
        var container = containers.get(0);
        assertThat(container.get("image")).isEqualTo("quay.io/kevindubois/remediation-sandbox:opencode");

        var security = (Map<String, Object>) container.get("securityContext");
        assertThat(security.get("allowPrivilegeEscalation")).isEqualTo(false);
        assertThat(security.get("readOnlyRootFilesystem")).isEqualTo(true);

        // the GitHub token must come from a secret reference, never a literal
        var env = (java.util.List<Map<String, Object>>) container.get("env");
        var token = env.stream().filter(e -> "GITHUB_TOKEN".equals(e.get("name"))).findFirst().orElseThrow();
        assertThat(token).doesNotContainKey("value");
        assertThat((String) token.get("valueFrom").toString()).contains("secretKeyRef");

        // the remediation prompt is passed through as an env var
        var prompt = env.stream().filter(e -> "REMEDIATION_PROMPT".equals(e.get("name"))).findFirst().orElseThrow();
        assertThat((String) prompt.get("value")).isEqualTo("fix the bug");
    }

    @Test
    void omitsBlankModel() throws Exception {
        var blankModel = new SandboxConfig("img", "opencode", "", "secret", "key", 60);
        var manifest = SandboxManifest.build("remediation-test", request, "prompt", blankModel);

        var containers = (java.util.List<Map<String, Object>>) podSpec(manifest).get("containers");
        var env = (java.util.List<Map<String, Object>>) containers.get(0).get("env");
        assertThat(env.stream().map(e -> e.get("name"))).doesNotContain("MODEL");
    }
}
