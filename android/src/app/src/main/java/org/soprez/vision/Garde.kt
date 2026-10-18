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

    /** Essais instrumentés seulement (posé par réflexion, comme [jusqua]) : l'empreinte réussie, simulée. Jamais vrai
     *  dans l'appli (OctroiTest le vérifie). */
    @Volatile private var preuveEssai = false

    fun minutes(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("minutes", 2)
    fun regler(ctx: Context, m: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("minutes", m.coerceIn(0, 10)).apply()

    /** La fenêtre est-elle encore ouverte ? */
    fun ouverte(): Boolean = SystemClock.elapsedRealtime() < jusqua

    /** Écran éteint, appli quittée : on referme. */
    fun fermer() { jusqua = 0L; Octroi.oublier() }

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
            Biometrie.demander(act, raison, ok = { reussi(act); ok() }, code = { demanderCode(act, cfg, raison, annule, ok) }, annule = annule)
            return
        }
        demanderCode(act, cfg, raison, annule, ok)
    }

    /**
     * FAILLE DE SÉCURITÉ (l'auteur, 16-10) : un geste qui DÉVERROUILLE ou ACCORDE (accepter une demande, déverrouiller,
     * prolonger, autoriser, ouvrir, valider un appareil) demande l'empreinte ou le code À CHAQUE FOIS : jamais de
     * fenêtre de grâce. La réussite délivre une preuve à usage unique ([Octroi]) sans laquelle [Net.post] refuse
     * d'envoyer la requête. La fenêtre de grâce reste pour les autres gestes de gestion ([exiger]).
     */
    fun exigerOctroi(act: Activity, raison: String, annule: () -> Unit = {}, ok: () -> Unit) {
        val apres = { Octroi.delivrer(); ok() }
        if (preuveEssai) { apres(); return }
        val cfg = Config(act)
        // Proposition validée en essai (16-10) : jamais « choisir un code puis accorder aussitôt ». Sans code, on le fait
        // choisir (le Décideur aurait dû le faire dès que l'appareil l'est devenu), et le geste n'est PAS fait.
        if (!cfg.aUnCode && CodeDecideur.codeHa(act) != true) { imposerCode(act, apresChoix = annule); return }
        if (Biometrie.disponible(act)) {
            Biometrie.demander(act, raison, ok = { reussi(act); apres() }, code = { demanderCode(act, cfg, raison, annule, apres) }, annule = annule)
            return
        }
        demanderCode(act, cfg, raison, annule, apres)
    }

    // ------------------------------------------------------------------ le code du Décideur, dès qu'il l'est

    private const val PREF_REQUIS = "code_requis"

    /** Pur : un appareil Décideur (il gère ou approuve) sans code doit le choisir avant tout autre geste. */
    fun codeRequis(fonctions: Set<String>, aUnCode: Boolean): Boolean =
        !aUnCode && ("gerer" in fonctions || "approuver" in fonctions)

    /** Après un relevé (Profil.releve) : l'appareil vient-il de devenir Décideur sans code ? On le note. */
    fun noterRole(ctx: Context, fonctions: Set<String>) {
        val requis = codeRequis(fonctions, Config(ctx).aUnCode) && CodeDecideur.codeHa(ctx) != true
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_REQUIS, requis).apply()
    }

    /** À l'ouverture des écrans d'un Décideur : s'il n'a pas encore de code, il le choisit maintenant. */
    fun exigerCodeDecideur(act: Activity) {
        val requis = ctx(act).getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_REQUIS, false)
        if (!requis && !codeRequis(Profil.fonctions(act), Config(act).aUnCode)) return
        if (Config(act).aUnCode || CodeDecideur.codeHa(act) == true) { ctx(act).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_REQUIS, false).apply(); return }
        imposerCode(act) {}
    }

    private fun ctx(act: Activity): Context = act.applicationContext

    @Volatile private var imposeEnCours = false

    /** Le choix du code, sans geste derrière : une fois posé, la biométrie (si HA la permet) se (ré)arme, et rien
     *  n'est accordé — il faut refaire le geste, qui demandera alors le code ou l'empreinte. */
    private fun imposerCode(act: Activity, apresChoix: () -> Unit) {
        if (imposeEnCours || act.isFinishing) return
        imposeEnCours = true
        val cfg = Config(act)
        val fin = { imposeEnCours = false; apresChoix() }
        choisirCode(act, cfg, act.getString(R.string.garde_code_decideur_texte, Vocabulaire.decideur(act)), annule = fin, ok = {
            ctx(act).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_REQUIS, false).apply()
            jusqua = 0L  // pas de fenêtre de grâce offerte par le choix du code
            Ui.toast(act, act.getString(R.string.garde_code_pose_refaire), court = false)
            fin()
        }, titre = R.string.garde_code_decideur_titre)
    }

    /** Le code actuel, tapé (pour le changer dans HA) : [ok] avec le texte, rien si on renonce. */
    fun saisirCodeActuel(act: Activity, ok: (String) -> Unit) {
        val champ = Ui.champ(act, act.getString(R.string.garde_code_actuel), "", chiffres = true)
        val cadre = FrameLayout(act).apply { val p = Ui.dp(act, 20f); setPadding(p, Ui.dp(act, 8f), p, 0); addView(champ) }
        Ui.dialogue(act).setTitle(R.string.garde_code_actuel).setView(cadre)
            .setPositiveButton(R.string.garde_valider) { _, _ -> ok(champ.text.toString()) }
            .setNegativeButton(R.string.commun_annuler, null).show()
    }

    /** Le geste qui enverra [corps] à [chemin] : [exigerOctroi] s'il accorde (Octroi.estOctroi), sinon [exiger]. Une
     *  preuve d'octroi n'est jamais délivrée pour un geste qui n'accorde rien (elle ne traîne pas). */
    fun exigerPour(act: Activity, raison: String, chemin: String, corps: org.json.JSONObject, annule: () -> Unit = {}, ok: () -> Unit) =
        if (Octroi.estOctroi(chemin, corps)) exigerOctroi(act, raison, annule, ok) else exiger(act, raison, annule, ok)

    private fun demanderCode(act: Activity, cfg: Config, raison: String, annule: () -> Unit, ok: () -> Unit) {
        if (act.isFinishing) return
        // 16-10 : le code du Décideur peut n'être que dans HA (autre appareil, tableau) : on le demande et HA le vérifie.
        if (!cfg.aUnCode && CodeDecideur.codeHa(act) != true) { choisirCode(act, cfg, raison, annule, ok); return }
        val champ = Ui.champ(act, act.getString(R.string.garde_code_gestion), "", chiffres = true)
        val cadre = FrameLayout(act).apply { val p = Ui.dp(act, 20f); setPadding(p, Ui.dp(act, 8f), p, 0); addView(champ) }
        Ui.dialogue(act).setTitle(R.string.garde_code_gestion).setMessage(raison).setView(cadre)
            .setPositiveButton(R.string.garde_valider) { _, _ ->
                // 16-10 (retour d'essai) : un seul code par Décideur — Home Assistant le vérifie (essais faux limités là-bas).
                CodeDecideur.verifier(act, champ.text.toString()) { res ->
                    if (res.juste) {
                        // Code juste sur l'appareil d'un Décideur : la biométrie (si HA la permet) se (ré)arme.
                        Biometrie.armerApresCode(act)
                        reussi(act); ok()
                    } else {
                        Ui.toast(act, res.message, court = true, genre = OeilModele.Genre.ERREUR); annule()
                    }
                }
            }
            .setNegativeButton(R.string.commun_annuler) { _, _ -> annule() }
            .setOnCancelListener { annule() }
            .show()
    }

    /** Pas encore de code sur cet appareil : on le choisit maintenant (deux fois), avant le premier geste. */
    private fun choisirCode(act: Activity, cfg: Config, raison: String, annule: () -> Unit, ok: () -> Unit,
                            titre: Int = R.string.garde_choisir_code) {
        val nouveau = Ui.champ(act, act.getString(R.string.garde_nouveau_code), "", chiffres = true)
        val encore = Ui.champ(act, act.getString(R.string.garde_code_encore), "", chiffres = true)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(act, 20f); setPadding(p, Ui.dp(act, 8f), p, 0)
            addView(nouveau); addView(encore)
        }
        Ui.dialogue(act).setTitle(titre)
            .setMessage(if (titre == R.string.garde_choisir_code) act.getString(R.string.garde_choisir_code_texte, raison) else raison)
            .setView(col)
            .setPositiveButton(R.string.commun_enregistrer) { _, _ ->
                val a = nouveau.text.toString(); val b = encore.text.toString()
                when {
                    a.length < 4 || !a.all { it.isDigit() } -> { Ui.toast(act, act.getString(R.string.garde_4_chiffres), court = true, genre = OeilModele.Genre.ERREUR); annule() }
                    a != b -> { Ui.toast(act, act.getString(R.string.garde_codes_differents), court = true, genre = OeilModele.Genre.ERREUR); annule() }
                    else -> CodeDecideur.definir(act, a, null) { res ->
                        if (res.juste) { Biometrie.armerApresCode(act); reussi(act); ok() }
                        else { Ui.toast(act, res.message, court = true, genre = OeilModele.Genre.ERREUR); annule() }
                    }
                }
            }
            .setNegativeButton(R.string.commun_annuler) { _, _ -> annule() }
            .setOnCancelListener { annule() }
            .show()
    }
}
