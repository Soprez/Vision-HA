// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** « Signaler un problème » : les écrans (réservé aux responsables, intro, enregistrement en cours, aperçu). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR")
class SignalerActivityTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun avant() { Signalement.oublier(); fonctions(emptySet()) }
    @After fun apres() { Signalement.oublier() }

    private fun fonctions(f: Set<String>) {
        ctx.getSharedPreferences("profil", android.content.Context.MODE_PRIVATE).edit().putStringSet("fonctions", f).commit()
    }

    private fun textes(v: View, sortie: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView && v.text.isNotEmpty()) sortie += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    private fun ecran(a: Activity) = textes(a.findViewById<ViewGroup>(android.R.id.content))

    private fun ouvrir(): SignalerActivity = Robolectric.buildActivity(SignalerActivity::class.java).create().start().resume().get()

    @Test fun un_demandeur_voit_que_l_ecran_est_reserve_et_n_a_pas_de_bouton_commencer() {
        val t = ecran(ouvrir())
        assertTrue(t.toString(), t.any { it.startsWith("Réservé aux") })
        assertFalse(t.any { it == ctx.getString(R.string.signaler_commencer) })
    }

    @Test fun un_decideur_voit_l_explication_et_le_bouton_commencer() {
        fonctions(setOf("gerer"))
        val t = ecran(ouvrir())
        assertTrue(t.any { it.contains("Rien ne part") || it.contains("Nothing leaves") })
        assertTrue(t.contains(ctx.getString(R.string.signaler_commencer)))
        assertFalse(t.contains(ctx.getString(R.string.signaler_terminer)))
    }

    @Test fun pendant_un_enregistrement_l_ecran_propose_terminer() {
        fonctions(setOf("approuver"))
        Signalement.commencer()
        val t = ecran(ouvrir())
        assertTrue(t.contains(ctx.getString(R.string.signaler_terminer)))
        assertFalse(t.contains(ctx.getString(R.string.signaler_commencer)))
    }

    @Test fun l_apercu_montre_les_comptes_les_captures_et_les_trois_actions() {
        fonctions(setOf("gerer"))
        val jpeg = CaptureEcran.jpeg(Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888))
        val b = Signalement.Brouillon(1_000L, 2_000L, "a\nb\nc", "d\ne", Signalement.fiche(ctx), 7, listOf(jpeg, jpeg))
        val a = ouvrir()
        a.apercuDe(b)
        val t = ecran(a)
        assertTrue(t.toString(), t.contains("Journaux : 5 lignes · 7 remplacements"))
        assertEquals(2, t.count { it == ctx.getString(R.string.signaler_retirer) })
        for (id in listOf(R.string.signaler_voir, R.string.signaler_envoyer, R.string.signaler_jeter)) assertTrue(t.contains(ctx.getString(id)))
        assertFalse("pas de recontact", t.any { it.contains("recontact", ignoreCase = true) })
    }
}
