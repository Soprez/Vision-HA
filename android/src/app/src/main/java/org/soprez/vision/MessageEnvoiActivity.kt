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
    // 1.94 (point 4) : le GIF Giphy choisi (identifiant) et sa vignette.
    private var gif = ""
    private var gifVignette: android.graphics.Bitmap? = null
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

    /**
     * 1.94 (point 4) : la recherche de GIF. Home Assistant interroge Giphy (la clé et la classification sont les siennes) et renvoie de
     * petites vignettes ; l'appareil ne contacte jamais Giphy. « Powered by GIPHY » reste affiché (conditions de Giphy).
     */
    private fun chercherGif() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(8f), px(20f), px(4f)) }
        val champ = EditText(this).also { ClavierTele.brancher(it) }.apply {
            hint = getString(R.string.gif_chercher_indice); textSize = 16f; setTextColor(theme.encre); setHintTextColor(theme.encre3)
            background = Ui.fondChamp(this@MessageEnvoiActivity, theme.carte, theme.encre, theme.carteBord)
            setPadding(px(14f), px(10f), px(14f), px(10f)); inputType = InputType.TYPE_CLASS_TEXT; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val etat = t("", 13f, theme.encre2).apply { setPadding(0, px(8f), 0, px(4f)) }
        val grille = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(champ); col.addView(etat); col.addView(grille)
        col.addView(t("Powered by GIPHY", 11f, theme.encre3).apply { setPadding(0, px(8f), 0, 0) })  // i18n-exception : mention exigée par Giphy (nom propre)
        val dialogue = Ui.dialogue(this).setTitle(R.string.gif_bouton).setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton(R.string.commun_annuler, null).create()
        fun lancer() {
            val q = champ.text.toString().trim()
            if (q.length < 2) { etat.text = getString(R.string.gif_deux_lettres); return }
            etat.text = getString(R.string.commun_chargement); grille.removeAllViews()
            Thread {
                var liste: JSONArray? = null; var err = ""
                try {
                    val cfg = Config(this)
                    val r = Net.post(this, cfg, "/api/pc_parental/giphy", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("q", q), 20_000)
                    liste = r.optJSONArray("resultats")
                } catch (e: Net.Echec) { err = e.corps?.optString("retour").orEmpty().ifEmpty { Geste.messageEchec(e.code) } } catch (_: Exception) { err = getString(R.string.msgenv_ha_ne_repond_pas) }
                main.post {
                    if (isFinishing) return@post
                    etat.text = err
                    val n = liste?.length() ?: 0
                    var rangee: LinearLayout? = null
                    for (i in 0 until n) {
                        val x = liste!!.getJSONObject(i)
                        val octets = try { android.util.Base64.decode(x.optString("vignette").substringAfter("base64,"), android.util.Base64.DEFAULT) } catch (_: Exception) { continue }
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(octets, 0, octets.size) ?: continue
                        if (rangee == null || rangee.childCount == 3) { rangee = LinearLayout(this); grille.addView(rangee) }
                        val v = android.widget.ImageView(this).apply {
                            setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; isFocusable = true; isClickable = true
                            contentDescription = x.optString("titre").ifEmpty { getString(R.string.gif_choisi) }
                            Ui.anneauFocus(this, theme, 10f, agrandir = false)
                            setOnClickListener { gif = x.optString("id"); gifVignette = bmp; dialogue.dismiss(); construire() }
                        }
                        rangee.addView(v, LinearLayout.LayoutParams(0, px(96f), 1f).apply { setMargins(px(3f), px(3f), px(3f), px(3f)) })
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
        champ.setOnEditorActionListener { _, _, _ -> lancer(); true }
        col.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.gif_chercher)) { lancer() }, haut = 8f))
        dialogue.show()
    }

    private fun envoyer() {
        if (occupe) return
        occupe = true; statut = getString(R.string.commun_en_cours); statutKo = false
        construire()
        main.postDelayed(finOccupe, 10_000)
        val corps = JSONObject().put("action", "envoyer").put("destinataires", JSONArray(choix.toList())).put("type", type)
            .put("texte", texte).put("reponses", JSONArray(reponses)).put("important", important).put("pause_tele", pause).put("voix", voix)
            .apply { if (gif.isNotEmpty()) put("gif", gif) }
        Thread {
            var ok = false; var msg = ""
            try { val r = appeler(corps, 9_000); ok = r.optBoolean("ok"); msg = r.optString("retour") }
            catch (e: Net.Echec) { msg = e.corps?.optString("retour").orEmpty().ifEmpty { Geste.messageEchec(e.code) } }
            catch (_: Exception) { msg = getString(R.string.msgenv_rien_parti) }
            main.post {
                main.removeCallbacks(finOccupe)
                if (isFinishing) return@post
                occupe = false; statut = msg.ifEmpty { getString(if (ok) R.string.msgenv_envoye else R.string.msgenv_pas_parti) }; statutKo = !ok
                if (ok) { texte = ""; gif = ""; gifVignette = null }
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
        setPadding(px(14f), px(10f), px(14f), px(10f)); minHeight = px(Ui.CIBLE_MIN); gravity = Gravity.CENTER_VERTICAL
        Ui.motsEntiers(this, 15f, 10f)
        isClickable = true; isFocusable = true; contentDescription = if (on) getString(R.string.msgenv_choisi, s) else s
        // Lot 6 (APP-13) : l'anneau de focus commun, visible à la télécommande.
        Ui.anneauFocus(this, theme, 999f, agrandir = false)
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
        // 15-10 (règle UX de l'auteur) : 3 options ou moins qui tiennent sur une ligne en étiquettes, sinon une liste (ChoixAdaptatif).
        fun objets(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
        val pour = objets(dest.optJSONArray("personnes")).map { x -> ChoixAdaptatif.Option(x.optString("id"), getString(R.string.msgenv_tous_ses_appareils, x.optString("nom"))) } +
            objets(dest.optJSONArray("appareils")).map { x -> ChoixAdaptatif.Option(x.optString("id"), x.optString("nom")) }
        section(c, getString(R.string.msgenv_pour))
        c.addView(Ui.choixMultiple(this, getString(R.string.msgenv_pour), pour, choix.toSet(), theme, 15f) { l -> choix.clear(); choix.addAll(l); construire() }.apply { tag = "msg_pour" })
        section(c, getString(R.string.msgenv_type))
        val types = objets(d.optJSONArray("types"))
        c.addView(Ui.choix(this, getString(R.string.msgenv_type), types.map { x -> ChoixAdaptatif.Option(x.optString("id"), x.optString("nom")) }, type, theme, 15f) { type = it; construire() }.apply { tag = "msg_type" })
        val typeImportant = types.firstOrNull { it.optString("id") == type }?.optString("priorite") == "importante"
        val champ = EditText(this).also { ClavierTele.brancher(it) }.apply {
            hint = getString(R.string.msgenv_ton_message); setText(texte); textSize = 16f; setTextColor(theme.encre); setHintTextColor(theme.encre3); minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // Lot 6 (APP-13) : bord épais à l'encre au focus, comme tous les champs (Ui.fondChamp).
            background = Ui.fondChamp(this@MessageEnvoiActivity, theme.carte, theme.encre, theme.carteBord)
            setPadding(px(14f), px(12f), px(14f), px(12f)); gravity = Gravity.TOP
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { texte = s?.toString().orEmpty() }
            })
        }
        c.addView(champ, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14f) })
        // 1.94 (point 4) : le bouton GIF n'existe que si Home Assistant dit que Giphy est prêt (activé, clé posée).
        if (d.optBoolean("giphy")) {
            val ligneGif = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val vignette = gifVignette
            if (gif.isNotEmpty() && vignette != null) {
                ligneGif.addView(android.widget.ImageView(this).apply { setImageBitmap(vignette); adjustViewBounds = true; contentDescription = getString(R.string.gif_choisi) },
                    LinearLayout.LayoutParams(px(72f), px(72f)).apply { rightMargin = px(10f) })
                ligneGif.addView(Ui.boutonSecondaire(this, getString(R.string.gif_retirer)) { gif = ""; gifVignette = null; construire() }.apply { tag = "gif_retirer" })
            } else ligneGif.addView(Ui.boutonSecondaire(this, getString(R.string.gif_bouton)) { chercherGif() }.apply { tag = "gif_bouton" })
            c.addView(ligneGif, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })
        }
        section(c, getString(R.string.msgenv_reponses))
        val proposees = (0 until (d.optJSONArray("reponses_proposees")?.length() ?: 0)).map { d.optJSONArray("reponses_proposees")!!.optString(it) }
        c.addView(ChoixAdaptatif(this, getString(R.string.msgenv_reponses), (proposees + reponses.filter { it !in proposees }).map { ChoixAdaptatif.Option(it, it) },
            reponses.toSet(), multiple = true, theme = theme, tailleSp = 15f, maxChoix = 3,
            trop = { statut = getString(R.string.msgenv_trois_max); statutKo = true; construire() }) { l -> reponses.clear(); reponses.addAll(l); construire() }.apply { tag = "msg_reponses" })
        val autre = EditText(this).also { ClavierTele.brancher(it) }.apply { hint = getString(R.string.msgenv_autre_reponse); textSize = 15f; setTextColor(theme.encre); setHintTextColor(theme.encre3)
            // Le même cadre que le champ du message (plus de soulignement Android à l'accent de la plateforme).
            background = Ui.fondChamp(this@MessageEnvoiActivity, theme.carte, theme.encre, theme.carteBord)
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
            Ui.anneauFocus(this, theme, 999f, agrandir = false)
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
