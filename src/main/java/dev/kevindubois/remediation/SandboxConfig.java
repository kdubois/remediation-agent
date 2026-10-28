package dev.kevindubois.remediation;

/**
 * Sandbox runtime configuration, bound from application.properties and passed
 * explicitly so tests can construct it directly.
 *
 * @param image            sandbox image tag, selects the coding agent (opencode, pi, bob)
 * @param codingAgent      value for the wrapper's CODING_AGENT env var
 * @param model            model id passed through to the coding agent, may be blank
 * @param githubSecretName secret holding the GitHub token the sandbox uses to push
 * @param githubSecretKey  key of the token inside that secret
 * @param timeoutSeconds   hard limit for one sandbox run
 */
public record SandboxConfig(
        String image,
        String codingAgent,
        String model,
        String githubSecretName,
        String githubSecretKey,
        int timeoutSeconds) {
}
