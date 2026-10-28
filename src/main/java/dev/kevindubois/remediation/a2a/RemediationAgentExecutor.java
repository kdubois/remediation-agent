package dev.kevindubois.remediation.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kevindubois.remediation.RemediationRequest;
import dev.kevindubois.remediation.RemediationResponse;
import dev.kevindubois.remediation.RemediationService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.List;

/**
 * A2A protocol bridge: parses the remediation request from the message text,
 * delegates to {@link RemediationService}, and returns the response JSON as
 * the task artifact.
 *
 * <p>execute() runs the remediation synchronously. The A2A request handler
 * ends the streaming response when execute() returns, whatever the task
 * state — so returning early (with the work on a background thread) closes
 * the stream before the terminal event and the client never receives the
 * artifact. Running inline keeps the response open for the whole sandbox
 * run: the WORKING event streams immediately, the artifact and terminal
 * event arrive when the remediation finishes.
 */
@ApplicationScoped
public class RemediationAgentExecutor {

    @Inject
    RemediationService remediationService;

    @Inject
    ObjectMapper mapper;

    RemediationAgentExecutor(RemediationService remediationService, ObjectMapper mapper) {
        this.remediationService = remediationService;
        this.mapper = mapper;
    }

    @Produces
    public AgentExecutor agentExecutor() {
        return new AgentExecutor() {

            @Override
            public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
                RemediationRequest request = parseRequest(context.getMessage());
                if (request == null) {
                    emitter.fail(message("Invalid request: expected a JSON RemediationRequest as message text"));
                    return;
                }

                Log.infof("A2A: remediation request for %s", request.repoUrl());
                emitter.startWork();

                try {
                    RemediationResponse response = remediationService.remediate(request, emitter.getTaskId());
                    Log.infof("A2A: remediation finished: %s", response.status());
                    emitter.addArtifact(List.of(new TextPart(mapper.writeValueAsString(response))),
                            null, "remediation", null);
                    emitter.complete();
                } catch (Exception e) {
                    Log.errorf(e, "A2A: remediation failed");
                    emitter.fail(message("Remediation failed: " + e.getMessage()));
                }
            }

            @Override
            public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
                Log.infof("A2A: cancelling remediation task %s", emitter.getTaskId());
                remediationService.cancel(emitter.getTaskId());
                emitter.cancel();
            }
        };
    }

    private RemediationRequest parseRequest(Message message) {
        String text = extractText(message);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(text, RemediationRequest.class);
        } catch (Exception e) {
            Log.warnf("Could not parse remediation request: %s", e.getMessage());
            return null;
        }
    }

    private String extractText(Message message) {
        if (message == null || message.parts() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Part<?> part : message.parts()) {
            if (part instanceof TextPart textPart) {
                sb.append(textPart.text());
            }
        }
        return sb.toString().trim();
    }

    private Message message(String text) {
        return Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart(text)))
                .build();
    }
}
