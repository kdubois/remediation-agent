package dev.kevindubois.remediation.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kevindubois.remediation.RemediationRequest;
import dev.kevindubois.remediation.RemediationResponse;
import dev.kevindubois.remediation.RemediationService;
import io.quarkus.logging.Log;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A2A protocol bridge: parses the remediation request from the message text,
 * delegates to {@link RemediationService}, and returns the response JSON as
 * the task artifact.
 *
 * <p>The sandbox run takes minutes, so the executor does not block the A2A
 * request thread: it reports the task as working and completes it on a
 * virtual thread when the remediation finishes.
 */
@ApplicationScoped
public class RemediationAgentExecutor {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final RemediationService remediationService;

    private final ObjectMapper mapper;

    @Inject
    public RemediationAgentExecutor(RemediationService remediationService, ObjectMapper mapper) {
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

                executor.execute(() -> {
                    try {
                        RemediationResponse response = remediationService.remediate(request);
                        Log.infof("A2A: remediation finished: %s", response.status());
                        emitter.addArtifact(List.of(new TextPart(mapper.writeValueAsString(response))),
                                null, "remediation", null);
                        emitter.complete();
                    } catch (Exception e) {
                        Log.errorf(e, "A2A: remediation failed");
                        emitter.fail(message("Remediation failed: " + e.getMessage()));
                    }
                });
            }

            @Override
            public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
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
