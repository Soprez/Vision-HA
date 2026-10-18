// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Règle UX de l'auteur (15-10), pour toute l'appli : un choix parmi des options s'affiche
 * - en ÉTIQUETTES quand il y a 3 options ou moins ET qu'elles tiennent sur une ligne ;
 * - sinon (plus de 3 options, ou débordement de la largeur — mesuré à l'exécution, pas seulement compté) en LISTE
 *   DÉROULANTE : un champ qui montre bien l'option choisie et ouvre la liste.
 * La décision est reprise à chaque mesure (rotation, police agrandie, autre écran).
 *
 * Un seul composant pour tous les écrans (et la Musique) : choix unique ou multiple, icône par option, focus de la
 * télécommande (anneau or, OK ouvre la liste ; gauche / droite passent d'une étiquette à l'autre), libellé lu par les
 * lecteurs d'écran (« titre : valeur »), rappel à chaque changement.
 *
 *   Ui.choix(ctx, titre, options, choisie) { cle -> … }                     // choix unique
 *   ChoixAdaptatif(ctx, titre, options, setOf(…), multiple = true) { cles -> … }
 */
@SuppressLint("ViewConstructor")
class ChoixAdaptatif(
    ctx: Context,
    private val titre: String,
    options: List<Option>,
    choisies: Set<String>,
    private val multiple: Boolean = false,
    private val theme: Theme = Local.themeEffectif(ctx, Palette.APPLI),
    /** Taille du texte (sp) ; la télé passe la sienne. */
    private val tailleSp: Float = 14f,
    /** Au plus d'étiquettes (3 par la règle du projet ; jamais plus). */
    private val maxEtiquettes: Int = MAX_ETIQUETTES,
    /** Choix multiple : au plus tant d'options cochées ([trop] est appelé quand on veut aller au-delà). */
    private val maxChoix: Int = Int.MAX_VALUE,
    private val trop: (() -> Unit)? = null,
    private val change: (Set<String>) -> Unit,
) : FrameLayout(ctx) {

    /** Une option : sa clé (rendue au rappel), son libellé, une icône facultative, et ce que dit un lecteur d'écran. */
    data class Option(val cle: String, val libelle: String, val icone: Drawable? = null, val description: String? = null)

    companion object {
        const val MAX_ETIQUETTES = 3

        /** Étiquettes ou liste ? Pur (essais JVM) : [largeurEtiquettes] la largeur qu'il leur faut, [dispo] la place. */
        fun enEtiquettes(nombre: Int, largeurEtiquettes: Int, dispo: Int, max: Int = MAX_ETIQUETTES): Boolean =
            nombre in 1..max && (dispo <= 0 || largeurEtiquettes <= dispo)

        /**
         * Une fenêtre de réglages faite de choix : pour chaque bloc, son intitulé puis son composant (ChoixAdaptatif ou
         * autre vue). Les changements s'appliquent tout de suite ; « Fermer » ; un bouton neutre facultatif.
         */
        fun fenetre(act: android.app.Activity, titre: String, blocs: List<Pair<String, View>>, neutre: Pair<String, () -> Unit>? = null,
                    fermee: () -> Unit = {}): android.app.AlertDialog {
            val th = Local.themeEffectif(act, Palette.APPLI)
            val d = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.resources.displayMetrics).toInt() }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d(24f), d(8f), d(24f), d(8f)); clipChildren = false; clipToPadding = false }
            blocs.forEach { (intitule, vue) ->
                col.addView(Ui.sansCesure(TextView(act)).apply { text = intitule; textSize = 13f; setTextColor(th.encre2); typeface = Polices.demiGras(act); setPadding(0, d(12f), 0, d(6f)) })
                col.addView(vue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val defile = android.widget.ScrollView(act).apply { addView(col) }
            val b = Ui.dialogue(act).setTitle(titre).setView(defile).setNegativeButton(act.getString(R.string.commun_fermer), null)
            neutre?.let { (t, action) -> b.setNeutralButton(t) { _, _ -> action() } }
            return b.create().apply { setOnDismissListener { fermee() }; show() }
        }

        /** Le texte du champ de la liste : l'option choisie, ou « A, B +2 » en choix multiple ; [aucune] si rien. Pur. */
        fun resume(libelles: List<String>, aucune: String, max: Int = 2): String = when {
            libelles.isEmpty() -> aucune
            libelles.size <= max -> libelles.joinToString(", ")
            else -> libelles.take(max).joinToString(", ") + " +" + (libelles.size - max)
        }
    }

    private var options: List<Option> = options
    private var choix: LinkedHashSet<String> = LinkedHashSet(choisies.filter { c -> options.any { it.cle == c } })
    private val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
    private var modeEtiquettes: Boolean? = null
    private val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    private val champ: TextView = Ui.sansCesure(TextView(ctx)).apply {
        textSize = tailleSp; setTextColor(theme.encre); typeface = Polices.demiGras(ctx)
        gravity = Gravity.CENTER_VERTICAL; minHeight = dp(Ui.CIBLE_MIN)
        setPadding(dp(14f), dp(8f), dp(12f), dp(8f))
        isFocusable = true; isClickable = true; tag = "choix_liste"
        setOnClickListener { ouvrirListe() }
    }

    init {
        clipChildren = false; clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        Ui.anneauFocus(champ, theme, 12f, agrandir = false)
        remplirEtiquettes()
        majChamp()
    }

    /** Ce qui est choisi (clés). */
    val choisies: Set<String> get() = LinkedHashSet(choix)

    /** Change les options (une liste reçue plus tard, par exemple les sources de Music Assistant) ; garde le choix possible. */
    fun options(nouvelles: List<Option>, choisies: Set<String> = choix) {
        options = nouvelles
        choix = LinkedHashSet(choisies.filter { c -> nouvelles.any { it.cle == c } })
        remplirEtiquettes(); majChamp(); modeEtiquettes = null; requestLayout()
    }

    /** Vrai si le composant montre des étiquettes (essais). */
    fun montreEtiquettes(): Boolean = modeEtiquettes == true

    // ------------------------------------------------------------------ étiquettes

    private fun etiquette(o: Option): TextView = Ui.sansCesure(TextView(context)).apply {
        text = o.libelle; textSize = tailleSp; typeface = Polices.demiGras(context)
        gravity = Gravity.CENTER; minHeight = dp(Ui.CIBLE_MIN); minWidth = dp(48f)
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        o.icone?.let { ic -> val t = dp(18f); ic.setBounds(0, 0, t, t); setCompoundDrawablesRelative(ic, null, null, null); compoundDrawablePadding = dp(6f) }
        isFocusable = true; isClickable = true; tag = "choix_" + o.cle
        contentDescription = o.description ?: o.libelle
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true; info.isChecked = o.cle in choix
                info.className = if (multiple) android.widget.CheckBox::class.java.name else android.widget.RadioButton::class.java.name
            }
        }
        Ui.anneauFocus(this, theme, 999f, agrandir = false)
        setOnClickListener { choisir(o.cle) }
    }

    private fun remplirEtiquettes() {
        rangee.removeAllViews()
        options.forEachIndexed { i, o ->
            rangee.addView(etiquette(o), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(8f) })
        }
        peindreEtiquettes()
    }

    private fun peindreEtiquettes() {
        for (i in 0 until rangee.childCount) {
            val t = rangee.getChildAt(i) as TextView
            val o = options.getOrNull(i) ?: continue
            val pris = o.cle in choix
            t.setTextColor(if (pris) encreSur(theme.or) else theme.encre)
            t.background = GradientDrawable().apply {
                cornerRadius = dp(999f).toFloat()
                setColor(if (pris) theme.or else theme.carte)
                setStroke(dp(1f), if (pris) theme.or else theme.carteBord)
            }
            t.isSelected = pris
        }
    }

    /** Une copie d'icône pour une autre vue ; une icône dessinée à la main (sans état partagé) est reprise telle quelle. */
    private fun copie(d: android.graphics.drawable.Drawable) = (d.constantState?.newDrawable() ?: d).mutate()

    /** Le texte le plus lisible sur [fond] : le fond du thème ou son encre, celui qui contraste le plus. */
    private fun encreSur(fond: Int): Int =
        listOf(theme.fond or 0xFF000000.toInt(), theme.encre).maxByOrNull { Math.abs(Palette.luminance(it) - Palette.luminance(fond)) } ?: theme.encre

    // ------------------------------------------------------------------ liste déroulante

    private fun majChamp() {
        val libelles = options.filter { it.cle in choix }.map { it.libelle }
        val valeur = resume(libelles, context.getString(R.string.choix_aucun))
        champ.text = "$valeur  ▾"
        champ.contentDescription = "$titre : $valeur"
        champ.background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(theme.carte); setStroke(dp(1f), theme.carteBord) }
        // L'icône de l'option choisie (choix unique), pour la reconnaître d'un coup d'œil.
        val icone = if (!multiple) options.firstOrNull { it.cle in choix }?.icone?.let { copie(it) } else null
        icone?.let { val t = dp(18f); it.setBounds(0, 0, t, t) }
        champ.setCompoundDrawablesRelative(icone, null, null, null); champ.compoundDrawablePadding = dp(8f)
    }

    private fun ouvrirListe() {
        val libelles = options.map { it.libelle }.toTypedArray()
        val d = Ui.dialogue(context).setTitle(titre)
        if (multiple) {
            val coches = BooleanArray(options.size) { options[it].cle in choix }
            d.setMultiChoiceItems(libelles, coches) { dlg, k, oui ->
                if (oui && coches.count { it } >= maxChoix) { (dlg as? android.app.AlertDialog)?.listView?.setItemChecked(k, false); trop?.invoke(); return@setMultiChoiceItems }
                coches[k] = oui
            }
                .setPositiveButton(context.getString(R.string.choix_valider)) { _, _ ->
                    choix = LinkedHashSet(options.filterIndexed { k, _ -> coches[k] }.map { it.cle }); apres()
                }
                .setNegativeButton(context.getString(R.string.commun_lot2_annuler), null)
        } else {
            val courant = options.indexOfFirst { it.cle in choix }
            val auChoix = { dlg: android.content.DialogInterface, k: Int -> dlg.dismiss(); choisir(options[k].cle) }
            if (options.any { it.icone != null }) {
                // Les icônes aussi dans la liste (pictos d'un widget, sources de la musique).
                val adaptateur = object : android.widget.ArrayAdapter<String>(context, android.R.layout.select_dialog_singlechoice, libelles) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply {
                        val ic = options[position].icone?.let { copie(it) }
                        ic?.let { val t = dp(20f); it.setBounds(0, 0, t, t) }
                        (this as? TextView)?.apply { setCompoundDrawablesRelative(ic, null, compoundDrawablesRelative[2], null); compoundDrawablePadding = dp(12f) }
                    }
                }
                d.setSingleChoiceItems(adaptateur, courant) { dlg, k -> auChoix(dlg, k) }
            } else d.setSingleChoiceItems(libelles, courant) { dlg, k -> auChoix(dlg, k) }
                .setNegativeButton(context.getString(R.string.commun_lot2_annuler), null)
        }
        d.show()
    }

    // ------------------------------------------------------------------ choix

    private fun choisir(cle: String) {
        if (multiple) { if (!choix.remove(cle)) { if (choix.size >= maxChoix) { trop?.invoke(); return }; choix.add(cle) } }
        else { if (choix.size == 1 && cle in choix) return; choix = linkedSetOf(cle) }
        apres()
    }

    private fun apres() { peindreEtiquettes(); majChamp(); change(LinkedHashSet(choix)) }

    // ------------------------------------------------------------------ étiquettes ou liste : à la mesure

    override fun onMeasure(largeurSpec: Int, hauteurSpec: Int) {
        val dispo = if (MeasureSpec.getMode(largeurSpec) == MeasureSpec.UNSPECIFIED) 0 else MeasureSpec.getSize(largeurSpec) - paddingLeft - paddingRight
        rangee.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val etiquettes = enEtiquettes(options.size, rangee.measuredWidth, dispo, maxEtiquettes)
        if (etiquettes != modeEtiquettes) {
            // Le focus suit (une étiquette qui disparaît le rend au champ, et inversement).
            val avaitFocus = hasFocus()
            removeAllViews()
            if (etiquettes) addView(rangee, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            else addView(champ, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            modeEtiquettes = etiquettes
            if (avaitFocus) post { (if (etiquettes) rangee.getChildAt(0) else champ)?.requestFocus() }
        }
        super.onMeasure(largeurSpec, hauteurSpec)
    }
}
