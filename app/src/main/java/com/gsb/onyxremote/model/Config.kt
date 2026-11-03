package com.gsb.onyxremote.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The whole panel: connection, display options and the pages of tiles.
 * Persisted as JSON, which is also the export / fleet-sync format.
 */
data class AppConfig(
    val title: String = "Onyx Remote",
    val host: String = "",
    val port: Int = 23,
    val heartbeatSec: Int = 10,
    val probeCommand: String = DEFAULT_PROBE,
    val idleDisconnectSec: Int = 0,
    val pin: String = "",
    val keepScreenOn: Boolean = true,
    val fullscreen: Boolean = true,
    val orientation: Orientation = Orientation.AUTO,
    val syncUrl: String = "",
    val syncOnStart: Boolean = false,
    val pages: List<Page> = listOf(Page()),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("title", title)
        .put("host", host)
        .put("port", port)
        .put("heartbeatSec", heartbeatSec)
        .put("probeCommand", probeCommand)
        .put("idleDisconnectSec", idleDisconnectSec)
        .put("pin", pin)
        .put("keepScreenOn", keepScreenOn)
        .put("fullscreen", fullscreen)
        .put("orientation", orientation.name)
        .put("syncUrl", syncUrl)
        .put("syncOnStart", syncOnStart)
        .put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })

    companion object {
        const val FORMAT = "onyx-remote"
        // A cuelist that cannot exist: Onyx answers "Cuelist Not Found", which
        // proves the console is alive without touching anything (hardware-verified).
        const val DEFAULT_PROBE = "GTQ 9999,1"

        fun fromJson(o: JSONObject): AppConfig {
            val pagesJson = o.optJSONArray("pages")
            require(pagesJson != null && pagesJson.length() > 0) { "Not an Onyx Remote layout (no pages)" }
            val d = AppConfig()
            return AppConfig(
                title = o.optString("title", d.title),
                host = o.optString("host", d.host).trim(),
                port = o.optInt("port", d.port),
                heartbeatSec = o.optInt("heartbeatSec", d.heartbeatSec),
                probeCommand = o.optString("probeCommand", d.probeCommand),
                idleDisconnectSec = o.optInt("idleDisconnectSec", d.idleDisconnectSec),
                pin = o.optString("pin", d.pin),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                fullscreen = o.optBoolean("fullscreen", d.fullscreen),
                orientation = enumOr(o.optString("orientation"), d.orientation),
                syncUrl = o.optString("syncUrl", d.syncUrl),
                syncOnStart = o.optBoolean("syncOnStart", d.syncOnStart),
                pages = (0 until pagesJson.length()).map { Page.fromJson(pagesJson.getJSONObject(it)) },
            )
        }
    }
}

enum class Orientation(val label: String) { AUTO("Auto"), LANDSCAPE("Landscape"), PORTRAIT("Portrait") }

data class Page(
    val id: String = newId(),
    val name: String = "Main",
    val columns: Int = 6,
    val rows: Int = 4,
    val tiles: List<Tile> = emptyList(),
) {
    /** True when [r] lies inside the grid and overlaps no tile other than [ignoreId]. */
    fun fits(r: Rect, ignoreId: String? = null): Boolean =
        r.x >= 0 && r.y >= 0 && r.w >= 1 && r.h >= 1 &&
            r.x + r.w <= columns && r.y + r.h <= rows &&
            tiles.none { it.id != ignoreId && it.rect.overlaps(r) }

    /** First free top-left cell for a [w]x[h] tile, scanning row by row. */
    fun freeSlot(w: Int, h: Int): Rect? {
        for (y in 0..rows - h) for (x in 0..columns - w) {
            val r = Rect(x, y, w, h)
            if (fits(r)) return r
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("columns", columns)
        .put("rows", rows)
        .put("tiles", JSONArray().apply { tiles.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Page {
            val t = o.optJSONArray("tiles") ?: JSONArray()
            return Page(
                id = o.optString("id").ifBlank { newId() },
                name = o.optString("name", "Page"),
                columns = o.optInt("columns", 6).coerceIn(1, MAX_GRID),
                rows = o.optInt("rows", 4).coerceIn(1, MAX_GRID),
                tiles = (0 until t.length()).map { Tile.fromJson(t.getJSONObject(it)) },
            )
        }
    }
}

const val MAX_GRID = 12

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(o: Rect) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
}

enum class TileKind(val label: String) { BUTTON("Button"), FADER("Fader"), LABEL("Label") }

data class Tile(
    val id: String = newId(),
    val kind: TileKind = TileKind.BUTTON,
    val label: String = "Button",
    val color: Long = 0xFF1565C0,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 1,
    val h: Int = 1,
    val commands: List<Command> = emptyList(),
    val confirm: Boolean = false,
    val faderCuelist: Int = 1,
    /** Last level this tablet sent, in percent. Onyx can't be asked for it. */
    val faderLevel: Int = 0,
) {
    val rect get() = Rect(x, y, w, h)

    fun withRect(r: Rect) = copy(x = r.x, y = r.y, w = r.w, h = r.h)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("label", label)
        .put("color", colorToHex(color))
        .put("x", x).put("y", y).put("w", w).put("h", h)
        .put("commands", JSONArray().apply { commands.forEach { put(it.toJson()) } })
        .put("confirm", confirm)
        .put("faderCuelist", faderCuelist)
        .put("faderLevel", faderLevel)

    companion object {
        fun fromJson(o: JSONObject): Tile {
            val c = o.optJSONArray("commands") ?: JSONArray()
            return Tile(
                id = o.optString("id").ifBlank { newId() },
                kind = enumOr(o.optString("kind"), TileKind.BUTTON),
                label = o.optString("label", ""),
                color = hexToColor(o.optString("color"), 0xFF1565C0),
                x = o.optInt("x", 0).coerceAtLeast(0),
                y = o.optInt("y", 0).coerceAtLeast(0),
                w = o.optInt("w", 1).coerceAtLeast(1),
                h = o.optInt("h", 1).coerceAtLeast(1),
                commands = (0 until c.length()).map { Command.fromJson(c.getJSONObject(it)) },
                confirm = o.optBoolean("confirm", false),
                faderCuelist = o.optInt("faderCuelist", 1),
                faderLevel = o.optInt("faderLevel", 0).coerceIn(0, 100),
            )
        }
    }
}

/** One step of a button. Wire syntax is from docs/ONYX_TELNET_REFERENCE.md. */
enum class CmdType(
    val label: String,
    val needsCuelist: Boolean = false,
    val needsCue: Boolean = false,
    val needsLevel: Boolean = false,
) {
    GO("Go cuelist", needsCuelist = true),
    GOTO("Go to cue", needsCuelist = true, needsCue = true),
    PAUSE("Pause cuelist", needsCuelist = true),
    RELEASE("Release cuelist", needsCuelist = true),
    LEVEL("Set cuelist level", needsCuelist = true, needsLevel = true),
    RELEASE_ALL("Release all cuelists"),
    RELEASE_ALL_DF("Release all cuelists, dimmer first"),
    RELEASE_OVERRIDES("Release all overrides"),
    RELEASE_ALL_OVERRIDES("Release all cuelists + overrides"),
    RELEASE_ALL_OVERRIDES_DF("Release all cuelists + overrides, dimmer first"),
    CLEAR("Clear programmer"),
    WAIT("Wait"),
    RAW("Raw telnet command"),
}

data class Command(
    val type: CmdType = CmdType.GO,
    val cuelist: Int = 1,
    val cue: String = "1",
    val level: Int = 100,
    val seconds: Double = 1.0,
    val raw: String = "",
) {
    /** The telnet line to send, or null for steps that send nothing (WAIT, empty RAW). */
    fun wire(): String? = when (type) {
        CmdType.GO -> "GQL $cuelist"
        CmdType.GOTO -> "GTQ $cuelist,${cue.trim().ifEmpty { "1" }}"
        CmdType.PAUSE -> "PQL $cuelist"
        CmdType.RELEASE -> "RQL $cuelist"
        CmdType.LEVEL -> "SQL $cuelist,${percentToLevel(level)}"
        CmdType.RELEASE_ALL -> "RAQL"
        CmdType.RELEASE_ALL_DF -> "RAQLDF"
        CmdType.RELEASE_OVERRIDES -> "RAO"
        CmdType.RELEASE_ALL_OVERRIDES -> "RAQLO"
        CmdType.RELEASE_ALL_OVERRIDES_DF -> "RAQLODF"
        CmdType.CLEAR -> "CLRCLR"
        CmdType.WAIT -> null
        CmdType.RAW -> raw.trim().ifEmpty { null }
    }

    fun describe(): String = when (type) {
        CmdType.WAIT -> "wait ${formatSeconds(seconds)}s"
        else -> wire() ?: "(empty)"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("cuelist", cuelist)
        .put("cue", cue)
        .put("level", level)
        .put("seconds", seconds)
        .put("raw", raw)

    companion object {
        fun fromJson(o: JSONObject) = Command(
            type = enumOr(o.optString("type"), CmdType.GO),
            cuelist = o.optInt("cuelist", 1),
            cue = o.optString("cue", "1"),
            level = o.optInt("level", 100).coerceIn(0, 100),
            seconds = o.optDouble("seconds", 1.0).coerceIn(0.0, 3600.0),
            raw = o.optString("raw", ""),
        )
    }
}

/** Onyx cuelist levels are 0-255; the UI works in percent. */
fun percentToLevel(percent: Int): Int = (percent.coerceIn(0, 100) * 255 + 50) / 100

fun formatSeconds(s: Double): String =
    if (s == Math.floor(s)) s.toLong().toString() else s.toString()

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

fun colorToHex(c: Long): String = "#%06X".format(c and 0xFFFFFF)

fun hexToColor(s: String, fallback: Long): Long {
    val h = s.trim().removePrefix("#")
    if (h.length != 6) return fallback
    return h.toLongOrNull(16)?.let { 0xFF000000 or it } ?: fallback
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback

/** First-run layout: shows off each tile type against cuelists 1 and 2. */
object Defaults {
    fun config() = AppConfig(
        pages = listOf(
            Page(
                name = "Main",
                columns = 6,
                rows = 4,
                tiles = listOf(
                    Tile(label = "GO\nCuelist 1", color = 0xFF2E7D32, x = 0, y = 0, w = 2, h = 2,
                        commands = listOf(Command(CmdType.GO, cuelist = 1))),
                    Tile(label = "PAUSE\nCuelist 1", color = 0xFFF9A825, x = 2, y = 0, w = 2, h = 1,
                        commands = listOf(Command(CmdType.PAUSE, cuelist = 1))),
                    Tile(label = "Cue 5", color = 0xFF1565C0, x = 2, y = 1, w = 2, h = 1,
                        commands = listOf(Command(CmdType.GOTO, cuelist = 1, cue = "5"))),
                    Tile(kind = TileKind.FADER, label = "Cuelist 1", color = 0xFF4FC3F7, x = 4, y = 0, w = 1, h = 4,
                        faderCuelist = 1),
                    Tile(kind = TileKind.FADER, label = "Cuelist 2", color = 0xFFBA68C8, x = 5, y = 0, w = 1, h = 4,
                        faderCuelist = 2),
                    Tile(label = "RELEASE\nCuelist 1", color = 0xFF455A64, x = 0, y = 2, w = 2, h = 1,
                        commands = listOf(Command(CmdType.RELEASE, cuelist = 1))),
                    Tile(label = "Release 1, then Go 2", color = 0xFF6A1B9A, x = 2, y = 2, w = 2, h = 1,
                        commands = listOf(
                            Command(CmdType.RELEASE, cuelist = 1),
                            Command(CmdType.WAIT, seconds = 1.0),
                            Command(CmdType.GO, cuelist = 2),
                        )),
                    Tile(label = "RELEASE ALL", color = 0xFFC62828, x = 0, y = 3, w = 4, h = 1, confirm = true,
                        commands = listOf(Command(CmdType.RELEASE_ALL))),
                ),
            ),
        ),
    )
}
