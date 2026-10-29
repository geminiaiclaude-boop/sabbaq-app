# Why a driver's orders are not accepted — reading the diagnosis

Every ride-app screen is followed until it is accepted or until the step where it stopped
(`core/.../engine/AcceptanceFunnel.kt`). The device shows the verdict on the dashboard and in
the decision log, and sends it to telemetry. Nothing personal leaves the device: no fare, no
address, no screen text.

## Where to look

`select public.admin_offer_telemetry_for_driver('<driver uuid>');` — the `funnel` lists
stage + reason_code with counts for the last 7 days. The current verdict is the newest
`HEARTBEAT` row whose reason_code starts with `diag:`.

## Verdict codes (`HEARTBEAT diag:<code>`, detail `r=<reason>`)

| code | step | meaning | fix |
|---|---|---|---|
| service_off | service | Accessibility service not connected | Driver re-enables Sabbaq in accessibility settings; check battery/autostart |
| gate_service_paused | gate | Monitoring switched off | Start monitoring |
| gate_disclosure_required | gate | Consent not given | Accept the disclosure |
| gate_subscription_unknown | gate | No subscription end date saved | Open the subscription page once online; check the licence row |
| gate_subscription_expired | gate | Subscription ended | Renew |
| gate_clock_tampered | gate | Phone clock moved back > 2 h | Automatic time |
| no_ride_app_events | events | No ride-app screen for 15 min | Ride app closed/offline, or wrong target app chosen |
| offer_not_read | read | Offer screens seen, offer not extracted; `r` = missing piece | Reader issue: compare with `OFFER_NOT_PARSED` detail (p,m,a,n,cap) |
| all_rejected | decision | Every offer failed a condition; `r` = main reason key | Driver's conditions too strict |
| held_back | decision | Matched offers held by repeat-tap protection | Clears by itself |
| tap_not_sent | tap | Decided, tap not dispatched; `r` = gate / foreign_node / empty_bounds / gesture_refused | See cause |
| tap_not_confirmed | confirm | Tap sent, ride app never showed the trip | Another driver faster, or tap off the button |
| tap_pending | confirm | Tap sent, waiting for the answer | — |
| working | — | At least one confirmed accept in the last hour | — |
| waiting_for_offers | — | Healthy; the ride app showed no offer | — |

## Step reasons in the funnel

- `OFFER_NOT_PARSED` reason: `no_accept`, `no_minutes`, `no_price`, `online_screen`, `no_data`,
  `no_offer_ids`, `cancel_screen`; detail `p=,m=,a=` (fare, minutes, accept seen 0/1), `n=` nodes
  read, `cap=1` node limit reached. Plain map/menu screens (`not_offer_screen`) are counted on
  the device only.
- `ROOT_UNAVAILABLE` reason: `root_unavailable` (Android gave no window), `other_app` (another
  app in front of the ride app).
- `CLICK_RESULT` reason: `semantic`, `parent`, `gesture`, `turbo`, or `not_dispatched:<cause>`.
- `REJECTED` reason: the decision key (`failed_price`, `dest_excluded_zone`, …).
- `CONFIRMATION_*`: `post_click_confirmed`, `confirmation_timeout`, `offer_disappeared_after_click`,
  `new_offer_appeared`.
