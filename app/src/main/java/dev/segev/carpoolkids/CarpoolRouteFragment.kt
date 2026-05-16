package dev.segev.carpoolkids

import android.animation.ValueAnimator
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.annotation.StringRes
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
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import dev.segev.carpoolkids.data.CarpoolRouteRepository
import dev.segev.carpoolkids.data.PracticeRepository
import dev.segev.carpoolkids.data.UserRepository
import dev.segev.carpoolkids.databinding.FragmentCarpoolRouteBinding
import dev.segev.carpoolkids.model.CarpoolRoute
import dev.segev.carpoolkids.model.Practice
import dev.segev.carpoolkids.routing.PolylineDecoder
import dev.segev.carpoolkids.ui.route.NumberedMarkerFactory
import dev.segev.carpoolkids.ui.route.RouteStopsAdapter
import dev.segev.carpoolkids.utilities.Constants
import dev.segev.carpoolkids.utilities.bidiSafe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only route screen for a single (practice, direction) pair.
 *
 * Lifecycle (Phase 6 discipline; Phase 7 added the practice listener):
 *  - `_binding`        → cleared in onDestroyView.
 *  - `routeListener`    → removed in onDestroyView.
 *  - `practiceListener` → removed in onDestroyView.
 *  - `googleMap`        → only touched after onMapReady and while _binding is alive.
 *
 * State precedence (top wins):
 *  1. Practice canceled        → orange banner; map cleared; no actions.
 *  2. No driver for direction   → "No driver claimed this yet" / "Driver canceled their slot".
 *  3. No route doc              → driver gets a Generate CTA; everyone else sees "Not generated yet".
 *  4. Route doc EMPTY_ROSTER    → "No riders signed up" + driver Regenerate CTA.
 *  5. Route doc FAILED          → failure copy + driver Retry CTA.
 *  6. Route doc READY (default) → map + summary + stops + actions, with a stale banner if
 *                                 `participantUids` no longer matches `stops ∪ missingAddressUids`.
 */
class CarpoolRouteFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentCarpoolRouteBinding? = null
    private val binding get() = _binding!!

    private var googleMap: GoogleMap? = null
    private var routeListener: ListenerRegistration? = null
    private var practiceListener: ListenerRegistration? = null

    private var practice: Practice? = null
    private var route: CarpoolRoute? = null
    private var isGenerating = false

    /** Cached display names for [CarpoolRoute.missingAddressUids]; refreshed when the set changes. */
    private var missingNamesByUid: Map<String, String> = emptyMap()
    private var missingNamesFetchKey: Set<String> = emptySet()

    private lateinit var adapter: RouteStopsAdapter
    private val drawnPolylines = mutableListOf<Polyline>()
    /** Phase 8 — keyed by passengerUid so a stop-row tap can focus its marker on the map. */
    private val stopMarkers = mutableMapOf<String, Marker>()
    /** Phase 8 — running marker fade-in animators; cancelled in clearMap / onDestroyView. */
    private val markerAnimators = mutableListOf<ValueAnimator>()

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
            currentUserUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty(),
            onStopClick = { stop -> focusStopOnMap(stop) }
        )
        binding.routeStopsList.layoutManager = LinearLayoutManager(requireContext())
        binding.routeStopsList.adapter = adapter

        binding.routeRegenerate.setOnClickListener { generateRoute() }
        binding.routeStateAction.setOnClickListener { generateRoute() }
        binding.routeStateBannerAction.setOnClickListener { generateRoute() }

        val mapFragment = childFragmentManager.findFragmentById(R.id.route_map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        attachPracticeListener()
        attachRouteListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        routeListener?.remove()
        routeListener = null
        practiceListener?.remove()
        practiceListener = null
        markerAnimators.forEach { it.cancel() }
        markerAnimators.clear()
        stopMarkers.clear()
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

    private fun attachPracticeListener() {
        if (practiceListener != null) return
        practiceListener = PracticeRepository.listenToPractice(practiceId) { incoming ->
            if (_binding == null) return@listenToPractice
            practice = incoming
            render()
        }
    }

    private fun attachRouteListener() {
        if (routeListener != null) return
        val routeId = CarpoolRouteRepository.routeId(practiceId, direction)
        routeListener = CarpoolRouteRepository.listenToRoute(routeId) { incoming ->
            if (_binding == null) return@listenToRoute
            route = incoming
            // Refresh missing-name cache only when the uid set actually changes — avoids hammering
            // Firestore each time the listener fires for an unrelated field change.
            val newKey = incoming?.missingAddressUids?.toSet().orEmpty()
            if (newKey != missingNamesFetchKey) {
                missingNamesFetchKey = newKey
                if (newKey.isEmpty()) {
                    missingNamesByUid = emptyMap()
                } else {
                    UserRepository.getUsersByIds(newKey.toList()) { profileMap ->
                        if (_binding == null) return@getUsersByIds
                        // Late update: only apply if the same set is still the one we wanted.
                        if (missingNamesFetchKey == newKey) {
                            missingNamesByUid = profileMap.mapValues { (_, profile) ->
                                profile.displayName?.takeIf { it.isNotBlank() }
                                    ?: profile.email?.takeIf { it.isNotBlank() }
                                    ?: profile.uid
                            }
                            render()
                        }
                    }
                }
            }
            render()
        }
    }

    private fun render() {
        val binding = _binding ?: return
        val practice = this.practice
        val route = this.route

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val expectedDriverUid = when (direction) {
            Constants.RouteDirection.PICKUP -> practice?.driverToUid
            Constants.RouteDirection.DROPOFF -> practice?.driverFromUid
            else -> null
        }
        val isAssignedDriver =
            expectedDriverUid != null && expectedDriverUid.isNotBlank() && expectedDriverUid == currentUid

        // Default everything to hidden; each branch turns the pieces it needs back on.
        binding.routeSummaryCard.visibility = View.GONE
        binding.routeActionsRow.visibility = View.GONE
        binding.routeStateOverlay.visibility = View.GONE
        binding.routeStateAction.visibility = View.GONE
        binding.routeStateBanner.visibility = View.GONE
        binding.routeStateBannerAction.visibility = View.GONE
        binding.routeProgress.visibility = if (isGenerating) View.VISIBLE else View.GONE

        // 1. Practice canceled — overrides everything. Banner stays up; the rest of the screen is
        //    cleared so the driver doesn't accidentally act on a stale plan.
        if (practice?.canceled == true) {
            showBanner(getString(R.string.route_state_practice_canceled), actionVisible = false)
            showEmptyState(
                message = getString(R.string.route_state_practice_canceled),
                actionVisible = false
            )
            adapter.submitList(emptyList())
            clearMap()
            return
        }

        // 2. No assigned driver for this direction. A pre-existing route doc means the driver
        //    cancelled their slot — call that out specifically; otherwise it's just unassigned.
        if (expectedDriverUid.isNullOrBlank()) {
            val message = if (route != null) {
                getString(R.string.route_state_driver_canceled)
            } else {
                getString(R.string.route_state_no_driver)
            }
            showEmptyState(message = message, actionVisible = false)
            adapter.submitList(emptyList())
            clearMap()
            return
        }

        // 3. No route doc yet.
        if (route == null) {
            showEmptyState(
                message = getString(
                    if (isAssignedDriver) R.string.route_not_generated_driver
                    else R.string.route_not_generated_member
                ),
                actionVisible = isAssignedDriver && !isGenerating,
                actionLabel = R.string.route_generate_now
            )
            adapter.submitList(emptyList())
            clearMap()
            return
        }

        when (route.status) {
            Constants.RouteStatus.EMPTY_ROSTER -> {
                showEmptyState(
                    message = getString(R.string.route_state_no_riders),
                    actionVisible = isAssignedDriver && !isGenerating,
                    actionLabel = R.string.route_generate_now
                )
                adapter.submitList(emptyList())
                clearMap()
            }
            Constants.RouteStatus.FAILED -> {
                showEmptyState(
                    message = getString(
                        R.string.route_state_failed_with_retry,
                        route.failureReason ?: "unknown error"
                    ),
                    actionVisible = isAssignedDriver && !isGenerating,
                    actionLabel = R.string.route_retry
                )
                adapter.submitList(emptyList())
                clearMap()
            }
            else -> renderReadyRoute(route, practice, isAssignedDriver)
        }
    }

    private fun renderReadyRoute(route: CarpoolRoute, practice: Practice?, isAssignedDriver: Boolean) {
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
            binding.routeSummaryMissing.text = getString(
                R.string.route_missing_addresses,
                missingDisplayNames(route)
            )
            binding.routeSummaryMissing.visibility = View.VISIBLE
        } else {
            binding.routeSummaryMissing.visibility = View.GONE
        }

        // Phase 8 — "Generated 2 minutes ago" line. Uses DateUtils so we get locale-aware copy
        // for free; sub-minute differences round to "just now" because the relative formatter
        // can't go below MINUTE_IN_MILLIS.
        val generatedAt = route.generatedAt
        if (generatedAt != null) {
            val now = System.currentTimeMillis()
            val ago = if (now - generatedAt < DateUtils.MINUTE_IN_MILLIS) {
                getString(R.string.route_generated_just_now)
            } else {
                val rel = DateUtils.getRelativeTimeSpanString(
                    generatedAt, now, DateUtils.MINUTE_IN_MILLIS
                ).toString()
                getString(R.string.route_generated_ago, rel)
            }
            binding.routeSummaryGeneratedAgo.text = ago
            binding.routeSummaryGeneratedAgo.visibility = View.VISIBLE
        } else {
            binding.routeSummaryGeneratedAgo.visibility = View.GONE
        }

        // Action row — only the driver gets the Regenerate button, so hide the whole row otherwise.
        binding.routeActionsRow.visibility = if (isAssignedDriver) View.VISIBLE else View.GONE
        binding.routeRegenerate.visibility = if (isAssignedDriver) View.VISIBLE else View.GONE
        binding.routeRegenerate.isEnabled = !isGenerating

        // Stale banner — only meaningful when we actually have a practice snapshot to compare to,
        // and only when we're not already regenerating (avoids the noisy mid-flight banner flicker).
        // Location change is checked before roster change so the message reflects the bigger geometry
        // shift when both happen at once.
        if (practice != null && !isGenerating) {
            val reason = computeStaleReason(route, practice)
            if (reason != null) {
                val text = getString(
                    when (reason) {
                        StaleReason.LOCATION ->
                            if (isAssignedDriver) R.string.route_state_stale_location_driver
                            else R.string.route_state_stale_location_member
                        StaleReason.ROSTER ->
                            if (isAssignedDriver) R.string.route_state_stale_driver
                            else R.string.route_state_stale_member
                    }
                )
                showBanner(text = text, actionVisible = isAssignedDriver)
            }
        }

        drawRouteOnMap(route)
    }

    private enum class StaleReason { LOCATION, ROSTER }

    /**
     * Returns the reason the route is stale, or null when it's up to date. Location change takes
     * precedence because it affects the polyline directly; the roster check is a set comparison
     * (joins, leaves, and the swap case where one rider replaces another between regenerations).
     */
    private fun computeStaleReason(route: CarpoolRoute, practice: Practice): StaleReason? {
        val practiceLat = practice.locationLat
        val practiceLng = practice.locationLng
        if (practiceLat != null && practiceLng != null) {
            val latDiff = kotlin.math.abs(route.trainingLat - practiceLat)
            val lngDiff = kotlin.math.abs(route.trainingLng - practiceLng)
            if (latDiff > COORD_STALE_EPSILON || lngDiff > COORD_STALE_EPSILON) {
                return StaleReason.LOCATION
            }
        }
        val routeCoverage =
            (route.stops.map { it.passengerUid } + route.missingAddressUids).toSet()
        val participants = practice.participantUids.toSet()
        if (routeCoverage != participants) return StaleReason.ROSTER
        return null
    }

    private fun missingDisplayNames(route: CarpoolRoute): String =
        route.missingAddressUids.joinToString(", ") { uid ->
            bidiSafe(missingNamesByUid[uid] ?: uid)
        }

    private fun showBanner(text: String, actionVisible: Boolean) {
        val binding = _binding ?: return
        binding.routeStateBanner.visibility = View.VISIBLE
        binding.routeStateBannerText.text = text
        binding.routeStateBannerAction.visibility = if (actionVisible) View.VISIBLE else View.GONE
    }

    private fun showEmptyState(
        message: String,
        actionVisible: Boolean,
        @StringRes actionLabel: Int = R.string.route_generate_now
    ) {
        val binding = _binding ?: return
        binding.routeStateOverlay.visibility = View.VISIBLE
        binding.routeStateMessage.text = message
        binding.routeStateAction.visibility = if (actionVisible) View.VISIBLE else View.GONE
        binding.routeStateAction.setText(actionLabel)
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
        // Phase 8: start invisible and fade in with a per-marker stagger for the "drop in" feel.
        route.stops.forEachIndexed { idx, stop ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(stop.lat, stop.lng))
                    .icon(NumberedMarkerFactory.create(ctx, stop.sequence + 1))
                    .anchor(0.5f, 0.5f)
                    .alpha(0f)
                    .title(stop.passengerName.ifBlank { stop.passengerUid })
            ) ?: return@forEachIndexed
            stopMarkers[stop.passengerUid] = marker
            animateMarkerIn(marker, delayMs = idx * MARKER_STAGGER_MS)
        }

        // Camera: fit all included points. Use a bounds builder so single-stop routes still frame.
        // Phase 8: animateCamera with a duration so the framing feels intentional rather than abrupt.
        val boundsBuilder = LatLngBounds.builder()
            .include(origin)
            .include(destination)
        route.stops.forEach { boundsBuilder.include(LatLng(it.lat, it.lng)) }
        polyPoints.forEach { boundsBuilder.include(it) }
        runCatching {
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), CAMERA_PADDING_PX),
                CAMERA_ANIMATION_MS,
                null
            )
        }
    }

    /** Fade a single marker from invisible to fully visible after [delayMs]. Tracked for teardown. */
    private fun animateMarkerIn(marker: Marker, delayMs: Long) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MARKER_FADE_DURATION_MS
            startDelay = delayMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                // The marker may have been removed if the route was regenerated mid-animation;
                // guard with a try/catch on the SDK's IllegalArgumentException.
                runCatching { marker.alpha = va.animatedValue as Float }
            }
        }
        markerAnimators += animator
        animator.start()
    }

    /**
     * Phase 8 — stop row tap: zoom in on that stop and pop its info window. Cheap "look here"
     * affordance that doesn't require a custom bounce animation.
     */
    private fun focusStopOnMap(stop: dev.segev.carpoolkids.model.RouteStop) {
        val map = googleMap ?: return
        val target = LatLng(stop.lat, stop.lng)
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(target, STOP_FOCUS_ZOOM),
            CAMERA_FOCUS_MS,
            null
        )
        stopMarkers[stop.passengerUid]?.showInfoWindow()
    }

    private fun clearMap() {
        markerAnimators.forEach { it.cancel() }
        markerAnimators.clear()
        stopMarkers.clear()
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

    companion object {
        private const val ARG_PRACTICE_ID = "practice_id"
        private const val ARG_DIRECTION = "direction"

        private const val POLYLINE_WIDTH_PX = 14f
        private const val CAMERA_PADDING_PX = 160

        /** ~1 m of latitude/longitude. Below this we treat the practice coords as unchanged. */
        private const val COORD_STALE_EPSILON = 1e-5

        // Phase 8 — animation timings.
        private const val CAMERA_ANIMATION_MS = 600
        private const val CAMERA_FOCUS_MS = 400
        private const val STOP_FOCUS_ZOOM = 15f
        private const val MARKER_FADE_DURATION_MS = 280L
        private const val MARKER_STAGGER_MS = 90L

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
