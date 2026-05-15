package dev.segev.carpoolkids

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.data.CarpoolRouteRepository
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.databinding.FragmentCarpoolRouteBinding
import dev.segev.carpoolkids.model.CarpoolRoute
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.routing.PolylineDecoder
import dev.segev.carpoolkids.ui.route.NumberedMarkerFactory
import dev.segev.carpoolkids.ui.route.RouteStopsAdapter
import dev.segev.carpoolkids.utilities.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only route screen for a single (practice, direction) pair.
 *
 * Lifecycle:
 *  - `_binding`     → cleared in onDestroyView.
 *  - `routeListener` → removed in onDestroyView (single Firestore snapshot listener per screen).
 *  - `googleMap`    → only touched after onMapReady, and only when _binding is still alive.
 *
 * State machine driven by the doc presence + `route.status`:
 *  - doc null    → driver sees "Generate route" CTA, others see "Driver hasn't generated it yet".
 *  - READY       → full UI (map + summary + stops + Regenerate / Open in Maps actions).
 *  - EMPTY_ROSTER → "No riders signed up yet."
 *  - FAILED       → failure copy + Retry (drivers only).
 *  - GENERATING / STALE → fall through to READY rendering; Phase 7 adds the stale banner.
 */
class CarpoolRouteFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentCarpoolRouteBinding? = null
    private val binding get() = _binding!!

    private var googleMap: GoogleMap? = null
    private var routeListener: ListenerRegistration? = null

    private var practice: Practice? = null
    private var route: CarpoolRoute? = null
    private var isGenerating = false

    private lateinit var adapter: RouteStopsAdapter
    private val drawnPolylines = mutableListOf<Polyline>()

    private val practiceId: String get() = arguments?.getString(ARG_PRACTICE_ID).orEmpty()
    private val direction: String get() = arguments?.getString(ARG_DIRECTION).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCarpoolRouteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (practiceId.isBlank() || direction.isBlank()) {
            Snackbar.make(binding.root, R.string.route_no_practice, Snackbar.LENGTH_LONG).show()
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        binding.routeTitle.setText(
            if (direction == Constants.RouteDirection.DROPOFF) R.string.route_screen_title_dropoff
            else R.string.route_screen_title_pickup
        )
        binding.routeBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        adapter = RouteStopsAdapter(
            direction = direction,
            currentUserUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        )
        binding.routeStopsList.layoutManager = LinearLayoutManager(requireContext())
        binding.routeStopsList.adapter = adapter

        binding.routeRegenerate.setOnClickListener { generateRoute() }
        binding.routeStateAction.setOnClickListener { generateRoute() }
        binding.routeOpenInMaps.setOnClickListener { openInGoogleMaps() }

        val mapFragment = childFragmentManager.findFragmentById(R.id.route_map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        loadPracticeThenListen()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        routeListener?.remove()
        routeListener = null
        drawnPolylines.forEach { it.remove() }
        drawnPolylines.clear()
        googleMap?.clear()
        googleMap = null
        _binding = null
    }

    override fun onMapReady(map: GoogleMap) {
        if (_binding == null) return
        googleMap = map
        // Defensive: a missing key surfaces as a runtime crash here, so wrap the style load.
        runCatching {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark))
        }
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMapToolbarEnabled = false
        // Re-render in case the route arrived before the map did.
        route?.let { drawRouteOnMap(it) }
    }

    private fun loadPracticeThenListen() {
        PracticeRepository.getPracticeById(practiceId) { loaded, _ ->
            if (_binding == null) return@getPracticeById
            practice = loaded
            // The route doc may exist independently of the practice (defensive: we still wire the listener
            // even if practice load fails so the empty-state copy at least reflects current Firestore truth).
            attachRouteListener()
        }
    }

    private fun attachRouteListener() {
        if (routeListener != null) return
        val routeId = CarpoolRouteRepository.routeId(practiceId, direction)
        routeListener = CarpoolRouteRepository.listenToRoute(routeId) { incoming ->
            if (_binding == null) return@listenToRoute
            route = incoming
            render()
        }
    }

    private fun render() {
        val binding = _binding ?: return
        val practice = this.practice
        val route = this.route

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val isAssignedDriver = practice != null && when (direction) {
            Constants.RouteDirection.PICKUP -> practice.driverToUid == currentUid
            Constants.RouteDirection.DROPOFF -> practice.driverFromUid == currentUid
            else -> false
        }

        // Default everything to hidden; each branch turns the pieces it needs back on.
        binding.routeSummaryCard.visibility = View.GONE
        binding.routeActionsRow.visibility = View.GONE
        binding.routeStateOverlay.visibility = View.GONE
        binding.routeStateAction.visibility = View.GONE
        binding.routeProgress.visibility = if (isGenerating) View.VISIBLE else View.GONE

        if (route == null) {
            showEmptyState(
                message = getString(
                    if (isAssignedDriver) R.string.route_not_generated_driver
                    else R.string.route_not_generated_member
                ),
                actionVisible = isAssignedDriver && !isGenerating
            )
            adapter.submitList(emptyList())
            clearMap()
            return
        }

        when (route.status) {
            Constants.RouteStatus.EMPTY_ROSTER -> {
                showEmptyState(
                    message = getString(R.string.route_empty_roster),
                    actionVisible = isAssignedDriver && !isGenerating
                )
                adapter.submitList(emptyList())
                clearMap()
            }
            Constants.RouteStatus.FAILED -> {
                showEmptyState(
                    message = getString(
                        R.string.route_failed_state,
                        route.failureReason ?: "unknown error"
                    ),
                    actionVisible = isAssignedDriver && !isGenerating
                )
                adapter.submitList(emptyList())
                clearMap()
            }
            else -> renderReadyRoute(route, isAssignedDriver)
        }
    }

    private fun renderReadyRoute(route: CarpoolRoute, isAssignedDriver: Boolean) {
        val binding = _binding ?: return
        adapter.setCurrentUserUid(FirebaseAuth.getInstance().currentUser?.uid.orEmpty())
        adapter.submitList(route.stops)

        // Summary card
        binding.routeSummaryCard.visibility = View.VISIBLE
        val isPickup = route.direction == Constants.RouteDirection.PICKUP
        binding.routeSummaryAnchorLabel.setText(
            if (isPickup) R.string.route_leave_by else R.string.route_depart
        )
        binding.routeSummaryAnchorTime.text =
            TIME_FMT.format(Date(route.recommendedDepartureMillis))
        binding.routeSummaryTotals.text = getString(
            R.string.route_total_summary,
            getString(R.string.route_distance_km, route.totalDistanceMeters / 1000.0),
            getString(R.string.route_duration_min, route.totalDurationSec / 60)
        )

        if (route.missingAddressUids.isNotEmpty()) {
            val joined = missingDisplayNames(route)
            binding.routeSummaryMissing.text = getString(R.string.route_missing_addresses, joined)
            binding.routeSummaryMissing.visibility = View.VISIBLE
        } else {
            binding.routeSummaryMissing.visibility = View.GONE
        }

        // Action row
        binding.routeActionsRow.visibility = View.VISIBLE
        binding.routeRegenerate.visibility = if (isAssignedDriver) View.VISIBLE else View.GONE
        binding.routeRegenerate.isEnabled = !isGenerating
        binding.routeOpenInMaps.visibility =
            if (route.stops.isNotEmpty()) View.VISIBLE else View.GONE

        // Map
        drawRouteOnMap(route)
    }

    private fun missingDisplayNames(route: CarpoolRoute): String {
        // Phase 6 stays on the doc only — name lookups live on the route screen for the routed
        // stops already, but for missing riders we just show their uids (Phase 7 swaps in display
        // names via UserRepository.getUsersByIds without forcing a fetch here).
        return route.missingAddressUids.joinToString(", ")
    }

    private fun showEmptyState(message: String, actionVisible: Boolean) {
        val binding = _binding ?: return
        binding.routeStateOverlay.visibility = View.VISIBLE
        binding.routeStateMessage.text = message
        binding.routeStateAction.visibility = if (actionVisible) View.VISIBLE else View.GONE
    }

    private fun drawRouteOnMap(route: CarpoolRoute) {
        val map = googleMap ?: return
        val ctx = context ?: return

        // Clear previous render so a regenerate doesn't leave ghosts.
        clearMap()

        val polyPoints = if (route.polyline.isNotEmpty()) {
            PolylineDecoder.decode(route.polyline, route.polylinePrecision)
        } else {
            emptyList()
        }
        if (polyPoints.isNotEmpty()) {
            val opts = PolylineOptions()
                .addAll(polyPoints)
                .width(POLYLINE_WIDTH_PX)
                .color(ContextCompat.getColor(ctx, R.color.button_primary_blue))
                .geodesic(false)
            drawnPolylines += map.addPolyline(opts)
        }

        // Endpoints (driver home + practice location) — small accent dots so the route has bookends.
        val origin: LatLng
        val destination: LatLng
        if (route.direction == Constants.RouteDirection.PICKUP) {
            origin = LatLng(route.driverHomeLat, route.driverHomeLng)
            destination = LatLng(route.trainingLat, route.trainingLng)
        } else {
            origin = LatLng(route.trainingLat, route.trainingLng)
            destination = LatLng(route.driverHomeLat, route.driverHomeLng)
        }
        map.addMarker(
            MarkerOptions()
                .position(origin)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .anchor(0.5f, 0.5f)
        )
        map.addMarker(
            MarkerOptions()
                .position(destination)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .anchor(0.5f, 0.5f)
        )

        // Numbered stop markers — sequence is 0-based in the model but 1-based on the pin.
        for (stop in route.stops) {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(stop.lat, stop.lng))
                    .icon(NumberedMarkerFactory.create(ctx, stop.sequence + 1))
                    .anchor(0.5f, 0.5f)
                    .title(stop.passengerName.ifBlank { stop.passengerUid })
            )
        }

        // Camera: fit all included points. Use a bounds builder so single-stop routes still frame.
        val boundsBuilder = LatLngBounds.builder()
            .include(origin)
            .include(destination)
        route.stops.forEach { boundsBuilder.include(LatLng(it.lat, it.lng)) }
        polyPoints.forEach { boundsBuilder.include(it) }
        runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), CAMERA_PADDING_PX))
        }
    }

    private fun clearMap() {
        drawnPolylines.forEach { it.remove() }
        drawnPolylines.clear()
        googleMap?.clear()
    }

    private fun generateRoute() {
        if (isGenerating) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isBlank()) return
        isGenerating = true
        render()
        CarpoolRouteRepository.generateAndSave(practiceId, direction, uid) { generated, err ->
            if (_binding == null) return@generateAndSave
            isGenerating = false
            if (generated == null) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.route_generation_failed, err ?: "unknown error"),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            // Listener will re-render on success/failure persistence.
            render()
        }
    }

    private fun openInGoogleMaps() {
        val route = this.route ?: return
        // Aim at the first stop (or the destination if there are no stops). Driver re-launches per
        // segment as they finish each pickup/dropoff — multi-waypoint deep links require a different
        // URL scheme that the plan didn't lock in for MVP.
        val first = route.stops.firstOrNull()
        val (lat, lng) = if (first != null) {
            first.lat to first.lng
        } else if (route.direction == Constants.RouteDirection.PICKUP) {
            route.trainingLat to route.trainingLng
        } else {
            route.driverHomeLat to route.driverHomeLng
        }
        val uri = Uri.parse("google.navigation:q=$lat,$lng")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        runCatching {
            startActivity(intent)
        }.onFailure {
            // Fall back to a generic geo intent if Google Maps isn't installed.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
        }
    }

    companion object {
        private const val ARG_PRACTICE_ID = "practice_id"
        private const val ARG_DIRECTION = "direction"

        private const val POLYLINE_WIDTH_PX = 14f
        private const val CAMERA_PADDING_PX = 160

        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.US)

        fun newInstance(practiceId: String, direction: String): CarpoolRouteFragment =
            CarpoolRouteFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PRACTICE_ID, practiceId)
                    putString(ARG_DIRECTION, direction)
                }
            }
    }
}
