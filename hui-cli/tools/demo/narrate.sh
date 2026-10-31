#!/bin/bash
# Synthesize narration segments via Ming-TTS server (default voice preset, text only).
set -uo pipefail
cd "$(dirname "$0")"
SERVER="http://127.0.0.1:8188"

texts=(
"this is Hui, the Hermes unofficial interface. one binary, nothing installed, and nothing runs locally."
"it dials a remote Hermes gateway over web socket, waits for the ready event, and opens a fresh session."
"you type your message at the prompt. Enter sends it. shift Enter puts a newline inside the draft."
"the reply streams in as deltas, rendered live as it arrives. the transcript scrolls, and you can keep typing underneath."
"tool calls arrive as dim one-liners, token usage prints after the turn, and the prompt redraws on its own line."
"slash commands stay client-side. status shows the build stamp, the gateway, the session id, and the model."
"when the agent asks for approval, you answer inline. pick a choice number and press Enter."
"exit restores the terminal cleanly. the session stays on the gateway, ready to resume. that is Hui."
)

> durations.json
i=0
for t in "${texts[@]}"; do
  i=$((i+1))
  out="seg$i.wav"
  if ! curl -sf -X POST "$SERVER/v1/audio/speech" -H "Content-Type: application/json" \
      -d "$(jq -n --arg input "$t" '{model: "ming", input: $input}')" --output "$out"; then
    echo "seg$i FAILED" >&2; exit 1
  fi
  python3 - "$out" <<'PY'
import wave, struct, sys
p = sys.argv[1]
with wave.open(p, 'rb') as w:
    rate, frames = w.getframerate(), w.getnframes()
    data = w.readframes(frames)
    samples = struct.unpack('<' + 'h' * (len(data)//2), data)
    rms = int((sum(s*s for s in samples)/len(samples))**0.5) if samples else 0
    print(f"{p} rate={rate} frames={frames} rms={rms} dur={frames/rate:.2f}")
    if frames == 0 or rms == 0: sys.exit(1)
PY
  if [ $? -ne 0 ]; then echo "seg$i audio invalid" >&2; exit 1; fi
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out")
  echo "$dur" >> durations.json
  echo "seg$i ok dur=$dur"
done
echo "ALL SEGMENTS DONE"
