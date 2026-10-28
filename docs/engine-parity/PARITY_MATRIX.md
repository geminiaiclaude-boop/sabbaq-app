# FareSniper ↔ Sabbaq — full order cycle, step by step

Each row is one behaviour of FareSniper's cycle (QANNAS_ENGINE_FACTS.md §). Status:
**✓** identical · **◐** partly · **✗** missing/different · **n/a** deliberately excluded.
"Installed" = Sabbaq 2.0.2 on drivers' phones. "Branch" = `fix/next-batch` now.

| # | Stage | FareSniper behaviour (§) | Installed | Branch |
|---|---|---|---|---|
| 1 | setup | event types 2048\|32, notificationTimeout 0, REPORT_VIEW_IDS + INTERACTIVE_WINDOWS (§1,§5) | ✓ | ✓ |
| 2 | setup | gesture warm-up stroke at connect (§5) | ✓ | ✓ |
| 3 | intake | package routing Jeeny/Bolt/Petra by keywords (§5) | ◐ exact packages | ◐ |
| 4 | intake | 2048 while a booked read is in flight → coalesce, nothing lost (§4) | ✗ | ✗ service not rewired |
| 5 | intake | booked root read off the main thread, 2-thread pool, priority −4, abort flags (§7) | ✗ main thread | ✗ |
| 6 | intake | throttle = adaptive 25–60 ms; inside it probe, drop only on NO_SIGNAL (§4,§11.3) | ✗ fixed 100 ms drop | ◐ core computes it |
| 7 | intake | noise-widget source filter (§4) | ✗ | ✗ |
| 8 | intake | subtree read with 15 ms budget, root fallback (§11.1–2) | ✗ | ✗ |
| 9 | read | keyword probe depth ≤4, ≤8 children, 10 ms, UNKNOWN on timeout (§6) | ✗ score classifier | ✗ |
| 10 | read | learned view-id shortcut, reset after 3 misses (§9,§12.2) | ✗ dead code | ✓ core |
| 11 | read | BFS with node cap 300/400/600 and hard/idle budgets (§13) | ✗ 3000-node copy | ✓ core |
| 12 | read | price rules: currency regex, lower 70 %, fallbacks, decimals (§13) | ✗ | ✓ core |
| 13 | read | time rules (time words, regex, extra keywords) (§13) | ✗ | ✓ core |
| 14 | read | accept: accept words minus rule words, no clickable needed, climb ≤3 parents (§13) | ✗ clickable required | ✓ core |
| 15 | read | reject node + accept found beside reject (§13) | ✗ | ✓ core |
| 16 | read | address rule ≥3 letters, labels, destination/pickup choice (§12.1,§13) | ✗ | ✓ core |
| 17 | read | online banner → no offer; active ride detection (§13) | ✗ | ✓ core |
| 18 | read | offer = accept + minutes, price optional when price filter off (§13) | ✗ | ✓ core |
| 19 | read | Bolt reader `Y2` (§7) | ✗ | ✗ not yet read |
| 20 | read | Petra reader `Et` (§7) | ✗ | ✗ not yet read |
| 21 | decide | no price and no time → reject (§14) | ✗ | ✓ core |
| 22 | decide | price range, 0 = unbounded (§14) | ✗ | ✓ core |
| 23 | decide | time range, bypass by high price (§14) | ✗ | ✓ core |
| 24 | decide | AND / OR (§14) | ◐ | ✓ core |
| 25 | decide | destination include / exclude + reject-on-unknown (§14) | ✗ | ✓ core |
| 26 | decide | no rejection for missing data / "no filters" / "no destination" (§14) | ✗ | ✓ core |
| 27 | decide | payment filter (§14) | ✗ | ✗ |
| 28 | decide | passenger rating filter (§14) | ✗ | ✗ |
| 29 | decide | wait & save filter (§14) | ✗ | ✗ |
| 30 | decide | geographic zones (include/exclude/global) (§14) | ✗ | ✗ |
| 31 | flow | active ride with nothing pending → reset (§11.4) | ✗ | ✓ core |
| 32 | flow | ride hash / identity, 100 / 2500 / 4000 ms rules (§11.8–9) | ✗ 500 / 20000 | ✓ core |
| 33 | flow | smart delay (Gaussian in [min,max]) (§11.11) | ✗ | ✓ core |
| 34 | flow | pending tap + 6250 ms watchdog (§10,§11.11) | ◐ 5 s tracker | ✓ core |
| 35 | tap | tap the very node that was read (§11.12) | ✗ re-resolved by path+label | ✗ service not rewired |
| 36 | tap | ladder: click → parent click → 40 ms gesture at node centre, no window/edge gate (§8) | ✗ `unsafe_bounds` | ✗ |
| 37 | tap | click on main thread, delay via coroutine (§11.12) | ◐ | ✗ |
| 38 | tap | late capture 60 ms / 80 ms budget to fill price/time/destination (§10) | ◐ | ✗ |
| 39 | tap | turbo: saved coordinates, instant tap, late read (§4) | ✗ | ✗ |
| 40 | verify | StillOffer / Confirmed / Inconclusive word lists (§15) | ◐ different lists | ✓ core |
| 41 | verify | outcomes: confirmed / new_offer_appeared / timeout / disappeared (§11.6) | ◐ | ✓ core |
| 42 | after | accept tone on Confirmed only, alarm stream, one ToneGenerator (§10) | ◐ | ✗ |
| 43 | after | report each outcome to the server (§11) | ◐ telemetry | ✗ |
| 44 | settings | settings fields and FareSniper defaults (§3,§16) | ✗ | ◐ core config only |
| 45 | settings | filters page on the FareSniper field set | ✗ | ✗ |
| 46 | gate | engine/sniping switches + subscription expiry gate (§5 isSnipingAllowedNow) | ◐ licence lease | ◐ |
| — | n/a | stealth (anti-detection) jitter, root tap injection — off by default in FareSniper | n/a | n/a |

## Score (46 rows, ✓ = 1, ◐ = 0.5)
- Installed 2.0.2: 2 ✓ + 10 ◐ = **7 / 46 = 15 %**
- Branch now: 25 ✓ + 5 ◐ = **27.5 / 46 = 60 %** — the engine core is done; the service that feeds it and taps (rows 4–9, 35–39, 42–43), Bolt/Petra readers, settings/UI and the extra FareSniper filters are not yet.

Target ≥ 97 % = at most one row left at ◐. Work order: service rewire (rows 4–9, 35–38, 42–43) → settings/UI (44–45) → Bolt/Petra readers (19–20) → probe (9) → payment / rating / wait&save (27–29) → turbo (39) → zones (30).
