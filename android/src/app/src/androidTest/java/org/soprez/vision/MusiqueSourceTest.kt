// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.test.InstrumentationTestCase
import org.json.JSONObject

/** 1.85.0 : la recherche de musique porte sur tout le catalogue ; chaque résultat dit d'où il vient, et l'appli montre l'explication de Home Assistant. */
class MusiqueSourceTest : InstrumentationTestCase() {
    fun testSourceEtConseil() {
        val r = JSONObject("""{"ok":true,"resultats":{"track":[{"uri":"ytmusic--x://track/1","nom":"Get Lucky","type":"track","artiste":"Daft Punk","source":"YouTube Music"},
            {"uri":"library://track/2","nom":"Around the World","type":"track","artiste":"Daft Punk","source":"Bibliothèque"}]},
            "sources":[{"nom":"YouTube Music","n":1}],"vides":["Spotify"],"conseil":"Spotify : rien trouvé pour « daft punk » (ou service injoignable)."}""")
        val l = Party.analyserChoix(r)
        assertEquals(listOf("YouTube Music", "Bibliothèque"), l.map { it.source })
        assertTrue(Party.conseilRecherche(r).startsWith("Spotify : rien trouvé"))
        // Une ancienne réponse (sans source) reste lisible.
        assertEquals("", Party.analyserChoix(JSONObject("""{"items":[{"uri":"library://playlist/1","nom":"Disney","type":"playlist"}]}""")).single().source)
    }
}
