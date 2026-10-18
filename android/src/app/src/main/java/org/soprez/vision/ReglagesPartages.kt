// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 15-10 (l'auteur : « Réglages de l'appareil » est presque obsolète) : les réglages de cette page rejoignent les menus de
 * l'appli — Couleurs (CouleursActivity), Disposition (menu de l'accueil) et Réglages (SetupActivity, espace Réglages).
 * Leurs boîtes de choix vivent ici, une seule fois, pour ces menus et pour les pages de la télé (ReglagesTvActivity).
 * [apres] : rappelé après un changement (l'écran se repeint).
 */
object ReglagesPartages {

    /** Un choix dans une liste (la valeur en cours cochée ; Retour ou « Annuler » ne change rien). */
    fun choisirParmi(a: Activity, titre: String, libelles: List<String>, courant: Int, choisi: (Int) -> Unit) {
        Ui.dialogue(a).setTitle(titre)
            .setSingleChoiceItems(libelles.toTypedArray(), courant) { d, k -> d.dismiss(); choisi(k) }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
    }

    // ------------------------------------------------------------------ apparence (menu Couleurs)

    fun nomModeSombre(ctx: Context): String = Themes.MODES_SOMBRE.first { it.first == Local.modeSombre(ctx) }.second

    fun choisirModeSombre(a: Activity, apres: () -> Unit) {
        val l = Themes.MODES_SOMBRE
        choisirParmi(a, a.getString(R.string.reglagestv_mode_sombre), l.map { it.second }, l.indexOfFirst { it.first == Local.modeSombre(a) }) { k ->
            Local.poserModeSombre(a, l[k].first); apres()
        }
    }

    /** « Sombre et sans son de 23:00 à 07:00 » : la plage « HH:mm-HH:mm », heures à la façon de l'appareil (12/24 h). */
    fun libelleNuit(ctx: Context, nuit: String): String = try {
        fun h(x: String): String {
            val p = x.split(":")
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, p[0].trim().toInt()); set(java.util.Calendar.MINUTE, p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
            }
            return Tr.heure(c.timeInMillis)
        }
        val (debut, fin) = nuit.split("-").let { it[0] to it[1] }
        ctx.getString(R.string.reglagestv_nuit_plage, h(debut), h(fin))
    } catch (_: Exception) { ctx.getString(R.string.reglagestv_nuit_plage, nuit.substringBefore("-"), nuit.substringAfter("-")) }

    fun nomNuit(ctx: Context): String = Local.nuit(ctx).let { if (it.isEmpty()) ctx.getString(R.string.reglagestv_jamais) else libelleNuit(ctx, it) }

    fun choisirNuit(a: Activity, apres: () -> Unit) {
        val l = Local.PLAGES_NUIT
        choisirParmi(a, a.getString(R.string.reglagestv_mode_nuit), l.map { if (it.isEmpty()) a.getString(R.string.reglagestv_jamais) else libelleNuit(a, it) },
            l.indexOf(Local.nuit(a))) { k -> Local.poserNuit(a, l[k]); apres() }
    }

    // L'arrière-plan de l'accueil (1.91.x) : fond, voile de lisibilité, fond modifiable par les Demandeurs.

    const val REQ_PHOTO_FOND = 4711

    /** Le Décideur (code), ou le Demandeur si le Décideur le lui a permis sur cet appareil. */
    private fun fondPermis(a: Activity, action: () -> Unit) {
        if (FondAccueil.permisDemandeur(a)) action() else Garde.exiger(a, a.getString(R.string.fondacc_raison)) { action() }
    }

    fun nomFond(ctx: Context): String = ctx.getString(FondAccueil.sorte(ctx).nom).let { n ->
        if (FondAccueil.sorte(ctx) == FondAccueil.Sorte.ANIME) n + " · " + FondLecteur.nomStyle(ctx, FondAccueil.styleAnime(ctx)) else n
    }

    /** Le fond de l'accueil ; une photo : la galerie s'ouvre, le résultat revient à [recevoirPhoto] (onActivityResult de [a]). */
    fun choisirFondAccueil(a: Activity, apres: () -> Unit) = fondPermis(a) {
        val tele = ReglagesTvActivity.estTele(a)
        // La photo vient de la galerie de l'appareil : pas sur une télé (pas de galerie).
        val sortes = FondAccueil.Sorte.values().filter { !(tele && it == FondAccueil.Sorte.PHOTO) }
        Ui.dialogue(a).setTitle(a.getString(R.string.fondacc_titre))
            .setSingleChoiceItems(sortes.map { a.getString(it.nom) }.toTypedArray(), sortes.indexOf(FondAccueil.sorte(a))) { d, k ->
                d.dismiss()
                when (val s = sortes[k]) {
                    FondAccueil.Sorte.UNI, FondAccueil.Sorte.DEGRADE -> choisirTeinteFond(a, s, apres)
                    FondAccueil.Sorte.PHOTO -> try {
                        @Suppress("DEPRECATION")
                        a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_PHOTO_FOND)
                    } catch (_: Exception) { Ui.toast(a, a.getString(R.string.fondacc_photo_echec), genre = OeilModele.Genre.ERREUR) }
                    FondAccueil.Sorte.ANIME -> choisirStyleFond(a, apres)
                    else -> { FondAccueil.poser(a, s); apres() }
                }
            }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun choisirTeinteFond(a: Activity, s: FondAccueil.Sorte, apres: () -> Unit) {
        val th = Local.themeEffectif(a, Palette.APPLI)
        val n = FondAccueil.teintes(th).size
        val noms = (0 until n).map { if (it == 0) a.getString(R.string.fondacc_teinte_fond) else a.getString(R.string.fondacc_teinte_n, it) }
            .let { if (s == FondAccueil.Sorte.DEGRADE) it.drop(1) else it }
        Ui.dialogue(a).setTitle(a.getString(s.nom))
            .setItems(noms.toTypedArray()) { _, k -> FondAccueil.poser(a, s, if (s == FondAccueil.Sorte.DEGRADE) k + 1 else k); apres() }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun choisirStyleFond(a: Activity, apres: () -> Unit) {
        val styles = FondLecteur.Style.values().filter { it != FondLecteur.Style.AUCUN }
        Ui.dialogue(a).setTitle(a.getString(R.string.fondacc_style))
            .setSingleChoiceItems(styles.map { FondLecteur.nomStyle(a, it) }.toTypedArray(), styles.indexOf(FondAccueil.styleAnime(a))) { d, k ->
                d.dismiss(); FondAccueil.poserStyleAnime(a, styles[k]); apres()
            }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
    }

    /** La photo choisie dans la galerie (à appeler depuis onActivityResult) ; vrai si le résultat était le sien. */
    fun recevoirPhoto(a: Activity, requestCode: Int, resultCode: Int, data: Intent?, apres: () -> Unit): Boolean {
        if (requestCode != REQ_PHOTO_FOND) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        Thread {
            val ok = try {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                a.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                val cote = maxOf(a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels)
                var echantillon = 1
                while (maxOf(opts.outWidth, opts.outHeight) / (echantillon * 2) >= cote) echantillon *= 2
                val b = a.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = echantillon }) }
                b != null && FondAccueil.garderPhoto(a, b, cote)
            } catch (_: Exception) { false }
            a.runOnUiThread {
                if (ok) { FondAccueil.poser(a, FondAccueil.Sorte.PHOTO); apres() }
                else Ui.toast(a, a.getString(R.string.fondacc_photo_echec), genre = OeilModele.Genre.ERREUR)
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    fun nomVoileFond(ctx: Context): String = ctx.getString(when {
        FondAccueil.renfort(ctx) >= 0.29f -> R.string.fondacc_voile_max
        FondAccueil.renfort(ctx) >= 0.14f -> R.string.fondacc_voile_plus
        else -> R.string.fondacc_voile_auto
    })

    fun choisirVoileFond(a: Activity, apres: () -> Unit) = Garde.exiger(a, a.getString(R.string.fondacc_raison)) {
        val valeurs = listOf(0f, 0.15f, 0.30f)
        val noms = listOf(R.string.fondacc_voile_auto, R.string.fondacc_voile_plus, R.string.fondacc_voile_max).map { a.getString(it) }
        Ui.dialogue(a).setTitle(a.getString(R.string.fondacc_voile))
            .setSingleChoiceItems(noms.toTypedArray(), valeurs.indexOfFirst { Math.abs(it - FondAccueil.renfort(a)) < 0.01f }.coerceAtLeast(0)) { d, k ->
                d.dismiss(); FondAccueil.poserRenfort(a, valeurs[k]); apres()
            }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
    }

    fun titrePermisDemandeur(ctx: Context): String = ctx.getString(R.string.fondacc_demandeur, Vocabulaire.demandeurs(ctx))
    fun nomPermisDemandeur(ctx: Context): String = ctx.getString(if (FondAccueil.permisDemandeur(ctx)) R.string.horloge_oui else R.string.horloge_non)

    fun basculerPermisDemandeur(a: Activity, apres: () -> Unit) = Garde.exiger(a, a.getString(R.string.fondacc_raison)) {
        FondAccueil.poserPermisDemandeur(a, !FondAccueil.permisDemandeur(a)); apres()
    }

    // ------------------------------------------------------------------ menu Réglages

    fun choisirSonMessages(a: Activity, apres: () -> Unit) {
        val s = MessagesV2.SONS
        Ui.dialogue(a).setTitle(a.getString(R.string.reglagestv_son_des_messages))
            .setSingleChoiceItems(s.map { MessagesV2.nomSon(it) }.toTypedArray(), s.indexOf(MessagesV2.sonAppareil(a))) { d, k -> MessagesV2.noterSon(a, s[k]); d.dismiss(); apres() }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Le mode de cet appareil (affiché seulement) : « Gestion · Décideurs », « Demandeurs », ou à venir. */
    fun nomModeAppareil(ctx: Context): String = when ((ctx.getSharedPreferences("lanceur", Context.MODE_PRIVATE).getString("profil", "") ?: "").ifEmpty { if (Etat.parent) "parent" else "" }) {
        "parent" -> ctx.getString(R.string.lanceur_gestion) + " · " + Vocabulaire.decideurs(ctx)
        "enfant" -> Vocabulaire.demandeurs(ctx)
        else -> ctx.getString(R.string.modeappareil_affichage)
    }

    fun nomOeilAccueil(ctx: Context): String = ctx.getString(if (OeilAccueil.actif(ctx)) R.string.oeilaccueil_active else R.string.oeilaccueil_desactive)

    fun basculerOeilAccueil(a: Activity, apres: () -> Unit) {
        OeilAccueil.activer(a, !OeilAccueil.actif(a)); apres()
    }

    fun nomBulle(ctx: Context): String = if (!Bulle.activee(ctx)) ctx.getString(R.string.reglagestv_rangee)
        else if (Bulle.permise(ctx)) ctx.getString(R.string.reglagestv_active) else ctx.getString(R.string.reglagestv_permission)

    fun basculerBulle(a: Activity, apres: () -> Unit) {
        if (!Bulle.permise(a)) { try { a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.packageName))) } catch (_: Exception) {} }
        else { Bulle.poser(a, !Bulle.activee(a)); apres() }
    }

    /** Les applications montrées sur l'accueil (cochées) ; [grille] : une appli remise est posée dans la grille. */
    fun choisirApplis(a: Activity, grille: Boolean, apres: () -> Unit) {
        val pm = a.packageManager
        val toutes = Accueil.applicationsInstallees(a)
        val masquees = Accueil.masquees(a).toMutableSet()
        val noms = toutes.map { it.loadLabel(pm).toString() }.toTypedArray()
        val coches = toutes.map { it.activityInfo.packageName !in masquees }.toBooleanArray()
        Ui.dialogue(a).setTitle(a.getString(R.string.reglagestv_applis_accueil))
            .setMultiChoiceItems(noms, coches) { _, k, on -> val p = toutes[k].activityInfo.packageName; if (on) masquees.remove(p) else masquees.add(p) }
            .setPositiveButton(a.getString(R.string.commun_lot2_garder)) { _, _ ->
                toutes.forEach { ri ->
                    val p = ri.activityInfo.packageName; val cachee = p in masquees
                    if (cachee != (p in Accueil.masquees(a))) { Accueil.cacher(a, p, cachee); if (grille && !cachee) Accueil.ajouterALaGrille(a, p) }
                }
                apres()
            }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
    }

    fun nomApplisVisibles(ctx: Context): String {
        val toutes = Accueil.applicationsInstallees(ctx).size
        return ctx.getString(R.string.reglagestv_sur, toutes - Accueil.masquees(ctx).size, toutes)
    }

    // ------------------------------------------------------------------ menu Disposition (téléphone, tablette)

    /**
     * Le menu « Disposition » de l'accueil : la disposition (Grille, Pages, Liste alphabétique) et, pour la liste, le côté
     * de l'index et les favoris en haut ; la zone du haut (ses widgets, sa place) ; les applications visibles. Chaque ligne
     * dit sa valeur ; après un choix, le menu revient (comme les réglages de l'horloge).
     */
    fun menuDisposition(a: Activity, apres: () -> Unit) {
        val lignes = ArrayList<Pair<String, () -> Unit>>()
        val rouvrir = { apres(); menuDisposition(a, apres) }
        lignes += (a.getString(R.string.reglagestv_disposition) + " : " + DispositionAccueil.nomMode(a, Accueil.mode(a))) to {
            DispositionAccueil.choisirDispositionTelephone(a) { rouvrir() }
        }
        if (Accueil.mode(a) == Accueil.MODE_ALPHA) {
            val gauche = DispositionAccueil.coteIndex(a) == DispositionAccueil.INDEX_GAUCHE
            lignes += (a.getString(R.string.lanceur_index_cote) + " : " + a.getString(if (gauche) R.string.lanceur_index_gauche else R.string.lanceur_index_droite)) to {
                DispositionAccueil.poserCoteIndex(a, if (gauche) DispositionAccueil.INDEX_DROITE else DispositionAccueil.INDEX_GAUCHE); rouvrir()
            }
            val fav = DispositionAccueil.favorisEnHaut(a)
            lignes += (a.getString(R.string.lanceur_favoris_haut) + " : " + a.getString(if (fav) R.string.horloge_oui else R.string.horloge_non)) to {
                DispositionAccueil.poserFavorisEnHaut(a, !fav); rouvrir()
            }
        }
        lignes += (a.getString(R.string.zonehaut_titre) + " : " + ZoneHaut.resume(a)) to { ZoneHaut.personnaliser(a) { rouvrir() } }
        val dessous = DispositionAccueil.positionZone(a) == DispositionAccueil.ZONE_DESSOUS
        lignes += (a.getString(R.string.lanceur_zone_position) + " : " + a.getString(if (dessous) R.string.lanceur_zone_dessous else R.string.lanceur_zone_dessus)) to {
            DispositionAccueil.poserPositionZone(a, if (dessous) DispositionAccueil.ZONE_DESSUS else DispositionAccueil.ZONE_DESSOUS); rouvrir()
        }
        lignes += (a.getString(R.string.reglagestv_applications_visibles) + " : " + nomApplisVisibles(a)) to { choisirApplis(a, grille = true) { rouvrir() } }
        Ui.dialogue(a).setTitle(a.getString(R.string.reglagestv_disposition))
            .setItems(lignes.map { it.first }.toTypedArray()) { _, k -> lignes[k].second() }
            .setNegativeButton(a.getString(R.string.zonehaut_enregistrer), null).show()
    }
}
