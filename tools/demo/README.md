tools/demo — regenerate hui-demo.mp4
====================================

Runs the demo pipeline: narrate.sh synthesizes the voiceover segments,
framegen.py paces the session timeline into PNG frames, assemble.sh concatenates
the audio and encodes ../../hui-demo.mp4.

Requirements
- system python3 with Pillow + fonttools
- DejaVu Sans Mono installed (/usr/share/fonts/TTF/DejaVuSansMono.ttf)
- NotoSansBamum-Regular.ttf (this dir) for the 𖦏 prompt glyph — U+1698F is
  a Bamum letter; no DejaVu/Noto Mono font carries it. Downloaded from
  googlefonts/noto-fonts, SIL OFL.
- a running audio.cpp Ming-TTS server at 127.0.0.1:8188 for narrate.sh
  (each segment is one POST of {model, input} only — default voice, no
  options).
- ffmpeg

Run, from this directory:
    bash narrate.sh      # 8 narration wavs + durations.json
    python3 framegen.py  # 15fps PNG frames in frames/, paced by durations.json
    bash assemble.sh     # concat audio (+0.6s pad per segment) and encode
                         # ../../hui-demo.mp4

durations.json, seg*.wav, narration.wav, silence.wav and frames/ are
generated intermediates and gitignored.
