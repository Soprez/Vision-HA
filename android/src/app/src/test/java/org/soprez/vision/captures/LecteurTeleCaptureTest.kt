// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.PartyActivity
import org.soprez.vision.R
import org.soprez.vision.Ui
import org.soprez.vision.ZoneSure

/**
 * 17-10 (l'auteur, lot 3 : musique sur la télé), sans émulateur, Home Assistant simulé ([FauxHa]) :
 * - point 9 : ouvrir la musique mène DIRECTEMENT au lecteur, même sans Party sur cette télé ; plus de « Bibliothèque »
 *   ni de « Rechercher dans tout le catalogue » ;
 * - point 23 : la partie gauche est centrée — pochette, titre et artiste, barre sur toute la largeur, commandes ;
 * - point 18 : « ⚙ Réglages » à droite d'« Ajouter un titre », sur la même ligne ;
 * - point 14 : la confirmation de lancement est une petite pastille du thème, dans la zone sûre, sans l'œil.
 * Télé 720p et 1080p, polices 100 et 130 %, « Ajuster l'écran » à 0 et 5 % (1.93.06 : 0 = au ras des bords, le réglage de
 * l'auteur). Rien ne sort de la zone : le bord réglé plus la marge de confort (ZoneSure.zone).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LecteurTeleCaptureTest(private val taille: Recette.Taille, private val police: Float, private val zone: Int) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-zone{2}")
        fun parametres(): List<Array<Any>> = Recette.combinaisons(Recette.TAILLES.filter { it.tele }).flatMap { c ->
            listOf(0, 5).map { z -> arrayOf(c[0], c[1], z) } }
    }

    /** Le mode soirée (point 8) : ouvert (QR code) ou fermé. */
    private var invitesOuverts = true

    private fun etat(joue: Boolean): JSONObject = if (!joue) JSONObject().put("ma", true).put("ici", false).put("actif", false)
    else JSONObject().put("ma", true).put("ici", true).put("actif", true).put("lecteur", "media_player.salon").put("lecteur_nom", "Salon")
        .put("piloter", true).put("qr", if (invitesOuverts) "https://exemple.invalid/invites?c=1" else "").put("invites_ouverts", invitesOuverts)
        .put("maintenant", JSONObject().put("etat", "playing").put("titre", "Groovy Beat").put("artiste", "The Funky Band")
            .put("album", "Night Moves").put("volume", 0.4).put("duree", 214.0).put("position", 61.0))
        .put("file", JSONArray((1..6).map { JSONObject().put("titre", "Titre suivant $it").put("artiste", "Artiste $it").put("id", "q$it") }))

    private fun ouvrir(ha: FauxHa, joue: Boolean): Activity {
        Recette.poser(taille, police)
        val ctx = RuntimeEnvironment.getApplication()
        ZoneSure.poser(ctx, zone, zone)
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
        ha.reponses["/api/pc_parental/party/etat"] = { etat(joue) }
        ha.reponses["/api/pc_parental/party/musique"] = { JSONObject().put("items", JSONArray(listOf("Disney classique", "Groovy", "Soirée calme").map {
            JSONObject().put("uri", "library://playlist/${it.hashCode()}").put("nom", it).put("type", "playlist").put("source", "Spotify") })) }
        // Une télé n'est jamais en mode tactile : le focus de départ doit s'y poser.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(PartyActivity::class.java).setup().visible().get()
        // L'état et les playlists arrivent d'un fil : on attend que l'écran les montre.
        val fin = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < fin) {
            ShadowLooper.idleMainLooper(); Thread.sleep(40)
            val d = a.window.decorView
            if (d.findViewWithTag<View>("lecteur_gauche") != null && (!joue || texteVisible(d, "Groovy Beat")) && texteVisible(d, "Soirée calme").or(joue)) break
        }
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        return a
    }

    private fun texteVisible(v: View, t: String): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is TextView && v.text.toString().contains(t)) return true
        return v is ViewGroup && (0 until v.childCount).any { texteVisible(v.getChildAt(it), t) }
    }

    private fun boite(v: View): Rect { val p = IntArray(2).also { v.getLocationOnScreen(it) }; return Rect(p[0], p[1], p[0] + v.width, p[1] + v.height) }

    /** Rien d'utile hors de la zone sûre (textes, images, commandes) ; les fonds plein écran n'y sont pas soumis. */
    private fun horsZone(v: View, z: Rect, ecran: Rect, sortie: MutableList<String>) {
        if (v.visibility != View.VISIBLE || v.alpha == 0f || v.width <= 0 || v.height <= 0) return
        var r = boite(v)
        for (sv in generateSequence(v.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>()) {
            if (!r.intersect(boite(sv))) return
        }
        val plein = v.width >= ecran.width() * 0.9f && v.height >= ecran.height() * 0.9f
        val contenu = v is TextView || v is android.widget.ImageView || (v !is ViewGroup && (v.isFocusable || v.isClickable)) ||
            (v is ViewGroup && v.isClickable && v !is android.widget.ScrollView)
        if (contenu && !plein && !(r.left >= z.left - 2 && r.top >= z.top - 2 && r.right <= z.right + 2 && r.bottom <= z.bottom + 2)) {
            sortie += "${v.javaClass.simpleName}${(v.tag as? String)?.let { "[$it]" } ?: ""}${(v as? TextView)?.text?.toString()?.take(30)?.let { " « $it »" } ?: ""} sort de la zone sûre (${r.toShortString()}, zone ${z.toShortString()})"
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) horsZone(v.getChildAt(i), z, ecran, sortie)
    }

    private fun zoneSure(a: Activity): Rect {
        val d = a.window.decorView
        val z = ZoneSure.zone(a)
        return Rect(z.left, z.top, d.width - z.right, d.height - z.bottom)
    }

    private fun nom(cas: String) = "lecteur-$cas-zone$zone"

    @Test fun ouvert_sans_party_ici_le_lecteur_directement() {
        FauxHa().use { ha ->
            val a = ouvrir(ha, joue = false)
            val d = a.window.decorView
            val defauts = ArrayList(Recette.capturer(d, nom("vide"), taille, police))
            if (d.findViewWithTag<View>("lecteur_gauche") == null) defauts += "pas de lecteur : l'écran guide s'affiche encore"
            for (t in listOf(a.getString(R.string.musrech_onglet_bibliotheque), a.getString(R.string.partyecran_rechercher_catalogue)))
                if (texteVisible(d, t)) defauts += "« $t » est encore à l'écran (point 9)"
            if (!texteVisible(d, a.getString(R.string.partyecran_rien_ne_joue))) defauts += "le lecteur ne dit pas « rien ne joue »"
            if (!texteVisible(d, "Soirée calme")) defauts += "les playlists ne sont pas à côté du lecteur"
            if (d.findFocus()?.tag != "lecteur_ajouter") defauts += "le focus n'est pas sur « Ajouter un titre » (${d.findFocus()?.tag})"
            memeLigne(d, defauts)
            horsZone(d, zoneSure(a), Rect(0, 0, d.width, d.height), defauts)
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }

    @Test fun lecteur_centre_et_reglages_a_droite() {
        FauxHa().use { ha ->
            val a = ouvrir(ha, joue = true)
            val d = a.window.decorView
            val defauts = ArrayList(Recette.capturer(d, nom("joue"), taille, police))
            val gauche = d.findViewWithTag<View>("lecteur_gauche")
            if (gauche == null) defauts += "pas de lecteur"
            else {
                val g = boite(gauche); val tol = Ui.dp(a, 3f)
                fun centre(tag: String) {
                    val v = d.findViewWithTag<View>(tag) ?: run { defauts += "$tag absent"; return }
                    val r = boite(v)
                    val contenu = if (v is TextView && v.layout != null) {
                        // Le texte, pas sa boîte (qui prend toute la largeur) : centré si ses deux marges sont égales.
                        val l = v.layout; val gx = r.left + v.totalPaddingLeft + l.getLineLeft(0).toInt(); val dx = r.left + v.totalPaddingLeft + l.getLineRight(0).toInt()
                        Rect(gx, r.top, dx, r.bottom)
                    } else r
                    if (Math.abs(contenu.centerX() - g.centerX()) > tol) defauts += "$tag n'est pas centré (${contenu.centerX()} pour ${g.centerX()})"
                }
                centre("lecteur_pochette"); centre("lecteur_titre")
                val cmd = d.findViewWithTag<ViewGroup>("lecteur_commandes")
                if (cmd != null && cmd.childCount > 0) {
                    val premier = boite(cmd.getChildAt(0)); val dernier = boite(cmd.getChildAt(cmd.childCount - 1))
                    val milieu = (premier.left + dernier.right) / 2
                    if (Math.abs(milieu - g.centerX()) > tol * 3) defauts += "les commandes ne sont pas centrées ($milieu pour ${g.centerX()})"
                } else defauts += "commandes absentes"
                // 1.93.06 : l'enceinte où joue la soirée, sous le titre.
                val enceinte = d.findViewWithTag<TextView>("lecteur_enceinte")
                if (enceinte == null || !enceinte.text.contains("Salon")) defauts += "l'enceinte de la soirée n'est pas dite (${enceinte?.text})"
                val barre = d.findViewWithTag<View>("lecteur_barre")
                if (barre == null || barre.width < gauche.width - tol) defauts += "la barre ne prend pas toute la largeur (${barre?.width} pour ${gauche.width})"
            }
            memeLigne(d, defauts)
            horsZone(d, zoneSure(a), Rect(0, 0, d.width, d.height), defauts)
            // Point 14 : la confirmation de lancement — une pastille du thème, basse, dans la zone sûre, sans l'œil.
            Ui.confirmation(a, a.getString(R.string.party_lancement_playlist, "Groovy"))
            ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            val c = d.findViewWithTag<View>(Ui.TAG_CONFIRMATION)
            if (c == null) defauts += "pas de confirmation"
            else {
                if (c.height > Ui.dp(a, 56f)) defauts += "confirmation trop haute (${c.height} px)"
                horsZone(c, zoneSure(a), Rect(0, 0, d.width, d.height), defauts)
                Recette.capturer(d, nom("confirmation"), taille, police)
            }
            if (oeil(d)) defauts += "l'œil est encore là"
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }

    /**
     * 1.93.06 (retour d'essai) : le QR du lecteur suit la taille réglée (Petit par défaut, la moitié d'avant ; Moyen ; Grand =
     * l'ancienne taille) : relu, au moins 2 px par module, rien hors de la zone. Et la vue « File d'attente » : la liste
     * finit en fondu au-dessus de l'aide, qui ne colle plus au bord.
     */
    @Test fun qr_trois_tailles_et_file_d_attente() {
        val ctx = RuntimeEnvironment.getApplication()
        val cotes = ArrayList<Int>()
        val defauts = ArrayList<String>()
        for (t in org.soprez.vision.QrInvites.TAILLES) FauxHa().use { ha ->
            org.soprez.vision.OptionsVeille.poserQrTaille(ctx, t)
            val a = ouvrir(ha, joue = true)
            val d = a.window.decorView
            defauts += Recette.capturer(d, nom("qr-$t"), taille, police).map { "$t : $it" }
            horsZone(d, zoneSure(a), Rect(0, 0, d.width, d.height), defauts)
            val q = d.findViewWithTag<android.widget.ImageView>("lecteur_qr")
            if (q == null) { defauts += "$t : pas de QR"; return@use }
            cotes += q.width
            val bmp = (q.drawable as android.graphics.drawable.BitmapDrawable).bitmap
            if (q.width < org.soprez.vision.QrInvites.MODULE_MIN_PX * bmp.width)
                defauts += "$t : ${q.width} px pour ${bmp.width} modules (au moins 2 px par module)"
            // La file : en fondu, et l'aide sous elle, dans la zone.
            val file = d.findViewWithTag<android.widget.ScrollView>("lecteur_file")
            if (file == null || !file.isVerticalFadingEdgeEnabled) defauts += "$t : la file ne finit pas en fondu"
            val aide = generateSequence(listOf<View>(d)) { n -> n.flatMap { (it as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.ifEmpty { null } }
                .flatten().firstOrNull { it is TextView && it.contentDescription == a.getString(R.string.mus_aide) }
            if (aide == null) defauts += "$t : pas d'aide sous la file"
            else if (file != null && boite(aide).top < boite(file).bottom - 1) defauts += "$t : l'aide recouvre la file"
        }
        org.soprez.vision.OptionsVeille.poserQrTaille(ctx, org.soprez.vision.QrInvites.DEFAUT)
        // Petit ≤ Moyen ≤ Grand ; Petit et Moyen se rejoignent au minimum de 2 px par module en 720p.
        if (cotes.size == 3 && !(cotes[0] <= cotes[1] && cotes[1] <= cotes[2] && cotes[0] < cotes[2])) defauts += "tailles du QR dans le désordre : $cotes"
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    private fun oeil(v: View): Boolean = v.javaClass.simpleName == "OeilVue" && v.visibility == View.VISIBLE ||
        v is ViewGroup && (0 until v.childCount).any { oeil(v.getChildAt(it)) }

    /** Point 18 : « ⚙ Réglages » sur la ligne d'« Ajouter un titre », à sa droite. */
    private fun memeLigne(d: View, defauts: MutableList<String>) {
        val aj = d.findViewWithTag<View>("lecteur_ajouter"); val rg = d.findViewWithTag<View>("lecteur_reglages")
        if (aj == null || rg == null) { defauts += "Ajouter ou Réglages absent"; return }
        val a = boite(aj); val r = boite(rg)
        if (Math.abs(a.centerY() - r.centerY()) > 4 || r.left < a.right) defauts += "Réglages n'est pas à droite d'Ajouter (${a.toShortString()} / ${r.toShortString()})"
        if (rg is TextView && rg.layout != null && rg.layout.getEllipsisCount(0) > 0) defauts += "Réglages est coupé"
    }

    /** Point 18b : OK sur un titre de la file → « Vider la file » tout en bas → confirmation → la commande part. */
    @Test fun vider_la_file_avec_confirmation() {
        if (zone != 5 || police != 1.0f) return  // le chemin ne dépend ni de la zone ni de la police
        FauxHa().use { ha ->
            val a = ouvrir(ha, joue = true)
            val ligne = a.window.decorView.findViewWithTag<View>("file:q2") ?: error("ligne de la file absente")
            ligne.performClick()
            ShadowLooper.idleMainLooper()
            val menu = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
            val l = menu.listView
            val n = l.adapter.count
            assertEquals(a.getString(R.string.partyecran_vider_file), l.adapter.getItem(n - 1).toString())
            assertEquals(a.getString(R.string.partyecran_retirer_file), l.adapter.getItem(n - 2).toString())
            l.performItemClick(l.adapter.getView(n - 1, null, l), n - 1, l.adapter.getItemId(n - 1))
            ShadowLooper.idleMainLooper()
            val conf = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
            Recette.dessiner(conf.window!!.decorView, java.io.File(Recette.dossier, "recette-calc-lecteur-vider-confirmation-${taille.nom}.png"))
            val message = conf.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
            assertEquals(a.getString(R.string.partyecran_vider_texte, 6), message)
            // Rien n'est parti avant la confirmation.
            assertEquals(0, ha.recus.count { it.second.optString("action") == "vider" })
            conf.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            val fin = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < fin && ha.recus.none { it.second.optString("action") == "vider" }) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
            assertEquals(1, ha.recus.count { it.first.endsWith("/party/commande") && it.second.optString("action") == "vider" })
        }
    }

    /** Point 8 : fermer le mode soirée depuis le lecteur (le QR code disparaît aussitôt), puis le rouvrir. */
    @Test fun mode_soiree_depuis_le_lecteur() {
        FauxHa().use { ha ->
            val a = ouvrir(ha, joue = true)
            val d = a.window.decorView
            val fermer = d.findViewWithTag<View>("soiree_fermer") ?: error("pas de bouton « Fermer le mode soirée » près du QR code")
            // Le serveur suit la commande : l'état relu dit « fermé ».
            ha.reponses["/api/pc_parental/party/commande"] = { invitesOuverts = false; JSONObject().put("ok", true).put("retour", "") }
            fermer.performClick()
            ShadowLooper.idleMainLooper()
            val defauts = ArrayList<String>()
            if (d.findViewWithTag<View>("soiree_ouvrir") == null) defauts += "pas de bouton « Ouvrir aux invités » une fois fermé"
            if (texteVisible(d, a.getString(R.string.partyecran_proposer_titre))) defauts += "le QR code est encore là"
            val fin = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < fin && ha.recus.none { it.second.optString("action") == "invites_ouverts" }) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
            val c = ha.recus.firstOrNull { it.second.optString("action") == "invites_ouverts" }
            if (c == null || c.second.optBoolean("actif", true)) defauts += "la commande « invites_ouverts » (actif = non) n'est pas partie"
            ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
            defauts.addAll(Recette.capturer(d, nom("soiree-fermee"), taille, police))
            horsZone(d, zoneSure(a), android.graphics.Rect(0, 0, d.width, d.height), defauts)
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }
}
