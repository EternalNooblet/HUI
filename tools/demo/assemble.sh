#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

python3 framegen.py

# silence tail for each narration segment
ffmpeg -y -v error -f lavfi -i anullsrc=r=44100:cl=mono -t 0.6 silence.wav

# concat segN.wav + silence for each of the 8 segments
> list.txt
for i in 1 2 3 4 5 6 7 8; do
  echo "file 'seg$i.wav'" >> list.txt
  echo "file 'silence.wav'" >> list.txt
done
ffmpeg -y -v error -f concat -safe 0 -i list.txt -c copy narration.wav

python3 - <<'PY'
import wave
with wave.open('narration.wav','rb') as w:
    print(f"narration: rate={w.getframerate()} ch={w.getnchannels()} dur={w.getnframes()/w.getframerate():.2f}s")
PY

OUT=../../assets/hui-demo.mp4
ffmpeg -y -v error -framerate 15 -i frames/f%05d.png -i narration.wav \
  -c:v libx264 -preset medium -crf 19 -pix_fmt yuv420p -c:a aac -b:a 192k \
  -movflags +faststart -shortest "$OUT"

ffprobe -v error -show_entries format=duration:stream=codec_type,width,height -of default=noprint_wrappers=1 "$OUT"
ls -l "$OUT"
echo ENCODE_DONE
