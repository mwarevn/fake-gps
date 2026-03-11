package io.github.mwarevn.fakegps.data.map

import android.graphics.Bitmap
import io.github.mwarevn.fakegps.domain.map.IMapController
import io.github.mwarevn.fakegps.domain.model.LatLng
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.*
import com.mapbox.maps.viewannotation.ViewAnnotationManager
import com.mapbox.maps.ViewAnnotationOptions
import com.mapbox.maps.AnnotatedFeature
import io.github.mwarevn.fakegps.R
import android.view.View
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.ImageView
import io.github.mwarevn.fakegps.domain.model.TrafficLightNode

class MapboxController(
    private val mapView: MapView,
    private val mapboxMap: MapboxMap,
    private val pointAnnotationManager: PointAnnotationManager,
    private val polylineAnnotationManager: PolylineAnnotationManager,
    private val viewAnnotationManager: ViewAnnotationManager,
    private val icons: MapIcons
) : IMapController {

    data class MapIcons(
        val locationIcon: Bitmap,
        val destinationIcon: Bitmap,
        val startIcon: Bitmap,
        val trafficLightIcon: Bitmap
    )

    private var currentLocationMarker: PointAnnotation? = null
    private var startMarker: PointAnnotation? = null
    private var destinationMarker: PointAnnotation? = null
    private var currentRouteLine: PolylineAnnotation? = null
    private var completedPolyline: PolylineAnnotation? = null
    
    // Map to hold references to all drawn traffic light ViewAnnotations by coordinates signature
    private val trafficLightViews = mutableMapOf<String, View>()

    override fun moveCamera(position: LatLng, zoom: Double?, animate: Boolean) {
        val builder = CameraOptions.Builder()
            .center(Point.fromLngLat(position.longitude, position.latitude))
        
        if (zoom != null) {
            builder.zoom(zoom)
        }
            
        val cameraOptions = builder.build()
        if (animate) {
            mapView.camera.easeTo(cameraOptions)
        } else {
            mapboxMap.setCamera(cameraOptions)
        }
    }

    override fun loadStyle(styleUri: String, onComplete: () -> Unit) {
        mapboxMap.loadStyleUri(styleUri) {
            onComplete()
        }
    }

    override fun addOnCameraChangeListener(listener: (Double) -> Unit) {
        mapboxMap.subscribeCameraChanged {
            listener(mapboxMap.cameraState.bearing)
        }
    }

    override fun updateFakeLocationMarker(position: LatLng, visible: Boolean) {
        if (!visible) {
            currentLocationMarker?.let { pointAnnotationManager.delete(it) }
            currentLocationMarker = null
            return
        }
        val newPoint = Point.fromLngLat(position.longitude, position.latitude)
        if (currentLocationMarker != null) {
            currentLocationMarker?.point = newPoint
            pointAnnotationManager.update(currentLocationMarker!!)
        } else {
            val options = PointAnnotationOptions()
                .withPoint(newPoint)
                .withIconImage(icons.locationIcon)
                .withIconSize(1.5)
                .withIconAnchor(IconAnchor.CENTER)
                .withDraggable(false)
            currentLocationMarker = pointAnnotationManager.create(options)
        }
    }

    override fun setDestinationMarker(position: LatLng, visible: Boolean) {
        destinationMarker?.let { pointAnnotationManager.delete(it) }
        if (!visible) {
            destinationMarker = null
            return
        }
        val options = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(position.longitude, position.latitude))
            .withIconImage(icons.destinationIcon)
            .withIconSize(2.5)
            .withIconAnchor(IconAnchor.BOTTOM)
            .withDraggable(true)
        destinationMarker = pointAnnotationManager.create(options)
    }

    override fun setStartMarker(position: LatLng, visible: Boolean) {
        startMarker?.let { pointAnnotationManager.delete(it) }
        if (!visible) {
            startMarker = null
            return
        }
        val options = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(position.longitude, position.latitude))
            .withIconImage(icons.startIcon)
            .withIconSize(2.5)
            .withIconAnchor(IconAnchor.BOTTOM)
            .withDraggable(true)
        startMarker = pointAnnotationManager.create(options)
    }

    override fun setDestinationDraggable(draggable: Boolean) {
        destinationMarker?.isDraggable = draggable
    }
    
    override fun setStartDraggable(draggable: Boolean) {
        startMarker?.isDraggable = draggable
    }

    override fun clearDestinationMarker() {
        destinationMarker?.let { pointAnnotationManager.delete(it) }
        destinationMarker = null
    }

    override fun clearStartMarker() {
        startMarker?.let { pointAnnotationManager.delete(it) }
        startMarker = null
    }

    override fun clearAllMarkers() {
        currentLocationMarker?.let { pointAnnotationManager.delete(it) }
        startMarker?.let { pointAnnotationManager.delete(it) }
        destinationMarker?.let { pointAnnotationManager.delete(it) }
        clearAllTrafficLightLabels()
        currentLocationMarker = null
        startMarker = null
        destinationMarker = null
    }

    override fun hasDestinationMarker(): Boolean = destinationMarker != null
    override fun hasStartMarker(): Boolean = startMarker != null

    override fun getDestinationPosition(): LatLng? = destinationMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }
    override fun getStartPosition(): LatLng? = startMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }

    override fun getDestinationId(): String? = destinationMarker?.id
    override fun getStartId(): String? = startMarker?.id

    override fun drawRoute(points: List<LatLng>, color: String, width: Double) {
        clearRoute()
        val routePoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        val options = PolylineAnnotationOptions()
            .withPoints(routePoints)
            .withLineColor(color)
            .withLineWidth(width)
        currentRouteLine = polylineAnnotationManager.create(options)
    }

    override fun drawCompletedPath(points: List<LatLng>, color: String, width: Double) {
        val routePoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        if (completedPolyline != null) {
            completedPolyline?.points = routePoints
            polylineAnnotationManager.update(completedPolyline!!)
        } else {
            val options = PolylineAnnotationOptions()
                .withPoints(routePoints)
                .withLineColor(color)
                .withLineWidth(width)
                .withLineSortKey(10.0) // Guarantee this renders ABOVE the regular route
            completedPolyline = polylineAnnotationManager.create(options)
        }
    }

    override fun clearRoute() {
        currentRouteLine?.let { polylineAnnotationManager.delete(it) }
        completedPolyline?.let { polylineAnnotationManager.delete(it) }
        currentRouteLine = null
        completedPolyline = null
    }

    override fun checkPermissions(): Boolean {
        // This will be delegated back to Activity or handled via a permission checker
        return true 
    }

    override fun requestPermissions() {
        // Delegate to Activity
    }

    private fun getPosKey(position: LatLng): String = "${position.latitude}_${position.longitude}"

    override fun drawGlobalTrafficLightLabels(points: List<TrafficLightNode>, onSkipClick: (LatLng) -> Unit) {
        val validKeys = points.map { getPosKey(it.location) }.toSet()
        val currentKeys = trafficLightViews.keys.toList()
        
        // Remove outdated/skipped labels
        for (key in currentKeys) {
            if (key !in validKeys) {
                trafficLightViews.remove(key)?.let { view ->
                    try { viewAnnotationManager.removeViewAnnotation(view) } catch (e: Exception) {}
                }
            }
        }
        
        // Add or update valid labels
        points.forEach { node ->
            val key = getPosKey(node.location)
            var view = trafficLightViews[key]
            
            if (view == null) {
                view = LayoutInflater.from(mapView.context).inflate(R.layout.view_traffic_light_badge, mapView, false)
                view.setOnClickListener { onSkipClick(node.location) }
                view.findViewById<TextView>(R.id.traffic_label_text)?.text = "Bỏ qua ${node.waitSeconds}s"
                view.findViewById<ImageView>(R.id.traffic_icon)?.setImageResource(R.drawable.ic_traffic_light)
                
                val options = ViewAnnotationOptions.Builder()
                    .annotatedFeature(AnnotatedFeature(Point.fromLngLat(node.location.longitude, node.location.latitude)))
                    .allowOverlap(true)
                    .build()
                    
                viewAnnotationManager.addViewAnnotation(view, options)
                trafficLightViews[key] = view
            }
        }
    }

    override fun showTrafficLightLabel(position: LatLng, seconds: Int, onSkipClick: () -> Unit) {
        val key = getPosKey(position)
        var view = trafficLightViews[key]
        
        if (view == null) {
            // View doesn't exist yet, we create a new one
            view = LayoutInflater.from(mapView.context).inflate(R.layout.view_traffic_light_badge, mapView, false)
            val options = ViewAnnotationOptions.Builder()
                .annotatedFeature(AnnotatedFeature(Point.fromLngLat(position.longitude, position.latitude)))
                .allowOverlap(true)
                .build()
            viewAnnotationManager.addViewAnnotation(view, options)
            trafficLightViews[key] = view
        }
        
        view.setOnClickListener { onSkipClick() }
        val textView = view.findViewById<TextView>(R.id.traffic_label_text)
        
        // Show active seconds when counting, else show impending approach
        if (seconds > 0) {
            textView?.text = "Đèn đỏ ($seconds - Bỏ qua)"
        } else {
            textView?.text = "Chuẩn bị rẽ / dừng (Bỏ qua)"
        }
    }

    override fun removeTrafficLightLabelAt(position: LatLng) {
        val key = getPosKey(position)
        trafficLightViews.remove(key)?.let { view ->
            try {
                viewAnnotationManager.removeViewAnnotation(view)
            } catch (e: Exception) {}
        }
    }

    override fun clearAllTrafficLightLabels() {
        trafficLightViews.values.forEach { view ->
            try {
                viewAnnotationManager.removeViewAnnotation(view)
            } catch (e: Exception) {}
        }
        trafficLightViews.clear()
    }
}
