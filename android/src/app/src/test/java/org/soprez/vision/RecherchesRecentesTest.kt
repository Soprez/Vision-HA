// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.AlertDialog
import android.content.DialogInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * 1.93.05 (l'auteur, retour 32, TV du salon) : l'entrée « Recherches récentes » des réglages de la musique effaçait toutes les
 * recherches dès le clic. Elle s'appelle maintenant « Effacer les recherches récentes » et demande confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecherchesRecentesTest {
    private fun entree(act: android.app.Activity): Pair<String, () -> Unit> {
        var trouvee: Pair<String, () -> Unit>? = null
        ReglagesMusique.lignes(act, { titre, _, tag, action -> if (tag == "mus_recents") trouvee = titre to action }, {})
        return trouvee ?: error("pas d'entrée des recherches récentes")
    }

    @Test fun rien_n_est_efface_sans_confirmation() {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        RechercheMusique.noterRecent(act, "daft punk"); RechercheMusique.noterRecent(act, "groovy")
        val (titre, action) = entree(act)
        assertEquals(act.getString(R.string.musrech_recents), titre)
        assertEquals(true, titre.startsWith("Clear") || titre.startsWith("Effacer"))
        action()
        ShadowLooper.idleMainLooper()
        // Le clic ouvre la confirmation, et n'efface rien.
        val d = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("pas de confirmation", d)
        assertEquals(2, RechercheMusique.recents(act).size)
        // Annuler : rien n'est effacé.
        d!!.getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(2, RechercheMusique.recents(act).size)
        // Effacer : tout est effacé.
        entree(act).second()
        ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(0, RechercheMusique.recents(act).size)
    }
}
