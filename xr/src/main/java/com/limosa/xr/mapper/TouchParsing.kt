package com.limosa.xr.mapper

// Pure Kotlin (no Android) so it can be unit tested: finding the touchscreen and decoding its events.

/** The touchscreen's event device, found with getevent. */
class Touchscreen(val path: String, val name: String, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val slots: Boolean) {
    companion object {
        /** Picks the touchscreen from `getevent -pl` output. */
        fun parse(out: String): Touchscreen? {
            val found = ArrayList<Pair<Touchscreen, Boolean>>()
            var path: String? = null
            var name = ""
            var minX = 0; var maxX = 0; var minY = 0; var maxY = 0
            var hasX = false; var hasY = false; var slots = false; var direct = false
            fun flush() {
                val pth = path
                if (pth != null && hasX && hasY && maxX > minX && maxY > minY) {
                    found.add(Touchscreen(pth, name, minX, maxX, minY, maxY, slots) to direct)
                }
            }
            val range = Regex("min (-?\\d+), max (-?\\d+)")
            for (line in out.lines()) {
                val t = line.trim()
                when {
                    t.startsWith("add device") -> {
                        flush()
                        path = t.substringAfter(": ").trim()
                        name = ""; hasX = false; hasY = false; slots = false; direct = false
                    }
                    t.startsWith("name:") -> name = t.substringAfter("name:").trim().trim('"')
                    "ABS_MT_POSITION_X" in t -> range.find(t)?.let { minX = it.groupValues[1].toInt(); maxX = it.groupValues[2].toInt(); hasX = true }
                    "ABS_MT_POSITION_Y" in t -> range.find(t)?.let { minY = it.groupValues[1].toInt(); maxY = it.groupValues[2].toInt(); hasY = true }
                    "ABS_MT_SLOT" in t -> slots = true
                    "INPUT_PROP_DIRECT" in t -> direct = true
                }
            }
            flush()
            return (found.firstOrNull { it.second } ?: found.firstOrNull())?.first
        }
    }
}

/** Parses Linux multi-touch events (protocol B with slots, or A) into frames. */
class MtParser(private val dev: Touchscreen) {
    private val maxSlots = 16
    private val tracking = IntArray(maxSlots) { -1 }
    private val rawX = IntArray(maxSlots)
    private val rawY = IntArray(maxSlots)
    private var slot = 0
    private var dropped = false
    // Protocol A: points collected between SYN_MT_REPORTs.
    private var aX = 0
    private var aY = 0
    private val aPoints = ArrayList<IntArray>()
    val ids = IntArray(maxSlots)
    val xy = FloatArray(maxSlots * 2)
    var count = 0

    /** Returns true when a complete frame is ready in [ids]/[xy]/[count]. */
    fun event(type: Int, code: Int, value: Int): Boolean {
        when (type) {
            EV_ABS -> when (code) {
                ABS_MT_SLOT -> slot = value.coerceIn(0, maxSlots - 1)
                ABS_MT_TRACKING_ID -> if (dev.slots) tracking[slot] = value
                ABS_MT_POSITION_X -> if (dev.slots) rawX[slot] = value else aX = value
                ABS_MT_POSITION_Y -> if (dev.slots) rawY[slot] = value else aY = value
            }
            EV_SYN -> when (code) {
                SYN_MT_REPORT -> if (!dev.slots) aPoints.add(intArrayOf(aX, aY))
                SYN_DROPPED -> dropped = true
                SYN_REPORT -> {
                    if (dropped) { dropped = false; return false }
                    count = 0
                    if (dev.slots) {
                        for (s in 0 until maxSlots) if (tracking[s] >= 0) put(s, rawX[s], rawY[s])
                    } else {
                        aPoints.forEachIndexed { i, p -> if (i < maxSlots) put(i, p[0], p[1]) }
                        aPoints.clear()
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun put(id: Int, x: Int, y: Int) {
        ids[count] = id
        xy[2 * count] = ((x - dev.minX).toFloat() / (dev.maxX - dev.minX).coerceAtLeast(1)).coerceIn(0f, 1f)
        xy[2 * count + 1] = ((y - dev.minY).toFloat() / (dev.maxY - dev.minY).coerceAtLeast(1)).coerceIn(0f, 1f)
        count++
    }
}

private const val EV_SYN = 0
private const val EV_ABS = 3
private const val SYN_REPORT = 0
private const val SYN_MT_REPORT = 2
private const val SYN_DROPPED = 3
private const val ABS_MT_SLOT = 0x2f
private const val ABS_MT_POSITION_X = 0x35
private const val ABS_MT_POSITION_Y = 0x36
private const val ABS_MT_TRACKING_ID = 0x39
