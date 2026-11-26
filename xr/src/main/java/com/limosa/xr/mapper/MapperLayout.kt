package com.limosa.xr.mapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where each on-screen control sits and where in the app it touches. Positions are fractions of
 * the screen (0..1), saved per app and per orientation.
 */
class MapperLayout(val controls: List<Control>) {
    enum class Kind { BUTTON, JOYSTICK, CAMERA }

    class Control(val id: String, val label: String, val kind: Kind, var cx: Float, var cy: Float, var tx: Float, var ty: Float)

    fun toJson() = JSONArray().apply {
        controls.forEach { put(JSONObject().put("id", it.id).put("cx", it.cx.toDouble()).put("cy", it.cy.toDouble()).put("tx", it.tx.toDouble()).put("ty", it.ty.toDouble())) }
    }

    companion object {
        /** Default layout (landscape): sticks in the bottom corners, shoulders at the sides, like a controller. */
        fun defaults(landscape: Boolean): MapperLayout {
            // In portrait the same layout is squeezed into the lower half of the screen.
            fun c(id: String, label: String, kind: Kind, x: Float, y: Float): Control {
                val py = if (landscape) y else 0.45f + y * 0.55f
                return Control(id, label, kind, x, py, x, py)
            }
            return MapperLayout(listOf(
                c("ls", "L", Kind.JOYSTICK, 0.12f, 0.76f),
                c("rs", "R", Kind.CAMERA, 0.88f, 0.76f),
                c("lt", "LT", Kind.BUTTON, 0.05f, 0.18f),
                c("lg", "LG", Kind.BUTTON, 0.05f, 0.40f),
                c("rt", "RT", Kind.BUTTON, 0.95f, 0.18f),
                c("rg", "RG", Kind.BUTTON, 0.95f, 0.40f),
                c("x", "X", Kind.BUTTON, 0.28f, 0.88f),
                c("y", "Y", Kind.BUTTON, 0.32f, 0.66f),
                c("a", "A", Kind.BUTTON, 0.72f, 0.88f),
                c("b", "B", Kind.BUTTON, 0.68f, 0.66f),
                c("menu", "≡", Kind.BUTTON, 0.16f, 0.16f),
            ))
        }

        private fun key(pkg: String, landscape: Boolean) = pkg + if (landscape) "|land" else "|port"

        fun load(ctx: Context, pkg: String, landscape: Boolean): MapperLayout {
            val layout = defaults(landscape)
            val saved = ctx.getSharedPreferences("mapper", Context.MODE_PRIVATE).getString(key(pkg, landscape), null) ?: return layout
            runCatching {
                val arr = JSONArray(saved)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val c = layout.controls.firstOrNull { it.id == o.getString("id") } ?: continue
                    c.cx = o.getDouble("cx").toFloat(); c.cy = o.getDouble("cy").toFloat()
                    c.tx = o.getDouble("tx").toFloat(); c.ty = o.getDouble("ty").toFloat()
                }
            }
            return layout
        }

        fun save(ctx: Context, pkg: String, landscape: Boolean, layout: MapperLayout) {
            ctx.getSharedPreferences("mapper", Context.MODE_PRIVATE).edit().putString(key(pkg, landscape), layout.toJson().toString()).apply()
        }

        fun reset(ctx: Context, pkg: String, landscape: Boolean) {
            ctx.getSharedPreferences("mapper", Context.MODE_PRIVATE).edit().remove(key(pkg, landscape)).apply()
        }
    }
}
