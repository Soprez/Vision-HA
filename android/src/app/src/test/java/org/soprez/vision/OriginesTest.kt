// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.94 · Découverte appli → sites : le relevé « origines » (200 domaines au plus, des comptes, jamais d'URL). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OriginesTest {
    @Before fun avant() = Origines.vider()
    @After fun apres() = Origines.vider()

    @Test fun une_requete_attribuee_compte_pour_son_appli_avec_ses_dates() {
        Origines.noter("CDN.Exemple.com.", listOf("com.exemple.jeu"), "com.autre", bloque = false, maintenant = 1_000)
        Origines.noter("cdn.exemple.com", listOf("com.exemple.jeu"), "com.autre", bloque = false, maintenant = 2_000)
        val e = Origines.releve().getJSONObject("cdn.exemple.com").getJSONObject("com.exemple.jeu")
        assertEquals(2, e.getInt("n")); assertEquals(0, e.getInt("incertain")); assertEquals(0, e.getInt("bloque"))
        assertEquals(1_000L, e.getLong("premier")); assertEquals(2_000L, e.getLong("dernier"))
    }

    @Test fun une_requete_sans_appli_compte_a_part_pour_l_appli_au_premier_plan() {
        Origines.noter("linkedin.com", emptyList(), "com.discord", bloque = false)
        val e = Origines.releve().getJSONObject("linkedin.com").getJSONObject("com.discord")
        assertEquals(0, e.getInt("n")); assertEquals(1, e.getInt("incertain"))   // jamais un lien : HA ne compte que « n »
    }

    @Test fun sans_appli_ni_premier_plan_la_requete_est_perdue() {
        Origines.noter("orphelin.exemple", emptyList(), "", bloque = false)
        Origines.noter("orphelin.exemple", emptyList(), null, bloque = false)
        assertEquals(0, Origines.releve().length())
    }

    @Test fun un_uid_partage_compte_pour_chacun_de_ses_paquets() {
        Origines.noter("meta.exemple", listOf("com.exemple.a", "com.exemple.b"), null, bloque = false)
        val d = Origines.releve().getJSONObject("meta.exemple")
        assertEquals(1, d.getJSONObject("com.exemple.a").getInt("n")); assertEquals(1, d.getJSONObject("com.exemple.b").getInt("n"))
    }

    @Test fun une_requete_bloquee_est_comptee_et_retient_son_appli() {
        Origines.noter("pistage.exemple", listOf("com.exemple.a"), null, bloque = true)
        assertEquals("com.exemple.a", Origines.appBloquante("PISTAGE.exemple."))
        assertEquals(1, Origines.releve().getJSONObject("pistage.exemple").getJSONObject("com.exemple.a").getInt("bloque"))
        assertNull("effacée au relevé", Origines.appBloquante("pistage.exemple"))
        // Incertaine : comptée bloquée, mais l'appli n'est pas retenue (alertes[].app reste absent).
        Origines.noter("pistage2.exemple", emptyList(), "com.exemple.a", bloque = true)
        assertNull(Origines.appBloquante("pistage2.exemple"))
    }

    @Test fun jamais_d_url_de_chemin_ni_de_port() {
        for (h in listOf("https://exemple.com/chemin?x=1", "exemple.com/chemin", "exemple.com:8080", "nom sans point", "a b.com", "x", "", "exemple.com?x")) {
            Origines.noter(h, listOf("com.exemple.a"), null, bloque = false)
        }
        assertEquals(0, Origines.releve().length())
        assertTrue(Origines.hoteValide("cdn-1.exemple.co.uk")); assertFalse(Origines.hoteValide("http://x.com"))
    }

    @Test fun le_releve_garde_les_200_domaines_les_plus_demandes_et_repart_de_zero() {
        for (i in 0 until 300) repeat(1 + i) { Origines.noter("d$i.exemple", listOf("com.exemple.a"), null, bloque = false) }
        val r = Origines.releve()
        assertEquals(Origines.MAX_RELEVE, r.length())
        assertTrue(r.has("d299.exemple")); assertFalse("les moins demandés sont écartés", r.has("d0.exemple"))
        assertEquals(0, Origines.releve().length())
    }
}
