// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * L'économiseur d'écran du système, sur télé : est-ce Vision ? Sinon, le chemin le plus simple pour CETTE télé
 * (ReglesVeille.plan) : Vision l'écrit lui-même s'il en a le droit, le réglage de la télé s'il propose Vision, ou la
 * commande ADB affichée telle quelle pour le Décideur (Google TV, Chromecast). En attendant, le repli : Vision lance
 * sa veille sur l'accueil après inactivité (LanceurActivity, AgentService).
 */
object EconomiseurTv {
    private const val PREFS = "local"
    private const val CLE_VOULU = "economiseur_voulu"
    private const val SECURE_COMPOSANTS = "screensaver_components"
    private const val SECURE_DEFAUT = "screensaver_default_component"
    private const val SECURE_ACTIF = "screensaver_enabled"

    /** Le composant du rêve de Vision, tel qu'Android l'écrit (suit l'identifiant réel de l'appli). */
    fun composant(ctx: Context): String = ComponentName(ctx, VeilleService::class.java).flattenToShortString()

    /** Vrai : l'économiseur du système est Vision ; faux : un autre ; null : illisible sur cet appareil. */
    fun estVision(ctx: Context): Boolean? = try {
        val r = ctx.contentResolver
        ReglesVeille.economiseurEffectifEstVision(Settings.Secure.getString(r, SECURE_COMPOSANTS), Settings.Secure.getString(r, SECURE_DEFAUT))
    } catch (_: Exception) { null }

    private fun reveActif(ctx: Context): Boolean? = try {
        Settings.Secure.getString(ctx.contentResolver, SECURE_ACTIF)?.trim()?.toIntOrNull()?.let { it != 0 }
    } catch (_: Exception) { null }

    /** WRITE_SECURE_SETTINGS accordé par une commande ADB (`pm grant`) : Vision règle l'économiseur lui-même. */
    fun ecritureDirecte(ctx: Context): Boolean = try {
        ctx.packageManager.checkPermission("android.permission.WRITE_SECURE_SETTINGS", ctx.packageName) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun installe(ctx: Context, pk: String): Boolean = try { ctx.packageManager.getPackageInfo(pk, 0); true } catch (_: Exception) { false }

    private fun reglageSysteme(ctx: Context): Boolean = try {
        Intent(Settings.ACTION_DREAM_SETTINGS).resolveActivity(ctx.packageManager) != null
    } catch (_: Exception) { false }

    fun faits(ctx: Context): ReglesVeille.Faits = ReglesVeille.Faits(
        economiseurVision = estVision(ctx),
        reveActif = reveActif(ctx),
        ecritureDirecte = ecritureDirecte(ctx),
        reglageSysteme = reglageSysteme(ctx),
        choixMasque = ReglesVeille.ACCUEILS_SANS_CHOIX.any { installe(ctx, it) },
        surveillanceGlobale = try { VisionAccessibility.estActive(ctx) } catch (_: Exception) { false },
        accueilVision = try { AccueilDefaut.estAccueil(ctx) || (Local.accueilForce(ctx) && Modules.actif(ctx, Modules.ACCUEIL)) } catch (_: Exception) { false },
    )

    fun plan(ctx: Context): ReglesVeille.Plan = ReglesVeille.plan(faits(ctx)).also { dernierPlan = it; dernierPlanA = android.os.SystemClock.uptimeMillis() }

    @Volatile private var dernierPlan: ReglesVeille.Plan? = null
    @Volatile private var dernierPlanA = 0L
    /** Le plan d'il y a moins d'une minute (lu à chaque touche de l'accueil : jamais de lecture du système à chaque appui). */
    fun planRecent(ctx: Context): ReglesVeille.Plan {
        val p = dernierPlan
        return if (p != null && android.os.SystemClock.uptimeMillis() - dernierPlanA < 60_000L) p else plan(ctx)
    }

    /** L'étape de l'assistant est faite : Vision est l'économiseur, ou le repli a été choisi en connaissance de cause. */
    fun regle(ctx: Context): Boolean = plan(ctx).chemin == ReglesVeille.Chemin.DEJA_VISION || Local.repliChoisi(ctx)

    /** La valeur affichée sur la ligne « Économiseur de la télé » des réglages. */
    fun libelle(ctx: Context): String = ctx.getString(when (plan(ctx).chemin) {
        ReglesVeille.Chemin.DEJA_VISION -> R.string.eco_valeur_vision
        ReglesVeille.Chemin.ECRIRE -> R.string.eco_valeur_ecrire
        ReglesVeille.Chemin.REGLAGE -> R.string.eco_valeur_reglage
        ReglesVeille.Chemin.ADB -> R.string.eco_valeur_adb
    })

    /** Écrit Vision comme économiseur (et l'active). Faux si la télé refuse. */
    fun ecrire(ctx: Context): Boolean = try {
        val r = ctx.contentResolver
        Settings.Secure.putString(r, SECURE_COMPOSANTS, composant(ctx))
        Settings.Secure.putString(r, SECURE_ACTIF, "1")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLE_VOULU, true).apply()
        estVision(ctx) == true
    } catch (_: Exception) { false }

    /**
     * Au démarrage de l'agent : un Décideur a choisi Vision et Vision a le droit d'écrire, mais une mise à jour de la
     * télé (ou son fabricant) a remis un autre économiseur : on le remet. Jamais sans ce choix explicite.
     */
    fun maintenir(ctx: Context) {
        try {
            if (!ReglagesTvActivity.estTele(ctx) || !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLE_VOULU, false)) return
            if (ecritureDirecte(ctx) && (estVision(ctx) != true || reveActif(ctx) == false)) ecrire(ctx)
        } catch (_: Exception) {}
    }

    /** Ouvre le réglage de l'économiseur ; à défaut, l'affichage, puis les réglages. Faux si rien ne s'ouvre. */
    fun ouvrirReglage(act: Activity): Boolean {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)) {
            val i = Intent(action)
            if (try { i.resolveActivity(act.packageManager) == null } catch (_: Exception) { true }) continue
            try { Assistant.ouvrir(act); act.startActivity(i); return true } catch (_: Exception) {}
        }
        return false
    }

    /** Le paragraphe du repli, selon ce qui tient sur cette télé. */
    private fun texteRepli(ctx: Context, plan: ReglesVeille.Plan): String? {
        val delai = Local.delaiVeille(ctx).takeIf { it > 0 } ?: Local.delaiRepli(ctx)
        return when (plan.repli) {
            ReglesVeille.Repli.INUTILE -> null
            ReglesVeille.Repli.AUCUN -> ctx.getString(R.string.eco_repli_aucun)
            else -> if (delai <= 0) ctx.getString(R.string.eco_repli_jamais)
                    else ctx.getString(if (plan.repli == ReglesVeille.Repli.ACCUEILS) R.string.eco_repli_accueils else R.string.eco_repli_accueil_vision, delai)
        }
    }

    private fun choisirRepli(ctx: Context) {
        Local.poserRepliChoisi(ctx, true)
        if (Local.delaiRepli(ctx) <= 0) Local.poserDelaiRepli(ctx, ReglesVeille.REPLI_DEFAUT)
    }

    /** La boîte qui explique et propose, à la télécommande : le chemin de cette télé, et le repli. */
    fun proposer(act: Activity, apres: () -> Unit) {
        val f = faits(act)
        val plan = ReglesVeille.plan(f)
        val th = Ui.themeActif(act)
        val dp = { v: Float -> Ui.dp(act, v) }
        val corps = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24f), dp(8f), dp(24f), dp(4f)) }
        fun para(s: String, couleur: Int = th.encre2, mono: Boolean = false) = corps.addView(Ui.sansCesure(TextView(act)).apply {
            text = s; textSize = if (mono) 15f else 16f; setTextColor(couleur); setPadding(0, dp(if (mono) 4f else 8f), 0, dp(if (mono) 4f else 4f))
            if (mono) { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setBackgroundColor(Palette.melanger(th.carte, th.encre, 0.08f)); setPadding(dp(10f), dp(6f), dp(10f), dp(6f)) }
        })
        para(act.getString(when {
            plan.chemin == ReglesVeille.Chemin.DEJA_VISION -> R.string.eco_etat_vision
            f.economiseurVision == true -> R.string.eco_etat_vision_eteint
            f.economiseurVision == false -> R.string.eco_etat_autre
            else -> R.string.eco_etat_inconnu
        }), th.encre)
        when (plan.chemin) {
            ReglesVeille.Chemin.ECRIRE -> para(act.getString(R.string.eco_ecrire))
            ReglesVeille.Chemin.REGLAGE -> para(act.getString(R.string.eco_reglage))
            ReglesVeille.Chemin.ADB -> {
                para(act.getString(R.string.eco_adb))
                ReglesVeille.commandesAdb(act.packageName, composant(act)).forEach { para(it, th.encre, mono = true) }
                para(act.getString(R.string.eco_adb_apres))
            }
            ReglesVeille.Chemin.DEJA_VISION -> {}
        }
        texteRepli(act, plan)?.let { para(it) }
        val b = Ui.dialogue(act).setTitle(act.getString(R.string.eco_titre)).setView(ScrollView(act).apply { addView(corps) })
        when (plan.chemin) {
            ReglesVeille.Chemin.DEJA_VISION -> b.setPositiveButton(act.getString(R.string.eco_ok)) { _, _ -> apres() }
            ReglesVeille.Chemin.ECRIRE -> b.setPositiveButton(act.getString(R.string.eco_choisir_vision)) { _, _ ->
                val ok = ecrire(act)
                if (!ok) choisirRepli(act)
                Ui.dialogue(act).setTitle(act.getString(R.string.eco_titre)).setMessage(act.getString(if (ok) R.string.eco_ecrit else R.string.eco_ecrit_echec))
                    .setPositiveButton(act.getString(R.string.eco_ok)) { _, _ -> apres() }.show()
            }
            ReglesVeille.Chemin.REGLAGE -> b.setPositiveButton(act.getString(R.string.eco_ouvrir_reglage)) { _, _ ->
                if (!ouvrirReglage(act)) {
                    Ui.dialogue(act).setTitle(act.getString(R.string.eco_introuvable_titre)).setMessage(act.getString(R.string.eco_introuvable))
                        .setPositiveButton(act.getString(R.string.eco_ok)) { _, _ -> choisirRepli(act); apres() }.show()
                }
            }
            // Google TV : le réglage n'y proposera pas Vision ; le repli est l'action utile, le réglage reste à portée.
            ReglesVeille.Chemin.ADB -> b.setPositiveButton(act.getString(R.string.eco_utiliser_repli)) { _, _ -> choisirRepli(act); apres() }
        }
        if (plan.chemin != ReglesVeille.Chemin.DEJA_VISION) {
            if (plan.chemin == ReglesVeille.Chemin.ADB) {
                if (f.reglageSysteme) b.setNeutralButton(act.getString(R.string.eco_ouvrir_reglage)) { _, _ -> ouvrirReglage(act) }
            } else b.setNeutralButton(act.getString(R.string.eco_utiliser_repli)) { _, _ -> choisirRepli(act); apres() }
            b.setNegativeButton(act.getString(R.string.eco_plus_tard), null)
        }
        b.show()
    }
}
