package dev.kevindubois.remediation.a2a;

import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.List;

/**
 * A2A agent card for the remediation agent. The card advertises the remediate
 * skill that the kubernetes-agent consumes through a LangChain4j A2A client
 * agent after a rollback decision.
 */
@ApplicationScoped
public class RemediationAgentCard {

    @ConfigProperty(name = "a2a.agent.url", defaultValue = "http://localhost")
    String agentUrl;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int port;

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        return AgentCard.builder()
                .name("Remediation Agent")
                .description("Remediates failed canary rollouts: runs a swappable coding agent "
                        + "in an isolated sandbox, verifies the fix with the repository's test "
                        + "suite, and opens a GitHub PR with the verification evidence or a "
                        + "GitHub issue explaining the failure.")
                .version("1.0.0")
                .supportedInterfaces(List.of(
                        new AgentInterface(TransportProtocol.JSONRPC.asString(), agentUrl + ":" + port)))
                .capabilities(AgentCapabilities.builder()
                        // Streaming keeps the response open until the terminal task
                        // event: the long sandbox run must not rely on the blocking
                        // path, whose agent timeout would return a bare WORKING task.
                        .streaming(true)
                        .pushNotifications(false)
                        .build())
                .skills(List.of(AgentSkill.builder()
                        .id("remediate")
                        .name("Remediate canary rollback")
                        .description("Fixes the bug that caused a canary rollback. Expects a JSON "
                                + "RemediationRequest (repoUrl, baseBranch, issueCategory, title, "
                                + "rootCause, analysis, errorLogs) as the message text and returns "
                                + "a JSON RemediationResponse with the PR or issue link.")
                        .tags(List.of("remediation", "canary", "github", "sandbox"))
                        .build()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .build();
    }
}
