# FareSniper (قناص الأجرة) 1.0.16 — verified engine facts

Source of truth: jadx output under `Desktop/apk-analysis/output/com.faresniper.faresniper_app/jadx/sources`.
Every fact below cites the decompiled file and line. The Gemini "reference code"
(`Desktop/تقرير-إصلاح-محرك-سباق/الكود-المرجعي-لمحرك-قناص-الأجرة.md`) is a
reconstruction; where it disagrees with jadx, jadx wins and the disagreement is listed in §Z.

Abbreviations: `A11y` = `com/faresniper/faresniper_app/engine/service/FareSniperA11yService.java`.

## 1. Accessibility service declaration
`res/xml/accessibility_service_config.xml`:
- event types: `typeWindowContentChanged|typeWindowStateChanged` only
- `packageNames="me.com.easytaxista"` (Jeeny only at OS level)
- `notificationTimeout="0"`, `flagReportViewIds`, `canRetrieveWindowContent`, `canPerformGestures`

## 2. Engine constants (A11y constructor, lines 114–156)
| constant | value |
|---|---|
| GLOBAL_CLICK_COOLDOWN | 100 ms |
| SAME_RIDE_COOLDOWN | 2500 ms (Gemini said 3000 — wrong) |
| MIN_TAP_HOLD_MS | 40 ms (Gemini said 20 — wrong) |
| POST_CLICK_SUPPRESS_MS | 4000 ms (Gemini said 1800 — wrong) |
| EVENT_THROTTLE_MS | 25 ms initial (adaptive) |
| SUBTREE_PARSE_BUDGET_MS | 15 ms (Gemini said 25 — wrong) |
| PROBE_BUDGET_MS | 10 ms |
| BOOKED_READ_IDLE_BUDGET_MS | 30 ms (Gemini said 35 — wrong) |
| MAX_BOOKED_READ_ABANDONS | 1 |
| FAST_PATH_MISS_LIMIT | 3 (Gemini said 2 — wrong) |
| POST_ACCEPT_TIMEOUT_MS | 6000 ms |
| LATE_CAPTURE_TOTAL_BUDGET_MS | 80 ms |
| CONFIG_TTL | 15000 ms |
| readerPool | fixed pool of 2 threads (`V8(2)`), not a single HandlerThread |
| readerScreenHeight default | 2400 |

## 3. Engine config (`defpackage/C3061xq.java`) — field names from its `toString()`
minPrice, maxPrice, minTime, maxTime, logicMode, destEnabled, destKeywords, snipingMode,
priceEnabled, timeEnabled, destCheckEnabled, clickX, clickY, clickDurationMs, roiPrice, roiTime,
roiDest, …, offerScreenKeywords, acceptedScreenKeywords, timeKeywords, snipingActive,
priceFilterEnabled, timeFilterEnabled, roiTimeAlt, snipingNoDest, smartDestActive, smartDestLat,
smartDestLng, smartDestAngle, driverLat, driverLng, stealthGesturesEnabled, smartDelayEnabled,
smartDelayMinMs, smartDelayMaxMs, …, turboModeEnabled, bypassTimeEnabled, bypassTimePrice,
destHomeMode, destExcludeEnabled, destExcludeKeywords, destExcludeRejectOnUnknown,
destIncludeRejectOnUnknown, canUseDestination, canUseDestinationFilter, canUseLogicOr,
destZonesEnabled, destZonesJson, destZonesRejectOnUnknown, destZonesBearingEnabled,
destZonesBearingMarginDeg, destZonesOnTheWayEnabled, travelAngleTolDeg, travelOvershootKm,
corridorHalfWidthKm, destZonesRouteApiBudgetMs, destExcludeZonesEnabled, destExcludeZonesJson,
destExcludeZonesRejectOnUnknown, destExcludeProximityMaxKm, destExcludeBearingMarginDeg,
globalDestExcludeZonesJson, globalDestExcludeOptOut, liveGeocodeOnMissMs, destExcludeConfirmMs,
boltDestExcludeConfirmMs, globalZoneProximityKm, acceptKeywordsExtra, timeKeywordsExtra,
currencyKeywordsExtra, noiseKeywordsExtra, paymentFilterMode, paymentRejectOnUnknown,
ratingFilterEnabled, minRating, ratingRejectOnUnknown, waitSaveFilterMode, targetApp.

Key point: **every content filter has an explicit `…RejectOnUnknown` switch.** Missing data
rejects only when the driver enabled that switch.

### 3.1 Fallback config when prefs cannot be read (A11y line 613)
minPrice 0, maxPrice 0, minTime 0, maxTime 0, logicMode "AND", destEnabled false,
priceEnabled false, timeEnabled false, destCheckEnabled false, clickDurationMs 5,
offerScreenKeywords `accept offer,accept,قبول الطلب,قبول العرض`,
acceptedScreenKeywords `end ride,navigate,إنهاء الرحلة,التنقل`,
timeKeywords `min,mins,minute,minutes,mn,away,دقائق,دقيقة,دقيقه,دقائ,دقي,يبعُد,يبعد,يبعدُ,بعد,بعيد,قريب,mîn,mín`,
priceFilterEnabled true, timeFilterEnabled true, snipingNoDest true, smartDestAngle 45,
stealthGestures false, smartDelay false (0..0), turbo false, bypassTime false,
destHomeMode "include", destExcludeEnabled false, destExcludeRejectOnUnknown false,
destIncludeRejectOnUnknown true, destZonesEnabled false, destZonesRejectOnUnknown true,
bearingEnabled true, bearingMargin 25, onTheWay false, travelAngleTol 20, overshoot 2,
corridor 1.5, excludeZonesEnabled false, excludeZonesRejectOnUnknown false, proximityMax 60,
excludeBearingMargin 30, destExcludeConfirmMs 1200, boltDestExcludeConfirmMs 3000,
paymentFilterMode "all", paymentRejectOnUnknown false, ratingFilterEnabled false,
ratingRejectOnUnknown false, waitSaveFilterMode "off", targetApp "jeeny".

### 3.2 `areAnyFiltersActive` (A11y line 254)
`(priceEnabled && priceFilterEnabled) || (timeEnabled && timeFilterEnabled) || destCheckEnabled
|| (smartDestActive && destEnabled && driverLat != 0 && smartDestLat != 0) || !snipingNoDest`.
With no filter enabled Qannas accepts every offer (no "NO_FILTERS" rejection exists).

## 4. Event handling (A11y `handleJeenyEvent`, line 723…)
- Handles only event types 2048 (content changed) and 32 (state changed).
- 2048 with no pending ride while a booked root read is in flight → return (coalesce, the
  in-flight read will see the latest tree).
- 2048, no pending ride, last root read was not an active ride, and NOT (turbo && clickX/Y saved
  && no filters) → `bookRootRead` (off-main read on reader pool) and return.
- Throttle: for 2048 within EVENT_THROTTLE_MS of the last processed event, probe the event
  source; drop only if the probe says NO_SIGNAL.
- Turbo path (turbo && saved coords && no filters && no pending): quick-scan source, tap saved
  coordinates immediately, then late capture.
- Noise filter (2048 only, A11y 851–872): source className containing scrollbar/edittext/
  progressbar/seekbar/switch/toast → drop; source with no text, no description and no
  children → drop. Otherwise probe; HAS_SIGNAL keeps the subtree source unless the config
  needs the full root (`C0404Pk.execute(config)`).
- Then `processOfferFrame(…, allowAsyncBookFallback = (2048 && no pending))`.

## 5. Package routing (A11y 1273–1306)
- Self package ignored.
- Bolt: `== ee.mtakso.driver` or contains `mtakso` (only if targetApp bolt/both).
- Petra: `== com.petraride_captain` or contains `petraride` (only if petra/both).
- Jeeny: equals the obfuscated Jeeny package (`me.com.easytaxista`) or contains
  jeeny/easytaxi/taxi/ride/driver (obfuscated KW_* strings), only if jeeny allowed.
- `onServiceConnected` (2229): eventTypes 2080 (2048|32), notificationTimeout 0,
  flags 81 = DEFAULT|REPORT_VIEW_IDS|RETRIEVE_INTERACTIVE_WINDOWS, packageNames = Jeeny only.
  Also warms the gesture path with a 1 ms stroke at (1,1) (`warmUpGesturePath`, 2140).

## 6. Probe / quick scan keywords (A11y 1239–1271, 1488–1517)
Depth ≤ 4, at most 8 children per node, lower-cased text-or-description contains any of:
`jod, sar, $, د.أ, ﷼, ريال, mins, "min ", دقيق, away, accept, reject, قبول, رفض, عرض`.
Probe has a 10 ms budget; on timeout it reports UNKNOWN (not NO_SIGNAL), so the frame is
still processed.

## 7. Booked root read (A11y 258–278, 1757–1889, 411–554)
- `bookRootRead`: one pending frame at a time; new booking sets all in-flight abort flags.
- Reader: `getRootInActiveWindow()` off main; Bolt → `Y2.execute`, Petra → `Et.execute`,
  Jeeny → learned-IDs fast path `C0404Pk.dispatch` then full `C0404Pk.manager` with idle
  budget 30 ms only for the first attempt (`abandons < 1`).
- Result posted to main → `finishBookedRead`: stale reads (start < newest decided) dropped;
  aborted/empty reads re-booked once; otherwise `processOfferFrame(..., booked result)`.

## 8. Click ladder `executeFallbackClicks` (A11y 325–405)
1. `node.performAction(ACTION_CLICK)`; 2. if false → `node.parent.performAction(ACTION_CLICK)`;
3. if still false → gesture at (x,y) **with no window-bounds check at all**:
   - stealth off: single point stroke, hold 40 ms;
   - stealth on: Gaussian jitter σx=max(4,0.12w), σy=max(4,0.15h) clamped to half size−2,
     hold N(10,3)∈[5,15] ms in turbo else N(120,40)∈[70,250] ms.
- Post-click: `processOfferFrame$lambda$84` runs the click on the main thread (or a root
  injector when root is available), records click latency, then runs the late-capture runnable.

## 9. Learned view IDs (A11y 963–986)
Learn (acceptBtnId, priceId, timeId) when all three are non-blank; reset after 3 consecutive
parses with no accept node.

## 10. Post-click bookkeeping
- `schedulePendingResolution`: watchdog at POST_ACCEPT_TIMEOUT_MS + 250 = 6250 ms →
  `offer_disappeared_after_click` if still pending.
- `isPostClickSuppressed`: same identity hash (price,time,destination) within 4000 ms.
- Late capture: 60 ms after a turbo tap, re-parse with an 80 ms budget to fill price/time/dest.
- Accept sound: ToneGenerator(STREAM_ALARM,100) tone 86 for 800 ms, alarm volume raised then
  restored. One ToneGenerator instance reused (`toneGen`).

## 11. processOfferFrame — read from smali (jadx failed; 3339 units)
Smali extracted with apktool to `apk-analysis/output/smali_dump`; method body 2591 lines
(scratch copy `pof.smali`). Verified flow:
1. Parse source: booked result → use it; else subtree (Bolt `Y2`, Petra `Et`, Jeeny learned
   IDs `Pk.dispatch` then `Pk.manager` with 15 ms hard budget, flags 0x68). Subtree result is
   discarded when aborted, or when it has no accept node and is not an active ride.
2. No result and not booked: if `allowAsyncBookFallback` → `bookRootRead` and return;
   otherwise synchronous `getRootInActiveWindow()` parse (flags 0x78, no budget).
3. Learn view IDs (Jeeny only). Adaptive throttle = clamp(1.5 × mean(last 5 parse ms), 25, 60).
4. Active ride and nothing pending → reset ride hashes, mark handled-accepted, return.
5. `isSnipingAllowedNow()` false → return (licence/engine gate).
6. Pending ride: screen offer hash = h(price,time,destination,Ok.callback,Ok.listener).
   Still same offer (hash equal, or turbo and <350 ms since click) and ≤6000 ms → skip
   verification. Otherwise outcome: Bolt/Petra → Confirmed iff parsed active ride; Jeeny root
   → `pu.connect(root, screenHeight)` (verifier). Confirmed → accept tone, clear pending,
   RPC `post_click_confirmed`, return. StillOffer with a different identity → RPC
   `post_click_timeout/new_offer_appeared` and continue to decide the new offer.
   Inconclusive after 6000 ms → same offer still shown → `post_click_timeout`
   `confirmation_timeout_<ms>`; else `offer_disappeared_after_click`, return.
7. **No accept node and no turbo coordinates → no decision** (reset ride hash after 1 s idle).
   Qannas never taps without either a parsed accept node or driver-saved coordinates.
8. New hash → markOfferSeen; same hash after SAME_RIDE_COOLDOWN → refresh detection time.
9. Skip when post-click suppressed (same identity, 4000 ms), when the same hash was
   accepted < 2500 ms ago, or < 100 ms since the last click.
10. Decision: `b9.process(service, Pk.factory(parsed), config, isBolt)`. Reject → RPC
    `rejected_by_engine` once per hash with the decision reason and return.
11. Accept: lastClickTime, handled hash, clickedOfferIdentity; smart delay = Gaussian
    mean (min+max)/2, σ (max−min)/4, resampled until inside [min,max], only when
    smartDelayEnabled and max > 0. Pending ride created, watchdog 6250 ms, suppression 4000 ms.
12. Tap target: turbo coords (200×120 box) or the accept node copy's `getBoundsInScreen`
    centre and size. **No window/edge/visibility check.** Click via `executeFallbackClicks`
    (or root injector) on the main thread; delay > 0 → coroutine delay then click.
13. Late capture (destination missing, Jeeny only) runs after the click (or scheduled at
    once when the smart delay ≥ 450 ms).

## 12. Jeeny parser lexicon (`C0404Pk` static init + `seal.java` cases 9–29, `C0326Mk`)
Strings obfuscated with XOR key `[90,60,127,33,77,110,137,18]` (`AbstractC1905m40.controller`);
decoded with `/tmp/deob.py` (a few Arabic words decode oddly — marked `?`).
All keyword regexes = `Pattern.quote(word)` joined by `|`, compiled with flags 66
(CASE_INSENSITIVE|UNICODE_CASE) and matched with `find()` (substring, no word boundaries).

| field | list |
|---|---|
| adapter → accept regex (`GL.encoder`) | accept, موافق, مواعد?, offer, عرض, confirm, تأكيد, take, استلام, قبول  (+ `acceptKeywordsExtra`) |
| decoder → "rule" exclusion | rule, قواعد, شرط, معدل القبول |
| encoder → reject regex | reject, رفض |
| callback+listener → currency regex (`GL.parser`) | sar, ﷼, ₡, net, earn, ⃁, مستلام?, ريال, ر.س, صافي, jod, دوج, دينار (+ `currencyKeywordsExtra`) |
| controller → noise regex (`GL.resolver`) | economy, إيكونومي, standard, ستاندرد, lite, لايت, accept, موافق, reject, رفض, jod, د.أ, $, mins, min, دقيقة, دقائق, يبعد, يبعُد, قبول, عرض, الطلب, العرض, km, كم, meter, متر, "m ", "mi ", cash, نقدي, card, بطاقة, wallet, محفظة, promo, خصم, discount, coupon (+ `noiseKeywordsExtra`) |
| dispatch → online indicators | online, متصل?, you're online, أنت متصل |
| observer → unit words | km, m, م, متر, meter, min, دقيقة, دقائق |
| factory → active-ride indicators | arrived at pickup, cancel ride, end ride, navigate, picking, pickup location, in progress, وصلت إلى نقطة الانطلاق, وصلت الى نقطة الانطلاق, وصلت إلى نقطة الإقلاع, نقطة الانطلاق, إنهاء المشوار, انهاء المشوار, إلغاء المشوار, الغاء المشوار, عرض الاتجاهات, عرض الإتجاهات, التوصيل, في التوصيل, جارٍ التوصيل |
| builder → network-error regex | check your network/internet/connection, no internet, …, تحقق من الشبكة, لا يوجد اتصال, انقطع الاتصال, جاري إعادة الاتصال … |
| generator → card | card, credit, visa, mada, mastercard, بطاقة, ائتمان, فيزا, wallet, محفظة |
| combiner → cash | cash, نقد, كاش, money |

Fixed regexes (Pk lines 40–68):
- price `process`: `(?:\$|JOD|SAR|د\.أ|د\.ا|د\.|﷼|ر\.س|⃁)\s*(\d+(?:[\.٫]\d+)?)|(\d+(?:[\.٫]\d+)?)\s*(?:…same…)`
  — **no comma decimal**; flags 0 (case-sensitive).
- time `execute`: `(?<!\d)(\d+)\s*(?:mins|min|دقائق|دقيقة|دقيقه|دقائ|mn|away|بعيد|قريب)`;
  the config builds `(?<!\d)(\d+)\s*(?:mins|min|دقائق|دقيقة|دقيقه|دقائ|mn|away|بعيد|قريب|<timeKeywordsExtra>)` with flags 66.
- distance `provider`: `^\d+([.,٫]\d+)?\s*(km|m|mi|كم|متر|م\s|كيلو)`.
- numeric-only `manager`: `^[\d.,٫\s★☆⭐%٪]+$`.
- `transformer` (currency words stripped before counting letters): `(?i)ريال|ر\.س|دينار|sar|jod|sr`.
- Digits normalised with `observer()`: Arabic-Indic ٠–٩ → 0–9.

### 12.1 Address candidate rule `Pk.callback(gl, raw, lower, normalized)` (Pk 199–220)
Accept as address when: raw non-blank, length > 2, not noise, not online indicator, not a
leading distance (`provider`), not a time, not starting with يبعُد/يبعد, not numeric-only,
not a network-error text, and **≥ 3 letters** after removing currency words.

### 12.2 Fast path `Pk.dispatch` (learned IDs) (Pk 324–493)
Skipped when `Pk.execute(config)` (full tree needed: destination/zone/exclude filters).
Accept node = first node with the learned accept ID whose text matches the accept regex,
is enabled and has positive bounds (**no clickable requirement**). Then time from the time
ID (time regex) and price from the price ID (price regex). All three required.

### 12.3 `Pk.listener(hasOffer, hasPrice, hasTime, hasDest, config)` (Pk 555–565)
If an offer is seen: price required; then time required unless (destination present and
`snipingNoDest`… note: reads `reducer`).

### 12.4 `Pk.factory(Ok) → Nx` (decision input): price, time, destination, and Ok fields
handler, provider, manager, controller, builder, factory, adapter.

## 13. Full-tree Jeeny parser `Pk.provider` — read from smali (jadx failed; 2478 units)
Scratch copy `pk_provider.smali` (1587 lines). Verified behaviour:
- Node cap by screen height: ≤1600 → 300, ≤2400 → 400, else 600. BFS (`V0` deque); children
  enqueued only while the queue is below the cap.
- Hard deadline checked every 8 nodes; idle deadline/abort only while nothing offer-like has
  been found yet (no price, fallback price, time, accept, reject, active-ride signal).
- Text = text, or content description when text blank; trimmed; Arabic-Indic digits → Latin.
  Lower-case copy used for keyword tests.
- Description-only node containing "destination" → next address is drop-off; "pick up"/
  "pickup" → next address is pickup.
- Payment: card/cash regexes (only when paymentFilterMode ≠ "all").
- Map element (class contains TextureView, or text has "google map"/"map marker"): record
  marker centre (1..400 × 1..600 px) when zones need it, enqueue children, skip text logic.
- `disabled = !isEnabled()`.
- Online indicator regex hit → flag `online`; active-ride regex hit → flag `activeRide`.
- **Time** (first only, enabled nodes): lower text contains min/دق/mn/away/بعيد/قريب (or a
  custom time keyword exists) → time regex group 1 → minutes; records the node view ID.
- **Price** (first only, enabled, not time node):
  1. currency regex (`GL.parser`) or "$" present → price regex; node top must be
     > 0.30 × screenHeight → price (+ view ID). If the regex fails: strip non-digits
     (`,`/`٫` → `.`); value 0.1..800, text has currency, not a unit word, not a time → price.
  2. no currency: text < 10 chars with digits, value 0.5..500, not a unit word, not a leading
     distance, not a time, ≠ parsed minutes, top > 0.30 × H → fallback price; keep the one
     with the most decimal digits.
- **Accept** = accept regex hit and not a "rule" word (rule/قواعد/شرط/معدل القبول).
  **Reject** = reject regex hit (first reject node kept).
- **Address**: when not an accept/reject/time node and `Pk.callback` (§12.1) passes and the
  driver needs addresses (or an offer exists without price) → add to ordered set; assign to
  pickup/drop-off when a label preceded it.
- **Accept node selection** (not reject, not time node): node clickable, or class contains
  "Button", or accept text. For accept text: if the node is not clickable climb ≤ 3 parents
  for a clickable ancestor; **if none, the text node itself is used**. Tap centre = the text
  node's own bounds. Later accept matches replace earlier ones. **No clickable requirement.**
- Early exit after each node: price && time && accept && (≥ 2 addresses or addresses not
  needed). Also stop when an active ride is seen without accept but with a price, or when an
  online indicator is seen.
- After the loop:
  - aborted → `Ok(aborted=true)`; online indicator seen → **null** (home screen, no offer).
  - No accept node but a reject node and price/time/addresses → geometric search ≤ 200 nodes
    for a clickable+enabled node on the reject row (|Δcy| < reject height) to its right
    (left ≥ reject.right − 0.5·reject.width), width > 50, height > 30.
  - Active ride and no accept → `Ok(activeRide=true)`.
  - Destination = labelled drop-off, else last address when ≥ 2, else the single address
    (flagged ambiguous). Pickup = first address when ≥ 2 and different from destination.
  - Payment card/cash/unknown.
  - **Result requires: minutes != null AND accept node != null AND (price != null OR
    `Pk.listener` allows a missing price — only when the price filter is off).**

## 14. Decision engine `C0798b9.process(service, Nx, config, isBolt)` (b9 lines 198–3676)
Result `C0896c9(shouldAccept, matchedPrice, matchedTime, matchedDest, rejectionReason, diagnostic, zoneEvidence)`.
- price and time both null → reject `no_ride_data_detected`.
- **Fast path** (no destination/include filter, no exclude keywords, no zones, payment "all",
  no rating/wait-save filter): (b9 312–378)
  - price check only if `priceEnabled && priceFilterEnabled`: in `[minPrice,maxPrice]`
    where 0 = unbounded (`dispatch`, b9 15). Missing price → passes only for the
    no-destination case flag.
  - time check only if `timeEnabled && timeFilterEnabled`, skipped when bypass-by-price
    (`bypassTimeEnabled && canUseLogicOr && price ≥ bypassTimePrice`): minutes in
    `[minTime,maxTime]`, 0 = unbounded (`handler`, b9 60).
  - combine enabled checks with logicMode AND (all) or OR (any). **No enabled check → accept.**
  - failure reason `failed_<names joined by _>` e.g. `failed_price`, `failed_time`, `failed_price_time`.
- **Full path** adds, in order: no-destination guard (`no_dest_sniping_disabled` only when
  the driver turned off "accept without destination" or needs the destination); payment
  filter (`payment_not_cash`/`payment_not_card`; unknown payment rejects only when
  `paymentRejectOnUnknown`); rating (`rating_below_min`; unknown only with
  `ratingRejectOnUnknown`); wait&save exclude; destination exclude keywords (`dest_excluded`;
  no destination → reject only with `destExcludeRejectOnUnknown`, else fail-open
  `exclude_skipped_no_dest`); include keywords as the "destination" check inside AND/OR;
  geographic zones (include/exclude/global, unknown handled by the matching RejectOnUnknown,
  default fail-open for exclude).
- Reason keys seen: no_ride_data_detected, no_dest_sniping_disabled, payment_unknown_reject,
  payment_not_cash, payment_not_card, rating_unknown_reject, rating_below_min,
  wait_save_filtered, dest_excluded, exclude_no_dest_safe_reject, dest_excluded_zone,
  dest_global_excluded_zone, dest_zone_unknown_reject, dest_exclude_zone_unknown_reject,
  failed_*, conservative_ocr_reject.
- **Qannas has no pickup-distance, trip-distance, fare-per-km or trip-time filters.** Its
  content filters are: price range, pickup-time range (minutes), destination include/exclude
  keywords, zones, payment, passenger rating, wait&save. Missing data never rejects unless
  the driver enabled the matching reject-on-unknown switch (or the include-destination mode).

## 15. Post-accept verifier `AbstractC2279pu.connect(root, screenHeight)` (Jeeny only)
- Collect screen texts; if any contains `accept offer|accept the offer|قبول الطلب|قبول العرض`
  → **StillOffer**.
- Else if any contains an active-ride indicator (arrived at pickup, arrived at pickup
  location, cancel ride, end ride, navigate, picking, pickup location, in progress,
  وصلت إلى/الى نقطة الانطلاق, وصلت إلى نقطة الإقلاع, نقطة الانطلاق, إنهاء/انهاء المشوار,
  إلغاء/الغاء المشوار, عرض الاتجاهات/الإتجاهات, التوصيل, في التوصيل, جارٍ التوصيل) →
  **Confirmed**(passengerName, pickupAddress) extracted heuristically.
- Else **Inconclusive**. Bolt/Petra: Confirmed iff the parser saw an active ride.
- Accept tone only on Confirmed (or on watchdog resolution when requested).

## Z. Gemini "reference code" vs jadx — disagreements found
| Gemini claim | Real (jadx/smali) |
|---|---|
| SAME_RIDE_COOLDOWN 3000 | 2500 |
| POST_CLICK_SUPPRESS 1800 | 4000 |
| MIN_TAP_HOLD 20 | 40 |
| SUBTREE budget 25 | 15 |
| BOOKED idle budget 35 | 30 |
| FAST_PATH_MISS_LIMIT 2 | 3 |
| single "fs-reader" thread | pool of 2 reader threads |
| DecisionEngine filters: rating, wait&save, payment, price, time, keywords, zones (early-return per filter) | AND/OR combination of price/time/destination-include; exclusions return early; every unknown has its own reject-on-unknown switch |
| parseTree accept: `acceptRegex && !OFFER_HEADER` | accept regex (accept/موافق/offer/عرض/confirm/تأكيد/take/استلام/قبول) minus rule words; reject regex separate |
| time keywords incl. "دق","بعيد","قريب" for time | same, plus time regex fixed list |
| price must be in lower 70 % (top > 0.30 H) | confirmed |
| parser max nodes 300/400/600 | confirmed |
| geometric reject-row fallback | confirmed (conditions slightly differ: also needs addresses) |
| result requires time + price + accept | time + accept required; price optional when the price filter is off |
| `online` indicator not mentioned | online indicator seen → no offer (null) |

## 16. Real settings keys and defaults (`AbstractC2380qv.validator`, line 1991)
Flutter prefs (`flutter.` prefix). Defaults when the driver never set them:
logic_mode AND; dest_enabled false; price_enabled **true**; time_enabled **true**;
price_filter_enabled true; time_filter_enabled true; min/max price and min/max time
numeric (unset = 0 = unbounded); sniping_no_dest true; dest_check_enabled false;
turbo false; stealth_gestures false; smart_delay false; bypass_time false;
dest_home_mode include; dest_exclude_enabled false; dest_exclude_reject_on_unknown false;
dest_include_reject_on_unknown true; dest_zones_enabled false; dest_zones_reject_on_unknown
true; dest_exclude_zones_reject_on_unknown false; payment_filter_mode "" → all;
payment_reject_on_unknown/rating_filter_enabled/rating_reject_on_unknown follow a plan flag;
wait_save_filter_mode off; target_app jeeny; offer/accepted/time keyword strings as §3.1.
With min/max all 0 the enabled price/time checks are unbounded → every offer passes.

## 17. Geographic stage (smali b9 3340–7000, Zh, Zi, Xr0, ja, item, I70, P20) — ported to core/engine/zones
- Inputs: destination text (none when ambiguous), driver position (`driver_lat/lng`), map markers (text
  "map marker", 1–400 × 1–600 px, collected only when bearing is on and a zone list is in use).
- Global exclude zones are kept within `global_zone_proximity_km` of the driver (0 = all) unless opted out.
  The driver's exclude zones count only with a known driver position.
- Point for the destination: address cache (exact key, key without number-only words, fuzzy words; candidates in
  the driver's city circle (14 Saudi/Jordan circles) or within `dest_exclude_proximity_max_km`; nearest wins;
  precision 120 / 1500 / 500 / 2500 m), overridden by a Plus Code (precision ≥ 15 m, ≤ 250 m) or a national short
  address (cache hit 40 m, else queued: `natl_code_pending`). Unplaced addresses are queued (150 max).
- Smart destination: bearing driver→destination within `smart_dest_angle` (45) of driver→point, else `wrong_direction`.
- No destination: include zones + reject-on-unknown → `dest_zone_unknown_reject`; exclude reject-on-unknown and an
  exclude zone within 15 km → `dest_exclude_zone_unknown_reject`; else fail open.
- Include zones: inside passes; else "on the way" (straight line crosses a zone, nearest zone point ≤ overshoot 2 km
  in the same direction ±20°, or a zone centroid within 1.5 km of the line) passes; else the map-marker direction
  (largest 15 % cluster → farthest marker, compared with each zone's bearing arc ± 25°) must be INSIDE, else
  `outside_dest_zone`. Unplaced destination: direction INSIDE passes, OUTSIDE → `outside_dest_zone_bearing`,
  inconclusive → reject only with reject-on-unknown. There is no route API (`Zi.resolver` returns null).
- Exclude zones (own, then shared): 1 = inside, 0 = outside, -1 = undecided (no point, fuzzy text, closer to the edge
  than its precision, or a > 200 m point inside). Inside → `dest_excluded_zone` / `dest_global_excluded_zone`;
  verdicts cached 10 min per destination. Undecided → reject only with reject-on-unknown and a zone within 15 km.
- The zone stage runs after the price/time/destination AND/OR result and before its `failed_*` rejection.
