package com.example.oeil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendEndpointTest {

    @Test
    fun `removes trailing slash and chat endpoint`() {
        assertEquals(
            "https://example.test/api",
            normalizeBackendUrl("https://example.test/api/chat/")
        )
    }

    @Test
    fun `allows an empty url to disconnect the backend`() {
        assertEquals("", normalizeBackendUrl("  "))
    }

    @Test
    fun `rejects URLs with credentials`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeBackendUrl("https://user:password@example.test")
        }
    }
}
