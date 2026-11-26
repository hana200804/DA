package com.signa.app.domain.model

data class User(
    val id: String,
    val displayName: String,
    val email: String,
    val vehicleType: VehicleType = VehicleType.CAR,
    val role: String? = null,
    val permissions: List<String> = emptyList()
)
