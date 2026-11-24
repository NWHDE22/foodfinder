---
version: 1
slug: "app"
primary_target: "app"
related_targets: []
---

Scope: whole FoodFinder Android app (Liefern, Abholen, Detail, Orte, Einstellungen). Mode: Operate. Build path: code-led (native app).

Audience/job: owner on their phone, hungry now, wants the order that saves the most here and now, often for two.

## Direction contract

THESIS: The deal list is a market stall's price board: the saving is written biggest, everything else is small print. Refuses the category default of photo-first restaurant cards with an orange accent.

OWN-WORLD: Slate-board ground (deep green-black), chalk-white text, chalk-yellow for savings, chalk-rose for struck old prices, chalk-mint for "aktiv/verfügbar". Cabin Sketch (chalk display) only for money figures; Roboto for everything else. Hairline chalk dividers, no drop shadows, tonal surfaces.

STORY: Opens on current place, sees ranked savings for "jetzt", narrows by cuisine/time/min order, opens a stall, sees discounted items and the 2-person bill, taps "In App bestellen".

FIRST VIEWPORT: Top app bar with location chip; search field with suggestion chips (time-of-day + cuisines); filter chip row; ranked LazyColumn: each row = service tag + name + meta (ETA, MBW, Gebühr) left, chalk saving figure right, struck "statt" total and "für 2: ca. xx €" below. Bottom NavigationBar: Liefern, Abholen, Orte, Einstellungen.

FORM: candidate 6 of 7 (Wochenmarkt-Kreidetafel). seed:37669234e5df6fdd. Raises: magnitude ramp from star atlas (saving figure size steps on a fixed ramp by € saved); tabular figures from datamatics (all prices tabular-aligned); action-only accent from warm consumer canon (chalk-yellow filled only on actionable primary).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
