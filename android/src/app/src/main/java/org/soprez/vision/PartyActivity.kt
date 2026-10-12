// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/**
 * 1.81.0 (point 14 bis, maquette validée en essai le 2026-10-10) : le Mode Party.
 *
 * Télé : plein écran. Pochette, titre, artiste, avancement ; à droite, la suite (si Music Assistant la donne)
 * et l'enceinte. Retour ou Accueil quittent ; rien n'est verrouillé ; la télé reste allumée pendant la Party.
 * Téléphone d'un décideur : choisir l'écran et l'enceinte, lancer ou arrêter, lecture, volume.
 * 2.2.0 : plus de « Mode Fête » (propre à une maison) : la Party émet des événements Home Assistant.
 * Chaque touche change l'écran tout de suite (« En cours… »), puis
 * l'état relu de Home Assistant ; un échec s'explique en français et rien ne reste bloqué (10 s au plus).
 */
class PartyActivity : Activity() {
    companion object {
        private val AIDE_TELE: String get() = Tr.s(R.string.mus_aide_tele)
        private val AIDE_DEPLACER: String get() = Tr.s(R.string.mus_aide_deplacer)
        /** 1.86.2 (retour d'essai) : vrai tant que l'écran Party est devant ; l'écran de veille de Vision ne vient pas pendant ce temps. */
        @Volatile var affichee = false
    }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var theme: Theme
    private var tele = false
    private var etat: Party.Etat? = null
    private var erreur = ""
    private var retour = ""
    private var occupe = ""
    private var ecranChoisi = ""
    private var lecteurChoisi = ""
    private var auPremierPlan = false
    private var chargement = false
    private var defilement: ScrollView? = null
    private var barre: Avancement? = null
    private var tempsTexte: TextView? = null
    /** 1.82.0 : les playlists montrées par la télé quand rien ne joue (chargées une fois). */
    private var playlistsTele: List<Party.Choix>? = null
    private var playlistsErreur = ""
    /** Le titre de la file en train d'être retiré (son bouton dit « En cours… »). */
    private var retraitId = ""
    // 1.86.2 (l'auteur : « Retirer saccade ») : la file change tout de suite à l'écran, sans rien recharger ; Music Assistant
    // confirme ensuite (en cas d'échec : retour en arrière et un message). Pendant ce temps, la relecture ne touche pas
    // à la file affichée.
    private val retiresEnAttente = HashMap<String, Long>()
    private var figeFileJusqua = 0L
    private var discretsEnCours = 0
    /** 1.86.8 (l'auteur : « le compteur descend puis revient ») : après un retrait, le nombre affiché ne remonte pas (6 s). */
    private var totalLocal = -1
    private var totalLocalJusqua = 0L
    private var colFile: LinearLayout? = null
    private var compteFile: TextView? = null
    private var aideTele: TextView? = null
    /** Télé : le titre en mode « Déplacer » (haut / bas le bougent, OK valide, Retour annule) ; téléphone : glissé. */
    private var deplace = ""
    private var ordreAvantDeplacement: List<Party.Titre> = emptyList()

    private val relire = Runnable { charger() }
    private val tic = object : Runnable {
        override fun run() { majAvancement(); main.postDelayed(this, 1000) }
    }
    private val finOccupe = Runnable { if (occupe.isNotEmpty()) { occupe = ""; retour = getString(R.string.choixmusique_pas_de_reponse); construire() } }

    private fun px(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Local.themeEffectif(this, Palette.APPLI)
        tele = ReglagesTvActivity.estTele(this)
        if (tele) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.fond))
        construire()
    }

    override fun onResume() {
        super.onResume()
        auPremierPlan = true; affichee = true
        FluxMusique.ecouter(this, "party") { if (auPremierPlan && !chargement) charger() }
        JournalEcran.annuler()
        charger()
        main.post(tic)
    }

    override fun onPause() {
        super.onPause()
        auPremierPlan = false; affichee = false
        FluxMusique.retirer("party")
        if (tele && !isFinishing) JournalEcran.surveiller(this)
        main.removeCallbacks(relire); main.removeCallbacks(tic)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        charger()
    }

    // ------------------------------------------------------------------ données

    private fun charger() {
        if (chargement) return
        chargement = true
        main.removeCallbacks(relire)
        Thread {
            var e: Party.Etat? = null
            var err = ""
            try {
                val cfg = Config(this)
                e = Party.analyser(Net.post(this, cfg, "/api/pc_parental/party/etat", JSONObject().put("id", cfg.id).put("secret", cfg.secret), 10_000))
            } catch (x: Net.Echec) { err = Party.messageErreur(x.code, x.corps) } catch (_: Exception) { err = Party.messageErreur(0, null) }
            main.post {
                chargement = false
                if (isFinishing) return@post
                val avant = etat; val erreurAvant = erreur
                val f = e?.let { tenirVoulu(filtrerFile(it, avant), avant) }
                if (f != null) { etat = f; erreur = "" } else erreur = err
                // Ouvert par le lancement : la Party arrêtée (ou passée sur une autre télé), l'écran se ferme.
                if (e != null && intent.getBooleanExtra("auto", false) && !e.ici) { finish(); return@post }
                // 1.82.1 (glitchs) : on ne rebâtit l'écran que si ce qu'il montre a changé ; la position du morceau
                // avance seule (barre d'avancement), sans redessin.
                val change = f == null || avant == null || sansPosition(f) != sansPosition(avant) || erreur != erreurAvant
                if (tele && f != null && !f.actif && !relanceFaite && f.lecteur.isNotEmpty() && !Party.rienNeJoue(f.maintenant)) {
                    relanceFaite = true
                    commande("lancer_ici")
                } else if (occupe.isEmpty()) { if (change) construire() else majAvancement() }
                // 1.87.1 : le titre lancé est arrivé (ou le délai est passé) : fin du lancement ; avant, relecture chaque seconde.
                if (etat?.maintenant?.let { !Party.rienNeJoue(it) } == true || System.currentTimeMillis() >= lancementJusqua) lancement = ""
                if (auPremierPlan) main.postDelayed(relire, if (enLancement(etat)) 1_000L else (etat?.relire ?: 5) * 1000L)
            }
        }.also { it.isDaemon = true }.start()
    }

    /** La file reçue, moins ce qui vient d'être retiré ici ; figée pendant un déplacement ou juste après un changement. */
    private fun filtrerFile(e: Party.Etat, avant: Party.Etat?): Party.Etat {
        val t = System.currentTimeMillis()
        retiresEnAttente.entries.removeAll { t - it.value > 15_000 }
        val e = if (t < totalLocalJusqua && totalLocal >= 0 && e.fileTotal > totalLocal) e.copy(fileTotal = totalLocal) else e
        if (avant != null && (deplace.isNotEmpty() || discretsEnCours > 0 || t < figeFileJusqua)) return e.copy(file = avant.file, fileTotal = avant.fileTotal)
        retiresEnAttente.keys.removeAll { id -> e.file.none { it.id == id } }  // Music Assistant a confirmé
        if (retiresEnAttente.isEmpty()) return e
        val l = e.file.filter { it.id !in retiresEnAttente }
        return e.copy(file = l, fileTotal = if (e.fileTotal >= 0) e.fileTotal - (e.file.size - l.size) else e.fileTotal)
    }

    /** Une commande sur la file, sans « En cours… » ni écran rebâti : l'écran a déjà changé. */
    private fun envoyerDiscret(action: String, corps: JSONObject, annuler: () -> Unit) {
        figeFileJusqua = System.currentTimeMillis() + 8_000
        discretsEnCours++
        Thread {
            var ok = false
            var msg = ""
            try {
                val cfg = Config(this)
                val r = Net.post(this, cfg, "/api/pc_parental/party/commande", corps.put("id", cfg.id).put("secret", cfg.secret).put("action", action), 9_000)
                ok = r.optBoolean("ok"); msg = r.optString("retour")
            } catch (x: Net.Echec) { msg = Party.messageErreur(x.code, x.corps) } catch (_: Exception) { msg = Party.messageErreur(0, null) }
            main.post {
                discretsEnCours = (discretsEnCours - 1).coerceAtLeast(0)
                if (isFinishing) return@post
                if (!ok) {
                    annuler(); figeFileJusqua = 0
                    retour = getString(R.string.partyecran_pas_fait, msg.ifEmpty { Party.messageErreur(-1, null) })
                    construire()
                } else {
                    figeFileJusqua = System.currentTimeMillis() + 1_200
                    main.removeCallbacks(relire); main.postDelayed(relire, 1_400)
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun ligneDe(id: String): View? = if (id.isEmpty()) null else colFile?.findViewWithTag("file:$id")

    private fun majCompte() { etat?.let { e -> compteFile?.text = Party.aSuivre(e) } }

    /** Une ligne qui se replie en 200 ms, puis disparaît. */
    private fun replier(v: View) {
        val h = v.height
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
        val marge = lp?.topMargin ?: 0
        android.animation.ValueAnimator.ofFloat(1f, 0f).setDuration(200).apply {
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val k = a.animatedValue as Float
                lp?.let { it.height = (h * k).toInt(); it.topMargin = (marge * k).toInt(); v.layoutParams = it }
                v.alpha = k
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { (v.parent as? ViewGroup)?.removeView(v) }
            })
            start()
        }
    }

    /** Retirer : la ligne se replie, le focus passe à la suivante (ou à la précédente si c'était la dernière). */
    private fun retirerVite(t: Party.Titre) {
        val e = etat ?: return
        val i = e.file.indexOfFirst { it.id == t.id }
        if (i < 0) return
        val voisin = e.file.getOrNull(i + 1) ?: e.file.getOrNull(i - 1)
        retiresEnAttente[t.id] = System.currentTimeMillis()
        etat = e.copy(file = e.file.filterNot { it.id == t.id }, fileTotal = if (e.fileTotal > 0) e.fileTotal - 1 else e.fileTotal)
        etat?.let { totalLocal = it.fileTotal; totalLocalJusqua = System.currentTimeMillis() + 6_000 }
        majCompte()
        val ligne = ligneDe(t.id)
        if (ligne != null) {
            if (ligne.isFocused || ligne.hasFocus()) (ligneDe(voisin?.id ?: "") ?: trouverDesc(window.decorView, getString(R.string.partyecran_ajouter_titre)))?.requestFocus()
            ligne.isFocusable = false; ligne.isClickable = false; ligne.tag = null
            replier(ligne)
        }
        envoyerDiscret("retirer", JSONObject().put("element", t.id)) { retiresEnAttente.remove(t.id); totalLocalJusqua = 0; etat = e }
    }

    /** Échange un titre avec son voisin (sens -1 : vers le haut) : c'est le voisin qui bouge, la ligne garde le focus. */
    private fun pas(id: String, sens: Int): Boolean {
        val e = etat ?: return false
        val i = e.file.indexOfFirst { it.id == id }
        val j = i + sens
        if (i < 0 || j !in e.file.indices) return false
        val l = e.file.toMutableList(); l[i] = e.file[j]; l[j] = e.file[i]
        etat = e.copy(file = l)
        val col = colFile ?: return true
        val va = ligneDe(id) ?: return true
        val vb = ligneDe(e.file[j].id) ?: return true
        col.removeView(vb)
        col.addView(vb, if (sens < 0) col.indexOfChild(va) + 1 else col.indexOfChild(va))
        va.post { Ui.garderVisible(va, px(14f)) }
        return true
    }

    /** « Jouer ensuite » (en tête) ou « Mettre à la fin » : tout de suite à l'écran, Music Assistant suit. */
    private fun placer(t: Party.Titre, enTete: Boolean) {
        val e = etat ?: return
        val l = e.file.toMutableList()
        val i = l.indexOfFirst { it.id == t.id }
        if (i < 0) return
        if ((enTete && i == 0) || (!enTete && i == l.size - 1)) {
            envoyerDiscret(if (enTete) "ensuite_element" else "a_la_fin", JSONObject().put("element", t.id)) {}
            return
        }
        l.removeAt(i); if (enTete) l.add(0, t) else l.add(t)
        etat = e.copy(file = l)
        val col = colFile
        val v = ligneDe(t.id)
        val ref = ligneDe((if (enTete) l.getOrNull(1) else l.getOrNull(l.size - 2))?.id ?: "")
        if (col != null && v != null && ref != null) {
            val focus = v.isFocused
            col.removeView(v)
            col.addView(v, if (enTete) col.indexOfChild(ref) else col.indexOfChild(ref) + 1)
            if (focus) v.requestFocus()
            v.alpha = 0.4f; v.animate().alpha(1f).setDuration(220).start()
        }
        envoyerDiscret(if (enTete) "ensuite_element" else "a_la_fin", JSONObject().put("element", t.id)) { etat = e }
    }

    private fun commencerDeplacement(t: Party.Titre) {
        val v = ligneDe(t.id) ?: return
        deplace = t.id
        ordreAvantDeplacement = etat?.file ?: return
        v.requestFocus()
        v.animate().scaleX(1.03f).scaleY(1.03f).translationZ(px(14f).toFloat()).setDuration(120).start()
        aideTele?.text = AIDE_DEPLACER
    }

    private fun finirDeplacement(valider: Boolean) {
        val id = deplace
        if (id.isEmpty()) return
        deplace = ""
        ligneDe(id)?.let { v -> v.animate().scaleX(1f).scaleY(1f).translationY(0f).translationZ(if (v.isFocused) px(6f).toFloat() else 0f).setDuration(140).start() }
        aideTele?.text = AIDE_TELE
        val avant = ordreAvantDeplacement
        val e = etat ?: return
        val i0 = avant.indexOfFirst { it.id == id }
        val i1 = e.file.indexOfFirst { it.id == id }
        if (!valider) {
            // Annuler : la ligne revient à sa place, pas à pas (rien n'est rebâti, rien n'est envoyé).
            var i = i1
            while (i0 >= 0 && i >= 0 && i != i0 && pas(id, if (i0 < i) -1 else 1)) i = etat?.file?.indexOfFirst { it.id == id } ?: -1
            ligneDe(id)?.requestFocus()
            return
        }
        if (i0 >= 0 && i1 >= 0 && i0 != i1) envoyerDiscret("deplacer", JSONObject().put("element", id).put("decalage", i1 - i0)) { etat = e.copy(file = avant) }
    }

    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        if (deplace.isNotEmpty()) when (ev.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ev.action == android.view.KeyEvent.ACTION_DOWN) pas(deplace, if (ev.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (ev.action == android.view.KeyEvent.ACTION_UP) finirDeplacement(true)
                return true
            }
            android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE -> {
                if (ev.action == android.view.KeyEvent.ACTION_UP) finirDeplacement(false)
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        return super.dispatchKeyEvent(ev)
    }

    /** Téléphone : la poignée d'une ligne, à glisser vers le haut ou le bas (décideurs). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun poignee(t: Party.Titre, ligne: View): View = texte("≡", 24f, theme.encre3).apply {
        setPadding(px(2f), px(10f), px(12f), px(10f))
        contentDescription = getString(R.string.partyecran_glisser_deplacer, t.titre)
        var y0 = 0f
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    y0 = ev.rawY; deplace = t.id; ordreAvantDeplacement = etat?.file ?: emptyList()
                    ligne.animate().scaleX(1.02f).scaleY(1.02f).translationZ(px(8f).toFloat()).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val e = etat
                    if (e != null) {
                        val i = e.file.indexOfFirst { it.id == t.id }
                        val bas = ligneDe(e.file.getOrNull(i + 1)?.id ?: "")
                        val haut = ligneDe(e.file.getOrNull(i - 1)?.id ?: "")
                        val dy = ev.rawY - y0
                        fun pasDe(w: View) = w.height + ((w.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0)
                        if (bas != null && dy > pasDe(bas) / 2f) { val d = pasDe(bas); if (pas(t.id, 1)) y0 += d }
                        else if (haut != null && dy < -pasDe(haut) / 2f) { val d = pasDe(haut); if (pas(t.id, -1)) y0 -= d }
                    }
                    ligne.translationY = ev.rawY - y0
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> finirDeplacement(true)
            }
            true
        }
    }

    private fun commande(action: String, corps: JSONObject = JSONObject(), attendu: ((Party.Etat) -> Party.Etat)? = null) {
        if (occupe.isNotEmpty()) return
        occupe = action; retour = ""
        etat?.let { e -> attendu?.let { etat = it(e) } }
        construire()
        main.postDelayed(finOccupe, 10_000)
        Thread {
            var msg = ""
            var ok = false
            try {
                val cfg = Config(this)
                val r = Net.post(this, cfg, "/api/pc_parental/party/commande", corps.put("id", cfg.id).put("secret", cfg.secret).put("action", action), 9_000)
                ok = r.optBoolean("ok"); msg = r.optString("retour")
            } catch (x: Net.Echec) { msg = Party.messageErreur(x.code, x.corps) } catch (_: Exception) { msg = Party.messageErreur(0, null) }
            main.post {
                main.removeCallbacks(finOccupe)
                if (isFinishing || occupe != action) return@post
                occupe = ""; retour = msg
                if (action == "jouer") { lancementDemande?.let { if (ok) lancementReussi(it) }; lancementDemande = null }
                if (!ok && retour.isEmpty()) retour = Party.messageErreur(-1, null)
                if (!ok) Ui.toast(this, retour)  // 1.86.15 : une erreur se voit toujours, où que soit le focus
                construire()
                charger()
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Client Sendspin natif (règles du projet) : le détail du volume d'un groupe, enceinte par enceinte. Une enceinte
     * « · Vision » n'a pas de réglage (« réglé sur la télé ») ; les autres ont − et +.
     */
    private fun volumesDesEnceintes(m: Party.Maintenant) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(8f), px(20f), px(8f)) }
        for (mb in m.membres) {
            // Sur la télé, nom et réglage côte à côte ; sur un téléphone (étroit), le nom en entier puis le réglage dessous.
            val bloc = LinearLayout(this).apply { orientation = if (tele) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6f), 0, px(6f)) }
            val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or (if (tele) Gravity.START else Gravity.END) }
            bloc.addView(texte(mb.nom, 16f, theme.encre).apply { gravity = Gravity.START },
                if (tele) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bloc.addView(ligne, LinearLayout.LayoutParams(if (tele) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val vol = mb.volume
            if (vol == null) ligne.addView(texte(getString(R.string.party_volume_sur_tele), 14f, theme.encre3).apply { setPadding(px(12f), 0, 0, 0) })
            else {
                var v: Double = vol
                val pct = texte("${Math.round(v * 100)} %", 14f, theme.encre2).apply { setPadding(px(8f), 0, px(8f), 0) }
                fun regler(sens: Int) { v = Party.volumeApres(v, sens); pct.text = "${Math.round(v * 100)} %"; commande("volume_membre", JSONObject().put("membre", mb.id).put("volume", v)) }
                val moins = getString(R.string.party_volume_moins_de, mb.nom); val plus = getString(R.string.party_volume_plus_de, mb.nom)
                ligne.addView(if (tele) rondTele("moins", moins, false) { regler(-1) } else rond("moins", moins, false, true) { regler(-1) })
                ligne.addView(pct)
                ligne.addView(if (tele) rondTele("plus", plus, false) { regler(1) } else rond("plus", plus, false, true) { regler(1) })
            }
            col.addView(bloc)
        }
        Ui.dialogue(this).setTitle(getString(R.string.party_volume_enceintes_titre))
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(getString(R.string.commun_fermer), null).show()
    }

    // ------------------------------------------------------------------ rendu commun

    private fun texte(t: String, taille: Float, couleur: Int, police: Typeface = Polices.texte(this)): TextView =
        Ui.sansCesure(TextView(this).apply { text = t; textSize = taille; setTextColor(couleur); typeface = police })

    private fun fond(couleur: Int, rayon: Float, bord: Int = 0) = GradientDrawable().apply {
        cornerRadius = px(rayon).toFloat(); setColor(couleur); if (bord != 0) setStroke(px(1.5f), bord)
    }

    private fun pochette(cote: Float, m: Party.Maintenant, lecteur: String): View {
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = fond(Palette.melanger(theme.carte, theme.or, 0.12f), cote / 14f, theme.carteBord)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        Pochettes.poser(this, img, lecteur, m.pochette)
        img.layoutParams = LinearLayout.LayoutParams(px(cote), px(cote))
        return img
    }

    @Volatile var constructions = 0
    /** 1.86.5 (retour d'essai) : la télé qui rouvre Musique pendant que la musique joue (soirée oubliée après un redémarrage de HA)
     *  relance la soirée d'elle-même, une fois : on arrive sur l'écran Lecture (pochette, QR, file), pas sur la recherche. */
    private var relanceFaite = false
    // 1.86.7 (l'auteur : « j'appuie sur play, ça affiche pause… puis revient ») : ce qui a été demandé tient à l'écran jusqu'à
    // ce que l'enceinte le confirme (ou 8 s) ; un état plus ancien ne l'écrase jamais. La barre se fige à l'appui.
    private var voulu = ""
    private var voulueDepuis = 0L
    private var positionFigee: Double? = null

    private fun sansPosition(x: Party.Etat) = x.copy(maintenant = x.maintenant.copy(position = null, positionTs = null), relire = 0)

    private fun majAvancement() {
        val e = etat ?: return
        val m = e.maintenant
        val figee = positionFigee
        val pos = if (figee != null && (m.etat != "playing" || voulu == "playing")) figee else Party.positionAffichee(this, e, System.currentTimeMillis() / 1000.0)
        val d = m.duree
        barre?.let { b -> b.part = if (pos != null && d != null && d > 0) (pos / d).toFloat() else 0f; b.invalidate() }
        // Plus d'une seconde sans confirmation : un signe discret que l'enceinte n'a pas encore suivi.
        val attente = voulu.isNotEmpty() && System.currentTimeMillis() - voulueDepuis > 1_200
        tempsTexte?.text = (if (pos != null && d != null) Party.temps(pos) + " / " + Party.temps(d) else "") + if (attente) "  ·  …" else ""
    }

    /** L'état reçu, sauf ce qui vient d'être demandé et pas encore confirmé (jamais de va-et-vient lecture / pause). */
    private fun tenirVoulu(e: Party.Etat, avant: Party.Etat?): Party.Etat {
        if (voulu.isEmpty()) return e
        val m = e.maintenant
        if (m.etat == voulu || System.currentTimeMillis() - voulueDepuis > 8_000) {
            voulu = ""
            val figee = positionFigee
            if (m.etat == "playing") positionFigee = null
            else if (figee != null && m.position != null && Math.abs(m.position - figee) >= 3) positionFigee = null
            return e
        }
        val a = avant?.maintenant
        return e.copy(maintenant = m.copy(etat = voulu, position = a?.position ?: m.position, positionTs = a?.positionTs ?: m.positionTs))
    }

    /** Lecture / pause : l'écran bascule tout de suite, la barre se fige, et HA reçoit l'état VOULU (pas une bascule). */
    private fun basculerLecture() {
        if (occupe.isNotEmpty()) return
        val e = etat ?: return
        val m = e.maintenant
        val cible = if (m.etat == "playing") "paused" else "playing"
        positionFigee = Party.positionAffichee(this, e, System.currentTimeMillis() / 1000.0)
        voulu = cible; voulueDepuis = System.currentTimeMillis()
        commande("lecture", JSONObject().put("voulu", if (cible == "paused") "pause" else "lecture")) { it.copy(maintenant = it.maintenant.copy(etat = cible)) }
    }

    private fun construire() {
        constructions++
        if (tele) construireTele() else construireTelephone()
        majAvancement()
    }

    // ------------------------------------------------------------------ télé

    private fun qrBitmap(contenu: String, cote: Int): android.graphics.Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(contenu, com.google.zxing.BarcodeFormat.QR_CODE, cote, cote,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val pts = IntArray(cote * cote)
        for (y in 0 until cote) for (x in 0 until cote) pts[y * cote + x] = if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        android.graphics.Bitmap.createBitmap(pts, cote, cote, android.graphics.Bitmap.Config.RGB_565)
    } catch (_: Exception) { null }

    // 1.86.0 (retours d'essai, photos du salon) : la zone sûre de la télé (5 % de chaque côté, overscan), des tailles
    // qui suivent la hauteur de l'écran, un focus qui ne change jamais la géométrie, aucun texte coupé ni empilé.
    private fun margeX() = (resources.displayMetrics.widthPixels * 0.05f).toInt()
    private fun margeY() = (resources.displayMetrics.heightPixels * 0.05f).toInt()
    private fun hauteurDp() = resources.displayMetrics.heightPixels / resources.displayMetrics.density
    private fun grandePolice() = resources.configuration.fontScale > 1.15f

    /** Un bouton ou une ligne télé : même taille et même trait au repos et au focus (seules les couleurs changent). */
    private fun boutonTele(t: String, plein: Boolean, ligne: Boolean = false, action: () -> Unit): TextView = texte(t, 18f, if (plein) theme.fond else theme.encre, Polices.gras(this)).apply {
        gravity = Gravity.CENTER; minHeight = px(48f); setPadding(px(20f), px(10f), px(20f), px(10f))
        isFocusable = true; isClickable = true
        val repos = if (plein) theme.or else 0
        val focus = if (plein) Palette.melanger(theme.or, theme.encre, 0.25f) else Palette.melanger(theme.fond, theme.or, 0.22f)
        val n = { a: Boolean -> GradientDrawable().apply { cornerRadius = px(999f).toFloat(); setColor(if (a) focus else repos); setStroke(px(2f), if (plein) theme.or else theme.carteBord) } }
        background = n(false)
        // 1.86.1 : le focus commun (double anneau par-dessus, ombre) ; les lignes de liste ne s'agrandissent pas.
        Ui.anneauFocus(this, theme, 26f, agrandir = !ligne) { a -> background = n(a) }
        // Agrandi depuis son bord gauche : un bouton calé sur la marge sûre n'en sort pas au focus.
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = 0f; v.pivotY = v.height / 2f }
        setOnClickListener { if (occupe.isEmpty()) action() }
    }

    private fun chargerPlaylistsTele() {
        if (playlistsTele != null || chargementPlaylists) return
        chargementPlaylists = true
        Thread {
            var l: List<Party.Choix>? = null; var err = ""
            try {
                val cfg = Config(this)
                l = Party.analyserChoix(Net.post(this, cfg, "/api/pc_parental/party/musique", JSONObject().put("quoi", "playlist").put("id", cfg.id).put("secret", cfg.secret), 15_000))
            } catch (x: Net.Echec) { err = Party.messageErreur(x.code, x.corps) } catch (_: Exception) { err = Party.messageErreur(0, null) }
            main.post { chargementPlaylists = false; playlistsTele = l; playlistsErreur = err; if (!isFinishing && occupe.isEmpty()) construire() }
        }.also { it.isDaemon = true }.start()
    }
    private var chargementPlaylists = false
    /**
     * 1.87.1 (l'auteur : « les playlists se sont lancées en arrière-plan ») : juste après un lancement réussi, Music Assistant
     * dit encore « rien ne joue » (titre vide, file vide) ; l'écran restait sur la liste des playlists pendant que la
     * musique démarrait derrière. Pendant 20 s au plus, l'écran du lecteur de Vision s'affiche aussitôt (« … :
     * lancement… ») et l'état est relu chaque seconde, jusqu'à ce que le titre arrive. Jamais Music Assistant à l'écran.
     */
    private var lancementDemande: String? = null
    private var lancement = ""
    private var lancementJusqua = 0L
    private fun enLancement(e: Party.Etat?) = e != null && e.ici && lancement.isNotEmpty() && System.currentTimeMillis() < lancementJusqua
    private fun lancementReussi(nom: String) { lancement = nom; lancementJusqua = System.currentTimeMillis() + 20_000 }

    /** Télé : jouer une playlist quand rien ne joue (et lancer la Party ici d'abord si besoin). */
    private fun jouerTele(x: Party.Choix) {
        // 1.86.15 (l'auteur : « je clique sur Disney classique, il ne se passe rien ») : chaque clic a un retour visible.
        val e = etat ?: run { Ui.toast(this, getString(R.string.mus_musique_charge)); return }
        if (occupe.isNotEmpty()) { Ui.toast(this, getString(R.string.mus_demande_en_cours)); return }
        Ui.toast(this, getString(R.string.party_lancement_playlist, x.nom), court = true)
        val corps = JSONObject().put("uri", x.uri).put("type", x.type).put("comment", "remplacer")
        if (e.ici) { lancementDemande = x.nom; commande("jouer", corps); return }
        occupe = "jouer"; retour = ""
        construire()
        main.postDelayed(finOccupe, 15_000)
        Thread {
            var msg = ""; var ok = false
            try {
                val cfg = Config(this)
                val l = Net.post(this, cfg, "/api/pc_parental/party/commande", JSONObject().put("action", "lancer_ici").put("id", cfg.id).put("secret", cfg.secret), 10_000)
                if (!l.optBoolean("ok", true)) throw Net.Echec(400, l.toString())
                val r = Net.post(this, cfg, "/api/pc_parental/party/commande", corps.put("action", "jouer").put("id", cfg.id).put("secret", cfg.secret), 10_000)
                ok = r.optBoolean("ok"); msg = r.optString("retour")
            } catch (y: Net.Echec) { msg = Party.messageErreur(y.code, y.corps) } catch (_: Exception) { msg = Party.messageErreur(0, null) }
            main.post {
                main.removeCallbacks(finOccupe)
                if (isFinishing) return@post
                occupe = ""; retour = if (ok) "" else msg.ifEmpty { Party.messageErreur(-1, null) }
                if (ok) lancementReussi(x.nom)
                if (!ok) Ui.toast(this, retour)
                construire(); charger()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun ouvrirRecherche(e: Party.Etat) =
        ChoixMusiqueActivity.ouvrir(this, "", e.lecteurNom, lancerIci = !e.ici, joue = e.ici && !Party.rienNeJoue(e.maintenant))

    /** Télé, rien ne joue : jamais un écran muet. Rechercher dans tout le catalogue, ou une playlist (par service). */
    private fun construireTeleGuide(racine: FrameLayout, e: Party.Etat?) {
        val grille = LinearLayout(this).apply { setPadding(margeX(), margeY(), margeX(), margeY()) }
        val gauche = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        gauche.addView(ImageView(this).apply { setImageDrawable(Accueil.iconeMusique(this@PartyActivity)) }, LinearLayout.LayoutParams(px(64f), px(64f)).apply { bottomMargin = px(16f) })
        val (titre, sous) = when {
            e == null && erreur.isNotEmpty() -> Party.NOM to erreur
            e == null -> Party.NOM to getString(R.string.choixmusique_chargement)
            !e.ma -> Party.NOM to getString(R.string.partyecran_ma_non_relie)
            Party.clinDOeil(this, e) != null -> Party.clinDOeil(this, e)!!
            else -> getString(R.string.partyecran_quest_ce_quon_ecoute) to (if (e.ici) getString(R.string.partyecran_joue_sur, e.lecteurNom) else getString(R.string.partyecran_demarre_tele))
        }
        gauche.addView(texte(titre, 32f, theme.encre, Polices.outfit(this)))
        gauche.addView(texte(sous, 18f, theme.encre2).apply { setPadding(0, px(8f), 0, 0) })
        if (retour.isNotEmpty()) gauche.addView(texte(retour, 17f, theme.pourpre).apply { setPadding(0, px(10f), 0, 0) })
        var premier: View? = null
        if (e != null && e.ma) {
            // Mis en avant : tout le catalogue de chaque service relié à Music Assistant, pas seulement la bibliothèque.
            val rech = boutonTele(getString(R.string.partyecran_rechercher_catalogue), true) { ouvrirRecherche(e) }.apply { contentDescription = getString(R.string.partyecran_rechercher_description) }
            gauche.addView(rech, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(22f) })
            gauche.addView(texte(getString(R.string.partyecran_tous_services), 15f, theme.encre3).apply { setPadding(0, px(8f), 0, 0) })
            premier = rech
        }
        gauche.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        gauche.addView(texte(getString(R.string.partyecran_tele_allumee), 14f, theme.encre3))
        gauche.addView(texte(getString(R.string.partyecran_retour_quitter), 14f, theme.encre3))
        grille.addView(gauche, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val droite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(32f), 0, 0, 0) }
        if (e != null && e.ma) {
            chargerPlaylistsTele()
            droite.addView(texte(getString(R.string.partyecran_playlists_maj), 17f, theme.or, Polices.gras(this)).apply { letterSpacing = 0.08f })
            val l = playlistsTele
            when {
                l == null && playlistsErreur.isEmpty() -> droite.addView(texte(getString(R.string.choixmusique_chargement), 17f, theme.encre3).apply { setPadding(0, px(10f), 0, 0) })
                l == null -> droite.addView(texte(playlistsErreur, 17f, theme.pourpre).apply { setPadding(0, px(10f), 0, 0) })
                l.isEmpty() -> droite.addView(texte(getString(R.string.partyecran_aucune_playlist_enregistree), 17f, theme.encre3).apply { setPadding(0, px(10f), 0, 0) })
                else -> {
                    // Les lignes ne grossissent pas au focus : la liste a sa marge propre, rien ne déborde.
                    val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; Ui.cadre(this) }
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(4f), 0, px(12f)) }
                    sv.addView(col)
                    l.groupBy { it.source.ifEmpty { getString(R.string.partyecran_bibliotheque) } }.forEach { (source, liste) ->
                        col.addView(texte(source, 15f, theme.encre2, Polices.demiGras(this)).apply { setPadding(px(4f), px(10f), 0, px(2f)) })
                        liste.take(30).forEach { x ->
                            val ligne = boutonTele(x.nom, false, ligne = true) { jouerTele(x) }.apply {
                                gravity = Gravity.CENTER_VERTICAL or Gravity.START; alpha = if (occupe == "jouer") 0.6f else 1f
                                textSize = 17f; contentDescription = getString(R.string.partyecran_jouer_playlist, x.nom)
                            }
                            col.addView(ligne, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) })
                            if (premier == null) premier = ligne
                        }
                    }
                    droite.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                }
            }
            if (occupe == "jouer") droite.addView(texte(getString(R.string.choixmusique_en_cours), 17f, theme.or, Polices.demiGras(this)).apply { setPadding(0, px(8f), 0, 0) })
        }
        grille.addView(droite, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        racine.addView(grille, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(racine)
        val f = currentFocus
        if (f == null || !f.isAttachedToWindow) premier?.post { premier?.requestFocus() }
    }

    /** Une petite pochette (ou sa place vide, sans saut) pour une ligne de liste. */
    private fun vignette(url: String, coteDp: Float): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = fond(Palette.melanger(theme.carte, theme.encre, 0.08f), 8f)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(px(coteDp), px(coteDp))
        Vignettes.poser(this, url, px(coteDp) * 2)
    }

    /** Un rond de commande à la télécommande (même anneau de focus que le reste). */
    private fun rondTele(picto: String, desc: String, plein: Boolean, action: () -> Unit): View {
        val t = if (plein) 56f else 46f
        val f = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (plein) setColor(theme.or) else { setColor(0); setStroke(px(1.5f), theme.carteBord) } }
            contentDescription = desc; isClickable = true
            setOnClickListener { if (occupe.isEmpty()) action() }
        }
        Ui.anneauFocus(f, theme, t / 2f, agrandir = true)
        f.addView(Picto(this, picto, if (plein) theme.fond else theme.encre), FrameLayout.LayoutParams(px(t * 0.42f), px(t * 0.42f), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(px(t), px(t)).apply { rightMargin = px(10f) }
        return f
    }

    /** 1.86.3 (retour d'essai) : toutes les enceintes et télés ; celles qui ne servent pas sont grisées avec la raison, et une aide finit la liste. */
    /** 1.86.6 (retour d'essai) : le choix d'enchaînement suivant, appliqué au lecteur de la soirée par Music Assistant. */
    private fun enchainementSuivant(e: Party.Etat) {
        val x = e.enchainement
        val actuel = if (x == null || !x.fondu) 0 else (x.duree ?: 8)
        val i = Party.FONDUS.indexOfFirst { it >= actuel }.let { if (it < 0) 0 else it }
        choisirEnchainement(Party.FONDUS[(i + 1) % Party.FONDUS.size])
    }

    private fun choisirEnchainement(fondu: Int) {
        val corps = if (fondu == 0) JSONObject().put("mode", "sans_blanc") else JSONObject().put("mode", "fondu").put("duree", fondu)
        commande("enchainement", corps) { it.copy(enchainement = Party.Enchainement(it.enchainement?.flux ?: true, fondu > 0, if (fondu > 0) fondu else it.enchainement?.duree, it.enchainement?.precharge ?: false)) }
    }

    private fun choisirEnceinte(e: Party.Etat) {
        val l = e.lecteurs
        val lignes = l.map { x ->
            val nom = Party.nomLecteur(x) + if (x.id == e.lecteur) getString(R.string.partyecran_en_cours_parenthese) else ""
            if (x.raison.isNotEmpty()) "$nom\n${x.raison}" else nom
        } + listOfNotNull(e.aideLecteurs.ifEmpty { null })
        val adaptateur = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, lignes) {
            override fun isEnabled(position: Int) = position < l.size && l[position].dispo
            override fun areAllItemsEnabled() = false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    val actif = isEnabled(position)
                    alpha = if (actif) 1f else 0.55f
                    textSize = if (position < l.size) 17f else 14f
                    contentDescription = lignes[position].replace("\n", ". ") + if (!actif && position < l.size) ", indisponible" else ""
                }
        }
        Ui.dialogue(this).setTitle(getString(R.string.partyecran_enceinte))
            .setAdapter(adaptateur) { _, k ->
                val x = l.getOrNull(k) ?: return@setAdapter
                if (x.dispo && x.id != e.lecteur) commande("enceinte", JSONObject().put("lecteur", x.id)) { it.copy(lecteur = x.id, lecteurNom = x.nom) }
            }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun menuTitre(e: Party.Etat, t: Party.Titre) {
        if (t.id.isEmpty()) return
        val choix = ArrayList<Pair<String, () -> Unit>>()
        val decide = e.piloter || e.ici
        if (decide) choix.add(getString(R.string.partyecran_jouer_maintenant) to { commande("jouer_element", JSONObject().put("element", t.id)) })
        choix.add(getString(R.string.choixmusique_jouer_ensuite) to { placer(t, true) })
        if (decide && tele) choix.add(getString(R.string.partyecran_deplacer) to { main.post { commencerDeplacement(t) } })
        if (decide) choix.add(getString(R.string.partyecran_mettre_fin) to { placer(t, false) })
        choix.add(getString(R.string.partyecran_retirer_file) to { main.post { retirerVite(t) } })
        Ui.dialogue(this).setTitle(t.titre).setItems(choix.map { it.first }.toTypedArray()) { _, k -> choix[k].second() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun construireTele() {
        val e = etat
        val racine = FrameLayout(this).apply { setBackgroundColor(theme.fond) }
        // Sans Party ici, sans enceinte, ou rien ne joue → l'écran guide (rechercher, playlists).
        if (e == null || !e.ici || e.lecteur.isEmpty() || (Party.rienNeJoue(e.maintenant) && e.file.isEmpty() && !enLancement(e))) { construireTeleGuide(racine, e); return }
        val m = if (enLancement(e) && e.maintenant.titre.isEmpty()) e.maintenant.copy(titre = getString(R.string.party_lancement_playlist, lancement)) else e.maintenant
        val h = hauteurDp() - 2 * margeY() / resources.displayMetrics.density
        val focusAvant = (currentFocus?.contentDescription ?: (currentFocus as? TextView)?.text)?.toString()
        val grille = LinearLayout(this).apply { setPadding(margeX(), margeY(), margeX(), margeY()) }

        // Gauche : ce qui passe, les commandes, puis le code invités.
        val gauche = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cote = (h * 0.30f).coerceIn(110f, 220f)
        val haut = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        haut.addView(pochette(cote, m, e.lecteur))
        val infos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18f), 0, 0, 0) }
        infos.addView(texte(Party.titreAffiche(m), 24f, theme.encre, Polices.outfit(this)))
        val sous = listOf(m.artiste, m.album).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sous.isNotEmpty()) infos.addView(texte(sous, 15f, theme.encre2))
        val b = Avancement(this, theme).also { barre = it }
        infos.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(5f)).apply { topMargin = px(10f) })
        tempsTexte = texte("", 13f, theme.encre3).also { infos.addView(it) }
        haut.addView(infos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gauche.addView(haut)
        // Les commandes : précédent, lecture/pause, suivant, volume.
        val cmd = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(14f), 0, 0) }
        val joue = m.etat == "playing"
        cmd.addView(rondTele("precedent", getString(R.string.vuewidget_precedent), false) { commande("precedent") })
        val lecture = rondTele(if (joue) "pause" else "lecture", if (joue) getString(R.string.vuewidget_pause) else getString(R.string.vuewidget_lecture), true) {
            basculerLecture()
        }
        cmd.addView(lecture)
        cmd.addView(rondTele("suivant", getString(R.string.vuewidget_suivant), false) { commande("suivant") })
        if (Party.volumeReglable(e.lecteurNom, m)) {
            val v = m.volume
            fun volume(sens: Int) { val n = Party.volumeApres(v, sens); commande("volume", JSONObject().put("volume", n)) { it.copy(maintenant = it.maintenant.copy(volume = n)) } }
            cmd.addView(rondTele("moins", getString(R.string.vuewidget_baisser_volume), false) { volume(-1) })
            val etiquette = texte(if (v == null) "" else if (m.groupe && !Party.detailVolumes(m)) getString(R.string.party_volume_groupe_pourcent, Math.round(v * 100)) else "${Math.round(v * 100)} %", 14f, theme.encre2).apply { setPadding(0, 0, px(10f), 0) }
            // Un groupe : l'étiquette du volume (courte : la ligne est pleine à 130 %) ouvre le détail par enceinte ; le mot
            // « groupe » est dit par la description.
            if (Party.detailVolumes(m)) etiquette.apply {
                text = "$text ›"; isClickable = true; isFocusable = true
                contentDescription = getString(R.string.party_volume_groupe_pourcent, Math.round((v ?: 0.0) * 100)) + " — " + getString(R.string.party_volume_enceintes_titre)
                background = fond(0, 999f, theme.carteBord); setPadding(px(10f), px(6f), px(10f), px(6f))
                setOnClickListener { if (occupe.isEmpty()) volumesDesEnceintes(m) }
                Ui.anneauFocus(this, theme, 18f)
            }
            cmd.addView(etiquette, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(10f) })
            cmd.addView(rondTele("plus", getString(R.string.vuewidget_monter_volume), false) { volume(1) })
        }
        gauche.addView(cmd)

        if (retour.isNotEmpty()) gauche.addView(texte(retour, 14f, theme.encre2).apply { setPadding(0, px(8f), 0, 0) })
        gauche.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        if (e.qr.isNotEmpty()) {
            val q = (h * if (grandePolice()) 0.22f else 0.26f).coerceIn(90f, 160f)
            qrBitmap(e.qr, px(q))?.let { bmp ->
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = fond(Palette.melanger(theme.fond, theme.carte, 0.7f), 14f); setPadding(px(10f), px(10f), px(14f), px(10f)) }
                r.addView(ImageView(this).apply { setImageBitmap(bmp); contentDescription = getString(R.string.partyecran_qr_description) }, LinearLayout.LayoutParams(px(q), px(q)))
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14f), 0, 0, 0) }
                col.addView(texte(getString(R.string.partyecran_proposer_titre), 16f, theme.encre, Polices.demiGras(this)))
                col.addView(texte(getString(R.string.partyecran_flashez_soiree), 13f, theme.encre2))
                r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                gauche.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        grille.addView(gauche, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // Droite : toute la hauteur pour la file.
        val droite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(28f), 0, 0, 0) }
        val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Grande police : le titre au-dessus, les deux boutons en dessous (sinon le titre se casserait).
        val titreFile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 1.86.7 (photo de l'auteur : « File / d'atte / nte ») : le titre et le nombre ont toute la largeur, sur une ligne
        // chacun ; les deux boutons passent dessous, l'enceinte prend la place qui reste (« … » si son nom est long).
        titreFile.addView(texte(getString(R.string.partyecran_file_attente), 18f, theme.or, Polices.gras(this)).apply { maxLines = 1 })
        compteFile = texte(Party.aSuivre(e), 13f, theme.encre2).apply { maxLines = 1 }.also { titreFile.addView(it) }
        droite.addView(titreFile.apply { setPadding(0, 0, 0, px(8f)) })
        val enceinte = boutonTele(getString(R.string.partyecran_enceinte_nom, e.lecteurNom.ifEmpty { "?" }), false) { choisirEnceinte(e) }.apply {
            textSize = 14f; contentDescription = getString(R.string.partyecran_changer_enceinte)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tete.addView(enceinte, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = px(10f) })
        val ajouter = boutonTele(getString(R.string.partyecran_ajouter_titre), true) { ouvrirRecherche(e) }.apply {
            textSize = 14f
            // Calé à droite : il s'agrandit vers la gauche (jamais hors de la zone sûre).
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = v.width.toFloat() }
        }
        tete.addView(ajouter)
        droite.addView(tete)
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; Ui.cadre(this) }
        // 1.86.2 : de la marge en haut et en bas, pour que la première et la dernière ligne gardent leur anneau entier.
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14f), px(10f), px(14f), px(14f)) }
        sv.addView(col)
        colFile = col
        fun ligneFile(titre: String, artiste: String, image: String, enCours: Boolean, invite: Boolean, t: Party.Titre?) {
            val l = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(10f), px(6f), px(12f), px(6f))
                background = fond(if (enCours) Palette.melanger(theme.fond, theme.or, 0.20f) else Palette.melanger(theme.fond, theme.carte, 0.7f), 12f, if (enCours) theme.or else 0)
            }
            // 1.86.7 (photo de l'auteur) : le titre en cours prend la pochette du lecteur (la même qu'en grand à gauche).
            l.addView(if (enCours) pochette(40f, m, e.lecteur) else vignette(image, 40f))
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12f), 0, 0, 0) }
            // 1.86.4 : une ligne par texte, « … » au bout, jamais coupé net au bord.
            c.addView(texte(titre, 15f, theme.encre, Polices.demiGras(this)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val ligne2 = listOfNotNull(if (enCours) getString(R.string.partyecran_en_cours_court) else null, artiste.ifEmpty { null }, if (invite) getString(R.string.partyecran_propose_par_invite) else null).joinToString(" · ")
            if (ligne2.isNotEmpty()) c.addView(texte(ligne2, 12f, if (invite || enCours) theme.or else theme.encre2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            l.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (t != null) {
                l.tag = "file:" + t.id
                l.isClickable = true; l.contentDescription = getString(R.string.partyecran_titre_en_file, t.titre)
                Ui.anneauFocus(l, theme, 12f, agrandir = false)
                l.setOnClickListener { menuTitre(e, t) }
            }
            col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5f) })
        }
        ligneFile(Party.titreAffiche(m), m.artiste, "", true, false, null)
        if (e.file.isEmpty()) col.addView(texte(getString(R.string.partyecran_rien_dautre), 14f, theme.encre3).apply { setPadding(px(4f), px(10f), 0, 0) })
        e.file.forEach { t -> ligneFile(t.titre, t.artiste, t.image, false, t.invite, t) }
        // 1.86.2 (retour d'essai) : pas d'ajout automatique de Music Assistant, sauf si on le demande.
        col.addView(boutonTele(if (e.similaires) getString(R.string.partyecran_fin_similaires) else getString(R.string.partyecran_fin_arret), false, ligne = true) {
            commande("similaires", JSONObject().put("actif", !e.similaires)) { it.copy(similaires = !e.similaires) }
        }.apply {
            textSize = 13f; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = getString(R.string.mus_similaires_desc, getString(if (e.similaires) R.string.mus_oui else R.string.mus_non))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
        col.addView(boutonTele(Party.libelleEnchainement(e.enchainement), false, ligne = true) { enchainementSuivant(e) }.apply {
            textSize = 13f; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = getString(R.string.mus_ok_changer, Party.libelleEnchainement(e.enchainement))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
        col.addView(boutonTele(getString(R.string.mus_fond_floute_bouton, getString(if (Party.fondFloute(this)) R.string.mus_oui else R.string.mus_non)), false, ligne = true) {
            Party.fondFloute(this, !Party.fondFloute(this)); construire()
        }.apply {
            textSize = 13f; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = getString(R.string.mus_fond_floute_desc, getString(if (Party.fondFloute(this@PartyActivity)) R.string.mus_oui else R.string.mus_non))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
        droite.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 1.86.4 (photo de l'auteur) : l'aide a sa zone à elle, sous la file, sur une seule ligne, au-dessus de tout.
        aideTele = texte(if (deplace.isEmpty()) AIDE_TELE else AIDE_DEPLACER, 13f, theme.encre3).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(px(4f), px(8f), px(4f), 0); setBackgroundColor(theme.fond)
            contentDescription = getString(R.string.mus_aide)
        }
        droite.addView(aideTele, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        grille.addView(droite, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f))
        racine.addView(grille, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        racine.contentDescription = Party.NOM + " : " + Party.titreAffiche(m) + (if (m.artiste.isNotEmpty()) ", " + m.artiste else "")
        setContentView(racine)
        // Le focus revient où il était (même bouton ou même titre) ; à la première ouverture, sur lecture / pause.
        val cible = ligneDe(deplace) ?: focusAvant?.let { d -> trouverDesc(racine, d) } ?: lecture
        cible.post {
            cible.requestFocus()
            if (deplace.isNotEmpty() && cible.tag == "file:$deplace") cible.animate().scaleX(1.03f).scaleY(1.03f).translationZ(px(14f).toFloat()).setDuration(1).start()
        }
    }

    private fun trouverDesc(v: View, d: String): View? {
        if (v.isFocusable && ((v.contentDescription?.toString() == d) || (v is TextView && v.text.toString() == d))) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) trouverDesc(v.getChildAt(i), d)?.let { return it }
        return null
    }

    /** Les touches de lecture de la télécommande pilotent la Party. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val e = etat
        if (e != null && e.actif && (e.ici || e.participer || e.piloter)) when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { basculerLecture(); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { commande("suivant"); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { commande("precedent"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ téléphone

    private fun section(c: LinearLayout, t: String) =
        c.addView(texte(t, 15f, theme.encre, Polices.demiGras(this)).apply { setPadding(0, px(18f), 0, px(8f)) })

    private fun pastille(t: String, choisie: Boolean, action: () -> Unit): TextView = texte(t, 15f, if (choisie) theme.encre else theme.encre2, if (choisie) Polices.demiGras(this) else Polices.texte(this)).apply {
        background = fond(if (choisie) Palette.melanger(theme.carte, theme.or, 0.22f) else 0, 999f, if (choisie) theme.or else theme.carteBord)
        setPadding(px(16f), px(11f), px(16f), px(11f)); minHeight = px(44f); gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        contentDescription = if (choisie) getString(R.string.choixmusique_onglet_choisi, t) else t
        setOnClickListener { action() }
    }

    private fun bouton(t: String, plein: Boolean, action: () -> Unit): TextView {
        val enCours = occupe.isNotEmpty()
        return texte(t, 16f, if (plein) theme.fond else theme.encre, Polices.gras(this)).apply {
            gravity = Gravity.CENTER; minHeight = px(52f); setPadding(px(18f), px(12f), px(18f), px(12f))
            background = fond(if (plein) theme.or else 0, 999f, if (plein) 0 else theme.carteBord)
            isClickable = true; isFocusable = true
            alpha = if (enCours) 0.6f else 1f
            setOnClickListener { if (!enCours) action() }
        }
    }

    private fun rond(picto: String, desc: String, plein: Boolean, actif: Boolean, action: () -> Unit): View {
        val t = if (plein) 60f else 52f
        val f = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (plein) setColor(theme.or) else { setColor(0); setStroke(px(1.5f), theme.carteBord) } }
            contentDescription = desc; isClickable = actif; isFocusable = actif; alpha = if (actif) 1f else 0.4f
            setOnClickListener { if (occupe.isEmpty()) action() }
        }
        f.addView(Picto(this, picto, if (plein) theme.fond else theme.encre), FrameLayout.LayoutParams(px(t * 0.4f), px(t * 0.4f), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(px(t), px(t)).apply { leftMargin = px(7f); rightMargin = px(7f) }
        return f
    }

    private fun construireTelephone() {
        val ancien = defilement?.scrollY ?: 0
        val sv = ScrollView(this).apply { setBackgroundColor(theme.fond); isFillViewport = true }
        defilement = sv
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(28f), px(20f), px(28f)) }
        sv.addView(c)
        c.addView(texte(Party.NOM, 28f, theme.encre, Polices.outfit(this)))
        val e = etat
        if (e == null) {
            c.addView(texte(erreur.ifEmpty { getString(R.string.choixmusique_chargement) }, 16f, if (erreur.isEmpty()) theme.encre2 else theme.pourpre).apply { setPadding(0, px(16f), 0, 0) })
            if (erreur.isNotEmpty()) c.addView(bouton(getString(R.string.editeurwidget_reessayer), false) { erreur = ""; construire(); charger() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
            setContentView(sv); return
        }
        val m = e.maintenant
        c.addView(texte(if (e.actif) getString(R.string.partyecran_en_cours_sur, e.ecranNom.ifEmpty { getString(R.string.partyecran_la_tele) }, e.lecteurNom) else getString(R.string.partyecran_arrete), 14f, if (e.actif) theme.or else theme.encre3, Polices.demiGras(this)).apply { setPadding(0, px(4f), 0, 0) })
        if (erreur.isNotEmpty()) c.addView(texte(erreur, 14f, theme.pourpre).apply { setPadding(0, px(8f), 0, 0) })
        if (retour.isNotEmpty()) c.addView(texte(retour, 14f, theme.encre2).apply { setPadding(0, px(8f), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })

        if (e.piloter) {
            if (ecranChoisi.isEmpty() || e.ecrans.none { it.id == ecranChoisi }) ecranChoisi = e.ecran.ifEmpty { e.ecrans.firstOrNull()?.id.orEmpty() }
            if (lecteurChoisi.isEmpty() || e.lecteurs.none { it.id == lecteurChoisi }) lecteurChoisi = e.lecteur.ifEmpty { e.lecteurs.firstOrNull { it.etat != "unavailable" && it.ma }?.id ?: e.lecteurs.firstOrNull { it.etat != "unavailable" }?.id.orEmpty() }
            section(c, getString(R.string.partyecran_ecran))
            if (e.ecrans.isEmpty()) c.addView(texte(getString(R.string.partyecran_aucune_tele), 14f, theme.encre3))
            val re = Pastilles(this, px(8f)); c.addView(re)
            e.ecrans.forEach { x -> re.addView(pastille(x.nom, x.id == ecranChoisi) { ecranChoisi = x.id; construire() }) }

            section(c, getString(R.string.partyecran_enceinte_groupe))
            val rl = Pastilles(this, px(8f)); c.addView(rl)
            // 1.86.3 (retour d'essai) : toutes les enceintes et télés ; grisées avec la raison quand elles ne servent pas.
            e.lecteurs.forEach { l ->
                val note = when { !l.dispo && l.etat == "unavailable" -> getString(R.string.mus_hors_ligne); !l.dispo -> getString(R.string.mus_hors_ma); else -> "" }
                rl.addView(pastille(Party.nomLecteur(l) + note, l.id == lecteurChoisi) {
                    if (!l.dispo) { retour = Party.nomLecteur(l) + " : " + l.raison; construire(); return@pastille }
                    lecteurChoisi = l.id
                    if (e.actif && l.id != e.lecteur) commande("enceinte", JSONObject().put("lecteur", l.id)) { it.copy(lecteur = l.id, lecteurNom = l.nom) } else construire()
                }.apply {
                    if (!l.dispo) { alpha = 0.55f; contentDescription = Party.nomLecteur(l) + ", indisponible : " + l.raison }
                })
            }
            if (e.aideLecteurs.isNotEmpty() && (e.lecteurs.any { !it.dispo } || e.lecteurs.none { it.dispo && it.ma }))
                c.addView(texte(e.aideLecteurs, 13f, theme.encre3).apply { setPadding(0, px(8f), 0, 0) })

            val nomEcran = e.ecrans.firstOrNull { it.id == ecranChoisi }?.nom.orEmpty()
            val relancer = !e.actif || ecranChoisi != e.ecran || occupe == "lancer"
            if (relancer && ecranChoisi.isNotEmpty() && lecteurChoisi.isNotEmpty()) {
                val t = if (occupe == "lancer") getString(R.string.choixmusique_en_cours) else getString(R.string.partyecran_lancer_nom_sur, Party.NOM, nomEcran)
                c.addView(bouton(t, true) {
                    commande("lancer", JSONObject().put("ecran", ecranChoisi).put("lecteur", lecteurChoisi)) {
                        it.copy(actif = true, ecran = ecranChoisi, ecranNom = nomEcran)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18f) })
            }
        } else {
            // 1.85.0 : Party ouverte à tous ; lancer, arrêter, l'enceinte et les invités restent aux décideurs.
            val note = when {
                e.ferme.isNotEmpty() -> e.ferme
                !e.actif -> getString(R.string.partyecran_pas_lancee, Party.NOM, Vocabulaire.decideurs(this))
                e.participer -> getString(R.string.partyecran_tout_le_monde_soiree, Vocabulaire.decideurs(this))
                else -> getString(R.string.partyecran_piloter_reserve, Party.NOM, Vocabulaire.decideurs(this))
            }
            c.addView(texte(note, 14f, if (e.ferme.isNotEmpty()) theme.pourpre else theme.encre3).apply { setPadding(0, px(8f), 0, 0) })
        }

        // Ce qui passe.
        if (e.lecteur.isNotEmpty() || lecteurChoisi.isNotEmpty()) {
            val carte = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = fond(theme.carte, 18f, theme.carteBord); setPadding(px(14f), px(14f), px(14f), px(14f)) }
            carte.addView(pochette(64f, m, e.lecteur))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14f), 0, 0, 0) }
            col.addView(texte(Party.titreAffiche(m), 17f, theme.encre, Polices.demiGras(this)))
            if (m.artiste.isNotEmpty()) col.addView(texte(m.artiste, 14f, theme.encre2))
            val b = Avancement(this, theme).also { barre = it }
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4f)).apply { topMargin = px(8f) })
            tempsTexte = texte("", 12f, theme.encre3).also { col.addView(it) }
            carte.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(carte, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18f) })

            if ((e.piloter || (e.participer && e.actif)) && e.lecteur.isNotEmpty()) {
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(14f), 0, 0) }
                val joue = m.etat == "playing"
                r.addView(rond("precedent", getString(R.string.vuewidget_precedent), false, true) { commande("precedent") })
                r.addView(rond(if (joue) "pause" else "lecture", if (joue) getString(R.string.vuewidget_pause) else getString(R.string.vuewidget_lecture), true, true) {
                    basculerLecture()
                })
                r.addView(rond("suivant", getString(R.string.vuewidget_suivant), false, true) { commande("suivant") })
                c.addView(r)
                if (Party.volumeReglable(e.lecteurNom, m)) {
                    val rv = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(10f), 0, 0) }
                    val v = m.volume
                    fun volume(sens: Int) {
                        val n = Party.volumeApres(v, sens)
                        commande("volume", JSONObject().put("volume", n)) { it.copy(maintenant = it.maintenant.copy(volume = n)) }
                    }
                    rv.addView(rond("moins", getString(R.string.vuewidget_baisser_volume), false, true) { volume(-1) })
                    rv.addView(texte(if (v == null) getString(R.string.partyecran_volume) else if (m.groupe) getString(R.string.party_volume_groupe_pourcent, Math.round(v * 100))
                        else getString(R.string.partyecran_volume_pourcent, Math.round(v * 100)), 14f, theme.encre2).apply { setPadding(px(10f), 0, px(10f), 0) })
                    rv.addView(rond("plus", getString(R.string.vuewidget_monter_volume), false, true) { volume(1) })
                    c.addView(rv)
                }
                if (Party.detailVolumes(m)) c.addView(bouton(getString(R.string.party_volume_enceintes), false) { volumesDesEnceintes(m) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(10f) })
            }
        }

        // 1.82.0 (retour d'essai) : choisir quoi jouer, et un état vide qui guide.
        if (e.piloter || (e.participer && e.actif)) {
            val lect = e.lecteur.ifEmpty { lecteurChoisi }
            val lectMa = e.lecteurs.firstOrNull { it.id == lect }?.ma ?: (lect == e.lecteur && e.lecteurMa)
            val nomLect = e.lecteurs.firstOrNull { it.id == lect }?.nom ?: e.lecteurNom
            when {
                !e.ma -> c.addView(texte(getString(R.string.partyecran_ma_non_relie), 14f, theme.pourpre).apply { setPadding(0, px(14f), 0, 0) })
                lect.isNotEmpty() && !lectMa -> c.addView(texte(getString(R.string.partyecran_enceinte_pas_ma), 14f, theme.encre2).apply { setPadding(0, px(14f), 0, 0) })
                lect.isNotEmpty() -> {
                    if (Party.rienNeJoue(m)) {
                        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = fond(0, 18f, theme.carteBord); setPadding(px(16f), px(16f), px(16f), px(16f)) }
                        val clin = Party.clinDOeil(this, e)
                        v.addView(texte(clin?.first ?: getString(R.string.partyecran_rien_ne_joue), if (clin != null) 20f else 17f, theme.encre, Polices.demiGras(this)).apply { gravity = Gravity.CENTER })
                        v.addView(texte(clin?.second ?: getString(R.string.partyecran_choisis_playlist_point), 14f, theme.encre2).apply { gravity = Gravity.CENTER; setPadding(0, px(4f), 0, px(12f)) })
                        v.addView(bouton(getString(R.string.manifeste_choisir_musique), true) { ChoixMusiqueActivity.ouvrir(this, lect, nomLect, joue = e.actif && !Party.rienNeJoue(m)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        c.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16f) })
                    } else c.addView(bouton(getString(R.string.manifeste_choisir_musique), false) { ChoixMusiqueActivity.ouvrir(this, lect, nomLect, joue = e.actif && !Party.rienNeJoue(m)) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14f) })
                }
            }
        }

        section(c, getString(R.string.partyecran_a_suivre))
        compteFile = null; colFile = null
        if (e.file.isEmpty()) c.addView(texte(if (e.ma) getString(R.string.partyecran_rien_a_suivre) else getString(R.string.partyecran_suite_ma), 14f, theme.encre3))
        else {
            compteFile = texte(Party.aSuivre(e), 13f, theme.encre2).also { c.addView(it) }
            val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(4f), 0, px(4f)) }
            colFile = liste
            c.addView(liste)
            val agit = e.piloter || (e.participer && e.actif)
            // Recette lot 1 : téléphone étroit ou grands caractères : les boutons passent sous le titre, qui garde toute
            // la largeur (à 130 % sur 360 dp, « Entfernen » et « ⋯ » à droite coupaient les mots du titre en deux).
            val etroit = resources.configuration.screenWidthDp / resources.configuration.fontScale < 330f
            e.file.forEach { t ->
                val r = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(px(4f), px(4f), 0, px(4f)); tag = if (t.id.isNotEmpty()) "file:" + t.id else null
                    background = fond(theme.fond, 12f)
                }
                // 1.86.2 (retour d'essai) : glisser par la poignée (décideurs) ; Music Assistant déplace sans couper la lecture.
                if (e.piloter && t.id.isNotEmpty()) r.addView(poignee(t, r))
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(texte(t.titre, 15f, theme.encre))
                Party.ligneFile(t).takeIf { it.isNotEmpty() }?.let { col.addView(texte(it, 13f, if (t.invite) theme.or else theme.encre2)) }
                r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (agit && t.id.isNotEmpty()) {
                    val boutons = if (etroit) LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, px(4f), 0, 0) }.also { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) } else r
                    boutons.addView(pastille(getString(R.string.editeurwidget_retirer), false) { retirerVite(t) }.apply { contentDescription = getString(R.string.partyecran_retirer_de_file, t.titre) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(6f) })
                    boutons.addView(pastille("⋯", false) { etat?.let { menuTitre(it, t) } }.apply { contentDescription = getString(R.string.partyecran_plus_de_choix, t.titre) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(6f) })
                }
                liste.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2f) })
            }
        }
        c.addView(Pastilles(this, px(8f)).apply {
            setPadding(0, px(10f), 0, 0)
            addView(pastille(getString(R.string.mus_fond_floute), Party.fondFloute(this@PartyActivity)) {
                Party.fondFloute(this@PartyActivity, !Party.fondFloute(this@PartyActivity)); construire()
            })
        })
        if (e.piloter && e.actif && e.ma) {
            section(c, getString(R.string.mus_enchainement_titres))
            val actuel = e.enchainement?.let { if (it.fondu) it.duree ?: 8 else 0 }
            val re = Pastilles(this, px(8f))
            Party.FONDUS.forEach { f ->
                re.addView(pastille(if (f == 0) getString(R.string.mus_sans_blanc) else getString(R.string.mus_fondu_s, f), actuel == f) { if (actuel != f) choisirEnchainement(f) })
            }
            c.addView(re)
            if (e.enchainement != null && !e.enchainement.flux)
                c.addView(texte(getString(R.string.mus_titre_par_titre_aide), 13f, theme.encre3).apply { setPadding(0, px(6f), 0, 0) })
        }
        if (e.piloter && e.actif && e.ma) {
            val rs = Pastilles(this, px(8f)).apply { setPadding(0, px(10f), 0, 0) }
            rs.addView(pastille(getString(R.string.partyecran_similaires), e.similaires) {
                commande("similaires", JSONObject().put("actif", !e.similaires)) { it.copy(similaires = !e.similaires) }
            })
            c.addView(rs)
        }

        // 1.82.0 : les invités (QR code sur la télé).
        if (e.piloter && e.actif && e.ma) {
            section(c, getString(R.string.partyecran_invites))
            c.addView(texte(if (e.qr.isNotEmpty()) getString(R.string.partyecran_invites_code) else getString(R.string.partyecran_invites_pas_code), 13f, theme.encre2))
            val rm = Pastilles(this, px(8f)).apply { setPadding(0, px(8f), 0, 0) }
            if (occupe == "invites") c.addView(texte(getString(R.string.choixmusique_en_cours), 14f, theme.or, Polices.demiGras(this)))
            rm.addView(pastille(getString(R.string.partyecran_ajout_direct), e.invitesMode == "direct") { if (e.invitesMode != "direct") commande("invites", JSONObject().put("mode", "direct")) { it.copy(invitesMode = "direct") } })
            rm.addView(pastille(getString(R.string.partyecran_je_valide), e.invitesMode == "validation") { if (e.invitesMode != "validation") commande("invites", JSONObject().put("mode", "validation")) { it.copy(invitesMode = "validation") } })
            // Le code a circulé trop loin (photo partagée…) : un nouveau, l'ancien ne marche plus.
            if (e.qr.isNotEmpty()) rm.addView(pastille(if (occupe == "nouveau_lien") getString(R.string.choixmusique_en_cours) else getString(R.string.appairageecran_nouveau_code), false) { commande("nouveau_lien") }.apply { contentDescription = getString(R.string.partyecran_changer_code) })
            c.addView(rm)
            e.propositions.forEach { p ->
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = fond(theme.carte, 14f, theme.carteBord); setPadding(px(12f), px(10f), px(10f), px(10f)) }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(texte(p.titre, 15f, theme.encre, Polices.demiGras(this)))
                col.addView(texte(listOf(p.artiste, getString(R.string.partyecran_propose_par_invite)).filter { it.isNotEmpty() }.joinToString(" · "), 13f, theme.encre2))
                r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // Recette lot 1 : téléphone étroit ou grands caractères : « Ajouter » / « Non » sous le titre (voir la file).
                val etroitP = resources.configuration.screenWidthDp / resources.configuration.fontScale < 330f
                val boutonsP = if (etroitP) LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, px(6f), 0, 0) }.also { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) } else r
                boutonsP.addView(pastille(getString(R.string.exceptions_ajouter), true) { commande("accepter", JSONObject().put("proposition", p.id)) { x -> x.copy(propositions = x.propositions.filter { it.id != p.id }) } }.apply { contentDescription = getString(R.string.partyecran_ajouter_a_file, p.titre) })
                boutonsP.addView(pastille(getString(R.string.messagesnotif_non), false) { commande("refuser", JSONObject().put("proposition", p.id)) { x -> x.copy(propositions = x.propositions.filter { it.id != p.id }) } }.apply { contentDescription = getString(R.string.partyecran_refuser, p.titre) })
                c.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
            }
        }

        if (e.piloter && (e.actif || occupe == "arreter")) {
            val t = if (occupe == "arreter") getString(R.string.choixmusique_en_cours) else getString(R.string.partyecran_arreter_nom, Party.NOM)
            c.addView(bouton(t, false) { commande("arreter") { it.copy(actif = false) } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(24f) })
        }
        setContentView(sv)
        sv.post { sv.scrollTo(0, ancien) }
    }

    /** La barre d'avancement du morceau. */
    class Avancement(ctx: android.content.Context, private val th: Theme) : View(ctx) {
        var part = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
            p.color = Palette.melanger(th.carte, th.encre, 0.18f); c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
            p.color = th.or; c.drawRoundRect(RectF(0f, 0f, w * part.coerceIn(0f, 1f), h), r, r, p)
        }
    }
}
