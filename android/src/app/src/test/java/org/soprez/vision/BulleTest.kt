// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.85.1 : sur la télé, l'astuce « Accueil ×2 » se montre un temps, une fois par appli, puis plus tant que rien ne change. */
class BulleTest {
    @Test fun teleUneFoisParAppli() {
        assertEquals("montrer_un_temps", Bulle.decision(true, true, "fr.freebox.tv", null))
        assertEquals("laisser", Bulle.decision(true, true, "fr.freebox.tv", "fr.freebox.tv"))   // déjà montrée : le minuteur l'a effacée, elle ne revient pas
        assertEquals("montrer_un_temps", Bulle.decision(true, true, "com.netflix", "fr.freebox.tv"))
        assertEquals("retirer", Bulle.decision(false, true, "fr.freebox.tv", "fr.freebox.tv"))
    }

    /** Veille-lot : un blanc entre deux titres ou un écran de Vision traversé ne fait pas revenir l'astuce. */
    @Test fun blancCourtNeRamenePasLAstuce() {
        assertEquals("retirer_garder", Bulle.decision(false, true, "fr.freebox.tv", "fr.freebox.tv", 0L))
        assertEquals("retirer_garder", Bulle.decision(false, true, "fr.freebox.tv", "fr.freebox.tv", Bulle.OUBLI_MS - 1))
        // La mémoire est restée : le son reprend sur la même appli, l'astuce ne revient pas.
        assertEquals("laisser", Bulle.decision(true, true, "fr.freebox.tv", "fr.freebox.tv", 0L))
        // Une vraie absence (le son s'est arrêté pour de bon) : elle est oubliée, elle reviendra une fois.
        assertEquals("retirer", Bulle.decision(false, true, "fr.freebox.tv", "fr.freebox.tv", Bulle.OUBLI_MS))
        assertEquals("retirer", Bulle.decision(false, true, "fr.freebox.tv", null, 0L))
    }

    @Test fun telephoneInchange() {
        assertEquals("montrer", Bulle.decision(true, false, "com.spotify", "com.spotify"))
        assertEquals("retirer", Bulle.decision(false, false, "com.spotify", null))
    }
}
