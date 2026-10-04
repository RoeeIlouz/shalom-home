# Promo video

`promo.html` is a timeline (intro, one scene per feature, outro) that `render.py` seeks frame by frame in
Chromium and pipes into ffmpeg, so the output is identical on every run.

```
python render.py shalom 16x9 out/shalom-home-16x9.mp4   # 1920x1080
python render.py shalom 9x16 out/shalom-home-9x16.mp4   # 1080x1920, Shorts / Reels
python render.py shalom 16x9 still.png --still 9.5      # one frame, for review
```

Needs Playwright + Chromium and ffmpeg on PATH. Scene text and timing live in `SCENES` in `promo.html`.

Screens in `assets/shalom/` are emulator captures of a debug build. The voice screens come from the
debug-only `demo_state` hook, which only displays a state and never records, dials or opens anything:

```
adb shell am start -n com.saba.home/.MainActivity --es demo_state confirm   # listening | thinking | confirm | notunderstood
```

Adapted from the ROCIs Apps promo renderer.
