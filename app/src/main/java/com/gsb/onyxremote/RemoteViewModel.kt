package com.gsb.onyxremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gsb.onyxremote.model.AppConfig
import com.gsb.onyxremote.model.CmdType
import com.gsb.onyxremote.model.Page
import com.gsb.onyxremote.model.Rect
import com.gsb.onyxremote.model.Tile
import com.gsb.onyxremote.model.newId
import com.gsb.onyxremote.model.percentToLevel
import com.gsb.onyxremote.net.OnyxClient
import com.gsb.onyxremote.net.OnyxClient.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    enum class Screen { PANEL, SETTINGS, CONSOLE }

    data class Ui(val screen: Screen = Screen.PANEL, val editing: Boolean = false, val page: Int = 0)

    /** The message strip under the panel. ok = null is neutral. */
    data class Status(val text: String, val ok: Boolean?, val at: Long = System.currentTimeMillis())

    /** A tile's brief green/red outline after it fires. */
    data class Flash(val ok: Boolean, val at: Long = System.currentTimeMillis())

    private val store = ConfigStore(app.filesDir)
    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _flashes = MutableStateFlow<Map<String, Flash>>(emptyMap())
    val flashes: StateFlow<Map<String, Flash>> = _flashes.asStateFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    val client = OnyxClient(viewModelScope)

    // Latest wanted level per fader. A StateFlow conflates, so a fast drag sends
    // as quickly as Onyx acknowledges and never builds a backlog.
    private val faderTargets = mutableMapOf<String, MutableStateFlow<Int>>()

    init {
        viewModelScope.launch {
            _config.map { it.clientSettings() }.distinctUntilChanged().collect { client.configure(it) }
        }
        val c = _config.value
        if (c.syncOnStart && c.syncUrl.isNotBlank()) syncFromUrl(c.syncUrl)
    }

    override fun onCleared() {
        client.stop()
    }

    // ---- navigation -----------------------------------------------------------

    fun show(screen: Screen) = _ui.update { it.copy(screen = screen) }

    fun selectPage(index: Int) = _ui.update { it.copy(page = index) }

    fun needsPin() = _config.value.pin.isNotEmpty()

    fun unlock(pin: String): Boolean {
        if (pin != _config.value.pin) return false
        _ui.update { it.copy(editing = true) }
        return true
    }

    fun setEditing(on: Boolean) = _ui.update { it.copy(editing = on) }

    // ---- firing ---------------------------------------------------------------

    fun fire(tile: Tile) {
        if (tile.commands.isEmpty()) {
            setStatus("“${name(tile)}” has no commands yet", false)
            return
        }
        viewModelScope.launch {
            _running.update { it + tile.id }
            var allOk = true
            var last = ""
            try {
                for (cmd in tile.commands) {
                    if (cmd.type == CmdType.WAIT) {
                        delay((cmd.seconds * 1000).toLong())
                        continue
                    }
                    val line = cmd.wire() ?: continue
                    val r = client.send(line)
                    last = "$line → ${r.detail}"
                    if (!r.ok) allOk = false
                    // Don't carry on with a macro on a dead link: the rest would land out of order.
                    if (r.outcome == Outcome.OFFLINE || r.outcome == Outcome.TIMEOUT) break
                }
            } finally {
                _running.update { it - tile.id }
            }
            _flashes.update { it + (tile.id to Flash(allOk)) }
            setStatus("${name(tile)}: $last", allOk)
        }
    }

    fun setFader(tileId: String, percent: Int) {
        val existing = faderTargets[tileId]
        if (existing != null) {
            existing.value = percent
            return
        }
        val target = MutableStateFlow(percent)
        faderTargets[tileId] = target
        viewModelScope.launch {
            var lastOutcome: Outcome? = null
            target.collect { level ->
                val tile = findTile(tileId) ?: return@collect
                val r = client.send("SQL ${tile.faderCuelist},${percentToLevel(level)}")
                // Report changes of state only; a drag would otherwise flood the strip.
                if (r.outcome != lastOutcome && !r.ok) setStatus("${name(tile)} fader: ${r.detail}", false)
                lastOutcome = r.outcome
                delay(30)
            }
        }
    }

    fun commitFader(tileId: String, percent: Int) {
        updateConfig { c ->
            c.copy(pages = c.pages.map { p ->
                p.copy(tiles = p.tiles.map { if (it.id == tileId) it.copy(faderLevel = percent) else it })
            })
        }
    }

    fun sendRaw(line: String) {
        if (line.isBlank()) return
        viewModelScope.launch { client.send(line) }
    }

    fun testConnection() {
        client.configure(_config.value.clientSettings()) // pick up just-saved settings now
        viewModelScope.launch {
            setStatus("Testing ${_config.value.host}…", null)
            val r = client.send(_config.value.probeCommand)
            setStatus(
                when (r.outcome) {
                    Outcome.OK, Outcome.NOT_FOUND -> "Onyx answered: ${r.detail}"
                    else -> "No good: ${r.detail}"
                },
                r.outcome == Outcome.OK || r.outcome == Outcome.NOT_FOUND,
            )
        }
    }

    fun setStatus(text: String, ok: Boolean?) {
        _status.value = Status(text, ok)
    }

    // ---- editing --------------------------------------------------------------

    fun updateConfig(transform: (AppConfig) -> AppConfig) {
        _config.value = transform(_config.value)
        // Always write the latest config, so saves that finish out of order can't regress it.
        viewModelScope.launch(Dispatchers.IO) { synchronized(store) { store.save(_config.value) } }
    }

    /** Insert or replace [tile] on [pageId]. Returns an error message, or null on success. */
    fun saveTile(pageId: String, tile: Tile): String? {
        val page = _config.value.pages.firstOrNull { it.id == pageId } ?: return "Page is gone"
        if (!page.fits(tile.rect, ignoreId = tile.id)) return "Doesn't fit there - it overlaps another tile or runs off the grid"
        updatePage(pageId) { p ->
            val exists = p.tiles.any { it.id == tile.id }
            p.copy(tiles = if (exists) p.tiles.map { if (it.id == tile.id) tile else it } else p.tiles + tile)
        }
        return null
    }

    fun moveTile(pageId: String, tileId: String, r: Rect) {
        val page = _config.value.pages.firstOrNull { it.id == pageId } ?: return
        val tile = page.tiles.firstOrNull { it.id == tileId } ?: return
        if (tile.rect == r) return
        if (!page.fits(r, ignoreId = tileId)) {
            setStatus("No room there", false)
            return
        }
        updatePage(pageId) { p -> p.copy(tiles = p.tiles.map { if (it.id == tileId) it.withRect(r) else it }) }
    }

    fun deleteTile(pageId: String, tileId: String) =
        updatePage(pageId) { p -> p.copy(tiles = p.tiles.filterNot { it.id == tileId }) }

    fun duplicateTile(pageId: String, tile: Tile): String? {
        val page = _config.value.pages.firstOrNull { it.id == pageId } ?: return "Page is gone"
        val slot = page.freeSlot(tile.w, tile.h) ?: return "No free ${tile.w}×${tile.h} space on this page"
        updatePage(pageId) { p -> p.copy(tiles = p.tiles + tile.copy(id = newId()).withRect(slot)) }
        return null
    }

    fun addPage() {
        updateConfig { c -> c.copy(pages = c.pages + Page(name = "Page ${c.pages.size + 1}")) }
        _ui.update { it.copy(page = _config.value.pages.lastIndex) }
    }

    fun updatePage(pageId: String, transform: (Page) -> Page) =
        updateConfig { c -> c.copy(pages = c.pages.map { if (it.id == pageId) transform(it) else it }) }

    fun deletePage(pageId: String) {
        updateConfig { c -> if (c.pages.size <= 1) c else c.copy(pages = c.pages.filterNot { it.id == pageId }) }
        _ui.update { it.copy(page = it.page.coerceAtMost(_config.value.pages.lastIndex)) }
    }

    fun movePage(pageId: String, delta: Int) {
        val pages = _config.value.pages.toMutableList()
        val i = pages.indexOfFirst { it.id == pageId }
        val j = i + delta
        if (i < 0 || j !in pages.indices) return
        pages.add(j, pages.removeAt(i))
        updateConfig { it.copy(pages = pages) }
        _ui.update { it.copy(page = j) }
    }

    // ---- import / export / sync -------------------------------------------------

    fun exportJson(): String = _config.value.toJson().toString(2)

    /**
     * [layoutOnly] keeps this tablet's own connection, PIN and display settings and
     * takes just the title and pages - the right thing when one file feeds many tablets.
     */
    fun importJson(text: String, layoutOnly: Boolean): String? = try {
        val imported = AppConfig.fromJson(JSONObject(text))
        updateConfig { cur -> if (layoutOnly) cur.copy(title = imported.title, pages = imported.pages) else imported }
        _ui.update { it.copy(page = 0) }
        null
    } catch (e: Exception) {
        e.message ?: "Not a valid layout file"
    }

    fun syncFromUrl(url: String) {
        viewModelScope.launch {
            setStatus("Syncing layout…", null)
            val text = try {
                withContext(Dispatchers.IO) { fetch(url) }
            } catch (e: IOException) {
                setStatus("Layout sync failed: ${e.message}", false)
                return@launch
            }
            val err = importJson(text, layoutOnly = true)
            setStatus(err?.let { "Layout sync failed: $it" } ?: "Layout synced from $url", err == null)
        }
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.useCaches = false
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    // ---- helpers ----------------------------------------------------------------

    private fun findTile(id: String): Tile? =
        _config.value.pages.asSequence().flatMap { it.tiles }.firstOrNull { it.id == id }

    private fun name(t: Tile) = t.label.replace('\n', ' ').ifBlank { "Button" }
}

fun AppConfig.clientSettings() = OnyxClient.Settings(
    host = host,
    port = port,
    heartbeatSec = heartbeatSec,
    probeCommand = probeCommand.ifBlank { AppConfig.DEFAULT_PROBE },
    idleDisconnectSec = idleDisconnectSec,
)
