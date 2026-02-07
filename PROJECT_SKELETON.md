# Instructor Repos Analysis & CarpoolKids Project Skeleton

## Part 1 — Instructor Repositories Summary

### 1. Package structure

- **Root**: `dev.tomco.<project_id>` (e.g. `dev.tomco.a26a_10208_l08`).
- **Subpackages** (when used):
  - `adapters/` — RecyclerView adapters (L08).
  - `interfaces/` — callback/listener interfaces (L06, L07, L08).
  - `model/` — data classes + optional `DataManager` (L05, L08).
  - `ui/` — fragments only (L06); activities stay in root.
  - `utilities/` — Constants, singletons (ImageLoader, SignalManager, TimeFormatter, etc.).

**CarpoolKids equivalent**: Use a single app package (e.g. `dev.tomco.carpoolkids`) with the same subpackages: `adapters`, `interfaces`, `model`, `ui`, `utilities`. Add a package for Firebase (e.g. `firebase` or keep Firestore/Auth access in utilities/managers).

---

### 2. Naming conventions

- **Activities**: `*Activity` (e.g. `MainActivity`, `SplashScreenActivity`, `LeaderboardActivity`).
- **Fragments**: `*Fragment` (e.g. `HighScoreFragment`, `MapFragment`).
- **View references**: `screenOrFragment_PREFIX_id` in XML and Kotlin:
  - `main_LBL_*` (label/text), `main_BTN_*` (button), `main_IMG_*`, `main_FAB_*`, `main_FRAME_*`, `main_RV_*` (RecyclerView).
  - Fragment layouts: `highScore_ET_text`, `highScore_BTN_send`, `map_LBL_title`.
  - List item layouts: `movie_LBL_title`, `movie_IMG_poster`, `movie_CV_data` (CardView).
- **Layout files**: `activity_<screen>.xml`, `fragment_<name>.xml`, `<entity>_item.xml` (e.g. `movie_item.xml`).
- **Constants**: `class Constants` with nested `object` (e.g. `Constants.Timer.DELAY`, `Constants.SP_KEYS.PLAYLIST_KEY`, `Constants.TextLengths.GENRES_MIN_LINES`).
- **Interfaces**: `Callback_<Action>` (L06) or `*Callback` (L08) (e.g. `Callback_HighScoreClicked`, `MovieCallback`).

---

### 3. Adapter patterns (L08)

- **Name**: `*Adapter` (e.g. `MovieAdapter`).
- **Constructor**: takes list of model items: `MovieAdapter(private val movies: List<MovieItem>)`.
- **ViewHolder**: inner class holding **ViewBinding** for item layout: `inner class MovieViewHolder(val binding: MovieItemBinding) : RecyclerView.ViewHolder(binding.root)`.
- **Callback**: nullable `var` on adapter: `var movieCallback: MovieCallback? = null`; Activity sets it and implements interface (e.g. `favoriteButtonClicked(movie, position)`).
- **Binding**: `onCreateViewHolder` inflates via `MovieItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)`; `onBindViewHolder` uses `holder.binding.*` (camelCase from ViewBinding).
- **No** ViewHolder `itemView.findViewById`; only binding.

---

### 4. Utility / singleton patterns

- **Constants**: `class Constants` (not object) with nested `object` blocks and `const val`.
- **Stateless helpers**: `object` (e.g. `TimeFormatter`, `DataManager` in L08 for in-memory list generation).
- **Context-dependent singletons**: `class X private constructor(context: Context)` with:
  - `private val contextRef = WeakReference(context)`
  - `companion object { @Volatile private var instance: X? = null; fun init(context: Context): X { ... }; fun getInstance(): X { ... } }`
  - Usage: `X.init(context)` in `Application.onCreate()`, then `X.getInstance()` everywhere.
- **Examples**: `ImageLoader`, `SignalManager`, `SharedPreferencesManagerV3` (L05); `ImageLoader` (L08). L08 `DataManager` is an `object` that only generates in-memory data.

---

### 5. Activity / fragment style

**Activity**:

- Extend `AppCompatActivity`.
- `onCreate`: `enableEdgeToEdge()` → `setContentView` (or `Binding.inflate(layoutInflater)` + `setContentView(binding.root)`) → `ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), ...)` for system bar padding.
- Then `findViews()` (or use binding), then `initViews()`.
- View references: either `lateinit var` + `findViewById` or a single `lateinit var binding: ActivityXxxBinding`.

**Fragment** (L06):

- Extend `Fragment`.
- `onCreateView`: inflate layout, then `findViews(v)`, `initViews(v)`, return `v`.
- Optional: `companion object { var callback: Callback_*? = null }` for cross-fragment communication; Activity sets it before adding fragment.

**Splash** (L08):

- `SplashScreenActivity`: ViewBinding, Lottie animation, `AnimatorListener.onAnimationEnd` → `startActivity(Intent(this, MainActivity::class.java)); finish()`.

---

### 6. Layout style

- Root often has `android:id="@+id/main"`.
- Use Material components: `MaterialTextView`, `MaterialButton`, `TextInputEditText`, `ExtendedFloatingActionButton`.
- RecyclerView item: `ConstraintLayout` or `ConstraintLayout` + `CardView`; ids like `movie_LBL_*`, `movie_IMG_*`, `movie_CV_*`.
- Dimensions in `res/values/dimens.xml` (e.g. `default_card_margin`, `default_corner_radius`).
- Colors in `res/values/colors.xml` and optional `values-night/colors.xml`.

---

### 7. Firebase usage in instructor repos

- **None** in the scanned repos (L04–L08). No Firebase Auth, Firestore, or Storage.
- CarpoolKids will introduce Firebase; keep the same package/layer style and add a clear place for Auth/Firestore (e.g. utilities or a dedicated `firebase` package) with failure handling and listeners as required.

---

## Part 2 — CarpoolKids Project Skeleton (Instructor-Style)

### Package tree

```
dev.tomco.carpoolkids
├── App.kt
├── SplashScreenActivity.kt
├── MainActivity.kt                    (post-login hub: groups / profile / logout)
├── LoginActivity.kt
├── RegisterActivity.kt
├── GroupDetailActivity.kt             (single group: events, members, ride requests)
├── CreateGroupActivity.kt
├── JoinGroupActivity.kt
├── CreateEventActivity.kt
├── ProfileActivity.kt                 (display name, profile image; change photo → StorageManager → Firestore)
├── adapters
│   ├── GroupAdapter.kt
│   ├── EventAdapter.kt
│   └── RideRequestAdapter.kt
├── interfaces
│   ├── Callback_GroupClicked.kt
│   ├── Callback_EventClicked.kt
│   └── RideRequestCallback.kt
├── model
│   ├── Group.kt
│   ├── Event.kt
│   ├── RideRequest.kt
│   ├── UserProfile.kt
│   └── DataManager.kt                 (optional in-memory helpers; real data from Firestore)
├── ui
│   ├── EventListFragment.kt           (optional: events inside group as fragment)
│   └── RideRequestsFragment.kt        (optional: requests inside group as fragment)
├── utilities
│   ├── Constants.kt
│   ├── SignalManager.kt               (toast / optional vibrate)
│   ├── ImageLoader.kt                 (profile images: load from URL; init in App)
│   ├── TimeFormatter.kt               (event time, duration)
│   ├── AuthManager.kt                 (Firebase Auth: login, register, logout, currentUser)
│   ├── FirestoreManager.kt            (Firestore: groups, events, ride requests; real-time listeners)
│   └── StorageManager.kt              (Firebase Storage: profile image upload; instructor singleton style)
```

- **ApplicationId / namespace**: e.g. `dev.tomco.carpoolkids` (align with your course ID if different).
- **Firebase Storage (required)**: Profile image upload only. One minimal flow: user picks/captures image → StorageManager uploads → download URL saved to Firestore user profile → ImageLoader displays in profile/group UI. No MVVM/Flow/DI; callbacks from StorageManager only.

---

### File list and responsibilities

| File | Responsibility |
|------|----------------|
| **App.kt** | `Application` subclass. In `onCreate`: `ImageLoader.init(this)`, `SignalManager.init(this)`, `StorageManager.init(this)`. Optionally `AuthManager.init(this)` if context-holding. |
| **SplashScreenActivity.kt** | First launcher. Check auth state (AuthManager/Firebase Auth); if logged in → `MainActivity`, else → `LoginActivity`. Use ViewBinding; optional Lottie/simple animation. |
| **MainActivity.kt** | Post-login home: list my groups (RecyclerView + GroupAdapter), FAB or button to create/join group, profile (with profile image via ImageLoader) / logout. Uses ViewBinding or findViews/initViews. |
| **LoginActivity.kt** | Email/password fields, login button; call AuthManager (Firebase Auth); on success → MainActivity; on failure show toast (SignalManager). Handle empty input and errors. |
| **RegisterActivity.kt** | Email, password, display name; register via AuthManager; on success → MainActivity or back to login. Validation + error toasts. Profile image is set later (ProfileActivity). |
| **CreateGroupActivity.kt** | Form: group name, optional invite code generation; save to Firestore via FirestoreManager; then finish or navigate to GroupDetailActivity. |
| **JoinGroupActivity.kt** | Input: invite code; FirestoreManager to find group and add current user as member; then open GroupDetailActivity or MainActivity. |
| **GroupDetailActivity.kt** | Shows one group: title, list of events (RecyclerView + EventAdapter), list of ride requests (RecyclerView + RideRequestAdapter), assign driver / approve actions. Attach Firestore listeners for this group’s events and requests (real-time). Optional: use fragments (EventListFragment, RideRequestsFragment) in frames for consistency with L06. |
| **CreateEventActivity.kt** | Form: title, date/time, location (text); save event to current group via FirestoreManager; finish. |
| **ProfileActivity.kt** | Show current user displayName and profile image (ImageLoader from photoUrl). Button “Change photo”: pick image → StorageManager.uploadProfileImage(uri) → on success FirestoreManager.updateUserPhotoUrl(url) and refresh ImageLoader; on failure SignalManager toast. |
| **GroupAdapter.kt** | RecyclerView adapter for `List<Group>`. Item binding: group name, optional invite code. `Callback_GroupClicked` (e.g. `groupClicked(group: Group)`) for opening GroupDetailActivity. |
| **EventAdapter.kt** | Adapter for `List<Event>`. Item: title, date/time, location. `Callback_EventClicked` for optional navigation or expand. |
| **RideRequestAdapter.kt** | Adapter for `List<RideRequest>`. Item: teen name, from/to or “needs ride”, status; buttons for parent: approve/decline, assign driver. `RideRequestCallback` with methods e.g. `approve(request)`, `decline(request)`, `assignDriver(request, driverId)`. |
| **Callback_GroupClicked.kt** | `interface Callback_GroupClicked { fun groupClicked(group: Group) }` |
| **Callback_EventClicked.kt** | `interface Callback_EventClicked { fun eventClicked(event: Event) }` (optional) |
| **RideRequestCallback.kt** | `interface RideRequestCallback { fun approve(request: RideRequest); fun decline(request: RideRequest); fun assignDriver(request: RideRequest, driverId: String) }` (or similar) |
| **Group.kt** | Data class (optional Builder if needed). Fields: id, name, inviteCode, memberIds, createdBy, etc. Match Firestore document shape. |
| **Event.kt** | Data class: id, groupId, title, dateTime (Long or type you use), location (String). |
| **RideRequest.kt** | Data class: id, eventId, teenId, fromTo or needsRide, status (pending/approved/declined), assignedDriverId, etc. |
| **UserProfile.kt** | Minimal profile: uid, displayName, email, photoUrl (String? = null; optional). Firestore user document may omit photoUrl at registration. UI must handle null by showing a default placeholder image (e.g. R.drawable.unavailable_photo). URL set after StorageManager upload. |
| **DataManager.kt** | Optional: local builders or sample data for tests; main data from FirestoreManager. |
| **Constants.kt** | Nested objects (e.g. `Firestore.COLLECTION_GROUPS`, `Firestore.COLLECTION_EVENTS`, `Firestore.COLLECTION_REQUESTS`, `Firestore.COLLECTION_USERS`, `Storage.PROFILE_IMAGES_PATH`, `Auth.*`, `SP_KEYS.*`). |
| **SignalManager.kt** | Same pattern as L05/L08: init(context), getInstance(), toast(text, length). Use for errors and success messages. |
| **ImageLoader.kt** | Same as L08: init(context), getInstance(), loadImage(url/res, ImageView). When loading profile: if UserProfile.photoUrl is null, use placeholder (loadImage with placeholder or set ImageView to placeholder drawable); otherwise load from URL. |
| **TimeFormatter.kt** | `object`: format timestamp for event list and ride request display. |
| **AuthManager.kt** | Wrapper around Firebase Auth: login, register, logout, currentUser. Try/catch and callbacks; show SignalManager toast on failure. |
| **FirestoreManager.kt** | Firestore: create/read/update groups, events, ride requests, user profiles (photoUrl). Real-time listeners (addSnapshotListener) for groups list, group’s events, group’s ride requests. Callbacks only (no MVVM/Flow); failure listeners → SignalManager. |
| **StorageManager.kt** | **Required.** Firebase Storage singleton: same style as ImageLoader/SignalManager—`class StorageManager private constructor(context: Context)`, `contextRef = WeakReference(context)`, `init(context)` / `getInstance()`. Upload profile image (file or URI) to path e.g. `profile_images/{uid}.jpg`; on success return download URL; on failure callback + SignalManager toast. Caller (ProfileActivity) saves URL to Firestore user document via FirestoreManager. |

---

### Layout files (res/layout)

| Layout | Purpose |
|--------|--------|
| `activity_splash_screen.xml` | Root `id=main`; optional Lottie or logo. |
| `activity_main.xml` | Root `id=main`; RecyclerView `main_RV_groups`, FAB/buttons create group, join group, logout. |
| `activity_login.xml` | main_ET_email, main_ET_password, main_BTN_login, main_BTN_goToRegister. |
| `activity_register.xml` | main_ET_email, main_ET_password, main_ET_displayName, main_BTN_register. |
| `activity_create_group.xml` | main_ET_groupName, main_BTN_create. |
| `activity_join_group.xml` | main_ET_inviteCode, main_BTN_join. |
| `activity_group_detail.xml` | main_LBL_groupName, main_RV_events, main_RV_requests (or FrameLayouts for fragments), main_BTN_createEvent, etc. |
| `activity_create_event.xml` | main_ET_title, date/time picker or fields, main_ET_location, main_BTN_save. |
| `activity_profile.xml` | main_IMG_avatar (profile image), main_LBL_displayName, main_BTN_changePhoto. Root `id=main`. |
| `group_item.xml` | group_LBL_name, group_LBL_inviteCode (optional). |
| `event_item.xml` | event_LBL_title, event_LBL_dateTime, event_LBL_location. |
| `ride_request_item.xml` | request_LBL_teen, request_LBL_fromTo, request_LBL_status, request_BTN_approve, request_BTN_decline, request_BTN_assignDriver (or spinner for driver). |

Use `dimens.xml` and `colors.xml`; optional `values-night` for theme.

---

### Crash prevention and error handling (evidence for grading)

- **Validation**: Non-empty email/password before login/register; non-empty group name, event title, invite code where required.
- **Try/catch**: Around Auth sign-in/register and Firestore writes; show toast and do not finish on failure.
- **Firestore**: Use failure listeners on all critical writes and attach them to listeners; in callback show user-facing message (SignalManager).
- **Null safety**: Use `currentUser` only when non-null before accessing group/event/request data; safe calls in adapters for optional fields.

---

### Firebase Storage (required — hard submission)

- **Profile image upload (minimal flow)**: One dedicated flow only. User opens profile (e.g. **ProfileActivity**), taps “Change photo” → pick from gallery or camera → **StorageManager** uploads to Firebase Storage path `profile_images/{uid}.jpg` → on success StorageManager returns download URL → Activity calls **FirestoreManager** to update current user’s document with `photoUrl` → **ImageLoader** loads that URL in profile and anywhere profile avatar is shown. Use callbacks only; no MVVM/Flow/DI. Add **ProfileActivity** to package tree and `activity_profile.xml` (main_IMG_avatar, main_BTN_changePhoto, main_LBL_displayName).

---

### Optional extra feature (beyond Storage)

- **FCM**: FirebaseMessagingService, store FCM token in Firestore; notifications when ride assigned or request approved (Cloud Functions or client-only as needed).
- **Maps**: Google Maps in CreateEventActivity or event detail for location picker/display; store lat/lng in Event.

---

### Build and dependencies (to add in CarpoolKids)

- **ViewBinding**: `buildFeatures { viewBinding = true }`.
- **Firebase**: `google-services` plugin + `firebase-bom`; **required**: `firebase-auth`, `firebase-firestore`, `firebase-storage`.
- **Material**, **AppCompat**, **ConstraintLayout**, **RecyclerView**, **CardView** (as in L08).
- **Glide**: Required for ImageLoader (profile images from Storage URL).
- **Optional**: Lottie for splash; Play Services Maps if using Maps; FCM if using notifications.

---

## Part 3 — Demo Scenario (for classroom video)

Use **two accounts** (Parent + Teen) and **one shared group** to show **real-time Firestore listeners**.

### Setup before recording

1. **Account 1 — Parent**: Register or log in (e.g. parent@example.com). Create a group, note the **invite code**. Optionally set profile image (ProfileActivity → Change photo) to show Storage.
2. **Account 2 — Teen**: Register or log in (e.g. teen@example.com). Join the same group using the invite code.
3. **Two devices or emulators**: Run the app on Device A (Parent) and Device B (Teen), or use one device and switch accounts after each step and show the other screen via pre-recorded clip.

### Demo flow (narration points)

1. **Same group**: Parent and Teen both see the group in “My groups” (same group id). Explain that Firestore holds the group and both users are members.
2. **Real-time: create event**: On **Parent** device, open the group → Create event (title, date/time, location) → save. Without refresh, on **Teen** device open the same group (or leave it open). **Teen’s list of events updates automatically** (Firestore listener). Narrate: “We’re using Firestore real-time listeners so when the parent adds an event, the teen sees it immediately.”
3. **Real-time: approve ride request**: On **Teen** device, create a ride request for that event (“needs ride” or from/to). On **Parent** device, open the group and the ride requests list. **Parent** approves the request and (if implemented) assigns themselves as driver. **Teen’s screen updates in real time** (request status → approved, driver assigned). Narrate: “When the parent approves the ride request, the teen’s view updates live thanks to the same Firestore listener.”
4. **Profile image (Storage)**: On one account, open Profile → Change photo → pick image. Show that the avatar updates and is stored (Firebase Storage + Firestore photoUrl). Mention: “Profile image is uploaded to Firebase Storage and the download URL is saved in Firestore.”

Keep everything strictly consistent with instructor style; no MVVM/Flow/DI in the narration or code.

---

## Part 4 — Deliverables (after implementation)

### README.md structure

Fill this in after the project is implemented. Suggested sections and content:

1. **App name and short description**  
   One paragraph: CarpoolKids coordinates carpools for kids/teens to sports practices in small groups.

2. **Features**  
   Bullet list: Splash + auth routing; Register/Login/Logout; Create group / Join by invite code / List my groups; Create event (title, date/time, location); Ride requests (teen creates, parent approves/declines, assign driver); Real-time sync (Firestore listeners for groups, events, requests); Profile image upload (Firebase Storage + Firestore photoUrl).

3. **Screenshots to include**  
   - Splash screen  
   - Login screen  
   - Main screen (My groups list)  
   - Group detail (events list + ride requests list)  
   - Create event screen  
   - Ride request item (e.g. pending → approved with driver)  
   - Profile screen (with profile image)  
   - (Optional) Join group (invite code field)

4. **Libraries used**  
   See “Libraries used and why” below; paste the same list into the README.

5. **How to run**  
   Clone repo, open in Android Studio, add `google-services.json` (Firebase project), sync Gradle, run on device/emulator.

6. **Deadline and submission**  
   Public GitHub repo; submission by 01/03/2026 23:59; demo video link if required.

---

### Video checklist / script (screen-by-screen)

Use this for the demo video. Tick off each point while recording.

| # | Screen / action | Narration / points to cover |
|---|-----------------|-----------------------------|
| 1 | Splash | “Splash checks auth: if logged in we go to main, else to login.” |
| 2 | Login | “Email and password; we use Firebase Authentication.” |
| 3 | Register | “Register with email, password, and display name.” |
| 4 | Main (My groups) | “List of groups I’m in. Create group or join with invite code.” |
| 5 | Create group | “Create a group; invite code is generated so others can join.” |
| 6 | Join group | “Join using the code from the parent.” |
| 7 | Group detail | “Inside a group: upcoming events and ride requests. All data is shared and updates in real time via Firestore.” |
| 8 | Create event | “Add an event: title, date/time, location. Saved to Firestore.” |
| 9 | Real-time event (second device) | “On the other account we see the new event appear without refresh—Firestore listener.” |
| 10 | Ride request (Teen) | “Teen creates a ride request for an event.” |
| 11 | Approve / assign driver (Parent) | “Parent approves and assigns a driver.” |
| 12 | Real-time request (Teen) | “Teen’s request list updates live—approved and driver shown.” |
| 13 | Profile + Change photo | “Profile screen: we can upload a profile image. It goes to Firebase Storage; the URL is saved in Firestore and displayed with Glide.” |
| 14 | Special components / libraries | “We use Firebase Auth, Firestore with real-time listeners, and Firebase Storage. UI uses ViewBinding, Material components, RecyclerView and adapters in the same style as the course.” |
| 15 | Challenges / known issues | Mention any known crash scenarios and why (e.g. “If the device is offline, Firestore calls fail; we show a toast and don’t crash”). |

---

### Libraries used and why (fill after implementation)

List every library you add (from `build.gradle.kts` / `libs.versions.toml`) and a one-line reason. Example template:

| Library | Why |
|--------|-----|
| Firebase BOM | Version alignment for Firebase libraries. |
| firebase-auth | Login, register, logout. |
| firebase-firestore | Groups, events, ride requests, user profiles; real-time listeners. |
| firebase-storage | Profile image upload; download URL stored in Firestore. |
| AndroidX Core / AppCompat / Activity | Standard Android UI and lifecycle. |
| Material Components | Buttons, text fields, styling per instructor layouts. |
| ConstraintLayout / RecyclerView / CardView | Layouts and list UIs. |
| ViewBinding | Type-safe view access (instructor L08 style). |
| Glide | Load profile images from Storage URLs (instructor L05/L08 ImageLoader). |
| (Optional) Lottie | Splash animation. |
| (Optional) Play Services Maps | If Maps extra feature used. |
| (Optional) firebase-messaging | If FCM extra feature used. |

---

## Summary

- **Package**: `dev.tomco.carpoolkids` with `adapters`, `interfaces`, `model`, `ui`, `utilities`. Activities (including ProfileActivity) in root; fragments in `ui/` if used.
- **Naming**: Instructor-style view ids (`main_LBL_*`, `main_BTN_*`, `*_item.xml`), `*Activity`/`*Fragment`, `Callback_*` / `*Callback`, `Constants` with nested objects, context singletons with `init`/`getInstance()`.
- **Patterns**: Activities with findViews/initViews or ViewBinding; optional fragments in `ui/` with callback set by Activity; adapters with ViewBinding and nullable callback; model data classes (Builder optional); Firestore/Auth/Storage in dedicated managers with callbacks and error handling (no MVVM/Flow/DI).
- **Firebase**: Auth + Firestore (required) + **Firebase Storage (required)** — profile image upload via StorageManager; ProfileActivity for “Change photo” flow.
- **No MVVM/Flow/DI**: Only patterns from instructor repos (L04–L08); callbacks from managers to activities.
- **Deliverables**: README (structure and screenshot list above), demo video (checklist/script above), and libraries table filled after implementation.

Implement step by step (project setup → Splash + Auth → Groups → Events → Ride requests → real-time sync → Profile + Storage), compiling after each step.
