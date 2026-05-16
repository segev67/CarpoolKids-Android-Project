package dev.segev.carpoolkids.routing

import com.google.android.gms.maps.model.LatLng

/**
 * Decoder for Google's encoded-polyline format (precision 5 by default — the OSRM demo's output).
 *
 * Algorithm: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 * The math is sensitive to bit-shift order and the zig-zag transform, so this is a faithful copy of
 * the reference implementation rather than a "creative" rewrite.
 */
object PolylineDecoder {
    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val factor = Math.pow(10.0, precision.toDouble())
        val out = mutableListOf<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        val len = encoded.length
        while (index < len) {
            lat += readVarint(encoded, index).also { index = it.nextIndex }.value
            lng += readVarint(encoded, index).also { index = it.nextIndex }.value
            out += LatLng(lat / factor, lng / factor)
        }
        return out
    }

    private data class Varint(val value: Int, val nextIndex: Int)

    private fun readVarint(s: String, startIndex: Int): Varint {
        var idx = startIndex
        var shift = 0
        var raw = 0
        var b: Int
        do {
            b = s[idx++].code - 63
            raw = raw or ((b and 0x1F) shl shift)
            shift += 5
        } while (b >= 0x20)
        // Zig-zag decode: odd → negative, even → positive.
        val value = if ((raw and 1) != 0) (raw shr 1).inv() else raw shr 1
        return Varint(value, idx)
    }
}
