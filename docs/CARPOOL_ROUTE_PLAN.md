# Shared Carpool Route — Implementation Plan

Status: Design locked. Ready to start Phase 1.

This document is the source of truth for the **AI-Powered Shared Carpool Route Planning** feature. It captures the design decisions agreed during planning and breaks the work into safe, testable phases.

---

## 1. What we are building

When a parent is assigned as the TO or FROM driver for a practice, the app generates an **optimized carpool route** that is shared in realtime with every group member.

Two route types:

- **Pickup (TO)**: driver home → each rider's home (in nearest-neighbor order) → practice. Anchored to `practice.startTime`.
- **Dropoff (FROM)**: practice → each rider's home (in nearest-neighbor order) → driver home. Anchored to `practice.endTime`.

Both routes use the **same roster**: `practice.participantUids` (a single list of children riding this practice).

---

## 2. Design decisions locked in

These are not up for debate during implementation — they were resolved in planning.

| Decision | Choice |
|---|---|
| Map SDK | **Google Maps Android SDK** (native, via `SupportMapFragment`) |
| Routing backend | **OSRM public demo** (`router.project-osrm.org`) via OkHttp |
| Gemini AI | **NOT in MVP.** Optional Phase 9 only — never input to routing, only a human-readable summary. |
| Roster source | `practice.participantUids: List<String>` — single list, both directions |
| Roster entry path #1 | CHILD taps "Join this practice" → arrayUnion of own uid |
| Roster entry path #2 | PARENT approves a CHILD's drive_request → atomic batch: `drive_request.status = APPROVED` AND `participantUids.arrayUnion(child)` |
| Driver cancels their slot | `participantUids` **unchanged.** Driver field cleared; approved drive_requests revert to PENDING. |
| Child leaves practice | arrayRemove self from `participantUids`. If had an APPROVED drive_request, it becomes CANCELED in the same batch. |
| Whole practice canceled | Existing cancel cascade. Route screen shows "Practice canceled" banner. |
| Ordering algorithm | Greedy nearest-neighbor by Haversine distance — deterministic, O(n²), n ≤ ~6 |
| ETA model | OSRM leg durations + per-stop dwell (60s) + 5-min buffer for pickup; 0 buffer for dropoff |
| Route persistence | One Firestore doc per `(practiceId, direction)` in `carpool_routes` collection. Doc id = `{practiceId}_PICKUP` or `{practiceId}_DROPOFF`. |
| Realtime strategy | Single `addSnapshotListener` per route doc per screen. No collection-wide queries. |
| Async pattern | **Callback-based.** No coroutines. Matches the existing project. OSRM hops back to main thread via `Handler(Looper.getMainLooper())`. |
| JSON parsing | `org.json.JSONObject` (built-in). No Moshi/Gson dependency. |
| Address input | **Map picker** (long-press to drop pin). No geocoding API. |
| New-user onboarding | After `ChooseRoleActivity`, a new `SetHomeAddressActivity` offers an optional, skippable home-address pick. Existing users see the dashboard banner only. |
| **Join with no home address** | **Allow the join.** Show a Snackbar tip on the joining child's screen with a `[Set]` action that opens the map picker. The dashboard banner is the long-term reminder. Reason: minimizing friction is more important than perfect data — the route generator handles the missing case safely. |
| **Route generation with missing-address passengers** | **Skip them from the route**, persist their uids in a `missingAddressUids` field on the `CarpoolRoute` doc, and surface a "⚠ Some riders have no address" warning on the route screen listing the names. The driver is never blocked. |
| Navigation | Manual `parentFragmentManager.commit { replace(...) }` — matches the project's existing pattern. |

---

## 3. Pre-flight — do this BEFORE Phase 1

| Step | What |
|---|---|
| 3.1 | Enable the **Maps SDK for Android** in the Google Cloud Console of the same project that hosts your `google-services.json`. |
| 3.2 | Create an Android-restricted API key. Restrict to your debug SHA-1 fingerprint + the package name `dev.segev.carpoolkids`. |
| 3.3 | Add `MAPS_API_KEY=...` to **`local.properties`** (which is gitignored). **Never** commit the key. |
| 3.4 | Test that you can reach `https://router.project-osrm.org/route/v1/driving/34.78,32.09;34.81,32.12?overview=false` from the dev machine and from a phone on the demo network. |
| 3.5 | Plan demo data: at least 1 parent driver, 3 children with addresses set, 1 practice with coordinates. Decide whether to seed in code or set up via the app on demo day. |

---

## 4. Phases

Each phase is small enough to verify on its own and ends with a tangible visible result. Do not skip phases or merge them — the order is chosen so each phase leaves the app in a working state.

---

### Phase 1 — Schema + dependencies + Firestore rules

**Goal**: Lay the data and infrastructure groundwork. No user-visible change yet.

**Files modified / created**:
- `app/build.gradle.kts` — add `com.google.android.gms:play-services-maps`, `com.squareup.okhttp3:okhttp` (latest stable). Add `manifestPlaceholders` reading `MAPS_API_KEY` from `local.properties`.
- `AndroidManifest.xml` — add `com.google.android.geo.API_KEY` meta-data. Add `ACCESS_NETWORK_STATE` permission (Maps recommends).
- `model/UserProfile.kt` — `+ homeLat: Double?`, `+ homeLng: Double?`, `+ homeAddressLabel: String?`
- `model/Practice.kt` — `+ locationLat: Double?`, `+ locationLng: Double?`, `+ participantUids: List<String> = emptyList()`
- `model/CarpoolRoute.kt` — **new** data class
- `model/RouteStop.kt` — **new** nested data class
- `utilities/Constants.kt` — `+ COLLECTION_CARPOOL_ROUTES = "carpool_routes"`, `+ DIRECTION_PICKUP = "PICKUP"`, `+ DIRECTION_DROPOFF = "DROPOFF"`
- `utilities/FirestoreManager.kt` — extend `documentToPractice()` and `documentToUserProfile()` to read the new fields (defensive nullable reads)
- `firestore.rules` — three rule changes (described below)

**Firestore rules changes**:

1. **Practices update — `participantUids` self-only branch.** Mirror the cancel-practice cascade rule pattern (`diff().affectedKeys().hasOnly([...])`). Any group member can arrayUnion or arrayRemove **their own uid only**, when only that field changes. Existing practice-update rules for other fields stay untouched.

2. **Drive requests update — auto-add to participants on approve.** When a parent flips status PENDING → APPROVED, the same write may also set `acceptedByUid`. The participants arrayUnion happens as a *second write in the same `WriteBatch`* from the client (rules cannot orchestrate cross-doc writes). The rule simply allows the participants arrayUnion when the writer is a PARENT in the group — same pattern as existing accept rules.

3. **`carpool_routes` collection** — full ruleset:
   - **Read**: any group member.
   - **Create / Update / Delete**: only the assigned driver for that direction (`request.auth.uid == practice.driverToUid` for PICKUP; `... == practice.driverFromUid` for DROPOFF).

**Difficulty**: Low. Mostly additive.

**Risks**: Rules branching — get the `affectedKeys()` shape wrong and you break existing practice updates. **Test in the Firestore emulator before deploying.**

**Visible result**: None — backend prep only.

**Acceptance**: App builds. Existing practices still load (nullable reads tolerate missing fields). Existing rules tests still pass.

---

### Phase 2 — Map picker + onboarding + practice location

**Goal**: Users can set geographic coordinates for their home (with an explicit onboarding step for new users) and parents can attach coordinates to practices. Two new activities, two new layouts, and additive changes to three existing fragments + the repositories.

**Files modified / created**:
- `MapPickerActivity.kt` — **new.** Hosts a `SupportMapFragment`; long-press drops / moves a single pin; returns `lat, lng` via `setResult`. Uses the modern `registerForActivityResult` API. Exposes static helpers `intentForHome(...)`, `intentForPracticeLocation(...)`, `extractResult(...)` so callers don't deal with extra-key strings.
- `res/layout/activity_map_picker.xml` — **new.** `<fragment>`-hosted `SupportMapFragment` + title/hint + cancel/confirm row.
- `SetHomeAddressActivity.kt` — **new.** Onboarding step between role selection and the group flow. Title, friendly explanation, primary `Set on map` button (launches `MapPickerActivity`), outlined `Skip for now` button. Either path finishes the activity with `RESULT_OK`; the caller (`ChooseRoleActivity`) continues onboarding via its result launcher. Existing users never reach this screen — they see the dashboard banner instead.
- `res/layout/activity_set_home_address.xml` — **new.** Title + explanation + Set/Skip buttons + indeterminate progress.
- `ChooseRoleActivity.kt` — after `saveRoleAndNavigate` succeeds, route through `SetHomeAddressActivity` via a new `setHomeLauncher`. On result, continues with the existing group-count navigation in `navigateAfterLogin`. `pendingRoleAfterChoose` is set before launching so the result lambda has the role.
- `AndroidManifest.xml` — register `MapPickerActivity` and `SetHomeAddressActivity` (both `exported=false`).
- `DashboardHomeFragment.kt` — banner appears when the logged-in user has no `homeLat/Lng`. Tap `Set home` launches `MapPickerActivity`; on result, saves via `UserRepository.updateHomeAddress` and hides the banner. Banner serves as the long-term reminder for users who skipped onboarding or pre-date Phase 2.
- `AddPracticeFragment.kt` — `Pick on map` outlined button below the location field. Picked coords flow into `PracticeRepository.createPractice` (signature extended with optional `locationLat/Lng`). After picking, the button shows `Pin set ✓`.
- `PracticeDetailFragment.kt` — `Set location on map` / `Change location on map` button (label adapts) for parents on non-canceled practices. Picker confirm calls `PracticeRepository.updateLocationCoords` and re-binds the local `Practice` copy.
- `data/UserRepository.kt` — `updateHomeAddress(uid, lat, lng, label, callback)` delegate.
- `data/PracticeRepository.kt` — `createPractice` signature extended with `locationLat`, `locationLng` (defaults null); new `updateLocationCoords(practiceId, lat, lng, callback)` delegate.
- `utilities/FirestoreManager.kt` — `createPractice` writes `locationLat/Lng` when present (uses `hashMapOf<String, Any>` for type-safe puts); new `updateHomeAddress(...)` and `updateLocationCoords(...)`.
- `res/layout/fragment_add_practice.xml` — `Pick on map` outlined button.
- `res/layout/fragment_dashboard_home.xml` — banner card; `today_label` constraint moved to chain below the banner with `layout_goneMarginTop` for the hidden state.
- `res/layout/fragment_practice_detail.xml` — `Set location on map` outlined button below the location row.
- `res/values/strings.xml` — `map_picker_*`, `profile_home_*`, `set_home_address_*`, `add_practice_pick_on_map(_set)`, `practice_detail_set/change_location_on_map`, plus success / error strings.

**New-user onboarding flow** (the part beyond the original Phase 2 spec):

```
Splash → Login → ChooseRole → [NEW] SetHomeAddress → CreateJoinGroup / Dashboard / TeamsList
                                       │
                                       ├── Set on map → MapPickerActivity → save → continue
                                       └── Skip for now                          → continue
```

**Difficulty**: Medium. First Maps SDK integration.

**Risks**: Maps API key misconfigured → gray tiles. Verify with a clean install before continuing. Onboarding launcher must call `navigateAfterLogin` regardless of `SetHomeAddressActivity` result code so a Skip / Set / back-press all keep onboarding moving forward.

**Visible result**: New users see the SetHomeAddress screen after picking a role. Existing users without a home see the dashboard banner. Parents can attach coords to practices both at create-time (AddPractice) and after (PracticeDetail).

**Acceptance**: A brand-new user lands on `SetHomeAddressActivity` after role selection; setting and skipping both continue to the group flow. An existing user without `homeLat` sees the banner; setting via the banner hides it and writes to Firestore. A parent can attach coordinates to a practice and see them persist.

---

### Phase 3 — Join practice flow

**Goal**: Children can join/leave a practice. Approving a drive_request auto-adds the child.

**Files modified / created**:
- `data/PracticeRepository.kt` — `joinPractice(practiceId, uid, callback)`, `leavePractice(practiceId, uid, callback)`.
- `utilities/FirestoreManager.kt` — same two methods. `leavePractice` must be an atomic batch: arrayRemove from participants AND, if the user has an APPROVED drive_request for this practice, set that request to CANCELED in the same batch.
- `data/DriveRequestRepository.kt` + `utilities/FirestoreManager.kt` — modify `acceptDriveRequest`. The existing transaction must be augmented so the same atomic operation that flips status to APPROVED **also arrayUnions the requesterUid into `practice.participantUids`**. Keep it transactional — partial state is unacceptable.
- `data/GroupRepository.kt` — in `leaveGroup` cascade, also arrayRemove the leaving uid from every future practice's `participantUids`. (Past practices left alone — historical accuracy.)
- `PracticeDetailFragment.kt`:
  - Add `practice_detail_join_button` and a riders list TextView to the transportation card.
  - For CHILD role only: show the button. Toggle between "+ Join this practice" and "✓ You're riding · tap to leave."
  - Show "Riders (N): name1, name2, name3" subtext when `participantUids` is non-empty. Fetch names via `UserRepository.getUsersByIds`.
  - When a CHILD has a PENDING drive_request for this practice, the Join button is hidden (avoid double-bookkeeping).
  - **Missing-home-address tip**: after a successful join, if the child's own `homeLat/Lng` is null, show a Snackbar (8s) with action `[Set]` that launches `MapPickerActivity` for home. Don't block the join. The dashboard banner is the long-term reminder; the route generator (Phase 5) handles the case where the child never sets it.
- `res/layout/fragment_practice_detail.xml` — add button + riders TextView in the transportation card.
- `res/values/strings.xml` — `practice_join_button`, `practice_leave_button`, `practice_riders_label`, `practice_no_riders`, `practice_join_already_pending`, `practice_join_set_home_tip`.

**Difficulty**: Medium. Atomic batch in `acceptDriveRequest` needs care.

**Risks**:
- Partial writes if the transaction is not extended correctly. Test with a fault-injection scenario (Firebase emulator).
- Forgetting to update the `leaveGroup` cascade leaves orphan uids in future practices. Add a unit-style log check.

**Visible result**: Children can join/leave practices. Parents who approve drive_requests see the riders list grow automatically.

**Acceptance**: Drive requests, approvals, and join/leave all keep `participantUids` consistent with the "child is actually riding" reality.

---

### Phase 4 — OSRM client + ordering + ETA (no UI yet)

**Goal**: All routing math works correctly, validated through logs. Still no user-visible map.

**Files modified / created** (new package `routing/`):
- `routing/OsrmClient.kt` — thin OkHttp wrapper. One public method:
  `fun fetchRoute(coords: List<LatLng>, callback: (OsrmResult?, String?) -> Unit)`.
  POSTs to `https://router.project-osrm.org/route/v1/driving/{coordsCsv}?overview=full&steps=false`. Parses with `org.json`. Hops back to main thread before calling the callback. Hold the `Call` reference so the caller can cancel.
- `routing/OsrmResult.kt` — `polyline: String`, `totalDurationSec: Int`, `totalDistanceMeters: Int`, `legs: List<Leg>`.
- `routing/PolylineDecoder.kt` — decodes Google's encoded polyline (standard 30-line algorithm) into `List<LatLng>`.
- `routing/RouteOrderHeuristic.kt` — `fun greedyOrder(start: LatLng, stops: List<Pair<String, LatLng>>, end: LatLng): List<Pair<String, LatLng>>`. Returns stops in nearest-neighbor visiting order.
- `routing/EtaCalculator.kt` — pure functions:
  - `recommendedDeparture(trainingStartMillis, totalLegSec, numStops, dwellSec, bufferSec): Long`
  - `etaPerStop(departureMs, legDurations, dwellSec): List<Long>`
  - `returnTime(trainingEndMillis, totalLegSec, numStops, dwellSec): Long`
- A throwaway "Test route" menu item in `PracticeDetailFragment` (debug only — remove before submission) that triggers the OSRM call with hardcoded coords and logs the result.

**Difficulty**: Medium. Polyline decoding is error-prone — copy a known-correct implementation and test against a fixture.

**Risks**:
- OSRM public-demo rate limits during heavy testing.
- Polyline bug → garbled map line. Verify with a fixed coordinate set and a manual visual check on the next phase.

**Visible result**: Logs show "Route: 4.2 km, 12 min, polyline len = 380 chars." No UI.

**Acceptance**: Given known inputs, the order, durations, and ETAs match a hand-calculated expected value.

---

### Phase 5 — Route generation + Firestore writes (still no map)

**Goal**: Driver can tap a button on `PracticeDetailFragment` to generate a route document. The doc appears in Firestore. No map screen yet.

**Files modified / created**:
- `data/CarpoolRouteRepository.kt` — **new** object singleton. Methods:
  - `generateAndSave(practiceId, direction, driverUid, callback)`
  - `listenToRoute(routeId, callback): ListenerRegistration`
  - `getRoute(routeId, callback)`
- `utilities/FirestoreManager.kt` — `createOrUpdateCarpoolRoute(route, callback)`, `listenToCarpoolRoute(routeId, callback)`, `getCarpoolRoute(routeId, callback)`.
- `data/GroupRepository.kt` — extend `deleteCarpoolAndRelatedData` to also delete docs in `carpool_routes` where `groupId` matches. (Three lines.)
- `PracticeDetailFragment.kt` — temporary "Generate pickup route" and "Generate dropoff route" buttons visible to the assigned driver only. On tap, call the repository and show a Toast with the result.
- `res/values/strings.xml` — `route_generate_pickup`, `route_generate_dropoff`, `route_generated_toast`, `route_generation_failed`.

**Generation algorithm** (inside `CarpoolRouteRepository.generateAndSave`):
1. Load the practice.
2. Guardrail: practice not canceled, coordinates present, driver uid matches expected direction.
3. Load `participantUids` → `UserRepository.getUsersByIds` → partition into:
   - `routed`  = profiles with both `homeLat` and `homeLng` set
   - `missing` = profiles with no home coordinates
4. If `routed.isEmpty()` → write doc with `status = "EMPTY_ROSTER"` and return (driver sees a friendly empty state — even if `missing` is non-empty, we can't route to anyone).
5. `RouteOrderHeuristic.greedyOrder(driverHome, routed, practiceLoc)` (for PICKUP) or reverse (for DROPOFF).
6. `OsrmClient.fetchRoute(orderedCoords)`.
7. On OSRM success → build `CarpoolRoute` with stops, ETAs, polyline, totals, `status = READY`, and **`missingAddressUids = missing.map { it.uid }`** so the route screen can surface those names without an extra fetch. Write doc.
8. On OSRM failure → write doc with `status = FAILED` and `failureReason`.

**Missing-address UX**: a `missingAddressUids` field on the `CarpoolRoute` doc lets the route screen render a warning section like *"⚠ 2 riders have no address — Daniel, Maya. Ask them to set their home address from the Home tab."* The route itself remains usable; the driver is never blocked.

> **Heads-up for Phase 5 implementation**: `missingAddressUids: List<String> = emptyList()` was **not** included on the `CarpoolRoute` model in Phase 1 — add it as part of the Phase 5 model edits, along with the corresponding read in `documentToCarpoolRoute`. (Same pattern as how Phase 1 added all the other new fields.)

**Difficulty**: Medium.

**Risks**: Firestore rules denying writes. Verify rules from Phase 1 cover this case.

**Visible result**: Driver taps button → Toast "Route generated" → doc visible in Firebase console.

**Acceptance**: Doc has correct `stops`, `polyline`, `recommendedDepartureMillis`. Regenerating overwrites cleanly.

---

### Phase 6 — `CarpoolRouteFragment` with map

**Goal**: The actual route screen. Map, polyline, numbered pins, summary card, stops list.

**Files modified / created**:
- `CarpoolRouteFragment.kt` — **new.** Hosts `SupportMapFragment`. Loads route doc via listener. Builds markers, polyline, camera bounds.
- `res/layout/fragment_carpool_route.xml` — **new.** Top: toolbar. Middle: summary card overlaid on map fragment. Bottom: RecyclerView of stops, action row.
- `res/layout/item_route_stop.xml` — **new.** Numbered chip + name + ETA + distance. Highlights with "You" badge for the viewing user.
- `ui/route/RouteStopsAdapter.kt` — **new.** Plain `RecyclerView.Adapter` (match the project's style — no `ListAdapter`/`DiffUtil`).
- `ui/route/NumberedMarkerFactory.kt` — **new.** Generates a `BitmapDescriptor` with a number drawn on a circle. Same number style as the chip in `item_route_stop.xml`.
- `PracticeDetailFragment.kt` — replace the temporary buttons from Phase 5 with proper "View pickup route" / "View dropoff route" buttons that open `CarpoolRouteFragment`. Visibility based on `driverToUid` / `driverFromUid` and not canceled.
- `res/raw/map_style_dark.json` — **new.** Map styling JSON matching the app's dark theme.
- `res/values/strings.xml` — `route_screen_title_pickup`, `route_screen_title_dropoff`, `route_open_in_google_maps`, `route_regenerate`, `route_leave_by`, `route_arrive_at`, `route_total_summary`, `route_depart`, `route_pick_up_at`, `route_drop_at`, `route_distance_from_previous`.

**Lifecycle discipline** (every one of these must be enforced):
- `_binding` nullable; cleared in `onDestroyView`.
- `routeListener: ListenerRegistration?` removed in `onDestroyView`.
- `osrmCall: Call?` cancelled in `onDestroyView`.
- `googleMap` referenced only inside `getMapAsync` lambda or after `_binding != null` checks.

**Difficulty**: High. First multi-marker map work + lifecycle.

**Risks**: Map fragment id conflicts; null `googleMap` access during quick navigation; missing API key surfacing only at runtime.

**Visible result**: Full route screen with map, polyline, numbered markers, summary card, stops list.

**Acceptance**: On a real device, the route renders correctly for both PICKUP and DROPOFF. Numbered markers match the stops list order.

---

### Phase 7 — Realtime + states

**Goal**: Multi-user realtime sync and proper handling of every edge state.

**Files modified / created**:
- `CarpoolRouteFragment.kt` —
  - Wire the live listener so updates from another user propagate within ~1 second.
  - Stale detection: on each route update, compare `route.stops.size` with `practice.participantUids.size`. If different, show "Riders changed — regenerate" banner (driver sees a Regenerate button; others see informational text).
  - Empty states: NO_DRIVER, NO_RIDERS, MISSING_ADDRESSES, DRIVER_CANCELED_SLOT, PRACTICE_CANCELED. Each has its own copy and CTA.
  - Error states: OSRM failure with Retry button.
  - "Open in Google Maps" deep link: `Uri.parse("google.navigation:q=$lat,$lng")`.
- `PracticeDetailFragment.kt` — hide route buttons when `practice.canceled == true`.
- `res/values/strings.xml` — all the state strings (`route_state_no_driver`, `route_state_no_riders`, `route_state_missing_addresses`, `route_state_driver_canceled`, `route_state_practice_canceled`, `route_state_failed_with_retry`).

**Difficulty**: Medium. Mostly UI branches.

**Risks**: Stale detection getting stuck in a "regenerate" loop because the listener fires repeatedly. Make sure the stale check is idempotent.

**Visible result**: Two devices showing the same route. When one regenerates, both update.

**Acceptance**: Each of the five empty/error states reachable manually shows the correct copy. Multi-device sync demo works.

---

### Phase 8 — Polish (only after Phase 7 is stable)

**Goal**: Demo-day shine.

**Items** (do as many as time allows):
- Dark-theme map style applied (`MapStyleOptions.loadRawResourceStyle`).
- Camera animates to fitted bounds with smooth duration.
- Marker drop animation.
- Tapping a stop row in the list animates the camera to that marker and bounces it.
- "You" badge highlight on the viewing user's row.
- Numbered chip colors match the polyline color.
- "Recently generated" relative time on the summary card ("Generated 2 min ago").

**Difficulty**: Low.

**Risks**: Don't break Phase 7 functionality while polishing. Polish each item, then verify everything still works.

---

### Phase 9 — Gemini summary (optional bonus)

**Only attempt if Phase 7 is rock-solid and submission is ≥ 1 week away.**

**Goal**: Add a one-sentence human-readable summary above the stops list.

**Files modified / created**:
- `routing/GeminiSummaryService.kt` — **new.** OkHttp call to Gemini API. 3-second timeout. Single retry. Hard-coded prompt template.
- `model/CarpoolRoute.kt` — `+ aiSummary: String?`, `+ aiSummaryGeneratedAt: Long?`.
- `CarpoolRouteFragment.kt` — render `aiSummary` in a one-line text view above the stops list. Fallback to a templated string built from `recommendedDeparture` + `totalDurationSec` when null.

**Difficulty**: Medium.

**Risks**: API key handling (same discipline as Maps key). API outages on demo day — the fallback templated string must always work.

**Acceptance**: Gemini summary appears when the API call succeeds. Templated fallback appears when it doesn't. **The map and stops list never depend on Gemini.**

---

## 5. What NOT to touch close to submission

A hard freeze list for the final week before submission:

- `firestore.rules` — only add the `carpool_routes` block. Don't refactor existing rules.
- `documentToPractice`, `documentToUserProfile` — only add new field reads. Don't reorganize.
- The cancel-practice cascade, leave-carpool cascade, drive-request accept transaction. These are recent work and high-blast-radius.
- Bottom navigation, fragment-switching logic.
- Existing styles in `styles.xml`. Add new ones, don't modify old ones.

---

## 6. Demo day checklist

| Item | Status |
|---|---|
| Maps API key restricted to debug SHA-1 + package | |
| OSRM endpoint reachable from demo Wi-Fi | |
| 1 parent driver account with home address set | |
| At least 3 child accounts with home addresses set | |
| 1 practice with `locationLat/locationLng` set and a future date | |
| Children joined the practice (or have APPROVED drive_requests) | |
| Route doc pre-generated so the screen opens instantly even if Wi-Fi is slow | |
| Two physical devices on the same Wi-Fi for the multi-device sync moment | |
| Backup screenshots of the route screen in case of network failure | |

---

## 7. The wow moment (rehearse this)

1. Two phones on the same Wi-Fi. Phone A signed in as the parent driver (TO). Phone B signed in as a child rider.
2. Both phones open the same practice → tap "View pickup route."
3. On Phone B, child taps "Join this practice" — Phone A's screen shows the stale banner: "Riders changed."
4. On Phone A, parent taps Regenerate. Spinner. Polyline redraws on Phone A.
5. Within ~1 second, Phone B's polyline redraws too — without anyone touching Phone B.

That is the entire pitch.

---

## 8. Open questions / future work (post-MVP)

Capture but do not build now:

- Manual per-stop coordinate override by the driver ("today, pick me up from school not home").
- Push notifications when a route is generated/updated (FCM).
- Live driver position tracking on the map (FusedLocationProvider + permissions).
- Multi-driver carpool splitting (more than one TO driver per practice).
- Geocoding from a text address (so users don't have to drop a pin).
- "Routes" as a top-level navigation tab listing all upcoming carpool routes.
