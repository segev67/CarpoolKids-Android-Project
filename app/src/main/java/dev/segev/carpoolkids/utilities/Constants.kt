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
    }

    object Storage {
        const val PROFILE_IMAGES_PATH: String = "profile_images"
    }
}
