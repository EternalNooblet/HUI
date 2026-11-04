# tools/demo — regenerate the demo video

Regenerates `assets/hui-demo.mp4`: narration (TTS) → paced PNG frames → encoded video.

## Needs

- python3 with Pillow + fonttools
- DejaVu Sans Mono + NotoSansBamum (for the prompt glyph), ffmpeg
- A Ming-TTS server at `127.0.0.1:8188` (audio.cpp)

## Run (from this directory)

```bash
bash narrate.sh      # voiceover wavs + durations.json
python3 framegen.py  # PNG frames, paced by durations.json
bash assemble.sh     # concat audio, encode ../../assets/hui-demo.mp4
```

Generated intermediates (`frames/`, `*.wav`, `durations.json`) are gitignored.
