package com.example

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val role: String? = "user"
)

@Serializable
data class AuthResponse(
    val token: String
)
