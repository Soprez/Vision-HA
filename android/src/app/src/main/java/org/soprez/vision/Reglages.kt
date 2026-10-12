// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONObject

/**
 * Les réglages de l'écran de veille tels que la télé vient de les changer :
 * un point de rendez-vous entre l'écran de réglages, l'accueil et l'écran de
 * veille, pour que le thème ou la radio changent tout de suite, sans attendre
 * la prochaine lecture de Home Assistant (une minute).
 */
object Reglages {
    @Volatile var json: JSONObject? = null
    @Volatile var quand = 0L
    @Volatile var version = 0

    fun poser(j: JSONObject) {
        json = j
        quand = System.currentTimeMillis()
        version++
    }

    /** Les réglages frais si la télé en a changé depuis la dernière lecture de Home Assistant. */
    fun plusRecentsQue(lectureMs: Long): JSONObject? = json?.takeIf { quand > lectureMs }
}
