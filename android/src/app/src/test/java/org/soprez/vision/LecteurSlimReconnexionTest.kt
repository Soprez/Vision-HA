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
 *
 * 1.93.06.1 (TV du salon, 19/10 : veille de la télé à 09:27, lecteur hors ligne jusqu'à 10:54) : une liaison muette
 * (plus aucune commande de Music Assistant) est tenue pour morte et refaite sans attendre ; les battements la gardent ;
 * au réveil ou avant de lancer la soirée, la vérification est immédiate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LecteurSlimReconnexionTest {

    @After fun fin() {
        LecteurSlim.arreter(); LecteurSlim.attenteRetourMs = 10_000L; LecteurSlim.sansCommandeMs = LecteurSlim.SANS_COMMANDE_MS
        LecteurSlim.veilleEssaiMs = LecteurSlim.VEILLE_ESSAI_MS
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).setIsInteractive(true)
    }

    @Test fun ecran_eteint_une_liaison_morte_attend_le_reveil() {
        // 19/10, 13:38 → 13:57 : TCL éteinte au bouton, réseau somnolent ; l'appli se reconnectait toutes les minutes et le
        // lecteur clignotait dans Home Assistant. Écran éteint : pas de nouvel essai avant le réveil.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
        val serveur = ServerSocket(3483, 4, InetAddress.getByName("127.0.0.1"))
        serveur.soTimeout = 20_000
        try {
            LecteurSlim.sansCommandeMs = 500L
            LecteurSlim.demarrer(ctx, "127.0.0.1")
            val c1 = serveur.accept()
            assertEquals("HELO", lireMessage(DataInputStream(c1.getInputStream())))
            pm.setIsInteractive(false)
            // Le faux Music Assistant se tait : la liaison tombe (0,5 s), mais l'écran est éteint : aucun nouvel essai.
            serveur.soTimeout = 3_000
            try { serveur.accept(); throw AssertionError("écran éteint : pas de reconnexion") } catch (_: java.net.SocketTimeoutException) {}
            assertFalse(LecteurSlim.connecte)
            // Réveil : la liaison est refaite tout de suite.
            pm.setIsInteractive(true)
            serveur.soTimeout = 5_000
            val c2 = serveur.accept()
            assertEquals("HELO", lireMessage(DataInputStream(c2.getInputStream())))
            c1.close(); c2.close()
        } finally {
            serveur.close()
        }
    }

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

    // ------------------------------------------------------------ 1.93.06.1 : liaison muette (TV du salon, 19/10)

    /** Un faux « strm t » (battement) de Music Assistant : longueur (2 octets), « strm », 24 octets. */
    private fun battement(c: Socket) {
        val d = ByteArray(24).also { it[0] = 't'.code.toByte(); it[1] = '0'.code.toByte() }
        val b = java.nio.ByteBuffer.allocate(2 + 4 + 24); b.putShort((4 + 24).toShort()); b.put("strm".toByteArray(Charsets.US_ASCII)); b.put(d)
        c.getOutputStream().apply { write(b.array()); flush() }
    }

    @Test fun liaison_morte_sans_commande_pendant_trop_longtemps() {
        assertFalse(LecteurSlim.liaisonMorte(depuisDerniereCommandeMs = 4_000L, limiteMs = 20_000L))
        assertFalse(LecteurSlim.liaisonMorte(20_000L, 20_000L))
        assertTrue(LecteurSlim.liaisonMorte(20_001L, 20_000L))
        // 19/10 : 77 min sans rien, la liaison aurait dû être refaite après 20 s.
        assertTrue(LecteurSlim.liaisonMorte(77L * 60_000L, LecteurSlim.SANS_COMMANDE_MS))
    }

    @Test fun une_liaison_muette_est_refermee_et_rouverte_sans_attendre() {
        // 19/10 : après la veille de la télé, la connexion restait « ouverte » côté télé alors que Music Assistant
        // l'avait abandonnée : 77 min hors ligne. Ici le faux Music Assistant se tait : la télé refait la liaison seule.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val serveur = ServerSocket(3483, 4, InetAddress.getByName("127.0.0.1"))
        serveur.soTimeout = 20_000
        try {
            LecteurSlim.sansCommandeMs = 700L
            LecteurSlim.demarrer(ctx, "127.0.0.1")
            val c1 = serveur.accept()
            val e1 = DataInputStream(c1.getInputStream())
            assertEquals("HELO", lireMessage(e1))
            val avant = LecteurSlim.liaisons  // compteur de l'objet, cumulé d'un essai à l'autre
            val t0 = System.nanoTime()
            val c2 = serveur.accept()  // la 2e liaison, d'elle-même
            val dt = (System.nanoTime() - t0) / 1_000_000
            assertEquals("HELO", lireMessage(DataInputStream(c2.getInputStream())))
            assertTrue("refaite après le silence (0,7 s), sans les 5 s d'attente entre deux essais : $dt ms", dt in 400..4_500)
            assertEquals(avant + 1, LecteurSlim.liaisons)
            // L'ancienne a été fermée proprement (« BYE! ») avant la nouvelle.
            assertTrue(generateSequence { lireMessage(e1) }.toList().contains("BYE!"))
            c1.close(); c2.close()
        } finally {
            serveur.close()
        }
    }

    @Test fun les_battements_de_music_assistant_gardent_la_liaison() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val serveur = ServerSocket(3483, 4, InetAddress.getByName("127.0.0.1"))
        serveur.soTimeout = 20_000
        try {
            LecteurSlim.sansCommandeMs = 600L
            LecteurSlim.demarrer(ctx, "127.0.0.1")
            val c1 = serveur.accept()
            val e1 = DataInputStream(c1.getInputStream())
            assertEquals("HELO", lireMessage(e1))
            val avant = LecteurSlim.liaisons
            // Un battement toutes les 200 ms pendant 3,5 s : bien plus que la limite de 600 ms, sans aucune coupure.
            val recus = mutableListOf<String>()
            val lecteur = Thread { generateSequence { lireMessage(e1) }.forEach { synchronized(recus) { recus.add(it) } } }.apply { isDaemon = true; start() }
            val coeur = Thread { try { repeat(18) { battement(c1); Thread.sleep(200) } } catch (_: Exception) {} }.apply { isDaemon = true; start() }
            Thread.sleep(2_000)
            assertTrue("vivante : une seule liaison", LecteurSlim.connecte)
            assertEquals(avant, LecteurSlim.liaisons)
            assertTrue("vivante : vérification immédiate", LecteurSlim.verifierLiaison("essai"))
            // Chaque battement a reçu sa réponse (STAT), aucun « BYE! ».
            val vus = synchronized(recus) { recus.toList() }
            assertTrue("réponses aux battements : $vus", vus.count { it == "STAT" } >= 7 && !vus.contains("BYE!"))
            // Tant que Music Assistant parle, aucune 2e liaison.
            serveur.soTimeout = 1_000
            try { serveur.accept(); throw AssertionError("aucune 2e liaison tant que Music Assistant parle") } catch (_: java.net.SocketTimeoutException) {}
            coeur.join(3_000)
            c1.close()
            lecteur.join(2_000)
        } finally {
            serveur.close()
        }
    }

    @Test fun au_reveil_ou_au_lancement_une_liaison_muette_est_refaite_tout_de_suite() {
        // Réveil de la télé, ou lancement de la soirée depuis la télé : on ne laisse pas courir le chien de garde.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val serveur = ServerSocket(3483, 4, InetAddress.getByName("127.0.0.1"))
        serveur.soTimeout = 20_000
        try {
            LecteurSlim.sansCommandeMs = 60_000L  // chien de garde loin
            LecteurSlim.demarrer(ctx, "127.0.0.1")
            val c1 = serveur.accept()
            val e1 = DataInputStream(c1.getInputStream())
            assertEquals("HELO", lireMessage(e1))
            assertTrue(LecteurSlim.attendreLiaison(3_000L))
            // Depuis, Music Assistant s'est tu plus longtemps que la limite (abaissée après coup pour l'essai).
            Thread.sleep(400)
            LecteurSlim.sansCommandeMs = 300L
            val t0 = System.nanoTime()
            assertFalse("muette : fermée", LecteurSlim.verifierLiaison("réveil de l'écran"))
            val c2 = serveur.accept()
            val dt = (System.nanoTime() - t0) / 1_000_000
            assertEquals("HELO", lireMessage(DataInputStream(c2.getInputStream())))
            assertTrue("refaite tout de suite : $dt ms", dt < 3_000)
            assertTrue(LecteurSlim.attendreLiaison(3_000L))
            assertTrue(generateSequence { lireMessage(e1) }.toList().contains("BYE!"))
            c1.close(); c2.close()
        } finally {
            serveur.close()
        }
    }

    @Test fun un_ecran_qui_se_rallume_verifie_la_liaison() {
        // Le récepteur ACTION_SCREEN_ON est posé au démarrage : au réveil, une liaison muette est refaite.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val serveur = ServerSocket(3483, 4, InetAddress.getByName("127.0.0.1"))
        serveur.soTimeout = 20_000
        try {
            LecteurSlim.sansCommandeMs = 60_000L
            LecteurSlim.demarrer(ctx, "127.0.0.1")
            val c1 = serveur.accept()
            assertEquals("HELO", lireMessage(DataInputStream(c1.getInputStream())))
            assertTrue(LecteurSlim.attendreLiaison(3_000L))
            Thread.sleep(400)
            LecteurSlim.sansCommandeMs = 300L
            ctx.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_SCREEN_ON))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val c2 = serveur.accept()
            assertEquals("HELO", lireMessage(DataInputStream(c2.getInputStream())))
            c1.close(); c2.close()
        } finally {
            serveur.close()
        }
    }
}
