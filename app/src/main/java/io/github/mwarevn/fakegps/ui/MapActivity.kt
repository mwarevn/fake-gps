package io.github.mwarevn.fakegps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.domain.map.IMapController
import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.VehicleType
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import io.github.mwarevn.fakegps.utils.LocationService
import io.github.mwarevn.fakegps.utils.PrefManager
import io.github.mwarevn.fakegps.utils.ext.*
import kotlinx.coroutines.*
import java.util.*
import io.github.mwarevn.fakegps.databinding.ActivityMapBinding
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.*
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.easeTo
import io.github.mwarevn.fakegps.network.RoutingService
import io.github.mwarevn.fakegps.network.OsrmClient
import io.github.mwarevn.fakegps.domain.route.RouteExtractorFactory
import com.mapbox.maps.plugin.scalebar.scalebar

/**
 * Clean redesigned MapActivity with Google Maps-like UX
 * Optimized to use LocationService for persistent background simulation
 */
class MapActivity : BaseMapActivity() {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 99
        const val ROUTE_COLOR = "#006eff"
        const val ROUTE_WIDTH = 8.0
        const val COMPLETED_ROUTE_COLOR = "#909090"
        const val FAKE_LOCATION_STROKE_COLOR = 0xFFFF4500.toInt()
        const val FAKE_LOCATION_FILL_COLOR = 0x50FF4500.toInt()
        const val FAKE_LOCATION_CENTER_COLOR = 0xFFFF4500.toInt()
        private const val CAMERA_UPDATE_INTERVAL_MS = 1000L
    }

    private lateinit var mapController: IMapController
    private var mapView: com.mapbox.maps.MapView? = null
    private lateinit var mapboxMap: com.mapbox.maps.MapboxMap

    private var currentMode = AppMode.SEARCH
    private enum class AppMode { SEARCH, ROUTE_PLAN, NAVIGATION }

    private var isDriving = false
    private var isPaused = false
    private var isGpsSet = false 
    
    private var currentStartPos: LatLng? = null
    private var currentDestPos: LatLng? = null
    private var currentNavigationPosition: LatLng? = null
    private var previousLocation: LatLng? = null
    private var routingPoints: List<LatLng> = emptyList()
    private val completedPathPoints: MutableList<LatLng> = mutableListOf()
    
    private var currentFakeLocationPos: LatLng? = null
    private var lastJoystickLat = 0.0
    private var lastJoystickLon = 0.0
    private var lastCameraUpdateTime = 0L
    private var isCameraFollowing = true
    private var currentSpeed = 52.0
    private var totalRouteDistanceKm = 0.0
    private var traveledDistanceKm = 0.0
    private var lastDistancePosition: LatLng? = null
    private var hasSelectedStartPoint = false

    private var locationService: LocationService? = null
    private var isBound = false
    private var serviceStateJob: Job? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted && pendingNavigationStart) { pendingNavigationStart = false; startNavigation() }
    }
    private var pendingNavigationStart = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocationService.LocationBinder
            locationService = binder.getService()
            isBound = true
            locationService?.updateCallbacks(
                onPosition = { pos -> runOnUiThread { handleNavigationUpdate(pos) } },
                onComplete = { runOnUiThread { onNavigationComplete() } }
            )
            syncWithService()
            observeServiceState()
        }
        override fun onServiceDisconnected(name: ComponentName?) { locationService = null; isBound = false; serviceStateJob?.cancel() }
    }

    private fun observeServiceState() {
        serviceStateJob?.cancel()
        serviceStateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val service = locationService ?: return@repeatOnLifecycle
                launch { service.isPausedFlow.collect { paused -> isPaused = paused; updateNavControlButtons() } }
                launch { service.isDrivingFlow.collect { driving -> if (isDriving && !driving) { onNavigationComplete() }; isDriving = driving; updateMarkersDraggableState() } }
            }
        }
    }

    private val prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "latitude" || key == "longitude") { syncJoystickPosition() }
        if (key == "map_type") { mapboxMap.loadStyleUri(PrefManager.getMapStyleUri()) { restoreMarkersAndRoutes() } }
    }

    private var currentVehicleType: VehicleType
        get() = VehicleType.fromString(PrefManager.vehicleType)
        set(value) { PrefManager.vehicleType = value.name }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isGpsSet = viewModel.isStarted
        viewModel.doGetUserDetails(); observeFavorites(); observeRoute()
        Intent(this, LocationService::class.java).also { bindService(it, serviceConnection, Context.BIND_AUTO_CREATE) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val data: Uri? = intent.data

        val isSupportedAction = (Intent.ACTION_SEND == action && sharedText != null) || (Intent.ACTION_VIEW == action && (data != null || sharedText != null))
        if (isSupportedAction) {
            android.util.Log.d("MapActivity", "New external intent: Clearing existing route/plan but keeping current Fake GPS state.")
            clearActiveNavigationData()
        }

        lifecycleScope.launch {
            var attempts = 0
            while (!::mapController.isInitialized && attempts < 20) { delay(200); attempts++ }
            if (!::mapController.isInitialized) return@launch

            when {
                Intent.ACTION_SEND == action && sharedText != null -> withContext(Dispatchers.Default) { processSharedText(sharedText) }
                Intent.ACTION_VIEW == action && data != null -> processGeoUri(data)
                Intent.ACTION_VIEW == action && data == null && sharedText != null -> processSharedText(sharedText)
            }
        }
    }

    private suspend fun processSharedText(text: String) {
        val urlRegex = Regex("""https?://[^\s]+"""); val urlMatch = urlRegex.find(text)
        if (urlMatch != null) {
            val url = urlMatch.value.trim(); val extractor = RouteExtractorFactory.getInstance().getExtractor(url)
            if (extractor != null) {
                try {
                    val routeData = extractor.extract(url)
                    if (routeData != null) {
                        runOnUiThread {
                            if (routeData.startPoint != null) setStartMarker(routeData.startPoint, animateCamera = false)
                            setDestinationAndEnterRouteMode(routeData.endPoint)
                        }
                        return
                    }
                } catch (e: Exception) { android.util.Log.e("MapActivity", "Extraction error", e) }
            }
        }
        val cleanQuery = text.replace(Regex("""https?://[^\s]+"""), "").replace("·", "").trim()
        if (cleanQuery.isNotEmpty()) searchLocation(cleanQuery) { setDestinationAndEnterRouteMode(it) }
    }

    private fun processGeoUri(uri: Uri) {
        when (uri.scheme) {
            "geo" -> {
                val coords = uri.schemeSpecificPart.split("?")[0].split(",")
                if (coords.size >= 2) {
                    val lat = coords[0].toDoubleOrNull(); val lon = coords[1].toDoubleOrNull()
                    if (lat != null && lon != null) { setDestinationAndEnterRouteMode(LatLng(lat, lon)); return }
                }
                uri.getQueryParameter("q")?.let { q -> lifecycleScope.launch { searchLocation(q) { setDestinationAndEnterRouteMode(it) } } }
            }
            "google.navigation" -> {
                uri.getQueryParameter("q")?.let { q ->
                    val coordMatch = Regex("""([-\d.]+),([-\d.]+)""").find(q)
                    if (coordMatch != null) {
                        val lat = coordMatch.groupValues[1].toDoubleOrNull(); val lon = coordMatch.groupValues[2].toDoubleOrNull()
                        if (lat != null && lon != null) { setDestinationAndEnterRouteMode(LatLng(lat, lon)); return@let }
                    }
                    lifecycleScope.launch { searchLocation(q) { setDestinationAndEnterRouteMode(it) } }
                } ?: uri.getQueryParameter("destination")?.let { dest -> lifecycleScope.launch { searchLocation(dest) { setDestinationAndEnterRouteMode(it) } } }
            }
        }
    }

    private fun setDestinationAndEnterRouteMode(pos: LatLng, autoStart: Boolean = true) {
        runOnUiThread {
            setDestinationMarker(pos); enterRoutePlanMode()
            if (autoStart) {
                val currentPos = if (isGpsSet) LatLng(PrefManager.getLat, PrefManager.getLng) else LatLng(lat, lon)
                setStartMarkerWithSelection(currentPos)
            }
            updateUseCurrentLocationButtonVisibility()
            lifecycleScope.launch {
                currentStartPos?.let { it.getAddress(this@MapActivity).collect { binding.startSearch.setText(it) } }
                currentDestPos?.let { it.getAddress(this@MapActivity).collect { binding.destinationSearch.setText(it) } }
            }
        }
    }

    private fun observeRoute() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentRoute.collect { points ->
                    if (points.isNotEmpty()) {
                        routingPoints = points; mapController.drawRoute(points, ROUTE_COLOR, ROUTE_WIDTH)
                        binding.routeLoadingCard.visibility = View.GONE
                        binding.actionButton.apply { text = "Bắt đầu"; setIconResource(R.drawable.ic_navigation); visibility = View.VISIBLE }
                        binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE }
                    } else {
                        // FIX: Nếu route trong ViewModel rỗng, đảm bảo xóa các nút và đường vẽ cũ
                        routingPoints = emptyList()
                        mapController.clearRoute()
                        if (currentMode == AppMode.SEARCH) {
                            binding.actionButton.visibility = View.GONE
                            binding.cancelRouteButton.visibility = View.GONE
                        }
                    }
                }
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.routeError.collect { showRouteErrorUI() } } }
    }

    private fun observeFavorites() {
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.allFavList.collect { updateAddFavoriteButtonVisibility() } } }
    }

    override fun initializeMap() {
        val map = binding.root.findViewById<com.mapbox.maps.MapView>(R.id.mapView)
        mapView = map; mapboxMap = map.mapboxMap
        map.scalebar.enabled = false
        val polylineAnnotationManager = map.annotations.createPolylineAnnotationManager()
        val pointAnnotationManager = map.annotations.createPointAnnotationManager()
        polylineAnnotationManager.lineCap = LineCap.ROUND; polylineAnnotationManager.lineJoin = LineJoin.ROUND
        
        mapController = io.github.mwarevn.fakegps.data.map.MapboxController(
            map, mapboxMap, pointAnnotationManager, polylineAnnotationManager,
            io.github.mwarevn.fakegps.data.map.MapboxController.MapIcons(getBitmapFromDrawable(R.drawable.ic_fake_location), getBitmapFromDrawable(R.drawable.ic_fake_gps_marker), getBitmapFromDrawable(R.drawable.ic_location_on))
        )
        map.location.enabled = true
        mapboxMap.addOnMapClickListener { point -> onMapClick(LatLng(point.latitude(), point.longitude())); true }
        pointAnnotationManager.addDragListener(io.github.mwarevn.fakegps.data.map.createDragListener(object : io.github.mwarevn.fakegps.domain.map.IMapController.OnPointAnnotationDragListenerWrapper {
            override fun onAnnotationDragFinished(id: String, point: com.mapbox.geojson.Point) {
                if (currentMode == AppMode.NAVIGATION || isDriving) { showToast("Không thể thay đổi lộ trình khi đang di chuyển"); return }
                lifecycleScope.launch {
                    val pos = LatLng(point.latitude(), point.longitude()); val addr = getAddressFromLocation(pos)
                    if (id == mapController.getDestinationId()) {
                        currentDestPos = pos; binding.destinationSearch.setText(addr)
                        updateReplaceLocationButtonVisibility(); updateAddFavoriteButtonVisibility(); updateActionButtonsVisibility()
                    } else if (id == mapController.getStartId()) {
                        currentStartPos = pos; binding.startSearch.setText(addr); updateUseCurrentLocationButtonVisibility()
                    }
                    if (currentMode == AppMode.ROUTE_PLAN && mapController.hasStartMarker() && mapController.hasDestinationMarker()) drawRoute()
                }
            }
        }))
        onMapReadyMapbox()
    }

    override fun getActivityInstance() = this
    override fun hasMarker() = mapController.hasDestinationMarker() || mapController.hasStartMarker()

    override fun moveMapToNewLocation(moveNewLocation: Boolean, shouldMark: Boolean) {
        if (moveNewLocation) {
            val newPos = LatLng(lat, lon); mapController.moveCamera(newPos, zoom = 16.0, animate = true)
            if (shouldMark && currentMode == AppMode.SEARCH) setDestinationMarker(newPos)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onMapReadyMapbox() {
        mapController.loadStyle(PrefManager.getMapStyleUri()) {
            if (checkPermissions()) getLastLocation() else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
            mapController.addOnCameraChangeListener { bearing -> PrefManager.cameraBearing = bearing.toFloat() }
            setupButtons(); setupSearchBoxes(); restoreMarkersAndRoutes()
            if (isBound) syncWithService()
        }
    }

    private fun restoreMarkersAndRoutes() {
        restoreFakeLocationMarker()
        currentDestPos?.let { setDestinationMarker(it, animateCamera = false) }
        currentStartPos?.let { setStartMarker(it, animateCamera = false) }
        if (isDriving) { drawRoute(showLoading = false); currentNavigationPosition?.let { updateFakeLocationMarker(it) } }
    }

    private fun syncWithService() {
        val service = locationService ?: return
        if (service.isDrivingFlow.value) {
            isDriving = true; currentMode = AppMode.NAVIGATION; routingPoints = service.currentRoutePoints
            mapController.drawRoute(routingPoints, ROUTE_COLOR, ROUTE_WIDTH)
            if (routingPoints.isNotEmpty()) {
                mapController.setStartMarker(routingPoints.first(), true)
                mapController.setDestinationMarker(service.currentTargetLatLng ?: routingPoints.last(), true)
            }
            completedPathPoints.clear(); completedPathPoints.addAll(service.traveledPoints)
            if (completedPathPoints.isNotEmpty()) drawCompletedPathLocally()
            binding.searchCard.visibility = View.GONE; binding.navigationControlsCard.visibility = View.VISIBLE; binding.cameraFollowToggle.visibility = View.VISIBLE
            updateNavigationAddresses(); updateNavControlButtons()
            service.lastPosition?.let { handleNavigationUpdate(it) }
            totalRouteDistanceKm = calculateTotalRouteDistance(routingPoints); traveledDistanceKm = (completedPathPoints.size * 0.005); updateDistanceLabel()
            updateMarkersDraggableState()
        }
    }

    private fun syncJoystickPosition() {
        val currentLat = PrefManager.getLat; val currentLon = PrefManager.getLng
        if (Math.abs(currentLat - lastJoystickLat) > 1e-7 || Math.abs(currentLon - lastJoystickLon) > 1e-7) {
            lastJoystickLat = currentLat; lastJoystickLon = currentLon
            val newPos = LatLng(currentLat, currentLon)
            lifecycleScope.launch(Dispatchers.Main) { currentFakeLocationPos = newPos; updateActionButtonsVisibility() }
        }
    }

    fun onMapClick(position: LatLng) {
        when (currentMode) {
            AppMode.SEARCH -> setDestinationMarker(position)
            AppMode.ROUTE_PLAN -> if (!hasSelectedStartPoint) setStartMarkerWithSelection(position)
            AppMode.NAVIGATION -> showToast("Không thể thay đổi khi đang di chuyển")
        }
    }

    private fun setupSearchBoxes() {
        binding.destinationSearch.setOnEditorActionListener { v, _, _ ->
            v.text.toString().trim().takeIf { it.isNotEmpty() }?.let { lifecycleScope.launch { searchLocation(it) { setDestinationMarker(it) } } }
            true
        }
        binding.startSearch.setOnEditorActionListener { v, _, _ ->
            v.text.toString().trim().takeIf { it.isNotEmpty() }?.let { lifecycleScope.launch { searchLocation(it) { setStartMarkerWithSelection(it) } } }
            true
        }
    }

    override fun setupButtons() {
        isGpsSet = viewModel.isStarted; updateSetLocationButton()
        binding.actionButton.setOnClickListener {
            when (currentMode) {
                AppMode.SEARCH -> if (mapController.hasDestinationMarker()) enterRoutePlanMode() else showToast("Vui lòng chọn điểm đến")
                AppMode.ROUTE_PLAN -> if (mapController.hasStartMarker() && mapController.hasDestinationMarker()) checkAndStartNavigation() else showToast("Vui lòng chọn đủ điểm đi và đến")
                AppMode.NAVIGATION -> stopNavigation()
            }
        }
        binding.getlocation.setOnClickListener { getLastLocation() }
        binding.getFakeLocation.setOnClickListener { currentFakeLocationPos?.let { pos -> mapboxMap.easeTo(CameraOptions.Builder().center(Point.fromLngLat(pos.longitude, pos.latitude)).zoom(15.0).build()) } }
        binding.setLocationButton.setOnClickListener {
            if (isGpsSet) {
                isGpsSet = false; viewModel.update(false, 0.0, 0.0); stopService(Intent(this, LocationService::class.java))
                mapController.updateFakeLocationMarker(LatLng(0.0, 0.0), false); currentFakeLocationPos = null
            } else {
                val pos = mapController.getDestinationPosition() ?: mapboxMap.cameraState.center.let { LatLng(it.latitude(), it.longitude()) }
                isGpsSet = true; currentFakeLocationPos = pos; viewModel.update(true, pos.latitude, pos.longitude)
                updateFakeLocationMarker(pos); checkAndStartForegroundService(LocationService.ACTION_SET_FIXED_LOCATION)
            }
            updateSetLocationButton(); updateActionButtonsVisibility()
        }
        binding.replaceLocationButton.setOnClickListener {
            mapController.getDestinationPosition()?.let { pos ->
                currentFakeLocationPos = pos; viewModel.update(true, pos.latitude, pos.longitude)
                updateFakeLocationMarker(pos); updateReplaceLocationButtonVisibility(); updateActionButtonsVisibility(); showToast("Đã cập nhật vị trí Fake GPS")
            }
        }
        binding.addFavoriteButton.setOnClickListener { mapController.getDestinationPosition()?.let { pos -> showAddFavoriteDialog(pos) } }
        binding.cameraFollowToggle.setOnClickListener { if (isDriving) { isCameraFollowing = !isCameraFollowing; updateCameraFollowButton() } }
        binding.speedSlider.addOnChangeListener { _, value, fromUser -> if (fromUser) { currentSpeed = if (isDriving && value <= 0) 1.0 else value.toDouble(); updateSpeedLabel(currentSpeed); locationService?.setSpeed(currentSpeed) } }
        binding.autoCurveSpeedCheckbox.isChecked = PrefManager.autoCurveSpeed
        binding.autoCurveSpeedCheckbox.setOnCheckedChangeListener { _, isChecked -> PrefManager.autoCurveSpeed = isChecked }
        binding.pauseButton.setOnClickListener { if (isDriving && !isPaused) locationService?.pauseSimulation() }
        binding.resumeButton.setOnClickListener { if (isDriving && isPaused) locationService?.resumeSimulation() }
        binding.stopButton.setOnClickListener { if (isDriving) onStopNavigationEarly() }
        binding.completedFinishButton.setOnClickListener { onFinishNavigation() }
        binding.completedRestartButton.setOnClickListener { onRestartNavigation() }
        binding.useCurrentLocationContainer.setOnClickListener { 
            val pos = if (isGpsSet) LatLng(PrefManager.getLat, PrefManager.getLng) else LatLng(lat, lon)
            setStartMarkerWithSelection(pos); if (currentMode == AppMode.SEARCH) enterRoutePlanMode()
            updateUseCurrentLocationButtonVisibility()
        }
        binding.routeRetryButton.setOnClickListener { drawRoute() }
        binding.routeErrorCancelButton.setOnClickListener { cancelRoutePlan() }
        binding.cancelRouteButton.setOnClickListener { if (currentMode == AppMode.SEARCH) clearDestinationMarker() else cancelRoutePlan() }
        binding.swapLocationsButton.setOnClickListener { swapStartAndDestination() }
        binding.navControlsToggle.setOnClickListener { toggleNavigationControls() }
        restoreNavigationControlsState()
    }

    private fun checkAndStartNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNavigationStart = true; requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        startNavigation()
    }

    private fun checkAndStartForegroundService(action: String = LocationService.ACTION_START_FOREGROUND) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        Intent(this, LocationService::class.java).apply { this.action = action }.also { startService(it) }
    }

    private fun showAddFavoriteDialog(latLng: LatLng) {
        val view = layoutInflater.inflate(R.layout.dialog, null); val editText = view.findViewById<EditText>(R.id.search_edittxt)
        lifecycleScope.launch { editText.setText(getAddressFromLocation(latLng)) }
        MaterialAlertDialogBuilder(this).setTitle("Thêm vào yêu thích").setView(view).setPositiveButton("Thêm") { _, _ ->
            val name = editText.text.toString().trim(); if (name.isNotEmpty()) { viewModel.storeFavorite(name, latLng.latitude, latLng.longitude); showToast("Đã lưu vào yêu thích") }
        }.setNegativeButton("Hủy", null).show()
    }

    private fun updateNavControlButtons() { binding.pauseButton.visibility = if (isPaused) View.GONE else View.VISIBLE; binding.resumeButton.visibility = if (isPaused) View.VISIBLE else View.GONE; binding.stopButton.visibility = if (isPaused) View.VISIBLE else View.GONE }

    private suspend fun searchLocation(query: String, onFound: (LatLng) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val addresses = Geocoder(this@MapActivity, Locale.getDefault()).getFromLocationName(query, 1)
            if (!addresses.isNullOrEmpty()) { val addr = addresses[0]; lifecycleScope.launch(Dispatchers.Main) { onFound(LatLng(addr.latitude, addr.longitude)) } }
            else lifecycleScope.launch(Dispatchers.Main) { showToast("Không tìm thấy địa điểm") }
        } catch (e: Exception) { lifecycleScope.launch(Dispatchers.Main) { showToast("Lỗi tìm kiếm: ${e.message}") } }
    }

    private suspend fun getAddressFromLocation(latLng: LatLng): String = withContext(Dispatchers.IO) {
        try {
            val addresses = Geocoder(this@MapActivity, Locale.getDefault()).getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]; val parts = mutableListOf<String>()
                addr.featureName?.takeIf { it.isNotBlank() && !it.matches(Regex("^[\\d,\\.\\s]+$")) }?.let { parts.add(it) }
                val street = "${addr.subThoroughfare ?: ""} ${addr.thoroughfare ?: ""}".trim(); if (street.isNotBlank()) parts.add(street)
                addr.subLocality?.let { parts.add(it) }; addr.locality?.let { parts.add(it) }; addr.adminArea?.let { parts.add(it) }
                if (parts.isNotEmpty()) return@withContext parts.distinct().joinToString(", ")
            }
            String.format("%.6f, %.6f", latLng.latitude, latLng.longitude)
        } catch (e: Exception) { String.format("%.6f, %.6f", latLng.latitude, latLng.longitude) }
    }

    private fun setDestinationMarker(position: LatLng, animateCamera: Boolean = true) {
        mapController.setDestinationMarker(position, true); currentDestPos = position
        if (animateCamera) mapController.moveCamera(position, zoom = 16.0, animate = true)
        lifecycleScope.launch { position.getAddress(this@MapActivity).collect { binding.destinationSearch.setText(it) } }
        if (currentMode == AppMode.SEARCH) updateActionButtonsVisibility()
        mapController.clearRoute(); updateSwapButtonVisibility(); updateReplaceLocationButtonVisibility(); updateUseCurrentLocationButtonVisibility()
    }

    private fun updateActionButtonsVisibility() {
        if (currentMode != AppMode.SEARCH) return
        val markerPos = mapController.getDestinationPosition(); val currentFakePos = if (isGpsSet) LatLng(PrefManager.getLat, PrefManager.getLng) else currentFakeLocationPos
        val isAtFakeLocation = isGpsSet && markerPos != null && currentFakePos != null && Math.abs(markerPos.latitude - currentFakePos.latitude) < 1e-6 && Math.abs(markerPos.longitude - currentFakePos.longitude) < 1e-6
        if (isAtFakeLocation) { binding.actionButton.visibility = View.GONE; binding.cancelRouteButton.visibility = View.GONE; mapController.clearDestinationMarker(); binding.destinationSearch.text.clear(); updateUseCurrentLocationButtonVisibility() } 
        else if (markerPos == null) { binding.actionButton.visibility = View.GONE; binding.cancelRouteButton.visibility = View.GONE; updateUseCurrentLocationButtonVisibility() } 
        else { binding.actionButton.apply { text = "Chỉ đường"; visibility = View.VISIBLE; setIconResource(R.drawable.ic_navigation) }; binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE }; updateUseCurrentLocationButtonVisibility() }
        updateAddFavoriteButtonVisibility()
    }

    private fun updateAddFavoriteButtonVisibility() {
        val dest = mapController.getDestinationPosition()
        if (currentMode == AppMode.SEARCH && dest != null) {
            val isFavorite = viewModel.allFavList.value.any { val results = FloatArray(1); android.location.Location.distanceBetween(it.lat ?: 0.0, it.lng ?: 0.0, dest.latitude, dest.longitude, results); results[0] < 15 }
            binding.addFavoriteButton.visibility = if (isFavorite) View.GONE else View.VISIBLE
        } else binding.addFavoriteButton.visibility = View.GONE
    }

    private fun clearDestinationMarker() {
        mapController.clearDestinationMarker(); currentDestPos = null; binding.destinationSearch.text.clear()
        if (currentMode == AppMode.ROUTE_PLAN) cancelRoutePlan()
        binding.actionButton.visibility = View.GONE; binding.cancelRouteButton.visibility = View.GONE; binding.addFavoriteButton.visibility = View.GONE
        updateSwapButtonVisibility(); updateReplaceLocationButtonVisibility(); updateUseCurrentLocationButtonVisibility()
    }

    private fun setStartMarker(position: LatLng, animateCamera: Boolean = true) {
        mapController.setStartMarker(position, true); currentStartPos = position
        lifecycleScope.launch { position.getAddress(this@MapActivity).collect { binding.startSearch.setText(it) } }
        if (animateCamera) {
            currentDestPos?.let { dPos ->
                val pts = listOf(com.mapbox.geojson.Point.fromLngLat(position.longitude, position.latitude), com.mapbox.geojson.Point.fromLngLat(dPos.longitude, dPos.latitude))
                mapboxMap.easeTo(mapboxMap.cameraForCoordinates(pts, com.mapbox.maps.EdgeInsets(150.0, 150.0, 150.0, 150.0)))
            }
        }
        drawRoute(showLoading = animateCamera)
        updateSwapButtonVisibility(); updateUseCurrentLocationButtonVisibility()
    }

    private fun setStartMarkerWithSelection(position: LatLng) { setStartMarker(position); hasSelectedStartPoint = true }
    private fun updateSwapButtonVisibility() { binding.swapButtonContainer.visibility = if (mapController.hasStartMarker() && mapController.hasDestinationMarker() && currentMode != AppMode.NAVIGATION) View.VISIBLE else View.GONE }

    private fun enterRoutePlanMode() {
        currentMode = AppMode.ROUTE_PLAN; hasSelectedStartPoint = false
        binding.startSearchContainer.visibility = View.VISIBLE; binding.startSearch.requestFocus()
        binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE }; binding.actionButton.visibility = View.GONE; binding.addFavoriteButton.visibility = View.GONE
        updateMarkersDraggableState(); updateUseCurrentLocationButtonVisibility(); updateReplaceLocationButtonVisibility()
    }

    private fun updateUseCurrentLocationButtonVisibility() {
        if (currentMode != AppMode.SEARCH && currentMode != AppMode.ROUTE_PLAN) { binding.useCurrentLocationContainer.visibility = View.GONE; return }
        if (currentMode == AppMode.SEARCH && !mapController.hasDestinationMarker()) { binding.useCurrentLocationContainer.visibility = View.GONE; return }
        val currentPos = if (isGpsSet) LatLng(PrefManager.getLat, PrefManager.getLng) else LatLng(lat, lon); val isFake = isGpsSet
        if (currentMode == AppMode.SEARCH) { binding.useCurrentLocationContainer.visibility = View.VISIBLE; binding.useCurrentLocationText.text = if (isFake) "Bắt đầu đi tới từ điểm hiện tại (Giả lập)" else "Bắt đầu đi tới từ điểm hiện tại (Thực tế)" } 
        else {
            val startLoc = mapController.getStartPosition(); val isSame = startLoc != null && Math.abs(startLoc.latitude - currentPos.latitude) < 1e-6 && Math.abs(startLoc.longitude - currentPos.longitude) < 1e-6
            binding.useCurrentLocationContainer.visibility = if (!isSame) View.VISIBLE else View.GONE
            if (!isSame) binding.useCurrentLocationText.text = "${if (isFake) "Dùng vị trí hiện tại (Giả lập)" else "Dùng vị tại hiện tại (Thực tế)"} (${String.format("%.4f", currentPos.latitude)}, ${String.format("%.4f", currentPos.longitude)})"
        }
    }

    private fun updateMarkersDraggableState() { val canDrag = currentMode != AppMode.NAVIGATION && !isDriving; mapController.setDestinationDraggable(canDrag); mapController.setStartDraggable(currentMode == AppMode.ROUTE_PLAN && canDrag) }

    private fun swapStartAndDestination() {
        val sPos = currentStartPos ?: return; val dPos = currentDestPos ?: return
        val sText = binding.startSearch.text.toString(); val dText = binding.destinationSearch.text.toString()
        currentStartPos = dPos; currentDestPos = sPos
        mapController.clearRoute()
        mapController.setStartMarker(currentStartPos!!, true)
        mapController.setDestinationMarker(currentDestPos!!, true)
        binding.startSearch.setText(dText); binding.destinationSearch.setText(sText)
        if (currentMode == AppMode.ROUTE_PLAN) { hasSelectedStartPoint = true; drawRoute() }
    }

    private fun drawRoute(showLoading: Boolean = true) {
        val start = currentStartPos ?: return; val dest = currentDestPos ?: return
        mapController.clearRoute(); binding.routeErrorCard.visibility = View.GONE
        if (showLoading) { binding.actionButton.visibility = View.GONE; binding.routeLoadingCard.visibility = View.VISIBLE; binding.routeLoadingProgressText.text = "Đang tìm tuyến đường..." }
        lifecycleScope.launch { delay(100); viewModel.calculateRoute(start, dest, currentVehicleType) }
    }

    private fun showRouteErrorUI() { binding.routeLoadingCard.visibility = View.GONE; binding.routeErrorCard.visibility = View.VISIBLE; binding.actionButton.visibility = View.GONE; binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE } }

    private fun cancelRoutePlan() {
        mapController.clearRoute(); mapController.clearStartMarker()
        viewModel.clearRoute() // FIX: Clear ViewModel data on manual cancel
        binding.startSearch.text.clear(); binding.startSearchContainer.visibility = View.GONE; binding.useCurrentLocationContainer.visibility = View.GONE
        binding.routeLoadingCard.visibility = View.GONE; binding.routeErrorCard.visibility = View.GONE; currentMode = AppMode.SEARCH; hasSelectedStartPoint = false
        updateActionButtonsVisibility(); binding.searchCard.visibility = View.VISIBLE; updateSwapButtonVisibility()
    }

    private fun startNavigation() {
        if (routingPoints.isEmpty()) return
        isDriving = true; isPaused = false; currentMode = AppMode.NAVIGATION; completedPathPoints.clear(); completedPathPoints.add(routingPoints.first())
        binding.actionButton.visibility = View.GONE; binding.navigationControlsCard.visibility = View.VISIBLE; binding.cancelRouteButton.visibility = View.GONE; binding.addFavoriteButton.visibility = View.GONE
        binding.searchCard.visibility = View.GONE; binding.swapButtonContainer.visibility = View.GONE; binding.getFakeLocation.visibility = View.GONE; binding.setLocationButton.visibility = View.GONE; binding.replaceLocationButton.visibility = View.GONE
        updateNavigationAddresses(); updateNavControlButtons(); isCameraFollowing = true; binding.cameraFollowToggle.visibility = View.VISIBLE; updateCameraFollowButton()
        totalRouteDistanceKm = calculateTotalRouteDistance(routingPoints); traveledDistanceKm = 0.0; lastDistancePosition = null; updateDistanceLabel(); updateMarkersDraggableState()
        Intent(this, LocationService::class.java).apply { action = LocationService.ACTION_START_FOREGROUND }.also { startService(it) }
        locationService?.startRouteSimulation(points = routingPoints, speedKmh = currentSpeed, onPosition = { pos -> runOnUiThread { handleNavigationUpdate(pos) } }, onComplete = { runOnUiThread { onNavigationComplete() } })
    }

    private fun handleNavigationUpdate(pos: LatLng) {
        val time = System.currentTimeMillis(); currentNavigationPosition = pos; updateTraveledDistance(pos); updateSpeedLabel(currentSpeed)
        if (PrefManager.isShowFakeIcon) updateFakeLocationMarker(pos)
        updateCompletedPath(pos)
        if (isCameraFollowing && time - lastCameraUpdateTime >= CAMERA_UPDATE_INTERVAL_MS) { mapboxMap.easeTo(CameraOptions.Builder().center(Point.fromLngLat(pos.longitude, pos.latitude)).build()); lastCameraUpdateTime = time }
        previousLocation = pos
    }

    private fun updateNavigationAddresses() { lifecycleScope.launch { val sPos = mapController.getStartPosition(); val dPos = mapController.getDestinationPosition(); if (sPos != null && dPos != null) { binding.navFromAddress.text = "• ${getAddressFromLocation(sPos)}"; binding.navToAddress.text = "• ${getAddressFromLocation(dPos)}" } } }

    private fun stopNavigation() { locationService?.stopRouteSimulation() }
    
    private fun updateSetLocationButton() { 
        isGpsSet = viewModel.isStarted 
        binding.setLocationButton.apply { 
            visibility = View.VISIBLE
            setImageResource(if (isGpsSet) R.drawable.ic_gps_off else R.drawable.ic_location_on) 
        }
        binding.getFakeLocation.visibility = if (isGpsSet && PrefManager.isShowFakeIcon && !isDriving) View.VISIBLE else View.GONE
        updateReplaceLocationButtonVisibility(); updateUseCurrentLocationButtonVisibility() 
    }

    private fun updateSpeedLabel(speed: Double) { try { val actual = io.github.mwarevn.fakegps.utils.SpeedSyncManager.getActualSpeed(); binding.speedLabel.text = if (actual > 0.01f) "${speed.toInt()} / ${actual.toInt()} km/h" else "${speed.toInt()} km/h" } catch (e: Exception) { binding.speedLabel.text = "${speed.toInt()} km/h" } }

    private fun updateReplaceLocationButtonVisibility() { val destPos = mapController.getDestinationPosition(); binding.replaceLocationButton.visibility = if (currentMode == AppMode.SEARCH && isGpsSet && destPos != null && currentFakeLocationPos != destPos) View.VISIBLE else View.GONE }

    private fun updateFakeLocationMarker(position: LatLng) { currentFakeLocationPos = position; if (!PrefManager.isShowFakeIcon) { mapController.updateFakeLocationMarker(position, false); return }; mapController.updateFakeLocationMarker(position, true); if (isCameraFollowing && isDriving) mapController.moveCamera(position, animate = true) }

    private fun restoreFakeLocationMarker() { if (isGpsSet) updateFakeLocationMarker(LatLng(PrefManager.getLat, PrefManager.getLng)) else mapController.updateFakeLocationMarker(LatLng(0.0, 0.0), false); updateSetLocationButton() }

    override fun onShowFakeIconToggled(show: Boolean) { if (!show) mapController.updateFakeLocationMarker(currentFakeLocationPos ?: LatLng(0.0, 0.0), false) else (if (isDriving) currentNavigationPosition else currentFakeLocationPos)?.let { updateFakeLocationMarker(it) }; updateSetLocationButton() }

    private fun drawCompletedPathLocally() { if (completedPathPoints.isNotEmpty()) mapController.drawCompletedPath(completedPathPoints, COMPLETED_ROUTE_COLOR, ROUTE_WIDTH + 1.0) }

    private fun updateCompletedPath(pos: LatLng) { if (completedPathPoints.isEmpty() || distanceBetween(completedPathPoints.last(), pos) >= 5.0) { completedPathPoints.add(pos); if (completedPathPoints.size % 3 == 0) mapController.drawCompletedPath(completedPathPoints, COMPLETED_ROUTE_COLOR, ROUTE_WIDTH + 1.0) } }

    private fun updateCameraFollowButton() { binding.cameraFollowToggle.setImageResource(if (isCameraFollowing) R.drawable.ic_camera_follow else R.drawable.ic_camera_free) }
    private fun distanceBetween(p1: LatLng, p2: LatLng): Double { val results = FloatArray(1); android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results); return results[0].toDouble() }
    private fun calculateTotalRouteDistance(points: List<LatLng>): Double { var dist = 0.0; for (i in 0 until points.size - 1) dist += distanceBetween(points[i], points[i + 1]); return dist / 1000.0 }
    private fun updateDistanceLabel() { binding.distanceLabel.text = String.format("%.2fkm/%.2fkm", traveledDistanceKm, totalRouteDistanceKm) }
    private fun updateTraveledDistance(pos: LatLng) { lastDistancePosition?.let { val d = distanceBetween(it, pos); if (d in 0.5..100.0) { traveledDistanceKm += d / 1000.0; updateDistanceLabel() } }; lastDistancePosition = pos }

    private fun onNavigationComplete() {
        isDriving = false; isPaused = false; binding.speedSlider.value = 52f; currentSpeed = 52.0; binding.speedLabel.text = "52 km/h"
        binding.navigationControlsCard.visibility = View.GONE; binding.cameraFollowToggle.visibility = View.GONE
        (currentNavigationPosition ?: routingPoints.lastOrNull() ?: mapController.getDestinationPosition())?.let { finalPos ->
            currentFakeLocationPos = finalPos; viewModel.update(true, finalPos.latitude, finalPos.longitude)
            updateFakeLocationMarker(finalPos)
        }
        binding.completionActionsCard.visibility = View.VISIBLE; updateSetLocationButton(); updateMarkersDraggableState()
    }

    private fun clearActiveNavigationData() {
        // FIX: Xoá route khỏi ViewModel để không bị khôi phục lại khi resume app
        viewModel.clearRoute()
        routingPoints = emptyList()
        
        mapController.clearRoute(); mapController.clearStartMarker(); mapController.clearDestinationMarker()
        completedPathPoints.clear(); currentMode = AppMode.SEARCH; hasSelectedStartPoint = false
        currentStartPos = null; currentDestPos = null; currentNavigationPosition = null
        
        binding.actionButton.visibility = View.GONE; binding.navigationControlsCard.visibility = View.GONE; binding.cameraFollowToggle.visibility = View.GONE
        binding.completionActionsCard.visibility = View.GONE; binding.startSearchContainer.visibility = View.GONE; binding.useCurrentLocationContainer.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE
        binding.destinationSearch.text.clear(); binding.startSearch.text.clear(); binding.searchCard.visibility = View.VISIBLE
        binding.addFavoriteButton.visibility = View.GONE; binding.routeLoadingCard.visibility = View.GONE; binding.routeErrorCard.visibility = View.GONE
        
        updateSetLocationButton(); updateMarkersDraggableState()
    }

    private fun onFinishNavigation() { clearActiveNavigationData() }

    private fun resetToSearchMode() {
        locationService?.stopRouteSimulation()
        isDriving = false; isPaused = false; isGpsSet = false; viewModel.update(false, 0.0, 0.0); stopService(Intent(this, LocationService::class.java))
        mapController.clearAllMarkers(); clearActiveNavigationData()
    }

    override fun onDestroy() { super.onDestroy(); if (isBound) { unbindService(serviceConnection); isBound = false }; serviceStateJob?.cancel(); PrefManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefChangeListener) }
    override fun onResume() { super.onResume(); PrefManager.sharedPreferences.registerOnSharedPreferenceChangeListener(prefChangeListener); updateNavigationUI() }

    private fun updateNavigationUI() { if (isDriving) { binding.navigationControlsCard.visibility = View.VISIBLE; binding.searchCard.visibility = View.GONE; binding.cameraFollowToggle.visibility = View.VISIBLE; binding.setLocationButton.visibility = View.GONE; binding.getFakeLocation.visibility = View.GONE; updateNavControlButtons(); updateSpeedLabel(currentSpeed); currentNavigationPosition?.let { updateFakeLocationMarker(it) }; updateMarkersDraggableState() } else { updateSetLocationButton() } }

    private fun onRestartNavigation() { binding.completionActionsCard.visibility = View.GONE; isDriving = false; isPaused = false; completedPathPoints.clear(); if (routingPoints.isNotEmpty()) startNavigation() else showToast("Dữ liệu đã mất") }
    private fun onStopNavigationEarly() { locationService?.stopRouteSimulation() }
    private fun toggleNavigationControls() { val expanded = !PrefManager.navControlsExpanded; PrefManager.navControlsExpanded = expanded; binding.navControlsExpandable.visibility = if (expanded) View.VISIBLE else View.GONE; binding.navControlsToggle.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more) }
    private fun restoreNavigationControlsState() { val expanded = PrefManager.navControlsExpanded; binding.navControlsExpandable.visibility = if (expanded) View.VISIBLE else View.GONE; binding.navControlsToggle.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more) }

    private fun getBitmapFromDrawable(resId: Int): Bitmap { val drawable = ContextCompat.getDrawable(this, resId); return if (drawable is BitmapDrawable) drawable.bitmap else { val bitmap = Bitmap.createBitmap(drawable!!.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); drawable.setBounds(0, 0, canvas.width, canvas.height); drawable.draw(canvas); bitmap } }
}
