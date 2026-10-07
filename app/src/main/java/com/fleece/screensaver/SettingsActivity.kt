package com.fleece.screensaver

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * TV launcher entry ("Fleece Screensavers") and the dream's settings screen: a D-pad friendly list of
 * every scene with a checkbox for the random rotation and a Preview button that runs the scene
 * full screen (where its parameters can be tweaked, see [PreviewActivity]). Choices are stored in [ScenePrefs] and read by the dream on its next activation.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: ScenePrefs
    private lateinit var tweakStore: TweakStore
    private val tweakNotes = mutableMapOf<String, TextView>()
    private lateinit var summary: TextView
    private val checks = mutableMapOf<String, CheckBox>()
    private var firstRow: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ScenePrefs(this)
        tweakStore = TweakStore(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(36), dp(64), dp(36))
        }
        column.addView(text("Fleece Screensavers", 30f, Color.WHITE, bold = true))
        summary = text("", 16f, GREY)
        column.addView(summary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(4); bottomMargin = dp(20)
        })

        SceneCatalog.scenes.forEach { column.addView(sceneRow(it)) }

        column.addView(button("Reset to defaults") {
            prefs.resetToDefaults()
            refresh()
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(column)
        })
        refresh()
        // Start with the D-pad cursor on the first scene so the highlight is visible right away.
        firstRow?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun sceneRow(scene: SceneInfo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left part: checkbox + title/description, focusable as one unit; OK toggles it.
        val check = CheckBox(this).apply {
            isFocusable = false
            isClickable = false
        }
        checks[scene.key] = check
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(scene.title, 20f, Color.WHITE))
            addView(text(scene.description, 14f, GREY))
            addView(text("", 14f, AMBER).also { tweakNotes[scene.key] = it })
        }
        val toggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
            background = focusBackground()
            isFocusable = true
            isClickable = true
            addView(check)
            addView(texts, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            setOnClickListener { toggle(scene) }
        }
        if (firstRow == null) firstRow = toggle
        row.addView(toggle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        row.addView(button("Preview / tweak") {
            startActivity(Intent(this, PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_SCENE, scene.key))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(16) })

        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) }
        return row
    }

    private fun toggle(scene: SceneInfo) {
        val enabled = prefs.isEnabled(scene.key)
        if (enabled && prefs.enabledKeys().size == 1) {
            Toast.makeText(this, "At least one scene must stay in the rotation", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.setEnabled(scene.key, !enabled)
        refresh()
    }

    private fun refresh() {
        val keys = prefs.enabledKeys()
        checks.forEach { (key, box) -> box.isChecked = key in keys }
        SceneCatalog.scenes.forEach { scene ->
            val n = tweakStore.changedCount(scene)
            tweakNotes[scene.key]?.apply {
                text = if (n == 1) "1 tweaked setting" else "$n tweaked settings"
                visibility = if (n > 0) View.VISIBLE else View.GONE
            }
        }
        val n = SceneCatalog.scenes.count { it.key in keys }
        summary.text = "$n of ${SceneCatalog.scenes.size} scenes in the random rotation. " +
            "Changes apply the next time the screensaver starts."
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = focusBackground(idle = 0xFF262626.toInt())
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Rounded highlight when focused (the D-pad cursor), otherwise [idle]. */
    private fun focusBackground(idle: Int = Color.TRANSPARENT) = StateListDrawable().apply {
        fun rounded(color: Int, stroke: Int = 0) = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(color)
            if (stroke != 0) setStroke(dp(2), stroke)
        }
        addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF3A3A3A.toInt(), Color.WHITE))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFF505050.toInt()))
        addState(intArrayOf(), if (idle == Color.TRANSPARENT) ColorDrawable(idle) else rounded(idle))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val GREY = 0xFFAAAAAA.toInt()
        const val AMBER = 0xFFFFB74D.toInt()
    }
}
