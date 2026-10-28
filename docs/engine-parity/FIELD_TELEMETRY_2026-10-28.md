# Field telemetry — why installed Sabbaq 2.0.2 accepted nothing (24–28 Oct 2026)

Source: `public.offer_telemetry_events`, last 4 days, grouped by version/provider/stage/reason
(query run in the Supabase SQL editor on 2026-10-28).

| stage | reason | n | last | meaning |
|---|---|---|---|---|
| OFFER_DETECTED | complete | 18 | 10-28 12:27 | Jeeny offers fully read |
| ACCEPT_NODE | found | 18 | 10-28 12:27 | accept control found every time |
| DECISION_ACCEPTED | rules_passed | 6 | 10-27 21:07 | conditions matched |
| CLICK_RESULT | unsafe_bounds | 6 | 10-27 21:07 | **every matched offer was never tapped** (bounds checked against displayMetrics without nav bar; fixed in main `c55b7a9`, not installed) |
| REJECTED | pickup_too_far | 6 | 10-28 12:27 | driver condition (legit) |
| REJECTED | trip_km_missing | 4 | 10-28 07:25 | **Jeeny shows no trip distance → missing data rejected** (Qannas never rejects on missing data unless the driver enables reject-on-unknown) |
| REJECTED | pickup_km_missing | 1 | 10-28 07:15 | single distance not assigned to pickup |
| REJECTED | redacted | 1 | 10-27 19:49 | — |
| GATE_BLOCKED | service_paused | 114 | 10-28 13:15 | monitoring paused by the licence gate |
| HEARTBEAT | license_invalid | 64 | 10-28 13:14 | licence lapse kept monitoring off (fixed in main by the licence fix, not installed) |
| HEARTBEAT | a11y_disabled | 47 | 10-28 07:52 | accessibility service off on a device |
| STALE | throttled | 89 | 10-28 12:27 | **content events dropped by the 100 ms throttle** (Qannas never drops: it coalesces) |
| STALE | unchanged_frame | 113 | 10-28 12:27 | identical frame skipped |
| SIGNAL_MISS | cls_none | 89 | 10-28 12:27 | frames the classifier called "no offer" |
| ROOT_UNAVAILABLE | active_null/systemui/other | 21 | 10-28 12:27 | active window not the ride app |

Conclusion: out of 18 real offers, **0 were tapped**: 6 matched but blocked by the bounds guard,
5 rejected for data Jeeny never shows, 6 rejected by the driver's own pickup limit, and the
licence gate paused monitoring for most of 28 Oct. Throttle drops and classifier misses add
further losses. All of these are engine behaviours that differ from FareSniper.
