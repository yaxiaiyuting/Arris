# Arris · Tux launcher

The portrait reuses the face, eye proportions, beak and green headphones from
`app/src/main/res/drawable/tux_listener.xml`, with a compact shoulder/scarf silhouette.
Tux: Larry Ewing / The GIMP. Arris vector redraw and generator: GPL-3.0.

Rebuild all vector layers, API 24/25 PNGs and the preview:

```bash
python3 tools/icons/generate_tux_launcher.py
```

Dependencies: `rsvg-convert`, Python `Pillow`, `numpy`.
The generator owns the launcher resources; edit it rather than individual outputs.
The previous external `arris-icons/install_into_repo.py` generates the retired disc
logo and should not be run against the current app.

- `tux-launcher.svg`: round icon SVG master, using the actual centre-72dp framing.
- `tux-monochrome.svg`: alpha silhouette for themed icons and the notification mark.
- `preview.png`: circle, rounded square, squircle and themed versions at 160/48/32/24px.
- `geometry.txt`: measured alpha bounds; visible foreground stays within r=34dp on the 108dp adaptive canvas.
- Normal and round legacy PNGs cover 48, 72, 96, 144 and 192px.

The notification uses a separately framed 24dp alpha vector, with no launcher
background. About/splash use the same portrait. Playback transport actions retain
their play, pause, previous and next symbols.
