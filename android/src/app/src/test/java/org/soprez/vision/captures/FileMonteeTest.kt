// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.Party
import org.soprez.vision.PartyActivity
import org.soprez.vision.R
import org.soprez.vision.ZoneSure
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 1.93.06 (point 2, l'auteur, après la vidéo de la TCL) : au titre suivant, « En cours » reste à sa place en haut de la file et
 * la liste y monte d'une ligne, en douceur. Aucune autre animation, aucun retour en arrière. Suivi image par image (16 ms)
 * du défilement de la file, télé 1080p, Home Assistant simulé ([FauxHa]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FileMonteeTest {
    private val tele = Recette.TAILLES.first { it.nom == "tv-1080p" }
    @Volatile private var enCours = 3
    /** Comme le HA de la maison (2.5.11.1, TCL le 18/10) : « toujours », « un sur deux » (un état avec, le suivant sans) ou « jamais ». */
    @Volatile private var passes = "toujours"
    /** Chaque image prend 150 ms, comme sur la TCL (l'horloge avance d'autant à chaque position posée). */
    @Volatile private var lente = false
    private val etats = java.util.concurrent.atomic.AtomicInteger()

    private fun titre(k: Int) = JSONObject().put("titre", "Titre $k").put("artiste", "Artiste $k").put("id", "q$k")

    private fun etat(): JSONObject = JSONObject().put("ma", true).put("ici", true).put("actif", true)
        .put("lecteur", "media_player.salon").put("lecteur_nom", "Salon").put("piloter", true).put("relire", 2)
        .put("maintenant", JSONObject().put("etat", "playing").put("titre", "Titre $enCours").put("artiste", "Artiste $enCours")
            .put("volume", 0.4).put("duree", 214.0).put("position", 5.0))
        .put("file_passes", JSONArray(if (passes == "jamais" || (passes == "un sur deux" && etats.incrementAndGet() % 2 == 0)) emptyList()
            else (maxOf(0, enCours - 3) until enCours).map { titre(it) }))
        .put("file", JSONArray((enCours + 1..enCours + 6).map { titre(it) }))

    private fun ouvrir(ha: FauxHa): Activity {
        Recette.poser(tele, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        ZoneSure.poser(ctx, 0, 0)
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
        ha.reponses["/api/pc_parental/party/etat"] = { etat() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(PartyActivity::class.java).setup().visible().get()
        attendreTitre(a, enCours)
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
        return a
    }

    /** Attend que l'écran montre le titre [k] en cours (l'état arrive d'un fil), sans avancer l'horloge au-delà du relevé. */
    private fun attendreTitre(a: Activity, k: Int) {
        val fin = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < fin) {
            if ((a.window.decorView.findViewWithTag<View>("lecteur_titre") as? TextView)?.text?.toString() == "Titre $k") return
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS); Thread.sleep(30)
        }
        throw AssertionError("le titre $k n'est pas arrivé à l'écran")
    }

    private fun file(a: Activity) = a.window.decorView.findViewWithTag<ScrollView>("lecteur_file")

    private fun textes(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { textes(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** La ligne « En cours » de la file, et sa hauteur à l'écran. */
    private fun ligneEnCours(a: Activity, k: Int): View {
        val col = file(a).getChildAt(0) as ViewGroup
        val marque = a.getString(R.string.partyecran_en_cours_court)
        return (0 until col.childCount).map { col.getChildAt(it) }.first { l -> textes(l).let { "Titre $k" in it && it.any { t -> t.contains(marque) } } }
    }

    private fun hautEcran(v: View) = IntArray(2).also { v.getLocationOnScreen(it) }[1]

    /**
     * Le défilement de la NOUVELLE file (l'écran se rebâtit au changement de titre), à chaque position posée (première
     * image, puis chaque pas de la montée) : relevé par PartyActivity.suiviFile, l'horloge de Robolectric pouvant avancer
     * de plusieurs images d'un coup. Rend les paires (instant, position), jusqu'à 700 ms après la première.
     */
    private fun suivre(a: Activity, k: Int): List<Pair<Long, Int>> {
        val vus = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()
        PartyActivity.suiviFile = { y ->
            vus += android.os.SystemClock.uptimeMillis() to y
            if (lente) org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(150))
        }
        try {
            enCours = k
            val fin = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < fin && (vus.isEmpty() || android.os.SystemClock.uptimeMillis() - vus.first().first < 700)) {
                ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                if (vus.isEmpty()) Thread.sleep(4)  // le relevé arrive d'un fil
            }
        } finally { PartyActivity.suiviFile = null }
        attendreTitre(a, k)
        assertEquals("la file à l'écran n'est pas à la dernière position posée", vus.lastOrNull()?.second, file(a).scrollY)
        // Une position par instant (la dernière).
        return vus.groupBy { it.first }.map { (t, l) -> t to l.last().second }.sortedBy { it.first }
    }

    /** Rien d'autre ne bouge dans la file : ni déplacement, ni transparence animée, ni animation posée. */
    private fun rienDAutreNeBouge(a: Activity) {
        val col = file(a).getChildAt(0) as ViewGroup
        for (i in 0 until col.childCount) {
            val l = col.getChildAt(i)
            assertTrue("ligne $i animée", l.animation == null && l.translationX == 0f && l.translationY == 0f && l.scaleX == 1f && l.scaleY == 1f)
        }
    }

    @Test fun au_titre_suivant_la_liste_monte_d_une_ligne_en_douceur() = monteeDouce()

    /** TCL, 19:45 : un état sur deux arrive sans titres passés ; la liste sautait. */
    @Test fun meme_quand_ha_n_envoie_les_passes_qu_un_etat_sur_deux() { passes = "un sur deux"; monteeDouce() }

    /** TCL, 19:46 : le HA de la maison n'envoie aucun titre passé ; la liste était remplacée d'un coup. */
    @Test fun meme_quand_ha_n_envoie_jamais_les_passes() { passes = "jamais"; monteeDouce() }

    /** 2e vidéo de la TCL (18/10, 22:35) : chaque image y prend 100 à 300 ms ; réglée sur le temps seul, la montée
     *  tenait en 2 ou 3 images et la liste semblait sauter d'un coup. */
    @Test fun sur_une_tele_lente_chaque_pas_se_voit() { lente = true; monteeDouce() }

    private fun monteeDouce() {
        FauxHa().use { ha ->
            val a = ouvrir(ha)
            val place = hautEcran(ligneEnCours(a, 3))
            val pas = suivre(a, 4)
            val vus = pas.map { it.second }
            assertTrue("la nouvelle file n'a pas défilé : $pas", vus.isNotEmpty())
            for (i in 1 until vus.size) assertTrue("retour en arrière dans la montée : $pas", vus[i] >= vus[i - 1])
            val fin = vus.last()
            val entre = vus.filter { it in (vus.first() + 1) until fin }.distinct()
            assertTrue("la montée n'est pas progressive (saut d'un coup) : $pas", entre.size >= 3)
            // 1.93.06 (2e vidéo de la TCL) : image par image ; chaque pas se voit, même si l'horloge saute (télé lente).
            assertTrue("pas assez de pas visibles (${vus.distinct().size}) : $pas", vus.distinct().size >= org.soprez.vision.MonteeFile.PAS_MIN)
            // Une ligne exactement : de l'ancien « En cours » (devenu le dernier passé) au nouveau.
            val col = file(a).getChildAt(0) as ViewGroup
            val nouvelle = ligneEnCours(a, 4)
            val ligne = nouvelle.top - col.getChildAt(col.indexOfChild(nouvelle) - 1).top
            assertTrue("pas une ligne : de ${vus.first()} à $fin (ligne $ligne)", abs((fin - vus.first()) - ligne) <= 2)
            // « En cours » reste à sa place en haut de la file.
            assertTrue("« En cours » a bougé : $place → ${hautEcran(nouvelle)}", abs(hautEcran(nouvelle) - place) <= 2)
            rienDAutreNeBouge(a)
            // Les relevés suivants (même titre) ne bougent plus rien.
            ShadowLooper.idleMainLooper(2_500, TimeUnit.MILLISECONDS); Thread.sleep(200); ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            // (La position absolue peut changer si HA envoie plus ou moins de titres passés au-dessus : ils sont hors de vue.)
            assertTrue("« En cours » a bougé après la montée : $place → ${hautEcran(ligneEnCours(a, 4))}", abs(hautEcran(ligneEnCours(a, 4)) - place) <= 2)
        }
    }

    @Test fun un_precedent_se_pose_d_un_coup_sans_animation_ni_retour() {
        FauxHa().use { ha ->
            val a = ouvrir(ha)
            val pas = suivre(a, 2)
            val vus = pas.map { it.second }
            assertEquals("un précédent ne s'anime pas : $vus", 1, vus.distinct().size)
            assertTrue("« En cours » n'est pas en haut", abs(hautEcran(ligneEnCours(a, 2)) - hautEcran(file(a))) <= ZoneSure.zone(a).top + 60)
            rienDAutreNeBouge(a)
        }
    }
}
