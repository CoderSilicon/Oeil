package com.example.oeil.data

import java.net.URI
import java.net.URISyntaxException

fun normalizeBackendUrl(value: String): String {
    val input = value.trim().trimEnd('/')
    if (input.isBlank()) return ""

    val uri = try {
        URI(input)
    } catch (error: URISyntaxException) {
        throw IllegalArgumentException("Enter a valid backend URL.", error)
    }
    require(uri.scheme?.equals("http", ignoreCase = true) == true ||
        uri.scheme?.equals("https", ignoreCase = true) == true) {
        "Backend URL must start with http:// or https://."
    }
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
        "Enter a backend URL with a host and no embedded credentials."
    }
    require(uri.rawQuery == null && uri.rawFragment == null) {
        "Backend URL must not include a query or fragment."
    }

    val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/chat")
    val authority = requireNotNull(uri.rawAuthority)
    return "${uri.scheme.lowercase()}://$authority$path".trimEnd('/')
}
