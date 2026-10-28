package dev.kevindubois.remediation.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kevindubois.remediation.RemediationRequest;
import dev.kevindubois.remediation.RemediationResponse;
import dev.kevindubois.remediation.RemediationService;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the A2A executor contract: JSON request in, remediation delegated,
 * JSON response out — and no blocking of the calling thread.
 */
class RemediationAgentExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RemediationService service = mock(RemediationService.class);
    private final AgentEmitter emitter = mock(AgentEmitter.class);
    private final RequestContext context = mock(RequestContext.class);

    private final RemediationAgentExecutor producer = new RemediationAgentExecutor(service, mapper);

    @Test
    void delegatesRequestAndEmitsResponseJson() throws Exception {
        var request = new RemediationRequest(
                "https://github.com/kdubois/argo-rollouts-quarkus-demo", "main", "CODE_BUG",
                "NPE in getStatus", "null dereference", "error rate spiked", "trace");
        var response = new RemediationResponse("success", "https://github.com/pr/1", null,
                "fix/k8s-issue-1", 2, "Tests run: 10, Failures: 0", "remediation-ab", null);

        when(service.remediate(any(RemediationRequest.class), any())).thenReturn(response);
        when(context.getMessage()).thenReturn(userMessage(mapper.writeValueAsString(request)));

        AtomicReference<String> artifact = new AtomicReference<>();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Part<?>> parts = inv.getArgument(0);
            artifact.set(((TextPart) parts.get(0)).text());
            return null;
        }).when(emitter).addArtifact(any(List.class), any(), any(), any());

        AgentExecutor executor = producer.agentExecutor();
        executor.execute(context, emitter);

        verify(service).remediate(any(RemediationRequest.class), any());
        verify(emitter).startWork();
        verify(emitter).complete();
        assertThat(artifact.get()).isNotNull();
        var parsed = mapper.readValue(artifact.get(), RemediationResponse.class);
        assertThat(parsed.prLink()).isEqualTo("https://github.com/pr/1");
        assertThat(parsed.status()).isEqualTo("success");
    }

    @Test
    void failsTaskOnInvalidRequest() throws Exception {
        when(context.getMessage()).thenReturn(userMessage("not json"));

        AgentExecutor executor = producer.agentExecutor();
        executor.execute(context, emitter);

        verify(emitter).fail(any(Message.class));
    }

    private Message userMessage(String text) {
        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(List.of(new TextPart(text)))
                .build();
    }
}
