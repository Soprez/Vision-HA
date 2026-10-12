// SPDX-License-Identifier: MIT
package org.soprez.vision

/** Les états météo de Home Assistant, dans la langue de l'appareil. */
object Meteo {
    fun libelle(etat: String): String = when (etat) {
        "sunny" -> Tr.s(R.string.meteo_sunny)
        "clear-night" -> Tr.s(R.string.meteo_clear_night)
        "partlycloudy" -> Tr.s(R.string.meteo_partlycloudy)
        "cloudy" -> Tr.s(R.string.meteo_cloudy)
        "fog" -> Tr.s(R.string.meteo_fog)
        "rainy" -> Tr.s(R.string.meteo_rainy)
        "pouring" -> Tr.s(R.string.meteo_pouring)
        "lightning", "lightning-rainy" -> Tr.s(R.string.meteo_lightning)
        "snowy" -> Tr.s(R.string.meteo_snowy)
        "snowy-rainy" -> Tr.s(R.string.meteo_snowy_rainy)
        "hail" -> Tr.s(R.string.meteo_hail)
        "windy", "windy-variant" -> Tr.s(R.string.meteo_windy)
        "exceptional" -> Tr.s(R.string.meteo_exceptional)
        else -> etat.replaceFirstChar { it.uppercase() }
    }
}
