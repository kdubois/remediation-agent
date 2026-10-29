package dev.kevindubois.remediation.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the classified sandbox log the demo UI consumes: wrapper lines,
 * opencode tool calls and commentary, the result event, and truncation.
 */
class SandboxStreamResourceTest {

    private final SandboxStreamResource resource = new SandboxStreamResource(null, null, new ObjectMapper());

    private static final String SAMPLE_LOG = """
            [run-agent] clone ok
            {"type":"step_start","timestamp":1,"part":{"id":"p1"}}
            {"type":"tool_use","part":{"state":{"input":{"command":"cd /workspace/repo && grep -n nullString src/main/java/dev/kevindubois/demo/MetricsResource.java"}}}}
            {"type":"text","part":{"text":"The bug is at line 129: nullString.length() without a null check."}}
            {"type":"step_finish","reason":"tool-calls"}
            {"type":"tool_use","part":{"state":{"input":{"filePath":"src/main/java/dev/kevindubois/demo/MetricsResource.java"}}}}
            {"status":"success","branch":"fix/k8s-issue-abc","filesChanged":2,"testSummary":"Tests run: 11, Failures: 0","model":"m","durationSeconds":212}
            """;

    @Test
    void classifiesWrapperToolsAgentTextAndResult() {
        var events = resource.classify(SAMPLE_LOG);

        assertThat(events).hasSize(5);
        assertThat(events.get(0)).isEqualTo(Map.of("type", "wrapper", "text", "clone ok"));
        assertThat(events.get(1).get("type")).isEqualTo("tool");
        assertThat(events.get(1).get("text")).contains("grep -n nullString");
        assertThat(events.get(2)).isEqualTo(Map.of("type", "agent", "text",
                "The bug is at line 129: nullString.length() without a null check."));
        assertThat(events.get(3).get("type")).isEqualTo("tool");
        assertThat(events.get(3).get("text")).contains("MetricsResource.java");
        assertThat(events.get(4)).isEqualTo(Map.of("type", "result", "text", "success"));
    }

    @Test
    void dropsProtocolNoise() {
        var events = resource.classify("""
                {"type":"step_start","part":{}}
                {"type":"step_finish","reason":"tool-calls"}
                not json at all
                """);
        assertThat(events).isEmpty();
    }

    @Test
    void incrementalFetchReturnsOnlyNewLines() {
        var events = resource.classify(SAMPLE_LOG);
        int after = 2;
        List<Map<String, String>> fresh = events.subList(Math.min(after, events.size()), events.size());
        assertThat(fresh).hasSize(3);
        assertThat(fresh.get(2).get("type")).isEqualTo("result");
    }

    @Test
    void truncatesLongToolCommands() {
        var longText = "x".repeat(300);
        var events = resource.classify(
                "{\"type\":\"tool_use\",\"part\":{\"state\":{\"input\":{\"command\":\"" + longText + "\"}}}}");
        assertThat(events.get(0).get("text").length()).isLessThanOrEqualTo(161);
    }

    @Test
    void toolEventsCarryTheToolNameAndTarget() {
        var events = resource.classify(
                "{\"type\":\"tool_use\",\"part\":{\"tool\":\"read\",\"state\":{\"input\":{\"path\":\"src/main/java/Foo.java\"}}}}");
        assertThat(events.get(0).get("text")).isEqualTo("read src/main/java/Foo.java");
    }

    @Test
    void toolEventsWithoutATargetShowTheToolNameOnly() {
        var events = resource.classify(
                "{\"type\":\"tool_use\",\"part\":{\"tool\":\"think\",\"state\":{\"input\":{}}}}");
        assertThat(events.get(0).get("text")).isEqualTo("think");
    }

    @Test
    void emptyAndNullLogsAreSafe() {
        assertThat(resource.classify(null)).isEmpty();
        assertThat(resource.classify("")).isEmpty();
        assertThat(resource.classify("\n\n")).isEmpty();
    }
}
