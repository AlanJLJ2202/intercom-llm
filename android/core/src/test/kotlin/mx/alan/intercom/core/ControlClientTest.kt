package mx.alan.intercom.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mx.alan.intercom.core.control.CaptureContext
import mx.alan.intercom.core.control.ControlException
import mx.alan.intercom.core.control.HttpControlClient
import mx.alan.intercom.core.control.IdeaInput
import mx.alan.intercom.core.control.OutboxControlClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ControlClientTest {
    private val ctx = CaptureContext("guarda esta idea", "DRIVING", "2026-10-29T10:00:00-06:00", "s1")

    @Test
    fun postsIdeaWithBearerToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"idea_9","status":"stored"}"""))
            val c = HttpControlClient(server.url("/").toString(), "tok")
            val r = c.saveIdea(IdeaInput("traductor", listOf("viaje"), ctx))
            assertEquals("idea_9", r.id)
            val req = server.takeRequest()
            assertEquals("/v1/ideas", req.path)
            assertEquals("Bearer tok", req.getHeader("Authorization"))
            val body = jacksonObjectMapper().readTree(req.body.readUtf8())
            assertEquals("traductor", body["text"].asText())
            assertEquals("DRIVING", body["context"]["mode"].asText())
        }
    }

    @Test
    fun forbiddenIsNotRetryable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"scope"}"""))
            val c = OutboxControlClient(HttpControlClient(server.url("/").toString(), "tok"))
            val e = assertFailsWith<ControlException> { c.saveIdea(IdeaInput("x", emptyList(), ctx)) }
            assertTrue(!e.retryable)
            assertEquals(0, c.pendingCount())
        }
    }

    @Test
    fun offlineWritesAreQueuedAndFlushed() {
        val tmp = File.createTempFile("outbox", ".json").apply { delete() }
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString()
        server.shutdown() // simula pérdida de conectividad
        val offline = OutboxControlClient(HttpControlClient(url, "tok"), tmp)
        val r = offline.saveIdea(IdeaInput("idea sin red", emptyList(), ctx))
        assertTrue(r.queuedOffline)
        assertEquals(1, offline.pendingCount())

        MockWebServer().use { up ->
            up.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"idea_1"}"""))
            // Reabre la bandeja persistida con un servidor disponible.
            val online = OutboxControlClient(HttpControlClient(up.url("/").toString(), "tok"), tmp)
            assertEquals(1, online.pendingCount())
            assertEquals(1, online.flush())
            assertEquals(0, online.pendingCount())
            assertEquals("idea sin red", jacksonObjectMapper().readTree(up.takeRequest().body.readUtf8())["text"].asText())
        }
    }
}
