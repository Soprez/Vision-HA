// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
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
class PartyActivity : Activity(), ZoneSure.Geree {
    companion object {
        /** 1.93.06 (point 2) : essais seulement (FileMonteeTest) — chaque position posée sur la file de la télé. */
        @Volatile internal var suiviFile: ((Int) -> Unit)? = null
        private val AIDE_TELE: String get() = Tr.s(R.string.mus_aide_tele)
        private val AIDE_DEPLACER: String get() = Tr.s(R.string.mus_aide_deplacer)
        /** 1.86.2 (retour d'essai) : vrai tant que l'écran Party est devant ; l'écran de veille de Vision ne vient pas pendant ce temps. */
        @Volatile var affichee = false
        /** Le lecteur de la télé sans Party ici : rien ne joue. */
        private val MAINTENANT_VIDE = Party.Maintenant("idle", "", "", "", "", null, null, null, null, "")
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
    /** 1.93.06 : la position de la file (télé) et le titre en cours quand elle a été prise. */
    private var defilFile = -1
    /** 1.93.06 (point 2, TCL) : le haut de la ligne « En cours » quand [defilFile] a été pris ; la position gardée suit
     *  cette ligne si les titres passés au-dessus changent (HA les envoie un état sur deux). */
    private var defilRef = 0
    private var titreDefilFile = ""
    /** 1.93.06 (point 2) : la montée d'une ligne de la file au titre suivant (la seule animation de la file). */
    private var monteeFile: MonteeFile? = null
    private var svFile: ScrollView? = null
    /** 1.93.06 (point 2, TCL) : les titres passés affichés (Party.passesStables) et le titre en cours affiché. */
    private var passesFile: List<Party.Titre> = emptyList()
    private var courantFile: Party.Titre? = null
    /** La ligne « En cours » de la file à l'écran : la montée se mesure depuis elle (des lignes au-dessus peuvent changer). */
    private var ligneFileCourante: View? = null
    /** 1.93.06 (point 6e) : le titre d'avant le dernier changement, quand il a changé, et le dernier « précédent » demandé ici. */
    private var titrePrecedent = ""
    private var titreChangeA = 0L
    private var precedentDemandeA = 0L
    private var barre: BarreLecture? = null
    /** 1.91.0 : le fond animé (style, intensité, couleurs de la pochette : FondLecteur). */
    private var fond: FondLecteurVue? = null
    private var pochetteFond = ""
    private var couleursFond: IntArray? = null
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
        override fun run() { majAvancement(); majFondPochette(); main.postDelayed(this, 1000) }
    }
    private val finOccupe = Runnable { if (occupe.isNotEmpty()) { occupe = ""; retour = getString(R.string.choixmusique_pas_de_reponse); construire() } }

    private fun px(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Local.themeEffectif(this, Palette.APPLI)
        tele = ReglagesTvActivity.estTele(this)
        if (tele) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.fond))
        Pochettes.ecranOuvert()
        construire()
    }

    override fun onResume() {
        super.onResume()
        auPremierPlan = true; affichee = true
        ToucheMedia.maintenir(this)  // 1.93.05 (retour 37)
        fond?.actif = true
        fondChange()
        couverteMoitie = false
        FluxMusique.ecouter(this, "party") { if (auPremierPlan && !chargement) charger() }
        JournalEcran.annuler()
        main.removeCallbacks(relire); main.removeCallbacks(tic)
        // 1.93.06 (retour d'essai) : au retour au premier plan, l'état lecture / pause est relu tel quel : une demande faite avant de
        // partir (lecture / pause « voulue », position figée) ne doit plus masquer ce qui s'est passé entre-temps.
        voulu = ""; positionFigee = null
        charger()
        main.post(tic)
    }

    override fun onDestroy() {
        // 1.90.2 (retour d'essai) : l'écran Party fermé, ses pochettes quittent la mémoire — sauf si un autre écran Musique est déjà
        // ouvert (Pochettes.ecranFerme). 1.93.06 : le disque les garde (réouverture sans rechargement) ; il se vide à la
        // fin de la Party.
        if (Pochettes.ecranFerme(isFinishing)) { Pochettes.liberer(); Vignettes.liberer() }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (tele && couverteMoitie && !isFinishing) return  // toujours visible à gauche de la recherche
        suspendre()
    }

    override fun onStop() {
        super.onStop()
        if (couverteMoitie) { couverteMoitie = false; suspendre() }
    }

    private fun suspendre() {
        auPremierPlan = false; affichee = false
        ToucheMedia.maintenir(this)
        fond?.actif = false
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
                // 1.93.06 (point 6e, vidéo sur la TCL) : juste après un changement de titre, Home Assistant renvoie
                // parfois encore l'ancien titre (le lecteur n'a pas fini de suivre) : l'écran faisait l'aller-retour.
                // Un tel retour en arrière est ignoré (8 s au plus), sauf « précédent » demandé ici.
                val nouveauTitre = e?.let { Party.cleTitre(it.maintenant.titre, it.maintenant.artiste) }.orEmpty()
                val actuelTitre = avant?.let { Party.cleTitre(it.maintenant.titre, it.maintenant.artiste) }.orEmpty()
                if (e != null && Party.retourEnArriere(titrePrecedent, actuelTitre, nouveauTitre, System.currentTimeMillis() - titreChangeA, precedentDemandeA > titreChangeA)) {
                    chargement = false
                    if (auPremierPlan) main.postDelayed(relire, 1_000L)
                    return@post
                }
                if (nouveauTitre.isNotEmpty() && nouveauTitre != actuelTitre) { titrePrecedent = actuelTitre; titreChangeA = System.currentTimeMillis() }
                // 1.93.06 (retour 40) : le bouton suit le lecteur réel de cet appareil (Party.corrigerParLocal).
                val f = e?.let { tenirVoulu(filtrerFile(it, avant), avant) }?.let { Party.corrigerParLocal(this, it, voulu.isNotEmpty()) }
                if (f != null) { etat = f; erreur = "" } else erreur = err
                if (f != null) prechargerPochettes(f)
                // 1.91.0 : un saut annoncé par Home Assistant (demandé ici ou ailleurs) recale le lecteur Classique de cet appareil.
                f?.saut?.let { s -> LecteurMusique.saut(this, f.ici, f.lecteurNom, s.position, android.os.SystemClock.elapsedRealtime() - (s.ilYa * 1000).toLong()) }
                // Ouvert par le lancement : la Party arrêtée (ou passée sur une autre télé), l'écran se ferme.
                if (e != null && intent.getBooleanExtra("auto", false) && !e.ici) { finish(); return@post }
                // 1.82.1 (glitchs) : on ne rebâtit l'écran que si ce qu'il montre a changé ; la position du morceau
                // avance seule (barre d'avancement), sans redessin.
                val change = f == null || avant == null || sansPosition(f) != sansPosition(avant) || erreur != erreurAvant
                if (tele && f != null && !f.actif && !relanceFaite && f.lecteur.isNotEmpty() && !Party.rienNeJoue(f.maintenant)) {
                    relanceFaite = true
                    commande("lancer_ici")
                } else if (occupe.isEmpty()) {
                    // 1.91.0 : jamais d'écran rebâti sous le doigt (ou la télécommande) qui règle la position : après le saut.
                    if (change && barre?.apercu != null) { rebatirApresSaut = true; majAvancement() }
                    else if (change) construire() else majAvancement()
                }
                // 1.87.1 : le titre lancé est arrivé (ou le délai est passé) : fin du lancement ; avant, relecture chaque seconde.
                if (lancement.isNotEmpty()) {
                    if (etat?.maintenant?.let { !Party.rienNeJoue(it) } == true) lancement = ""
                    else if (System.currentTimeMillis() >= lancementJusqua) {
                        // l'auteur (16/10, Sendspin : « Lancement… » puis l'écran de recherche) : la musique n'est pas partie —
                        // on le dit, et l'écran reste sur le lecteur.
                        retour = getString(R.string.musrech_lancement_echec, etat?.lecteurNom.orEmpty().ifEmpty { "—" })
                        lancement = ""
                        construire()
                    }
                }
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

    // 1.93.05 (l'auteur, retour 38) : la jauge de volume de Vision ; la touche reste au système.
    private val jauge by lazy { JaugeVolume(this, theme) }

    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        if (tele) jauge.touche(ev)
        if (parametresOuverts && (ev.keyCode == android.view.KeyEvent.KEYCODE_BACK || ev.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
            if (ev.action == android.view.KeyEvent.ACTION_UP) { parametresOuverts = false; construire() }
            return true
        }
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
        // l'auteur (16/10, « comme si j'appuyais, mais il ne se passe rien ») : un appui pendant un ordre en cours le dit,
        // au lieu d'être ignoré en silence (le son du clic jouait quand même).
        if (occupe.isNotEmpty()) { Ui.toast(this, getString(R.string.mus_demande_en_cours), court = true); return }
        occupe = action; retour = ""
        // Un lecteur choisi ou piloté ici : la carte « en cours de lecture » de l'accueil pourra le montrer.
        LecteursSuivis.noter(this, corps.optString("lecteur").ifEmpty { etat?.lecteur })
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
                if (!ok) Ui.toast(this, retour, genre = OeilModele.Genre.ERREUR)  // 1.86.15 : une erreur se voit toujours, où que soit le focus
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

    // ------------------------------------------------------------------ fond animé (1.91.0)

    private fun nouveauFond(): FondLecteurVue = FondLecteurVue(this, theme, tele).apply {
        actif = auPremierPlan
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Réactif au son : le niveau du son que joue cet appareil (0 quand il ne joue rien).
        niveau = { if (FondLecteur.son(this@PartyActivity)) NiveauSon.niveau(android.os.SystemClock.elapsedRealtime()) else 0f }
        pochette(if (FondLecteur.pochette(this@PartyActivity)) couleursFond else null)
    }.also { fond = it }

    /** Téléphone : l'écran (qui défile) posé sur le fond animé. */
    private fun habiller(contenu: View): View = FrameLayout(this).apply {
        setBackgroundColor(theme.fond)
        addView(nouveauFond(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(contenu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Les couleurs de la pochette en cours, dès qu'elle est chargée (une fois par titre). */
    private fun majFondPochette() {
        val f = fond ?: return
        val e = etat
        if (!FondLecteur.pochette(this) || e == null || e.maintenant.pochette.isEmpty()) { couleursFond = null; pochetteFond = ""; f.pochette(null); return }
        val cle = e.lecteur + "|" + e.maintenant.pochette
        if (cle == pochetteFond) { f.pochette(couleursFond); return }
        val bmp = Pochettes.garde(e.lecteur, e.maintenant.pochette) ?: return
        couleursFond = try {
            val petit = android.graphics.Bitmap.createScaledBitmap(bmp, 24, 24, true)
            val px = IntArray(24 * 24); petit.getPixels(px, 0, 24, 0, 0, 24, 24)
            if (petit !== bmp) petit.recycle()
            FondLecteur.couleursPochette(px)
        } catch (_: Exception) { null }
        pochetteFond = cle
        f.pochette(couleursFond)
    }

    /** Retours de l'auteur (15-10) : le fond se règle dans les paramètres Vision ; au retour, il suit sans rebâtir l'écran. */
    private fun fondChange() {
        fond?.let { it.style = FondLecteur.style(this); it.intensite = FondLecteur.intensite(this) }
        pochetteFond = ""; majFondPochette()
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
        // 1.90.2 : en attendant la pochette du titre qui vient de passer, sa vignette (déjà chargée avec la file).
        // 1.93.06 (TCL) : jamais la pochette du titre d'avant pour un autre titre ; la vignette du titre, sinon la case vide.
        Pochettes.poser(this, img, lecteur, m.pochette, vignetteDuTitre(m)?.let { Vignettes.gardeGrand(it) ?: Vignettes.garde(it) },
            garderAncienne = false)
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

    private fun sansPosition(x: Party.Etat) = Party.pourAffichage(x)

    private fun majAvancement() {
        val e = etat ?: return
        val m = e.maintenant
        val figee = positionFigee
        val annoncee = if (figee != null && (m.etat != "playing" || voulu == "playing")) figee else Party.positionAffichee(this, e, System.currentTimeMillis() / 1000.0)
        val d = m.duree
        // 1.91.0 : après un saut, la position voulue tient à l'écran jusqu'à ce que le lecteur la confirme (8 s au plus).
        val maintenant = android.os.SystemClock.elapsedRealtime()
        val cible = sautCible
        var pos = annoncee
        if (cible != null) {
            val attendue = Saut.attendue(cible, sautDepuis, maintenant, m.etat == "playing", d)
            if (Saut.fini(annoncee, attendue, sautDepuis, maintenant)) sautCible = null else pos = attendue
        }
        barre?.let { b -> b.duree = d; b.part = Saut.part(pos, d) }
        // Plus d'une seconde sans confirmation : un signe discret que l'enceinte n'a pas encore suivi.
        val attente = voulu.isNotEmpty() && System.currentTimeMillis() - voulueDepuis > 1_200
        tempsTexte?.text = (if (pos != null && d != null) Party.temps(pos) + " / " + Party.temps(d) else "") + if (attente) "  ·  …" else ""
    }

    // 1.91.0 : le saut dans le titre (voir Saut).
    private var sautCible: Double? = null
    private var sautDepuis = 0L
    private var rebatirApresSaut = false

    private fun barreLecture(e: Party.Etat, m: Party.Maintenant): BarreLecture = BarreLecture(this, theme, tele).apply {
        duree = m.duree
        interactif = Saut.permis(e)
        dire = { p, d -> getString(R.string.lecture_position_valeur, Party.temps(p), Party.temps(d)) }
        surSaut = { p -> sauter(p) }
    }

    private val sautsEnSerie = Saut.EnSerie()

    /** Envoie le saut à Home Assistant ; l'écran montre tout de suite la position voulue. */
    private fun sauter(p: Double) {
        val e = etat ?: return
        val d = e.maintenant.duree ?: return
        val cible = Math.round(Saut.borner(p, d) * 10) / 10.0
        val quand = android.os.SystemClock.elapsedRealtime()
        sautCible = cible; sautDepuis = quand
        // En pause, la barre reste où on l'a posée (pas sur l'ancienne position figée).
        if (positionFigee != null) positionFigee = cible
        if (rebatirApresSaut) { rebatirApresSaut = false; construire() } else majAvancement()
        // l'auteur (16/10) : un saut à la fois ; pendant un saut, seule la dernière position voulue partira ensuite.
        sautsEnSerie.demander(cible, quand)?.let { envoyerSaut(it) }
    }

    private fun envoyerSaut(cible: Double) {
        val e = etat ?: return
        val quand = android.os.SystemClock.elapsedRealtime()
        sautCible = cible; sautDepuis = quand
        // Cet appareil est lui-même le lecteur (Classique) : son horloge locale sait déjà où le titre reprend.
        LecteurMusique.saut(this, e.ici, e.lecteurNom, cible, quand)
        val local = LecteurMusique.positionLocale(this, e.ici, e.lecteurNom) != null
        val debutsAvant = LecteurSlim.debutsTitre
        Thread {
            var ok = false; var msg = ""
            try {
                val cfg = Config(this)
                val r = Net.post(this, cfg, "/api/pc_parental/party/commande",
                    JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("action", "position").put("position", cible), 9_000)
                ok = r.optBoolean("ok"); msg = r.optString("retour")
            } catch (x: Net.Echec) { msg = Party.messageErreur(x.code, x.corps) } catch (_: Exception) { msg = Party.messageErreur(0, null) }
            main.post {
                if (isFinishing) return@post
                if (!ok) {
                    if (sautDepuis == quand) sautCible = null
                    Ui.toast(this, getString(R.string.partyecran_pas_fait, msg.ifEmpty { Party.messageErreur(-1, null) }))
                    majAvancement()
                } else { main.removeCallbacks(relire); main.postDelayed(relire, 1_200) }
                // Le saut a pris quand le flux de cet appareil est reparti (lecteur local), sinon dès la réponse.
                attendreSautPris(local && ok, debutsAvant, android.os.SystemClock.elapsedRealtime())
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun attendreSautPris(local: Boolean, debutsAvant: Int, depuis: Long) {
        val maintenant = android.os.SystemClock.elapsedRealtime()
        if (local && LecteurSlim.debutsTitre == debutsAvant && maintenant - depuis < 3_000) {
            main.postDelayed({ if (!isFinishing) attendreSautPris(true, debutsAvant, depuis) }, 150); return
        }
        sautsEnSerie.pris(maintenant)?.let { envoyerSaut(it) }
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
        if (occupe.isNotEmpty()) { Ui.toast(this, getString(R.string.mus_demande_en_cours), court = true); return }
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

    /**
     * 1.93.06 (retour d'essai) : le QR des invités à la taille choisie pour cet appareil (Petit par défaut : la moitié d'avant), à un
     * nombre entier de pixels par module (au moins 2), sans lissage, zone de silence de 4 modules ([QrInvites]).
     * [coteAvantDp] : sa taille d'avant (« Grand »). Rend l'image et son côté en px.
     */
    private fun qrImage(contenu: String, coteAvantDp: Float): Pair<android.graphics.drawable.BitmapDrawable, Int>? {
        val bmp = QrInvites.matrice(contenu) ?: return null
        val cote = QrInvites.cotePx(px(coteAvantDp * QrInvites.facteur(OptionsVeille.qrTaille(this))).toFloat(), bmp.width).toInt()
        return android.graphics.drawable.BitmapDrawable(resources, bmp).apply { isFilterBitmap = false } to cote
    }

    // 1.86.0 (retours d'essai, photos du salon) : la zone sûre de la télé (5 % de chaque côté, overscan), des tailles
    // qui suivent la hauteur de l'écran, un focus qui ne change jamais la géométrie, aucun texte coupé ni empilé.
    private fun margeX() = ZoneSure.x(this@PartyActivity, 0.05f)
    private fun margeY() = ZoneSure.y(this@PartyActivity, 0.05f)
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
        // l'auteur (16/10) : plus de garde muette ici — « ⚙ Réglages » ou « Ajouter un titre » répondent toujours ; un ordre
        // envoyé pendant un autre est refusé par commande(), qui le dit.
        setOnClickListener { action() }
    }

    /** Les playlists de Music Assistant (par source), à jouer d'un appui ; rend la première ligne (pour le focus). */
    private fun lignesPlaylistsTele(col: LinearLayout, titre: Boolean = true): View? {
        chargerPlaylistsTele()
        if (titre) col.addView(texte(getString(R.string.partyecran_playlists_maj), 15f, theme.or, Polices.gras(this)).apply { letterSpacing = 0.08f; setPadding(px(4f), px(14f), 0, 0) })
        val l = playlistsTele
        var premier: View? = null
        when {
            l == null && playlistsErreur.isEmpty() -> col.addView(texte(getString(R.string.choixmusique_chargement), 17f, theme.encre3).apply { setPadding(0, px(10f), 0, 0) })
            l == null -> col.addView(texte(playlistsErreur, 17f, theme.pourpre).apply { setPadding(0, px(10f), 0, 0) })
            l.isEmpty() -> col.addView(texte(getString(R.string.partyecran_aucune_playlist_enregistree), 17f, theme.encre3).apply { setPadding(0, px(10f), 0, 0) })
            else -> l.groupBy { it.source.ifEmpty { getString(R.string.partyecran_bibliotheque) } }.forEach { (source, liste) ->
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
        }
        if (occupe == "jouer") col.addView(texte(getString(R.string.choixmusique_en_cours), 17f, theme.or, Polices.demiGras(this)).apply { setPadding(0, px(8f), 0, 0) })
        return premier
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
        // 17-10 (l'auteur, point 14) : une confirmation discrète, plus le grand œil ; le lecteur dit ensuite « … : lancement… ».
        Ui.confirmation(this, getString(R.string.party_lancement_playlist, x.nom))
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
                if (!ok) Ui.toast(this, retour, genre = OeilModele.Genre.ERREUR)
                construire(); charger()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun ouvrirRecherche(e: Party.Etat) {
        // l'auteur (16/10) : depuis le lecteur de la télé, la recherche prend la moitié droite ; le lecteur reste à gauche.
        // 17-10 (l'auteur, point 9) : le lecteur est toujours là, même sans Party ici.
        val moitie = tele
        if (moitie) couverteMoitie = true
        ChoixMusiqueActivity.ouvrir(this, "", e.lecteurNom, lancerIci = !e.ici, joue = e.ici && !Party.rienNeJoue(e.maintenant), moitie = moitie)
    }

    /** La recherche couvre la moitié droite : cet écran reste visible, donc il continue de se mettre à jour. */
    private var couverteMoitie = false
    /** Le titre que montre le lecteur : un nouveau titre arrive en fondu (17-10, point 14). */
    private var titreMontre: String? = null

    /** Télé : Home Assistant pas encore lu, ou Music Assistant pas relié — jamais un écran muet (17-10 : sinon, le lecteur). */
    private fun construireTeleGuide(racine: FrameLayout, e: Party.Etat?) {
        val grille = LinearLayout(this).apply { setPadding(margeX(), margeY(), margeX(), margeY()) }
        val gauche = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        gauche.addView(ImageView(this).apply { setImageDrawable(Accueil.iconeMusique(this@PartyActivity)) }, LinearLayout.LayoutParams(px(64f), px(64f)).apply { bottomMargin = px(16f) })
        val (titre, sous) = when {
            e == null && erreur.isNotEmpty() -> Party.NOM to erreur
            e == null -> Party.NOM to getString(R.string.choixmusique_chargement)
            else -> Party.NOM to getString(R.string.partyecran_ma_non_relie)
        }
        gauche.addView(texte(titre, 32f, theme.encre, Polices.outfit(this)))
        gauche.addView(texte(sous, 18f, theme.encre2).apply { setPadding(0, px(8f), 0, 0) })
        if (retour.isNotEmpty()) gauche.addView(texte(retour, 17f, theme.pourpre).apply { setPadding(0, px(10f), 0, 0) })
        gauche.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        gauche.addView(texte(getString(R.string.partyecran_tele_allumee), 14f, theme.encre3))
        gauche.addView(texte(getString(R.string.partyecran_retour_quitter), 14f, theme.encre3))
        grille.addView(gauche, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        racine.addView(grille, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(racine)
    }

    // ------------------------------------------------------------------ pochettes préchargées (1.90.2)

    /** Titre → vignette de la file, pour l'image d'attente du titre qui devient le titre en cours. */
    private val vignettesParTitre = HashMap<String, String>()

    /** La vignette de la file pour le titre en cours : clé exacte, sinon la clé large (HA l'écrit autrement). */
    private fun vignetteDuTitre(m: Party.Maintenant): String? =
        vignettesParTitre[Party.cleTitre(m.titre, m.artiste)] ?: vignettesParTitre[Party.cleTitreLarge(m.titre, m.artiste)]
    private var partyActive = false

    /**
     * 1.90.2 (l'auteur : « il faudrait les précharger, et quand la partie est terminée ou qu'on ferme l'appli, ça décharge
     * automatiquement ») : les vignettes des [Party.PRECHARGE] titres suivants se chargent dès que la file est connue ;
     * la Party terminée, tout est déchargé (mémoire et disque).
     */
    private fun prechargerPochettes(e: Party.Etat) {
        if (partyActive && !e.actif) { Pochettes.vider(this); Vignettes.vider(this); vignettesParTitre.clear() }
        partyActive = e.actif
        if (!e.actif) return
        val suivants = e.file.take(Party.PRECHARGE)
        suivants.forEach { t -> t.image.takeIf { it.isNotEmpty() }?.let {
            vignettesParTitre[Party.cleTitre(t.titre, t.artiste)] = it
            vignettesParTitre.putIfAbsent(Party.cleTitreLarge(t.titre, t.artiste), it)  // 1.93.06 : HA l'écrit autrement
        } }
        Vignettes.precharger(this, suivants.map { it.image }, px(if (tele) 56f else 48f) * 2)
        // 1.93.06 (retour d'essai) : le titre qui vient ensuite, préchargé à la taille de la pochette du lecteur : au changement de
        // titre, l'image d'attente est déjà nette (plus de vignette agrandie, ni de case vide).
        suivants.firstOrNull()?.image?.takeIf { it.isNotEmpty() }?.let { Vignettes.prechargerGrand(this, it, px(if (tele) 220f else 260f)) }
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
    /** 1.93.06 : resserre une ligne de commandes de [k] (tailles, pictos, marges et textes), pour qu'elle tienne en largeur. */
    private fun resserrer(ligne: LinearLayout, k: Float) {
        val f = k.coerceIn(0.6f, 1f)
        for (i in 0 until ligne.childCount) {
            val v = ligne.getChildAt(i)
            (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (lp.width > 0) lp.width = (lp.width * f).toInt()
                if (lp.height > 0) lp.height = (lp.height * f).toInt()
                lp.leftMargin = (lp.leftMargin * f).toInt(); lp.rightMargin = (lp.rightMargin * f).toInt()
            }
            if (v is TextView) v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, v.textSize * f)
            if (v is ViewGroup) for (j in 0 until v.childCount) v.getChildAt(j).layoutParams?.let { lp ->
                if (lp.width > 0) lp.width = (lp.width * f).toInt()
                if (lp.height > 0) lp.height = (lp.height * f).toInt()
            }
        }
    }

    private fun rondTele(picto: String, desc: String, plein: Boolean, action: () -> Unit): View {
        val t = if (plein) 56f else 46f
        val f = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (plein) setColor(theme.or) else { setColor(0); setStroke(px(1.5f), theme.carteBord) } }
            contentDescription = desc; isClickable = true
            setOnClickListener { action() }
        }
        Ui.anneauFocus(f, theme, t / 2f, agrandir = true)
        f.addView(Picto(this, picto, if (plein) theme.fond else theme.encre), FrameLayout.LayoutParams(px(t * 0.42f), px(t * 0.42f), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(px(t), px(t)).apply { rightMargin = px(10f) }
        return f
    }

    /** 1.86.3 (retour d'essai) : toutes les enceintes et télés ; celles qui ne servent pas sont grisées avec la raison, et une aide finit la liste. */
    /** 1.86.6 (retour d'essai) : l'enchaînement choisi, appliqué au lecteur de la soirée par Music Assistant. */
    private fun choisirEnchainement(fondu: Int) {
        val corps = if (fondu == 0) JSONObject().put("mode", "sans_blanc") else JSONObject().put("mode", "fondu").put("duree", fondu)
        commande("enchainement", corps) { it.copy(enchainement = Party.Enchainement(it.enchainement?.flux ?: true, fondu > 0, if (fondu > 0) fondu else it.enchainement?.duree, it.enchainement?.precharge ?: false)) }
    }

    /**
     * Réponse de l'auteur (15-10) : les réglages de la soirée, regroupés. Pendant une soirée avec Music Assistant :
     * enchaînement (liste : 4 choix) et titres similaires (un Décideur, ou la télé de la soirée) ; invités (2 choix : un
     * appui change) et nouveau code des invités (un Décideur) ; pour tous : les réglages de la musique (paramètres Vision).
     */
    private fun lignesSoiree(e: Party.Etat?): List<Pair<String, () -> Unit>> {
        val lignes = ArrayList<Pair<String, () -> Unit>>()
        fun oui(b: Boolean) = getString(if (b) R.string.mus_oui else R.string.mus_non)
        // 17-10 (l'auteur, point 8) : le mode soirée (invités), d'un appui — un Décideur, ou la télé de la soirée.
        if (e != null && e.actif && (e.piloter || (tele && e.ici)))
            lignes.add(getString(R.string.musrech_soiree_mode, oui(e.invitesOuverts)) to { basculerInvites(e) })
        // Comme avant : la télé de la soirée règle l'enchaînement et les titres similaires ; les invités, un Décideur.
        if (e != null && e.actif && e.ma && (e.piloter || tele)) {
            lignes.add(Party.libelleEnchainement(e.enchainement) to {
                val actuel = e.enchainement?.let { if (it.fondu) it.duree ?: 8 else 0 } ?: -1
                val libelles = Party.FONDUS.map { f -> if (f == 0) getString(R.string.mus_sans_blanc) else getString(R.string.mus_fondu_s, f) }
                Ui.dialogue(this).setTitle(getString(R.string.mus_enchainement_titres))
                    .setSingleChoiceItems(libelles.toTypedArray(), Party.FONDUS.indexOf(actuel)) { d, k -> d.dismiss(); if (Party.FONDUS[k] != actuel) choisirEnchainement(Party.FONDUS[k]) }
                    .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
            })
            lignes.add(getString(R.string.musrech_soiree_similaires, oui(e.similaires)) to {
                commande("similaires", JSONObject().put("actif", !e.similaires)) { it.copy(similaires = !e.similaires) }
            })
        }
        if (e != null && e.piloter && e.actif && e.ma) {
            // l'auteur (16/10) : trois modes — valider avant, jouer après le titre en cours, ajouter en fin de file.
            val modes = listOf("validation" to getString(R.string.musrech_invites_valider), "ensuite" to getString(R.string.musrech_invites_ensuite),
                "direct" to getString(R.string.musrech_invites_fin))
            val actuel = modes.indexOfFirst { it.first == e.invitesMode }.coerceAtLeast(0)
            lignes.add(getString(R.string.musrech_soiree_invites, modes[actuel].second) to {
                Ui.dialogue(this).setTitle(getString(R.string.musrech_invites_titre))
                    .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), actuel) { d, k ->
                        d.dismiss()
                        val mode = modes[k].first
                        if (mode != e.invitesMode) commande("invites", JSONObject().put("mode", mode)) { it.copy(invitesMode = mode) }
                    }
                    .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
            })
            if (e.qr.isNotEmpty()) lignes.add(getString(R.string.partyecran_changer_code) to { commande("nouveau_lien") })
        }
        return lignes
    }

    /** 17-10 (l'auteur, point 8) : ouvrir ou fermer le mode soirée ; fermé, le QR code disparaît aussitôt. */
    private fun basculerInvites(e: Party.Etat) {
        val ouvrir = !e.invitesOuverts
        commande("invites_ouverts", JSONObject().put("actif", ouvrir)) { it.copy(invitesOuverts = ouvrir, qr = if (ouvrir) it.qr else "") }
    }

    /** Télé : les réglages de la soirée, l'enceinte et les réglages de la musique, dans un dialogue (télécommande). */
    private fun reglagesSoiree() {
        val e = etat
        val lignes = ArrayList(lignesSoiree(e))
        // Comme l'ancien bouton de l'en-tête : la télé de la soirée choisit l'enceinte (un Décideur aussi).
        if (e != null && (e.piloter || tele) && e.lecteurs.isNotEmpty()) lignes.add(0, getString(R.string.partyecran_enceinte_nom, e.lecteurNom.ifEmpty { "—" }) to { choisirEnceinte(e) })
        lignes.add(getString(R.string.musrech_reglages_raccourci) to { ReglagesMusique.ouvrir(this) })
        Ui.dialogue(this).setTitle(getString(R.string.musrech_reglages_soiree_titre))
            .setItems(lignes.map { it.first }.toTypedArray()) { _, k -> lignes[k].second() }
            .setNegativeButton(getString(R.string.commun_fermer), null).show()
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
        // 17-10 (l'auteur, point 18b) : tout en bas, « Vider la file », après confirmation ; le titre en cours continue.
        if (decide) choix.add(getString(R.string.partyecran_vider_file) to { main.post { viderFile(e) } })
        Ui.dialogue(this).setTitle(t.titre).setItems(choix.map { it.first }.toTypedArray()) { _, k -> choix[k].second() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /**
     * 17-10 (l'auteur, point 24 : « la file d'attente n'apparaît pas tout de suite, et on ne peut pas la réorganiser ») : la file,
     * directement sous le lecteur du téléphone (avant : dans la page Réglages). Réordonner par la poignée ≡ (décideurs),
     * glisser une ligne sur le côté pour la retirer, ⋯ : jouer maintenant, ensuite, à la fin, retirer, vider la file
     * (confirmée). Elle suit Music Assistant en direct, comme le reste de l'écran (relecture et flux de Home Assistant).
     */
    private fun fileTelephone(c: LinearLayout, e: Party.Etat) {
        section(c, getString(R.string.partyecran_a_suivre))
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
                // 17-10 (l'auteur, point 24) : glisser la ligne sur le côté la retire (le bouton « Retirer » reste, pour qui ne glisse pas).
                if (agit && t.id.isNotEmpty()) glisserPourRetirer(r, t)
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
        // « Vider la file » à portée, sous la liste (un Décideur), confirmé ; le titre en cours continue.
        if (e.piloter && e.actif && e.file.isNotEmpty()) c.addView(pastille(getString(R.string.partyecran_vider_file), false) { viderFile(e) }.apply { tag = "vider_file" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f); gravity = Gravity.END })
    }

    /** 17-10 (l'auteur, point 24) : une ligne de la file glissée sur le côté (au-delà du tiers de sa largeur) est retirée. */
    private fun glisserPourRetirer(ligne: View, t: Party.Titre) {
        val seuil = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var glisse = false
        ligne.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = ev.rawX; y0 = ev.rawY; glisse = false; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - x0; val dy = ev.rawY - y0
                    if (!glisse && Math.abs(dx) > seuil && Math.abs(dx) > Math.abs(dy) * 1.5f) { glisse = true; v.parent.requestDisallowInterceptTouchEvent(true) }
                    if (glisse) { v.translationX = dx; v.alpha = (1f - Math.abs(dx) / v.width.coerceAtLeast(1)).coerceIn(0.2f, 1f) }
                    glisse
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.rawX - x0
                    if (glisse && ev.actionMasked == android.view.MotionEvent.ACTION_UP && Math.abs(dx) > v.width / 3f) {
                        v.animate().translationX(Math.signum(dx) * v.width).alpha(0f).setDuration(160).withEndAction { retirerVite(t) }.start()
                    } else v.animate().translationX(0f).alpha(1f).setDuration(160).start()
                    val etait = glisse; glisse = false; etait
                }
                else -> false
            }
        }
    }

    /** 17-10 (l'auteur, point 18b) : vider la file (commande « vider » de Home Assistant, branche retours-ja-1017-ha) — confirmé ; le titre en cours continue. */
    private fun viderFile(e: Party.Etat) {
        val n = if (e.fileTotal >= 0) maxOf(e.fileTotal, e.file.size) else e.file.size
        Ui.dialogue(this).setTitle(getString(R.string.partyecran_vider_titre))
            .setMessage(getString(R.string.partyecran_vider_texte, n))
            .setPositiveButton(getString(R.string.partyecran_vider_bouton)) { _, _ -> commande("vider") { it.copy(file = emptyList(), fileTotal = 0) } }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun construireTele() {
        val e = etat
        val racine = FrameLayout(this).apply { setBackgroundColor(theme.fond) }
        racine.addView(nouveauFond(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Sans Party ici, sans enceinte, ou rien ne joue → l'écran guide (rechercher, playlists).
        // l'auteur (16/10) : dès l'accueil de Musique, le lecteur est visible (la Party sur cette télé, même quand rien ne joue) ;
        // la partie droite propose alors bibliothèque, recherche et playlists. Sans Party ici : l'écran guide.
        // 17-10 (l'auteur, point 9) : ouvrir la musique mène DIRECTEMENT au lecteur. L'écran guide ne reste que pour attendre
        // Home Assistant ou dire que Music Assistant n'est pas relié. Sans Party sur cette télé, le lecteur est vide (rien
        // de l'autre pièce ne s'y pilote) : une playlist ou « Ajouter un titre » lancent la Party ici.
        if (e == null || !e.ma) { construireTeleGuide(racine, e); return }
        val ici = e.ici && e.lecteur.isNotEmpty()
        val vide = !ici || (Party.rienNeJoue(e.maintenant) && e.file.isEmpty() && !enLancement(e))
        val m = when {
            !ici -> MAINTENANT_VIDE
            enLancement(e) && e.maintenant.titre.isEmpty() -> e.maintenant.copy(titre = getString(R.string.party_lancement_playlist, lancement))
            else -> e.maintenant
        }
        val h = hauteurDp() - 2 * margeY() / resources.displayMetrics.density
        val focusAvant = (currentFocus?.contentDescription ?: (currentFocus as? TextView)?.text)?.toString()
        val grille = LinearLayout(this).apply { setPadding(margeX(), margeY(), margeX(), margeY()) }

        // Gauche : ce qui passe, les commandes, puis le code invités.
        val gauche = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "lecteur_gauche" }
        // 17-10 (l'auteur, point 23) : tout centré dans la partie gauche — la pochette, dessous le titre et l'artiste, puis la
        // barre sur toute la largeur, puis les commandes.
        // La pochette, empilée au-dessus du titre, laisse sa place au code invités (zone sûre de 9 % : rien ne dépasse).
        // Grande police : le code invités (et son bouton « Fermer le mode soirée ») prend plus de hauteur.
        val cote = (h * if (e.qr.isEmpty()) 0.30f else if (grandePolice()) 0.21f else 0.26f).coerceIn(90f, 220f)
        val haut = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        haut.addView(pochette(cote, m, e.lecteur).apply { tag = "lecteur_pochette" })
        // Rien ne joue : le clin d'œil de la fin de file (le même sur tous les écrans), sinon « rien ne joue ».
        val clin = if (vide && ici) Party.clinDOeil(this, e) else null
        val titreLecteur = clin?.first ?: if (vide) getString(R.string.partyecran_rien_ne_joue) else Party.titreAffiche(m)
        haut.addView(texte(titreLecteur, 24f, theme.encre, Polices.outfit(this)).apply {
            tag = "lecteur_titre"; gravity = Gravity.CENTER_HORIZONTAL; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(12f), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val sous = clin?.second ?: listOf(m.artiste, m.album).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sous.isNotEmpty()) haut.addView(texte(sous, 15f, theme.encre2).apply {
            gravity = Gravity.CENTER_HORIZONTAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 1.93.06 (diagnostic de la 2.5.11.1 : la soirée jouait sur un autre lecteur que celui de la télé) : l'enceinte où
        // joue la soirée, dite sous le titre (comme sur le téléphone, « En cours sur … »).
        if (e.actif && e.lecteurNom.isNotEmpty()) haut.addView(texte(getString(R.string.partyecran_enceinte_nom, e.lecteurNom), 13f, theme.or, Polices.demiGras(this)).apply {
            tag = "lecteur_enceinte"; gravity = Gravity.CENTER_HORIZONTAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(4f), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 1.91.0 : la barre devient un curseur (focus, gauche / droite) quand on peut sauter dans le titre.
        val b = barreLecture(e, m).also { barre = it; it.tag = "lecteur_barre" }
        haut.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(28f)).apply { topMargin = px(6f) })
        tempsTexte = texte("", 13f, theme.encre3).apply { gravity = Gravity.CENTER_HORIZONTAL }
            .also { haut.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        gauche.addView(haut, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 17-10 (l'auteur, point 14) : pas d'écran intermédiaire au lancement ; un nouveau titre arrive en fondu doux.
        if (titreLecteur != titreMontre) {
            if (titreMontre != null) { haut.alpha = 0f; haut.translationY = px(8f).toFloat(); haut.animate().alpha(1f).translationY(0f).setDuration(450).start() }
            titreMontre = titreLecteur
        }
        // Les commandes : précédent, lecture/pause, suivant, volume — centrées.
        val cmd = LinearLayout(this).apply { gravity = Gravity.CENTER; tag = "lecteur_commandes"; setPadding(0, px(14f), 0, 0) }
        val joue = m.etat == "playing"
        cmd.addView(rondTele("precedent", getString(R.string.vuewidget_precedent), false) { precedentDemandeA = System.currentTimeMillis(); commande("precedent") })
        val lecture = rondTele(if (joue) "pause" else "lecture", if (joue) getString(R.string.vuewidget_pause) else getString(R.string.vuewidget_lecture), true) {
            basculerLecture()
        }
        cmd.addView(lecture)
        cmd.addView(rondTele("suivant", getString(R.string.vuewidget_suivant), false) { commande("suivant") })
        if (ici && Party.volumeReglable(e.lecteurNom, m)) {
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
        for (i in 0 until cmd.childCount) (cmd.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.let { it.leftMargin = px(5f); it.rightMargin = px(5f) }
        // Sans Party ici, rien à piloter : les commandes restent visibles, éteintes.
        if (!ici) for (i in 0 until cmd.childCount) cmd.getChildAt(i).apply { alpha = 0.4f; isFocusable = false; isClickable = false }
        // 1.93.06 (marge de confort + bord réglé + grande police) : la ligne se resserre si elle est plus large que la colonne.
        val largeurGauche = ((resources.displayMetrics.widthPixels - 2 * margeX() - px(28f)) / 2.15f).toInt()
        cmd.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        if (cmd.measuredWidth > largeurGauche) resserrer(cmd, largeurGauche.toFloat() / cmd.measuredWidth)
        gauche.addView(cmd, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (retour.isNotEmpty()) gauche.addView(texte(retour, 14f, theme.encre2).apply { setPadding(0, px(8f), 0, 0) })
        gauche.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        if (e.qr.isNotEmpty()) {
            val q = (h * if (grandePolice()) 0.22f else 0.26f).coerceIn(90f, 160f)
            qrImage(Party.qrAvecTheme(e.qr, theme), q)?.let { (image, coteQr) ->
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = fond(Palette.melanger(theme.fond, theme.carte, 0.7f), 14f); setPadding(px(10f), px(10f), px(14f), px(10f)) }
                r.addView(ImageView(this).apply { setImageDrawable(image); scaleType = ImageView.ScaleType.FIT_XY; tag = "lecteur_qr"; contentDescription = getString(R.string.partyecran_qr_description) }, LinearLayout.LayoutParams(coteQr, coteQr))
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14f), 0, 0, 0) }
                col.addView(texte(getString(R.string.partyecran_proposer_titre), 16f, theme.encre, Polices.demiGras(this)))
                col.addView(texte(getString(R.string.partyecran_flashez_soiree), 13f, theme.encre2))
                // 17-10 (l'auteur, point 8) : fermer le mode soirée d'ici, sans passer par les réglages.
                if (ici) col.addView(boutonTele(getString(R.string.partyecran_soiree_fermer), false) { basculerInvites(e) }.apply {
                    textSize = 13f; minHeight = px(32f); setPadding(px(12f), px(4f), px(12f), px(4f)); tag = "soiree_fermer"
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) })
                r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                gauche.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } else if (ici && e.actif && !e.invitesOuverts) {
            // Mode soirée fermé : le dire, et le rouvrir d'un appui (un nouveau QR code).
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fond(Palette.melanger(theme.fond, theme.carte, 0.7f), 14f); setPadding(px(14f), px(10f), px(14f), px(10f)) }
            r.addView(texte(getString(R.string.partyecran_soiree_fermee), 13f, theme.encre2))
            r.addView(boutonTele(getString(R.string.partyecran_soiree_ouvrir), false) { basculerInvites(e) }.apply {
                textSize = 13f; minHeight = px(36f); setPadding(px(14f), px(6f), px(14f), px(6f)); tag = "soiree_ouvrir"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
            gauche.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 1.93.06 : la colonne tient dans la hauteur utile (bord réglé + marge de confort, grande police) : la pochette
        // rétrécit de ce qui dépasse, jamais sous 64 dp.
        gauche.measure(View.MeasureSpec.makeMeasureSpec(largeurGauche, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val trop = gauche.measuredHeight - (resources.displayMetrics.heightPixels - 2 * margeY())
        if (trop > 0) haut.findViewWithTag<View>("lecteur_pochette")?.let { p ->
            val c = maxOf(px(64f), p.layoutParams.height - trop - px(2f))
            p.layoutParams = p.layoutParams.apply { width = c; height = c }
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
        // Règle de l'auteur (15-10) : l'enceinte se choisit dans « ⚙ Réglages » ; ici, la recherche seule.
        tete.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val ajouter = boutonTele(getString(R.string.partyecran_ajouter_titre), true) { ouvrirRecherche(e) }.apply {
            textSize = 14f; tag = "lecteur_ajouter"
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = v.width / 2f }
        }
        tete.addView(ajouter)
        // 17-10 (l'auteur, point 18) : « ⚙ Réglages » à droite d'« Ajouter un titre » (avant : tout en bas de la file).
        // Réponse de l'auteur (15-10) : les réglages de la soirée (enchaînement, titres similaires, invités) et le chemin vers
        // les réglages de la musique sont derrière ce seul bouton.
        tete.addView(boutonTele(getString(R.string.musrech_reglages_soiree), false) { reglagesSoiree() }.apply {
            textSize = 14f; maxLines = 1; tag = "lecteur_reglages"
            contentDescription = getString(R.string.musrech_reglages_soiree_desc)
            // Calé à droite : il s'agrandit vers la gauche (jamais hors de la zone sûre).
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = v.width.toFloat() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(10f) })
        droite.addView(tete)
        // 1.93.06 (photo de l'auteur, vue « File d'attente ») : la liste finit au-dessus de l'aide en fondu (haut et bas), jamais
        // sur une ligne coupée net.
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false; Ui.cadre(this)
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(px(28f)); tag = "lecteur_file"
        }
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
        var ligneCourante: View? = null
        if (vide) {
            // Rien ne joue : les playlists, toujours à côté du lecteur (17-10, l'auteur, point 9 : plus de « Bibliothèque »).
            lignesPlaylistsTele(col)
        } else {
            // l'auteur (16/10) : les derniers titres joués au-dessus du titre en cours ; la file défile et le garde en vue.
            // 1.93.06 (point 2, TCL) : la liste des titres passés ne change plus de forme d'un état à l'autre.
            val cleCourant = Party.cleTitre(m.titre, m.artiste)
            passesFile = Party.passesStables(passesFile, e.filePasses, courantFile, cleCourant)
            courantFile = Party.Titre(m.titre, m.artiste, "", false, vignetteDuTitre(m).orEmpty())
            passesFile.forEach { t -> ligneFile(t.titre, t.artiste, t.image, false, false, t.takeIf { it.id.isNotEmpty() }); col.getChildAt(col.childCount - 1)?.alpha = 0.55f }
            ligneFile(Party.titreAffiche(m), m.artiste, "", true, false, null)
            ligneCourante = col.getChildAt(col.childCount - 1)
            if (e.file.isEmpty()) col.addView(texte(getString(R.string.partyecran_rien_dautre), 14f, theme.encre3).apply { setPadding(px(4f), px(10f), 0, 0) })
            e.file.forEach { t -> ligneFile(t.titre, t.artiste, t.image, false, t.invite, t) }
        }
        // 1.93.06 (l'auteur : « la liste ne doit plus sauter pendant la lecture ») : l'écran se rebâtit à chaque changement (lecture /
        // pause, volume, pochette) ; la file ne revient sur le titre en cours que si ce titre a changé. Sinon elle reste
        // là où on l'a fait défiler.
        val courant = if (vide) "" else Party.cleTitre(m.titre, m.artiste)
        // Un écran rebâti pendant la montée (pochette, pause, volume…) : la même montée continue sur la nouvelle file, là
        // où elle en est (jamais de mi-chemin figé, jamais de saut au bout). Un autre titre entre-temps l'arrête.
        val enRoute = monteeFile?.takeIf { it.enCours && courant == titreDefilFile }
        if (enRoute == null) { monteeFile?.annuler(); monteeFile = null }
        svFile = sv; ligneFileCourante = ligneCourante
        val garder = courant.isNotEmpty() && courant == titreDefilFile && defilFile >= 0
        val garde = defilFile
        // 1.93.06 (point 2) : au titre suivant, « En cours » garde sa place en haut et la liste y monte d'une ligne,
        // en douceur : on part de la ligne de l'ancien titre en cours (devenu le dernier passé) et on glisse jusqu'au
        // nouveau. Seul le défilement bouge ; il ne fait que monter. Tout autre changement se pose d'un coup.
        val dernierPasse = passesFile.takeIf { !vide }?.lastOrNull()?.let { Party.cleTitre(it.titre, it.artiste) }.orEmpty()
        val depart = ligneCourante?.takeIf { enRoute == null && !garder && Party.monteeDUneLigne(titreDefilFile, courant, dernierPasse) }
            ?.let { lc -> col.indexOfChild(lc).takeIf { it > 0 }?.let { col.getChildAt(it - 1) } }
        // 1.93.06 (point 6e, vidéo sur la TCL) : la position est posée AVANT le premier dessin. Avec un post, l'écran
        // rebâti se dessinait une image tout en haut (les titres passés), puis sautait sur le titre en cours.
        sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (sv.viewTreeObserver.isAlive) sv.viewTreeObserver.removeOnPreDrawListener(this)
                val va = monteeFile
                // La montée anime ce qui reste à monter jusqu'à « En cours » (et non une position) : un écran rebâti
                // en route, avec plus ou moins de titres passés au-dessus, la continue sans saut.
                if (va != null && va.enCours) ligneCourante?.let { lc -> sv.scrollTo(0, maxOf(0, lc.top - px(8f) - va.reste())) }
                else if (garder) sv.scrollTo(0, maxOf(0, garde + ((ligneCourante?.top ?: defilRef) - defilRef))) else ligneCourante?.let { lc ->
                    val cible = maxOf(0, lc.top - px(8f))
                    val de = depart?.let { maxOf(0, it.top - px(8f)) } ?: cible
                    sv.scrollTo(0, de)
                    // 1.93.06 (2e vidéo de la TCL) : image par image (MonteeFile), jamais réglée sur le temps.
                    if (cible > de) monteeFile = MonteeFile(cible - de, surPas = { reste ->
                        // La file et sa ligne « En cours » à l'écran à cet instant (l'écran a pu être rebâti depuis le départ).
                        val v = svFile?.takeIf { it.isAttachedToWindow }
                        val l = ligneFileCourante
                        if (v != null && l != null) { v.scrollTo(0, maxOf(v.scrollY, maxOf(0, l.top - px(8f) - reste))); suiviFile?.invoke(v.scrollY) }
                    }, surFin = { svFile?.let { defilFile = it.scrollY; defilRef = ligneFileCourante?.top ?: 0 } }).also { it.demarrer() }
                }
                suiviFile?.invoke(sv.scrollY)
                return true
            }
        })
        if (courant != titreDefilFile) { titreDefilFile = courant; defilFile = -1 }
        // Pendant la montée, la position n'est retenue qu'à l'arrivée.
        sv.viewTreeObserver.addOnScrollChangedListener { if (sv.isAttachedToWindow && colFile === col && monteeFile?.enCours != true) { defilFile = sv.scrollY; defilRef = ligneCourante?.top ?: 0 } }
        // Un doigt pendant la montée : elle s'arrête là où elle est, rien ne revient en arrière.
        sv.setOnTouchListener { _, _ -> monteeFile?.annuler(); false }
        droite.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 1.86.4 (photo de l'auteur) : l'aide a sa zone à elle, sous la file, sur une seule ligne, au-dessus de tout.
        // Campagne Musique (15-10) : Home Assistant injoignable alors que l'écran montre encore la soirée : la phrase
        // prend la place de l'aide (avant : l'écran figé ne disait rien).
        aideTele = texte(if (erreur.isNotEmpty()) erreur else if (deplace.isEmpty()) AIDE_TELE else AIDE_DEPLACER, 13f, if (erreur.isNotEmpty()) theme.pourpre else theme.encre3).apply {
            // 17-10 : deux lignes au plus (polices 130 % et zone sûre de 9 % : la phrase était coupée).
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            // 1.91.0 : une pastille ajustée au texte (et non plus une bande opaque qui coupait le fond animé), au-dessus
            // d'une ligne agrandie au focus.
            setPadding(px(10f), px(4f), px(10f), px(4f))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = px(12f).toFloat(); setColor((theme.fond and 0x00FFFFFF) or 0xE0000000.toInt()) }
            translationZ = px(8f).toFloat(); outlineProvider = null  // au-dessus de la file, sans ombre portée
            contentDescription = getString(R.string.mus_aide)
        }
        droite.addView(aideTele, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4f) })
        grille.addView(droite, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f))
        racine.addView(grille, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        racine.contentDescription = Party.NOM + " : " + Party.titreAffiche(m) + (if (m.artiste.isNotEmpty()) ", " + m.artiste else "")
        setContentView(racine)
        // Le focus revient où il était (même bouton ou même titre) ; à la première ouverture, sur lecture / pause.
        val cible = ligneDe(deplace) ?: focusAvant?.let { d -> trouverDesc(racine, d) } ?: if (vide) ajouter else lecture
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

    /**
     * Les touches de lecture de la télécommande pilotent la Party. 1.93.05 (l'auteur, retour 37) : les huit touches média
     * (lecture, pause, lecture/pause, stop, suivant, précédent, avance et recul de 10 s), comme les boutons de l'écran.
     * Le volume n'est jamais pris ici (retour 38) : le système règle le son et montre sa jauge.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val e = etat
        val a = ToucheMedia.action(keyCode)
        if (a != null && e != null && e.actif && (e.ici || e.participer || e.piloter)) {
            if ((event?.repeatCount ?: 0) > 0) return true
            val joue = e.maintenant.etat == "playing"
            when (a) {
                ToucheMedia.Action.BASCULER -> basculerLecture()
                ToucheMedia.Action.LECTURE -> if (!joue) basculerLecture()
                ToucheMedia.Action.PAUSE, ToucheMedia.Action.ARRET -> if (joue) basculerLecture()
                ToucheMedia.Action.SUIVANT -> commande("suivant")
                ToucheMedia.Action.PRECEDENT -> { precedentDemandeA = System.currentTimeMillis(); commande("precedent") }
                ToucheMedia.Action.AVANCER, ToucheMedia.Action.RECULER -> if (Saut.permis(e)) {
                    val p = sautCible ?: Party.positionAffichee(this, e, System.currentTimeMillis() / 1000.0)
                    if (p != null) sauter(p + if (a == ToucheMedia.Action.AVANCER) ToucheMedia.SAUT_S else -ToucheMedia.SAUT_S)
                }
            }
            return true
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
            setOnClickListener { action() }
        }
    }

    private fun rond(picto: String, desc: String, plein: Boolean, actif: Boolean, action: () -> Unit): View {
        val t = if (plein) 60f else 52f
        val f = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (plein) setColor(theme.or) else { setColor(0); setStroke(px(1.5f), theme.carteBord) } }
            contentDescription = desc; isClickable = actif; isFocusable = actif; alpha = if (actif) 1f else 0.4f
            setOnClickListener { action() }
        }
        f.addView(Picto(this, picto, if (plein) theme.fond else theme.encre), FrameLayout.LayoutParams(px(t * 0.4f), px(t * 0.4f), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(px(t), px(t)).apply { leftMargin = px(7f); rightMargin = px(7f) }
        return f
    }

    /** Règle de l'auteur (15-10) : la page Réglages du lecteur (téléphone) est ouverte au lieu du lecteur ; Retour y ramène. */
    private var parametresOuverts = false

    /** L'icône réglages du lecteur ; un point doré signale des propositions d'invités en attente. */
    private fun iconeReglages(e: Party.Etat): View {
        val enAttente = e.piloter && e.actif && e.ma && e.propositions.isNotEmpty()
        return texte(if (enAttente) "⚙ ${e.propositions.size}" else "⚙", 22f, if (enAttente) theme.or else theme.encre, Polices.gras(this)).apply {  // i18n-exception : pictogramme
            gravity = Gravity.CENTER; minWidth = px(52f); minHeight = px(52f); setPadding(px(10f), 0, px(10f), 0)
            background = fond(0, 999f, if (enAttente) theme.or else theme.carteBord)
            isClickable = true; isFocusable = true
            contentDescription = if (enAttente) getString(R.string.musrech_reglages_attente_desc, e.propositions.size) else getString(R.string.musrech_reglages_soiree_desc)
            setOnClickListener { parametresOuverts = true; construire(); defilement?.post { defilement?.scrollTo(0, 0) } }
        }
    }

    /** Une ligne de la page Réglages : titre, valeur dessous, « › » ; aux couleurs de l'écran Musique (carte du thème). */
    private fun ligneReglage(c: LinearLayout, titre: String, valeur: String, action: () -> Unit) {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(60f); setPadding(px(16f), px(12f), px(14f), px(12f))
            background = fond(theme.carte, 16f, theme.carteBord); isClickable = true; isFocusable = true
            contentDescription = listOf(titre, valeur).filter { it.isNotEmpty() }.joinToString(", ")
            Ui.anneauFocus(this, theme, 16f, agrandir = false)
            setOnClickListener { action() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(texte(titre, 16f, theme.encre, Polices.demiGras(this)))
        if (valeur.isNotEmpty()) col.addView(texte(valeur, 14f, theme.encre2).apply { setPadding(0, px(2f), 0, 0) })
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(texte("›", 20f, theme.encre3).apply { setPadding(px(10f), 0, 0, 0) })  // i18n-exception : pictogramme
        c.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
    }

    /**
     * La page Réglages (téléphone) : écran et enceinte (un Décideur), volume des enceintes du groupe, soirée
     * (enchaînement, titres similaires, invités et leurs propositions), file d'attente, réglages de la musique, arrêter.
     * Plus de 3 choix : une liste (règle du projet) ; deux : un appui change.
     */
    private fun parametresTelephone(c: LinearLayout, e: Party.Etat) {
        val m = e.maintenant
        // Les retours des commandes (« Pas fait… », une enceinte indisponible) se lisent aussi ici, et « En cours… ».
        if (occupe.isNotEmpty()) c.addView(texte(getString(R.string.choixmusique_en_cours), 14f, theme.or, Polices.demiGras(this)).apply { setPadding(0, px(8f), 0, 0) })
        if (erreur.isNotEmpty()) c.addView(texte(erreur, 14f, theme.pourpre).apply { setPadding(0, px(8f), 0, 0) })
        if (retour.isNotEmpty()) c.addView(texte(retour, 14f, theme.encre2).apply { setPadding(0, px(8f), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        if (e.piloter) {
            if (ecranChoisi.isEmpty() || e.ecrans.none { it.id == ecranChoisi }) ecranChoisi = e.ecran.ifEmpty { e.ecrans.firstOrNull()?.id.orEmpty() }
            if (lecteurChoisi.isEmpty() || e.lecteurs.none { it.id == lecteurChoisi }) lecteurChoisi = e.lecteur.ifEmpty { e.lecteurs.firstOrNull { it.etat != "unavailable" && it.ma }?.id ?: e.lecteurs.firstOrNull { it.etat != "unavailable" }?.id.orEmpty() }
            c.addView(Ui.section(this, getString(R.string.musrech_section_ecoute)))
            val nomEcran = e.ecrans.firstOrNull { it.id == ecranChoisi }?.nom ?: getString(R.string.partyecran_aucune_tele)
            ligneReglage(c, getString(R.string.partyecran_ecran), nomEcran) {
                if (e.ecrans.isEmpty()) return@ligneReglage
                Ui.dialogue(this).setTitle(getString(R.string.partyecran_ecran))
                    .setSingleChoiceItems(e.ecrans.map { it.nom }.toTypedArray(), e.ecrans.indexOfFirst { it.id == ecranChoisi }) { d, k -> d.dismiss(); ecranChoisi = e.ecrans[k].id; construire() }
                    .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
            }
            val lect = e.lecteurs.firstOrNull { it.id == lecteurChoisi }
            ligneReglage(c, getString(R.string.partyecran_enceinte_groupe), lect?.let { Party.nomLecteur(it) } ?: "—") { choisirEnceinteTelephone(e) }
            if (e.aideLecteurs.isNotEmpty() && (e.lecteurs.any { !it.dispo } || e.lecteurs.none { it.dispo && it.ma }))
                c.addView(texte(e.aideLecteurs, 13f, theme.encre3).apply { setPadding(0, px(8f), 0, 0) })
        }
        if (Party.detailVolumes(m)) ligneReglage(c, getString(R.string.party_volume_enceintes), "") { volumesDesEnceintes(m) }
        val soiree = lignesSoiree(e)
        if (soiree.isNotEmpty()) {
            c.addView(Ui.section(this, getString(R.string.musrech_section_soiree)))
            soiree.forEach { (titre, action) -> ligneReglage(c, titre, "", action) }
        }
        // 1.82.0 : les propositions des invités, à accepter ou refuser.
        if (e.piloter && e.actif && e.ma && e.propositions.isNotEmpty()) {
            section(c, getString(R.string.partyecran_invites))
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

        c.addView(Ui.section(this, getString(R.string.musrech_section)))
        ligneReglage(c, getString(R.string.musrech_reglages_raccourci).removeSuffix(" ›"), getString(R.string.musrech_section_phrase)) { ReglagesMusique.ouvrir(this) }
        if (e.piloter && (e.actif || occupe == "arreter")) {
            val t = if (occupe == "arreter") getString(R.string.choixmusique_en_cours) else getString(R.string.partyecran_arreter_nom, Party.NOM)
            c.addView(bouton(t, false) { commande("arreter") { it.copy(actif = false) } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(24f) })
        }
    }

    /** L'enceinte ou le groupe (téléphone) : liste, celles qui ne servent pas grisées avec la raison. */
    private fun choisirEnceinteTelephone(e: Party.Etat) {
        val l = e.lecteurs
        if (l.isEmpty()) return
        val noms = l.map { x -> Party.nomLecteur(x) + when { !x.dispo && x.etat == "unavailable" -> getString(R.string.mus_hors_ligne); !x.dispo -> getString(R.string.mus_hors_ma); else -> "" } }
        Ui.dialogue(this).setTitle(getString(R.string.partyecran_enceinte_groupe))
            .setSingleChoiceItems(noms.toTypedArray(), l.indexOfFirst { it.id == lecteurChoisi }) { d, k ->
                d.dismiss()
                val x = l[k]
                if (!x.dispo) { retour = Party.nomLecteur(x) + " : " + x.raison; construire(); return@setSingleChoiceItems }
                lecteurChoisi = x.id
                if (e.actif && x.id != e.lecteur) commande("enceinte", JSONObject().put("lecteur", x.id)) { it.copy(lecteur = x.id, lecteurNom = x.nom) } else construire()
            }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun construireTelephone() {
        val ancien = defilement?.scrollY ?: 0
        val sv = ScrollView(this).apply { isFillViewport = true }
        defilement = sv
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(28f), px(20f), px(28f)) }
        sv.addView(c)
        val e = etat
        // Règle de l'auteur (15-10) : le lecteur ne montre que l'essentiel ; tout le reste est dans « Réglages », derrière ⚙.
        val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (parametresOuverts) tete.addView(texte("‹", 30f, theme.encre, Polices.gras(this)).apply {  // i18n-exception : pictogramme
            gravity = Gravity.CENTER; minWidth = px(48f); minHeight = px(48f)
            background = fond(0, 999f, theme.carteBord); isClickable = true; isFocusable = true
            contentDescription = getString(R.string.musrech_retour_lecteur)
            setOnClickListener { parametresOuverts = false; construire() }
        }, LinearLayout.LayoutParams(px(48f), px(48f)).apply { rightMargin = px(12f) })
        tete.addView(texte(if (parametresOuverts) getString(R.string.musrech_reglages_soiree_titre) else Party.NOM, 28f, theme.encre, Polices.outfit(this)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!parametresOuverts && e != null) tete.addView(iconeReglages(e))
        c.addView(tete)
        if (e == null) {
            c.addView(texte(erreur.ifEmpty { getString(R.string.choixmusique_chargement) }, 16f, if (erreur.isEmpty()) theme.encre2 else theme.pourpre).apply { setPadding(0, px(16f), 0, 0) })
            if (erreur.isNotEmpty()) c.addView(bouton(getString(R.string.editeurwidget_reessayer), false) { erreur = ""; construire(); charger() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
            setContentView(habiller(sv)); return
        }
        compteFile = null; colFile = null  // posées par fileTelephone, sous le lecteur (17-10)
        if (parametresOuverts) { parametresTelephone(c, e); setContentView(habiller(sv)); sv.post { sv.scrollTo(0, ancien) }; return }
        val m = e.maintenant
        c.addView(texte(if (e.actif) getString(R.string.partyecran_en_cours_sur, e.ecranNom.ifEmpty { getString(R.string.partyecran_la_tele) }, e.lecteurNom) else getString(R.string.partyecran_arrete), 14f, if (e.actif) theme.or else theme.encre3, Polices.demiGras(this)).apply { setPadding(0, px(4f), 0, 0) })
        if (erreur.isNotEmpty()) c.addView(texte(erreur, 14f, theme.pourpre).apply { setPadding(0, px(8f), 0, 0) })
        if (retour.isNotEmpty()) c.addView(texte(retour, 14f, theme.encre2).apply { setPadding(0, px(8f), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })

        if (e.piloter) {
            if (ecranChoisi.isEmpty() || e.ecrans.none { it.id == ecranChoisi }) ecranChoisi = e.ecran.ifEmpty { e.ecrans.firstOrNull()?.id.orEmpty() }
            if (lecteurChoisi.isEmpty() || e.lecteurs.none { it.id == lecteurChoisi }) lecteurChoisi = e.lecteur.ifEmpty { e.lecteurs.firstOrNull { it.etat != "unavailable" && it.ma }?.id ?: e.lecteurs.firstOrNull { it.etat != "unavailable" }?.id.orEmpty() }
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

        // l'auteur (16/10, « super important ») : depuis le téléphone, transférer la lecture vers une enceinte ou une télé.
        // La sortie se lit et se change d'un appui, ici ; le reste des réglages reste derrière ⚙.
        if (e.piloter && e.lecteurs.isNotEmpty()) {
            val idSortie = e.lecteur.ifEmpty { lecteurChoisi }
            val nomSortie = e.lecteurs.firstOrNull { it.id == idSortie }?.let { Party.nomLecteur(it) } ?: e.lecteurNom
            c.addView(texte(getString(R.string.musrech_sortie, nomSortie.ifEmpty { "—" }), 15f, theme.encre, Polices.demiGras(this)).apply {
                isClickable = true; isFocusable = true; minHeight = px(48f); gravity = Gravity.CENTER_VERTICAL
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                background = fond(0, 999f, theme.carteBord); setPadding(px(16f), px(8f), px(16f), px(8f))
                contentDescription = getString(R.string.musrech_sortie_desc, nomSortie)
                setOnClickListener { choisirEnceinteTelephone(e) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })
        }

        // Ce qui passe.
        if (e.lecteur.isNotEmpty() || lecteurChoisi.isNotEmpty()) {
            val carte = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = fond(theme.carte, 18f, theme.carteBord); setPadding(px(14f), px(14f), px(14f), px(14f)) }
            carte.addView(pochette(64f, m, e.lecteur))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14f), 0, 0, 0) }
            col.addView(texte(Party.titreAffiche(m), 17f, theme.encre, Polices.demiGras(this)))
            if (m.artiste.isNotEmpty()) col.addView(texte(m.artiste, 14f, theme.encre2))
            // 1.91.0 : le curseur se glisse au doigt (48 dp de haut : une cible confortable), la bulle montre le temps visé.
            val b = barreLecture(e, m).also { barre = it }
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(Ui.CIBLE_MIN)).apply { topMargin = px(2f) })
            tempsTexte = texte("", 12f, theme.encre3).also { col.addView(it) }
            carte.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(carte, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18f) })

            if ((e.piloter || (e.participer && e.actif)) && e.lecteur.isNotEmpty()) {
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(14f), 0, 0) }
                val joue = m.etat == "playing"
                r.addView(rond("precedent", getString(R.string.vuewidget_precedent), false, true) { precedentDemandeA = System.currentTimeMillis(); commande("precedent") })
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
            }
        }
        // 17-10 (l'auteur, point 24) : la file, tout de suite sous le lecteur.
        if (e.actif || e.file.isNotEmpty()) fileTelephone(c, e)

        // 1.82.0 (retour d'essai) : choisir quoi jouer, et un état vide qui guide.
        if (e.piloter || (e.participer && e.actif)) {
            val lect = e.lecteur.ifEmpty { lecteurChoisi }
            val lectMa = e.lecteurs.firstOrNull { it.id == lect }?.ma ?: (lect == e.lecteur && e.lecteurMa)
            val nomLect = e.lecteurs.firstOrNull { it.id == lect }?.nom ?: e.lecteurNom
            when {
                !e.ma -> c.addView(texte(getString(R.string.partyecran_ma_non_relie), 14f, theme.pourpre).apply { setPadding(0, px(14f), 0, 0) })
                lect.isNotEmpty() && !lectMa -> c.addView(texte(getString(R.string.partyecran_enceinte_pas_ma), 14f, theme.encre2).apply { setPadding(0, px(14f), 0, 0) })
                // l'auteur (16/10, « rien, Musique arrêtée ») : aucune enceinte disponible — avant, l'écran restait muet.
                lect.isEmpty() -> {
                    c.addView(texte(getString(R.string.musrech_aucune_enceinte), 16f, theme.encre, Polices.demiGras(this)).apply { setPadding(0, px(18f), 0, 0) })
                    if (e.aideLecteurs.isNotEmpty()) c.addView(texte(e.aideLecteurs, 14f, theme.encre2).apply { setPadding(0, px(6f), 0, 0) })
                    c.addView(bouton(getString(R.string.musrech_reglages_raccourci), false) { ReglagesMusique.ouvrir(this) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
                }
                lect.isNotEmpty() -> {
                    if (Party.rienNeJoue(m)) {
                        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = fond(0, 18f, theme.carteBord); setPadding(px(16f), px(16f), px(16f), px(16f)) }
                        val clin = Party.clinDOeil(this, e)
                        v.addView(texte(clin?.first ?: getString(R.string.partyecran_rien_ne_joue), if (clin != null) 20f else 17f, theme.encre, Polices.demiGras(this)).apply { gravity = Gravity.CENTER })
                        v.addView(texte(clin?.second ?: getString(R.string.partyecran_choisis_playlist_point), 14f, theme.encre2).apply { gravity = Gravity.CENTER; setPadding(0, px(4f), 0, px(12f)) })
                        // l'auteur (16/10) : depuis un lecteur vide, trois entrées évidentes — la bibliothèque (musique locale
                        // comprise), les playlists, la recherche dans tous les services reliés à Music Assistant.
                        val joueDeja = e.actif && !Party.rienNeJoue(m)
                        val entrees = listOf<Triple<String, Boolean, () -> Unit>>(
                            Triple(getString(R.string.musrech_onglet_bibliotheque), true) { ChoixMusiqueActivity.ouvrir(this, lect, nomLect, joue = joueDeja, onglet = "album") },
                            Triple(getString(R.string.choixmusique_playlists), false) { ChoixMusiqueActivity.ouvrir(this, lect, nomLect, joue = joueDeja, onglet = "playlist") },
                            Triple(getString(R.string.musrech_entree_rechercher), false) { ChoixMusiqueActivity.ouvrir(this, lect, nomLect, joue = joueDeja, rechercher = true) })
                        for ((t, plein, ouvrir) in entrees) v.addView(bouton(t, plein) { ouvrir() },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
                        c.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16f) })
                    } else c.addView(bouton(getString(R.string.manifeste_choisir_musique), false) { ChoixMusiqueActivity.ouvrir(this, lect, nomLect, joue = e.actif && !Party.rienNeJoue(m)) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14f) })
                }
            }
        }

        setContentView(habiller(sv))
        sv.post { sv.scrollTo(0, ancien) }
    }

}
