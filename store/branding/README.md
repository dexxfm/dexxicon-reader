# Store branding assets

Listing assets for the app stores (Amazon Appstore, Google Play). Regenerate whenever the
icon or the UI changes materially.

## Files

| File | Size | Used for |
|---|---|---|
| `icon.svg` | vector | Source for the launcher/store icon — the `ic_launcher` adaptive icon flattened (background `#152238` + `ic_launcher_foreground` paths). |
| `icon-512x512.png` | 512×512 | Store icon (Amazon "512 x 512px", Play "hi-res icon"). PNG-32. |
| `icon-114x114.png` | 114×114 | Amazon small icon. |
| `promotional-1024x500.png` | 1024×500 | Amazon promotional image / Play feature graphic (landscape). |
| `screenshot-1-continue.png` … `screenshot-5-comic-reader.png` | 2560×1600 | Tablet screenshots (Amazon's largest accepted size; landscape). Captured on the `Pixel_10_Pro_Fold` AVD forced to `wm size 2560x1600` / `wm density 400`. |

## Regenerating

Icons/promo — render `icon.svg` at 1024², then downscale:

```bash
chrome --headless --disable-gpu --force-device-scale-factor=1 \
  --default-background-color=00000000 --window-size=1024,1024 \
  --screenshot=icon_render.png icon.html    # icon.html wraps icon.svg with margin:0
```

then resize with Pillow to 512 / 114, and composite the 1024×500 promo (icon left, title +
tagline right, `#152238` background).

Screenshots — `adb shell wm size 2560x1600 && adb shell wm density 400`, restart the app,
`adb shell screencap -p /sdcard/x.png && adb pull …` per screen, then `adb shell wm size
reset && adb shell wm density reset`.
