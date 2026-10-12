package com.example

import com.example.data.engine.OkHttpProvider
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network integration tests using OkHttp MockWebServer for scenarios:
 * 1. Abrupt network disconnection / dropped connection (SocketPolicy.DISCONNECT_AT_START)
 * 2. Slow server response (throttleBody)
 * 3. HTTP 403 Forbidden error response
 * 4. Successful HTTP 206 Partial Content Range response
 */
class NetworkIntegrationMockServerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `abrupt network disconnection triggers IOException gracefully`() {
        server.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )

        val request = Request.Builder()
            .url(server.url("/test-disconnect"))
            .build()

        var caughtException = false
        try {
            OkHttpProvider.client.newCall(request).execute()
        } catch (e: IOException) {
            caughtException = true
        }
        assertTrue("Dropped connection should throw IOException", caughtException)
    }

    @Test
    fun `server returning 403 Forbidden is captured correctly`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Access Denied / Forbidden")
        )

        val request = Request.Builder()
            .url(server.url("/secure-file.bin"))
            .build()

        val response = OkHttpProvider.client.newCall(request).execute()
        assertEquals(403, response.code)
        assertFalse(response.isSuccessful)
        response.close()
    }

    @Test
    fun `slow server response with throttled bytes streams without failure`() {
        val payload = "Slow streaming data payload for throttle test"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .throttleBody(10, 50, TimeUnit.MILLISECONDS)
                .setBody(payload)
        )

        val request = Request.Builder()
            .url(server.url("/slow-stream"))
            .build()

        val response = OkHttpProvider.client.newCall(request).execute()
        assertEquals(200, response.code)
        val bodyString = response.body?.string().orEmpty()
        assertEquals(payload, bodyString)
        response.close()
    }

    @Test
    fun `server returning 206 Partial Content Range header responds accurately`() {
        val partialPayload = "RangeBytes500to1000"
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 500-1000/2000")
                .setHeader("Content-Length", partialPayload.length.toString())
                .setBody(partialPayload)
        )

        val request = Request.Builder()
            .url(server.url("/range-download"))
            .header("Range", "bytes=500-1000")
            .build()

        val response = OkHttpProvider.client.newCall(request).execute()
        assertEquals(206, response.code)
        assertEquals("bytes 500-1000/2000", response.header("Content-Range"))
        val bodyContent = response.body?.string()
        assertEquals(partialPayload, bodyContent)
        response.close()
    }
}
