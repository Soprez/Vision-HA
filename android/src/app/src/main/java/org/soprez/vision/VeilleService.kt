// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.service.dreams.DreamService
import android.widget.FrameLayout

/**
 * L'écran de veille Vision : quand la télé n'est plus regardée, elle montre
 * la maison. Heure, météo et vigilance Météo-France, qui est devant quel
 * écran, et les chiffres que la maison a choisis dans Home Assistant.
 *
 * Gauche et droite feuillettent les tableaux, OK fige ; Retour rend la main.
 * Si une appli autorisée joue au premier plan (YouTube…), le rêve du système
 * l'aurait mise en pause : on lui laisse la place et la veille passe en couche.
 */
class VeilleService : DreamService() {
    private var vue: VeilleView? = null
    private var cadre: FrameLayout? = null
    private var appFond: String? = null
    private var parGeste = false
    private val gestes by lazy { GestesVeille(this, { vue }) { parGeste = true; finish() } }
    /** Un flux démarre pendant le rêve : permis en fond, la veille passe en couche pour ne pas le geler ; sinon elle s'efface. */
    private val guetteur by lazy { Flux.Guetteur(this, { _ -> if (Veille.permise(this)) { appFond = null; parGeste = true; finish(); Veille.ouvrir(this) } }, { parGeste = true; finish() }) }

    /** Module « veille » coupé : le système appelle encore l'économiseur ; écran neutre, puis on rend la main. */
    private var neutre = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        if (!Modules.actif(this, Modules.VEILLE)) {
            neutre = true
            isScreenBright = false
            setContentView(FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) })
            return
        }
        val cadre = FrameLayout(this)
        val v = VeilleView(this)
        val cameras = CamerasCouche(this)
        v.cameras = cameras
        cadre.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        cadre.addView(cameras, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        VeilleWeb.attacher(cadre, v)
        // 1.93.05 (retour 36) : par-dessus tout (page de HA comprise), le bandeau « Lecteur musique ».
        cadre.addView(CoinsVeille(cadre.context, v).apply { tag = "coins_veille" }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        vue = v
        setContentView(cadre)
        this.cadre = cadre
        MessagesRiches.poserHote(this, cadre)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (neutre) { parGeste = true; finish(); return }
        // 1.86.14 (retour d'essai) : la musique de cette télé joue (même pilotée d'un téléphone, Vision derrière le récepteur Cast) : pas de veille.
        if (VeilleMusique.bloque(this)) { parGeste = true; finish(); return }
        // Une appli joue : si elle a le droit de rester en fond (Spotify…), la veille passe dessus sans sa radio ;
        // sinon (Netflix, un jeu…) ce n'est pas le moment, on rend la main tout de suite.
        appFond = try { Local.appEnFond(this) } catch (_: Exception) { null }
        val proteger = try { Local.appAProteger(this) } catch (_: Exception) { false }
        if (proteger) { parGeste = true; finish(); return }
        if (appFond != null && Veille.permise(this)) {
            // Le rêve met l'appli en pause (YouTube s'arrête) : la couche, elle, la laisse jouer.
            appFond = null
            parGeste = true
            finish()
            Veille.ouvrir(this)
            return
        }
        Veille.fermer()
        vue?.demarrer()
        parGeste = false
        guetteur.demarrer()
    }

    override fun onDreamingStopped() {
        if (neutre) { super.onDreamingStopped(); return }
        guetteur.arreter()
        // Fermé par le système et non par la télécommande : si c'est un cast qui a réveillé la télé, la veille reviendra en couche.
        if (!parGeste && !Veille.ouverte) Flux.guetterApres(this)
        vue?.arreter()
        // L'appli de fond jouait : on reprend dessus, ou on va à l'accueil ? L'utilisateur choisit.
        val pk = appFond; appFond = null
        val eveille = try { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive } catch (_: Exception) { true }
        if (pk != null && eveille) RetourActivity.proposer(this, pk)
        super.onDreamingStopped()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (neutre) { finish(); true } else MessagesRiches.touche(event) || gestes.touche(event)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (neutre) { finish(); true } else MessagesRiches.toucher(event) || gestes.toucher(event)

    override fun onDetachedFromWindow() {
        if (neutre) { super.onDetachedFromWindow(); return }
        cadre?.let { MessagesRiches.retirerHote(this, it) }
        guetteur.arreter()
        vue?.arreter()
        vue = null
        super.onDetachedFromWindow()
    }
}
