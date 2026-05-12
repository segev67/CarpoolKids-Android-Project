# CarpoolKids

Android app for coordinating rides to sports practices within a trusted group of **parents** and **children**.

## Overview

CarpoolKids helps families organize who drives to and from practice: schedule occurrences, request or offer rides, and manage group membership—without scattered chats or spreadsheets.

**Problem it solves:** Clear, shared visibility of practices and transport needs for a youth sports carpool.

**Target users:** Parents (organize, approve rides) and children (request rides, see assignments).

**Context:** Academic project — Afeka College.

---

## Features

- User profiles with **parent** / **child** roles  
- **Teams / groups:** create a carpool or join with an invite code  
- **Schedule:** practices (date, time, location); add or edit occurrences  
- **Drive requests:** broadcast need for a ride (TO/FROM practice); parents accept or decline  
- **Practice cancel:** parents can cancel a practice; related drive requests close  
- **Join requests** and **blocked users** for group admins  
- Real-time updates via Firestore listeners where applicable  

---

## Screens / UI

| Area | Description |
|------|-------------|
| **Dashboard** | Home hub for the selected group: navigation to schedule, drive requests, group settings |
| **Teams list** | List of carpool groups; switch group or open one |
| **Create / Join group** | Create a new group or enter an invite code to request membership |
| **Requests** | Join requests for the group; drive requests tab lists open and resolved ride requests |

<!-- Screenshots (optional): -->
<!-- ![Dashboard](docs/images/dashboard.png) -->
<!-- ![Schedule](docs/images/schedule.png) -->
<!-- ![Drive requests](docs/images/drive_requests.png) -->

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Language | **Kotlin** |
| Platform | **Android** (minSdk 24, target/compile 34) |
| UI | Activities, Fragments, **View Binding** |
| Backend | **Firebase Authentication** |
| Database | **Cloud Firestore** |
| Files | **Firebase Storage** (where used for profile/media) |
| Build | Gradle (Kotlin DSL), Google Services plugin |

---

## Architecture

The project follows a **layered, instructor-style** layout: clear packages, thin UI, and shared access to Firebase through dedicated types.

- **Activities / Fragments** — UI flow, user input, navigation; avoid embedding Firestore calls directly where a repository exists  
- **Repositories** — Thin facades (e.g. `PracticeRepository`, `UserRepository`) delegating to the manager  
- **`FirestoreManager`** — Central Firestore access: CRUD, queries, batch writes, listeners  
- **Models** — Data classes (`UserProfile`, `Group`, `Practice`, `DriveRequest`, …)  
- **Adapters** — `RecyclerView` adapters for lists (e.g. drive requests, schedules)  
- **Utilities** — Constants, helpers, Firebase initialization  

**Separation of concerns:** UI → repository → manager → Firestore; models stay decoupled from Android UI details.

---

## Firestore structure (high level)

| Collection | Purpose |
|------------|---------|
| `users` | Profiles, roles (parent/child), links between parents and children |
| `groups` | Carpools: members, metadata (often referred to as teams in the UI) |
| `join_requests` | Pending requests to join a group |
| `practices` | Scheduled practice occurrences per group (time, location, drivers, cancel flags) |
| `drive_requests` | Ride requests (direction, status: pending / approved / declined / canceled) |
| `link_codes` | Short-lived invite codes tied to a group |

Additional fields may exist (e.g. blocked users on groups); see `Constants.Firestore` and `docs/` for details.

---

## Setup instructions

1. **Clone** the repository  
   ```bash
   git clone <repository-url>
   cd <project-folder>
   ```

2. **Open** the project in **Android Studio** (Arctic Fox or newer recommended).

3. **Firebase**  
   - Create a Firebase project (or use the course project).  
   - Add an Android app with package `dev.segev.carpoolkids`.  
   - Download **`google-services.json`** and place it in:  
     `app/google-services.json`  
   - Enable **Authentication** (e.g. Email/Password as configured for the app).  
   - Enable **Firestore** and **Storage** if your build uses them.

4. **Run**  
   - Select a device or emulator (API 24+).  
   - **Run** → Run `app`.

> Do **not** commit `google-services.json` if the repository is public; use a private template or CI secrets.

---

## Firebase configuration

- **Indexes** — Composite indexes for Firestore queries are defined in **`firestore.indexes.json`**. Deploy with:  
  `firebase deploy --only firestore:indexes`  
- **Security rules** — **`firestore.rules`** defines read/write access (users, groups, practices, drive requests, etc.). Deploy with:  
  `firebase deploy --only firestore:rules`  

See `docs/FIRESTORE_RULES_DRIVE_REQUESTS.md` and related docs under `docs/` for rule behavior.

---

## Future improvements

- **Push notifications** (FCM) for practice changes, drive request updates, join approvals  
- **Richer roles / admin** (e.g. dedicated coach or group admin)  
- **UX polish:** onboarding, empty states, accessibility  
- **Optional:** maps integration, recurring practices, analytics  

---

## Author

**[Segev Dadi]** — Afeka College  

---

## License

*Specify if applicable (e.g. educational use only — check with your instructor).*
