# Builds the Werd server image on the machine that runs it (your NAS), from the ready-made programs in dist/linux.
# It picks the right one for the NAS's processor (Intel/AMD, ARM64 or older 32-bit ARM) by itself.
# You don't run this directly: "docker compose up -d" (or Synology Container Manager → Project) uses it.

# On Intel/AMD it also adds the AI page reader (llama.cpp's llama-server, from dist/linux/llama-amd64).
# (the "debug" variant of the small base image has a shell for this one step; nothing comes from Docker Hub)
FROM gcr.io/distroless/static-debian12:debug AS pick
SHELL ["/busybox/sh", "-c"]
COPY dist/linux/ /bin-all/
RUN set -e; \
    case "$(uname -m)" in \
      x86_64|amd64)          f=werd-server-linux-amd64 ;; \
      aarch64|arm64)         f=werd-server-linux-arm64 ;; \
      armv7l|armv7|armv8l|armhf) f=werd-server-linux-armv7 ;; \
      *) echo "Unsupported processor: $(uname -m) — Werd server runs on amd64, arm64 and armv7" >&2; exit 1 ;; \
    esac; \
    echo "Using $f for $(uname -m)"; \
    cp "/bin-all/$f" /werd-server && chmod 755 /werd-server; \
    mkdir -p /opt/llama; \
    if [ "$f" = werd-server-linux-amd64 ] && [ -d /bin-all/llama-amd64 ]; then \
      cp -r /bin-all/llama-amd64/. /opt/llama/ && chmod 755 /opt/llama/llama-server; echo "AI page reader included"; fi

# "cc" = the same small base plus the C/C++ runtime the AI engine needs
FROM gcr.io/distroless/cc-debian12
COPY --from=pick /werd-server /werd-server
COPY --from=pick /opt/llama /opt/llama
ENV WERD_DATA=/data WERD_ADDR=:8080 WERD_DOCKER=1
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD ["/werd-server", "healthcheck"]
ENTRYPOINT ["/werd-server"]
LABEL org.opencontainers.image.title="Werd Server" \
      org.opencontainers.image.description="Shared library and family features for the Werd app" \
      org.opencontainers.image.version="1.8.0"
