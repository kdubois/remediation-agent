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

# Coding agent CLI: opencode binary from the official install script.
# (The opencode-ai npm package mis-selects its musl variant on glibc images.)
RUN curl -fsSL https://opencode.ai/install | bash \
    && mv /root/.opencode/bin/opencode /usr/local/bin/opencode \
    && /usr/local/bin/opencode --version

USER 1001
WORKDIR /workspace

COPY --chmod=755 run-agent.sh /usr/local/bin/run-agent.sh

ENTRYPOINT ["/usr/local/bin/run-agent.sh"]
