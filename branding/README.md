# Branding

The logo, and listing assets for the app stores (Amazon Appstore, Google Play). Regenerate
whenever the icon or the UI changes materially. `concepts/` holds the original logo concepts
(B became the logo). Moved here from `store/branding/` to match other projects (issue #326).

## Logo colours (issue #324)

| Part | Colour |
|---|---|
| Background | `#152238` |
| Head outline (4.6-unit stroke on the 108-unit canvas) | `#C7CED9` |
| Eyes and nose | `#9AA4B4` |
| Muzzle | `#57C9B4` (Aqua) |
| Bookmark | `#8A94A6` |

The muzzle is drawn under the outline. The same artwork is in
`app/src/main/res/drawable/ic_launcher_foreground.xml` (the source of truth) and the iOS app
icon, `iosApp/Dexxicon/Assets.xcassets/AppIcon.appiconset/icon-1024.png`. That one is rendered
from `icon.svg` without the store icon's vertical-centring shift, the same layout as the
Android launcher.

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

When only the icon changes, keep the promo's text: resize the render to 300×300 and paste it
over the icon tile at (64, 100) (issue #312 did this for the Aqua muzzle). Keep `icon.svg` in
step with `app/src/main/res/drawable/ic_launcher_foreground.xml`, which is the source of truth
for the artwork. One intended difference (issue #314): the store icon shifts the mark up 9.25
units so it is vertically centred on the square, while the launcher keeps its original layout
for Android's icon masks.

Screenshots — `adb shell wm size 2560x1600 && adb shell wm density 400`, restart the app,
`adb shell screencap -p /sdcard/x.png && adb pull …` per screen, then `adb shell wm size
reset && adb shell wm density reset`.
