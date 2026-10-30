#!/bin/sh
# Builds the Windows release files into dist/windows/: werd-server.exe (+ arm64).
# The Docker image for the NAS is built from source by Dockerfile, on GitHub by DevStore.
set -e
cd "$(dirname "$0")"
VERSION="${VERSION:-1.8.0}"
LDFLAGS="-s -w -X main.version=$VERSION"
# keep the prebuilt AI engine (dist/windows/llama/) across rebuilds
mkdir -p dist/windows && rm -f dist/windows/*.exe

go test ./...

# Windows (icon and version info come from rsrc_windows_*.syso, made by: go-winres make --in winres/winres.json --arch amd64,arm64)
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "$LDFLAGS" -o dist/windows/werd-server.exe .
CGO_ENABLED=0 GOOS=windows GOARCH=arm64 go build -trimpath -ldflags "$LDFLAGS" -o dist/windows/werd-server-arm64.exe .

echo "binaries:"; ls -la dist/windows
