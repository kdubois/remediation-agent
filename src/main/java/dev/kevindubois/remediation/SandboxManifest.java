package dev.kevindubois.remediation;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.quarkus.logging.Log;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the Sandbox custom resource for one remediation run.
 *
 * <p>The manifest is constructed deterministically from validated request
 * fields — never from model output — so a hostile analysis result cannot steer
 * the cluster. The pod template applies the full isolation profile: restricted
 * settings, dropped capabilities, read-only root filesystem with sized emptyDir
 * workspaces (including /tmp — the JVM, Maven and node all write there), no
 * service-account token, restart policy Never so the wrapper's exit is
 * observable, and both the GitHub and LLM credentials injected from secret
 * references rather than literals.
 */
public final class SandboxManifest {

    public static final String API_VERSION = "agents.x-k8s.io/v1beta1";
    public static final String KIND = "Sandbox";

    private SandboxManifest() {
    }

    /**
     * Builds the Sandbox CR as a plain map, ready to serialize and create.
     *
     * @param name    sandbox name, also used as the pod name (a controller contract)
     * @param request the validated remediation request
     * @param prompt  remediation prompt passed to the coding agent
     * @param config  sandbox runtime configuration
     */
    public static Map<String, Object> build(String name, RemediationRequest request, String prompt,
            SandboxConfig config) {
        List<EnvVar> env = new ArrayList<>(List.of(
                // OpenShift runs the pod as a random UID with no passwd entry:
                // without an explicit HOME, tools resolve it to / and fail on
                // the read-only root filesystem
                env("HOME", "/home/sandbox"),
                env("REPO_URL", request.repoUrl()),
                env("BASE_BRANCH", request.baseBranch()),
                env("CODING_AGENT", config.codingAgent()),
                env("REMEDIATION_PROMPT", prompt),
                env("COMMIT_TITLE", "Fix: " + (request.title() != null ? request.title() : "canary rollback")),
                env("ROOT_CAUSE", request.rootCause() == null ? "" : request.rootCause()),
                secretEnv("GITHUB_TOKEN", config.githubSecretName(), config.githubSecretKey())));
        if (config.model() != null && !config.model().isBlank()) {
            env.add(env("MODEL", config.model()));
        }
        if (config.llmBaseUrl() != null && !config.llmBaseUrl().isBlank()) {
            env.add(env("LLM_BASE_URL", config.llmBaseUrl()));
            env.add(secretEnv("LLM_API_KEY", config.llmSecretName(), config.llmSecretKey()));
        }
        // budget for the coding agent itself, below the sandbox's hard timeout
        env.add(env("AGENT_TIMEOUT_SECONDS", String.valueOf(Math.max(60, config.timeoutSeconds() - 600))));

        PodSpec podSpec = podSpec(config, env);

        Log.infof("Built sandbox manifest %s for %s", name, request.repoUrl());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("apiVersion", API_VERSION);
        manifest.put("kind", KIND);
        manifest.put("metadata", Map.of(
                "name", name,
                "labels", Map.of("app", "remediation-agent", "remediation", name)));
        manifest.put("spec", Map.of(
                "shutdownPolicy", "Delete",
                // The sandbox self-destructs even if the service dies mid-run.
                "shutdownTime", Instant.now().plus(config.timeoutSeconds() + 300, ChronoUnit.SECONDS).toString(),
                "podTemplate", Map.of("spec", podSpec)));
        return manifest;
    }

    public static String newSandboxName() {
        return "remediation-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static PodSpec podSpec(SandboxConfig config, List<EnvVar> env) {
        // No explicit fsGroup: restricted-v2 assigns one from the namespace
        // range and rejects hardcoded values; the assigned group keeps the
        // emptyDir volumes writable for the run-as-non-root container.
        var podSecurityContext = new PodSecurityContextBuilder()
                .withRunAsNonRoot(true)
                .withNewSeccompProfile()
                .withType("RuntimeDefault")
                .endSeccompProfile()
                .build();

        return new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false)
                .withSecurityContext(podSecurityContext)
                .withVolumes(
                        sizedVolume("workspace", "2Gi"),
                        sizedVolume("agent-home", "2Gi"),
                        sizedVolume("tmp", "1Gi"))
                .withContainers(container(config, env))
                .build();
    }

    private static io.fabric8.kubernetes.api.model.Volume sizedVolume(String name, String size) {
        return new VolumeBuilder()
                .withName(name)
                .withNewEmptyDir()
                .withSizeLimit(new Quantity(size))
                .endEmptyDir()
                .build();
    }

    private static Container container(SandboxConfig config, List<EnvVar> env) {
        return new ContainerBuilder()
                .withName("remediation")
                .withImage(config.image())
                .withImagePullPolicy("Always")
                .withEnv(env)
                .withNewResources()
                .withRequests(Map.of("cpu", new Quantity("500m"), "memory", new Quantity("512Mi")))
                .withLimits(Map.of("cpu", new Quantity("2"), "memory", new Quantity("2Gi")))
                .endResources()
                .withVolumeMounts(
                        new VolumeMountBuilder().withName("workspace").withMountPath("/workspace").build(),
                        new VolumeMountBuilder().withName("agent-home").withMountPath("/home/sandbox").build(),
                        new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build())
                .withNewSecurityContext()
                .withAllowPrivilegeEscalation(false)
                .withReadOnlyRootFilesystem(true)
                .withNewCapabilities()
                .withDrop("ALL")
                .endCapabilities()
                .endSecurityContext()
                .build();
    }

    private static EnvVar env(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value == null ? "" : value).build();
    }

    private static EnvVar secretEnv(String name, String secretName, String secretKey) {
        return new EnvVarBuilder()
                .withName(name)
                .withValueFrom(new EnvVarSourceBuilder()
                        .withNewSecretKeyRef()
                        .withName(secretName)
                        .withKey(secretKey)
                        .endSecretKeyRef()
                        .build())
                .build();
    }
}
