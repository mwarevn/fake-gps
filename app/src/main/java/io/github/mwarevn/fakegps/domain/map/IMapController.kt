package io.github.mwarevn.fakegps.domain.map

import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.TrafficLightNode

/**
 * Interface for abstraction of Map providers (Mapbox, Google Maps, etc.)
 */
interface IMapController {
    
    // Core Map interactions
    fun moveCamera(position: LatLng, zoom: Double? = null, animate: Boolean = true)
    fun loadStyle(styleUri: String, onComplete: () -> Unit)
    fun addOnCameraChangeListener(listener: (Double) -> Unit)
    
    // Markers & Overlays
    fun updateFakeLocationMarker(position: LatLng, visible: Boolean)
    fun setDestinationMarker(position: LatLng, visible: Boolean)
    fun setStartMarker(position: LatLng, visible: Boolean)
    fun setDestinationDraggable(draggable: Boolean)
    fun setStartDraggable(draggable: Boolean)
    fun clearDestinationMarker()
    fun clearStartMarker()
    fun clearAllMarkers()
    
    fun hasDestinationMarker(): Boolean
    fun hasStartMarker(): Boolean
    fun getDestinationPosition(): LatLng?
    fun getStartPosition(): LatLng?
    fun getDestinationId(): String?
    fun getStartId(): String?
    
    interface OnPointAnnotationDragListenerWrapper {
        fun onAnnotationDragFinished(id: String, point: com.mapbox.geojson.Point)
    }
    
    // Routing
    fun drawRoute(points: List<LatLng>, color: String, width: Double)
    fun drawCompletedPath(points: List<LatLng>, color: String, width: Double)
    fun clearRoute()
    
    // Permissions & State
    fun checkPermissions(): Boolean
    fun requestPermissions()

    // Traffic Light Overlay
    fun showTrafficLightLabel(position: LatLng, seconds: Int, onSkipClick: () -> Unit)
    fun drawGlobalTrafficLightLabels(points: List<TrafficLightNode>, onSkipClick: (LatLng) -> Unit)
    fun removeTrafficLightLabelAt(position: LatLng)
    fun clearAllTrafficLightLabels()
}
