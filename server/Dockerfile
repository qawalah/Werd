# Werd server image. DevStore's docker-release workflow builds it on GitHub for every new
# version in devstore.json and publishes it privately to ghcr.io/qawalah/werd-server
# (Intel/AMD only). The NAS runs it with docker-compose.yml, and Watchtower keeps it updated.
# By hand: docker build --build-arg VERSION=1.8.0 -t werd-server .

# The server, built from source.
FROM mirror.gcr.io/library/golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /werd-server .

# The AI page reader: llama.cpp's llama-server, CPU build (it picks the best of its CPU
# variants at run time, down to SSE4.2 for NAS processors without AVX). The build must match
# llamaBuild in gpu.go, like scripts/prepare-llama.ps1; update the SHA-256 with it.
FROM mirror.gcr.io/library/golang:1.26-alpine AS engine
ADD --checksum=sha256:81b133a0cd266851ebbe3eea21edf87e045d381699026a620cf1387f0cf55fe3 \
    https://github.com/ggml-org/llama.cpp/releases/download/b11800/llama-b11800-bin-ubuntu-x64.tar.gz /tmp/llama.tar.gz
RUN set -e; mkdir -p /opt/llama; tar -xzf /tmp/llama.tar.gz -C /tmp; cd /tmp/llama-b11800; \
    cp -a llama-server libllama-server-impl.so libllama.so* libllama-common.so* libmtmd.so* libggml*.so* /opt/llama/

# "cc" = the small distroless base plus the C/C++ runtime, OpenMP and OpenSSL the engine needs
# (every base image comes from Google's registries, so Docker Hub's pull limits don't apply).
FROM gcr.io/distroless/cc-debian12
COPY --from=build /werd-server /werd-server
COPY --from=engine /opt/llama /opt/llama
# Fail the build here if the engine is missing a library, not when someone first reads a page.
RUN ["/opt/llama/llama-server", "--version"]
ENV WERD_DATA=/data WERD_ADDR=:8080 WERD_DOCKER=1
VOLUME /data
EXPOSE 8080
# DevStore only publishes the image after this reports healthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD ["/werd-server", "healthcheck"]
ENTRYPOINT ["/werd-server"]
LABEL org.opencontainers.image.title="Werd Server" \
      org.opencontainers.image.description="Shared library and family features for the Werd app"
