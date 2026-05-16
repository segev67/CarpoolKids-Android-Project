# 🚗 CarpoolKids

> **Smart carpool coordination for parents and children — share practice schedules, request rides, and view routes in real time.**

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-34-blue)
![Firebase](https://img.shields.io/badge/Firebase-Auth%20%2B%20Firestore-FFCA28?logo=firebase&logoColor=black)
![Maps](https://img.shields.io/badge/Google%20Maps-SDK-4285F4?logo=googlemaps&logoColor=white)
![Routing](https://img.shields.io/badge/Routing-OSRM-2ECC71)

---

## 📖 Overview

**CarpoolKids** is a collaborative carpool management app for families whose children attend extracurricular activities — sports practices, classes, rehearsals. Instead of scattered WhatsApp threads and spreadsheets, the group shares one source of truth: the practice schedule, who's driving each leg, who's riding, and a live map of the pickup or drop-off route.

**The problem it solves.** Coordinating "who picks up whom and at what time" is the recurring friction point of every kids' activity. CarpoolKids replaces ad-hoc messaging with a structured, realtime, role-aware app where parents organize and approve rides while children request and confirm their participation.

**Target users.** Parents (organize, drive, approve) and children (join practices, request rides, view their pickup time).

**Context.** Final Android course project — Afeka College.

---

## ✨ Features

### 🔐 Authentication
- **Firebase Authentication** with FirebaseUI sign-in (email/password).
- **Role selection** on first launch: PARENT or CHILD. Role is persisted in `users/{uid}.role` and gates UI/permissions throughout the app.
- **Onboarding step** (`SetHomeAddressActivity`): right after picking a role, the user is offered an optional map picker to set their home coordinates — used later by the route planner.

### 👥 Group Management
- Create a new carpool group or join an existing one via a **6-character invite code**.
- **Join requests** flow: requesters appear in the group admin's "Join requests" tab; admins approve, decline, or block.
- **Blocked users list** (parent-only): unblock from a dedicated screen.
- **Group details**: members list with role badges, "You" badge for the viewing user, and each member's saved home address.
- **Leave carpool** flow with smart cascade — `LeaveCarpoolPolicy` evaluates whether the leaver is the last parent with riding children (blocked) or the last member overall (dissolves the carpool and cleans up join_requests, practices, drive_requests, link_codes, carpool_routes).

### 📅 Practice Scheduling
- User add practices with **date, start/end time, location coordinates** (via the map picker).
- **Schedule** tab shows a weekly view with previous/next-week navigation.
- **Cancel practice** — atomic cascade: marks the practice canceled, automatically transitions any PENDING/APPROVED drive requests for that practice to CANCELED, surfaces a "Practice canceled" banner everywhere the practice appears.

### 🚙 Carpool Management — Drive Requests
- **Two directions per practice**: TO (pickup) and FROM (dropoff). Each direction has at most one assigned driver.
- **"Carpool Request"** (child or parent) — broadcast that a slot needs filling.
- **"I'll drive"** (parent only, self-declare) — claim the slot without going through a request.
- **Approve / Decline / Cancel** flows for drive requests with atomic Firestore transactions so the practice's `driverToUid` / `driverFromUid` and the request status never get out of sync.
- **Past-practice filtering** — picker dialog only shows future practices.

### 🛰️ Shared Realtime Features
- Every collaborative screen uses a Firestore `addSnapshotListener` so updates from one device propagate to all group members within ~1 second:
  - Practice schedule
  - Drive request board
  - Join requests + blocked users
  - **Carpool route** (live polyline updates across devices)
- Listeners are scoped per-screen and removed in `onDestroyView` to avoid leaks.

### 🎨 UI / UX Features
- Dark theme throughout (Material Components dark surfaces).
- **Dark-styled Google Map** (`raw/map_style_dark.json`) so the route screen matches the app palette.
- Animated camera framing, staggered marker drop-in, row-tap-to-focus, and a "Generated 2 minutes ago" relative timestamp on the route summary card.
- **"My Address" home banner** that switches between *"Set your home address"* and *"Home: <reverse-geocoded address>"* with a single-tap Edit action.

### 🔥 Firebase Integration
- **Auth**: FirebaseUI email-and-password sign-in.
- **Firestore**: 7 collections (see [Firebase Usage](#-firebase-usage)).
- **Security rules** (`firestore.rules`): per-collection, role-aware, with composite checks like *"only the assigned driver for this direction can write the route document"* and *"any group member may arrayRemove themselves from `participantUids`"*.
- **Indexes** (`firestore.indexes.json`) for composite-where queries.

### 🛡️ Stability & Lifecycle Handling
- **View Binding** (`_binding` nullable + cleared in `onDestroyView`) on every Fragment.
- All Firestore `ListenerRegistration`s, OkHttp `Call`s, and `ValueAnimator`s are tracked and cancelled in `onDestroyView` — so a fast back-press during a route generation or map render doesn't leak or crash.
- Defensive `if (_binding == null) return` guards in every async callback.

---

## Shared Carpool Route Planning

The flagship feature. When a parent claims TO or FROM driver for a practice, the app can generate an optimized multi-stop route, persist it to Firestore, and stream it live to every group member.

### How it works

```
Driver home ──► kid 1 home ──► kid 2 home ──► practice
   (origin)      (stop 1)        (stop 2)     (destination)
```

```
┌──────────────────────────────────────────────────────────────┐
│  CarpoolRouteRepository.generateAndSave(practiceId, direction)│
└────────────────────────┬─────────────────────────────────────┘
                         │
        1. Load Practice (Firestore)
        2. Validate: not canceled, has coords, driver matches
        3. Load passenger UserProfiles → partition:
              routed (has home coords) vs missing
        4. Cluster-then-route:                           ◄── post-MVP enhancement
              partition by Haversine distance from practice
              outliers pinned to home-adjacent end of route
        5. Greedy nearest-neighbor + tail 2-opt swap
        6. OSRM HTTP request (router.project-osrm.org)
        7. ETA math (depart-by + per-stop ETA + return time)
        8. Persist `carpool_routes/{practiceId}_{direction}`
        9. Firestore listener fires on every device → live update
```

### What it computes

| Output | How |
|---|---|
| **Visit order** | Greedy nearest-neighbor with a tail 2-opt swap. Pre-pass clusters far-flung riders to the home-adjacent end of the route so close-cluster kids aren't stranded in the car during a long detour. |
| **Polyline geometry** | OSRM's encoded polyline (precision 5), decoded client-side. |
| **Per-leg distance + duration** | OSRM legs array. |
| **Departure time** | `practice_start − Σ(legs) − Σ(dwell) − buffer` (5-min default buffer). |
| **Per-stop ETA** | Cumulative leg sum + 60s dwell per intermediate stop. |
| **Return time** | For DROPOFF direction. |
| **Missing-address warning** | Riders without coords are listed by display name on the route card; the route generator never blocks because of them. |

### Live realtime behavior

- Two phones open the same practice → both tap **View pickup route**.
- Driver taps **Generate route** → doc written → child's phone updates in ~1 s.
- Child leaves the practice → both phones surface an orange banner: *"Riders changed — Regenerate"*.
- Parent re-picks the practice location on the map → both phones see *"Practice location changed — Regenerate"*.

### Route screen UI

- Top toolbar with screen title (Pickup / Dropoff).
- Optional **state banner** below the toolbar for canceled-practice / no-driver / stale / failed states.
- **Google Map** (top half) with decoded polyline, numbered stop pins, azure origin marker, green destination marker, animated camera fit-to-bounds.
- **Summary card** overlaid on the map's top edge: "Leave by HH:MM" + total km / min + missing-addresses warning + relative "Generated N ago".
- **Stops list** (bottom panel) — tapping a row animates the camera to that stop and opens its info window.
- **Driver-only action row**: Regenerate.

---

## 📱 Screens / User Flow

```
Splash → Login → ChooseRole → SetHomeAddress (optional)
                                  │
                                  ▼
                          CreateJoinGroup ◄──────────────► TeamsList
                                  │                          │
                                  ▼                          ▼
                          GroupDashboardActivity (hosts the bottom-nav fragments)
                                  │
   ┌──────────────┬───────────────┼────────────────────┬────────────────┐
   ▼              ▼               ▼                    ▼                ▼
 Home          Schedule         Group               Drive            Group
 (Dashboard)   (weekly)         (hub)              Requests         Details
   │              │              │                    │
   │              ▼              ├─ JoinRequests      ▼
   │      AddPractice            ├─ BlockedUsers   "I'll drive" /
   │      PracticeDetail         └─ InviteCode      "Carpool Request"
   │              │
   │              ▼
   │      CarpoolRouteFragment ◄── flagship route screen
   │
   ├─ "My Address" banner ──► MapPickerActivity (long-press, address search, reverse geocode)
   ├─ Today's Practice card
   └─ My Requests list - join to carpool + status (Pending / Approved / Declined).
```

### Major activities

| Activity | Purpose |
|---|---|
| `SplashScreenActivity` | Lottie splash + auth/profile bootstrap. |
| `LoginActivity` | FirebaseUI sign-in entry point. |
| `ChooseRoleActivity` | Pick PARENT or CHILD on first launch. |
| `SetHomeAddressActivity` | Optional onboarding home-address picker. |
| `CreateJoinGroupActivity` | Create a new group or join via invite code. |
| `TeamsListActivity` | Switch between carpools the user belongs to. |
| `GroupDashboardActivity` | Hosts the bottom-navigation fragments (Home / Schedule / Group / Drive). |
| `MapPickerActivity` | Long-press to drop a pin; address search + reverse geocode. |

### Major fragments

| Fragment | What it shows |
|---|---|
| `DashboardHomeFragment` | Greeting, "My Address" banner, today's practice card, my teams list, leave-carpool action, sign out. |
| `ScheduleFragment` | Weekly practice list with previous/next-week navigation. |
| `AddPracticeFragment` | Parent/Child: create a practice with date/time/coords. |
| `PracticeDetailFragment` | View / edit a practice; join/leave (child); take/give-up driver slots; open route screen. |
| `GroupFragment` | Group hub (group details, invite, join requests, blocked users). |
| `GroupDetailsFragment` | Members list with role + home address. |
| `JoinRequestsFragment` | Approve / decline / block pending requests. |
| `BlockedUsersFragment` | Unblock previously-blocked users (parent-only). |
| `InviteCodeFragment` | Show + regenerate the group's invite code (only parent can regenerate invite code). |
| `DriversRequestsFragment` | List + act on TO/FROM drive requests. |
| `CarpoolRouteFragment` | The route screen — map, polyline, summary card, stops list. |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Activities + Fragments  (UI layer, ViewBinding)                │
│  ────────────────────────────────────────────────                │
│  Bind views • collect user input • observe LiveData/listeners   │
└─────────────────────────────┬───────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────┐
│  Adapters (ui/*)            • RecyclerView.Adapter (plain)       │
│                             • per-screen view-model lite         │
└─────────────────────────────┬───────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────┐
│  Repositories (data/*)      • Thin facades over FirestoreManager │
│                             • CarpoolRouteRepository orchestrates│
│                               the routing pipeline               │
│                             • LeaveCarpoolPolicy is the only     │
│                               business-logic-rich class          │
└────────────┬──────────────────────────────┬─────────────────────┘
             │                              │
┌────────────▼──────────────┐  ┌────────────▼────────────────────┐
│  FirestoreManager         │  │  routing/                        │
│  (singleton)              │  │  • OsrmClient (OkHttp + org.json)│
│  All Firestore CRUD,      │  │  • OsrmResult (parsed legs)      │
│  queries, batches,        │  │  • PolylineDecoder               │
│  transactions, listeners  │  │  • RouteOrderHeuristic           │
└────────────┬──────────────┘  │    (greedy + 2-opt + cluster)    │
             │                 │  • EtaCalculator (pure functions)│
             ▼                 └──────────────────────────────────┘
        Firebase Auth
        + Cloud Firestore
        + Firebase Storage
```

### Design conventions

- **Activities + Fragments** — no Jetpack Compose, all XML layouts.
- **ViewBinding** on every layout; `_binding` is nullable and cleared in `onDestroyView`.
- **Callback-based async** — no coroutines anywhere. Matches the instructor-style of the rest of the codebase.
- **`org.json` parsing** — no Moshi or Gson dependency for the OSRM payload.
- **Plain `RecyclerView.Adapter`** — only `GroupMembersAdapter` uses `ListAdapter`/`DiffUtil`; the rest follow the simpler explicit `notifyItemRange*` pattern.
- **Manual fragment navigation** via `parentFragmentManager.commit { replace(...) ; addToBackStack(...) }` — matches the existing pattern, no Navigation Component.

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | **Kotlin** |
| Platform | **Android** (`minSdk 24`, `target/compileSdk 34`) |
| UI | Activities + Fragments, **XML layouts only**, **View Binding** |
| Layout | `ConstraintLayout`, `LinearLayout`, `MaterialCardView` |
| Lists | `RecyclerView` |
| Theming | **Material Components** (dark theme), custom dimens/text styles |
| Maps | **Google Maps Android SDK** (`com.google.android.gms:play-services-maps`) |
| Routing | **OSRM public demo** (`router.project-osrm.org`) over OkHttp |
| HTTP | **OkHttp 4.12** |
| JSON | **`org.json`** (built-in, no extra dependency) |
| Backend | **Firebase Authentication** (FirebaseUI 9.0) |
| Database | **Cloud Firestore** (Firebase BOM 33.7) |
| Files | Firebase Storage |
| Animation | **Lottie** (splash) + `ValueAnimator` (marker drop-in) |
| Image loading | **Glide 4.16** |
| Build | Gradle (Kotlin DSL) + `google-services` plugin |

---

## 🔥 Firebase Usage

### Collections

| Collection | Doc shape (key fields) | Purpose |
|---|---|---|
| `users` | `uid, displayName, email, role, homeLat, homeLng, homeAddressLabel, parentUids, childUids` | Profile + role + home coords. |
| `groups` | `name, inviteCode, memberIds, createdBy, blockedUids, inactive` | A carpool group ("team"). |
| `join_requests` | `groupId, requesterUid, status` | Approval workflow for new members. |
| `link_codes` | `groupId, creatorUid, creatorRole, createdAt` | Short-lived (15 min) parent-child link codes. |
| `practices` | `groupId, dateMillis, startTime, endTime, location, locationLat, locationLng, driverToUid, driverFromUid, participantUids, canceled, …` | A single practice occurrence. |
| `drive_requests` | `groupId, practiceId, direction, requesterUid, status, acceptedByUid, declinedByUid` | A ride request (TO / FROM, PENDING / APPROVED / DECLINED / CANCELED). |
| `carpool_routes` | `id = {practiceId}_{direction}, driverUid, stops[], polyline, totalDurationSec, totalDistanceMeters, recommendedDepartureMillis, status, missingAddressUids` | The persisted shared route document. |

### Realtime listeners

- `listenToPracticesForWeek` — schedule tab.
- `listenToDriveRequestsForGroup` — drive requests board.
- `listenToJoinRequestsForGroup` / `listenToJoinRequestsForUser` — parent & child users views.
- `listenToGroup` — blocked-users / member changes.
- `listenToPractice` — route screen, for stale detection.
- `listenToCarpoolRoute` — route screen, for the live polyline.

Every listener registration is held in a `ListenerRegistration?` field and removed in `onDestroyView` / `onDestroy`.

### Security rules

Defined in `firestore.rules`:

- **users** — read for any authenticated user; write only own document.
- **groups** — read/create for any auth; update for members; delete only when last member leaves.
- **join_requests** — read for the requester OR any group member; create with `requesterUid == auth.uid` and `status == PENDING`; update/delete by any group member.
- **practices** — full CRUD for group members.
- **drive_requests** — read/create/delete for group members; update for PARENT (any), CHILD (their own), or any member when cascading a practice cancel.
- **carpool_routes** — read for any group member; create/update/delete only for the assigned driver of that direction.

### Indexes

`firestore.indexes.json` declares composite indexes for the practice-by-week queries and drive-request filters. Deploy with `firebase deploy --only firestore:indexes`.

---

## 🗺️ Maps & Routing

### Why Google Maps SDK + OSRM?

| Concern | Why this split |
|---|---|
| **Rendering** | Google Maps Android SDK is the platform default and offers great Android integration (tile cache, gestures, marker layer, info windows, dark style). |
| **Routing** | OSRM is **free, no API key, no quota anxiety**, and returns a well-documented polyline + per-leg breakdown. Google Directions would have worked but introduces billing concerns for a student project. |
| **Costs** | Maps SDK is free under Google's basemap quota; OSRM is free up to a fair-use limit. |

### Where each one lives

```
GoogleMap         → tile rendering, markers, polyline drawing, camera
                    Used in: MapPickerActivity, CarpoolRouteFragment

OSRM HTTP         → driving-directions geometry + leg metadata
                    Used in: CarpoolRouteRepository.runOsrmAndPersist
                    Wrapped by: routing/OsrmClient.kt
```

### Routing pipeline highlights (`routing/`)

- **`OsrmClient`** — OkHttp request to `https://router.project-osrm.org/route/v1/driving/{coords}?overview=full&steps=false&geometries=polyline`, parsed with `org.json`, callback hopped back to the main thread with `Handler(Looper.getMainLooper())`.
- **`PolylineDecoder`** — faithful port of Google's encoded polyline algorithm (precision 5).
- **`RouteOrderHeuristic`** — greedy nearest-neighbor + tail 2-opt swap + cluster-then-route outlier pinning. All distance metrics are Haversine; OSRM is consulted only after the order is decided.
- **`EtaCalculator`** — pure functions: `recommendedDeparture`, `etaPerStop`, `returnTime`.

### Marker / polyline render

- Stop markers are drawn at runtime by `NumberedMarkerFactory` — a `Bitmap` with a filled blue circle and the stop number, returned as a `BitmapDescriptor`.
- Polyline color matches the numbered chip color (`@color/button_primary_blue`) so the map and the stops list line up visually.
- The map applies a dark theme via `MapStyleOptions.loadRawResourceStyle(R.raw.map_style_dark)`.

---

## 🎨 UX & Design Considerations

### Responsive XML layouts
- Every screen uses `ConstraintLayout` with `0dp + start/end` constraints instead of fixed widths.
- Spacing literals (`8dp`, `12dp`, `16dp`, `24dp`, `32dp`) are all routed through the project's `@dimen/spacing_*` constants.
- Text sizes are centralized in `dimens.xml` (`text_caption_size`, `text_section_label_size`, `text_card_time_size`, etc.).

### Lifecycle handling
- `_binding` nullable + cleared in `onDestroyView`.
- `ListenerRegistration?` removed in `onDestroyView`.
- `okhttp3.Call?` cancelled in `onDestroyView` (debug route in PracticeDetailFragment, future OSRM calls in route screen).
- `ValueAnimator`s in `markerAnimators` cancelled in both `clearMap()` and `onDestroyView`.

### Dark theme
- Dark surfaces app-wide (`@color/dashboard_background = #121212`, `@color/dashboard_card_background = #1E1E1E`).
- Custom dark map style JSON to match.

### Loading + error states
- Indeterminate `ProgressBar` overlaid on the route screen during generation.
- Snackbar feedback on every async action (join, leave, generate, save).
- The route screen has a 6-state machine: `PRACTICE_CANCELED → DRIVER_CANCELED → NO_DRIVER → NO_ROUTE_DOC → EMPTY_ROSTER → FAILED → READY (+ STALE banner overlay)`.

### Animations / polish
- Camera animates to fitted bounds on first render.
- Numbered map markers fade in with a staggered drop animation.
- Tapping a row in the stops list pans + zooms the camera to that stop and pops its info window.
- "Generated 2 minutes ago" relative-time line on the summary card via `DateUtils.getRelativeTimeSpanString`.

---

## ⚙️ Challenges & Engineering Decisions

| Challenge | Resolution |
|---|---|
| **Firestore realtime sync across devices** | One `ListenerRegistration` per screen; every listener guards with `if (_binding == null) return` and is removed in `onDestroyView`. The route screen subscribes to **both** the practice doc (for stale detection) and the route doc (for the polyline) — two listeners, two cleanup calls. |
| **Mixed Hebrew/English text flipping rows** | App layout locked LTR + per-string `BidiFormatter.unicodeWrap` wrapping + per-TextView `textDirection="ltr"` on user-content fields. Documented in `utilities/BidiUtils.kt`. |
| **TSP for n ≤ 6 stops, but human-friendly** | Pure greedy nearest-neighbor strands an outlier rider in the middle of the route. A cluster-then-route pre-pass partitions stops by Haversine distance from the practice (≥3× gap) and pins outliers to the home-adjacent end — so a kid living far from the carpool's general area always rides alone with the driver, not with everyone else. |
| **Map screen lifecycle** | The `SupportMapFragment` is hosted via `<androidx.fragment.app.FragmentContainerView>`; the parent fragment's binding is cleared in `onDestroyView`, the map cleared, the listener removed, and any in-flight `ValueAnimator`s cancelled before the view is detached. |
| **OSRM public-demo rate limits** | The route screen never auto-regenerates; generation is always user-triggered ("Generate" / "Regenerate"). Stale detection only shows a banner — it never silently re-fetches. |
| **Atomic state across two Firestore writes** | Two cases use `WriteBatch` or `Transaction`: (1) approving a drive request also `arrayUnion`s the requester to `practice.participantUids` and flips the request status to APPROVED; (2) leaving a practice both `arrayRemove`s the rider and atomically cancels any APPROVED drive_request they had for that practice. Partial state is unacceptable in either case. |
| **Read-only address field that's still bidi-safe** | The practice location text field shows a reverse-geocoded address that can be Hebrew. The field is locked to read-only (`focusable=false`, `inputType=none`) and the text passes through `bidiSafe()` before being set. |
| **APPROVE button clipping on narrow phones** | The three join-request action buttons (`Approve` / `Decline` / `Block`) share a row with `layout_weight=1` each. Material's default 16dp internal padding + 4dp outer insets squeezed out the last letter of `APPROVE`. Reducing `join_request_action_text_size` to 9sp solved it cleanly. |
| **AppCompat dialog ignoring `textAppearanceListItem`** | A theme overlay on `AlertDialog.Builder(ctx, theme)` doesn't propagate the list-item text appearance reliably on AppCompat. Switched to a custom `ArrayAdapter` that applies the style per-row in `getView()`. |

---

## 📊 Project Status

- ✅ **Complete (MVP)**: Auth, role selection, group management, schedule, drive requests, practice cancel cascade, leave carpool flows, map picker, home-address onboarding, route generation + persistence, live route screen, multi-state empty/error handling, stale detection, RTL/BiDi hardening, UI polish.
---

## 🧰 Setup Instructions

### Prerequisites

- **Android Studio** (Hedgehog or newer recommended) with Android SDK 34.
- **JDK 17** (bundled with Android Studio's JBR is fine).
- A **Firebase project** you can edit (or the course's project).
- A **Google Maps Android API key** restricted to your debug SHA-1 fingerprint and the package `dev.segev.carpoolkids`.

### 1. Clone

```bash
git clone https://github.com/segev67/CarpoolKids-Android-Project.git
cd CarpoolKids-Android-Project
```

### 2. Firebase

1. In the Firebase Console, register an Android app with package `dev.segev.carpoolkids`.
2. Download **`google-services.json`** and place it at `app/google-services.json`.
3. Enable **Authentication** → Email/Password.
4. Enable **Firestore** (Native mode).
5. Deploy the security rules and indexes:
   ```bash
   firebase deploy --only firestore:rules,firestore:indexes
   ```

### 3. Maps API key

1. In the Google Cloud Console (same project as Firebase), enable **Maps SDK for Android**.
2. Create an Android-restricted API key: debug SHA-1 + package `dev.segev.carpoolkids`.
3. Add it to **`local.properties`** (gitignored — never commit):
   ```properties
   MAPS_API_KEY=YOUR_KEY_HERE
   ```

### 4. Run

- Open the project in Android Studio.
- Wait for Gradle sync to complete.
- Select a device (API 24+) and **Run ▶ app**.

### Optional: command-line build

```bash
./gradlew :app:assembleDebug
```

---

## 📚 Documentation

In-repo design docs (under `docs/`):

- `CARPOOL_ROUTE_PLAN.md` — the full design + phasing plan for the route feature.
- `DRIVE_REQUESTS_DESIGN.md` — drive-request lifecycle.
- `FIRESTORE_RULES_*.md` — per-collection rule reasoning.
- `CANCEL_PRACTICE_PHASE*.md` — cancel cascade design notes.
- `LEAVE_CARPOOL_PHASE_1.md` — leave-carpool policy.

Project presentation slides: see the companion repo / folder *CarpookKids-presentation*.

---

## 📦 Credits / Libraries

| Library | Use |
|---|---|
| [Firebase BOM 33.7](https://firebase.google.com/) | Auth, Firestore, Storage, Analytics |
| [FirebaseUI Auth 9.0](https://github.com/firebase/FirebaseUI-Android) | Sign-in screen |
| [Google Play Services Maps 18.2](https://developers.google.com/maps/documentation/android-sdk) | Map rendering |
| [OSRM public demo](http://project-osrm.org/) | Routing geometry + leg metadata |
| [OkHttp 4.12](https://square.github.io/okhttp/) | OSRM HTTP client |
| [Material Components 1.11](https://github.com/material-components/material-components-android) | Buttons, cards, chips, dialogs |
| [Lottie 6.6](https://github.com/airbnb/lottie-android) | Splash animation |
| [Glide 4.16](https://github.com/bumptech/glide) | Image loading |

---

## 👤 Author

**Segev Dadi** — Afeka College.

---
