// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.LanceurActivity

/**
 * « Mise à jour depuis la version précédente avec réglages réels » (panne de la 1.93.01 sur la télé du salon de l'auteur,
 * 17/10 : disposition « Vertical », rangées du haut et du bas vides → l'accueil ne montrait que le fond).
 *
 * La recette ne posait que des réglages neufs. Ici, l'accueil s'ouvre avec les préférences qu'un appareil garde d'une
 * version à l'autre : clés absentes, listes vides, zone activée sans widget, valeur d'une ancienne version ou inconnue —
 * pour chaque disposition (télé : Rangées, Vertical et ses trois colonnes ; téléphone et tablette : défilement, pages,
 * liste alphabétique), zones et rangées vides et pleines.
 *
 * Invariant : l'accueil n'est JAMAIS vide — des commandes (applis, « Ajouter », menu) visibles à l'écran, et en
 * « Vertical » la ligne et la colonne des applis sur plus de la moitié de la hauteur. Plus les vérifications de
 * Debordements, et une image par cas.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AccueilMiseAJourCaptureTest(private val cas: Cas, private val taille: Recette.Taille) {

    /** Un cas : son nom, et les préférences « lanceur » (et « local ») qu'une version précédente a laissées. */
    data class Cas(val nom: String, val tele: Boolean, val lanceur: Map<String, Any> = emptyMap(), val local: Map<String, Any> = emptyMap(),
                   /** Téléphone : combien d'applis l'utilisateur a posées sur sa grille (un vrai téléphone en a). */
                   val applisPosees: Int = 6) {
        override fun toString() = nom
    }

    companion object {
        private const val VIDE = "[]"
        private const val PLEINE = """["horloge_vision","meteo"]"""

        private fun vertical(colonne: String?, g: String?, d: String?, h: String?, b: String?, actives: Boolean?): Map<String, Any> {
            val m = LinkedHashMap<String, Any>()
            m["tele_disposition"] = "vertical"
            colonne?.let { m["tele_colonne"] = it }
            for ((z, v) in listOf("g" to g, "d" to d, "h" to h, "b" to b)) {
                if (v != null) m["tele_zone_$z"] = v
                if (actives != null && v != null) m["tele_zone_${z}_active"] = actives
            }
            return m
        }

        val CAS_TELE = listOf(
            Cas("tele-neuve", true),
            Cas("tele-rangees", true, mapOf("tele_disposition" to "rangees")),
            Cas("tele-disposition-inconnue", true, mapOf("tele_disposition" to "grille_ancienne")),
            // 1.93.0 → 1.93.01 : « Vertical » choisi avant que les rangées existent (aucune clé de rangée).
            Cas("tele-vertical-sans-cle-de-rangee", true, vertical("centre", PLEINE, PLEINE, null, null, true)),
            // La télé du salon de l'auteur (17/10) : rangées du haut et du bas vides.
            Cas("tele-vertical-rangees-vides-coupees", true, vertical("centre", PLEINE, PLEINE, VIDE, VIDE, false)),
            Cas("tele-vertical-rangees-vides-activees", true, vertical("centre", PLEINE, PLEINE, VIDE, VIDE, true)),
            Cas("tele-vertical-tout-vide", true, vertical("centre", VIDE, VIDE, VIDE, VIDE, true)),
            Cas("tele-vertical-tout-plein", true, vertical("centre", PLEINE, PLEINE, PLEINE, PLEINE, true)),
            Cas("tele-vertical-rangees-pleines-cotes-vides", true, vertical("centre", VIDE, VIDE, PLEINE, PLEINE, true)),
            Cas("tele-vertical-gauche-vide", true, vertical("gauche", VIDE, VIDE, VIDE, VIDE, true)),
            Cas("tele-vertical-droite-plein", true, vertical("droite", PLEINE, PLEINE, PLEINE, PLEINE, true)),
            Cas("tele-vertical-colonne-inconnue", true, vertical("milieu", PLEINE, PLEINE, VIDE, VIDE, true)),
            // Un widget d'une version précédente, inconnu aujourd'hui, dans une rangée activée.
            Cas("tele-vertical-widget-inconnu", true, vertical("centre", VIDE, VIDE, """["ancien_widget"]""", """["ancien_widget"]""", true)),
            // 1.93.04 → 1.93.05 (télé du salon) : « Vertical », un widget à gauche et un à droite, rangées vides et coupées.
            Cas("tele-1.93.04-salon", true, mapOf("tele_disposition" to "vertical", "tele_colonne" to "centre",
                "tele_zone_g" to """["horloge_vision"]""", "tele_zone_g_active" to true,
                "tele_zone_d" to """["meteo"]""", "tele_zone_d_active" to true,
                "tele_zone_h" to VIDE, "tele_zone_h_active" to false, "tele_zone_b" to VIDE, "tele_zone_b_active" to false)),
            // 1.93.05 → 1.93.06 (modes Utilisation et Modification) : des widgets à commandes (le cadre ne prend plus le
            // focus), dans la rangée des widgets et dans une zone « Vertical ».
            Cas("tele-1.93.05-rangees-widgets", true, mapOf("tele_disposition" to "rangees",
                "disposition" to """[{"cle":"widget:chauffage","col":0,"row":0,"w":2,"h":2},{"cle":"widget:temps","col":2,"row":0,"w":4,"h":1}]""")),
            Cas("tele-1.93.05-vertical-widgets", true, mapOf("tele_disposition" to "vertical", "tele_colonne" to "centre",
                "tele_zone_g" to """["widget:temps","horloge_vision"]""", "tele_zone_g_active" to true,
                "tele_zone_d" to """["meteo"]""", "tele_zone_d_active" to true,
                "tele_zone_h" to VIDE, "tele_zone_h_active" to false, "tele_zone_b" to VIDE, "tele_zone_b_active" to false)),
        )

        val CAS_TELEPHONE = listOf(
            Cas("telephone-neuf", false, applisPosees = 0),
            Cas("telephone-defilement", false, mapOf("mode" to "defilement")),
            Cas("telephone-pages", false, mapOf("mode" to "pages")),
            Cas("telephone-alpha", false, mapOf("mode" to "alpha")),
            Cas("telephone-alpha-gaucher-zone-dessous", false, mapOf("mode" to "alpha", "alpha_index" to "gauche", "zone_position" to "dessous")),
            Cas("telephone-mode-inconnu", false, mapOf("mode" to "grille_ancienne")),
            Cas("telephone-zone-haut-vide-dessous", false, mapOf("mode" to "defilement", "zone_position" to "dessous"),
                mapOf("zone_haut_widgets" to VIDE)),
        )

        /** 17-10 (points 10, 11, 21) : les mêmes télés avec une zone sûre de 9 %, comme la TCL du salon qui rogne. */
        val CAS_TELE_ZONE = CAS_TELE.filter { it.nom != "tele-neuve" }.map { c ->
            c.copy(nom = c.nom + "-zone9", local = c.local + mapOf("zone_sure_x" to 9, "zone_sure_y" to 9)) }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parametres(): List<Array<Any>> =
            (CAS_TELE + CAS_TELE_ZONE).flatMap { c -> Recette.TAILLES.filter { it.tele }.map { arrayOf<Any>(c, it) } } +
                CAS_TELEPHONE.flatMap { c -> listOf("360dp", "412dp", "360dp-paysage", "tablette-portrait")
                    .map { n -> arrayOf<Any>(c, Recette.TAILLES.first { it.nom == n }) } }

        /** Des applis installées, comme sur un vrai appareil (sans elles, l'accueil serait vide par nature). */
        val APPLIS = listOf("com.google.android.youtube" to "YouTube", "com.netflix.ninja" to "Netflix",
            "fr.francetv.pluzz" to "france.tv", "com.spotify.tv.android" to "Spotify", "org.videolan.vlc" to "VLC",
            "com.android.tv.settings" to "Paramètres", "com.disney.disneyplus" to "Disney+", "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "com.google.android.apps.youtube.music" to "YouTube Music", "fr.m6.m6replay" to "M6+", "fr.tf1.mytf1" to "TF1+",
            "com.plexapp.android" to "Plex", "com.deezer.android.tv" to "Deezer", "org.xbmc.kodi" to "Kodi")
    }

    private fun installer(ctx: Context) {
        val pm = shadowOf(ctx.packageManager)
        for ((pkg, nom) in APPLIS) {
            pm.installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = nom; enabled = true }
            })
            // Une icône, comme une vraie appli (sans elle, la case reste blanche sur la capture).
            pm.setApplicationIcon(pkg, android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                paint.color = 0xFF000000.toInt() or (pkg.hashCode() and 0x00FFFFFF); intrinsicWidth = 96; intrinsicHeight = 96 })
            val cn = ComponentName(pkg, "$pkg.Principale")
            pm.addActivityIfNotPresent(cn)
            pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(if (cas.tele) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            })
        }
    }

    private fun poserPreferences(ctx: Context) {
        fun ecrire(nom: String, m: Map<String, Any>) {
            val e = ctx.getSharedPreferences(nom, Context.MODE_PRIVATE).edit().clear()
            for ((k, v) in m) when (v) { is String -> e.putString(k, v); is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v) }
            e.commit()
        }
        // Une télé mise à jour (pas une installation neuve) garde le mode « toutes les applis » (Accueil.initialiserTele).
        ecrire("lanceur", if (cas.tele && cas.nom != "tele-neuve") mapOf<String, Any>("tele_mode" to "masquees") + cas.lanceur else cas.lanceur)
        if (cas.local.isNotEmpty()) ecrire("local", cas.local)
    }

    /** La part de [v] réellement à l'écran (position à l'écran, coupée aux bords), en px de hauteur. */
    private fun hauteurALEcran(v: View, ecran: Rect): Int {
        val pos = IntArray(2).also { v.getLocationOnScreen(it) }
        val r = Rect(pos[0], pos[1], pos[0] + v.width, pos[1] + v.height)
        return if (r.intersect(ecran)) r.height() else 0
    }

    /** Les commandes (applis, « Ajouter », menu…) ENTIÈREMENT à l'écran : une vue poussée sous le bord ne compte pas. */
    private fun commandesVisibles(v: View, ecran: Rect, sortie: MutableList<View>) {
        if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return
        if (v.isFocusable || v.isClickable) {
            val pos = IntArray(2).also { v.getLocationOnScreen(it) }
            val r = Rect(pos[0], pos[1], pos[0] + v.width, pos[1] + v.height)
            if (ecran.contains(r)) sortie += v
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) commandesVisibles(v.getChildAt(i), ecran, sortie)
    }

    private fun texteVisible(v: View, texte: String, ecran: Rect): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is android.widget.TextView && v.text.toString() == texte && hauteurALEcran(v, ecran) > 0) return true
        return v is ViewGroup && (0 until v.childCount).any { texteVisible(v.getChildAt(it), texte, ecran) }
    }

    /**
     * 17-10 (l'auteur, points 10, 11, 21) : rien ne sort de la zone sûre de la télé (ZoneSure) — textes, icônes, commandes et
     * widgets à l'écran doivent y tenir entiers. Les fonds plein écran n'y sont pas soumis ; une rangée qui défile en
     * largeur d'un bord à l'autre (BordABord, applis et messages) non plus : au repos son premier élément est sur la zone.
     */
    private fun horsZoneSure(v: View, zone: Rect, ecran: Rect, sortie: MutableList<String>) {
        if (v.visibility != View.VISIBLE || v.alpha == 0f || v.width <= 0 || v.height <= 0) return
        if (v is android.widget.HorizontalScrollView) return
        val pos = IntArray(2).also { v.getLocationOnScreen(it) }
        var r = Rect(pos[0], pos[1], pos[0] + v.width, pos[1] + v.height)
        // Dans une liste qui défile (et coupe à son cadre) : seule la part dessinée compte.
        for (sv in generateSequence(v.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>()) {
            val p = IntArray(2).also { sv.getLocationOnScreen(it) }
            if (!r.intersect(p[0], p[1], p[0] + sv.width, p[1] + sv.height)) return
        }
        val plein = v.width >= ecran.width() * 0.9f && v.height >= ecran.height() * 0.9f
        // Le contenu : textes, images, commandes (une case cliquable), cartes de widgets ; pas les conteneurs qui
        // défilent (une ScrollView est « focusable » par défaut) ni les espaces vides.
        val contenu = v is android.widget.TextView || v is android.widget.ImageView ||
            (v !is ViewGroup && (v.isFocusable || v.isClickable)) || (v is ViewGroup && v.isClickable && v !is android.widget.ScrollView) ||
            (v is ViewGroup && v.childCount > 0 && (v.tag as? String)?.startsWith("zone_tele_") == true)
        if (contenu && !plein && Rect.intersects(r, ecran) && !(r.left >= zone.left - 2 && r.top >= zone.top - 2 && r.right <= zone.right + 2 && r.bottom <= zone.bottom + 2)) {
            val nom = (v.tag as? String)?.let { "[$it]" } ?: ""
            val t = (v as? android.widget.TextView)?.text?.toString()?.take(40)?.let { " « $it »" } ?: ""
            sortie += "${cas.nom} : ${v.javaClass.simpleName}$nom$t sort de la zone sûre (${r.toShortString()}, zone ${zone.toShortString()})"
            return  // ses enfants sortent aussi : un seul défaut par branche
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) horsZoneSure(v.getChildAt(i), zone, ecran, sortie)
    }

    private fun tuilesApplis(v: View, sortie: MutableList<View>) {
        if (v.visibility != View.VISIBLE) return
        val tag = v.tag as? String
        if (v.isFocusable && tag != null && (tag.contains('.') || tag == "plus")) { sortie += v; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) tuilesApplis(v.getChildAt(i), sortie)
    }

    private fun parcourirFocus(a: Activity, decor: View, ecran: Rect, ctx: Context, defauts: MutableList<String>) {
        val tuiles = ArrayList<View>().also { tuilesApplis(decor, it) }
        if (tuiles.size < 2) return
        val zx = ecran.width() * org.soprez.vision.ZoneSure.pourcentX(ctx) / 100; val zy = ecran.height() * org.soprez.vision.ZoneSure.pourcentY(ctx) / 100
        val zone = Rect(zx, zy, ecran.width() - zx, ecran.height() - zy)
        for (t in tuiles + tuiles.reversed()) {
            // Comme une touche de la télécommande : on quitte le mode tactile (Robolectric y démarre).
            t.requestFocusFromTouch()
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            // Les défilements doux (smoothScrollTo) n'avancent qu'à chaque image dessinée : on fait passer les images.
            val sv0 = generateSequence(t.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().firstOrNull()
            repeat(40) { ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS); sv0?.computeScroll() }
            if (!t.hasFocus() && !t.isFocused) continue
            val pos = IntArray(2).also { t.getLocationOnScreen(it) }
            // L'appli agrandie au focus (scaleX/Y autour de son centre).
            val dw = (t.width * (t.scaleX - 1f) / 2).toInt(); val dh = (t.height * (t.scaleY - 1f) / 2).toInt()
            val r = Rect(pos[0] - dw, pos[1] - dh, pos[0] + t.width + dw, pos[1] + t.height + dh)
            val defile = generateSequence(t.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().firstOrNull()
            val cadre = defile?.let { sv -> IntArray(2).also { sv.getLocationOnScreen(it) }.let { p -> Rect(p[0], p[1], p[0] + sv.width, p[1] + sv.height) } }
            val nom = t.tag
            // Collée au bord coupé d'une liste qui défile encore de ce côté : elle paraît rognée (et l'overscan la mange).
            val marge = (4 * ctx.resources.displayMetrics.density).toInt()
            val hautCoupe = defile != null && defile.canScrollVertically(-1); val basCoupe = defile != null && defile.canScrollVertically(1)
            if (cadre != null && (r.top < cadre.top + (if (hautCoupe) marge else -1) || r.bottom > cadre.bottom - (if (basCoupe) marge else -1)))
                defauts += "${cas.nom} : appli $nom coupée par sa liste au focus (${r.toShortString()}, liste ${cadre.toShortString()})"
            if (defile != null && !(r.top >= zone.top - 2 && r.bottom <= zone.bottom + 2))
                defauts += "${cas.nom} : appli $nom hors de la zone sûre au focus (${r.toShortString()}, zone ${zone.toShortString()})"
        }
        decor.findFocus()?.clearFocus()
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun vue(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) vue(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    /** Les widgets de l'accueil télé dont une part est à l'écran : cartes des zones « Vertical » (« carte_zone_<z>_<code> »)
     *  et widgets des rangées (« widget_tele:<code> »). */
    private fun widgetsALEcran(v: View, ecran: Rect, sortie: MutableSet<String>) {
        if (v.visibility != View.VISIBLE) return
        val tag = v.tag as? String
        if (tag != null && hauteurALEcran(v, ecran) > 0) when {
            tag.startsWith("widget_tele:") -> sortie += tag.removePrefix("widget_tele:")
            tag.startsWith("carte_zone_") -> sortie += tag.removePrefix("carte_zone_").substringAfter('_')
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) widgetsALEcran(v.getChildAt(i), ecran, sortie)
    }

    @Test fun accueil_jamais_vide() {
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        installer(ctx)
        poserPreferences(ctx)
        // Un vrai appareil est relié à Home Assistant (adresse vide : aucune requête ne part, elles échouent tout de suite).
        org.soprez.vision.Config(ctx).apply {
            id = "essai_maj"; secret = "s".repeat(32); nom = "Télé du salon"; utilisateur = "Ja"; lieu = "Maison"
            personne = "person.essai"; urlInterne = ""; urlExterne = ""; urlActive = ""
        }
        if (!cas.tele) APPLIS.take(minOf(cas.applisPosees, 6)).forEach { (pkg, _) -> org.soprez.vision.Accueil.ajouterALaGrille(ctx, pkg) }
        val a: Activity = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        ShadowLooper.idleMainLooper()
        Thread.sleep(200); ShadowLooper.idleMainLooper()
        val decor = a.window.decorView
        val ecran = Rect(0, 0, decor.width, decor.height)
        val defauts = ArrayList<String>()
        val commandes = ArrayList<View>().also { commandesVisibles(decor, ecran, it) }
        // Un téléphone neuf (aucune appli posée) montre son aide « Ton accueil est vide… » : c'est voulu.
        val aideVide = !cas.tele && cas.applisPosees == 0 && texteVisible(decor, ctx.getString(org.soprez.vision.R.string.lanceur_accueil_vide), ecran)
        if (commandes.size < 2 && !aideVide) defauts += "${cas.nom} : accueil vide — ${commandes.size} commande(s) entièrement à l'écran"
        if (cas.tele && (cas.lanceur["tele_disposition"] == "vertical")) {
            val ligne = vue(decor, "tele_ligne")?.let { hauteurALEcran(it, ecran) } ?: -1
            val col = vue(decor, "colonne_tele")?.let { hauteurALEcran(it, ecran) } ?: -1
            // Rangées pleines : elles prennent leur part de la hauteur ; la ligne garde au moins un tiers de l'écran.
            val seuil = if (cas.lanceur.keys.any { it.startsWith("tele_zone_h") || it.startsWith("tele_zone_b") } &&
                cas.lanceur.filterKeys { it == "tele_zone_h" || it == "tele_zone_b" }.values.any { it == PLEINE }) ecran.height() / 3 else ecran.height() / 2
            if (ligne <= seuil) defauts += "${cas.nom} : ligne de l'accueil Vertical écrasée ou hors de l'écran ($ligne px visibles sur ${ecran.height()})"
            if (col <= seuil) defauts += "${cas.nom} : colonne des applis écrasée ou hors de l'écran ($col px visibles sur ${ecran.height()})"
        }
        // 1.93.05 : les widgets posés par la version précédente dans une zone activée restent à l'écran (rien de perdu
        // par la zone sûre, le style proposé par HA ou le nettoyage des zones).
        if (cas.tele && cas.lanceur["tele_disposition"] == "vertical") {
            val attendus = listOf("g", "d", "h", "b").filter { cas.lanceur["tele_zone_${it}_active"] == true }
                .flatMap { z -> (cas.lanceur["tele_zone_$z"] as? String)?.let { org.json.JSONArray(it) }?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() }
                .filter { it in setOf("horloge_vision", "meteo") }.toSet()
            val vus = HashSet<String>().also { widgetsALEcran(decor, ecran, it) }
            for (w in attendus - vus) defauts += "${cas.nom} : widget « $w » perdu après la mise à jour (à l'écran : ${vus.sorted()})"
        }
        // 17-10 (l'auteur, point 11) : le focus parcourt les applis vers le bas puis remonte ; l'appli prise (agrandie, avec son
        // anneau) reste entière dans sa liste et dans la zone sûre, à chaque pas.
        if (cas.tele) parcourirFocus(a, decor, ecran, ctx, defauts)
        if (cas.tele) {
            val zx = ecran.width() * org.soprez.vision.ZoneSure.pourcentX(ctx) / 100; val zy = ecran.height() * org.soprez.vision.ZoneSure.pourcentY(ctx) / 100
            horsZoneSure(decor, Rect(zx, zy, ecran.width() - zx, ecran.height() - zy), ecran, defauts)
        }
        defauts += Recette.capturer(decor, "maj-${cas.nom}", taille, 1.0f)
        Recette.noter(taille, 1.0f, defauts.filter { "accueil" in it || "écrasée" in it || "zone sûre" in it || "focus" in it })
        a.finish()
        assertEquals("${cas.nom} (${taille.nom}) :\n" + defauts.joinToString("\n"), emptyList<String>(), defauts)
    }
}
