// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import org.json.JSONObject

/**
 * Un message envoyé depuis Home Assistant, en plein écran : rappel,
 * avertissement, ou demande de temps d'un enfant à laquelle le parent
 * répond ici même.
 */
class MessageActivity : Activity() {
    /** Lot Accueil (point 10) : le fond (fondu) et la carte (fondu, glissement, agrandissement) animés par Anim. */
    private var fond: android.view.View? = null
    private var carteVue: android.view.View? = null
    private var sortie = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sansTransitionSysteme(fermeture = false)
        Ui.charger(this)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        pleinEcranDesLeDebut()
        val urgent = intent.getBooleanExtra("urgent", false)
        val demande = intent.getStringExtra("demande")?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        val acces = intent.getStringExtra("acces")?.let { try { JSONObject(it) } catch (_: Exception) { null } }

        val fond = Ui.colonne(this, Ui.dp(this, 28f)).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Palette.melanger(Ui.FOND, if (urgent) Ui.ROUGE else Ui.OR, 0.12f), Ui.FOND))
            // Le contenu reste hors des barres système (le fond, lui, les couvre).
            val marge = Ui.dp(this@MessageActivity, 28f)
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, ins ->
                v.setPadding(marge + ins.systemWindowInsetLeft, marge + ins.systemWindowInsetTop,
                    marge + ins.systemWindowInsetRight, marge + ins.systemWindowInsetBottom)
                ins
            }
        }
        val carte = Ui.carte(this, Ui.CARTE)
        val entete = Ui.rangee(this)
        // L'œil de Vision tel quel (une teinte en ferait un rond uni).
        // Lot 6 (APP-22) : le logo est un décor (le titre suit) : il n'est pas lu.
        entete.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_vision); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            android.widget.LinearLayout.LayoutParams(Ui.dp(this, 28f), Ui.dp(this, 28f)))
        entete.addView(Ui.marge(this, Ui.poids(Ui.texte(this,
            intent.getStringExtra("titre") ?: "Vision", 13f, if (urgent) Ui.ROUGE else Ui.OR, gras = true)), gauche = 10f))
        carte.addView(entete)
        carte.addView(Ui.marge(this, Ui.texte(this, intent.getStringExtra("texte") ?: "", 20f, Ui.TEXTE, gras = true), haut = 14f))

        if (demande != null) {
            carte.addView(Ui.marge(this, Ui.texte(this,
                getString(R.string.messageecran_accorder_a, demande.optString("nom", getString(R.string.messageecran_cet_appareil))), 14f, Ui.TEXTE_2), haut = 8f))
            // Lot 6 (APP-08) : les mêmes réponses que la notification et la gestion : accorder ce qui est demandé,
            // une autre durée (dessous), refuser.
            val r = Ui.rangeeBoutons(this)
            val accordee = Durees.accordee(demande.optInt("minutes", 0))
            val (lblAccorder, _, lblRefuser) = Durees.reponsesTemps(accordee)
            r.addView(Ui.poids(Ui.boutonPrimaire(this, lblAccorder) { repondre(demande, accordee) }))
            r.addView(Ui.marge(this, Ui.poids(Ui.boutonDanger(this, lblRefuser) { repondre(demande, 0) }), gauche = 8f))
            carte.addView(Ui.marge(this, r, haut = 18f))
            val prochain = Etat.lireDate(demande.optString("prochain"))
            carte.addView(Ui.marge(this, Ui.boutonSecondaire(this, if (prochain > System.currentTimeMillis()) getString(R.string.messageecran_autre_duree_jusqua, Etat.heure(prochain)) else getString(R.string.messageecran_autre_duree)) {
                Garde.exigerOctroi(this, ParentsActivity.RAISON) {  // FAILLE (l'auteur, 16-10) : à chaque fois
                    Temps.choisir(this, getString(R.string.messageecran_accorder_du_temps), prochain) { ch ->
                        Geste.enCours()
                        ReponseReceiver.envoyer(this, demande.optString("pc"), demande.optString("id"),
                            if (ch.jusquaPlage) Temps.minutesJusqua(prochain) else ch.minutes, ch.jusquaPlage) { res -> finirOuRelacher(res) }
                    }
                }
            }, haut = 8f))
        } else if (acces != null) {
            carte.addView(Ui.marge(this, Ui.texte(this,
                getString(R.string.messageecran_autoriser_pour, Demandes.quoi(acces), acces.optString("prenom", getString(R.string.messageecran_cet_appareil))), 14f, Ui.TEXTE_2), haut = 8f))
            carte.addView(Ui.marge(this, Ui.texte(this, Demandes.description(acces), 13f, Ui.TEXTE_2), haut = 4f))
            val lien = acces.optString("lien"); val motif = acces.optString("motif")
            if (lien.isNotEmpty()) carte.addView(Ui.marge(this, Ui.texte(this, lien, 13f, Ui.OR).apply {
                setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(if (lien.contains("://")) lien else "https://$lien"))) } catch (_: Exception) {} }
            }, haut = 6f))
            if (motif.isNotEmpty()) carte.addView(Ui.marge(this, Ui.texte(this, getString(R.string.messageecran_motif, motif), 13f, Ui.TEXTE_2), haut = 4f))
            acces.optString("raison").takeIf { it.isNotEmpty() }?.let { carte.addView(Ui.marge(this, Ui.texte(this, getString(R.string.messageecran_ferme_car, it), 13f, Ui.TEXTE_3), haut = 6f)) }
            val r = Ui.rangeeBoutons(this)
            listOf("temporaire", "toujours", "non").zip(Durees.reponsesAcces()).forEachIndexed { k, (dec, lbl) ->
                val btn = if (dec != "non") Ui.boutonPrimaire(this, lbl) { Garde.exigerOctroi(this, ParentsActivity.RAISON) { Geste.enCours(); Acces.repondre(this, acces.optString("pc"), acces.optString("id"), dec) { res -> finirOuRelacher(res) } } }
                          else Ui.boutonDanger(this, lbl) { Garde.exiger(this, ParentsActivity.RAISON) { Geste.enCours(); Acces.repondre(this, acces.optString("pc"), acces.optString("id"), dec) { res -> finirOuRelacher(res) } } }
                r.addView(Ui.marge(this, Ui.poids(btn), gauche = if (k == 0) 0f else 8f))
            }
            carte.addView(Ui.marge(this, r, haut = 18f))
        } else {
            carte.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.commun_lot2_ok)) { finish() }, haut = 22f))
        }
        fond.addView(carte)
        // Invisibles avant le premier rendu (fenêtre transparente, thème Vision.Message), puis l'entrée douce.
        this.fond = fond; carteVue = carte
        Anim.preparerFond(fond); Anim.preparer(carte, Anim.Sens.CENTRE)
        setContentView(fond, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        entrerQuandPose(fond, carte)
    }

    /**
     * Lot Accueil (point 10, retour de l'intégrateur) : la fenêtre a sa taille et sa mise en page finales AVANT la
     * première image — plein écran sous les barres (transparentes, aussi posées par le thème Vision.Message), encoche
     * comprise —, réglé avant setContentView. Sinon la fenêtre commençait sous la barre d'état puis s'agrandissait :
     * bord net du fond et carte qui saute.
     */
    @Suppress("DEPRECATION")
    private fun pleinEcranDesLeDebut() {
        val w = window
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.statusBarColor = android.graphics.Color.TRANSPARENT; w.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) { w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false }
        if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
            layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
        w.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    /** La taille que doit avoir la fenêtre : celle de la zone d'affichage de l'appli (écran entier hors multi-fenêtre). */
    @Suppress("DEPRECATION")
    internal fun tailleAttendue(): android.graphics.Point =
        if (android.os.Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.let { android.graphics.Point(it.width(), it.height()) }
        else android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }

    /**
     * L'entrée ne démarre qu'une fois la mise en page finale posée (fenêtre à sa taille, marges des barres appliquées) :
     * d'ici là fond et carte restent à alpha 0, donc invisibles. Garde-fou : au plus [IMAGES_MAX] images d'attente.
     */
    private fun entrerQuandPose(fond: android.view.View, carte: android.view.View) {
        if (!Anim.actif(this)) { Anim.entrerFond(fond); Anim.entrer(carte, Anim.Sens.CENTRE); return }
        val decor = window.decorView
        var images = 0
        decor.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val attendue = tailleAttendue()
                val insetsPoses = android.os.Build.VERSION.SDK_INT < 23 || fond.rootWindowInsets != null
                val pose = decor.width == attendue.x && decor.height == attendue.y && fond.width == decor.width &&
                    fond.height == decor.height && insetsPoses && !fond.isLayoutRequested
                if (pose || ++images >= IMAGES_MAX) {
                    if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
                    Anim.entrerFond(fond); Anim.entrer(carte, Anim.Sens.CENTRE)
                } else decor.postInvalidateOnAnimation()
                return true
            }
        })
    }

    /** Toute fermeture (OK, réponse réglée, retour) passe par la sortie douce ; l'activité finit à la fin de l'animation. */
    override fun finish() {
        if (sortie) return
        val f = fond; val c = carteVue
        if (f == null || c == null || !Anim.actif(this)) { finirSansAttendre(); return }
        sortie = true
        Anim.sortirFond(f)
        Anim.sortir(c, Anim.Sens.CENTRE) { finirSansAttendre() }
    }

    private fun finirSansAttendre() {
        super.finish()
        sansTransitionSysteme(fermeture = true)
    }

    /**
     * Pas d'animation de transition du système par-dessus la nôtre (le thème la coupe déjà) : Android 14+ par
     * overrideActivityTransition (ouverture et fermeture, posées dès l'ouverture), avant par overridePendingTransition.
     */
    @Suppress("DEPRECATION")
    private fun sansTransitionSysteme(fermeture: Boolean) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                if (!fermeture) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                }
            } else overridePendingTransition(0, 0)
        } catch (_: Exception) {}
    }

    private fun repondre(demande: JSONObject, minutes: Int) = Garde.exigerPour(this, ParentsActivity.RAISON, "/api/pc_parental/reponse",
        JSONObject().put("minutes", minutes)) {
        Geste.enCours()
        ReponseReceiver.envoyer(this, demande.optString("pc"), demande.optString("id"), minutes) { res -> finirOuRelacher(res) }
    }

    /** 1.77.0 : l'écran attend la réponse (« En cours… ») ; réglée, il se ferme ; ratée, il reste et le bouton revient. */
    private fun finirOuRelacher(res: Geste.Resultat) {
        if (isFinishing) return
        if (res.ok) finish() else Geste.relacher()
    }

    companion object {
        /** Attente maximale de la mise en page finale avant l'entrée (≈ 200 ms à 60 i/s). */
        const val IMAGES_MAX = 12

        fun intention(ctx: Context, titre: String, texte: String, urgent: Boolean, demande: JSONObject?, acces: JSONObject? = null): Intent =
            Intent(ctx, MessageActivity::class.java).apply {
                putExtra("titre", titre); putExtra("texte", texte); putExtra("urgent", urgent)
                if (demande != null) putExtra("demande", demande.toString())
                if (acces != null) putExtra("acces", acces.toString())
            }

        fun afficher(ctx: Context, titre: String, texte: String, urgent: Boolean, demande: JSONObject? = null, acces: JSONObject? = null) {
            try {
                ctx.startActivity(intention(ctx, titre, texte, urgent, demande, acces).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {}
        }
    }
}
