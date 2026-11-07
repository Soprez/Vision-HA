// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.94 · Découverte appli → sites : l'attribution d'une requête DNS à son appli (JVM pur ; la mesure réelle est le prototype D0). */
class AttributionUidTest {
    private val PROC_UDP = """
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops
 1500: 0100000A:D3A4 3500006F:0035 01 00000000:00000000 00:00000000 00000000 10234        0 54321 2 0000000000000000 0
 1501: 0100000A:C350 3500006F:0035 01 00000000:00000000 00:00000000 00000000 10311        0 54322 2 0000000000000000 0
 1502: 00000000:0044 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 11 2 0000000000000000 0
 ligne illisible
"""

    @Test fun le_texte_de_proc_net_udp_donne_l_uid_par_port_local() {
        val t = AttributionUid.parseProcNet(PROC_UDP)
        assertEquals(10234, t[0xD3A4])   // 54180
        assertEquals(10311, t[0xC350])   // 50000
        assertEquals(0, t[0x44])
        assertEquals(3, t.size)          // l'en-tête et la ligne illisible sont ignorés
    }

    @Test fun udp6_a_des_adresses_de_32_chiffres_et_le_meme_format() {
        val t = AttributionUid.parseProcNet("  sl local_address rem_address st tx rx tr retr uid timeout inode\n" +
            "  7: 0000000000000000FFFF00000100000A:B1B1 00000000000000000000000000000000:0000 07 00:00 00:0 00 10456 0 99 2 0 0\n")
        assertEquals(10456, t[0xB1B1])
    }

    @Test fun un_uid_normal_donne_ses_paquets_et_un_uid_partage_en_donne_plusieurs() {
        assertEquals(listOf("com.snapchat.android"), AttributionUid.attribuer(10234) { listOf("com.snapchat.android") }.paquets)
        val partage = AttributionUid.attribuer(10311) { listOf("com.exemple.a", "com.exemple.b") }
        assertEquals(listOf("com.exemple.a", "com.exemple.b"), partage.paquets)
        assertFalse(partage.incertain)
    }

    @Test fun uid_invalide_systeme_ou_sans_paquet_donne_une_requete_incertaine() {
        for (uid in listOf(AttributionUid.INVALID_UID, -5, 0, 1000, 1051)) {
            assertTrue("uid $uid", AttributionUid.attribuer(uid) { listOf("com.exemple.a") }.incertain)
        }
        assertTrue(AttributionUid.attribuer(10999) { emptyList() }.incertain)
        assertTrue(AttributionUid.attribuer(10999) { listOf("", " ") }.incertain)
    }

    @Test fun le_cache_garde_un_port_une_seconde() {
        var t = 1_000L
        var appels = 0
        val c = AttributionUid.Cache(1_000L) { t }
        assertEquals(10234, c.uid(54180) { appels++; 10234 })
        assertEquals(10234, c.uid(54180) { appels++; 99999 })   // même port, même seconde : pas de nouvel appel
        assertEquals(1, appels)
        t += 999
        c.uid(54180) { appels++; 10234 }
        assertEquals(1, appels)
        t += 2
        assertEquals(555, c.uid(54180) { appels++; 555 })        // une seconde plus tard : redemandé
        assertEquals(2, appels)
        assertEquals(777, c.uid(54181) { appels++; 777 })        // un autre port : redemandé
        assertEquals(3, appels)
    }

    @Test fun le_mode_dit_comment_l_appareil_attribue() {
        assertEquals("uid", AttributionUid.mode(34, false))
        assertEquals("uid", AttributionUid.mode(29, true))
        assertEquals("proc", AttributionUid.mode(28, true))
        assertEquals("proc", AttributionUid.mode(21, true))
        assertEquals("degrade", AttributionUid.mode(28, false))
    }

    @Test fun les_adresses_du_paquet_pour_getConnectionOwnerUid() {
        val (local, distant) = AttributionUid.adresses(byteArrayOf(10, 111, 0, 1), 54180, "10.111.0.53")
        assertEquals("10.111.0.1", local.address.hostAddress); assertEquals(54180, local.port)
        assertEquals("10.111.0.53", distant.address.hostAddress); assertEquals(53, distant.port)
    }
}
