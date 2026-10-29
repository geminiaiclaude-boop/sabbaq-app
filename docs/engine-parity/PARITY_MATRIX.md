# FareSniper ↔ Sabbaq — full order cycle, step by step

Each row is one behaviour of FareSniper's cycle (QANNAS_ENGINE_FACTS.md §). Status:
**✓** identical · **◐** partly · **✗** missing/different · **n/a** deliberately excluded.
"Installed" = Sabbaq 2.0.2 on drivers' phones. "Branch" = `fix/next-batch` now.

| # | Stage | FareSniper behaviour (§) | Installed | Branch |
|---|---|---|---|---|
| 1 | setup | event types 2048\|32, notificationTimeout 0, REPORT_VIEW_IDS + INTERACTIVE_WINDOWS, Jeeny package only (§1,§5) | ◐ | ✓ |
| 2 | setup | gesture warm-up stroke at connect (§5) | ✓ | ✓ EngineHost.warmUpGesture |
| 3 | intake | Jeeny routing by package keywords (§5) | ◐ | ✓ isJeeny / isRidePackage |
| 4 | intake | 2048 while a booked read is in flight → coalesce (§4) | ✗ | ✓ EngineHost.onEvent |
| 5 | intake | booked root read off main, 2-thread pool, priority −4, abort flags (§7) | ✗ | ✓ bookRootRead/runBookedRead |
| 6 | intake | adaptive throttle 25–60 ms; probe; drop only on NO_SIGNAL (§4,§11.3) | ✗ | ✓ OfferFlow.eventThrottleMs + probe |
| 7 | intake | noise-widget source filter (§4) | ✗ | ✓ NOISE_WIDGETS |
| 8 | intake | subtree read with 15 ms budget, root fallback (§11.1–2) | ✗ | ✓ processFrame |
| 9 | read | keyword probe depth ≤4, ≤8 children, 10 ms (§6) | ✗ | ✓ probeOfferSignal |
| 10 | read | learned view-id shortcut, reset after 3 misses (§9,§12.2) | ✗ | ✓ |
| 11 | read | BFS node cap 300/400/600, hard/idle budgets (§13) | ✗ | ✓ |
| 12 | read | price rules (§13) | ✗ | ✓ |
| 13 | read | time rules (§13) | ✗ | ✓ |
| 14 | read | accept: words minus rule words, no clickable needed, climb ≤3 (§13) | ✗ | ✓ |
| 15 | read | reject node + accept beside reject (§13) | ✗ | ✓ |
| 16 | read | addresses, labels, destination/pickup (§12.1,§13) | ✗ | ✓ |
| 17 | read | online → no offer; active ride (§13) | ✗ | ✓ |
| 18 | read | offer = accept + minutes, price optional when price filter off (§13) | ✗ | ✓ |
| 19 | read | Bolt reader `Y2` (only when targetApp bolt/both; default jeeny) | ✗ | ✗ not ported |
| 20 | read | Petra reader `Et` (only when targetApp petra/both) | ✗ | ✗ not ported |
| 21 | decide | no price and no time → reject (§14) | ✗ | ✓ |
| 22 | decide | price range, 0 = unbounded (§14) | ✗ | ✓ |
| 23 | decide | time range, bypass by price (§14) | ✗ | ✓ |
| 24 | decide | AND / OR (§14) | ◐ | ✓ |
| 25 | decide | destination include / exclude + reject-on-unknown (§14) | ✗ | ✓ |
| 26 | decide | no rejection for missing data / no filters / no destination (§14) | ✗ | ✓ |
| 27 | decide | payment filter (cash/card/all) (§14) | ✗ | ✗ |
| 28 | decide | passenger rating filter (§14) | ✗ | ✗ |
| 29 | decide | wait & save filter (§14) | ✗ | ✗ |
| 30 | decide | geographic zones (§14) | ✗ | ✗ |
| 31 | flow | active ride with nothing pending → reset (§11.4) | ✗ | ✓ |
| 32 | flow | ride hash / identity, 100 / 2500 / 4000 ms (§11.8–9) | ✗ | ✓ |
| 33 | flow | smart delay (§11.11) | ✗ | ✓ + UI |
| 34 | flow | pending tap + 6250 ms watchdog (§10) | ◐ | ✓ |
| 35 | tap | tap the very node that was read (§11.12) | ✗ | ✓ LiveNode |
| 36 | tap | click → parent → 40 ms gesture at centre, no window gate (§8) | ✗ | ✓ clickLadder (+ empty-bounds and Jeeny-package guard) |
| 37 | tap | click on main thread, delay via post (§11.12) | ◐ | ✓ |
| 38 | tap | late capture 60 / 80 ms fills price/time/destination for the report (§10) | ◐ | ◐ reads but does not feed the report |
| 39 | tap | turbo: saved coordinates, instant tap (§4) | ✗ | ✗ |
| 40 | verify | StillOffer / Confirmed / Inconclusive (§15) | ◐ | ✓ |
| 41 | verify | confirmed / new_offer_appeared / timeout / disappeared (§11.6) | ◐ | ✓ |
| 42 | after | accept tone on Confirmed only, alarm stream, one ToneGenerator (§10) | ◐ | ✓ |
| 43 | after | report each outcome (§11) | ◐ | ✓ telemetry + local log |
| 44 | settings | FareSniper fields and defaults (§3,§16) | ✗ | ✓ EngineConfig |
| 45 | settings | conditions page on those fields | ✗ | ✓ same Sabbaq design |
| 46 | gate | engine switch + saved subscription end + clock guard, no network (§5) | ✗ (in-memory lease) | ✓ SnipingGate |
| — | n/a | stealth jitter, root injection — off by default in FareSniper | n/a | n/a |

## Score (46 rows, ✓ = 1, ◐ = 0.5) — 2026-10-29
- Installed 2.0.2: **≈ 15 %**
- Branch `fix/next-batch` (commit 141cfa4): 38 ✓ + 1 ◐ = **38.5 / 46 = 84 %**
- Open: 19–20 (Bolt/Petra readers), 27–29 (payment, rating, wait&save), 30 (zones), 38 (late capture into report), 39 (turbo).
- Jeeny with default settings (FareSniper's default `targetApp = jeeny`, no payment/rating/zone filter, turbo off): every row on that path is ✓ except 38 (reporting only, no effect on accepting).
