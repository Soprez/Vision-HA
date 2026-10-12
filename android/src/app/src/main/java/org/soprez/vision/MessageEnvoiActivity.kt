// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2.0.0 (point 8, notifications v2, décisions du projet) : un décideur écrit à la maison depuis son téléphone.
 * Pour qui (personnes, appareils), le type, le message, jusqu'à 3 réponses proposées, « Important » (mis en avant,
 * ne bloque pas), « Mettre en pause la télé » (décoché), « Lire à voix haute ». Puis le suivi : reçu, lu, répondu.
 * Règle de l'auteur : « En cours… » au clic, réponse en français, rien de bloqué plus de 10 s.
 */
class MessageEnvoiActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var theme: Theme
    private var donnees: JSONObject? = null
    private var erreur = ""
    private var statut = ""
    private var statutKo = false
    private var occupe = false
    private val choix = LinkedHashSet<String>()
    private var type = "message"
    private var texte = ""
    private val reponses = ArrayList<String>()
    private var important = false
    private var pause = false
    private var voix = false
    private var suiviVue: LinearLayout? = null
    private var suiviEmpreinte = ""
    private var visible = false
    private val relire = object : Runnable { override fun run() { charger(seulementSuivi = true); if (visible) main.postDelayed(this, 5000) } }
    private val finOccupe = Runnable { if (occupe) { occupe = false; statut = getString(R.string.msgenv_pas_de_reponse); statutKo = true; construire() } }

    private fun px(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Local.themeEffectif(this, Palette.APPLI)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.fond))
        construire()
        charger()
    }

    override fun onResume() { super.onResume(); visible = true; main.postDelayed(relire, 5000) }
    override fun onPause() { super.onPause(); visible = false; main.removeCallbacks(relire) }

    private fun appeler(corps: JSONObject, delai: Int = 10_000): JSONObject {
        val cfg = Config(this)
        return Net.post(this, cfg, "/api/pc_parental/messages/app", corps.put("id", cfg.id).put("secret", cfg.secret), delai)
    }

    private fun charger(seulementSuivi: Boolean = false) {
        Thread {
            var d: JSONObject? = null; var err = ""
            try { d = appeler(JSONObject().put("action", "lire")) } catch (e: Net.Echec) { err = e.corps?.optString("retour").orEmpty().ifEmpty { Geste.messageEchec(e.code) } } catch (_: Exception) { err = getString(R.string.msgenv_ha_ne_repond_pas) }
            main.post {
                if (isFinishing) return@post
                if (d != null) { donnees = d; erreur = "" } else if (!seulementSuivi) erreur = err
                if (seulementSuivi && d != null) majSuivi() else if (!seulementSuivi) construire()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun envoyer() {
        if (occupe) return
        occupe = true; statut = getString(R.string.commun_en_cours); statutKo = false
        construire()
        main.postDelayed(finOccupe, 10_000)
        val corps = JSONObject().put("action", "envoyer").put("destinataires", JSONArray(choix.toList())).put("type", type)
            .put("texte", texte).put("reponses", JSONArray(reponses)).put("important", important).put("pause_tele", pause).put("voix", voix)
        Thread {
            var ok = false; var msg = ""
            try { val r = appeler(corps, 9_000); ok = r.optBoolean("ok"); msg = r.optString("retour") }
            catch (e: Net.Echec) { msg = e.corps?.optString("retour").orEmpty().ifEmpty { Geste.messageEchec(e.code) } }
            catch (_: Exception) { msg = getString(R.string.msgenv_rien_parti) }
            main.post {
                main.removeCallbacks(finOccupe)
                if (isFinishing) return@post
                occupe = false; statut = msg.ifEmpty { getString(if (ok) R.string.msgenv_envoye else R.string.msgenv_pas_parti) }; statutKo = !ok
                if (ok) texte = ""
                construire(); charger()
            }
        }.also { it.isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ rendu

    private fun t(s: String, taille: Float, couleur: Int, police: Typeface = Polices.texte(this)) =
        Ui.sansCesure(TextView(this).apply { text = s; textSize = taille; setTextColor(couleur); typeface = police })

    private fun section(c: LinearLayout, s: String) = c.addView(t(s, 14f, theme.encre2, Polices.demiGras(this)).apply { setPadding(0, px(16f), 0, px(6f)) })

    // Recette lot 1 : un nom d'appareil long (« TELEFON-MAXIMILIAN-KINDERZIMMER ») se coupe à ses tirets et, si un morceau
    // reste plus large que la pastille, la police rapetisse : jamais un mot coupé en deux.
    private fun pastille(s: String, on: Boolean, action: () -> Unit) = t(Ui.coupable(s), 15f, if (on) theme.encre else theme.encre2, if (on) Polices.demiGras(this) else Polices.texte(this)).apply {
        background = GradientDrawable().apply { cornerRadius = px(999f).toFloat(); setColor(if (on) Palette.melanger(theme.carte, theme.or, 0.22f) else 0); setStroke(px(1.5f), if (on) theme.or else theme.carteBord) }
        setPadding(px(14f), px(10f), px(14f), px(10f)); minHeight = px(44f); gravity = Gravity.CENTER_VERTICAL
        Ui.motsEntiers(this, 15f, 10f)
        isClickable = true; isFocusable = true; contentDescription = if (on) getString(R.string.msgenv_choisi, s) else s
        setOnClickListener { action() }
    }

    private fun interrupteur(c: LinearLayout, titre: String, detail: String, valeur: Boolean, actif: Boolean = true, change: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8f), 0, px(8f)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(t(titre, 15f, theme.encre, Polices.demiGras(this)))
        if (detail.isNotEmpty()) col.addView(t(detail, 13f, theme.encre2))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.interrupteur(this, valeur, theme, description = titre, change = change).apply { isEnabled = actif })
        c.addView(r)
    }

    private fun construire() {
        val sv = ScrollView(this).apply { setBackgroundColor(theme.fond); isFillViewport = true }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(28f), px(20f), px(28f)) }
        sv.addView(c)
        c.addView(t(getString(R.string.msgenv_titre), 26f, theme.encre, Polices.outfit(this)))
        val d = donnees
        if (d == null) {
            c.addView(t(erreur.ifEmpty { getString(R.string.commun_chargement) }, 15f, if (erreur.isEmpty()) theme.encre2 else theme.pourpre).apply { setPadding(0, px(12f), 0, 0) })
            setContentView(sv); return
        }
        val dest = d.optJSONObject("destinataires") ?: JSONObject()
        section(c, getString(R.string.msgenv_pour))
        val p = Pastilles(this, px(8f)); c.addView(p)
        fun objets(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
        objets(dest.optJSONArray("personnes")).forEach { x -> val id = x.optString("id"); p.addView(pastille(getString(R.string.msgenv_tous_ses_appareils, x.optString("nom")), id in choix) { if (!choix.remove(id)) choix.add(id); construire() }) }
        objets(dest.optJSONArray("appareils")).forEach { x -> val id = x.optString("id"); p.addView(pastille(x.optString("nom"), id in choix) { if (!choix.remove(id)) choix.add(id); construire() }) }
        section(c, getString(R.string.msgenv_type))
        val types = objets(d.optJSONArray("types"))
        val pt = Pastilles(this, px(8f)); c.addView(pt)
        types.forEach { x -> val id = x.optString("id"); pt.addView(pastille(x.optString("nom"), id == type) { type = id; construire() }) }
        val typeImportant = types.firstOrNull { it.optString("id") == type }?.optString("priorite") == "importante"
        val champ = EditText(this).apply {
            hint = getString(R.string.msgenv_ton_message); setText(texte); textSize = 16f; setTextColor(theme.encre); setHintTextColor(theme.encre3); minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(theme.carte); setStroke(px(1f), theme.carteBord) }
            setPadding(px(14f), px(12f), px(14f), px(12f)); gravity = Gravity.TOP
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { texte = s?.toString().orEmpty() }
            })
        }
        c.addView(champ, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14f) })
        section(c, getString(R.string.msgenv_reponses))
        val pr = Pastilles(this, px(8f)); c.addView(pr)
        val proposees = (0 until (d.optJSONArray("reponses_proposees")?.length() ?: 0)).map { d.optJSONArray("reponses_proposees")!!.optString(it) }
        (proposees + reponses.filter { it !in proposees }).forEach { r ->
            pr.addView(pastille(r, r in reponses) {
                if (!reponses.remove(r)) { if (reponses.size < 3) reponses.add(r) else { statut = getString(R.string.msgenv_trois_max); statutKo = true } }
                construire()
            })
        }
        val autre = EditText(this).apply { hint = getString(R.string.msgenv_autre_reponse); textSize = 15f; setTextColor(theme.encre); setHintTextColor(theme.encre3)
            // Le même cadre que le champ du message (plus de soulignement Android à l'accent de la plateforme).
            background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(theme.carte); setStroke(px(1f), theme.carteBord) }
            setPadding(px(14f), px(10f), px(14f), px(10f)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; filters = arrayOf(android.text.InputFilter.LengthFilter(30)) }
        val ra = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ra.addView(autre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ra.addView(pastille(getString(R.string.gestion_ajouter), false) {
            val v = autre.text.toString().trim(); if (v.isNotEmpty() && v !in reponses && reponses.size < 3) reponses.add(v); construire()
        })
        c.addView(ra)
        interrupteur(c, getString(R.string.msgenv_important), getString(R.string.msgenv_important_detail), important || typeImportant, !typeImportant) { important = it }
        interrupteur(c, getString(R.string.msgenv_pause_tele), getString(R.string.msgenv_pause_tele_detail), pause) { pause = it }
        interrupteur(c, getString(R.string.msgenv_voix), "", voix) { voix = it }
        val pret = choix.isNotEmpty() && !occupe
        val b = t(getString(if (occupe) R.string.commun_en_cours else R.string.msgenv_envoyer), 16f, theme.fond, Polices.gras(this)).apply {
            gravity = Gravity.CENTER; minHeight = px(52f); setPadding(px(18f), px(12f), px(18f), px(12f))
            background = GradientDrawable().apply { cornerRadius = px(999f).toFloat(); setColor(theme.or) }
            alpha = if (pret) 1f else 0.5f; isClickable = true; isFocusable = true
            setOnClickListener {
                if (choix.isEmpty()) { statut = getString(R.string.msgenv_choisis_dest); statutKo = true; construire() }
                else if (texte.isBlank()) { statut = getString(R.string.msgenv_ecris); statutKo = true; construire() }
                else envoyer()
            }
        }
        c.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16f) })
        if (statut.isNotEmpty()) c.addView(t(statut, 14f, if (statutKo) theme.pourpre else theme.encre2).apply { setPadding(0, px(8f), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        c.addView(t(getString(R.string.msgenv_son), 13f, theme.encre3).apply { setPadding(0, px(8f), 0, 0) })
        section(c, getString(R.string.msgenv_suivi))
        suiviVue = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(suiviVue)
        suiviEmpreinte = ""
        majSuivi()
        setContentView(sv)
    }

    /** Le suivi seul, et seulement s'il a changé (aucun clignotement : règle du projet). */
    private fun majSuivi() {
        val v = suiviVue ?: return
        val envois = donnees?.optJSONArray("envois") ?: JSONArray()
        val emp = envois.toString()
        if (emp == suiviEmpreinte) return
        suiviEmpreinte = emp
        v.removeAllViews()
        if (envois.length() == 0) { v.addView(t(getString(R.string.msgenv_aucun), 14f, theme.encre3)); return }
        for (i in 0 until minOf(envois.length(), 5)) {
            val e = envois.optJSONObject(i) ?: continue
            val carte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12f), px(10f), px(12f), px(10f)); background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(theme.carte); setStroke(px(1f), theme.carteBord) } }
            carte.addView(t(e.optString("texte"), 15f, theme.encre, Polices.demiGras(this)))
            val app = e.optJSONArray("appareils") ?: JSONArray()
            for (k in 0 until app.length()) {
                val a = app.optJSONObject(k) ?: continue
                val rep = a.optString("reponse")
                carte.addView(t(if (rep.isNotEmpty()) getString(R.string.msgenv_etat_reponse, a.optString("nom"), a.optString("etat_texte"), rep)
                    else getString(R.string.msgenv_etat, a.optString("nom"), a.optString("etat_texte")), 13.5f, if (a.optString("etat") == "repondu") theme.or else theme.encre2))
            }
            v.addView(carte, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
        }
    }
}
