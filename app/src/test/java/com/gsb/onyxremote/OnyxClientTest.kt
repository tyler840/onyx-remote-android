package com.gsb.onyxremote

import com.gsb.onyxremote.net.OnyxClient
import com.gsb.onyxremote.net.OnyxClient.Link
import com.gsb.onyxremote.net.OnyxClient.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** Replies exactly as docs/ONYX_TELNET_REFERENCE.md records the real console doing. */
private class FakeOnyx(val cuelists: Set<Int> = setOf(1, 2)) : AutoCloseable {
    val server = ServerSocket(0)
    val port get() = server.localPort
    val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val clients = Collections.synchronizedList(mutableListOf<Socket>())
    @Volatile var accepts = 0

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                clients += s
                accepts++
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) = try {
        val out = s.getOutputStream()
        fun say(vararg lines: String) = out.write(lines.joinToString("") { "$it\r\n" }.toByteArray())
        say("200 Welcome")
        s.getInputStream().bufferedReader().lineSequence().forEach { line ->
            received += line
            val parts = line.trim().split(' ', limit = 2)
            val cl = parts.getOrNull(1)?.split(',')?.firstOrNull()?.trim()?.toIntOrNull()
            when (parts[0].uppercase()) {
                "GQL", "GTQ", "SQL" -> say("200 Ok", if (cl in cuelists) "GOTOCUE COMMAND OK" else "GOTOCUE Cuelist Not Found", ".")
                "PQL", "RQL" -> say("222 Ok", if (cl in cuelists) "RELEASE COMMAND OK" else "RELEASE Cuelist Not Found", ".")
                "RAQL" -> say("200 Ok", "RELEASEALL COMMAND OK", ".")
                "SILENT" -> Unit
                else -> say("400-Unknown Command", "400 ${parts[0]}")
            }
        }
    } catch (_: Exception) {
    }

    fun dropClients() = synchronized(clients) { clients.forEach { it.close() }; clients.clear() }

    override fun close() {
        server.close()
        dropClients()
    }
}

class OnyxClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun client(onyx: FakeOnyx, idle: Int = 0) = OnyxClient(scope).apply {
        configure(OnyxClient.Settings(host = "127.0.0.1", port = onyx.port, heartbeatSec = 0, idleDisconnectSec = idle,
            connectTimeoutMs = 1000, replyTimeoutMs = 800))
    }

    @Test fun goAndGotoAreAcknowledged() = runBlocking {
        FakeOnyx().use { onyx ->
            val c = client(onyx)
            val r = c.send("GQL 1")
            assertEquals(Outcome.OK, r.outcome)
            assertEquals("GOTOCUE COMMAND OK", r.detail)
            assertEquals(Outcome.OK, c.send("GTQ 2,5").outcome)
            assertEquals(Outcome.OK, c.send("RQL 1").outcome) // 222 code is success too
            assertEquals(listOf("GQL 1", "GTQ 2,5", "RQL 1"), onyx.received.toList())
        }
    }

    @Test fun missingCuelistIsNotFoundDespite200() = runBlocking {
        FakeOnyx().use { onyx ->
            val r = client(onyx).send("GQL 77")
            assertEquals(Outcome.NOT_FOUND, r.outcome)
            assertTrue(r.detail.contains("Not Found"))
        }
    }

    @Test fun unknownCommandIsError() = runBlocking {
        FakeOnyx().use { onyx ->
            val r = client(onyx).send("BOGUS")
            assertEquals(Outcome.ERROR, r.outcome)
        }
    }

    @Test fun noServerFailsFastAsOffline() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // nothing listening
        val c = OnyxClient(scope).apply {
            configure(OnyxClient.Settings(host = "127.0.0.1", port = port, heartbeatSec = 0, connectTimeoutMs = 500))
        }
        val t0 = System.currentTimeMillis()
        val r = c.send("GQL 1")
        assertEquals(Outcome.OFFLINE, r.outcome)
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2500)
    }

    @Test fun silenceDropsTheLinkAndItReconnects() = runBlocking {
        FakeOnyx().use { onyx ->
            val c = client(onyx)
            assertEquals(Outcome.OK, c.send("GQL 1").outcome)
            assertEquals(Outcome.TIMEOUT, c.send("SILENT").outcome)
            withTimeout(5000) { c.link.first { it == Link.CONNECTED } }
            assertEquals(Outcome.OK, c.send("GQL 2").outcome)
        }
    }

    @Test fun reconnectsAfterOnyxDropsTheSession() = runBlocking {
        FakeOnyx().use { onyx ->
            val c = client(onyx)
            assertEquals(Outcome.OK, c.send("GQL 1").outcome)
            onyx.dropClients()
            // Heartbeat is off, so only the idle socket watch can notice the drop.
            val t0 = System.currentTimeMillis()
            while (onyx.accepts < 2 && System.currentTimeMillis() - t0 < 3000) Thread.sleep(20)
            assertEquals(2, onyx.accepts)
            withTimeout(5000) { c.link.first { it == Link.CONNECTED } }
            assertEquals(Outcome.OK, c.send("GQL 1").outcome)
        }
    }

    @Test fun onDemandModeConnectsPerPress() = runBlocking {
        FakeOnyx().use { onyx ->
            val c = client(onyx, idle = 1)
            assertEquals(Link.OFFLINE, c.link.value)
            assertEquals(Outcome.OK, c.send("GQL 1").outcome)
            withTimeout(4000) { c.link.first { it == Link.OFFLINE } } // idle close
            assertEquals(Outcome.OK, c.send("GQL 2").outcome)
        }
    }

    @Test fun classifyMatchesReference() {
        fun cls(vararg l: String) = OnyxClient.classify("x", l.toList(), true).outcome
        assertEquals(Outcome.OK, cls("200 Ok", "GOTOCUE COMMAND OK", "."))
        assertEquals(Outcome.NOT_FOUND, cls("200 Ok", "GOTOCUE Cuelist Not Found", "."))
        assertEquals(Outcome.ERROR, cls("400-Unknown Command", "400 BOGUSCMD"))
        assertTrue(OnyxClient.isTerminator("."))
        assertTrue(OnyxClient.isTerminator("400 BOGUSCMD"))
        assertTrue(!OnyxClient.isTerminator("400-Unknown Command"))
    }
}
