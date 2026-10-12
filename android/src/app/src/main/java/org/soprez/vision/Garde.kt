// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Ré-authentification avant tout geste de gestion (faille signalée en essai le 2026-10-08) : le
 * téléphone d'un parent, laissé déverrouillé ou prêté, ne doit pas permettre d'accorder du temps,
 * d'ouvrir, de fermer, de changer les règles… sans que la personne prouve qu'elle gère.
 *
 * Empreinte ou visage si la biométrie est activée sur cet appareil (Biometrie, permise par HA),
 * sinon le code de gestion. Une réussite ouvre une courte fenêtre (2 min par défaut, réglable)
 * pendant laquelle les gestes suivants passent sans redemander. La fenêtre vit en mémoire
 * (horloge monotone) : elle tombe avec l'appli et à l'extinction de l'écran.
 *
 * Ce n'est qu'une protection de l'appareil : Home Assistant reste la frontière de sécurité
 * (essai/gestion_attaques_test.py côté intégration).
 */
object Garde {
    private const val PREFS = "garde"
    val CHOIX_MINUTES = listOf(0, 1, 2, 5, 10)

    @Volatile private var jusqua = 0L

    fun minutes(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("minutes", 2)
    fun regler(ctx: Context, m: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("minutes", m.coerceIn(0, 10)).apply()

    /** La fenêtre est-elle encore ouverte ? */
    fun ouverte(): Boolean = SystemClock.elapsedRealtime() < jusqua

    /** Écran éteint, appli quittée : on referme. */
    fun fermer() { jusqua = 0L }

    @Volatile private var guette = false

    private fun reussi(ctx: Context) {
        jusqua = SystemClock.elapsedRealtime() + minutes(ctx) * 60_000L
        if (guette) return
        guette = true
        try {
            val r = object : android.content.BroadcastReceiver() { override fun onReceive(c: Context, i: android.content.Intent) { fermer() } }
            val f = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
            val app = ctx.applicationContext
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
        } catch (_: Exception) { guette = false }
    }

    /**
     * Exécute `ok` après preuve (ou tout de suite si la fenêtre est ouverte).
     * `annule` : la personne renonce (utile pour fermer un écran transparent).
     */
    fun exiger(act: Activity, raison: String, annule: () -> Unit = {}, ok: () -> Unit) {
        if (ouverte()) { ok(); return }
        val cfg = Config(act)
        if (Biometrie.disponible(act)) {
            Biometrie.demander(act, raison, ok = { reussi(act); ok() }, code = { demanderCode(act, cfg, raison, annule, ok) })
            return
        }
        demanderCode(act, cfg, raison, annule, ok)
    }

    private fun demanderCode(act: Activity, cfg: Config, raison: String, annule: () -> Unit, ok: () -> Unit) {
        if (act.isFinishing) return
        if (!cfg.aUnCode) { choisirCode(act, cfg, raison, annule, ok); return }
        val champ = Ui.champ(act, act.getString(R.string.garde_code_gestion), "", chiffres = true)
        val cadre = FrameLayout(act).apply { val p = Ui.dp(act, 20f); setPadding(p, Ui.dp(act, 8f), p, 0); addView(champ) }
        Ui.dialogue(act).setTitle(R.string.garde_code_gestion).setMessage(raison).setView(cadre)
            .setPositiveButton(R.string.garde_valider) { _, _ ->
                if (cfg.verifierCode(champ.text.toString())) {
                    // Code juste sur l'appareil d'un parent : la biométrie (si HA la permet) se (ré)arme.
                    Biometrie.armerApresCode(act)
                    reussi(act); ok()
                } else {
                    Toast.makeText(act, R.string.garde_code_incorrect, Toast.LENGTH_SHORT).show(); annule()
                }
            }
            .setNegativeButton(R.string.commun_annuler) { _, _ -> annule() }
            .setOnCancelListener { annule() }
            .show()
    }

    /** Pas encore de code sur cet appareil : on le choisit maintenant (deux fois), avant le premier geste. */
    private fun choisirCode(act: Activity, cfg: Config, raison: String, annule: () -> Unit, ok: () -> Unit) {
        val nouveau = Ui.champ(act, act.getString(R.string.garde_nouveau_code), "", chiffres = true)
        val encore = Ui.champ(act, act.getString(R.string.garde_code_encore), "", chiffres = true)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(act, 20f); setPadding(p, Ui.dp(act, 8f), p, 0)
            addView(nouveau); addView(encore)
        }
        Ui.dialogue(act).setTitle(R.string.garde_choisir_code)
            .setMessage(act.getString(R.string.garde_choisir_code_texte, raison))
            .setView(col)
            .setPositiveButton(R.string.commun_enregistrer) { _, _ ->
                val a = nouveau.text.toString(); val b = encore.text.toString()
                when {
                    a.length < 4 || !a.all { it.isDigit() } -> { Toast.makeText(act, R.string.garde_4_chiffres, Toast.LENGTH_SHORT).show(); annule() }
                    a != b -> { Toast.makeText(act, R.string.garde_codes_differents, Toast.LENGTH_SHORT).show(); annule() }
                    else -> { cfg.definirCode(a); Biometrie.armerApresCode(act); reussi(act); ok() }
                }
            }
            .setNegativeButton(R.string.commun_annuler) { _, _ -> annule() }
            .setOnCancelListener { annule() }
            .show()
    }
}
