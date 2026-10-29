# Address geocoding for destination zones

Zones need a point for the order's destination address. The app asks, in order:

1. **Sabbaq's server** — `public.geocode_resolve` (migration `0014_geocode_cache.sql`). A shared
   cache answers every driver; a miss is sent once to **Google Geocoding** with the key kept on the
   server. Bounded by a monthly cap and a per-driver daily cap.
2. **OpenStreetMap Nominatim** — when Google is off, capped, failing or offline.
3. **Android's geocoder** — last resort.

Only the order's address text and a coarse region (driver position rounded to 0.5°, ±1°) leave
the device; never the exact position.

## Turning Google on (production)

1. Apply `supabase/migrations/0014_geocode_cache.sql` in the Supabase SQL editor.
2. Supabase → Database → Extensions: enable **http** (schema `extensions`). Without it the server
   answers "unavailable" and the app keeps using OpenStreetMap.
3. Google Cloud: enable **Geocoding API**, create an API key, restrict it to the Geocoding API.
   Billing must be enabled (10,000 requests a month free, then about $5 per 1,000).
4. Sabbaq admin panel → **سياسة العرض** → **تحديد مواقع العناوين (Google)**: paste the key, switch
   it on, set the caps (defaults: 9,000 a month, so it stays inside the free tier; 200 a day per
   driver). The key is write-only: the panel shows only whether one is set, this month's calls and
   the number of cached addresses.

## Behaviour

| server answer | app |
|---|---|
| `hit` / `resolved` with points | stores the points locally; zones use them |
| `hit` / `resolved` with no points | address not found anywhere; remembered, not asked again |
| `unavailable` (`disabled`, `monthly_cap`, `driver_cap`, `fetch_failed`) | falls back to OpenStreetMap, then Android |

`public.purge_geocode_cache()` drops "not found" answers after 30 days and usage rows after 90.
Tests: `supabase/tests/geocode_cache_test.sql` (Google replaced by a stub).
