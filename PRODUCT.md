# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Kotlin + Jetpack Compose (Material 3), standalone APK (sideloaded, no Play Store, no Google Play Services dependency).

## Users

People in Germany/Austria using it on their own Android phone, mostly in the moment of "I'm hungry, what's the cheapest good option right now, here?". Often eating for two. Moves between places (home, work, travel), so location matters.

## Product Purpose

Find the best food deal right now at the current (or a saved) location across services, ranked by real money saved:

- **Liefern:** Lieferando, Uber Eats, Wolt.
- **Abholen / vor Ort:** Lieferando (Abholung), Wolt (Abholung), NeoTaste, Too Good To Go.

Success: within seconds of opening the app, the top of the list is the order that saves the most for what the user wants to eat, with a believable estimate of what it costs for two people.

## Positioning

Cross-app, savings-first ranking that folds in things no single app shows together: the user's own memberships (Lieferando Plus, Wolt+), vouchers from the user's e-mails, and deal notifications the delivery apps pushed to the phone.

## Operating Context

- One-handed phone use, often on the move; quick decisions.
- Ordering itself happens in the original app; FoodFinder deep-links there.
- Data comes from the services' public web endpoints (no official APIs). Too Good To Go needs an e-mail login. Sources can break; the app must say plainly when a source is unavailable.
- E-mail access via IMAP (app password) plus Android notification listener for the delivery apps' push messages.
- Background checks with notifications for new deals.

## Capabilities and Constraints

- Location: current GPS position or saved places.
- Filters: cuisine, delivery time, minimum order, delivery fee, rating, service.
- Ranking by estimated savings; item-level discounts listed per restaurant; estimate for N persons (default 2).
- Memberships: Lieferando Plus (also via Amazon Prime) and Wolt+ can be switched on; status is re-checked from e-mails/notifications and expiry reminders are shown.
- Language: German UI.

## Evidence on Hand

No brand assets, logos or screenshots. Third-party service names are used only to label sources; their logos/brand art must not be reproduced.

## Product Principles

1. Money saved is the headline number; everything else supports that decision.
2. Honest estimates: label assumptions ("ca.", "geschätzt") and show the calculation.
3. Fast to "now": one tap from opening to a ranked answer for the current place.
4. Degrade visibly: a broken source is shown as broken, never silently dropped.
