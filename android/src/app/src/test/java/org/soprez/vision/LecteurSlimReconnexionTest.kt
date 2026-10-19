// SPDX-License-Identifier: MIT
package org.soprez.vision

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.93.06 (TV du salon, après une coupure réseau) : à la reconnexion, l'ancienne connexion SlimProto est fermée
 * proprement AVANT la nouvelle. Music Assistant ne voit jamais deux connexions du même lecteur (« Duplicate
 * connection » : il éjectait le lecteur et le réenregistrait sous son nom brut, file vide).
 *
 * Un faux Music Assistant sur 127.0.0.1:3483 compte les connexions ouvertes en même temps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LecteurSlimReconnexionTest {

    @After fun fin() { LecteurSlim.arreter(); LecteurSlim.attenteRetourMs = 10_000L }

    /** Lit un message du lecteur (op sur 4 octets, longueur sur 4, contenu) ; null à la fermeture. */
    private fun lireMessage(e: DataInputStream): String? = try {
        val op = ByteArray(4); e.readFully(op)
        val n = e.readInt(); e.readFully(ByteArray(n))
        String(op, Charsets.US_ASCII)
    } catch (_: Exception) { null }

    @Test fun coupure_puis_retour_une_seule_connexion_a_la_fois() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val serveur = ServerSocket(3483, 4, InetAddress.getByName("127.0.0.1"))
        serveur.soTimeout = 20_000
        val ouvertes = AtomicInteger(0)
        var maximum = 0
        try {
            LecteurSlim.attenteRetourMs = 300L
            LecteurSlim.demarrer(ctx, "127.0.0.1")

            // 1re connexion : HELO.
            val c1: Socket = serveur.accept()
            maximum = maxOf(maximum, ouvertes.incrementAndGet())
            val e1 = DataInputStream(c1.getInputStream())
            assertEquals("HELO", lireMessage(e1))

            // Coupure puis retour du réseau validé : la connexion en cours est fermée, « BYE! » d'abord.
            LecteurSlim.reseauChange(present = false)
            LecteurSlim.reseauChange(present = true)
            val vus = generateSequence { lireMessage(e1) }.toList()
            assertTrue("BYE! avant la fermeture : $vus", vus.contains("BYE!"))
            c1.close(); ouvertes.decrementAndGet()

            // 2e connexion, seulement après la fermeture de la 1re : jamais deux à la fois.
            val c2 = serveur.accept()
            maximum = maxOf(maximum, ouvertes.incrementAndGet())
            assertEquals("HELO", lireMessage(DataInputStream(c2.getInputStream())))
            assertEquals("une seule connexion vers Music Assistant à la fois", 1, maximum)

            // Arrêt : la connexion part proprement aussi.
            LecteurSlim.arreter()
            val e2 = DataInputStream(c2.getInputStream())
            assertTrue(generateSequence { lireMessage(e2) }.toList().contains("BYE!"))
            c2.close()
        } finally {
            serveur.close()
        }
    }

    @Test fun attente_apres_le_retour_du_reseau() {
        assertEquals(10_000L, LecteurSlim.attenteAvantLiaison(0L, 10_000L))
        assertEquals(4_000L, LecteurSlim.attenteAvantLiaison(6_000L, 10_000L))
        assertEquals(0L, LecteurSlim.attenteAvantLiaison(12_000L, 10_000L))
    }

    @Test fun reconnexion_en_pleine_lecture_et_relance_de_la_seule_soiree_de_cet_appareil() {
        assertEquals(0x4000.toShort(), LecteurSlim.champReconnexion(true))
        assertEquals(0.toShort(), LecteurSlim.champReconnexion(false))
        val moi = "Télé du salon · Vision"
        assertTrue(LecteurSlim.relancePermise(actif = true, ici = true, lecteurNom = moi, etatMa = "off", nomCeLecteur = moi))
        // Jamais un autre lecteur (la commande agit sur celui de la soirée : sur le banc, un vrai lecteur de la maison).
        assertFalse(LecteurSlim.relancePermise(true, ici = false, lecteurNom = moi, etatMa = "off", nomCeLecteur = moi))
        assertFalse(LecteurSlim.relancePermise(true, true, lecteurNom = "Salon", etatMa = "off", nomCeLecteur = moi))
        assertFalse(LecteurSlim.relancePermise(actif = false, ici = true, lecteurNom = moi, etatMa = "off", nomCeLecteur = moi))
        // Déjà repartie : « lecture » bascule, on ne la remettrait pas en pause.
        assertFalse(LecteurSlim.relancePermise(true, true, moi, etatMa = "playing", nomCeLecteur = moi))
    }

    @Test fun le_flux_est_rouvert_une_fois_juste_apres_la_reconnexion() {
        assertTrue(LecteurSlim.rouvrirFlux(essai = 0, depuisLiaisonMs = 1_500L))
        assertFalse("une seule fois", LecteurSlim.rouvrirFlux(essai = 1, depuisLiaisonMs = 1_500L))
        assertFalse("loin de la reconnexion : « pas de son » comme avant", LecteurSlim.rouvrirFlux(essai = 0, depuisLiaisonMs = 120_000L))
    }

    @Test fun apres_une_liaison_la_musique_ne_part_jamais_par_dessus_un_autre_son() {
        // Incident du salon (18/10) : une appli de replay jouait, la soirée est repartie après une réinstallation.
        assertFalse("autre chose joue sur la télé", LecteurSlim.departApresLiaisonPermis(autreSon = true))
        // Rien d'autre : un « lecture » demandé juste après l'ouverture de l'appli passe (banc, 18:49).
        assertTrue(LecteurSlim.departApresLiaisonPermis(autreSon = false))
    }
}
