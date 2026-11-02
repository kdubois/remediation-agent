package dev.kevindubois.remediation;

/**
 * Outcome of one sandbox run: the wrapper's result JSON, or the reason no
 * result could be produced. Lets the caller tell a timed-out sandbox apart
 * from an infrastructure failure.
 */
public sealed interface SandboxOutcome {

    /** The wrapper ran to completion and emitted its result JSON. */
    record Completed(SandboxResult result) implements SandboxOutcome {
    }

    /** Why the run produced no result. */
    enum Reason {
        /** the pod never reached a terminal phase within the timeout */
        TIMEOUT,
        /** the Sandbox custom resource could not be created */
        CREATE_FAILED,
        /** the pod finished but its log could not be read */
        LOG_UNREADABLE,
        /** the log was read but contained no result JSON */
        NO_RESULT
    }

    record Failed(Reason reason) implements SandboxOutcome {
    }

    static SandboxOutcome completed(SandboxResult result) {
        return new Completed(result);
    }

    static SandboxOutcome failed(Reason reason) {
        return new Failed(reason);
    }
}
