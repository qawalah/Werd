# Werd's graphics-card voice: Chatterbox Multilingual (Resemble AI, MIT licence) reading Arabic.
# Started by the Werd server on demand (127.0.0.1 only) and stopped when idle. Not meant to be run by hand.
#
#   GET  /health  -> {"ok": true, "device": "cuda", "gpu": "..."}
#   POST /speak   {"text": "...", "ref": "<path to a reference .wav>", "exaggeration": 0.5, "cfg": 0.5} -> audio/wav
#
# "--warmup" downloads the model (about 3 GB, into HF_HOME) and exits.

import argparse
import io
import json
import re
import sys
import threading
import traceback
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=0)
ap.add_argument("--warmup", action="store_true")
args = ap.parse_args()

import numpy as np  # noqa: E402
import torch  # noqa: E402
from chatterbox.mtl_tts import ChatterboxMultilingualTTS  # noqa: E402

device = "cuda" if torch.cuda.is_available() else "cpu"
gpu = torch.cuda.get_device_name(0) if device == "cuda" else ""
model = ChatterboxMultilingualTTS.from_pretrained(device=device)
print(f"werd-voice: model loaded on {device} {gpu}", flush=True)
if args.warmup:
    sys.exit(0)

lock = threading.Lock()
current = {"key": None}

# Sentence-sized pieces keep each generation well inside the model's length limit.
SPLIT = re.compile(r"(?<=[\.\!\?؟؛،۔:؛،])\s+")


def pieces(text, limit=220):
    out, cur = [], ""
    for part in SPLIT.split(text.strip()):
        part = part.strip()
        if not part:
            continue
        if cur and len(cur) + 1 + len(part) > limit:
            out.append(cur)
            cur = part
        else:
            cur = (cur + " " + part).strip()
        while len(cur) > limit:  # a very long sentence: cut at a space
            cut = cur.rfind(" ", 0, limit)
            cut = cut if cut > 40 else limit
            out.append(cur[:cut].strip())
            cur = cur[cut:].strip()
    if cur:
        out.append(cur)
    return out or [text]


def speak(text, ref, exaggeration, cfg):
    with lock:
        key = (ref, round(exaggeration, 2))
        if ref and current["key"] != key:
            model.prepare_conditionals(ref, exaggeration=exaggeration)
            current["key"] = key
        chunks = []
        gap = np.zeros(int(model.sr * 0.18), dtype=np.float32)
        for p in pieces(text):
            wav = model.generate(p, language_id="ar", exaggeration=exaggeration, cfg_weight=cfg)
            a = wav.squeeze().detach().cpu().numpy().astype(np.float32)
            if chunks:
                chunks.append(gap)
            chunks.append(a)
        audio = np.concatenate(chunks) if chunks else np.zeros(1, dtype=np.float32)
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2").tobytes()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(model.sr)
        w.writeframes(pcm)
    return buf.getvalue()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            self.send(200, json.dumps({"ok": True, "device": device, "gpu": gpu}).encode(), "application/json")
        else:
            self.send(404, b"{}", "application/json")

    def do_POST(self):
        if not self.path.startswith("/speak"):
            self.send(404, b"{}", "application/json")
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            req = json.loads(self.rfile.read(n).decode("utf-8"))
            data = speak(req.get("text", ""), req.get("ref") or None,
                         float(req.get("exaggeration", 0.5)), float(req.get("cfg", 0.5)))
            self.send(200, data, "audio/wav")
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            self.send(500, json.dumps({"error": str(e)[:500]}).encode(), "application/json")


srv = ThreadingHTTPServer(("127.0.0.1", args.port), H)
print(f"werd-voice: listening on {args.port}", flush=True)
srv.serve_forever()
