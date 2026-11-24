---
name: FoodFinder – Marktstand-Kreidetafel
platform: android
seed: 37669234e5df6fdd
---

# Design

The deal list is a market stall's price board: the saving is written biggest, everything else is small print. Material 3 governs structure, navigation and controls; the world contributes only palette, the chalk money face, density and one signature move.

## Color (Material roles)

| Role | Board (dark) | Daylight (light) | Job |
|---|---|---|---|
| background / surface | `#18211E` slate | `#F1ECE2` chalk-dust paper | ground |
| onSurface | `#ECEDE6` chalk white | `#1B2420` | text |
| onSurfaceVariant | `#B4BEB6` | `#48524C` | meta lines |
| primary | `#F2D46B` chalk yellow | `#6B5800` ochre | savings figure, the one filled action ("Bei … öffnen"), selection |
| secondary | `#8FD3B4` chalk mint | `#256A50` | service · cuisine line, location, "aktiv" states |
| tertiary | `#F2A3A3` chalk rose | `#9B3B3B` | struck-through regular price |
| warn accent | `#F5C27A` | `#8A5300` | source unavailable |
| outlineVariant | `#3B4842` | `#C9C3B6` | hairline row dividers |

Light/dark follows the system setting. No drop shadows; tonal surfaces only.

## Type

- Roboto (system) for everything through the Material type scale, `sp` units.
- **Cabin Sketch Bold** (OFL, bundled at `res/font/cabin_sketch_bold.ttf`, from google/fonts) only for money: list savings, detail headline, discounted product prices, headline roles.
- Savings size steps on a fixed ramp by euros saved: <2 € 21sp · ≥2 € 23sp · ≥5 € 26sp · ≥10 € 30sp. Detail headline 52sp.
- All prices use tabular figures (`fontFeatureSettings = "tnum"`).

## Signature move

`Modifier.chalkStrike()`: the regular price is crossed by a 2dp round-capped stroke drawn slightly uphill, in the tertiary rose, the way a stall owner strikes an old price on a slate.

## Layout

- Bottom NavigationBar: Liefern · Abholen · Orte · Konten.
- Deal row: left column name (titleMedium), service · cuisine (secondary), meta, up to three deal lines (applied deals in primary); right column savings in chalk, "gespart"/"ca. gespart", "für N", total, struck regular total. Rows separated by inset hairlines, no cards.
- Detail: chalk saving headline, the bill for N persons (labels left, tabular amounts right, deductions in primary), assumptions as small print, deals with origin icons (tag / verified / mail / bell), reduced products, cheapest mains, sticky bottom action.
- Touch targets ≥ 48dp; edge-to-edge with system insets; predictive back enabled.

## Icons

Material Symbols (outlined for idle nav, filled for selected). Launcher: authored vector, chalk-yellow price tag with a struck stroke on slate (`res/drawable/ic_launcher_fg.xml`, hand-written for this project).
