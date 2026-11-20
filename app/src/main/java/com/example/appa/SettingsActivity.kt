package com.example.appa

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Settings screen: enter service URLs/keys (stored encrypted) and toggle live mode. */
class SettingsActivity : Activity() {

    private lateinit var fields: MutableMap<String, EditText>
    private lateinit var liveToggle: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val root = Ui.vRow(this)
        Ui.padded(root, 14)
        root.setBackgroundColor(Color.WHITE)

        val header = Ui.hRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            text = "‹ Back"
            setTextColor(Ui.ACCENT)
            background = pillBg(Ui.CARD_BG, 8)
            setOnClickListener { finish() }
        })
        header.addView(Ui.textView(this, "Settings", 18f, Ui.TEXT, bold = true).apply {
            val p = dp(10); setPadding(p, 0, 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(Ui.textView(
            this,
            "Secrets are stored encrypted on this device only (Android Keystore). " +
                "The app talks to your services directly; LAN HTTP is allowed for local addresses.",
            11f, Ui.DIM
        ).apply { val p = dp(6); setPadding(0, p, 0, 0) })

        val scroll = ScrollView(this)
        val form = Ui.vRow(this)
        scroll.addView(form)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fields = mutableMapOf()
        val cfg = AppState.config
        fun field(label: String, key: String, value: String, hint: String = "") {
            form.addView(Ui.textView(this, label, 12f, Ui.DIM, bold = true).apply {
                val p = dp(10); setPadding(0, p, 0, 0)
            })
            val et = EditText(this).apply {
                setText(value)
                if (hint.isNotEmpty()) setHint(hint)
                setTextColor(Ui.TEXT)
                setHintTextColor(Ui.DIM)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                background = pillBg(Ui.CARD_BG, 6)
                val p = dp(8); setPadding(p, p, p, p)
                isSingleLine = true
            }
            fields[key] = et
            form.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        field("TMDB API read token", "tmdbToken", cfg.tmdbToken, "eyJ…")
        field("Sonarr URL", "sonarrUrl", cfg.sonarrUrl, "http://192.168.178.42:8989")
        field("Sonarr API key", "sonarrKey", cfg.sonarrKey)
        field("Radarr URL", "radarrUrl", cfg.radarrUrl, "http://192.168.178.42:7878")
        field("Radarr API key", "radarrKey", cfg.radarrKey)
        field("Prowlarr URL (magnets)", "prowlarrUrl", cfg.prowlarrUrl, "http://192.168.178.42:9696")
        field("Prowlarr API key", "prowlarrKey", cfg.prowlarrKey)
        field("Jellyfin URL", "jellyfinUrl", cfg.jellyfinUrl, "https://…")
        field("Jellyfin API key", "jellyfinKey", cfg.jellyfinKey)

        liveToggle = CheckBox(this).apply {
            text = "Use live services (otherwise placeholder mode)"
            isChecked = cfg.liveMode
            setTextColor(Ui.TEXT)
            val p = dp(12); setPadding(0, p, 0, 0)
        }
        form.addView(liveToggle)

        val btnRow = Ui.hRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val saveBtn = Button(this).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            background = pillBg(Ui.ACCENT, 8)
            setOnClickListener { save() }
        }
        val testBtn = Button(this).apply {
            text = "Test connection"
            setTextColor(Ui.ACCENT)
            background = pillBg(Ui.CARD_BG, 8)
            setOnClickListener { test() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(8)
            layoutParams = lp
        }
        btnRow.addView(saveBtn)
        btnRow.addView(testBtn)
        root.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        setContentView(root)
    }

    private fun collect(): AppConfig = AppConfig(
        tmdbToken = fields["tmdbToken"]!!.text.toString(),
        sonarrUrl = fields["sonarrUrl"]!!.text.toString(),
        sonarrKey = fields["sonarrKey"]!!.text.toString(),
        radarrUrl = fields["radarrUrl"]!!.text.toString(),
        radarrKey = fields["radarrKey"]!!.text.toString(),
        prowlarrUrl = fields["prowlarrUrl"]!!.text.toString(),
        prowlarrKey = fields["prowlarrKey"]!!.text.toString(),
        jellyfinUrl = fields["jellyfinUrl"]!!.text.toString(),
        jellyfinKey = fields["jellyfinKey"]!!.text.toString(),
        liveMode = liveToggle.isChecked
    )

    private fun save() {
        val cfg = collect()
        AppState.saveConfig(this, cfg)
        Toast.makeText(this, if (cfg.normalized().liveReady) "Saved — live mode on" else "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun test() {
        val cfg = collect().normalized()
        if (!cfg.liveReady) {
            Toast.makeText(this, "Fill TMDB + Sonarr/Radarr and enable live mode first", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show()
        Thread {
            val results = LiveRepository(cfg).pingAll()
            Handler(Looper.getMainLooper()).post {
                val msg = results.entries.joinToString("\n") { (k, v) -> "$k: ${if (v) "OK" else "FAIL"}" }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
