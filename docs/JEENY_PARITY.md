# Jeeny parity matrix

This matrix records implementation evidence in the repository only. It does not claim real-device success rates. `Fully implemented` means deterministic JVM coverage exists for the behavior; `Partial` means a production path exists but depends on Android accessibility/device behavior; `Unproven` means no repository evidence is sufficient.

| Dimension | Status | Evidence / remaining proof |
|---|---|---|
| Package and window scoping | Fully implemented | `SabbaqA11yService` scopes Jeeny events and `JeenyOfferPipeline` resets generation on window changes; JVM state tests cover this. |
| Progressive frame handling | Fully implemented | `JeenyFrameClassifier` returns `INCOMPLETE`; probe refuses it; JVM test covers fare-plus-accept partial frames. |
| Independent offer signals | Fully implemented | Classifier combines accept, fare/currency, distance, time, pickup/address and offer-label families with a threshold and minimum feature count. |
| Confirmation/active-ride exclusion | Fully implemented | Confirmation labels and active-state cues are hard exclusions; JVM test covers an active frame retaining an accept-looking node. |
| Full fare/route parsing | Fully implemented | Existing `OfferParser` tests cover Arabic/Indic digits, split currency, meters, minutes, addresses and identity. |
| Bounded scan and text budget | Fully implemented | Classifier caps at 128 nodes/4096 text characters; JVM test verifies the node cap. |
| Late captures | Fully implemented | Two bounded late reads remain in the service and pipeline; existing pipeline test verifies the cap and pending-action stop. |
| Same-offer identity suppression | Fully implemented | `Offer.fingerprint` excludes countdown text and the pipeline suppresses the same fingerprint; JVM tests cover both. |
| Safe click ordering and confirmation tracking | Partial | The service retains `ClickGate`, live-node re-resolution, semantic-before-gesture ordering, and `AcceptTracker`; actual Android dispatch and post-click confirmation are device-only. |
| Accessibility tree/package/device parity | Unproven | No real-device corpus or emulator accessibility capture is checked into this repository; verify with Jeeny builds, locale variants, TalkBack/accessibility settings, and rotated/window transitions. |

## Counts

- Fully implemented: **8 / 10**
- Partial: **1 / 10**
- Unproven: **1 / 10**

## Test corpus metric

The checked-in JVM corpus contains **7 Jeeny pipeline/classifier scenarios** in `JeenyOfferPipelineTest` (positive frame, progressive/incomplete frame, confirmation/active frame, hint-only/unrelated frame, scan limit, same-offer suppression, and window reset), plus parser corpus cases in `OfferParserTest`. This is a repository test count, not a real-device accuracy metric. There are **no real-device parity measurements** claimed here.
