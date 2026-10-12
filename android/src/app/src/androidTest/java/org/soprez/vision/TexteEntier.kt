// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.view.View
import android.widget.TextView

/**
 * 1.87.0 : un texte qui finit par « … » est admis quand son texte entier est dit ailleurs (contentDescription de la vue
 * ou d'un parent proche) : c'est le cas des noms Home Assistant trop longs, qui ne se coupent plus au milieu d'un mot.
 */
internal fun texteEntierDit(tv: TextView): Boolean {
    val t = tv.text.toString()
    var p: Any? = tv; var n = 0
    while (p is View && n < 4) { if (p.contentDescription?.toString()?.contains(t) == true) return true; p = p.parent; n++ }
    return false
}
