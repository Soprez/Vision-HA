// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Composer un tableau de l'écran de veille sur l'appareil : un titre, une
 * durée, jusqu'à huit cases. Chaque case prend une entité de Home Assistant
 * (choisie par domaine, puis dans la liste), un libellé facultatif et un
 * rendu (automatique, valeur, jauge, courbe 24 h, état, texte). Le tableau
 * reste sur l'appareil ; Home Assistant ne fait que fournir les valeurs.
 */
class TableauEditeurActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private var theme: Theme = Themes.liste[0]
    private lateinit var racine: ScrollView
    private lateinit var contenu: LinearLayout
    private var tableau: JSONObject = JSONObject()
    private var catalogue: JSONArray? = null
    // Libellés par identifiant de ressource : les textes se lisent après onCreate (langue de l'appareil).
    private val rendusIds = listOf("auto" to R.string.tableauediteur_rendu_auto, "valeur" to R.string.tableauediteur_rendu_valeur,
        "jauge" to R.string.tableauediteur_rendu_jauge, "courbe" to R.string.tableauediteur_rendu_courbe, "etat" to R.string.tableauediteur_rendu_etat,
        "texte" to R.string.tableauediteur_rendu_texte)
    private val rendus by lazy { rendusIds.map { (k, id) -> k to getString(id) } }
    private val domainesIds = linkedMapOf("sensor" to R.string.tableauediteur_dom_capteurs, "binary_sensor" to R.string.tableauediteur_dom_capteurs_oui_non,
        "climate" to R.string.tableauediteur_dom_thermostats, "light" to R.string.tableauediteur_dom_lumieres, "switch" to R.string.tableauediteur_dom_interrupteurs,
        "cover" to R.string.tableauediteur_dom_volets, "lock" to R.string.tableauediteur_dom_serrures, "person" to R.string.tableauediteur_dom_personnes,
        "device_tracker" to R.string.tableauediteur_dom_appareils, "weather" to R.string.tableauediteur_dom_meteo, "media_player" to R.string.tableauediteur_dom_lecteurs,
        "camera" to R.string.tableauediteur_dom_cameras, "input_boolean" to R.string.tableauediteur_dom_booleens, "input_number" to R.string.tableauediteur_dom_nombres,
        "number" to R.string.tableauediteur_dom_nombres, "fan" to R.string.tableauediteur_dom_ventilateurs, "vacuum" to R.string.tableauediteur_dom_aspirateurs,
        "alarm_control_panel" to R.string.tableauediteur_dom_alarme, "water_heater" to R.string.tableauediteur_dom_eau_chaude, "humidifier" to R.string.tableauediteur_dom_humidificateurs)
    private val domaines by lazy { LinkedHashMap(domainesIds.mapValues { getString(it.value) }) }
    private val tele by lazy { ReglagesTvActivity.estTele(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        theme = Local.themeEffectif(this, Palette.APPLI)
        if (tele) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val id = intent.getStringExtra("id") ?: ""
        tableau = Local.local(this, id) ?: run { finish(); return }
        racine = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setBackgroundColor(theme.fond) }
        contenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        racine.addView(contenu)
        setContentView(racine)
        construire()
        chargerCatalogue()
    }

    private fun px(dp: Float) = Ui.dp(this, dp)
    private val dm get() = resources.displayMetrics
    private fun t(pxMaquette: Float): Float = if (tele) pxMaquette * (dm.heightPixels / dm.density) / 1080f else pxMaquette * 0.62f
    private fun texte(s: String, taille: Float, couleur: Int, police: android.graphics.Typeface = Polices.texte(this)): TextView = Ui.sansCesure(TextView(this).apply { text = s; textSize = taille; setTextColor(couleur); typeface = police })
    private fun fondCarte(rayonDp: Float, couleur: Int = theme.carte, bord: Int = theme.carteBord, epaisseurDp: Float = 1f): GradientDrawable = GradientDrawable().apply { cornerRadius = px(rayonDp).toFloat(); setColor(couleur); setStroke(px(epaisseurDp), bord) }
    private fun focusable(v: View) { v.isFocusable = true; v.isClickable = true; v.background = fondCarte(18f); v.setOnFocusChangeListener { x, a -> x.background = if (a) fondCarte(18f, bord = theme.or, epaisseurDp = 1.5f) else fondCarte(18f) } }

    private fun sauver() { Local.remplacerLocal(this, tableau); construire() }

    private fun construire() {
        contenu.removeAllViews()
        val mx = if (tele) (dm.widthPixels * 0.08f).toInt() else px(18f); val my = if (tele) (dm.heightPixels * 0.06f).toInt() else px(40f)
        contenu.setPadding(mx, my, mx, my)
        contenu.addView(texte(getString(R.string.tableauediteur_titre_ecran), t(18f), theme.or, Polices.gras(this)).apply { letterSpacing = 0.22f })
        val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tete.addView(texte(tableau.optString("titre"), t(44f), theme.encre, Polices.outfit(this)).apply { includeFontPadding = false }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tete.addView(ligne(getString(R.string.tableauediteur_renommer), "", 0) {
            val champ = EditText(this).apply { setText(tableau.optString("titre")); setSelectAllOnFocus(true) }
            Ui.dialogue(this).setTitle(getString(R.string.tableauediteur_titre)).setView(champ).setPositiveButton(getString(R.string.commun_lot2_garder)) { _, _ -> tableau.put("titre", champ.text.toString().trim().ifEmpty { getString(R.string.tableauediteur_tableau_defaut) }); sauver() }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contenu.addView(tete)
        val duree = tableau.optInt("duree", 22)
        val rangDuree = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(10f), 0, 0) }
        rangDuree.addView(texte(getString(R.string.tableauediteur_duree_affichage), t(22f), theme.encre2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rangDuree.addView(ligne("−", "", 0, getString(R.string.a11y_raccourcir_5_s, getString(R.string.tableauediteur_duree_affichage))) { tableau.put("duree", (duree - 5).coerceAtLeast(5)); sauver() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rangDuree.addView(texte(getString(R.string.tableauediteur_secondes, duree), t(24f), theme.encre, Polices.demiGras(this)).apply { gravity = Gravity.CENTER; minWidth = px(70f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rangDuree.addView(ligne("+", "", 0, getString(R.string.a11y_allonger_5_s, getString(R.string.tableauediteur_duree_affichage))) { tableau.put("duree", (duree + 5).coerceAtMost(120)); sauver() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contenu.addView(rangDuree)

        contenu.addView(texte(getString(R.string.tableauediteur_cases), t(18f), theme.encre2, Polices.gras(this)).apply { letterSpacing = 0.22f; setPadding(0, px(18f), 0, px(8f)) })
        val cases = tableau.optJSONArray("cases") ?: JSONArray().also { tableau.put("cases", it) }
        if (cases.length() == 0) contenu.addView(texte(getString(R.string.tableauediteur_aucune_case), t(20f), theme.encre3))
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val nomEntite = nomDe(c.optString("entite"))
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16f), px(10f), px(10f), px(10f)) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(texte(c.optString("libelle").ifEmpty { nomEntite }, t(24f), theme.encre, Polices.demiGras(this)))
            col.addView(texte("${c.optString("entite")}  ·  ${rendus.firstOrNull { it.first == c.optString("rendu", "auto") }?.second ?: getString(R.string.tableauediteur_rendu_auto)}", t(17f), theme.encre2))
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(ligne(getString(R.string.tableauediteur_rendu), "", 0) { val k = rendus.indexOfFirst { it.first == c.optString("rendu", "auto") }; c.put("rendu", rendus[(k + 1) % rendus.size].first); sauver() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
            r.addView(ligne(getString(R.string.tableauediteur_libelle), "", 0) {
                val champ = EditText(this).apply { setText(c.optString("libelle")); hint = nomEntite }
                Ui.dialogue(this).setTitle(getString(R.string.tableauediteur_libelle_case)).setView(champ).setPositiveButton(getString(R.string.commun_lot2_garder)) { _, _ -> c.put("libelle", champ.text.toString().trim()); sauver() }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
            r.addView(ligne("×", "", 0, getString(R.string.a11y_retirer_case, nomEntite)) { val n = JSONArray(); for (k in 0 until cases.length()) if (k != i) n.put(cases.getJSONObject(k)); tableau.put("cases", n); sauver() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
            r.background = fondCarte(22f)
            contenu.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })
        }
        if (cases.length() < 8) {
            val plus = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(18f), px(12f), px(18f), px(12f)) }
            plus.addView(ImageView(this).apply { setImageResource(R.drawable.ic_plus); setColorFilter(theme.or) }, LinearLayout.LayoutParams(px(22f), px(22f)).apply { rightMargin = px(14f) })
            plus.addView(texte(if (catalogue == null) getString(R.string.tableauediteur_ajouter_chargement) else getString(R.string.tableauediteur_ajouter), t(22f), theme.encre, Polices.moyen(this)))
            focusable(plus)
            plus.setOnClickListener { if (catalogue != null) choisirDomaine() }
            contenu.addView(plus)
        }
        contenu.addView(texte(getString(R.string.tableauediteur_explication), t(17f), theme.encre3).apply { setPadding(px(4f), px(12f), 0, 0) })
            Ui.laisserDeborder(window.decorView)
}

    private fun ligne(titre: String, valeur: String, pad: Int, description: String = "", action: () -> Unit): View {
        val v = texte(titre, t(22f), theme.encre, Polices.moyen(this)).apply { gravity = Gravity.CENTER; setPadding(px(14f), px(8f), px(14f), px(8f)); minWidth = px(Ui.CIBLE_MIN); minHeight = px(Ui.CIBLE_MIN)
            if (description.isNotEmpty()) contentDescription = description }
        focusable(v); v.setOnClickListener { action() }
        return v
    }

    private fun nomDe(entite: String): String {
        val c = catalogue ?: return entite
        for (i in 0 until c.length()) { val o = c.getJSONObject(i); if (o.optString("id") == entite) return o.optString("nom") }
        return entite
    }

    private fun choisirDomaine() {
        val c = catalogue ?: return
        val presents = LinkedHashMap<String, Int>()
        for (i in 0 until c.length()) { val d = c.getJSONObject(i).optString("domaine"); presents[d] = (presents[d] ?: 0) + 1 }
        val cles = domaines.keys.filter { it in presents } + presents.keys.filter { it !in domaines }
        val noms = cles.map { "${domaines[it] ?: it}  (${presents[it]})" }.toTypedArray()
        Ui.dialogue(this).setTitle(getString(R.string.tableauediteur_quel_genre)).setItems(noms) { _, k -> choisirEntite(cles[k]) }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun choisirEntite(domaine: String) {
        val c = catalogue ?: return
        val liste = ArrayList<JSONObject>()
        for (i in 0 until c.length()) { val o = c.getJSONObject(i); if (o.optString("domaine") == domaine) liste.add(o) }
        val noms = liste.map { o -> o.optString("nom") + (if (o.optString("etat").isNotEmpty()) "  ·  ${o.optString("etat")} ${o.optString("unite")}".trimEnd() else "") }.toTypedArray()
        Ui.dialogue(this).setTitle(domaines[domaine] ?: domaine).setItems(noms) { _, k ->
            val o = liste[k]
            val cases = tableau.optJSONArray("cases") ?: JSONArray().also { tableau.put("cases", it) }
            cases.put(JSONObject().put("entite", o.optString("id")).put("libelle", "").put("rendu", "auto"))
            sauver()
        }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun chargerCatalogue() {
        Thread {
            try {
                val r = Net.post(this, cfg, "/api/pc_parental/veille/entites", JSONObject().put("id", cfg.id).put("secret", cfg.secret), 20_000)
                catalogue = r.optJSONArray("entites")
                main.post { construire() }
            } catch (_: Exception) { main.post { android.widget.Toast.makeText(this, getString(R.string.tableauediteur_injoignable), android.widget.Toast.LENGTH_LONG).show() } }
        }.also { it.isDaemon = true }.start()
    }
}
