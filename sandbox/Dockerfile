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

RUN microdnf install -y git java-25-openjdk-devel maven openssl tar unzip \
    && microdnf clean all \
    && useradd -m -u 1001 sandbox

# Coding agent CLI: opencode v2 binary from the official v2 installer
# (opencode.ai/install serves the 1.x line; v2 lives at /v2/install).
# No build-time version check: the bun-compiled binary aborts under qemu
# emulation on arm build hosts; it runs fine on real x86-64 hardware.
RUN curl -fsSL https://opencode.ai/v2/install | bash -s -- --version 2.2.0 \
    && mv /root/.opencode/bin/opencode /usr/local/bin/opencode \
    && test -x /usr/local/bin/opencode

# Java 25 is the project's toolchain: make it the default for mvn, mvnw and
# the coding agent's shells — the RHEL default java on PATH is 17, which fails
# with UnsupportedClassVersionError on class file version 69.
ENV JAVA_HOME=/usr/lib/jvm/java-25

# Pre-bake the Maven repository for the demo project so sandbox runs resolve
# nothing through the egress allow-list. A full build of the demo repo — not
# dependency:go-offline — is required: the Quarkus platform properties
# artifact resolves only during a real Quarkus build, and a seed without it
# cannot bootstrap an offline verification. Baked at /opt/maven-repo, NOT
# /home/sandbox/.m2: the Sandbox CR mounts an empty agent-home volume over
# /home/sandbox at runtime, which would shadow anything baked there —
# run-agent.sh seeds $HOME/.m2 from these copies. The Maven wrapper
# distribution is baked the same way, so runs do not download Maven itself
# either. Building from a clone of the repo makes the seed track the current
# pom, which replaces the pom-demo.xml mirror and its manual refresh.
RUN git clone -q --depth 1 https://github.com/kdubois/argo-rollouts-aiops-dashboard /tmp/seed \
    && cd /tmp/seed \
    && ./mvnw -q -Dmaven.repo.local=/opt/maven-repo test \
    && cp -a /root/.m2/wrapper /opt/maven-wrapper \
    && cd / \
    && rm -rf /tmp/seed

USER 1001
WORKDIR /workspace

COPY --chmod=755 run-agent.sh /usr/local/bin/run-agent.sh

ENTRYPOINT ["/usr/local/bin/run-agent.sh"]
