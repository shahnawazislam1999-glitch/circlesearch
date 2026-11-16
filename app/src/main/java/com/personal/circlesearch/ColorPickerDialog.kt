package com.personal.circlesearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Colour chooser for a handle: preset swatches plus a hex field. */
object ColorPickerDialog {

    val PRESETS = intArrayOf(
        0xFF111111.toInt(), // black
        0xFFFFFFFF.toInt(), // white
        0xFF9AA0A6.toInt(), // grey
        0xFFEA4335.toInt(), // red
        0xFFFF6D00.toInt(), // orange
        0xFFFBBC04.toInt(), // yellow
        0xFF25D366.toInt(), // WhatsApp green
        0xFF0F9D58.toInt(), // green
        0xFF10A37F.toInt(), // teal
        0xFF00ACC1.toInt(), // cyan
        0xFF1A73E8.toInt(), // blue
        0xFF3949AB.toInt(), // indigo
        0xFF8E24AA.toInt(), // purple
        0xFFE91E63.toInt(), // pink
        0xFF795548.toInt(), // brown
        0xFFFF8A80.toInt(), // coral
        0xFF64DD17.toInt(), // lime
        0xFF455A64.toInt()  // slate
    )
    private const val PER_ROW = 6

    fun show(
        context: Context,
        title: String,
        initial: Int,
        confirmLabel: String = "Save",
        onPick: (Int) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val preview: View = view.findViewById(R.id.colorPreview)
        val hexLayout: TextInputLayout = view.findViewById(R.id.hexLayout)
        val hexInput: TextInputEditText = view.findViewById(R.id.hexInput)
        val grid: LinearLayout = view.findViewById(R.id.colorGrid)

        var current = initial or AppShortcut.OPAQUE
        val swatches = ArrayList<Pair<View, Int>>()
        var updatingText = false

        fun refresh(fromTyping: Boolean) {
            style(preview, current, false)
            for ((v, c) in swatches) style(v, c, c == current)
            if (!fromTyping) {
                updatingText = true
                hexInput.setText(toHex(current))
                hexInput.setSelection(hexInput.text?.length ?: 0)
                updatingText = false
                hexLayout.error = null
            }
        }

        val size = context.dpInt(34)
        for (rowColors in PRESETS.toList().chunked(PER_ROW)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in rowColors) {
                val cell = FrameLayout(context)
                val swatch = View(context).apply {
                    contentDescription = toHex(c)
                    setOnClickListener {
                        current = c
                        refresh(false)
                    }
                }
                cell.addView(swatch, FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
                swatches.add(swatch to c)
                row.addView(cell, LinearLayout.LayoutParams(0, context.dpInt(46), 1f))
            }
            // Keep the last row aligned when it has fewer cells.
            repeat(PER_ROW - rowColors.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, context.dpInt(46), 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingText) return
                val text = s?.toString().orEmpty()
                val parsed = parseHex(text)
                if (parsed != null) {
                    current = parsed
                    hexLayout.error = null
                    refresh(true)
                } else {
                    hexLayout.error = if (text.trimStart('#').length >= 6) "Use 6 digits, e.g. 25D366" else null
                }
            }
        })

        refresh(false)

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(confirmLabel) { _, _ -> onPick(current) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun toHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

    fun parseHex(text: String): Int? {
        val t = text.trim().trimStart('#')
        if (t.length != 6) return null
        return try {
            Color.parseColor("#$t") or AppShortcut.OPAQUE
        } catch (_: Throwable) {
            null
        }
    }

    /** Round colour dot used in the picker and in the settings rows. */
    fun style(v: View, color: Int, selected: Boolean) {
        val ctx = v.context
        val outline = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOutline)
        val primary = MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimary)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) setStroke(ctx.dpInt(3), primary) else setStroke(ctx.dpInt(1), outline)
        }
    }
}

/** Picks a sensible default handle colour from an app's icon (most vivid dominant colour). */
object IconColor {
    fun dominant(icon: Drawable, fallback: Int): Int {
        return try {
            val s = 32
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, s, s)
            icon.draw(Canvas(bmp))
            val px = IntArray(s * s)
            bmp.getPixels(px, 0, s, 0, 0, s, s)
            bmp.recycle()

            val weight = FloatArray(512)
            val sr = FloatArray(512)
            val sg = FloatArray(512)
            val sb = FloatArray(512)
            for (p in px) {
                if (Color.alpha(p) < 200) continue
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                val sat = (mx - mn) / 255f
                if (mx > 235 && sat < 0.12f) continue // ignore white backgrounds
                val w = 0.1f + sat * sat
                val key = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
                weight[key] += w
                sr[key] += r * w
                sg[key] += g * w
                sb[key] += b * w
            }
            var best = -1
            for (i in weight.indices) if (weight[i] > 0f && (best < 0 || weight[i] > weight[best])) best = i
            if (best < 0) fallback
            else Color.rgb(
                (sr[best] / weight[best]).toInt(),
                (sg[best] / weight[best]).toInt(),
                (sb[best] / weight[best]).toInt()
            )
        } catch (_: Throwable) {
            fallback
        }
    }
}
