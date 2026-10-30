#!/bin/sh
# Builds every release file into dist/:
#   dist/windows/werd-server.exe (+ arm64), dist/linux/werd-server-linux-{amd64,arm64,armv7}
# then (if Docker is available) the multi-platform image and one image file per NAS type.
set -e
cd "$(dirname "$0")"
VERSION="${VERSION:-1.8.0}"
LDFLAGS="-s -w -X main.version=$VERSION"
# keep the prebuilt AI engines (llama-server) across rebuilds
mkdir -p dist/windows dist/linux && rm -f dist/windows/*.exe dist/linux/werd-server-*

go test ./...

# Windows (icon and version info come from rsrc_windows_*.syso, made by: go-winres make --in winres/winres.json --arch amd64,arm64)
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "$LDFLAGS" -o dist/windows/werd-server.exe .
CGO_ENABLED=0 GOOS=windows GOARCH=arm64 go build -trimpath -ldflags "$LDFLAGS" -o dist/windows/werd-server-arm64.exe .

# Linux, for Docker / NAS
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "$LDFLAGS" -o dist/linux/werd-server-linux-amd64 .
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "$LDFLAGS" -o dist/linux/werd-server-linux-arm64 .
CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -trimpath -ldflags "$LDFLAGS" -o dist/linux/werd-server-linux-armv7 .

echo "binaries:"; ls -la dist/windows dist/linux
