package com.gsb.onyxremote

import com.gsb.onyxremote.model.AppConfig
import com.gsb.onyxremote.model.CmdType
import com.gsb.onyxremote.model.Command
import com.gsb.onyxremote.model.Defaults
import com.gsb.onyxremote.model.Page
import com.gsb.onyxremote.model.Rect
import com.gsb.onyxremote.model.Tile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    @Test fun jsonRoundTrip() {
        val c = Defaults.config().copy(pin = "1234", syncUrl = "http://x/y.json")
        val back = AppConfig.fromJson(JSONObject(c.toJson().toString()))
        assertEquals(c, back)
    }

    @Test fun wireSyntax() {
        assertEquals("GQL 3", Command(CmdType.GO, cuelist = 3).wire())
        assertEquals("GTQ 3,2.5", Command(CmdType.GOTO, cuelist = 3, cue = "2.5").wire())
        assertEquals("SQL 4,255", Command(CmdType.LEVEL, cuelist = 4, level = 100).wire())
        assertEquals("SQL 4,128", Command(CmdType.LEVEL, cuelist = 4, level = 50).wire())
        assertEquals("SQL 4,0", Command(CmdType.LEVEL, cuelist = 4, level = 0).wire())
        assertEquals("RAQLODF", Command(CmdType.RELEASE_ALL_OVERRIDES_DF).wire())
        assertNull(Command(CmdType.WAIT).wire())
        assertNull(Command(CmdType.RAW, raw = "  ").wire())
    }

    @Test fun gridFit() {
        val p = Page(columns = 4, rows = 2, tiles = listOf(Tile(id = "a", x = 0, y = 0, w = 2, h = 2)))
        assertFalse(p.fits(Rect(1, 0, 1, 1)))          // overlaps a
        assertTrue(p.fits(Rect(1, 0, 1, 1), "a"))     // ...unless it is a
        assertFalse(p.fits(Rect(3, 0, 2, 1)))          // off the right edge
        assertEquals(Rect(2, 0, 2, 1), p.freeSlot(2, 1))
        assertNull(p.freeSlot(3, 1))
    }

    @Test fun rejectsFilesWithoutPages() {
        val r = runCatching { AppConfig.fromJson(JSONObject("{\"title\":\"x\"}")) }
        assertTrue(r.isFailure)
    }
}
