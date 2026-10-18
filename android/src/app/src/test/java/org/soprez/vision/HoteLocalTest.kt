// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.90.3 (télé d'essai) : reconnaître NOTRE Home Assistant sur le réseau local, et son adresse HTTPS. */
class HoteLocalTest {
    private val ext = "https://exemple.exemple.org"
    private fun a(vararg urls: String) = HoteLocal.Annonce("10.1.1.11", 8123, urls.toList())

    @Test fun le_notre_par_certificat_ou_adresse_annoncee() {
        // Rien d'annoncé, mais le certificat présenté sur l'IP est au nom de l'adresse externe connue.
        assertTrue(HoteLocal.estLaNotre(a(), listOf("exemple.exemple.org"), listOf(ext)))
        assertTrue(HoteLocal.estLaNotre(a(), listOf("*.exemple.org"), listOf(ext)))
        // L'adresse annoncée est celle de la config.
        assertTrue(HoteLocal.estLaNotre(a("https://exemple.exemple.org"), emptyList(), listOf(ext)))
        // Un autre Home Assistant du réseau : jamais.
        assertFalse(HoteLocal.estLaNotre(a("https://voisin.exemple.org"), listOf("voisin.exemple.org"), listOf(ext)))
        assertFalse(HoteLocal.estLaNotre(a(), emptyList(), listOf(ext)))
        // Sans adresse connue (pas relié) : jamais.
        assertFalse(HoteLocal.estLaNotre(a(), listOf("exemple.exemple.org"), emptyList()))
        // Un nom générique ne couvre qu'un niveau.
        assertFalse(HoteLocal.couvre("*.org", "exemple.exemple.org"))
    }

    @Test fun candidats_https_depuis_le_certificat() {
        assertEquals(listOf("https://exemple.exemple.org", "https://exemple.exemple.org:8123"),
            HoteLocal.candidats(listOf("exemple.exemple.org", "*.exemple.org"), 8123))
        assertEquals(listOf("https://exemple.exemple.org"), HoteLocal.candidats(listOf("Exemple.exemple.org"), 443))
        assertEquals(emptyList<String>(), HoteLocal.candidats(listOf("*.exemple.org", "localhost"), 8123))
    }
}
