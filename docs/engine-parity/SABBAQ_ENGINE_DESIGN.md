# Sabbaq engine rebuild — FareSniper parity design

Owner decision (2026-10-28): Sabbaq matches FareSniper 1.0.16 in everything, rebuild allowed.
Every rule below cites `QANNAS_ENGINE_FACTS.md` (§n). Behaviour is reimplemented in Sabbaq's
own Kotlin; decompiled code is not pasted. Excluded on purpose: stealth (anti-detection)
gestures and root tap injection — both are off by default in FareSniper, whose default path
is a plain 40 ms tap.

## 1. Why the current engine fails (evidence: FIELD_TELEMETRY_2026-10-28.md)
| current Sabbaq | FareSniper | field effect |
|---|---|---|
| tree copied to `ScreenNode`, live node re-found by path + exact label before tapping | keeps the live accept node (`obtain`) and taps it | countdown labels/re-layout → no live node |
| gesture refused when `root.window` null or bounds outside "safe" area | gesture at node centre, no window check (§8, §11.12) | 6/6 matched offers `unsafe_bounds` |
| accept control must be `clickable` (or inside a clickable container) | accept text node accepted, climbs ≤3 parents for a clickable one, else the text node itself (§13) | custom buttons invisible |
| content events inside 100 ms dropped | never dropped: coalesced into one booked off-main read (§4, §7) | 89 `throttled` |
| classifier score gate before parsing (`SIGNAL_MISS`) | cheap keyword probe only; parser decides (§6) | 89 `cls_none` |
| missing trip km / pickup km / destination / no filters → reject | missing data never rejects unless the driver enables reject-on-unknown (§14) | 5 `*_missing` |
| filters: fare, per-km, pickup km/min, trip km/min, schedule, override, keywords, ANY/ALL | price range, pickup-time range, dest include/exclude, bypass time by price, AND/OR (§14, §16) | — |

## 2. Architecture (new code, old engine removed)
```
core/  app.sabbaq.core.engine   (pure JVM, unit-tested)
  UiNode            – interface over a node: text, description, className, viewId,
                      clickable, enabled, bounds, children, parent. Implemented by
                      the live AccessibilityNodeInfo (app) and by FakeNode (tests).
  JeenyLexicon      – §12 word lists + regexes, flags CASE_INSENSITIVE|UNICODE_CASE,
                      plus driver "extra" keywords.
  JeenyScreenReader – §13 full BFS parser (node cap 300/400/600, price/time/address/
                      accept/reject rules, geometric fallback, online/active-ride),
                      §12.2 learned-ID fast path; returns ParsedRide with the live
                      accept node.
  BoltScreenReader / PetraScreenReader – same contract (FareSniper Y2 / Et; to be read
                      from smali before porting, Jeeny first).
  EngineConfig      – §3/§16 fields actually used by the decision: minPrice, maxPrice,
                      minTime, maxTime, priceEnabled, timeEnabled, logicMode AND/OR,
                      destHomeMode include/exclude, dest keywords, exclude keywords,
                      destIncludeRejectOnUnknown (true), destExcludeRejectOnUnknown
                      (false), snipingNoDest (true), bypassTimeEnabled, bypassTimePrice,
                      smartDelay min/max/enabled, acceptKeywordsExtra, timeKeywordsExtra,
                      currencyKeywordsExtra, noiseKeywordsExtra, targetApp.
  DecisionEngine    – §14 (fast path + destination include/exclude), reason keys as in
                      FareSniper (`failed_price`, `failed_time`, `dest_excluded`, …).
  PostAcceptVerifier– §15 (StillOffer / Confirmed / Inconclusive).
  OfferFlow         – §11 state machine with an injected clock: ride hash, identity hash,
                      SAME_RIDE 2500, POST_CLICK_SUPPRESS 4000, GLOBAL_CLICK 100,
                      POST_ACCEPT_TIMEOUT 6000 (+250 watchdog), adaptive throttle 25..60,
                      learned IDs (miss limit 3), smart delay sampling. Returns actions
                      (TAP node/point, REPORT reject/confirm/timeout); never touches Android.

app/  app.sabbaq.service.SabbaqA11yService (rewritten, thin)
  - event types 2048|32, notificationTimeout 0, flags DEFAULT|REPORT_VIEW_IDS|
    RETRIEVE_INTERACTIVE_WINDOWS, packages from profiles (§1, §5)
  - routing by package keywords (§5); gesture warm-up on connect
  - booked root read on a 2-thread reader pool at priority −4 with abort flags (§7)
  - subtree probe path with 15 ms budget; noise-widget filter (§4)
  - click ladder node → parent → 40 ms gesture at node centre (§8), on the main thread
  - accept tone (ToneGenerator reused) only on Confirmed (§10, §15)
  - telemetry stages mapped onto the existing upload (OFFER_DETECTED, DECISION_*,
    CLICK_RESULT semantic/parent/gesture, CONFIRMATION_*), stats + voice + history hooks

## 3. Settings & UI
- `Filters` replaced by the §16 field set with FareSniper defaults (price/time enabled,
  bounds 0 = unbounded → every offer passes until the driver sets a limit).
- Filters page rebuilt: price min/max, time-to-customer min/max, AND/OR, destination mode
  include/exclude + keywords + reject-on-unknown, bypass time by high price, smart delay.
- Migration: old minFare/maxFare → minPrice/maxPrice; old min/maxPickupMinutes →
  minTime/maxTime; old excludeKeywords → exclude keywords; removed fields dropped.
- No "no filters" block: starting with nothing set accepts every offer (FareSniper).
- Presets rewritten on the new fields; schedule removed.

## 4. Tests (must fail on the old engine)
- JeenyScreenReader on fixture trees: non-clickable accept text, split currency node,
  countdown label, no destination, short Arabic district names, online banner, reject-row
  fallback, active ride, node caps, view-ID fast path.
- DecisionEngine truth table vs §14 (AND/OR, 0 = unbounded, bypass, include/exclude,
  reject-on-unknown).
- OfferFlow timing (cooldowns, suppression, watchdog, new offer during pending).
- Instrumented: a fake ride-app activity in the test APK + the real service driven end
  to end (detect → decide → tap → confirm) on the CI emulator — replaces the misleading
  live-accept self test.
- Field proof after install: telemetry shows CLICK_RESULT + CONFIRMATION_CONFIRMED.
