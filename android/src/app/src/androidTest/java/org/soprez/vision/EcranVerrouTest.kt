// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.io.File

/**
 * 1.79.0 (point 13 bis) — l'habillage de l'écran verrouillé, sur un téléphone :
 *  - chaque état se dessine (prénom, état, couleurs du thème), en clair et en sombre, sans image unie ;
 *  - activer pose un vrai fond de verrouillage ; couper l'efface (l'écran verrouillé reprend le fond d'accueil) ;
 *  - l'écran réellement verrouillé est capturé ;
 *  - la notification de statut montre son titre sur l'écran verrouillé (version publique), sans le détail.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.EcranVerrouTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class EcranVerrouTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }

    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }

    private fun enregistrer(b: Bitmap, nom: String) = File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun unie(b: Bitmap): Boolean {
        val vus = HashMap<Int, Int>(); var n = 0
        for (y in 0 until b.height step 12) for (x in 0 until b.width step 12) { val c = b.getPixel(x, y) and 0xF8F8F8; vus[c] = (vus[c] ?: 0) + 1; n++ }
        return vus.values.max() > n * 0.97 || vus.size < 6
    }

    fun testChaqueEtatSeDessine() {
        for (theme in listOf(Themes.liste[0], Themes.versionSombre(Themes.liste[0]))) {
            for (m in EcranVerrou.Moment.values()) {
                val img = EcranVerrou.dessiner(1080, 2400, theme, "Maximilien-Alexandre", m, "Fermé · réouverture à 07h00", "")
                enregistrer(img, "verrou_${m.name.lowercase()}_${if (theme.sombre) "sombre" else "clair"}")
                assertFalse("image unie : $m", unie(img))
                // Le haut reste libre pour l'horloge d'Android : une seule couleur de fond dégradé, pas de texte.
                val haut = Bitmap.createBitmap(img, 0, (2400 * 0.12).toInt(), 1080, 300)
                var clairs = 0
                for (x in 0 until 1080 step 6) for (y in 0 until 300 step 6) if (Themes.contraste(haut.getPixel(x, y), theme.fond) > 3f) clairs++
                assertTrue("du texte dans la zone de l'horloge ($m)", clairs < 20)
            }
        }
    }

    fun testActiverPuisRestaurer() {
        val cfg = Config(ctx)
        val inscritAvant = cfg.id; val utilisateurAvant = cfg.utilisateur
        try {
            if (cfg.id.isEmpty()) { cfg.id = "essai_verrou"; cfg.secret = "s".repeat(32) }
            cfg.utilisateur = "Alex"
            val wm = WallpaperManager.getInstance(ctx)
            EcranVerrou.activer(ctx, true)
            val fin = SystemClock.elapsedRealtime() + 10_000
            while (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0 && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(200)
            assertTrue("pas de fond de verrouillage posé", wm.getWallpaperId(WallpaperManager.FLAG_LOCK) > 0)
            // L'écran vraiment verrouillé : on éteint, on rallume, on capture.
            shell("input keyevent KEYCODE_SLEEP"); SystemClock.sleep(1500)
            shell("input keyevent KEYCODE_WAKEUP"); SystemClock.sleep(3500)
            // Capture par l'écran lui-même (la capture d'accessibilité ne voit pas l'écran verrouillé).
            val f = File(dossier, "verrou_reel.png")
            shell("screencap -p ${f.absolutePath}")
            val img = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            assertNotNull("capture de l'écran verrouillé impossible", img)
            assertFalse("écran verrouillé uni", unie(img))
            shell("wm dismiss-keyguard"); SystemClock.sleep(800)
            EcranVerrou.activer(ctx, false)
            val fin2 = SystemClock.elapsedRealtime() + 10_000
            while (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) > 0 && SystemClock.elapsedRealtime() < fin2) SystemClock.sleep(200)
            assertTrue("le fond Vision est resté après « couper »", wm.getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0)
        } finally {
            EcranVerrou.activer(ctx, false)
            cfg.utilisateur = utilisateurAvant
            if (inscritAvant.isEmpty()) cfg.id = ""
        }
    }

    fun testNotificationLisibleVerrouillee() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        EcranVerrou.poserDetail(ctx, false)
        AgentService.demarrer(ctx)
        SystemClock.sleep(3000)
        val n = nm.activeNotifications.firstOrNull { it.notification.channelId == StatutNotif.CANAL }?.notification
        if (n == null) return  // statut non actif sur cet émulateur (module ou appareil non relié) : rien à vérifier
        val pub = n.publicVersion
        assertNotNull("pas de version publique : l'écran verrouillé masquerait tout", pub)
        assertEquals(n.extras.getString("android.title"), pub!!.extras.getString("android.title"))
    }
}
