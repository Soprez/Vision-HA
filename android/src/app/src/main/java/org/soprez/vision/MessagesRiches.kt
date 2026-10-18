// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les messages riches de Home Assistant sur la télé (Vision 1.67.0, docs/messages.md) : texte,
 * image, son ou vidéo, à la position et à la taille demandées, éventuellement bloquants.
 *
 * - Veille active (couche, activité ou rêve du système) : plein écran **dans** la veille, qui
 *   reprend ensuite. Sinon : une fenêtre par-dessus l'appli en cours.
 * - Tant qu'il n'est pas lu, le message reste sur l'accueil Vision (carte « Messages »).
 * - Bloquant « lu » : seulement « J'ai lu ». Bloquant « parent » : le code parent, une libération
 *   envoyée par Home Assistant, ou la limite de sécurité, comptée depuis l'affichage.
 * - Chaque étape est renvoyée à Home Assistant (accusé), rejouée au relevé suivant si le réseau
 *   manque. Un uid déjà reçu est ignoré (livraison « au moins une fois »).
 *
 * Téléphones : rien de tout cela, la notification native (Messages) suffit.
 */
object MessagesRiches {
    private const val PREFS = "messages_riches"
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ L'état, sur le disque

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Les messages pas encore lus (ou bloquants en cours), du plus ancien au plus récent. */
    fun enAttente(ctx: Context): List<JSONObject> {
        val a = try { JSONArray(prefs(ctx).getString("liste", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun ecrire(ctx: Context, l: List<JSONObject>) {
        prefs(ctx).edit().putString("liste", JSONArray(l).toString()).apply()
    }

    private fun maj(ctx: Context, uid: String, f: (JSONObject) -> Unit) {
        val l = enAttente(ctx)
        l.firstOrNull { it.optString("uid") == uid }?.let(f)
        ecrire(ctx, l)
    }

    private fun retirer(ctx: Context, uid: String) = ecrire(ctx, enAttente(ctx).filter { it.optString("uid") != uid })

    private fun dejaRecu(ctx: Context, uid: String): Boolean {
        val vus = (prefs(ctx).getString("vus", "") ?: "").split(',').filter { it.isNotEmpty() }
        if (uid in vus) return true
        prefs(ctx).edit().putString("vus", (vus.takeLast(199) + uid).joinToString(",")).apply()
        return false
    }

    /** Ce message est-il pour cet écran-ci (une télé) ? Les téléphones gardent la notification. */
    fun pourCetAppareil(ctx: Context, m: JSONObject): Boolean =
        m.optString("uid").isNotEmpty() && ReglagesTvActivity.estTele(ctx)

    // ------------------------------------------------------------------ Ce qu'apporte le relevé

    /** Les messages riches du relevé ; renvoie ceux qu'il reste à traiter comme avant. */
    fun recevoir(ctx: Context, a: JSONArray?): JSONArray? {
        a ?: return null
        // Module « messages » coupé : rien n'est gardé ni montré ici (Messages.afficher écarte le reste).
        if (!Modules.actif(ctx, Modules.MESSAGES)) return a
        val reste = JSONArray()
        var nouveaux = false
        for (i in 0 until a.length()) {
            val m = a.optJSONObject(i) ?: continue
            if (!pourCetAppareil(ctx, m)) { reste.put(m); continue }
            if (dejaRecu(ctx, m.optString("uid"))) continue
            m.put("recuA", System.currentTimeMillis())
            ecrire(ctx, enAttente(ctx) + m)
            nouveaux = true
        }
        if (nouveaux) main.post { suivant(ctx.applicationContext) }
        return reste
    }

    /** `liberer` du relevé : les bloquants « parent » levés depuis Home Assistant. */
    fun liberer(ctx: Context, a: JSONArray?) {
        a ?: return
        val tous = (0 until a.length()).any { a.opt(it)?.toString() == "*" }
        val ids = (0 until a.length()).mapNotNull { a.opt(it)?.toString()?.toIntOrNull() }.toSet()
        main.post {
            for (m in enAttente(ctx)) {
                if (m.optString("bloquant") == "parent" && (tous || m.optInt("id") in ids)) lever(ctx.applicationContext, m, "libere_parent", "ha")
            }
        }
    }

    /** À chaque relevé : périmés, limites de sécurité, accusés en souffrance. */
    fun entretenir(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            val maintenant = System.currentTimeMillis()
            for (m in enAttente(app)) {
                val affiche = m.optLong("afficheA")
                if (m.optString("bloquant") == "parent" && affiche > 0 && maintenant - affiche > m.optInt("limite") * 60_000L) {
                    lever(app, m, "libere_auto", "")
                } else if (m.optString("bloquant") == "non" && maintenant / 1000.0 > m.optDouble("expire", Double.MAX_VALUE)) {
                    accuser(app, m, "expire"); retirer(app, m.optString("uid"))
                }
            }
            renvoyerAccuses(app)
            if (courant == null) suivant(app)
        }
    }

    // ------------------------------------------------------------------ Les accusés

    private fun accuser(ctx: Context, m: JSONObject, etat: String, detail: String = "") = accuserId(ctx, m.optInt("id"), etat, detail)

    /** 2.0.0 : l'accusé d'un message par son numéro (téléphone, réponses) ; gardé et renvoyé si le réseau manque. */
    fun accuserId(ctx: Context, id: Int, etat: String, detail: String = "") {
        val corps = JSONObject().put("message", id).put("etat", etat).put("detail", detail)
        val attente = try { JSONArray(prefs(ctx).getString("accuses", "[]")) } catch (_: Exception) { JSONArray() }
        attente.put(corps)
        prefs(ctx).edit().putString("accuses", attente.toString()).apply()
        renvoyerAccuses(ctx)
    }

    private fun renvoyerAccuses(ctx: Context) {
        val cfg = Config(ctx)
        val base = cfg.urlActive.ifEmpty { cfg.adresses().firstOrNull() ?: return }
        if (!cfg.inscrit) return
        val attente = try { JSONArray(prefs(ctx).getString("accuses", "[]")) } catch (_: Exception) { return }
        if (attente.length() == 0) return
        prefs(ctx).edit().putString("accuses", "[]").apply()
        Thread {
            val echecs = JSONArray()
            for (i in 0 until attente.length()) {
                val a = attente.optJSONObject(i) ?: continue
                try {
                    Net.postUne(ctx, "$base/api/pc_parental/message", JSONObject(a.toString()).put("id", cfg.id).put("secret", cfg.secret))
                } catch (e: Net.Echec) {
                    // Refusé par Home Assistant (message inconnu…) : inutile d'insister.
                } catch (_: Exception) {
                    echecs.put(a)
                }
            }
            if (echecs.length() > 0) main.post {
                val l = try { JSONArray(prefs(ctx).getString("accuses", "[]")) } catch (_: Exception) { JSONArray() }
                for (i in 0 until echecs.length()) l.put(echecs.get(i))
                prefs(ctx).edit().putString("accuses", l.toString()).apply()
            }
        }.start()
    }

    // ------------------------------------------------------------------ L'hôte : la veille

    private var hote: FrameLayout? = null

    /** La veille s'affiche : les messages s'y montrent en plein écran. */
    fun poserHote(ctx: Context, cadre: FrameLayout) {
        main.post {
            hote = cadre
            if (courant != null) { val m = courant!!; detacher(); montrer(ctx.applicationContext, m) } else suivant(ctx.applicationContext)
        }
    }

    /** La veille se retire : un message en cours repasse par-dessus l'appli. */
    fun retirerHote(ctx: Context, cadre: FrameLayout) {
        if (hote !== cadre) return
        hote = null
        val m = courant
        if (m != null && dansHote) { detacher(); main.post { montrer(ctx.applicationContext, m) } }
    }

    /** La veille donne les touches au message affiché chez elle. */
    fun touche(e: KeyEvent): Boolean {
        val v = vue ?: return false
        if (!dansHote) return false
        v.dispatchKeyEvent(e)
        return true
    }

    fun toucher(e: MotionEvent): Boolean {
        val v = vue ?: return false
        if (!dansHote) return false
        v.dispatchTouchEvent(e)
        return true
    }

    // ------------------------------------------------------------------ L'affichage

    private var courant: JSONObject? = null
    private var vue: View? = null
    private var dansHote = false
    /** Lot Accueil (point 10) : d'où vient le message affiché, et si c'est sa carte (plein écran) qui glisse. */
    private var sens = Anim.Sens.CENTRE
    private var animeCarte = false
    private var joueur: ExoPlayer? = null
    private var sonnerie: android.media.Ringtone? = null

    /** Le prochain message à montrer : d'abord les bloquants. */
    private fun suivant(ctx: Context) {
        if (courant != null) return
        if (!Modules.actif(ctx, Modules.MESSAGES)) return
        val l = enAttente(ctx).filter { it.optLong("vuA") == 0L || it.optString("bloquant") != "non" }
        val m = l.firstOrNull { it.optString("bloquant") != "non" } ?: l.firstOrNull() ?: return
        montrer(ctx, m)
    }

    /** Remontre un message depuis l'accueil (carte « Messages »). */
    fun rouvrir(ctx: Context, uid: String) {
        if (!Modules.actif(ctx, Modules.MESSAGES)) return
        val m = enAttente(ctx).firstOrNull { it.optString("uid") == uid } ?: return
        if (courant != null) return
        montrer(ctx.applicationContext, JSONObject(m.toString()).put("duree", 0))
    }

    private fun montrer(ctx: Context, m: JSONObject) {
        courant = m
        val pleinEcran = hote != null || m.optString("position") == "plein_ecran" || m.optString("bloquant") != "non"
        val racine = construire(ctx, m, pleinEcran)
        vue = racine
        // Lot Accueil (point 10) : entrée douce (Anim). Posée invisible AVANT d'être attachée : aucune image sèche.
        val carte = racine.getChildAt(0)
        sens = Anim.sensPourPosition(m.optString("position"), pleinEcran)
        animeCarte = pleinEcran
        if (pleinEcran) { Anim.preparerFond(racine); Anim.preparer(carte, sens) } else Anim.preparer(racine, sens)
        val h = hote
        if (h != null) {
            dansHote = true
            h.addView(racine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            dansHote = false
            if (!fenetre(ctx, racine, m, pleinEcran)) {
                // Sans « Afficher par-dessus » : il attend sur l'accueil.
                courant = null; vue = null
                accuser(ctx, m, "erreur", "superposition"); return
            }
        }
        if (pleinEcran) { Anim.entrerFond(racine); Anim.entrer(carte, sens) } else Anim.entrer(racine, sens)
        racine.requestFocus()
        if (m.optLong("afficheA") == 0L) {
            m.put("afficheA", System.currentTimeMillis())
            maj(ctx, m.optString("uid")) { it.put("afficheA", m.optLong("afficheA")) }
            accuser(ctx, m, "affiche")
        }
        maj(ctx, m.optString("uid")) { it.put("vuA", System.currentTimeMillis()) }
        // 2.0.0 : son selon le réglage de l'appareil ; pause de la télé seulement si demandée ; lecture à voix haute.
        if (MessagesV2.sonAppareil(ctx) != MessagesV2.SON_SILENCIEUX) sonner(ctx, m)
        if (m.optBoolean("pause_tele")) MessagesV2.pauseMedia(ctx)
        if (m.optBoolean("voix") && m.optLong("parleA") == 0L) {
            m.put("parleA", System.currentTimeMillis()); maj(ctx, m.optString("uid")) { it.put("parleA", m.optLong("parleA")) }
            MessagesV2.parler(ctx, m.optString("title") + ". " + m.optString("text"))
        }
        val duree = m.optInt("duree")
        if (m.optString("bloquant") == "non" && duree > 0) main.postDelayed({ if (courant === m) fermer(ctx, m, "ferme") }, duree * 1000L)
        if (m.optString("bloquant") == "parent") {
            val reste = m.optInt("limite") * 60_000L - (System.currentTimeMillis() - m.optLong("afficheA"))
            main.postDelayed({ if (courant === m) lever(ctx, m, "libere_auto", "") }, reste.coerceAtLeast(0))
        }
    }

    /** La fenêtre par-dessus l'appli en cours, à la position et à la taille demandées. */
    private fun fenetre(ctx: Context, racine: View, m: JSONObject, pleinEcran: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(ctx)) return false
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ecran = ctx.resources.displayMetrics
        val part = when (m.optString("taille")) { "petite" -> 0.32f; "grande" -> 0.7f; else -> 0.48f }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            if (pleinEcran) WindowManager.LayoutParams.MATCH_PARENT else (ecran.widthPixels * part).toInt(),
            if (pleinEcran) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (pleinEcran) WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL),
            PixelFormat.TRANSLUCENT)
        lp.gravity = when (m.optString("position")) {
            "haut" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            "bas" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            "gauche" -> Gravity.START or Gravity.CENTER_VERTICAL
            "droite" -> Gravity.END or Gravity.CENTER_VERTICAL
            "haut_gauche" -> Gravity.TOP or Gravity.START
            "haut_droite" -> Gravity.TOP or Gravity.END
            "bas_gauche" -> Gravity.BOTTOM or Gravity.START
            "bas_droite" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.CENTER
        }
        if (!pleinEcran) {
            // Lot Accueil (point 10) : une marge transparente autour de la carte, pour qu'elle glisse sans être coupée
            // par le bord de sa fenêtre ; la fenêtre grandit d'autant, la carte garde sa place et sa taille.
            val marge = Ui.dp(ctx, 24f); val jeu = Ui.dp(ctx, Anim.GLISSEMENT_DP)
            (racine as? ViewGroup)?.clipToPadding = false
            racine.setPadding(jeu, jeu, jeu, jeu)
            lp.width += 2 * jeu
            val centreH = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
            val centreV = (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL
            lp.x = if (centreH) marge else marge - jeu; lp.y = if (centreV) marge else marge - jeu
        }
        // Pas d'animation de fenêtre du système par-dessus la nôtre.
        lp.windowAnimations = 0
        return try { wm.addView(racine, lp); true } catch (_: Exception) { false }
    }

    private fun construire(ctx: Context, m: JSONObject, pleinEcran: Boolean): FrameLayout {
        val bloquant = m.optString("bloquant")
        val cadre = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (e.action == KeyEvent.ACTION_UP && bloquant == "non") fermer(ctx, m, "ferme")
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
        }
        cadre.isFocusable = true
        if (pleinEcran) cadre.setBackgroundColor(0xE6000000.toInt())
        val carte = Ui.carte(ctx, Ui.CARTE).apply { elevation = Ui.dp(ctx, 8f).toFloat() }
        // 2.0.0 : « Nolan · important » ; un message important est mis en avant (cadre doré), sans rien bloquer.
        MessagesV2.entete(m).takeIf { it.isNotEmpty() }?.let { carte.addView(Ui.texte(ctx, it, if (pleinEcran) 16f else 13f, Ui.TEXTE_2)) }
        if (m.optBoolean("important")) carte.background = Ui.fond(Ui.CARTE, Ui.dp(ctx, 20f).toFloat(), Ui.dp(ctx, 3f), Ui.OR)
        // v2 : le titre n'est que l'expéditeur, déjà dans l'en-tête : on ne le répète pas.
        if (!(MessagesV2.estV2(m) && m.optString("title") == m.optString("de")))
            carte.addView(Ui.texte(ctx, m.optString("title").ifEmpty { "Vision" }, if (pleinEcran) 30f else 20f, Ui.OR, gras = true))
        val texte = m.optString("text")
        if (texte.isNotEmpty()) carte.addView(Ui.marge(ctx, Ui.texte(ctx, texte, if (pleinEcran) 22f else 16f, Ui.TEXTE), haut = 8f))
        media(ctx, m, carte, pleinEcran)
        val boutons = Ui.rangeeBoutons(ctx).apply { gravity = Gravity.END }
        when (bloquant) {
            "parent" -> {
                carte.addView(Ui.marge(ctx, Ui.texte(ctx, ctx.getString(R.string.messagesriches_en_attente), 16f, Ui.TEXTE_2), haut = 12f))
                val code = EditText(ctx).also { ClavierTele.brancher(it) }.apply {
                    hint = ctx.getString(R.string.biometrie_code_gestion); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    setTextColor(Ui.TEXTE); setHintTextColor(Ui.TEXTE_2); minWidth = Ui.dp(ctx, 160f)
                }
                boutons.addView(code)
                boutons.addView(Ui.marge(ctx, Ui.boutonPrimaire(ctx, ctx.getString(R.string.messagesriches_liberer)) {
                    if (Config(ctx).verifierCode(code.text.toString())) lever(ctx, m, "libere_parent", "code_parent")
                    else { code.text.clear(); code.hint = ctx.getString(R.string.messagesriches_code_incorrect) }
                }, gauche = 10f))
            }
            "lu" -> boutons.addView(Ui.boutonPrimaire(ctx, ctx.getString(R.string.messagesriches_j_ai_lu)) { fermer(ctx, m, "lu") })
            // 2.0.0 : à la télé, seulement les réponses proposées (pas de texte libre sans clavier).
            else -> if (MessagesV2.reponses(m).isNotEmpty()) {
                MessagesV2.reponses(m).forEachIndexed { k, r ->
                    boutons.addView(Ui.marge(ctx, Ui.boutonPrimaire(ctx, r) { repondre(ctx, m, r) }, gauche = if (k == 0) 0f else 10f))
                }
                boutons.addView(Ui.marge(ctx, Ui.boutonSecondaire(ctx, ctx.getString(R.string.commun_lot2_fermer)) { fermer(ctx, m, "ferme") }, gauche = 10f))
            } else {
                boutons.addView(Ui.boutonSecondaire(ctx, ctx.getString(R.string.messagesriches_plus_tard)) { fermer(ctx, m, "ferme") })
                boutons.addView(Ui.marge(ctx, Ui.boutonPrimaire(ctx, ctx.getString(R.string.messagesriches_j_ai_lu)) { fermer(ctx, m, "lu") }, gauche = 10f))
            }
        }
        carte.addView(Ui.marge(ctx, boutons, haut = 16f))
        val lpCarte = FrameLayout.LayoutParams(
            if (pleinEcran) (ctx.resources.displayMetrics.widthPixels * 0.7f).toInt() else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        cadre.addView(carte, lpCarte)
        // Le premier bouton utile a le focus (télécommande) : la première réponse proposée s'il y en a.
        cadre.post { (if (MessagesV2.reponses(m).isNotEmpty() && bloquant == "non") boutons.getChildAt(0) else boutons.getChildAt(boutons.childCount - 1) ?: carte)?.requestFocus() }
        return cadre
    }

    /** Image, son ou vidéo, lus directement chez Home Assistant (URL propre à ce message). */
    private fun media(ctx: Context, m: JSONObject, carte: LinearLayout, pleinEcran: Boolean) {
        val media = m.optJSONObject("media") ?: return
        val cfg = Config(ctx)
        val base = cfg.urlActive.ifEmpty { cfg.adresses().firstOrNull() ?: return }
        val url = base + media.optString("url")
        val hauteur = (ctx.resources.displayMetrics.heightPixels * (if (pleinEcran) 0.55f else 0.3f)).toInt()
        when (m.optString("type")) {
            "image" -> {
                val img = ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
                carte.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hauteur).apply { topMargin = Ui.dp(ctx, 10f) })
                Thread {
                    val octets = Net.getBytes(ctx, url)
                    val bmp = octets?.let { b ->
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(b, 0, b.size, o)
                        var echelle = 1
                        while (o.outWidth / (echelle * 2) >= 1920 || o.outHeight / (echelle * 2) >= 1080) echelle *= 2
                        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = echelle })
                    }
                    main.post { if (bmp != null) img.setImageBitmap(bmp) else accuser(ctx, m, "erreur", "media") }
                }.start()
            }
            "audio", "video" -> {
                val p = ExoPlayer.Builder(ctx).build()
                joueur?.release(); joueur = p
                if (m.optString("type") == "video") {
                    val tv = TextureView(ctx)
                    carte.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hauteur).apply { topMargin = Ui.dp(ctx, 10f) })
                    p.setVideoTextureView(tv)
                } else {
                    carte.addView(Ui.marge(ctx, Ui.texte(ctx, ctx.getString(R.string.messagesriches_message_sonore), 15f, Ui.TEXTE_2), haut = 8f))
                }
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { accuser(ctx, m, "erreur", "lecteur") }
                })
                p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                p.prepare(); p.playWhenReady = true
            }
        }
    }

    private fun sonner(ctx: Context, m: JSONObject) {
        if (m.optString("type") in setOf("audio", "video")) return
        val son = m.optString("sound")
        if (son.isEmpty() || son.contains("Silent", true) || son.contains("silencieux", true)) return
        // 1.90.2 : sur une télé, le son de l'appli, au volume de la télé (canal média) ; voir [SonTele].
        if (ReglagesTvActivity.estTele(ctx)) {
            val r = SonTele.choix(son, m.optBoolean("loop"), m.optBoolean("important")) ?: return
            if (SonTele.jouer(ctx, r, m.optBoolean("loop")) && m.optBoolean("loop")) main.postDelayed({ SonTele.arreter() }, 60_000)
            return
        }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            sonnerie = RingtoneManager.getRingtone(ctx, uri)?.apply {
                if (Build.VERSION.SDK_INT >= 28) isLooping = m.optBoolean("loop")
                play()
            }
            if (m.optBoolean("loop")) main.postDelayed({ sonnerie?.stop() }, 60_000)
        } catch (_: Exception) {}
    }

    /** 2.0.0 : une réponse proposée, choisie à la télécommande : elle part, le message est lu. */
    private fun repondre(ctx: Context, m: JSONObject, r: String) {
        if (courant !== m) return
        accuser(ctx, m, "repondu", r)
        retirer(ctx, m.optString("uid"))
        detacher(anime = true)
        main.post { suivant(ctx) }
    }

    /** Ferme la fenêtre : « lu » le retire, « ferme » le laisse sur l'accueil. */
    private fun fermer(ctx: Context, m: JSONObject, etat: String) {
        if (courant !== m) return
        accuser(ctx, m, etat)
        if (etat == "lu") retirer(ctx, m.optString("uid"))
        detacher(anime = true)
        main.postDelayed({ suivant(ctx) }, 600)
    }

    /** Le mode parent accordé à distance (déverrouillage) lève tous les bloquants « parent ». */
    fun leverParent(ctx: Context, detail: String) {
        main.post { for (m in enAttente(ctx)) if (m.optString("bloquant") == "parent") lever(ctx.applicationContext, m, "libere_parent", detail) }
    }

    /** Lève un bloquant « parent » (code parent, Home Assistant, limite de sécurité). */
    private fun lever(ctx: Context, m: JSONObject, etat: String, detail: String) {
        accuser(ctx, m, etat, detail)
        retirer(ctx, m.optString("uid"))
        if (courant?.optString("uid") == m.optString("uid")) { detacher(anime = true); main.postDelayed({ suivant(ctx) }, 600) }
    }

    /**
     * Module « messages » coupé : le message affiché se retire, sans accusé ni effacement (il revient si le
     * module est réactivé ; un bloquant « parent » n'y bloque donc plus l'appareil).
     */
    fun suspendre() { main.post { if (courant != null) detacher(anime = true) } }

    /**
     * Retire le message affiché. `anime` (lot Accueil, point 10) : sortie douce, la vue n'est retirée qu'à la fin ;
     * sans (passage veille ↔ fenêtre), retrait immédiat. L'état (courant, vue) est libéré tout de suite dans les deux cas.
     */
    private fun detacher(anime: Boolean = false) {
        main.removeCallbacksAndMessages(null)
        try { sonnerie?.stop() } catch (_: Exception) {}
        sonnerie = null
        SonTele.arreter()
        joueur?.release(); joueur = null
        val v = vue
        if (v != null) {
            val chezHote = dansHote
            val enlever = {
                if (chezHote) (v.parent as? ViewGroup)?.removeView(v)
                else try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
                Unit
            }
            val carte = (v as? ViewGroup)?.getChildAt(0)
            when {
                !anime -> enlever()
                animeCarte && carte != null -> { Anim.sortirFond(v); Anim.sortir(carte, sens, enlever) }
                else -> Anim.sortir(v, sens, enlever)
            }
        }
        vue = null; courant = null; dansHote = false
    }
}
