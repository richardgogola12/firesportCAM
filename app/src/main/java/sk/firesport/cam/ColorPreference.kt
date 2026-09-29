package sk.firesport.cam

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import java.util.Locale

/** Nastavenie farby s náhľadom a výberom (predvoľby, RGB, HEX). */
class ColorPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var color = Color.WHITE

    init {
        widgetLayoutResource = R.layout.pref_color_widget
    }

    var value: Int
        get() = color
        set(v) {
            color = v or 0xFF000000.toInt()
            persistInt(color)
            updateSummary()
            notifyChanged()
        }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        val s = a.getString(index)
        return try {
            Color.parseColor(s)
        } catch (_: Exception) {
            a.getInt(index, Color.WHITE)
        }
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        color = getPersistedInt((defaultValue as? Int) ?: Color.WHITE)
        updateSummary()
    }

    private fun updateSummary() {
        summary = String.format(Locale.US, "#%06X", 0xFFFFFF and color)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val swatch = holder.findViewById(R.id.color_swatch) ?: return
        val dp = context.resources.displayMetrics.density
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * dp).toInt(), Color.GRAY)
        }
    }

    override fun onClick() {
        ColorPickerDialog.show(context, color, title) { c ->
            if (callChangeListener(c)) value = c
        }
    }
}

object ColorPickerDialog {
    private val PRESETS = intArrayOf(
        Color.WHITE, 0xFFBDBDBD.toInt(), 0xFF616161.toInt(), Color.BLACK,
        0xFFE53935.toInt(), 0xFFB71C1C.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(),
        0xFF4CAF50.toInt(), 0xFF1B5E20.toInt(), 0xFF00BCD4.toInt(), 0xFF2196F3.toInt(),
        0xFF0D47A1.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(), 0xFF795548.toInt()
    )

    fun show(ctx: Context, initial: Int, title: CharSequence?, onPick: (Int) -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        var color = initial or 0xFF000000.toInt()
        var updating = false

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(4))
        }

        val preview = View(ctx)
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(48)))

        val grid = GridLayout(ctx).apply { columnCount = 8 }
        root.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(12) })

        val names = arrayOf("R", "G", "B")
        val seeks = Array(3) { SeekBar(ctx).apply { max = 255 } }
        val hex = EditText(ctx).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "#RRGGBB"
        }

        fun refresh(fromHex: Boolean) {
            updating = true
            preview.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 8 * dp
                setStroke(px(1), Color.GRAY)
            }
            seeks[0].progress = Color.red(color)
            seeks[1].progress = Color.green(color)
            seeks[2].progress = Color.blue(color)
            if (!fromHex) hex.setText(String.format(Locale.US, "#%06X", 0xFFFFFF and color))
            updating = false
        }

        for (c in PRESETS) {
            val v = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(px(1), Color.GRAY)
                }
                setOnClickListener {
                    color = c
                    refresh(false)
                }
            }
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = px(30)
                height = px(30)
                setMargins(px(3), px(3), px(3), px(3))
            })
        }

        for (i in 0 until 3) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(ctx).apply { text = names[i]; minWidth = px(20) })
            row.addView(seeks[i], LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(6) })
            seeks[i].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (updating || !fromUser) return
                    color = Color.rgb(seeks[0].progress, seeks[1].progress, seeks[2].progress)
                    refresh(false)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        root.addView(hex, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(8) })
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                var t = s?.toString()?.trim() ?: return
                if (!t.startsWith("#")) t = "#$t"
                if (t.length != 7) return
                try {
                    color = Color.parseColor(t) or 0xFF000000.toInt()
                    refresh(true)
                } catch (_: Exception) {
                }
            }
        })

        refresh(false)

        val scroll = ScrollView(ctx)
        scroll.addView(root)
        AlertDialog.Builder(ctx)
            .setTitle(title ?: "Farba")
            .setView(scroll)
            .setPositiveButton("OK") { _, _ -> onPick(color) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }
}
