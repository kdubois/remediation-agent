# Remediation prompt template

The service composes `REMEDIATION_PROMPT` from the canary analysis result and passes
it to the sandbox. `run-agent.sh` appends the rules section; only the context below
varies per remediation.

```markdown
## What happened

A canary deployment of this repository was rolled back by an automated analysis.

**Decision:** rollback (confidence {confidence}%)
**Category:** {issueCategory}

**Root cause**

{rootCause}

**Analysis**

{analysis}

**Canary error logs**

```
{errorLogs}
```
```
