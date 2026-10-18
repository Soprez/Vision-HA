package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 1.90.5 (l'auteur, variante B) : à l'écran, la réaction de l'œil remplace le bandeau gris.
 * - succès : l'œil seul ; info et erreur : l'œil et le texte ;
 * - dans l'écran utile, sans couvrir un bouton ni un texte quand un coin est libre ;
 * - annoncée (description) ; partie après la réaction ;
 * - la zone du haut : le remerciement et l'œil joyeux, ou l'heure et la météo.
 */
class ReactionOeilEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private var zoneAvant: List<String>? = null

    override fun setUp() { super.setUp(); CodeEssai.poser(instrumentation.targetContext); EcransVivants.suivre(instrumentation); zoneAvant = ZoneHaut.widgets(ctx); ReactionOeil.actif(ctx, true) }
    override fun tearDown() { zoneAvant?.let { ZoneHaut.poser(ctx, it) }; CodeEssai.rendre(instrumentation.targetContext); super.tearDown() }

    private fun lancer(): Activity {
        val a = EcransVivants.lancer(instrumentation, EcransReglages.hote(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(800)
        return a
    }

    private fun reaction(a: Activity): View? {
        var v: View? = null
        instrumentation.runOnMainSync { v = (a.window.decorView as ViewGroup).let { r -> (0 until r.childCount).map { r.getChildAt(it) }.firstOrNull { it.tag == ReactionOeil.TAG } } }
        return v
    }

    private fun textes(v: View): List<String> = if (v is TextView) listOf(v.text.toString()) else if (v is ViewGroup) (0 until v.childCount).flatMap { textes(v.getChildAt(it)) } else emptyList()

    /** La réaction de l'œil pour [texte] ; réémise tant que l'écran n'est pas encore mis en page (3 s au plus). */
    private fun montrer(a: Activity, texte: String, genre: OeilModele.Genre): View {
        val fin = SystemClock.elapsedRealtime() + 3_000
        while (true) {
            instrumentation.runOnMainSync { Ui.toast(a, texte, genre = genre) }
            SystemClock.sleep(300)
            reaction(a)?.let { return it }
            if (SystemClock.elapsedRealtime() > fin) throw AssertionError("pas de réaction de l'œil pour « $texte »")
        }
    }

    private fun verifierPlace(a: Activity, v: View, nom: String) {
        var defaut = ""
        instrumentation.runOnMainSync {
            val racine = a.window.decorView as ViewGroup
            val r = Rect(); v.getGlobalVisibleRect(r)
            if (r.width() < v.width - 1 || r.height() < v.height - 1) defaut = "$nom : réaction coupée ($r)"
            val occ = ReactionOeil.occupes(racine)
            val couverts = occ.filter { Rect.intersects(it, r) }
            // Rien ne doit être couvert quand un coin libre existe ; sur un écran plein, le repli en bas au centre est admis.
            val cadre = Rect(); racine.getWindowVisibleDisplayFrame(cadre)
            val libre = ReactionOeil.coinLibre(ReactionOeil.Boite(cadre.left, cadre.top, cadre.right, cadre.bottom), r.width(), r.height(),
                Ui.dp(a, if (ReglagesTvActivity.estTele(a)) 32f else 16f), occ.map { ReactionOeil.Boite(it.left, it.top, it.right, it.bottom) })
            if (couverts.isNotEmpty() && libre) defaut = "$nom : la réaction couvre ${couverts.size} élément(s) alors qu'un coin est libre : $couverts / $r"
        }
        assertTrue(defaut, defaut.isEmpty())
    }

    fun testSuccesLOeilSeul() {
        val a = lancer()
        val v = montrer(a, "Enregistré.", OeilModele.Genre.SUCCES)
        assertEquals("Enregistré.", v.contentDescription)
        assertTrue("un succès n'a pas de texte : ${textes(v)}", textes(v).isEmpty())
        verifierPlace(a, v, "succès")
        // Partie après la réaction (750 ms) et son effacement.
        val fin = SystemClock.elapsedRealtime() + 3_000
        while (reaction(a) != null && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(100)
        assertNull("la réaction d'un succès reste à l'écran", reaction(a))
        a.finish()
    }

    fun testInfoEtErreurGardentLeurTexte() {
        val a = lancer()
        for ((t, g) in listOf("Choisis une enceinte." to OeilModele.Genre.INFO, "Home Assistant ne répond pas." to OeilModele.Genre.ERREUR)) {
            val v = montrer(a, t, g)
            assertEquals(listOf(t), textes(v))
            verifierPlace(a, v, g.name)
            SystemClock.sleep(1_200)
            assertNotNull("le texte de « $t » doit rester le temps de le lire", reaction(a))
        }
        a.finish()
    }

    /** Sur l'accueil (télé : remerciement en haut, rangée d'applis), une erreur ne couvre ni nom d'appli ni texte. */
    fun testReactionSurLAccueil() {
        val avant = ZoneHaut.widgets(ctx)
        try {
            ZoneHaut.poser(ctx, listOf(ZoneHaut.REMERCIEMENT))
            // Une tâche propre : l'accueil d'un essai précédent ne doit pas être réutilisé.
            val a = EcransVivants.lancer(instrumentation, Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try {
            instrumentation.waitForIdleSync(); SystemClock.sleep(1000)
            val v = montrer(a, "Home Assistant injoignable : les entités viendront plus tard.", OeilModele.Genre.ERREUR)
            verifierPlace(a, v, "accueil")
            // L'accueil a des coins libres : la réaction n'y couvre rien (sinon, ce qui bloque chaque coin est listé).
            var bloque = ""
            instrumentation.runOnMainSync {
                val racine = a.window.decorView as ViewGroup
                val r = Rect(); v.getGlobalVisibleRect(r)
                val occ = ReactionOeil.occupes(racine)
                val couverts = occ.filter { Rect.intersects(it, r) }
                if (couverts.isNotEmpty()) {
                    val cadre = Rect(); racine.getWindowVisibleDisplayFrame(cadre)
                    val m = Ui.dp(a, if (ReglagesTvActivity.estTele(a)) 32f else 16f)
                    val coins = listOf("haut-droite" to Rect(cadre.right - m - r.width(), cadre.top + m, cadre.right - m, cadre.top + m + r.height()),
                        "bas-droite" to Rect(cadre.right - m - r.width(), cadre.bottom - m - r.height(), cadre.right - m, cadre.bottom - m),
                        "haut-gauche" to Rect(cadre.left + m, cadre.top + m, cadre.left + m + r.width(), cadre.top + m + r.height()),
                        "bas-gauche" to Rect(cadre.left + m, cadre.bottom - m - r.height(), cadre.left + m + r.width(), cadre.bottom - m))
                    bloque = coins.joinToString(" | ") { (n, c) -> n + " : " + occ.filter { Rect.intersects(it, c) }.joinToString() }
                    // Qui est-ce ? (classe, étiquette, texte) pour le coin haut-droite.
                    val hd = coins[0].second
                    fun noms(x: View): List<String> {
                        val p = IntArray(2); x.getLocationOnScreen(p)
                        val rx = Rect(p[0], p[1], p[0] + x.width, p[1] + x.height)
                        val ici = if (x.visibility == View.VISIBLE && x.tag != ReactionOeil.TAG && Rect.intersects(rx, hd) && (x.isClickable || x.isFocusable || (x is TextView && x.text.isNotBlank())))
                            listOf("${x.javaClass.simpleName}[${x.tag}] « ${(x as? TextView)?.text ?: x.contentDescription ?: ""} » $rx") else emptyList()
                        return ici + if (x is ViewGroup && x.tag != ReactionOeil.TAG) (0 until x.childCount).flatMap { noms(x.getChildAt(it)) } else emptyList()
                    }
                    bloque += " || haut-droite : " + noms(racine).joinToString(" ; ")
                    // Toute la bande du milieu (entre l'en-tête et la rangée d'applis) : ce qui y compte comme occupé.
                    bloque += " || bande : " + occ.filter { it.top < cadre.height() * 0.6f && it.bottom > cadre.height() * 0.2f }.joinToString()
                }
            }
            var ou = ""
            instrumentation.runOnMainSync { val r = Rect(); v.getGlobalVisibleRect(r); ou = r.toString() }
            assertTrue("la réaction ($ou) couvre l'accueil ; coins : $bloque", bloque.isEmpty())
            } finally { a.finish(); instrumentation.waitForIdleSync(); SystemClock.sleep(300) }
        } finally { ZoneHaut.poser(ctx, avant) }
    }

    fun testUneSeuleReactionALaFois() {
        val a = lancer()
        montrer(a, "Un.", OeilModele.Genre.INFO); montrer(a, "Deux.", OeilModele.Genre.INFO)
        var n = 0
        instrumentation.runOnMainSync { val r = a.window.decorView as ViewGroup; n = (0 until r.childCount).count { r.getChildAt(it).tag == ReactionOeil.TAG } }
        assertEquals(1, n)
        a.finish()
    }

    /**
     * Télé (l'auteur, 14 octobre) : plus d'en-tête « VISION », en rangées comme en « Vertical » ; en « Vertical », une zone
     * activée montre l'horloge stylisée (HorlogeVision), pas une horloge simple.
     */
    fun testAccueilTeleSansMarque() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        val p = ctx.getSharedPreferences("lanceur", android.content.Context.MODE_PRIVATE)
        val avant = p.all.toMap()
        try {
            for (d in listOf(DispositionAccueil.TELE_RANGEES, DispositionAccueil.TELE_VERTICAL)) {
                DispositionAccueil.poserDisposTele(ctx, d)
                DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_D, listOf(ZoneHaut.HORLOGE))
                DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_D, true)
                DispositionAccueil.poserColonne(ctx, DispositionAccueil.COLONNE_GAUCHE)
                val a = EcransVivants.lancer(instrumentation, Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                instrumentation.waitForIdleSync(); SystemClock.sleep(1_000)
                var marque = true; var horloge: View? = null
                instrumentation.runOnMainSync {
                    marque = textes(a.window.decorView).any { it == "VISION" }
                    horloge = a.window.decorView.findViewWithTag<View>("zone_tele_d")?.findViewWithTag("horloge_vision")
                }
                assertFalse("plus d'en-tête « VISION » sur l'accueil télé ($d)", marque)
                if (d == DispositionAccueil.TELE_VERTICAL) assertNotNull("l'horloge stylisée dans la zone de droite", horloge)
                a.finish()
            }
        } finally {
            p.edit().clear().apply { for ((k, v) in avant) when (v) { is String -> putString(k, v); is Boolean -> putBoolean(k, v) } }.commit()
        }
    }

    /** Téléphone (l'auteur, 23 h 17) : l'horloge stylisée par défaut, sans météo ni en-tête « VISION » ; la météo en option. */
    fun testAccueilTelephoneHorloge() {
        if (ReglagesTvActivity.estTele(ctx)) return
        for (m in listOf(listOf(ZoneHaut.HORLOGE), listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO))) {
            ZoneHaut.poser(ctx, m)
            val a = EcransVivants.lancer(instrumentation, Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            instrumentation.waitForIdleSync(); SystemClock.sleep(1_000)
            var horloge: View? = null; var meteo = false; var marque = false
            instrumentation.runOnMainSync {
                horloge = a.window.decorView.findViewWithTag("horloge_vision")
                meteo = a.window.decorView.findViewWithTag<View>("temp") != null
                marque = textes(a.window.decorView).any { it == "VISION" }
            }
            assertNotNull("l'horloge stylisée en haut ($m)", horloge)
            assertFalse("plus d'en-tête « VISION » collé à la caméra", marque)
            assertEquals("météo seulement en option ($m)", ZoneHaut.METEO in m, meteo)
            // Rien sous l'encoche ni sous la barre d'état.
            var defaut = ""
            instrumentation.runOnMainSync {
                val r = Rect(); horloge!!.getGlobalVisibleRect(r)
                val cadre = Rect(); a.window.decorView.getWindowVisibleDisplayFrame(cadre)
                if (r.top < cadre.top) defaut = "horloge sous la barre d'état : $r / $cadre"
                if (android.os.Build.VERSION.SDK_INT >= 28) a.window.decorView.rootWindowInsets?.displayCutout?.boundingRects?.forEach { e -> if (Rect.intersects(e, r)) defaut = "horloge sous l'encoche : $r / $e" }
            }
            assertTrue(defaut, defaut.isEmpty())
            a.finish(); instrumentation.waitForIdleSync(); SystemClock.sleep(400)
        }
    }

    /** L'horloge stylisée est un widget : ses réglages (date masquée, 12 h) changent ce qui est montré. */
    fun testReglagesHorloge() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val avant = ZoneHaut.reglagesHorloge(ctx)
        try {
            ZoneHaut.poser(ctx, listOf(ZoneHaut.HORLOGE))
            for (r in listOf(ZoneHaut.ReglagesHorloge(date = true), ZoneHaut.ReglagesHorloge(taille = ZoneHaut.Taille.PETITE, format = ZoneHaut.Format.H12, date = false))) {
                ZoneHaut.poserReglagesHorloge(ctx, r)
                val a = EcransVivants.lancer(instrumentation, Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                instrumentation.waitForIdleSync(); SystemClock.sleep(800)
                var n = -1; var cote = 0
                instrumentation.runOnMainSync {
                    val z = a.window.decorView.findViewWithTag<ViewGroup>("zone_horloge")
                    n = z?.childCount ?: -1; cote = z?.getChildAt(0)?.width ?: 0
                }
                assertEquals("date ${if (r.date) "montrée" else "masquée"}", if (r.date) 2 else 1, n)
                if (r.taille == ZoneHaut.Taille.PETITE) assertTrue("petite horloge : $cote px", cote < (148 * ctx.resources.displayMetrics.density).toInt())
                a.finish(); instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            }
        } finally { ZoneHaut.poserReglagesHorloge(ctx, avant) }
    }

    /**
     * 1.91.2 (retour d'essai) : téléphone d'un Décideur — rien sous la date (ni pastille « Gestion » ni « Personnaliser cette zone ») ;
     * la zone du haut se personnalise par un appui long (action annoncée à l'accessibilité).
     */
    fun testRienSousLaDateAppuiLong() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val parentAvant = Etat.parent
        try {
            Etat.parent = true
            ctx.getSharedPreferences("lanceur", 0).edit().putString("profil", "parent").commit()
            ZoneHaut.poser(ctx, listOf(ZoneHaut.HORLOGE))
            val a = EcransVivants.lancer(instrumentation, Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try {
                instrumentation.waitForIdleSync(); SystemClock.sleep(800)
                var interdits = emptyList<String>(); var appuiLong = false; var action = false; var actions = ""
                instrumentation.runOnMainSync {
                    val entete = a.window.decorView.findViewWithTag<ViewGroup>("entete_telephone")!!
                    interdits = textes(entete).filter { it == ctx.getString(R.string.lanceur_gestion) || it == ctx.getString(R.string.zonehaut_personnaliser) }
                    appuiLong = entete.isLongClickable
                    val info = entete.createAccessibilityNodeInfo()
                    actions = info.actionList.joinToString { "${it.id}:${it.label}" } + " délégué=" + entete.accessibilityDelegate?.javaClass?.name
                    action = info.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK && it.label?.toString() == ctx.getString(R.string.zonehaut_personnaliser) }
                }
                assertTrue("rien sous la date : $interdits", interdits.isEmpty())
                assertTrue("la zone du haut se personnalise par un appui long", appuiLong)
                assertTrue("l'appui long est annoncé à l'accessibilité : $actions", action)
            } finally { a.finish(); instrumentation.waitForIdleSync() }
        } finally { Etat.parent = parentAvant }
    }

    fun testZoneDuHaut() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        for (m in listOf(listOf(ZoneHaut.REMERCIEMENT), listOf(ZoneHaut.CLASSIQUE))) {
            ZoneHaut.poser(ctx, m)
            val a = EcransVivants.lancer(instrumentation, Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            instrumentation.waitForIdleSync(); SystemClock.sleep(1_000)
            var zone: View? = null; var heure = false; var texte = ""
            instrumentation.runOnMainSync {
                zone = a.window.decorView.findViewWithTag(ZoneHaut.TAG)
                texte = a.window.decorView.findViewWithTag<TextView>("zone_haut_texte")?.text?.toString().orEmpty()
                heure = a.window.decorView.findViewWithTag<View>("temp") != null
            }
            if (m == listOf(ZoneHaut.REMERCIEMENT)) {
                assertNotNull("la zone du haut montre le remerciement", zone)
                assertEquals(ctx.getString(R.string.zonehaut_merci), texte)
                assertFalse("ni heure ni météo avec le remerciement", heure)
            } else {
                assertNull("heure et météo : pas de zone de remerciement", zone)
                assertTrue("l'heure et la météo sont là", heure)
            }
            a.finish(); instrumentation.waitForIdleSync(); SystemClock.sleep(400)
        }
    }
}
