package com.example.appa

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Settings screen (organism): glass form for service URLs/keys (stored
 * encrypted on-device), live-mode switch, save + test-connection with
 * per-service result chips.
 */
class SettingsActivity : Activity() {

    private lateinit var fields: MutableMap<String, EditText>
    private var liveChecked = false
    private lateinit var testResults: LinearLayout
    private lateinit var saveBtn: android.widget.TextView
    private lateinit var testBtn: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        AppState.init(this)
        val t = AppTheme.current

        val root = FrameLayout(this).withAurora()
        val body = col(this) {
            padSystemBars(top = true, bottom = true, start = true, end = true, extraTopDp = t.space.sm, extraBottomDp = t.space.sm)
            val px = dp(t.space.lg); setPadding(px, paddingTop, px, paddingBottom)

            addView(AppTopBar(this@SettingsActivity, "Settings", leading = TopBarAction("✕", contentDescription = "Close") { finishDropping() }), lp(MATCH, WRAP))
            addView(AppText(
                this@SettingsActivity,
                "Secrets are stored encrypted on this device only (Android Keystore) — never in git. " +
                    "LAN HTTP is allowed for local services.",
                TextStyle.LABEL, t = t
            ), lp(MATCH, WRAP, marginTopDp = t.space.sm))

            val scroll = ScrollView(this@SettingsActivity)
            val form = col(this@SettingsActivity)
            scroll.addView(form)
            addView(scroll, lp(MATCH, 0, weight = 1f, marginTopDp = t.space.md))

            fields = mutableMapOf()
            val cfg = AppState.config
            fun field(label: String, key: String, value: String, hint: String = "", secret: Boolean = false, isUrl: Boolean = false) {
                val (lab, et) = FormField(this@SettingsActivity, label, value, hint, secret, t)
                val err = AppText(this@SettingsActivity, "", TextStyle.LABEL, color = t.colors.error, t = t).apply { visibility = View.GONE }
                form.addView(lab, lp(MATCH, WRAP, marginTopDp = t.space.md))
                form.addView(et, lp(MATCH, WRAP, marginTopDp = t.space.xs))
                form.addView(err, lp(MATCH, WRAP, marginTopDp = 2))
                if (isUrl) { // only URL fields get the scheme validator — never token/key fields
                    et.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                        override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {
                            val e = AppConfig().urlError(et.text.toString().trim().trimEnd('/'))
                            err.text = e ?: ""
                            err.visibility = if (e == null) View.GONE else View.VISIBLE
                        }
                        override fun afterTextChanged(a: android.text.Editable?) {}
                    })
                }
                fields[key] = et
            }

            field("TMDB API read token", "tmdbToken", cfg.tmdbToken, "eyJ…", secret = true)
            field("Sonarr URL", "sonarrUrl", cfg.sonarrUrl, "http://192.168.178.42:8989", isUrl = true)
            field("Sonarr API key", "sonarrKey", cfg.sonarrKey, secret = true)
            field("Radarr URL", "radarrUrl", cfg.radarrUrl, "http://192.168.178.42:7878", isUrl = true)
            field("Radarr API key", "radarrKey", cfg.radarrKey, secret = true)
            field("Prowlarr URL (magnets)", "prowlarrUrl", cfg.prowlarrUrl, "http://192.168.178.42:9696", isUrl = true)
            field("Prowlarr API key", "prowlarrKey", cfg.prowlarrKey, secret = true)
            field("Jellyfin URL", "jellyfinUrl", cfg.jellyfinUrl, "https://…", isUrl = true)
            field("Jellyfin API key", "jellyfinKey", cfg.jellyfinKey, secret = true)

            form.addView(SwitchRow(this@SettingsActivity, "Use live services (otherwise placeholder mode)", cfg.liveMode) { liveChecked = it },
                lp(MATCH, WRAP, marginTopDp = t.space.lg))

            if (AppState.configWasWiped) {
                form.addView(AppText(this@SettingsActivity, "⚠ Stored config was reset (device key changed) — please re-enter your keys.", TextStyle.BODY, color = t.colors.warning, t = t),
                    lp(MATCH, WRAP, marginTopDp = t.space.md))
            }
            testResults = col(this@SettingsActivity)
            form.addView(testResults, lp(MATCH, WRAP, marginTopDp = t.space.md))

            val btnRow = row(this@SettingsActivity) { gravity = android.view.Gravity.CENTER_VERTICAL }
            saveBtn = PrimaryButton(this@SettingsActivity, "Save") { save() }
            testBtn = SecondaryButton(this@SettingsActivity, "Test connection") { test() }
            btnRow.addView(saveBtn, lp(WRAP, WRAP))
            btnRow.addView(testBtn, lp(WRAP, WRAP, marginStartDp = t.space.sm))
            addView(btnRow, lp(MATCH, WRAP, marginTopDp = t.space.lg))
        }
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        liveChecked = AppState.config.liveMode
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
        liveMode = liveChecked
    )

    private fun save() {
        val cfg = collect()
        // block saving with an invalid URL (missing scheme) — show where
        for (key in listOf("sonarrUrl", "radarrUrl", "prowlarrUrl", "jellyfinUrl")) {
            val err = AppConfig().urlError(fields[key]!!.text.toString().trim().trimEnd('/'))
            if (err != null) {
                Toast.makeText(this, "${fields[key]!!.hint}: $err", Toast.LENGTH_LONG).show()
                return
            }
        }
        saveBtn.isEnabled = false
        saveBtn.text = "Saving…"
        AppState.saveConfig(this, cfg) {
            // honest feedback: only claim live mode when it actually engaged
            Toast.makeText(
                this,
                if (AppState.liveMode) "Saved — live mode on"
                else "Saved — placeholder mode (fill TMDB + Sonarr/Radarr and enable live mode to go live)",
                Toast.LENGTH_LONG
            ).show()
            finishDropping()
        }
    }

    private fun test() {
        val t = AppTheme.current
        val cfg = collect().normalized()
        if (!cfg.liveReady) {
            Toast.makeText(this, "Fill TMDB + Sonarr/Radarr and enable live mode first", Toast.LENGTH_LONG).show()
            return
        }
        testBtn.isEnabled = false
        testBtn.text = "Testing…"
        testResults.removeAllViews()
        val names = listOf("tmdb", "prowlarr", "sonarr", "radarr", "jellyfin")
        val chips = names.associateWith { name ->
            Chip(this, "$name …", t.colors.surfaceGlassStrong, fg = t.colors.textSecondary, t = t).also {
                testResults.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6); topMargin = dp(4) })
            }
        }
        WorkerScope.spawn {
            val results = runCatching { LiveRepository(cfg).pingAll() }.getOrElse { emptyMap() }
            Handler(Looper.getMainLooper()).post {
                if (isFinishing || isDestroyed) return@post
                names.forEach { name ->
                    val ok = results[name] == true
                    val c = chips[name]!!
                    c.text = "$name ${if (ok) "✓" else "✗"}"
                    c.restyleChip(if (ok) t.colors.success else t.colors.error, t)
                }
                testBtn.isEnabled = true
                testBtn.text = "Test connection"
            }
        }
    }
}
