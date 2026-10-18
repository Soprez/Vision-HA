// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Retours de l'auteur (15-10) : le clavier Android ne convient pas à la télé. Sur la télé, la recherche de musique a son
 * clavier à elle : une grille ABC de 7 colonnes toujours ouverte à côté des résultats (flèches pour se déplacer, OK
 * tape la lettre), qui se met à jour à chaque frappe. Une page « 123 · éàç » pour les chiffres et les accents.
 */
class ClavierTele(ctx: Context, private val theme: Theme, private val taille: Float,
                  private val taper: (String) -> Unit, private val effacer: () -> Unit, private val toutEffacer: () -> Unit,
                  private val versLaDroite: () -> android.view.View? = { null },
                  /** Des signes en plus sur la page « 123 » (une adresse : « / », « : »…) ; la musique n'en a pas. */
                  private val signesEnPlus: List<String> = emptyList(),
                  /** Ouvrir sur la page des chiffres (un code, un nombre). */
                  chiffres: Boolean = false) : LinearLayout(ctx) {

    companion object {
        const val COLONNES = 7
        val LETTRES = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ".map { it.toString() } + listOf("'", "-"))
        val SIGNES = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "é", "è", "ê", "à", "â", "ç", "ù", "û", "ô", "î", "ï", "ë", "&", "!", "?", ".", ",", "ñ")
        /** Pour une adresse ou un chemin (« /lovelace/salon », « https://… »). */
        val SIGNES_ADRESSE = listOf("/", ":", "_", "@", "#", "+", "=")

        /**
         * 16-10 (l'auteur, télé du salon : « c'est encore le clavier Android qui s'ouvre ») : sur la télé, un champ de saisie
         * ouvre le clavier de Vision (celui de la recherche de musique) dans une fenêtre, au lieu du clavier Android.
         * Ailleurs (téléphone, tablette) : rien ne change.
         */
        fun brancher(champ: android.widget.EditText) {
            val ctx = champ.context
            if (!ReglagesTvActivity.estTele(ctx)) return
            champ.showSoftInputOnFocus = false
            champ.isFocusable = true; champ.isFocusableInTouchMode = false
            champ.setOnClickListener { saisir(champ) }
            champ.setOnKeyListener { _, code, e ->
                val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (ok && e.action == android.view.KeyEvent.ACTION_UP) saisir(champ)
                ok
            }
        }

        /** La fenêtre de saisie : le texte en cours, le clavier de Vision ; « OK » le rend au champ. */
        fun saisir(champ: android.widget.EditText) {
            val a = champ.context as? android.app.Activity ?: return
            val th = Ui.themeActif(a)
            val tampon = StringBuilder(champ.text?.toString().orEmpty())
            val chiffres = (champ.inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER
            val masque = chiffres && (champ.inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            val d = { v: Float -> Ui.dp(a, v) }
            val affiche = Ui.sansCesure(TextView(a)).apply {
                textSize = 22f; setTextColor(th.encre); typeface = Polices.demiGras(a); setPadding(d(14f), d(10f), d(14f), d(10f))
                background = GradientDrawable().apply { cornerRadius = d(12f).toFloat(); setColor(th.carte); setStroke(d(1f), th.carteBord) }
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE; tag = "clavier_texte"
            }
            fun maj() {
                val t = if (masque) "•".repeat(tampon.length) else tampon.toString()  // i18n-exception : points d'un code masqué
                affiche.text = if (t.isEmpty()) (champ.hint ?: "") else "$t▏"  // i18n-exception : curseur
                affiche.setTextColor(if (t.isEmpty()) th.encre3 else th.encre)
            }
            maj()
            val adresse = (champ.inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_URI
            val clavier = ClavierTele(a, th, 18f, { c -> tampon.append(c); maj() }, { if (tampon.isNotEmpty()) tampon.setLength(tampon.length - 1); maj() },
                { tampon.setLength(0); maj() }, signesEnPlus = if (adresse) SIGNES_ADRESSE else emptyList(), chiffres = chiffres).apply { tag = "clavier_tele" }
            val col = LinearLayout(a).apply { orientation = VERTICAL; setPadding(d(20f), d(8f), d(20f), d(4f)) }
            col.addView(affiche, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(clavier, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(10f) })
            val titre = champ.hint?.toString()?.takeIf { it.isNotBlank() } ?: a.getString(R.string.clavier_saisie)
            val fenetre = Ui.dialogue(a).setTitle(titre).setView(android.widget.ScrollView(a).apply { addView(col) })
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    champ.setText(tampon.toString()); champ.setSelection(champ.text?.length ?: 0)
                }
                .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).create()
            fenetre.setOnShowListener { clavier.premiere()?.requestFocus() }
            fenetre.show()
        }
    }

    private var signes = chiffres

    init { orientation = VERTICAL; construire() }

    private fun px(v: Float) = Ui.dp(context, v)

    private fun touche(t: String, description: String, poids: Float, action: () -> Unit): TextView =
        Ui.sansCesure(TextView(context).apply {
            text = t; textSize = taille; setTextColor(theme.encre); typeface = Polices.demiGras(context); gravity = Gravity.CENTER
            minHeight = px(40f); isFocusable = true; isClickable = true; contentDescription = description
            background = GradientDrawable().apply { cornerRadius = px(8f).toFloat(); setColor(Palette.melanger(theme.carte, theme.encre, 0.04f)) }
            Ui.anneauFocus(this, theme, 8f, agrandir = false)
            setOnClickListener { action() }
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, poids).apply { setMargins(px(2f), px(2f), px(2f), px(2f)) }
        })

    private fun construire() {
        val focus = findFocus()?.contentDescription?.toString()
        removeAllViews()
        (if (signes) SIGNES + signesEnPlus else LETTRES).chunked(COLONNES).forEach { rang ->
            val r = LinearLayout(context)
            rang.forEach { c -> r.addView(touche(c, c, 1f) { taper(if (signes) c else c.lowercase()) }) }
            sortieADroite(r.getChildAt(r.childCount - 1))
            addView(r)
        }
        val bas = LinearLayout(context)
        bas.addView(touche(context.getString(R.string.musrech_espace), context.getString(R.string.musrech_espace), 2f) { taper(" ") })
        bas.addView(touche("⌫", context.getString(R.string.musrech_effacer_lettre), 1f) { effacer() })  // i18n-exception : pictogramme
        bas.addView(touche(context.getString(R.string.musrech_effacer_tout), context.getString(R.string.musrech_effacer_tout), 2f) { toutEffacer() })
        val bascule = if (signes) "ABC" else "123 · éàç"  // i18n-exception : pages du clavier (lettres, chiffres et accents)
        bas.addView(touche(bascule, context.getString(if (signes) R.string.musrech_page_lettres else R.string.musrech_page_signes), 2f) {
            signes = !signes; construire(); post { (getChildAt(childCount - 1) as? ViewGroup)?.getChildAt(3)?.requestFocus() }
        })
        sortieADroite(bas.getChildAt(bas.childCount - 1))
        addView(bas)
        if (focus != null) post { trouver(this, focus)?.requestFocus() }
    }

    /** La télécommande : ► depuis la dernière colonne mène aux résultats (sinon Android choisissait le micro, au-dessus). */
    private fun sortieADroite(v: android.view.View) {
        v.setOnKeyListener { _, code, e ->
            if (code != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            val cible = versLaDroite() ?: return@setOnKeyListener false
            if (e.action == android.view.KeyEvent.ACTION_DOWN) cible.requestFocus()
            true
        }
    }

    private fun trouver(v: android.view.View, d: String): android.view.View? {
        if (v.isFocusable && v.contentDescription?.toString() == d) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) trouver(v.getChildAt(i), d)?.let { return it }
        return null
    }

    /** La première touche (« A »), pour y poser le focus à l'ouverture. */
    fun premiere(): android.view.View? = (getChildAt(0) as? ViewGroup)?.getChildAt(0)
}
