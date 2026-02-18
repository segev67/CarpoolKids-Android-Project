package dev.segev.carpoolkids.utilities

class Constants {
    object Timer {
        const val DELAY: Long = 1_000L
    }

    object TextLengths {
        const val MIN_LINES: Int = 1
    }

    object Firestore {
        const val COLLECTION_USERS: String = "users"
        const val COLLECTION_GROUPS: String = "groups"
        const val COLLECTION_LINK_CODES: String = "link_codes"
        const val COLLECTION_JOIN_REQUESTS: String = "join_requests"
    }

    object LinkCode {
        const val EXPIRY_MS: Long = 15 * 60 * 1000L
    }

    object UserRole {
        const val PARENT: String = "PARENT"
        const val CHILD: String = "CHILD"
    }

    object Storage {
        const val PROFILE_IMAGES_PATH: String = "profile_images"
    }
}
