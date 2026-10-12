// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.io.File

/**
 * Lot Accueil (point 4) : l'écran de blocage à font_scale 1,0 et 1,3, en portrait et en paysage, dans un thème clair
 * et un thème sombre. Aucun texte coupé (points de suspension, hauteur trop courte, mot coupé en deux) et rien ne
 * déborde de l'écran en largeur (la grille « Toujours accessibles » revient à la ligne). Puis un deuxième blocage
 * pour une autre appli (singleTask → onNewIntent) : l'écran doit montrer la nouvelle appli.
 * Captures : /sdcard/Android/data/org.soprez.vision/files/captures/blocage_*.png
 *
 * Lancement (émulateur seulement, par l'intégrateur) :
 *   adb shell am instrument -w -e class org.soprez.vision.EcranBlocageTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class EcranBlocageTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val problemes = ArrayList<String>()
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }

    private fun shell(cmd: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    override fun tearDown() {
        shell("settings put system font_scale 1.0")
        // Retour à l'orientation naturelle d'abord : sur une télé (sans capteur), « libérer » seul la laisse tournée.
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        shell("settings put system user_rotation 0")
        super.tearDown()
    }

    fun testTailles100Et130() {
        val themeAvant = Local.theme(ctx); val nuitAvant = Local.nuit(ctx)
        val verrouAvant = Etat.verrouille; val msgAvant = Etat.message; val prochainAvant = Etat.prochainChangement
        Local.poserNuit(ctx, "")
        try {
            for (echelle in listOf(1.0f, 1.3f)) {
                shell("settings put system font_scale $echelle")
                SystemClock.sleep(1500)
                // Une télé ne pivote pas : son orientation naturelle seulement.
                val sens = if (ReglagesTvActivity.estTele(ctx)) listOf(UiAutomation.ROTATION_FREEZE_0 to "tele")
                    else listOf(UiAutomation.ROTATION_FREEZE_0 to "portrait", UiAutomation.ROTATION_FREEZE_90 to "paysage")
                for ((rotation, sens) in sens) {
                    instrumentation.uiAutomation.setRotation(rotation)
                    SystemClock.sleep(800)
                    for (theme in listOf("Beige", "Bleu nuit")) {
                        Local.poserTheme(ctx, theme)
                        Etat.verrouille = true
                        Etat.message = "Temps d'écran de la journée écoulé : la tablette se repose jusqu'à demain matin"
                        Etat.prochainChangement = System.currentTimeMillis() + 10 * 3600_000L
                        val nom = "blocage_${Themes.cle(theme)}_${(echelle * 100).toInt()}_$sens"
                        ecran(nom, Intent(ctx, BlockActivity::class.java).putExtra("raison", Etat.message).putExtra("pkg", ""))
                    }
                }
            }
        } finally {
            Etat.verrouille = verrouAvant; Etat.message = msgAvant; Etat.prochainChangement = prochainAvant
            Local.poserTheme(ctx, themeAvant); Local.poserNuit(ctx, nuitAvant)
        }
        File(dossier, "problemes_blocage_lot4.txt").writeText(problemes.joinToString("\n").ifEmpty { "Aucun problème." })
        assertTrue("Écran de blocage :\n" + problemes.joinToString("\n"), problemes.isEmpty())
    }

    /** Deux blocages d'affilée pour deux applis : le second remplace le premier (pkg, titre). */
    fun testDeuxiemeBlocageAutreAppli() {
        val appsAvant = Etat.apps; val raisonsAvant = Etat.raisons; val paquetAvant = Usage.dernierPaquet; val verrouAvant = Etat.verrouille
        val a = "com.android.chrome"; val b = "com.android.settings"
        // 1.87.0 : l'agent de l'émulateur (appairé à un vrai HA) réécrit l'état toutes les 20 s et le suivi d'usage le
        // dernier paquet ; l'écran se retirait alors de lui-même et le 2e blocage en ouvrait un autre. On fige le décor.
        val cible = java.util.concurrent.atomic.AtomicReference(a)
        val fige = java.util.concurrent.atomic.AtomicBoolean(true)
        val figeur = Thread {
            while (fige.get()) {
                Etat.verrouille = false
                Etat.apps = setOf(a, b); Etat.raisons = mapOf(a to "plage|Hors des plages prévues", b to "regle|Fermée par un gestionnaire")
                Usage.dernierPaquet = cible.get()
                SystemClock.sleep(50)
            }
        }
        try {
            figeur.start(); SystemClock.sleep(100)
            val act = instrumentation.startActivitySync(Intent(ctx, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("raison", "Cette appli est bloquée").putExtra("pkg", a))
            instrumentation.waitForIdleSync(); SystemClock.sleep(800)
            val titreA = titre(act)
            cible.set(b); SystemClock.sleep(100)
            ctx.startActivity(Intent(ctx, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("raison", "Cette appli est bloquée").putExtra("pkg", b))
            instrumentation.waitForIdleSync(); SystemClock.sleep(1200)
            val titreB = titre(act)
            assertEquals(b, act.intent.getStringExtra("pkg"))
            assertTrue("Titre inchangé après le 2e blocage : « $titreA »", titreA != titreB)
            // Ui.coupable pose des espaces sans chasse (U+200B, invisibles) après les points d'un nom de paquet long.
            assertTrue("Titre du 2e blocage : « $titreB »", titreB.replace("\u200B", "").startsWith(Usage.libelle(ctx, b)))
            instrumentation.runOnMainSync { act.finish() }
        } finally {
            fige.set(false); figeur.join()
            Etat.apps = appsAvant; Etat.raisons = raisonsAvant; Usage.dernierPaquet = paquetAvant; Etat.verrouille = verrouAvant
        }
    }

    private fun titre(a: Activity): String {
        var t = ""
        instrumentation.runOnMainSync {
            val f = BlockActivity::class.java.getDeclaredField("titre").apply { isAccessible = true }
            t = (f.get(a) as TextView).text.toString()
        }
        return t
    }

    private fun ecran(nom: String, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1200)
        instrumentation.runOnMainSync {
            val racine = a.window.decorView
            verifier(racine, racine.width, nom)
            contours(a, nom)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { b -> File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        instrumentation.runOnMainSync { a.finish() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }

    /**
     * Retour de l'auteur (« des erreurs de marge sur les contours ») : aucun bouton ni carte rogné par son parent (coins arrondis
     * coupés, bas plat), rien collé au bord de l'écran, et sur télé tout ce qui est visible dans la zone sûre (5 %).
     */
    private fun contours(a: android.app.Activity, ecran: String) {
        val dm = a.resources.displayMetrics
        val tele = ReglagesTvActivity.estTele(a)
        val zc = if (tele) Math.round(dm.widthPixels * Marges.ZONE_SURE) else 0
        val zh = if (tele) Math.round(dm.heightPixels * Marges.ZONE_SURE) else 0
        val defile = trouver(a.window.decorView) { it is android.widget.ScrollView } as? android.widget.ScrollView
        // La partie visible du cadre qui défile, marge de zone sûre déduite : ce qui y est doit y être entier.
        val cadre = Rect().also { r -> defile?.let { s -> s.getGlobalVisibleRect(r); r.top += s.paddingTop; r.bottom -= s.paddingBottom } }
        fun visite(v: View) {
            if (!v.isShown || v.width == 0) return
            val boite = v is android.widget.Button || (v.background != null && v is ViewGroup && v !== defile && v.parent !is android.widget.ScrollView)
            if (boite && v.parent is ViewGroup) {
                val p = v.parent as ViewGroup
                val rogne = v.top < -1 || v.left < -1 || v.bottom > p.height + 1 || v.right > p.width + 1
                if (rogne) problemes += "$ecran · ${v.javaClass.simpleName} « ${(v as? TextView)?.text ?: ""} » rogné par son parent (${v.left},${v.top},${v.right},${v.bottom} dans ${p.width}×${p.height})"
                // Entièrement visible à l'écran (pas sous le bord), ou alors défilable : seulement dans le cadre qui défile.
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                val dansCadre = defile == null || (r.top >= cadre.top - 1 && r.bottom <= cadre.bottom + 1)
                if (dansCadre && (r.left < zc - 1 || r.right > dm.widthPixels - zc + 1 || r.top < zh - 1 || r.bottom > dm.heightPixels - zh + 1))
                    problemes += "$ecran · ${v.javaClass.simpleName} « ${(v as? TextView)?.text ?: ""} » hors zone sûre ($r, zone ${zc}/${zh})"
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visite(v.getChildAt(i))
        }
        visite(a.window.decorView)
    }

    private fun trouver(v: View, test: (View) -> Boolean): View? {
        if (test(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) trouver(v.getChildAt(i), test)?.let { return it }
        return null
    }

    private fun verifier(v: View, largeurEcran: Int, ecran: String) {
        if (!v.isShown) return
        // Débordement horizontal : rien ne sort de l'écran (la page défile en hauteur seulement).
        if (v.width > 0) {
            val r = Rect()
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            r.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            if (r.left < -2 || r.right > largeurEcran + 2)
                problemes += "$ecran · ${v.javaClass.simpleName} « ${(v as? TextView)?.text ?: v.contentDescription ?: ""} » déborde (${r.left}..${r.right} sur $largeurEcran px)"
        }
        if (v is TextView && v !is EditText && v.text.isNotEmpty() && v.width > 0) {
            val l = v.layout
            val nom = "$ecran · « ${v.text.toString().replace('\n', ' ').take(60)} »"
            if (l != null) {
                if ((0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }) problemes += "$nom : points de suspension"
                val visibles = minOf(l.lineCount, v.maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE)
                if (visibles < l.lineCount) problemes += "$nom : lignes cachées"
                val utile = v.height - v.totalPaddingTop - v.totalPaddingBottom
                if (l.getLineBottom(visibles - 1) > utile + 2) problemes += "$nom : hauteur trop courte"
                val t = v.text
                for (i in 0 until l.lineCount - 1) {
                    val fin = l.getLineEnd(i)
                    if (fin in 1 until t.length && t[fin - 1].isLetter() && t[fin].isLetter()) { problemes += "$nom : mot coupé en deux"; break }
                }
            }
            val parent = v.parent as? ViewGroup
            if (parent != null && (v.left < -2 || v.right > parent.width + 2)) problemes += "$nom : dépasse son parent"
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) verifier(v.getChildAt(i), largeurEcran, ecran)
    }
}
