package com.gsb.onyxremote.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Onyx telnet remote, per docs/ONYX_TELNET_REFERENCE.md: plain TCP, CRLF lines,
 * a "200 Welcome" banner, and replies that end with a "." line (or, for an
 * unknown command, a final "4xx " line).
 *
 * A single worker owns the socket and runs commands strictly one at a time, so
 * every reply is matched to the command that caused it.
 *
 * Commands are never held for later: a press while the link is down fails at
 * once, and a request that could not start promptly is dropped unsent. A cue
 * that fires a minute after someone pressed it is worse than one that visibly
 * fails.
 */
class OnyxClient(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Settings(
        val host: String = "",
        val port: Int = 23,
        /** Seconds between liveness probes while connected and idle; 0 = never. */
        val heartbeatSec: Int = 10,
        val probeCommand: String = "GTQ 9999,1",
        /** Close the socket after this many idle seconds and reconnect on demand; 0 = stay connected. */
        val idleDisconnectSec: Int = 0,
        val connectTimeoutMs: Int = 3000,
        val replyTimeoutMs: Int = 3000,
    )

    enum class Link { OFFLINE, CONNECTING, CONNECTED }

    enum class Outcome { OK, NOT_FOUND, ERROR, TIMEOUT, OFFLINE }

    data class Reply(val command: String, val outcome: Outcome, val lines: List<String>, val detail: String) {
        val ok get() = outcome == Outcome.OK
    }

    data class LogLine(val time: Long, val kind: Kind, val text: String) {
        enum class Kind { TX, RX, INFO, ERROR }
    }

    private class Request(val line: String, val deadline: Long, val result: CompletableDeferred<Reply>) {
        fun fail(outcome: Outcome, detail: String) {
            result.complete(Reply(line, outcome, emptyList(), detail))
        }
    }

    private val _link = MutableStateFlow(Link.OFFLINE)
    val link: StateFlow<Link> = _link.asStateFlow()

    /** Why the link is down, or null while healthy. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val requests = Channel<Request>(Channel.UNLIMITED)

    @Volatile private var settings = Settings()
    @Volatile private var socket: Socket? = null
    private var worker: Job? = null

    /** Apply new settings, reconnecting if they changed. Safe to call repeatedly. */
    fun configure(s: Settings) {
        if (s == settings && worker?.isActive == true) return
        settings = s
        val old = worker
        closeSocket() // unblocks a worker stuck in a socket read
        // Anything already queued is sent on the new link; each request's start-by
        // deadline still stops a stale press from firing late.
        worker = scope.launch(io) {
            old?.cancelAndJoin()
            run()
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        closeSocket()
        _link.value = Link.OFFLINE
        failQueued("Stopped")
    }

    /** Send one telnet line and wait for Onyx's answer. Never throws. */
    suspend fun send(line: String): Reply {
        val s = settings
        val startBy = System.currentTimeMillis() + s.connectTimeoutMs + 1000
        val req = Request(line.trim(), startBy, CompletableDeferred())
        if (requests.trySend(req).isFailure) return Reply(req.line, Outcome.OFFLINE, emptyList(), "Client stopped")
        val waitMs = (s.connectTimeoutMs + 1000 + s.replyTimeoutMs + 500).toLong()
        return withTimeoutOrNull(waitMs) { req.result.await() }
            ?: Reply(req.line, Outcome.TIMEOUT, emptyList(), "No reply from Onyx")
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    // ---- worker -------------------------------------------------------------

    private suspend fun run() {
        var backoff = 1000L
        while (true) {
            val s = settings
            if (s.host.isBlank()) {
                _link.value = Link.OFFLINE
                _problem.value = "No Onyx address set"
                requests.receive().fail(Outcome.OFFLINE, "No Onyx address set")
                continue
            }
            val onDemand = s.idleDisconnectSec > 0
            // On-demand mode connects only when there is something to send.
            val first = if (onDemand) requests.receive() else null

            _link.value = Link.CONNECTING
            val conn = try {
                Conn.open(s)
            } catch (e: IOException) {
                _link.value = Link.OFFLINE
                val msg = "Can't reach ${s.host}:${s.port} - ${reason(e)}"
                if (_problem.value != msg) note(LogLine.Kind.ERROR, msg)
                _problem.value = msg
                first?.fail(Outcome.OFFLINE, msg)
                failQueued(msg)
                if (!onDemand) {
                    // Back off, but answer any presses during the wait straight away.
                    withTimeoutOrNull(backoff) {
                        while (true) requests.receive().fail(Outcome.OFFLINE, msg)
                    }
                    backoff = (backoff * 2).coerceAtMost(10_000)
                }
                continue
            }

            socket = conn.socket
            backoff = 1000
            _link.value = Link.CONNECTED
            _problem.value = null
            note(LogLine.Kind.INFO, "Connected to ${s.host}:${s.port}" + (conn.banner?.let { " ($it)" } ?: ""))
            try {
                first?.let { exec(conn, it) }
                var lastActivity = System.currentTimeMillis()
                while (true) {
                    // Wake twice a second even when idle, so a session Onyx closes is
                    // noticed now rather than on the next press.
                    val req = withTimeoutOrNull(IDLE_POLL_MS) { requests.receive() }
                    val now = System.currentTimeMillis()
                    if (req != null) {
                        exec(conn, req)
                        lastActivity = System.currentTimeMillis()
                        continue
                    }
                    conn.poll()?.takeIf { it.isNotBlank() }?.let { note(LogLine.Kind.RX, it) }
                    if (onDemand) {
                        if (now - lastActivity >= s.idleDisconnectSec * 1000L) {
                            note(LogLine.Kind.INFO, "Idle - disconnected")
                            break
                        }
                    } else if (s.heartbeatSec > 0 && now - lastActivity >= s.heartbeatSec * 1000L) {
                        // A half-open link (cable pulled, PC asleep) only shows up as silence.
                        val probe = conn.transact(s.probeCommand, s.replyTimeoutMs)
                        if (probe.lines.isEmpty()) throw IOException("no reply to liveness probe")
                        lastActivity = System.currentTimeMillis()
                    }
                }
            } catch (e: IOException) {
                val msg = "Connection lost - ${reason(e)}"
                note(LogLine.Kind.ERROR, msg)
                _problem.value = msg
            } finally {
                conn.close()
                socket = null
                _link.value = Link.OFFLINE
            }
        }
    }

    private fun exec(conn: Conn, req: Request) {
        if (System.currentTimeMillis() > req.deadline) {
            req.fail(Outcome.TIMEOUT, "Dropped unsent - it waited too long")
            return
        }
        note(LogLine.Kind.TX, req.line)
        val reply = try {
            conn.transact(req.line, settings.replyTimeoutMs)
        } catch (e: IOException) {
            req.fail(Outcome.OFFLINE, "Connection lost")
            throw e
        }
        reply.lines.forEach { note(LogLine.Kind.RX, it) }
        req.result.complete(reply)
        // Silence on a real command means the link is dead, not that Onyx is thinking.
        if (reply.lines.isEmpty()) throw IOException("no reply to ${req.line}")
    }

    private fun failQueued(detail: String) {
        while (true) {
            val r = requests.tryReceive().getOrNull() ?: break
            r.fail(Outcome.OFFLINE, detail)
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun note(kind: LogLine.Kind, text: String) {
        val line = LogLine(System.currentTimeMillis(), kind, text)
        _log.update { (it + line).takeLast(LOG_LIMIT) }
    }

    /** Short, operator-readable cause; the raw Java messages run to a full line. */
    private fun reason(e: IOException): String {
        val m = e.message.orEmpty()
        return when {
            e is UnknownHostException -> "unknown host"
            "ECONNREFUSED" in m || "refused" in m -> "connection refused (is Onyx running?)"
            e is SocketTimeoutException || "ETIMEDOUT" in m -> "timed out (check the address and network)"
            "EHOSTUNREACH" in m || "ENETUNREACH" in m || e is NoRouteToHostException -> "no route to host (check the network)"
            "ECONNRESET" in m || "EPIPE" in m -> "connection reset"
            m.isNotBlank() -> m
            else -> e.javaClass.simpleName
        }
    }

    // ---- one TCP session ----------------------------------------------------

    private class Conn(val socket: Socket, var banner: String? = null) : Closeable {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private val partial = StringBuilder()

        companion object {
            fun open(s: Settings): Conn {
                val sock = Socket()
                try {
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.connect(InetSocketAddress(s.host, s.port), s.connectTimeoutMs)
                    val c = Conn(sock)
                    // Swallow the "200 Welcome" banner; tolerate a console that sends none.
                    val deadline = System.currentTimeMillis() + 1500
                    while (true) {
                        val line = c.readLine(deadline) ?: break
                        if (line.isNotBlank()) { c.banner = line.trim(); break }
                    }
                    return c
                } catch (e: IOException) {
                    sock.close()
                    throw e
                }
            }
        }

        fun transact(line: String, timeoutMs: Int): Reply {
            drain()
            output.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
            output.flush()
            val deadline = System.currentTimeMillis() + timeoutMs
            val lines = ArrayList<String>()
            var complete = false
            while (true) {
                val l = readLine(deadline) ?: break
                if (l.isBlank()) continue
                lines += l
                if (isTerminator(l)) { complete = true; break }
            }
            return classify(line, lines, complete)
        }

        /** Non-blocking check while idle: an unsolicited line, or null. Throws if Onyx hung up. */
        fun poll(): String? = readLine(System.currentTimeMillis() + 5)

        /** Discard anything unsolicited so it can't be mistaken for the next reply. */
        private fun drain() {
            while (input.available() > 0) {
                if (readLine(System.currentTimeMillis() + 50) == null) break
            }
            partial.setLength(0)
        }

        /** One line without its CR/LF, or null at the deadline. Throws if Onyx hung up. */
        fun readLine(deadline: Long): String? {
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val b = try { input.read() } catch (_: SocketTimeoutException) { return null }
                when {
                    b < 0 -> throw EOFException("Onyx closed the connection")
                    b == IAC -> skipTelnetCommand()
                    b == '\n'.code -> {
                        val l = partial.toString().trimEnd('\r')
                        partial.setLength(0)
                        return l
                    }
                    else -> partial.append(b.toChar())
                }
            }
        }

        // Telnet option negotiation (IAC ...) isn't part of Onyx's text protocol; ignore it.
        private fun skipTelnetCommand() {
            val cmd = input.read()
            if (cmd in 251..254) input.read()
        }

        override fun close() {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    companion object {
        private const val IAC = 255
        private const val LOG_LIMIT = 400
        private const val IDLE_POLL_MS = 500L
        private val CODE_FINAL = Regex("^[45]\\d\\d( |$)")
        private val CODE_ERROR = Regex("^[45]\\d\\d")

        fun isTerminator(line: String): Boolean = line.trim() == "." || CODE_FINAL.containsMatchIn(line.trim())

        /**
         * Onyx answers "200 Ok" even for a cuelist that doesn't exist, so judge the
         * text, not the code: "... COMMAND OK" is success, "... Not Found" is not.
         */
        fun classify(command: String, lines: List<String>, complete: Boolean): Reply {
            val body = lines.map { it.trim() }.filter { it != "." }
            fun find(s: String) = body.firstOrNull { it.contains(s, ignoreCase = true) }
            find("COMMAND OK")?.let { return Reply(command, Outcome.OK, lines, it) }
            find("not found")?.let { return Reply(command, Outcome.NOT_FOUND, lines, it) }
            (find("unknown command") ?: body.firstOrNull { CODE_ERROR.containsMatchIn(it) })?.let {
                return Reply(command, Outcome.ERROR, lines, it)
            }
            if (body.isEmpty()) return Reply(command, Outcome.TIMEOUT, lines, "No reply from Onyx")
            // A 2xx reply with no recognised text (e.g. "help") still means it was accepted.
            if (body.first().startsWith("2")) return Reply(command, Outcome.OK, lines, body.getOrNull(1) ?: body.first())
            return Reply(command, if (complete) Outcome.ERROR else Outcome.TIMEOUT, lines, body.last())
        }
    }
}
