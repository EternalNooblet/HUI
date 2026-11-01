#!/usr/bin/env python3
"""One-line-per-request STT pipe for the companion server.

MUST be launched with Hermes's committed-venv python (resolved by the server from
~/.hermes/installs/<hash>/facts.json → packages.venv). Reuses Hermes's faster-whisper
`base` via tools.transcription_tools._transcribe_local — ONE model total, CPU/int8,
never a second copy loaded, never pip-installs anything (hermes-stt-venv-safety rule).

Protocol: NDJSON in, NDJSON out.
  in:  {"wav": "/abs/path.wav", "prompt": "hotword hints or ''"}
  out: {"success": true, "transcript": "..."}  (the _ok_result / _error_result envelope)
"""
import sys
import json
import logging

logging.basicConfig(level=logging.INFO)

from tools.transcription_tools import _transcribe_local  # committed venv finder resolves this
from tools.transcription_common import DEFAULT_LOCAL_MODEL


def local_model_name() -> str:
    try:
        from tools.transcription_common import _get_stt_section
        from tools.transcription_tools import _load_stt_config
        cfg = _load_stt_config()
        model = (_get_stt_section(cfg, "local") or {}).get("model")
        return model or DEFAULT_LOCAL_MODEL
    except Exception:
        return DEFAULT_LOCAL_MODEL


def main() -> None:
    model = None
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            sys.stdout.write(json.dumps({"success": False, "transcript": "", "error": "bad request"}) + "\n")
            sys.stdout.flush()
            continue
        wav = req.get("wav")
        prompt = (req.get("prompt") or "").strip() or None
        if not wav:
            out = {"success": False, "transcript": "", "error": "no wav"}
        else:
            if model is None:
                model = local_model_name()
                sys.stderr.write(f"[stt-helper] using configured model: {model}\n")
            res = _transcribe_local(wav, model, prompt=prompt)
            out = {"success": bool(res.get("success")), "transcript": res.get("transcript", "")}
            if not res.get("success"):
                out["error"] = res.get("error", "transcription failed")
        sys.stdout.write(json.dumps(out) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
