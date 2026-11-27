package com.limosa.kdehome

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** The Android app drawer, shown over the KDE desktop. */
class AppDrawer(
    context: Context,
    private val shortcuts: AndroidShortcuts,
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    private var apps: List<AndroidShortcuts.App> = emptyList()
    private var shown: List<AndroidShortcuts.App> = emptyList()
    private val icons = HashMap<AndroidShortcuts.App, Drawable>()
    private val search = EditText(context)
    private val grid = GridView(context)
    private val adapter = Adapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(235, 24, 26, 30))
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        isClickable = true

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        search.apply {
            hint = "Search Android apps"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter()
            })
        }
        bar.addView(search, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(context).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setOnClickListener { onClose() }
        })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = "Tap to open · long-press to put it on the KDE desktop"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(4), dp(4), 0, dp(8))
        })

        grid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(84)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(12)
            adapter = this@AppDrawer.adapter
            setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
            setOnItemLongClickListener { _, v, pos, _ -> menu(v, shown[pos]); true }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() {
        search.setText("")
        thread(isDaemon = true) {
            val list = shortcuts.launchableApps()
            val loaded = list.associateWith { shortcuts.icon(it) }
            post {
                apps = list
                icons.clear()
                icons.putAll(loaded)
                filter()
            }
        }
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) || it.component.packageName.contains(q) }
        adapter.notifyDataSetChanged()
    }

    private fun launch(app: AndroidShortcuts.App) {
        runCatching { context.startActivity(shortcuts.launchIntent(app)) }
            .onFailure { Toast.makeText(context, "Can't open ${app.label}: ${it.message}", Toast.LENGTH_SHORT).show() }
        onClose()
    }

    private fun menu(anchor: View, app: AndroidShortcuts.App) {
        val pkg = app.component.packageName
        PopupMenu(context, anchor).apply {
            menu.add("Add to KDE desktop").setOnMenuItemClickListener {
                thread(isDaemon = true) { shortcuts.pinToDesktop(app) }
                Toast.makeText(context, "${app.label} is on the KDE desktop", Toast.LENGTH_SHORT).show()
                true
            }
            menu.add("App info").setOnMenuItemClickListener {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }
            menu.add("Uninstall").setOnMenuItemClickListener {
                @Suppress("DEPRECATION")
                context.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }
            show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(context), LayoutParams(dp(52), dp(52)))
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val app = shown[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(icons[app])
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }
}
