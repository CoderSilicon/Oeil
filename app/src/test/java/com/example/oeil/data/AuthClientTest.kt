package com.example.oeil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthClientTest {

    @Test
    fun `oauth start URL targets selected provider and app callback`() {
        assertEquals(
            "https://api.example.test/auth/oauth/google/start?redirect_uri=oeil%3A%2F%2Fauth%2Fcallback",
            AuthClient("https://api.example.test").oauthStartUrl("google"),
        )
    }

    @Test
    fun `oauth start rejects unsupported providers`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthClient("https://api.example.test").oauthStartUrl("unknown")
        }
    }

    @Test
    fun `oauth start requires configured backend`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthClient("").oauthStartUrl("google")
        }
    }
}
