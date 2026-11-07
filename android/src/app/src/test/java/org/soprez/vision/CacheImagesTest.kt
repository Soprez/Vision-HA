// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 1.94 (point 4) : le cache disque des images : purge LRU, plafond par famille, taille par défaut, « Vider ». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheImagesTest {
    private val mo = CacheImages.MO
    private fun e(nom: String, fam: String, octets: Long, acces: Long) = CacheImages.Entree(nom, fam, octets, acces)

    // ------------------------------------------------------------------ pur

    @Test fun taille_par_defaut_cinq_pour_cent_de_l_espace_libre_avec_plafond_et_plancher() {
        assertEquals(256L, CacheImages.tailleParDefautMo(100_000, 8_000, tele = false))   // plafond téléphone
        assertEquals(128L, CacheImages.tailleParDefautMo(100_000, 8_000, tele = true))    // plafond télé
        assertEquals(50L, CacheImages.tailleParDefautMo(1_000, 8_000, tele = false))      // 5 % de 1 000 Mo
        assertEquals(32L, CacheImages.tailleParDefautMo(100, 8_000, tele = false))        // plancher
    }

    @Test fun la_tcl_peu_de_memoire_et_peu_d_espace_garde_16_mo() {
        assertEquals(16L, CacheImages.tailleParDefautMo(300, 2_048, tele = true))         // 2 Go de mémoire vive : plancher de 16 Mo
        assertEquals(16L, CacheImages.tailleParDefautMo(100, 2_048, tele = true))
        assertEquals(32L, CacheImages.tailleParDefautMo(300, 4_096, tele = true))         // mémoire normale : plancher de 32 Mo
        assertEquals(32L, CacheImages.tailleParDefautMo(300, 0, tele = true))             // mémoire inconnue : pas de plancher réduit
    }

    @Test fun la_purge_retire_les_moins_recemment_lus() {
        val l = listOf(e("a", "notif", 40 * mo, 1), e("b", "pochette", 40 * mo, 3), e("c", "gif", 40 * mo, 2))
        // 120 Mo pour 100 : « a » (le plus ancien) part ; une seule suffit.
        assertEquals(listOf("a"), CacheImages.aRetirer(l, 100 * mo, plafondFamille = 1.0))
        assertEquals(emptyList<String>(), CacheImages.aRetirer(l, 120 * mo, plafondFamille = 1.0))
    }

    @Test fun une_famille_ne_depasse_pas_60_pour_cent_du_total() {
        // Un GIF lourd (70 Mo) dans un cache de 100 Mo : sa famille est ramenée à 60 Mo, les pochettes restent.
        val l = listOf(e("g1", "gif", 40 * mo, 1), e("g2", "gif", 30 * mo, 2), e("p1", "pochette", 20 * mo, 0), e("p2", "pochette", 10 * mo, 0))
        assertEquals(listOf("g1"), CacheImages.aRetirer(l, 100 * mo))
    }

    @Test fun rien_a_retirer_dans_les_bornes() {
        val l = listOf(e("a", "notif", 10 * mo, 1), e("b", "gif", 10 * mo, 2))
        assertEquals(emptyList<String>(), CacheImages.aRetirer(l, 100 * mo))
        assertEquals(emptyList<String>(), CacheImages.aRetirer(emptyList(), 100 * mo))
    }

    // ------------------------------------------------------------------ disque

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test fun ecrire_lire_et_vider() {
        CacheImages.vider(ctx)
        val octets = ByteArray(1000) { it.toByte() }
        assertNull(CacheImages.lire(ctx, CacheImages.NOTIF, "msg:abc"))
        CacheImages.ecrire(ctx, CacheImages.NOTIF, "msg:abc", octets)
        assertArrayEquals(octets, CacheImages.lire(ctx, CacheImages.NOTIF, "msg:abc"))
        assertNull("une autre famille ne partage pas la clé", CacheImages.lire(ctx, CacheImages.GIF, "msg:abc"))
        assertEquals(1000L, CacheImages.utilise(ctx))
        CacheImages.vider(ctx)
        assertNull(CacheImages.lire(ctx, CacheImages.NOTIF, "msg:abc"))
        assertEquals(0L, CacheImages.utilise(ctx))
    }

    @Test fun le_reglage_borne_le_cache_et_zero_veut_dire_automatique() {
        CacheImages.vider(ctx)
        CacheImages.poserReglageMo(ctx, 1)                       // 1 Mo : 600 ko au plus par famille
        assertEquals(1 * mo, CacheImages.tailleMaxOctets(ctx))
        repeat(5) { i -> CacheImages.ecrire(ctx, CacheImages.GIF, "g$i", ByteArray(300 * 1024)); File(ctx.cacheDir, "images").walkTopDown().filter { it.isFile }.forEach { } }
        assertTrue("le cache tient dans sa taille : ${CacheImages.utilise(ctx)}", CacheImages.utilise(ctx) <= 1 * mo)
        assertTrue("une famille reste sous 60 % : ${CacheImages.utilise(ctx)}", CacheImages.utilise(ctx) <= (0.6 * mo).toLong() + 1)
        CacheImages.poserReglageMo(ctx, 0)
        assertTrue("automatique : au moins le plancher", CacheImages.tailleMaxOctets(ctx) >= CacheImages.PLANCHER_PEU_DE_MEMOIRE_MO * mo)
        CacheImages.vider(ctx)
    }

    @Test fun les_ecrans_de_reglages_proposent_le_cache() {
        fun source(f: String) = listOf(File("src/main/java/org/soprez/vision/$f"), File("app/src/main/java/org/soprez/vision/$f")).first { it.isFile }.readText()
        assertTrue(source("ReglagesTvActivity.kt").contains("CacheImages.proposerVider"))
        assertTrue(source("SetupActivity.kt").contains("CacheImages.proposerVider"))
        assertTrue(source("MessagesRiches.kt").contains("CacheImages.ecrire(ctx, CacheImages.NOTIF"))
    }
}
