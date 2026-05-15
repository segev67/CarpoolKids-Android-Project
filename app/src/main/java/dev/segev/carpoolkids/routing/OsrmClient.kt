package dev.segev.carpoolkids.routing

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp wrapper around the OSRM public-demo routing service.
 *
 * Callers pass an ordered list of coordinates (first = origin, last = destination, anything between
 * = intermediate stops) and receive back the encoded polyline, per-leg durations and distances, and
 * route totals. The callback always runs on the main thread.
 *
 * The returned [Call] reference lets the fragment cancel an in-flight request in onDestroyView so
 * we don't leak a dangling callback into a torn-down view tree.
 */
object OsrmClient {
    private const val TAG = "OsrmClient"
    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving/"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetchRoute(
        coords: List<LatLng>,
        callback: (OsrmResult?, String?) -> Unit
    ): Call? {
        if (coords.size < 2) {
            mainHandler.post { callback(null, "Need at least 2 coordinates") }
            return null
        }
        // OSRM expects `lng,lat` pairs separated by `;`. Lat/lng order is the opposite of LatLng's.
        val csv = coords.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = "$BASE_URL$csv?overview=full&steps=false&geometries=polyline"
        val request = Request.Builder().url(url).get().build()
        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "OSRM request failed", e)
                mainHandler.post { callback(null, e.message ?: "OSRM request failed") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        mainHandler.post { callback(null, "OSRM HTTP ${resp.code}") }
                        return
                    }
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        mainHandler.post { callback(null, "Empty OSRM response") }
                        return
                    }
                    val parsed = try {
                        parse(body)
                    } catch (e: JSONException) {
                        Log.w(TAG, "OSRM parse error", e)
                        mainHandler.post { callback(null, "OSRM parse error: ${e.message}") }
                        return
                    }
                    mainHandler.post { callback(parsed, null) }
                }
            }
        })
        return call
    }

    private fun parse(body: String): OsrmResult {
        val root = JSONObject(body)
        val code = root.optString("code", "Ok")
        if (code != "Ok") throw JSONException("OSRM code=$code")
        val routes = root.optJSONArray("routes")
            ?: throw JSONException("OSRM response missing routes")
        if (routes.length() == 0) throw JSONException("OSRM returned 0 routes")
        val route = routes.getJSONObject(0)
        val polyline = route.optString("geometry", "")
        val totalDuration = route.optDouble("duration", 0.0).toInt()
        val totalDistance = route.optDouble("distance", 0.0).toInt()
        val legsJson = route.optJSONArray("legs")
        val legs = mutableListOf<OsrmResult.Leg>()
        if (legsJson != null) {
            for (i in 0 until legsJson.length()) {
                val leg = legsJson.getJSONObject(i)
                legs += OsrmResult.Leg(
                    durationSec = leg.optDouble("duration", 0.0).toInt(),
                    distanceMeters = leg.optDouble("distance", 0.0).toInt()
                )
            }
        }
        return OsrmResult(
            polyline = polyline,
            totalDurationSec = totalDuration,
            totalDistanceMeters = totalDistance,
            legs = legs
        )
    }
}
