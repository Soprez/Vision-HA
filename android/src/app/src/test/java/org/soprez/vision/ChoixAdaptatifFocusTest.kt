// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.94 (l'auteur, TCL du salon : « menu, onglet Musique, quand on descend, il y a un moment, on revient à l'accueil »).
 * Cause : une passe de mesure sans largeur connue (UNSPECIFIED) faisait passer la liste en étiquettes, puis de retour en liste ;
 * le champ qui avait le focus était retiré et Android rendait le focus au premier élément de la fenêtre (le menu, « Accueil »).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChoixAdaptatifFocusTest {
    private fun composant(): Triple<FrameLayout, ChoixAdaptatif, android.app.Activity> {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        // Des libellés très longs : leur largeur dépasse 300 px quelle que soit la police de l'essai (la mesure du texte y est factice).
        val long = { t: String -> (t + " ").repeat(40) }
        val options = listOf("toujours" to long("Mettre la musique en pause"), "applis" to long("Seulement pour certaines applis"), "jamais" to long("Jamais"))
            .map { ChoixAdaptatif.Option(it.first, it.second) }
        val c = ChoixAdaptatif(act, "Quand une autre appli joue du son", options, setOf("toujours")) { }
        val parent = FrameLayout(act).apply { addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        act.setContentView(parent, ViewGroup.LayoutParams(600, 800))
        return Triple(parent, c, act)
    }

    private fun mesurer(p: View, largeur: Int, mode: Int) {
        p.measure(View.MeasureSpec.makeMeasureSpec(largeur, mode), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        p.layout(0, 0, p.measuredWidth, p.measuredHeight)
    }

    @Test fun une_passe_sans_largeur_ne_change_pas_le_mode_et_garde_le_focus() {
        val (parent, c, _) = composant()
        mesurer(parent, 300, View.MeasureSpec.EXACTLY)
        assertFalse("trois libellés longs dans 300 px : une liste", c.montreEtiquettes())
        val champ = c.findViewWithTag<View>("choix_liste")
        assertNotNull(champ)
        champ.isFocusableInTouchMode = true
        champ.requestFocus()
        assertTrue(champ.hasFocus())
        // La passe « sans largeur » : avant le correctif, le mode passait en étiquettes et le champ perdait le focus.
        mesurer(parent, 0, View.MeasureSpec.UNSPECIFIED)
        assertFalse("une passe sans largeur ne change pas le mode", c.montreEtiquettes())
        assertTrue("le champ garde le focus", champ.hasFocus() && champ.parent === c)
        mesurer(parent, 300, View.MeasureSpec.EXACTLY)
        assertTrue("le champ garde le focus après la passe suivante", champ.hasFocus())
    }

    @Test fun un_vrai_changement_de_largeur_garde_le_focus() {
        val (parent, c, _) = composant()
        mesurer(parent, 300, View.MeasureSpec.EXACTLY)
        val champ = c.findViewWithTag<View>("choix_liste")
        champ.isFocusableInTouchMode = true; champ.requestFocus()
        // Très large : les étiquettes tiennent ; le focus passe à la première étiquette, il ne part pas ailleurs.
        mesurer(parent, 600000, View.MeasureSpec.EXACTLY)
        assertTrue(c.montreEtiquettes())
        assertTrue("le focus reste dans le composant", c.hasFocus())
    }
}
