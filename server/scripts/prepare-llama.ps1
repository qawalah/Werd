# DevStore prepare step: ships the llama.cpp CPU engine (llama-server.exe and its DLLs) in a
# "llama" folder next to werd-server.exe, where the server looks for it (see ocr.go, Engine()).
# The CUDA engine is not bundled; the server downloads it on demand (gpu.go, same build number).
$ErrorActionPreference = 'Stop'
$build = 'b11800'   # keep equal to llamaBuild in gpu.go
$sha256 = '99154bbdcec5a62b8d45884b016830699e29cf1464536edcc542a69b42349e7d'
$url = "https://github.com/ggml-org/llama.cpp/releases/download/$build/llama-$build-bin-win-cpu-x64.zip"

$tmp = Join-Path ([IO.Path]::GetTempPath()) "llama-$build.zip"
Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
$got = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
if ($got -ne $sha256) { throw "llama.cpp $build checksum mismatch (got $got)" }

$dest = Join-Path $env:DEVSTORE_OUT 'llama'
New-Item -ItemType Directory -Force $dest | Out-Null
Expand-Archive -Path $tmp -DestinationPath $dest -Force
if (-not (Test-Path (Join-Path $dest 'llama-server.exe'))) { throw 'llama-server.exe missing after unpacking' }
Remove-Item $tmp
Write-Output "llama.cpp $build CPU engine placed in $dest"
