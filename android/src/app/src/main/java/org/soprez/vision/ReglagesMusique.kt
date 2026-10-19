// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Retours de l'auteur (15-10) : tous les réglages de la musique vivent dans les paramètres Vision (section « Musique »,
 * ReglagesTvActivity) ; l'appli Musique n'a plus qu'un raccourci qui y mène ([ouvrir]).
 *
 * Ce fichier donne le contenu de la section (lecteur : fond animé, pochette floutée ; recherche : clavier, voix,
 * recherches récentes) pour que l'écran des paramètres n'ait que l'appel et l'intitulé (coordination avec la
 * Refonte UX : vision-src/COORDINATION-musique-reglages-et-choix.md).
 */
object ReglagesMusique {
    /** L'étiquette de la section dans les paramètres (téléphone : défilement ; télé : rubrique « musique »). */
    const val SECTION = "s_musique"
    const val CLAVIER_VISION = "vision"
    const val CLAVIER_ANDROID = "android"

    /** Le raccourci de l'appli Musique : les paramètres Vision, directement sur la section Musique. */
    fun ouvrir(ctx: Context) = ReglagesTvActivity.ouvrir(ctx, SECTION)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("musique_recherche", Context.MODE_PRIVATE)

    /** Le clavier de la recherche. Par défaut (recommandation) : celui de Vision sur la télé, celui d'Android ailleurs. */
    fun clavierParDefaut(tele: Boolean): String = if (tele) CLAVIER_VISION else CLAVIER_ANDROID
    fun clavier(ctx: Context): String = prefs(ctx).getString("clavier", null)?.takeIf { it == CLAVIER_VISION || it == CLAVIER_ANDROID }
        ?: clavierParDefaut(ReglagesTvActivity.estTele(ctx))
    fun clavier(ctx: Context, v: String) = prefs(ctx).edit().putString("clavier", v).apply()

    /** La recherche vocale : montrée seulement si l'appareil a un service de reconnaissance ([voixPossible]). */
    fun voix(ctx: Context): Boolean = prefs(ctx).getBoolean("voix", true)
    fun voix(ctx: Context, oui: Boolean) = prefs(ctx).edit().putBoolean("voix", oui).apply()

    fun intentionVoix(ctx: Context): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, ctx.getString(R.string.musrech_voix_invite))
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    fun voixPossible(ctx: Context): Boolean = try {
        ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    } catch (_: Exception) { false }

    fun nomClavier(ctx: Context, v: String): String =
        ctx.getString(if (v == CLAVIER_VISION) R.string.musrech_clavier_vision else R.string.musrech_clavier_android)

    private fun oui(ctx: Context, b: Boolean) = ctx.getString(if (b) R.string.mus_oui else R.string.mus_non)

    /**
     * Les lignes de la section, dans la forme des paramètres (titre, valeur, étiquette, action). Deux valeurs : un
     * appui change ; plus : une liste. [repeindre] redessine les paramètres après un changement.
     */
    fun lignes(act: Activity, ligne: (titre: String, valeur: String, tag: String, action: () -> Unit) -> Unit, repeindre: () -> Unit,
               vue: ((android.view.View) -> Unit)? = null, theme: Theme? = null, tailleSp: Float = 14f) {
        ligne(act.getString(R.string.fond_titre), FondLecteur.resume(act), "mus_fond") { choisirFond(act, repeindre) }
        ligne(act.getString(R.string.mus_fond_floute), oui(act, Party.fondFloute(act)), "mus_floute") { Party.fondFloute(act, !Party.fondFloute(act)); repeindre() }
        // Règle de l'auteur : deux options → étiquettes, par le composant partagé (ChoixAdaptatif) quand l'écran peut le poser.
        if (vue != null) {
            val th = theme ?: Local.themeEffectif(act, Palette.APPLI)
            fun choix(titre: String, tag: String, options: List<ChoixAdaptatif.Option>, choisie: String, change: (String) -> Unit) {
                val bloc = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(Ui.dp(act, 4f), Ui.dp(act, 6f), 0, Ui.dp(act, 10f)); this.tag = tag }
                bloc.addView(Ui.sansCesure(android.widget.TextView(act).apply { text = titre; textSize = tailleSp + 1f; setTextColor(th.encre); typeface = Polices.moyen(act); setPadding(0, 0, 0, Ui.dp(act, 6f)) }))
                bloc.addView(Ui.choix(act, titre, options, choisie, th, tailleSp, change))
                vue(bloc)
            }
            choix(act.getString(R.string.musrech_clavier), "mus_clavier", listOf(ChoixAdaptatif.Option(CLAVIER_VISION, nomClavier(act, CLAVIER_VISION)),
                ChoixAdaptatif.Option(CLAVIER_ANDROID, nomClavier(act, CLAVIER_ANDROID))), clavier(act)) { c -> clavier(act, c) }
            if (voixPossible(act)) choix(act.getString(R.string.musrech_voix), "mus_voix", listOf(ChoixAdaptatif.Option("oui", oui(act, true)),
                ChoixAdaptatif.Option("non", oui(act, false))), if (voix(act)) "oui" else "non") { c -> voix(act, c == "oui") }
        } else {
            ligne(act.getString(R.string.musrech_clavier), nomClavier(act, clavier(act)), "mus_clavier") {
                clavier(act, if (clavier(act) == CLAVIER_VISION) CLAVIER_ANDROID else CLAVIER_VISION); repeindre()
            }
            if (voixPossible(act)) ligne(act.getString(R.string.musrech_voix), oui(act, voix(act)), "mus_voix") { voix(act, !voix(act)); repeindre() }
        }
        // 1.93.06 (retour d'essai) : la musique face au son des autres applis (FocusMusique) ; aussi réglable depuis HA (musique_regler).
        val modes = FocusMusique.MODES.map { ChoixAdaptatif.Option(it, nomFocus(act, it)) }
        if (vue != null) {
            val th = theme ?: Local.themeEffectif(act, Palette.APPLI)
            val bloc = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(Ui.dp(act, 4f), Ui.dp(act, 6f), 0, Ui.dp(act, 10f)); this.tag = "mus_focus" }
            bloc.addView(Ui.sansCesure(android.widget.TextView(act).apply { text = act.getString(R.string.focus_titre); textSize = tailleSp + 1f; setTextColor(th.encre); typeface = Polices.moyen(act); setPadding(0, 0, 0, Ui.dp(act, 6f)) }))
            bloc.addView(Ui.choix(act, act.getString(R.string.focus_titre), modes, FocusMusique.mode(act), th, tailleSp) { m -> FocusMusique.poserMode(act, m); repeindre() })
            vue(bloc)
        } else ligne(act.getString(R.string.focus_titre), nomFocus(act, FocusMusique.mode(act)), "mus_focus") {
            val i = FocusMusique.MODES.indexOf(FocusMusique.mode(act))
            FocusMusique.poserMode(act, FocusMusique.MODES[(i + 1) % FocusMusique.MODES.size]); repeindre()
        }
        if (FocusMusique.mode(act) == FocusMusique.APPLIS) {
            val k = FocusMusique.applis(act).size
            ligne(act.getString(R.string.focus_applis_titre), if (k == 0) act.getString(R.string.focus_applis_aucune) else act.resources.getQuantityString(R.plurals.focus_applis_n, k, k), "mus_focus_applis") {
                choisirApplisFocus(act, repeindre)
            }
        }
        if (FocusMusique.mode(act) != FocusMusique.JAMAIS) ligne(act.getString(R.string.focus_reprise), oui(act, FocusMusique.reprise(act)), "mus_focus_reprise") {
            FocusMusique.poserReprise(act, !FocusMusique.reprise(act)); repeindre()
        }
        val n = RechercheMusique.recents(act).size
        ligne(act.getString(R.string.musrech_recents), if (n == 0) act.getString(R.string.musrech_recents_aucune) else act.resources.getQuantityString(R.plurals.musrech_recents_n, n, n), "mus_recents") {
            // 1.93.05 (l'auteur, retour 32 : « Recherches récentes » effaçait tout dès le clic) : l'entrée dit ce qu'elle fait
            // (« Effacer les recherches récentes ») et demande confirmation ; rien n'est effacé avant « Effacer ».
            if (n > 0) Ui.dialogue(act).setTitle(act.getString(R.string.musrech_recents_titre_confirmer))
                .setMessage(act.getString(R.string.musrech_recents_texte_confirmer))
                .setNegativeButton(act.getString(R.string.commun_lot2_annuler), null)
                .setPositiveButton(act.getString(R.string.musrech_recents_effacer)) { _, _ ->
                    RechercheMusique.effacerRecents(act); Ui.toast(act, act.getString(R.string.musrech_recents_effaces), court = true); repeindre()
                }.show()
        }
    }

    fun nomFocus(ctx: Context, m: String): String = ctx.getString(when (m) {
        FocusMusique.APPLIS -> R.string.focus_applis
        FocusMusique.JAMAIS -> R.string.focus_jamais
        else -> R.string.focus_toujours
    })

    /** Les applis qui mettent la musique en pause (mode « Seulement pour certaines applis ») : celles du lanceur, cochées. */
    fun choisirApplisFocus(act: Activity, apres: () -> Unit) {
        val pm = act.packageManager
        val toutes = Accueil.applicationsInstallees(act).filter { it.activityInfo.packageName != act.packageName }
        val choisies = FocusMusique.applis(act).toMutableSet()
        // 1.93.06 (point 6a) : sur la télé, « Entrées de la télé (HDMI, TV en direct) » en tête de liste (SourcesTele).
        val paquets = (if (ReglagesTvActivity.estTele(act)) listOf(SourcesTele.JETON) else emptyList()) + toutes.map { it.activityInfo.packageName }
        val noms = ((if (ReglagesTvActivity.estTele(act)) listOf(act.getString(R.string.focus_entrees_tele)) else emptyList()) +
            toutes.map { it.loadLabel(pm).toString() }).toTypedArray()
        val coches = paquets.map { it in choisies }.toBooleanArray()
        Ui.dialogue(act).setTitle(act.getString(R.string.focus_applis_titre))
            .setMultiChoiceItems(noms, coches) { _, k, on -> val p = paquets[k]; if (on) choisies.add(p) else choisies.remove(p) }
            .setPositiveButton(act.getString(R.string.commun_lot2_garder)) { _, _ -> FocusMusique.poserApplis(act, choisies); apres() }
            .setNegativeButton(act.getString(R.string.commun_lot2_annuler), null).show()
    }

    /**
     * Le fond animé du lecteur, par le sélecteur partagé (ChoixAdaptatif, règle UX de l'auteur du 15-10) : le style (5, en
     * liste), l'intensité (3, en étiquettes si elles tiennent), la pochette et le son (oui / non). Tout s'applique
     * tout de suite ; [apres] à chaque changement.
     */
    fun choisirFond(act: Activity, apres: () -> Unit) {
        val th = Local.themeEffectif(act, Palette.APPLI)
        val taille = if (ReglagesTvActivity.estTele(act)) 18f else 14f
        fun intitule(id: Int) = act.getString(id, "").substringBeforeLast(':').trim()
        fun ouiNon(id: Int, valeur: Boolean, poser: (Boolean) -> Unit) = Ui.choix(act, intitule(id),
            listOf(ChoixAdaptatif.Option("oui", act.getString(R.string.mus_oui)), ChoixAdaptatif.Option("non", act.getString(R.string.mus_non))),
            if (valeur) "oui" else "non", th, taille) { poser(it == "oui"); apres() }
        val style = Ui.choix(act, act.getString(R.string.fond_titre), FondLecteur.Style.values().map { ChoixAdaptatif.Option(it.cle, FondLecteur.nomStyle(act, it)) },
            FondLecteur.style(act).cle, th, taille) { k -> FondLecteur.Style.values().firstOrNull { it.cle == k }?.let { FondLecteur.poserStyle(act, it); apres() } }
            .apply { tag = "fond_style" }
        val intensite = Ui.choix(act, intitule(R.string.fond_intensite), FondLecteur.Intensite.values().map { ChoixAdaptatif.Option(it.cle, FondLecteur.nomIntensite(act, it)) },
            FondLecteur.intensite(act).cle, th, taille) { k -> FondLecteur.Intensite.values().firstOrNull { it.cle == k }?.let { FondLecteur.poserIntensite(act, it); apres() } }
            .apply { tag = "fond_intensite" }
        val blocs = mutableListOf<Pair<String, android.view.View>>(
            act.getString(R.string.fond_titre) to style,
            intitule(R.string.fond_intensite) to intensite,
            intitule(R.string.fond_pochette_valeur) to ouiNon(R.string.fond_pochette_valeur, FondLecteur.pochette(act)) { FondLecteur.poserPochette(act, it) }.apply { tag = "fond_pochette" },
            intitule(R.string.fond_son_valeur) to ouiNon(R.string.fond_son_valeur, FondLecteur.son(act)) { FondLecteur.poserSon(act, it) }.apply { tag = "fond_son" })
        if (!Anim.actif(act)) blocs.add("" to Ui.texte(act, act.getString(R.string.fond_reduit), 13f, Ui.TEXTE_2))
        ChoixAdaptatif.fenetre(act, act.getString(R.string.fond_titre), blocs)
    }
}
