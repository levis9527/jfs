# Development base image for the jfs Java project.
# Bundles a pinned JDK 21 toolchain and Maven so Cloud Agents can build, test,
# and run the project without any per-agent toolchain installation.
FROM maven:3.9.11-eclipse-temurin-21

# git and curl are useful for source checkout and general tooling in a fully
# custom image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
