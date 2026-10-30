package com.example.oeil.model

/** Local profile used to scope conversation history on this device. */
data class User(
    val id: String,
    val name: String,
    val email: String? = null
)
