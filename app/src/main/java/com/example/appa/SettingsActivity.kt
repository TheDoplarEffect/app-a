package com.example.appa

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        AppState.init(this)
        val t = AppTheme.current

        val root = FrameLayout(this).withAurora()
        val body = col(this) {
            padSystemBars(top = true, bottom = true, start = true, end = true, extraTopDp = t.space.sm, extraBottomDp = t.space.sm)
            val px = dp(t.space.lg); setPadding(px, paddingTop, px, paddingBottom)

            addView(AppTopBar(this@SettingsActivity, "Settings", leading = TopBarAction("✕") { finishDropping() }), lp(MATCH, WRAP))
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
            fun field(label: String, key: String, value: String, hint: String = "", secret: Boolean = false) {
                val (lab, et) = FormField(this@SettingsActivity, label, value, hint, secret, t)
                form.addView(lab, lp(MATCH, WRAP, marginTopDp = t.space.md))
                form.addView(et, lp(MATCH, WRAP, marginTopDp = t.space.xs))
                fields[key] = et
            }

            field("TMDB API read token", "tmdbToken", cfg.tmdbToken, "eyJ…", secret = true)
            field("Sonarr URL", "sonarrUrl", cfg.sonarrUrl, "http://192.168.178.42:8989")
            field("Sonarr API key", "sonarrKey", cfg.sonarrKey, secret = true)
            field("Radarr URL", "radarrUrl", cfg.radarrUrl, "http://192.168.178.42:7878")
            field("Radarr API key", "radarrKey", cfg.radarrKey, secret = true)
            field("Prowlarr URL (magnets)", "prowlarrUrl", cfg.prowlarrUrl, "http://192.168.178.42:9696")
            field("Prowlarr API key", "prowlarrKey", cfg.prowlarrKey, secret = true)
            field("Jellyfin URL", "jellyfinUrl", cfg.jellyfinUrl, "https://…")
            field("Jellyfin API key", "jellyfinKey", cfg.jellyfinKey, secret = true)

            form.addView(SwitchRow(this@SettingsActivity, "Use live services (otherwise placeholder mode)", cfg.liveMode) { liveChecked = it },
                lp(MATCH, WRAP, marginTopDp = t.space.lg))

            testResults = row(this@SettingsActivity) { gravity = android.view.Gravity.CENTER_VERTICAL }
            form.addView(testResults, lp(MATCH, WRAP, marginTopDp = t.space.md))

            val btnRow = row(this@SettingsActivity) { gravity = android.view.Gravity.CENTER_VERTICAL }
            btnRow.addView(PrimaryButton(this@SettingsActivity, "Save") { save() }, lp(WRAP, WRAP))
            btnRow.addView(SecondaryButton(this@SettingsActivity, "Test connection") { test() }, lp(WRAP, WRAP, marginStartDp = t.space.sm))
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
        AppState.saveConfig(this, cfg)
        Toast.makeText(this, if (cfg.normalized().liveReady) "Saved — live mode on" else "Saved", Toast.LENGTH_SHORT).show()
        finishDropping()
    }

    private fun test() {
        val t = AppTheme.current
        val cfg = collect().normalized()
        if (!cfg.liveReady) {
            Toast.makeText(this, "Fill TMDB + Sonarr/Radarr and enable live mode first", Toast.LENGTH_LONG).show()
            return
        }
        testResults.removeAllViews()
        testResults.addView(AppText(this, "Testing…", TextStyle.LABEL, t = t))
        Thread {
            val results = LiveRepository(cfg).pingAll()
            Handler(Looper.getMainLooper()).post {
                testResults.removeAllViews()
                results.forEach { (name, ok) ->
                    testResults.addView(
                        Chip(this, "$name ${if (ok) "✓" else "✗"}", if (ok) t.colors.success else t.colors.error, t = t),
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
                    )
                }
            }
        }.start()
    }
}
