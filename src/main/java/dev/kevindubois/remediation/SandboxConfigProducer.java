package dev.kevindubois.remediation;

import io.quarkus.logging.Log;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Binds sandbox configuration from application.properties into the
 * {@link SandboxConfig} record.
 */
public class SandboxConfigProducer {

    @ConfigProperty(name = "remediation.sandbox.image")
    String image;

    @ConfigProperty(name = "remediation.sandbox.coding-agent", defaultValue = "opencode")
    String codingAgent;

    @ConfigProperty(name = "remediation.sandbox.model")
    Optional<String> model;

    @ConfigProperty(name = "remediation.sandbox.github-secret-name", defaultValue = "remediation-agent")
    String githubSecretName;

    @ConfigProperty(name = "remediation.sandbox.github-secret-key", defaultValue = "github_token")
    String githubSecretKey;

    @ConfigProperty(name = "remediation.sandbox.timeout-seconds", defaultValue = "1200")
    int timeoutSeconds;

    @Produces
    @Singleton
    SandboxConfig sandboxConfig() {
        Log.infof("Sandbox config: image=%s, agent=%s", image, codingAgent);
        return new SandboxConfig(image, codingAgent, model.orElse(null), githubSecretName, githubSecretKey,
                timeoutSeconds);
    }
}
