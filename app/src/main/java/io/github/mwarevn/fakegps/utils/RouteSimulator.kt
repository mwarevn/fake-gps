package io.github.mwarevn.fakegps.utils

import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.TrafficLightState
import io.github.mwarevn.fakegps.domain.model.TrafficLightStatus
import io.github.mwarevn.fakegps.domain.model.TrafficLightNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.*
import kotlin.random.Random

class RouteSimulator(
    private val points: List<LatLng>,
    private var targetSpeedKmh: Double = 52.0,
    private val updateIntervalMs: Long = 300L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var job: Job? = null
    private var paused: Boolean = false
    private var totalDistanceMeters = 0.0
    private var currentActualSpeedKmh = 0.0

    // Traffic Light Simulation State
    var isAutoLightEnabled: Boolean = false
        set(value) {
            field = value
            if (value && trafficLightPoints.value.isEmpty()) {
                generateAllTrafficLights()
            } else if (!value) {
                trafficLightPoints.value = emptyList<TrafficLightNode>()
                upcomingStopDistance = null
                upcomingStopLocation = null
                upcomingStopWaitTime = 0
                skipRequested = false
            }
        }

    val trafficLightPoints = MutableStateFlow<List<TrafficLightNode>>(emptyList())
    
    private var upcomingStopDistance: Double? = null
    private var upcomingStopLocation: LatLng? = null
    private var upcomingStopWaitTime: Int = 0
    private var trafficLightWaitSeconds: Int = 0
    private var skipRequested: Boolean = false
    private var trafficLightStatus: TrafficLightStatus = TrafficLightStatus.MOVING
    var onTrafficLightUpdate: ((TrafficLightState) -> Unit)? = null

    // Cấu hình giảm tốc độ khi cua
    companion object {
        private const val MIN_SPEED_KMH = 5.0
        private const val CURVE_SPEED_MAX = 40.0 // Tốc độ tối đa khi cua góc rộng
        private const val SMOOTH_FACTOR = 0.15 // Tốc độ thay đổi (acceleration/deceleration)
        private const val LOOK_AHEAD_POINTS = 3 // Số điểm nhìn trước để đoán cua
    }

    init {
        for (i in 0 until points.size - 1) {
            totalDistanceMeters += PolylineUtils.haversineDistanceMeters(points[i], points[i+1])
        }
        currentActualSpeedKmh = targetSpeedKmh
        generateAllTrafficLights()
    }

    private fun generateAllTrafficLights() {
        if (!isAutoLightEnabled || points.isEmpty() || totalDistanceMeters < 500.0) {
            trafficLightPoints.value = emptyList()
            return
        }
        val nodes = mutableListOf<TrafficLightNode>()
        
        var currentD = Random.nextDouble(400.0, 1000.0)
        while (currentD < totalDistanceMeters - 200.0) {
            val triggered = Random.nextDouble() < 0.4
            if (triggered) {
                val point = getPointAtDistance(currentD)
                if (point != null) {
                    val waitTime = Random.nextInt(8, 36) // Pre-calculate wait seconds globally
                    nodes.add(TrafficLightNode(point, currentD, waitTime))
                }
            }
            
            // Generate next gap
            currentD += Random.nextDouble(400.0, 1000.0)
        }
        
        trafficLightPoints.value = nodes
    }

    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val dLng = to.longitude - from.longitude
        val y = sin(Math.toRadians(dLng)) * cos(Math.toRadians(to.latitude))
        val x = cos(Math.toRadians(from.latitude)) * sin(Math.toRadians(to.latitude)) - 
                sin(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) * cos(Math.toRadians(dLng))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun calculateAngleChange(p1: LatLng, p2: LatLng, p3: LatLng): Double {
        val b1 = calculateBearing(p1, p2)
        val b2 = calculateBearing(p2, p3)
        var diff = abs(b1 - b2)
        if (diff > 180) diff = 360 - diff
        return diff
    }

    private fun getPointAtDistance(targetMeters: Double): LatLng? {
        if (points.isEmpty()) return null
        if (targetMeters <= 0.0) return points.first()

        var currentDist = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i+1]
            val segLen = PolylineUtils.haversineDistanceMeters(a, b)
            
            if (currentDist + segLen >= targetMeters) {
                val excess = targetMeters - currentDist
                val frac = (excess / segLen).coerceIn(0.0, 1.0)
                return LatLng(
                    a.latitude + (b.latitude - a.latitude) * frac,
                    a.longitude + (b.longitude - a.longitude) * frac
                )
            }
            currentDist += segLen
        }
        return points.last()
    }

    private fun getRecommendedSpeedForCurve(points: List<LatLng>, currentIndex: Int): Double {
        if (!PrefManager.autoCurveSpeed) return targetSpeedKmh
        
        var maxAngleChange = 0.0
        val p1 = points[currentIndex]
        var p2: LatLng? = null
        var p3: LatLng? = null
        
        // Find p2 and p3 ahead by roughly 15m and 30m distance to avoid micro-segment noise
        var distAccumulated = 0.0
        for (i in currentIndex until points.size - 1) {
            val d = PolylineUtils.haversineDistanceMeters(points[i], points[i+1])
            distAccumulated += d
            if (p2 == null && distAccumulated >= 15.0) {
                p2 = points[i+1]
            } else if (p2 != null && distAccumulated >= 30.0) {
                p3 = points[i+1]
                break
            }
        }
        
        if (p2 != null && p3 != null) {
            val angle = calculateAngleChange(p1, p2, p3)
            if (angle > maxAngleChange) maxAngleChange = angle
        }

        return when {
            maxAngleChange > 70 -> 10.0 // Cua cực gắt (U-turn)
            maxAngleChange > 45 -> 18.0 // Cua gắt
            maxAngleChange > 25 -> 28.0 // Cua vừa
            maxAngleChange > 10 -> 38.0 // Cua nhẹ
            else -> targetSpeedKmh // Đường thẳng
        }.coerceAtMost(targetSpeedKmh)
    }

    private fun updateTrafficLightState(status: TrafficLightStatus, remaining: Int, canSkip: Boolean) {
        if (trafficLightStatus != status || remaining != trafficLightWaitSeconds) {
            trafficLightStatus = status
            trafficLightWaitSeconds = remaining
            onTrafficLightUpdate?.invoke(TrafficLightState(status, remaining, canSkip, upcomingStopLocation))
        }
    }

    fun skipCurrentTrafficLight(location: LatLng? = null) {
        if (location != null) {
            // User explicitly tapped a global label to skip it ahead of time
            trafficLightPoints.value = trafficLightPoints.value.filter { it.location != location }
            
            // Allow bypassing logic if it was our immediate next stop
            if (upcomingStopLocation == location) {
                skipRequested = true
            }
        } else if (trafficLightStatus == TrafficLightStatus.APPROACHING || trafficLightStatus == TrafficLightStatus.WAITING) {
            skipRequested = true
        }
    }

    fun start(onUpdate: (position: LatLng, progress: Int) -> Unit = { _, _ -> }, onComplete: (() -> Unit)? = null) {
        stop()
        if (points.size < 2) return

        job = scope.launch {
            var idx = 0
            var accumulatedDistanceOfPastSegments = 0.0
            var traveledInSeg = 0.0 // Carried over to next segment properly
            
            while (idx < points.size - 1 && isActive) {
                val a = points[idx]
                val b = points[idx + 1]
                val segMeters = PolylineUtils.haversineDistanceMeters(a, b)
                if (segMeters <= 0.1) { 
                    accumulatedDistanceOfPastSegments += segMeters
                    idx++
                    continue 
                }

                while (traveledInSeg < segMeters && isActive) {
                    if (paused) { delay(500L); continue }

                    val totalTraveledMeters = accumulatedDistanceOfPastSegments + traveledInSeg.coerceAtMost(segMeters)
                    
                    // --- Traffic Light Logic ---
                    var speedLimit = targetSpeedKmh

                    if (isAutoLightEnabled) {
                        if (upcomingStopDistance == null) {
                            // Find the NEXT traffic light we haven't reached yet
                            val nextNode = trafficLightPoints.value.firstOrNull { it.distance > totalTraveledMeters }
                            
                            // ONLY prime the stop logic when we are within 100m of it
                            if (nextNode != null && nextNode.distance - totalTraveledMeters <= 100.0) {
                                upcomingStopDistance = nextNode.distance
                                upcomingStopLocation = nextNode.location
                                upcomingStopWaitTime = nextNode.waitSeconds
                                updateTrafficLightState(TrafficLightStatus.MOVING, 0, false)
                            }
                        }

                        if (upcomingStopDistance != null && !skipRequested) {
                            val distToStop = (upcomingStopDistance!! - totalTraveledMeters).coerceAtLeast(0.0)
                            
                            if (distToStop <= 0.5) {
                                // Full stop reached
                                currentActualSpeedKmh = 0.0
                                var waitSeconds = upcomingStopWaitTime
                                
                                while (waitSeconds > 0 && isAutoLightEnabled && !skipRequested && isActive) {
                                    updateTrafficLightState(TrafficLightStatus.WAITING, waitSeconds, true)
                                    delay(1000L)
                                    waitSeconds--
                                }
                                
                                // Done waiting or skipped
                                trafficLightPoints.value = trafficLightPoints.value.filter { it.location != upcomingStopLocation }
                                upcomingStopDistance = null
                                upcomingStopLocation = null
                                upcomingStopWaitTime = 0
                                skipRequested = false
                                updateTrafficLightState(TrafficLightStatus.PASSED, 0, false)
                                delay(1000L) // Show PASSED for a bit before MOVING
                                updateTrafficLightState(TrafficLightStatus.MOVING, 0, false)
                                continue // Recalculate movement
                            } else if (distToStop <= 80.0) {
                                updateTrafficLightState(TrafficLightStatus.APPROACHING, 0, true)
                                // Decelerate based on distance left. At 20m, drop drastically
                                val maxAllowedSpeed = if (distToStop > 20.0) {
                                    (distToStop / 80.0) * targetSpeedKmh
                                } else {
                                    (distToStop / 20.0) * 15.0 // very slow < 15kmh in last 20m
                                }
                                speedLimit = maxAllowedSpeed.coerceAtLeast(0.0)
                            }
                        } else if (upcomingStopDistance != null && skipRequested) {
                            // Skipped before full stop
                            trafficLightPoints.value = trafficLightPoints.value.filter { it != upcomingStopLocation }
                            upcomingStopDistance = null
                            upcomingStopLocation = null
                            skipRequested = false
                            updateTrafficLightState(TrafficLightStatus.PASSED, 0, false)
                            delay(1000L)
                            updateTrafficLightState(TrafficLightStatus.MOVING, 0, false)
                        }
                    } else if (upcomingStopDistance != null) {
                        // Feature was disabled while approaching/waiting
                        upcomingStopDistance = null
                        upcomingStopLocation = null
                        skipRequested = false
                        updateTrafficLightState(TrafficLightStatus.MOVING, 0, false)
                    }

                    // --- Curve speed limit ---
                    val recommendedSpeed = getRecommendedSpeedForCurve(points, idx).coerceAtMost(speedLimit)

                    // Thay đổi tốc độ từ từ (Smooth Transition)
                    if (currentActualSpeedKmh < recommendedSpeed) {
                        currentActualSpeedKmh += (recommendedSpeed - currentActualSpeedKmh) * SMOOTH_FACTOR
                    } else if (currentActualSpeedKmh > recommendedSpeed) {
                        // When stopping for red light, we can decelerate faster
                        val decelFactor = if (speedLimit < 5.0) SMOOTH_FACTOR * 3 else SMOOTH_FACTOR
                        currentActualSpeedKmh -= (currentActualSpeedKmh - recommendedSpeed) * decelFactor
                    }
                    
                    // Allow dropping below MIN_SPEED_KMH if we are actually stopping
                    val minSpeed = if (speedLimit < MIN_SPEED_KMH) 0.0 else MIN_SPEED_KMH
                    currentActualSpeedKmh = currentActualSpeedKmh.coerceIn(minSpeed, targetSpeedKmh)

                    val bearing = calculateBearing(a, b).toFloat()
                    val interval = updateIntervalMs + (Math.random() * 50).toLong()
                    
                    val stepMeters = (currentActualSpeedKmh * 1000.0 / 3600.0) * (interval.toDouble() / 1000.0)
                    if (stepMeters <= 0.0 && currentActualSpeedKmh <= 0.0) {
                        // Explicitly avoid dead loop if speed is 0 but we are supposed to move
                        if (upcomingStopDistance == null) currentActualSpeedKmh = MIN_SPEED_KMH
                    }
                    
                    traveledInSeg += stepMeters
                    
                    val frac = (traveledInSeg / segMeters).coerceIn(0.0, 1.0)
                    val currentPos = LatLng(
                        a.latitude + (b.latitude - a.latitude) * frac,
                        a.longitude + (b.longitude - a.longitude) * frac
                    )
                    
                    SpeedSyncManager.updateActualSpeed(currentActualSpeedKmh.toFloat())
                    SpeedSyncManager.updateBearing(bearing)
                    SpeedSyncManager.updateCurveReduction((currentActualSpeedKmh / targetSpeedKmh).toFloat())

                    PrefManager.update(
                        start = true, la = currentPos.latitude, ln = currentPos.longitude,
                        bearing = bearing, speed = SpeedSyncManager.speedKmhToMs(currentActualSpeedKmh.toFloat())
                    )

                    val progress = if (totalDistanceMeters > 0) ((totalTraveledMeters / totalDistanceMeters) * 100).toInt().coerceIn(0, 100) else 0
                    onUpdate(currentPos, progress)
                    delay(interval)
                }
                
                // End of segment logic: push mathematical counters safely
                accumulatedDistanceOfPastSegments += segMeters
                traveledInSeg -= segMeters // Carry over the physical overflow overshoot to the next micro-segment smoothly
                idx++
            }
            onComplete?.invoke()
        }
    }

    fun setSpeed(v: Double) { 
        targetSpeedKmh = v 
    }
    fun pause() { paused = true }
    fun resume() { paused = false }
    fun stop() { job?.cancel(); job = null }
}
