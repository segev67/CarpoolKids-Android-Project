package dev.segev.carpoolkids

import android.app.Activity
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.snackbar.Snackbar
import dev.segev.carpoolkids.databinding.ActivityMapPickerBinding
import java.util.Locale

/**
 * Pick a coordinate on a map. Long-press anywhere to drop / move a pin; tap Confirm to return.
 *
 * Optional input extras:
 *   [EXTRA_INITIAL_LAT], [EXTRA_INITIAL_LNG] — center + pre-place pin (skipped if either is NaN)
 *   [EXTRA_TITLE] — replaces the default title text
 *
 * On RESULT_OK the returned Intent carries [EXTRA_RESULT_LAT] and [EXTRA_RESULT_LNG] (Double).
 */
class MapPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMapPickerBinding
    private var googleMap: GoogleMap? = null
    private var marker: Marker? = null
    private var initialLat: Double = DEFAULT_LAT
    private var initialLng: Double = DEFAULT_LNG
    private var hasInitialPin: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let {
            binding.mapPickerTitle.text = it
        }

        val argLat = intent.getDoubleExtra(EXTRA_INITIAL_LAT, Double.NaN)
        val argLng = intent.getDoubleExtra(EXTRA_INITIAL_LNG, Double.NaN)
        if (!argLat.isNaN() && !argLng.isNaN()) {
            initialLat = argLat
            initialLng = argLng
            hasInitialPin = true
        }

        binding.mapPickerCancel.setOnClickListener { finish() }
        binding.mapPickerConfirm.setOnClickListener { confirm() }
        binding.mapPickerConfirm.isEnabled = hasInitialPin

        binding.mapPickerSearchButton.setOnClickListener {
            performGeocodeSearch(binding.mapPickerSearchQuery.text?.toString().orEmpty())
        }
        binding.mapPickerSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performGeocodeSearch(binding.mapPickerSearchQuery.text?.toString().orEmpty())
                true
            } else false
        }
        // Some devices ship without a geocoder backend — hide the field then so users aren't fooled.
        if (!Geocoder.isPresent()) {
            binding.mapPickerSearchQuery.visibility = android.view.View.GONE
            binding.mapPickerSearchButton.visibility = android.view.View.GONE
        }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map_picker_map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    /**
     * Geocode [query] using the Android Geocoder (no external service / API key). API 33+ has an
     * async callback variant; older versions block on a background thread to avoid ANR.
     */
    private fun performGeocodeSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        if (!Geocoder.isPresent()) {
            Snackbar.make(binding.root, R.string.map_picker_search_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        // Dismiss IME so the user can see the map result.
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.mapPickerSearchQuery.windowToken, 0)

        binding.mapPickerSearchButton.isEnabled = false
        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocationName(q, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    runOnUiThread {
                        binding.mapPickerSearchButton.isEnabled = true
                        applyGeocodeResult(addresses)
                    }
                }
                override fun onError(errorMessage: String?) {
                    runOnUiThread {
                        binding.mapPickerSearchButton.isEnabled = true
                        Snackbar.make(binding.root, R.string.map_picker_search_failed, Snackbar.LENGTH_LONG).show()
                    }
                }
            })
        } else {
            // Pre-33: getFromLocationName is synchronous and may hit the network; off the main thread.
            Thread {
                val results: List<Address>? = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(q, 1)
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    binding.mapPickerSearchButton.isEnabled = true
                    if (results == null) {
                        Snackbar.make(binding.root, R.string.map_picker_search_failed, Snackbar.LENGTH_LONG).show()
                    } else {
                        applyGeocodeResult(results)
                    }
                }
            }.start()
        }
    }

    private fun applyGeocodeResult(addresses: List<Address>) {
        if (addresses.isEmpty()) {
            Snackbar.make(binding.root, R.string.map_picker_search_no_results, Snackbar.LENGTH_LONG).show()
            return
        }
        val target = LatLng(addresses[0].latitude, addresses[0].longitude)
        val map = googleMap ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15f))
        val existing = marker
        if (existing == null) {
            marker = map.addMarker(MarkerOptions().position(target))
        } else {
            existing.position = target
        }
        binding.mapPickerConfirm.isEnabled = true
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        val center = LatLng(initialLat, initialLng)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, if (hasInitialPin) 15f else 10f))
        if (hasInitialPin) {
            marker = map.addMarker(MarkerOptions().position(center))
        }
        map.setOnMapLongClickListener { latLng ->
            val existing = marker
            if (existing == null) {
                marker = map.addMarker(MarkerOptions().position(latLng))
            } else {
                existing.position = latLng
            }
            binding.mapPickerConfirm.isEnabled = true
        }
    }

    private fun confirm() {
        val m = marker
        if (m == null) {
            Snackbar.make(binding.root, R.string.map_picker_select_first, Snackbar.LENGTH_SHORT).show()
            return
        }
        val data = Intent().apply {
            putExtra(EXTRA_RESULT_LAT, m.position.latitude)
            putExtra(EXTRA_RESULT_LNG, m.position.longitude)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    companion object {
        const val EXTRA_INITIAL_LAT = "initial_lat"
        const val EXTRA_INITIAL_LNG = "initial_lng"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RESULT_LAT = "result_lat"
        const val EXTRA_RESULT_LNG = "result_lng"

        // Tel Aviv default — only shown when no initial pin and the user hasn't zoomed yet.
        private const val DEFAULT_LAT = 32.0853
        private const val DEFAULT_LNG = 34.7818

        /** Convenience: build an Intent for the home-address use case. */
        fun intentForHome(context: android.content.Context, currentLat: Double?, currentLng: Double?): Intent {
            val i = Intent(context, MapPickerActivity::class.java)
                .putExtra(EXTRA_TITLE, context.getString(R.string.map_picker_title_home))
            if (currentLat != null && currentLng != null) {
                i.putExtra(EXTRA_INITIAL_LAT, currentLat)
                i.putExtra(EXTRA_INITIAL_LNG, currentLng)
            }
            return i
        }

        /** Convenience: build an Intent for the practice-location use case. */
        fun intentForPracticeLocation(
            context: android.content.Context,
            currentLat: Double?,
            currentLng: Double?
        ): Intent {
            val i = Intent(context, MapPickerActivity::class.java)
                .putExtra(EXTRA_TITLE, context.getString(R.string.map_picker_title_practice))
            if (currentLat != null && currentLng != null) {
                i.putExtra(EXTRA_INITIAL_LAT, currentLat)
                i.putExtra(EXTRA_INITIAL_LNG, currentLng)
            }
            return i
        }

        /** Extract result coordinates from the returned Intent. Returns null if missing. */
        fun extractResult(data: Intent?): Pair<Double, Double>? {
            if (data == null) return null
            val lat = data.getDoubleExtra(EXTRA_RESULT_LAT, Double.NaN)
            val lng = data.getDoubleExtra(EXTRA_RESULT_LNG, Double.NaN)
            return if (lat.isNaN() || lng.isNaN()) null else lat to lng
        }
    }
}
