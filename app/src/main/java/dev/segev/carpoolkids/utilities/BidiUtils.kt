package dev.segev.carpoolkids.utilities

import androidx.core.text.BidiFormatter

/**
 * Wrap user-controlled text (names, addresses, anything that may contain Hebrew or Arabic) with
 * Unicode bidi isolation marks so an embedded RTL chunk can't flip the surrounding English
 * format string. Pairs with `android:supportsRtl="false"` in the manifest, which locks the layout
 * direction to LTR; this helper is the per-string companion that protects mixed-content cases.
 *
 * Returns the original string when null or empty — no isolation marks are added in that case,
 * so we avoid littering empty fields with invisible characters.
 */
fun bidiSafe(text: String?): String =
    if (text.isNullOrEmpty()) text.orEmpty() else BidiFormatter.getInstance().unicodeWrap(text)
