// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 1.86.5 (l'auteur : « quand je reviens, la musique doit continuer… l'avoir sur l'accueil ») : le mini-lecteur de l'accueil.
 * Pochette, titre, lecture/pause et suivant ; toucher (ou OK) ramène à l'écran Musique.
 *
 * Règles de l'accueil : il se relit tout seul et ne change que ce qui a changé (jamais l'accueil rebâti, jamais de
 * clignotement) ; il ne prend jamais le focus de lui-même ; caché quand rien ne joue. En option (non par défaut), la
 * pochette floutée sert de fond à l'accueil.
 */
class MiniLecteur(ctx: Context, private val theme: Theme, private val tele: Boolean, private val fond: ImageView?) : LinearLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private var etat: Party.Etat? = null
    private var empreinte = ""
    private var attache = false
    private var lecture = false
    /** 1.86.7 : l'état demandé tient jusqu'à la confirmation de l'enceinte (8 s au plus). */
    private var voulu = ""
    private var voulueDepuis = 0L
    private val img = ImageView(ctx)
    private val titre = TextView(ctx)
    private val artiste = TextView(ctx)
    private val boutons = LinearLayout(ctx)
    private val picLecture = Picto(ctx, "pause", theme.encre)
    private val relire = Runnable { charger() }

    private fun px(v: Float) = Ui.dp(context, v)
    private fun t(v: Float) = if (tele) v * 1.25f else v

    init {
        visibility = GONE
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = px(18f).toFloat(); setColor(Palette.melanger(theme.fond, theme.carte, 0.85f)); setStroke(px(1f), theme.carteBord) }
        setPadding(px(8f), px(8f), px(10f), px(8f))
        isClickable = true; contentDescription = "Musique en cours"
        setOnClickListener { Party.ouvrir(context) }
        Ui.anneauFocus(this, theme, 18f, agrandir = false)
        img.scaleType = ImageView.ScaleType.CENTER_CROP; img.clipToOutline = true
        img.background = GradientDrawable().apply { cornerRadius = px(10f).toFloat(); setColor(Palette.melanger(theme.carte, theme.or, 0.12f)) }
        img.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(img, LayoutParams(px(t(44f)), px(t(44f))))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(px(12f), 0, px(8f), 0) }
        for ((v, taille, couleur) in listOf(Triple(titre, 15f, theme.encre), Triple(artiste, 12f, theme.encre2))) {
            v.textSize = t(taille); v.setTextColor(couleur); v.maxLines = 1; v.ellipsize = TextUtils.TruncateAt.END
            v.typeface = if (v === titre) Polices.demiGras(ctx) else Polices.texte(ctx)
            col.addView(v)
        }
        addView(col, LayoutParams(px(if (tele) 300f else 0f), LayoutParams.WRAP_CONTENT, if (tele) 0f else 1f))
        boutons.gravity = Gravity.CENTER_VERTICAL
        boutons.addView(rond(picLecture, "Lecture ou pause") { basculer() })
        boutons.addView(rond(Picto(ctx, "suivant", theme.encre), "Titre suivant") { commande("suivant") })
        addView(boutons)
    }

    private fun rond(p: Picto, desc: String, action: () -> Unit): View {
        val c = px(t(38f))
        return FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(1.5f), theme.carteBord) }
            contentDescription = desc; isClickable = true
            setOnClickListener { action() }
            Ui.anneauFocus(this, theme, t(19f), agrandir = false)
            addView(p, FrameLayout.LayoutParams((c * 0.42f).toInt(), (c * 0.42f).toInt(), Gravity.CENTER))
            layoutParams = LayoutParams(c, c).apply { leftMargin = px(8f) }
        }
    }

    /** L'option du fond se change dans Musique : l'accueil la suit tout de suite, sans attendre la relecture. */
    private val surReglage = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, cle ->
        if (cle == "fond_floute") main.post { empreinte = "x"; montrer(etat) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); attache = true; main.post(relire)
        FluxMusique.ecouter(context, "mini") { if (attache) { main.removeCallbacks(relire); main.post(relire) } }
        context.applicationContext.getSharedPreferences("party", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(surReglage)
    }
    override fun onDetachedFromWindow() {
        attache = false; main.removeCallbacks(relire)
        FluxMusique.retirer("mini")
        context.applicationContext.getSharedPreferences("party", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(surReglage)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        main.removeCallbacks(relire)
        if (v == View.VISIBLE && attache) main.post(relire)
    }

    private fun charger() {
        main.removeCallbacks(relire)
        if (!attache) return
        // Seulement là où Musique existe (la tuile le dit) ; sinon un coup d'œil de temps en temps, sans réseau.
        if (!Party.tuile(context)) { montrer(null); main.postDelayed(relire, 30_000); return }
        Thread {
            val e = try {
                val cfg = Config(context)
                Party.analyser(Net.post(context, cfg, "/api/pc_parental/party/etat", JSONObject().put("id", cfg.id).put("secret", cfg.secret), 8_000))
            } catch (_: Exception) { null }
            main.post {
                if (!attache) return@post
                if (e != null) montrer(e)
                main.postDelayed(relire, if (visibility == VISIBLE) 5_000L else 15_000L)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun joue(e: Party.Etat?) = e != null && e.lecteur.isNotEmpty() && e.maintenant.titre.isNotEmpty() &&
        e.maintenant.etat in setOf("playing", "paused", "buffering")

    private fun montrer(e: Party.Etat?) {
        etat = e
        var m = e?.maintenant
        if (m != null && voulu.isNotEmpty()) {
            if (m.etat == voulu || System.currentTimeMillis() - voulueDepuis > 8_000) voulu = "" else m = m.copy(etat = voulu)
        }
        val cle = if (!joue(e) || m == null) "" else listOf(m.titre, m.artiste, m.etat, m.pochette, e!!.actif, e.lecteur, Party.fondFloute(context)).joinToString("|")
        if (cle == empreinte) return  // rien n'a changé : rien n'est redessiné
        empreinte = cle
        if (cle.isEmpty() || m == null || e == null) { visibility = GONE; fond?.visibility = GONE; return }
        titre.text = m.titre
        artiste.text = m.artiste
        artiste.visibility = if (m.artiste.isEmpty()) GONE else VISIBLE
        lecture = m.etat == "playing"
        picLecture.nom = if (lecture) "pause" else "lecture"; picLecture.invalidate()
        // Les commandes : quand la soirée tourne et que cet appareil peut agir ; sinon la carte seule (OK → Musique).
        boutons.visibility = if (e.actif && (e.piloter || e.participer || e.ici)) VISIBLE else GONE
        contentDescription = "Musique en cours : ${m.titre}" + (if (m.artiste.isNotEmpty()) ", ${m.artiste}" else "") + ". Ouvrir Musique"
        Pochettes.poser(context, img, e.lecteur, m.pochette)
        visibility = VISIBLE
        poserFond(e.lecteur, m.pochette, 0)
    }

    /** Option « pochette floutée en fond d'accueil » : la pochette réduite à 16 px puis agrandie (flou doux, toutes versions). */
    private fun poserFond(lecteur: String, cle: String, essai: Int) {
        val f = fond ?: return
        if (!Party.fondFloute(context)) { f.visibility = GONE; return }
        val b: Bitmap? = Pochettes.garde(lecteur, cle)
        if (b == null) { if (essai < 10) main.postDelayed({ if (attache) poserFond(lecteur, cle, essai + 1) }, 500); return }
        val petit = Bitmap.createScaledBitmap(b, 16, 16, true)
        f.setImageDrawable(BitmapDrawable(resources, petit).apply { isFilterBitmap = true })
        f.visibility = VISIBLE
    }

    private fun basculer() {
        lecture = !lecture
        picLecture.nom = if (lecture) "pause" else "lecture"; picLecture.invalidate()
        voulu = if (lecture) "playing" else "paused"; voulueDepuis = System.currentTimeMillis()
        commande("lecture", if (lecture) "lecture" else "pause")
    }

    private fun commande(action: String, voulu: String = "") {
        val e = etat ?: return
        Thread {
            try {
                val cfg = Config(context)
                Net.post(context, cfg, "/api/pc_parental/party/commande", JSONObject().put("id", cfg.id).put("secret", cfg.secret)
                    .put("action", action).put("lecteur", e.lecteur).apply { if (voulu.isNotEmpty()) put("voulu", voulu) }, 9_000)
            } catch (_: Exception) {}
            main.post { if (attache) { main.removeCallbacks(relire); main.postDelayed(relire, 700) } }
        }.also { it.isDaemon = true }.start()
    }
}
