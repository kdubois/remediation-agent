package dev.kevindubois.remediation;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Binds sandbox configuration from application.properties into the
 * {@link SandboxConfig} record.
 */
@ApplicationScoped
public class SandboxConfigProducer {

    @ConfigProperty(name = "remediation.sandbox.image")
    String image;

    @ConfigProperty(name = "remediation.sandbox.namespace", defaultValue = "remediation")
    String namespace;

    @ConfigProperty(name = "remediation.sandbox.coding-agent", defaultValue = "opencode")
    String codingAgent;

    @ConfigProperty(name = "remediation.sandbox.model")
    Optional<String> model;

    @ConfigProperty(name = "remediation.sandbox.llm.base-url")
    Optional<String> llmBaseUrl;

    @ConfigProperty(name = "remediation.sandbox.llm.secret-name", defaultValue = "remediation-agent")
    String llmSecretName;

    @ConfigProperty(name = "remediation.sandbox.llm.secret-key", defaultValue = "llm_api_key")
    String llmSecretKey;

    @ConfigProperty(name = "remediation.sandbox.github-secret-name", defaultValue = "remediation-agent")
    String githubSecretName;

    @ConfigProperty(name = "remediation.sandbox.github-secret-key", defaultValue = "github_token")
    String githubSecretKey;

    @ConfigProperty(name = "remediation.sandbox.timeout-seconds", defaultValue = "1800")
    int timeoutSeconds;

    @ConfigProperty(name = "remediation.sandbox.keep-failed", defaultValue = "false")
    boolean keepFailedSandboxes;

    @Produces
    @Singleton
    SandboxConfig sandboxConfig() {
        Log.infof("Sandbox config: image=%s, agent=%s, namespace=%s", image, codingAgent, namespace);
        return new SandboxConfig(image, namespace, codingAgent, model.orElse(null), llmBaseUrl.orElse(null),
                llmSecretName, llmSecretKey, githubSecretName, githubSecretKey, timeoutSeconds,
                keepFailedSandboxes);
    }
}
