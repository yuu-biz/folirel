# Folirel brand assets

The SVG files in this directory are the source of truth for the Folirel logo and app icon. Everything the app and the
README draw is derived from them. The mark is two leaves; the larger one carries lines of text like a page (folio, from
Latin *folium*, "leaf" or "sheet"; leaf × page).

## Colours

These are fixed brand colours. They do not follow the user's colour set (Classic, Leaf, Sand, Violet, Dynamic) or the
light/dark setting; only the choice between the light and the dark logo variant does.

| Use | Value |
|---|---|
| Icon background | `#1A7548` |
| Logo (leaf, lettering) | `#146A3F` |
| Left leaf on the icon | `#E2EEE5` |
| Left leaf on the logo | `#6FAA86` |
| Leaf and left leaf on a dark surface | `#2E9A62` / `#8CC7A3` |
| Third text line | the line colour at 45 % opacity |

## Files

| File | Use |
|---|---|
| `folirel-icon.svg` | Master app icon (512 × 512, rounded plate included). |
| `folirel-logo.svg` | Horizontal logo, colour, for light surfaces. Used by the README and by Settings > About in a light theme. |
| `folirel-logo-dark.svg` | Horizontal logo for dark surfaces (white lettering). Used by the README and by Settings > About in a dark theme. |
| `folirel-logo-white.svg` | Reversed logo on a green plate, for use on photos or coloured backgrounds. |
| `folirel-logo-black.svg` | One-colour logo for print and stamps. |
| `folirel-wordmark.svg`, `folirel-wordmark-white.svg` | The lettering alone. The letters are drawn shapes (outlined), not a font. |
| `android/ic_launcher_background.svg`, `ic_launcher_foreground.svg`, `ic_launcher_monochrome.svg` | The three layers of the Android adaptive icon (108 × 108; the symbol sits inside the central 66-unit safe zone). |
| `play-store/ic_launcher-playstore.png` | 512 px store icon for a future Google Play listing. Not used by the app. |
| `preview.png` | Overview of the variants. |
| `source/gen.py`, `source/wordmark.py` | The generator the kit came with (needs `shapely` and `cairosvg`). Kept for reference; the committed SVGs, not its output, are authoritative. |
| `source/svg_to_vector.py` | Converts the two horizontal logo SVGs to the VectorDrawables the app draws. |

## How the app uses them

- **Adaptive icon.** `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_launcher_background.xml` and
  `mipmap-anydpi/ic_launcher.xml` / `ic_launcher_round.xml` in `app/src/main/res` are the Android resources of the kit,
  unchanged. The normal and the round icon are the same symbol; the manifest names both. The minimum Android version is 9
  (API 28), which always draws adaptive icons, so no density PNGs are shipped.
- **Themed icon (Android 13 and later).** The icon has a dedicated `<monochrome>` layer. It is white with alpha only: the
  system chooses the colour from the wallpaper, so no brand colour is baked into it. The foreground layer is never reused
  as the monochrome layer.
- **Settings > About.** `folirel_logo.xml` and `folirel_logo_dark.xml` in `app/src/main/res/drawable` are generated from
  the two logo SVGs with `python3 artwork/folirel/source/svg_to_vector.py` (run from the repository root). The app picks
  the variant from the surface colour it is drawing, so a user who chooses Dark in the app on a light system gets the dark
  variant. The picture is drawn as an image, never tinted.
- **Splash.** There is no custom splash screen; Android's own splash uses the launcher icon.

## Changing a brand asset

Edit the SVG first, then regenerate or replace what is derived from it: run `svg_to_vector.py` for the logo drawables, and
bring `android/*.svg` and the three Android resources in line by hand (the layers have no generator here). Do not edit the
generated VectorDrawables in place.

## Licence

The logo, icon and wordmark were drawn for Folirel by the scripts in `source/` (no third-party font or image is used) and are
released under the repository's [MIT License](../../LICENSE).
