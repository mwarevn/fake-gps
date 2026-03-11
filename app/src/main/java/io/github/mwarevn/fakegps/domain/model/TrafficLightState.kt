package io.github.mwarevn.fakegps.domain.model

enum class TrafficLightStatus {
    MOVING,
    APPROACHING,
    WAITING,
    PASSED
}

data class TrafficLightState(
    val status: TrafficLightStatus = TrafficLightStatus.MOVING,
    val remainingSeconds: Int = 0,
    val canSkip: Boolean = false,
    val lightPosition: LatLng? = null
)

data class TrafficLightNode(
    val location: LatLng,
    val distance: Double,
    val waitSeconds: Int
)
