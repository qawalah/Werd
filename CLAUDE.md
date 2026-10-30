# DevStore app repo rules (Android app + Windows server)

This repo is released through DevStore. Pushing to `main` builds both parts. Each
part releases on its own when its version is bumped.

## Android app (`app/`)

A new `versionCode` publishes a GitHub Release (tag `android-v...`) that installs
itself on the user's phone through Obtainium (filter releases with `^android-`).

- **Never change `applicationId`.** The phone would treat it as a different app.
- **Bump `versionCode` (+1) and `versionName`** in `app/build.gradle(.kts)` for
  every change the user should receive. No bump means no release (the build
  still runs as a check).
- **Don't add a release `signingConfig`** and don't commit keystores. CI signs
  every release with the shared DevStore key.
- Keep `gradlew`, `gradlew.bat` and `gradle/wrapper/*` committed.
- When a change is done: commit and push to `main`. That push *is* the
  release. If the environment allows it, run `./gradlew assembleRelease` first.
- If the Release workflow fails, read its log in the Actions tab and fix it.
- `HANDOVER.md` (if present) holds history from the original Claude chat.
  Read it before making larger changes.

## Windows server (`server/`, werd-server)

A new `version` in `server/devstore.json` publishes a GitHub Release (tag
`windows-v...`) that the DevStore Updater installs on the Windows PC. It is built
with `go build` (static exe, `-X main.version=<version>`), then
`server/scripts/prepare-llama.ps1` adds the llama.cpp CPU engine in `llama/`
next to the exe.

- **Never change `name`** (`werd-server`), `language` or `type` in
  `server/devstore.json`. Bump `version` (`MAJOR.MINOR.PATCH`) for every server
  change the user should receive. Keep `build.sh` (`VERSION`), `Dockerfile`,
  `docker-compose.yml` and `winres/winres.json` (then regenerate the `.syso`
  files) in step with it.
- It is a `service`: DevStore installs and runs it as `devstore-werd-server`.
  Don't use the exe's own `install` command on a DevStore-managed PC. It runs
  in the foreground and exits cleanly on Ctrl+C / SIGTERM.
- Data lives in `%ProgramData%\Werd Server` (kept so existing installs keep their
  data), never next to the exe.
- The engine build in `scripts/prepare-llama.ps1` must match `llamaBuild` in
  `gpu.go`. Update its SHA-256 when changing the build.
- Keep `go.mod` and `go.sum` committed. Embed web assets with `go:embed`.
- Store listing: `displayName`, `description` and `icon` (`winres/icon.png`).
