package dev.kevindubois.remediation;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
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
 * settings, dropped capabilities, read-only root filesystem with emptyDir
 * workspaces, no service-account token, restart policy Never so the wrapper's
 * exit is observable, and the GitHub token injected from a secret reference
 * rather than a literal.
 */
public final class SandboxManifest {

    public static final String API_VERSION = "agents.x-k8s.io/v1beta1";
    public static final String KIND = "Sandbox";

    private SandboxManifest() {
    }

    /**
     * Builds the Sandbox CR as a plain map, ready to serialize and create.
     *
     * @param name    sandbox name, also used as the pod name
     * @param request the validated remediation request
     * @param prompt  remediation prompt passed to the coding agent
     * @param config  sandbox runtime configuration
     */
    public static Map<String, Object> build(String name, RemediationRequest request, String prompt,
            SandboxConfig config) {
        List<EnvVar> env = new ArrayList<>(List.of(
                env("REPO_URL", request.repoUrl()),
                env("BASE_BRANCH", request.baseBranch()),
                env("CODING_AGENT", config.codingAgent()),
                env("REMEDIATION_PROMPT", prompt),
                tokenEnv("GITHUB_TOKEN", config)));
        if (config.model() != null && !config.model().isBlank()) {
            env.add(env("MODEL", config.model()));
        }

        var podSecurityContext = new io.fabric8.kubernetes.api.model.PodSecurityContextBuilder()
                .withRunAsNonRoot(true)
                .withFsGroup(1001L)
                .withNewSeccompProfile()
                .withType("RuntimeDefault")
                .endSeccompProfile()
                .build();

        PodSpec podSpec = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false)
                .withSecurityContext(podSecurityContext)
                .withVolumes(
                        new VolumeBuilder()
                                .withName("workspace")
                                .withNewEmptyDir()
                                .endEmptyDir()
                                .build(),
                        new VolumeBuilder()
                                .withName("agent-home")
                                .withNewEmptyDir()
                                .endEmptyDir()
                                .build())
                .withContainers(container(config, env))
                .build();

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
        return "remediation-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Container container(SandboxConfig config, List<EnvVar> env) {
        return new ContainerBuilder()
                .withName("remediation")
                .withImage(config.image())
                .withEnv(env)
                .withNewResources()
                .withRequests(Map.of("cpu", new Quantity("500m"), "memory", new Quantity("512Mi")))
                .withLimits(Map.of("cpu", new Quantity("2"), "memory", new Quantity("2Gi")))
                .endResources()
                .withVolumeMounts(
                        new VolumeMountBuilder().withName("workspace").withMountPath("/workspace").build(),
                        new VolumeMountBuilder().withName("agent-home").withMountPath("/home/sandbox").build())
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

    private static EnvVar tokenEnv(String name, SandboxConfig config) {
        return new EnvVarBuilder()
                .withName(name)
                .withValueFrom(new EnvVarSourceBuilder()
                        .withNewSecretKeyRef(config.githubSecretName(), config.githubSecretKey(), false)
                        .build())
                .build();
    }
}
