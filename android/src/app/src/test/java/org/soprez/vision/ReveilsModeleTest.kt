// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Réveil (lot 4) : le moteur pur. Heure locale de l'appareil (jamais un instant figé), changement d'heure,
 * prochaine occurrence hors sonnées, alarme de synchronisation, montée du volume, répétitions, désarmement,
 * horodatages ISO aller-retour.
 */
class ReveilsModeleTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")

    private fun reveil(id: String, vararg occs: Pair<String, String>, volDebut: Int = 10, volFin: Int = 60, montee: Int = 180, repMax: Int = 3) =
        ReveilsModele.Reveil(id, "Collège", "sig", occs.map { (d, h) -> ReveilsModele.Occ("$id@$d", id, d, h, "") },
            ReveilsModele.Source("sonnerie", ""), volDebut, volFin, montee, 9, repMax, 30, true)

    private fun bloc(vararg r: ReveilsModele.Reveil, actif: Boolean = true) =
        ReveilsModele.Bloc(actif, "v1", 10, r.toList(), emptyList())

    @Test fun heureLocaleDeLAppareil() {
        val t = ReveilsModele.instant("2026-10-12", "07:00", paris)
        assertEquals("2026-10-12T07:00:00+02:00", ReveilsModele.iso(t, paris))
        // Même occurrence sur un appareil à Tokyo : 07:00 de Tokyo, pas 07:00 de Paris.
        val tk = ReveilsModele.instant("2026-10-12", "07:00", tokyo)
        assertEquals("2026-10-12T07:00:00+09:00", ReveilsModele.iso(tk, tokyo))
        assertEquals(7 * 3_600_000L, t - tk)
    }

    @Test fun changementDHeure() {
        // Passage à l'heure d'été (29/03/2026) : 02:30 n'existe pas → 03:30.
        assertEquals("2026-03-29T03:30:00+02:00", ReveilsModele.iso(ReveilsModele.instant("2026-03-29", "02:30", paris), paris))
        // Un réveil 07:00 reste à 07:00 locale de part et d'autre du passage à l'heure d'hiver (25/10/2026).
        assertEquals("2026-10-25T07:00:00+01:00", ReveilsModele.iso(ReveilsModele.instant("2026-10-25", "07:00", paris), paris))
        assertEquals("2026-10-24T07:00:00+02:00", ReveilsModele.iso(ReveilsModele.instant("2026-10-24", "07:00", paris), paris))
    }

    @Test fun prochaineHorsSonnees() {
        val b = bloc(reveil("rv1", "2026-10-12" to "07:00", "2026-10-13" to "07:00"), reveil("rv2", "2026-10-12" to "06:45"))
        val dimanche = ReveilsModele.instant("2026-10-11", "21:00", paris)
        val (occ, t) = ReveilsModele.prochaine(b, dimanche, paris)!!
        assertEquals("rv2@2026-10-12", occ.id)
        assertEquals(ReveilsModele.instant("2026-10-12", "06:45", paris), t)
        // Déjà sonnée : on passe à la suivante.
        assertEquals("rv1@2026-10-12", ReveilsModele.prochaine(b, dimanche, paris, setOf("rv2@2026-10-12"))!!.first.id)
        // Passées : ignorées.
        val lundi8h = ReveilsModele.instant("2026-10-12", "08:00", paris)
        assertEquals(listOf("rv1@2026-10-13"), ReveilsModele.aVenir(b, lundi8h, paris).map { it.first.id })
        assertNull(ReveilsModele.prochaine(b, ReveilsModele.instant("2026-10-14", "00:00", paris), paris))
        assertNull(ReveilsModele.prochaine(null, dimanche, paris))
    }

    @Test fun synchroAvantLHeure() {
        val h = ReveilsModele.instant("2026-10-12", "07:00", paris)
        assertEquals(h - 600_000L, ReveilsModele.synchro(h, 10, h - 3_600_000L))
        assertNull(ReveilsModele.synchro(h, 10, h - 300_000L))  // déjà dans les 10 min
    }

    @Test fun volumeProgressif() {
        val r = reveil("rv1", volDebut = 10, volFin = 60, montee = 180)
        assertEquals(10f / 60f, ReveilsModele.volume(r, 0), 0.001f)
        assertEquals(10f / 60f + (1f - 10f / 60f) / 2f, ReveilsModele.volume(r, 90_000), 0.001f)
        assertEquals(1f, ReveilsModele.volume(r, 180_000), 0.001f)
        assertEquals(1f, ReveilsModele.volume(r, 999_000), 0.001f)
        assertEquals(1f, ReveilsModele.volume(reveil("x", montee = 0), 0), 0.001f)
        assertEquals(0f, ReveilsModele.volume(reveil("x", volFin = 0), 50_000), 0.001f)
        assertEquals(1f, ReveilsModele.volume(reveil("x", volDebut = 80, volFin = 60), 0), 0.001f)  // début > fin : plein volume
        assertEquals(4, ReveilsModele.cranAlarme(60, 7))
        assertEquals(1, ReveilsModele.cranAlarme(1, 7))
        assertEquals(0, ReveilsModele.cranAlarme(0, 7))
        assertEquals(7, ReveilsModele.cranAlarme(100, 7))
    }

    @Test fun repetitions() {
        val r = reveil("rv1", repMax = 3)
        assertTrue(ReveilsModele.peutRepeter(r, 0))
        assertTrue(ReveilsModele.peutRepeter(r, 2))
        assertFalse(ReveilsModele.peutRepeter(r, 3))
        assertFalse(ReveilsModele.peutRepeter(reveil("x", repMax = 0), 0))
    }

    @Test fun desarmement() {
        val b = bloc(reveil("rv1", "2026-10-12" to "07:00"))
        assertFalse(ReveilsModele.desarmerTout(setOf("reveil", "plages"), b, true))
        assertTrue(ReveilsModele.desarmerTout(setOf("plages"), b, true))            // module coupé
        assertTrue(ReveilsModele.desarmerTout(setOf("reveil"), bloc(actif = false), true))
        assertTrue(ReveilsModele.desarmerTout(setOf("reveil"), null, true))          // bloc disparu
        assertFalse(ReveilsModele.desarmerTout(setOf("reveil"), null, false))
        assertFalse(ReveilsModele.desarmerTout(null, null, true))                    // HA ancien : on garde
    }

    @Test fun horodatagesIso() {
        val t = ReveilsModele.instant("2026-10-12", "07:00", paris)
        assertEquals(t, ReveilsModele.lireIso("2026-10-12T07:00:00+02:00"))
        assertEquals(t, ReveilsModele.lireIso("2026-10-12T05:00:00Z"))
        assertEquals(t + 2_000, ReveilsModele.lireIso("2026-10-12T07:00:02.123+02:00"))
        assertEquals(0L, ReveilsModele.lireIso("demain"))
        assertEquals("07:05", ReveilsModele.hhmm(7, 5))
        assertEquals("23:59", ReveilsModele.hhmm(30, 75))
    }

    /** Appareil éteint ou redémarré pendant l'heure : la dernière occurrence manquée sonne encore (dans l'arrêt auto). */
    @Test fun rattrapageApresRedemarrage() {
        val b = bloc(reveil("rv1", "2026-10-12" to "07:00", "2026-10-13" to "07:00"))
        val sept = ReveilsModele.instant("2026-10-12", "07:00", paris)
        assertEquals("rv1@2026-10-12", ReveilsModele.manquee(b, sept + 10 * 60_000L, paris, emptySet())!!.first.id)
        assertNull(ReveilsModele.manquee(b, sept + 31 * 60_000L, paris, emptySet()))        // trop tard (arrêt auto 30 min)
        assertNull(ReveilsModele.manquee(b, sept + 10 * 60_000L, paris, setOf("rv1@2026-10-12")))  // déjà sonnée
        assertNull(ReveilsModele.manquee(b, sept - 60_000L, paris, emptySet()))              // pas encore l'heure
    }

    @Test fun reportGardeAuRedemarrage() {
        val t = 1_000_000_000L
        assertEquals("armer", ReveilsModele.sortReport(t, t - 1))
        assertEquals("sonner", ReveilsModele.sortReport(t, t + 5 * 60_000L))
        assertEquals("oublier", ReveilsModele.sortReport(t, t + 31 * 60_000L))
    }

    @Test fun sourceJouableSurLAppareil() {
        assertTrue(ReveilsModele.Source("url", "https://radio/x").flux)
        assertFalse(ReveilsModele.Source("url", "ftp://x").flux)
        assertFalse(ReveilsModele.Source("ma", "library://playlist/1").flux)   // V1 : sonnerie locale
        assertFalse(ReveilsModele.Source("sonnerie", "").flux)
    }
}
