package com.limosa.xr.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchParsingTest {
    // Shape of `getevent -pl` on a phone: a fingerprint sensor, an indirect touchpad and the screen.
    private val getevent = """
add device 1: /dev/input/event4
  bus:      0000
  name:     "uinput-fpc"
  events:
    KEY (0001): KEY_UP                KEY_DOWN
  input props:
    <none>
add device 2: /dev/input/event5
  name:     "some-touchpad"
  events:
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 4, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 999, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 999, fuzz 0, flat 0, resolution 0
  input props:
    INPUT_PROP_POINTER
add device 3: /dev/input/event2
  bus:      0000
  name:     "fts_ts"
  events:
    KEY (0001): BTN_TOUCH
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0
                ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
  input props:
    INPUT_PROP_DIRECT
""".trimIndent()

    @Test fun picksTheDirectTouchscreen() {
        val t = Touchscreen.parse(getevent)
        assertNotNull(t)
        assertEquals("/dev/input/event2", t!!.path)
        assertEquals("fts_ts", t.name)
        assertEquals(1079, t.maxX)
        assertEquals(2399, t.maxY)
        assertTrue(t.slots)
    }

    @Test fun noTouchscreen() {
        assertNull(Touchscreen.parse("add device 1: /dev/input/event0\n  name: \"keys\"\n"))
    }

    private fun ev(p: MtParser, type: Int, code: Int, value: Int) = p.event(type, code, value)

    @Test fun protocolBTwoFingers() {
        val p = MtParser(Touchscreen("/x", "t", 0, 1000, 0, 2000, true))
        // Finger in slot 0 goes down at (500, 1000).
        ev(p, 3, 0x2f, 0); ev(p, 3, 0x39, 7); ev(p, 3, 0x35, 500); ev(p, 3, 0x36, 1000)
        assertTrue(ev(p, 0, 0, 0))
        assertEquals(1, p.count)
        assertEquals(0, p.ids[0])
        assertEquals(0.5f, p.xy[0], 1e-4f)
        assertEquals(0.5f, p.xy[1], 1e-4f)
        // Second finger in slot 1; first one moves only in x.
        ev(p, 3, 0x2f, 1); ev(p, 3, 0x39, 8); ev(p, 3, 0x35, 1000); ev(p, 3, 0x36, 0)
        ev(p, 3, 0x2f, 0); ev(p, 3, 0x35, 250)
        assertTrue(ev(p, 0, 0, 0))
        assertEquals(2, p.count)
        assertEquals(0.25f, p.xy[0], 1e-4f)
        assertEquals(0.5f, p.xy[1], 1e-4f)
        assertEquals(1, p.ids[1])
        assertEquals(1f, p.xy[2], 1e-4f)
        assertEquals(0f, p.xy[3], 1e-4f)
        // First finger lifts.
        ev(p, 3, 0x2f, 0); ev(p, 3, 0x39, -1)
        assertTrue(ev(p, 0, 0, 0))
        assertEquals(1, p.count)
        assertEquals(1, p.ids[0])
    }

    @Test fun droppedFrameIsSkipped() {
        val p = MtParser(Touchscreen("/x", "t", 0, 100, 0, 100, true))
        ev(p, 3, 0x39, 1); ev(p, 3, 0x35, 10); ev(p, 3, 0x36, 10)
        ev(p, 0, 3, 0)  // SYN_DROPPED
        assertFalse(ev(p, 0, 0, 0))
        assertTrue(ev(p, 0, 0, 0))
    }

    @Test fun protocolA() {
        val p = MtParser(Touchscreen("/x", "t", 0, 100, 0, 100, false))
        ev(p, 3, 0x35, 10); ev(p, 3, 0x36, 20); ev(p, 0, 2, 0)
        ev(p, 3, 0x35, 90); ev(p, 3, 0x36, 80); ev(p, 0, 2, 0)
        assertTrue(ev(p, 0, 0, 0))
        assertEquals(2, p.count)
        assertEquals(0.9f, p.xy[2], 1e-4f)
        // No contacts: an empty frame lifts everything.
        assertTrue(ev(p, 0, 0, 0))
        assertEquals(0, p.count)
    }
}
