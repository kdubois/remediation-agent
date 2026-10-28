# Remediation sandbox image.
#
# Base: minimal UBI 9 with the toolchain every coding agent needs (git, JDK 25,
# Maven). The coding agent itself is selected by build argument so each CLI gets
# its own image tag while the wrapper contract stays identical:
#
#   docker build --build-arg CODING_AGENT=opencode -t remediation-sandbox:opencode .
#
# The container runs as a non-root user with a writable workspace volume; the
# runtime hardening (restricted-v2 SCC, read-only root filesystem, no service
# account token, egress allow-list) is applied by the Sandbox CR the service
# creates — see AGENTS.md for the isolation model.
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

ARG CODING_AGENT=opencode

RUN microdnf install -y git java-25-openjdk-devel maven openssl tar \
    && microdnf clean all \
    && useradd -m -u 1001 sandbox

# Coding agent CLI: opencode v2 binary from the official v2 installer
# (opencode.ai/install serves the 1.x line; v2 lives at /v2/install).
# No build-time version check: the bun-compiled binary aborts under qemu
# emulation on arm build hosts; it runs fine on real x86-64 hardware.
RUN curl -fsSL https://opencode.ai/v2/install | bash -s -- --version 2.2.0 \
    && mv /root/.opencode/bin/opencode /usr/local/bin/opencode \
    && test -x /usr/local/bin/opencode

USER 1001
WORKDIR /workspace

COPY --chmod=755 run-agent.sh /usr/local/bin/run-agent.sh

ENTRYPOINT ["/usr/local/bin/run-agent.sh"]
