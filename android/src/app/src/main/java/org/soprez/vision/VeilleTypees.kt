// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Veille-lot : les cases typées du contrat dans la veille dessinée par l'appli (docs/veille-contrat.md §4.2).
 *
 * - **Images** (picture-elements, radar de pluie, instantanés de caméras, vignettes Frigate) : demandées à
 *   `/api/pc_parental/veille/media/<clé>` avec l'identité de l'appareil, décodées hors du fil principal à la taille
 *   utile (RGB 565), gardées dans un petit cache ; celles de l'écran suivant sont chargées avant qu'il n'arrive.
 * - **Caméras** : une seule en direct à la fois, sur le flux léger « _sub » ; en mode Auto, des images dès que
 *   l'appareil peine ou que le flux échoue (DirectVeille).
 * - **Iframe** : une seule WebView légère à la fois (sans son automatique), libérée une minute après son départ.
 * - **Derniers passages** (Frigate) : les vignettes, la caméra, l'objet et depuis quand.
 *
 * Rien n'est alloué à chaque image hors des textes déjà mis en cache : les libellés de dates sont calculés une fois.
 */
class VeilleTypees(private val v: VeilleView) {
    private val ctx = v.context
    private val main = Handler(Looper.getMainLooper())
    private val r0 = RectF()
    private val r1 = RectF()
    private val chemin = Path()
    private val pImage = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ------------------------------------------------------------------ images

    private class Image(val bitmap: Bitmap, val recue: Long)
    private val images = LinkedHashMap<String, Image>(16, 0.75f, true)
    private val enCours = HashSet<String>()
    private val demandesA = HashMap<String, Long>()
    private var executeur = Executors.newFixedThreadPool(2) { r -> Thread(r, "veille-medias").apply { isDaemon = true } }
    @Volatile private var actif = true

    /** Demande une image si elle manque ou date de plus de `ageMax` ms (jamais deux fois en même temps). */
    private fun demander(url: String, ageMax: Long) {
        if (url.isEmpty() || url in enCours) return
        val maintenant = SystemClock.uptimeMillis()
        val img = images[url]
        if (img != null && maintenant - img.recue < ageMax) return
        // Une image qui ne vient pas n'est pas redemandée à chaque image : au plus toutes les 10 s.
        if (maintenant - (demandesA[url] ?: 0L) < 10_000L) return
        demandesA[url] = maintenant
        enCours.add(url)
        val cible = (v.width / 2).coerceAtLeast(480)
        val app = ctx.applicationContext
        try {
            executeur.execute {
                var bm: Bitmap? = null
                try {
                    val cfg = Config(app)
                    val octets = Net.postBytes(app, cfg, url, JSONObject().put("id", cfg.id).put("secret", cfg.secret), 12_000)
                    if (octets != null && actif) {
                        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(octets, 0, octets.size, bornes)
                        var echelle = 1
                        while (bornes.outWidth / (echelle * 2) >= cible) echelle *= 2
                        bm = BitmapFactory.decodeByteArray(octets, 0, octets.size, BitmapFactory.Options().apply { inSampleSize = echelle; inPreferredConfig = Bitmap.Config.RGB_565 })
                    }
                } catch (_: Exception) {}
                main.post {
                    enCours.remove(url)
                    val b = bm ?: return@post
                    if (!actif) { b.recycle(); return@post }
                    images.put(url, Image(b, SystemClock.uptimeMillis()))?.bitmap?.recycle()
                    // Un petit cache : les plus anciennes images vues partent (les bitmaps sont libérés tout de suite).
                    while (images.size > 24) { val premier = images.entries.iterator().next(); images.remove(premier.key); premier.value.bitmap.recycle() }
                }
            }
        } catch (_: Exception) { enCours.remove(url) }
    }

    private fun image(url: String): Image? = images[url]?.takeIf { !it.bitmap.isRecycled }

    /** Le rythme de rafraîchissement d'une image selon ce qu'elle montre. */
    private fun ageMax(t: CasesVeille.Typee, url: String): Long = when (t) {
        is CasesVeille.Typee.Camera -> 5_000L
        is CasesVeille.Typee.Radar -> if (url == t.url) 600_000L else 60_000L
        is CasesVeille.Typee.Image -> if (url == t.url) 600_000L else 10_000L
        is CasesVeille.Typee.Clips -> 60_000L
        else -> 600_000L
    }

    // ------------------------------------------------------------------ cycle de chaque image

    private var directVoulu: String? = null
    private var directMontre = false
    private var iframeVue = false
    private val echecs = HashMap<String, Int>()

    /** Avant de dessiner : les médias de la page à l'écran (et de la suivante), la caméra à montrer en direct. */
    fun debutImage(page: List<CasesVeille.Typee>?, suivante: List<CasesVeille.Typee>?) {
        directMontre = false; iframeVue = false
        directVoulu = null
        if (page != null) {
            for (i in page.indices) { val t = page[i]; for (u in t.images()) demander(u, ageMax(t, u)) }
            val directs = page.mapNotNull { (it as? CasesVeille.Typee.Camera)?.direct }
            val choisi = DirectVeille.choisir(Local.directCameras(ctx), directs, v.peine(), 0)
            directVoulu = choisi?.takeIf { (echecs[it] ?: 0) < 2 }
        }
        if (suivante != null) for (i in suivante.indices) { val t = suivante[i]; for (u in t.images()) demander(u, ageMax(t, u)) }
    }

    /** Après le dessin : ce qui n'a pas été montré cette fois se cache (direct, iframe). */
    fun finImage() {
        val couche = v.cameras
        if (!directMontre && couche != null && directPose != null) { couche.garderSeulement(emptySet()); directPose = null }
        if (!iframeVue) iframe.masquer()
    }

    fun arreter() {
        actif = false
        iframe.detruire()
        directPose = null
        images.values.forEach { it.bitmap.recycle() }
        images.clear(); enCours.clear(); demandesA.clear(); echecs.clear()
        executeur.shutdownNow()
        executeur = Executors.newFixedThreadPool(2) { r -> Thread(r, "veille-medias").apply { isDaemon = true } }
        actif = true
    }

    // ------------------------------------------------------------------ dessin

    /** Dessine une case typée dans `r` (coordonnées du dessin, avant la dérive `dx`, `dy`). */
    fun dessiner(c: Canvas, r: RectF, h: Float, hu: Float, nom: String, texte: String, t: CasesVeille.Typee, a: Float, pf: Float, temps: Float, dx: Float, dy: Float) {
        when (t) {
            is CasesVeille.Typee.Meteo -> meteo(c, r, h, hu, nom, t, a, pf, temps)
            is CasesVeille.Typee.Agenda -> agenda(c, r, h, hu, nom, t, a)
            is CasesVeille.Typee.Image -> imageAvecElements(c, r, h, nom, t, a)
            is CasesVeille.Typee.Radar -> radar(c, r, h, nom, t, a)
            is CasesVeille.Typee.Camera -> camera(c, r, h, nom, t, a, temps, dx, dy)
            is CasesVeille.Typee.Iframe -> iframe(c, r, h, nom, t, a, dx, dy)
            is CasesVeille.Typee.Clips -> clips(c, r, h, nom, t, a)
        }
    }

    private fun entete(c: Canvas, r: RectF, h: Float, nom: String, a: Float): Float {
        val pad = min(r.height(), h * 0.3f) * 0.12f
        val petit = min(h * 0.026f, r.width() * 0.07f).coerceAtMost(r.height() * 0.2f)
        if (nom.isNotEmpty()) v.ecrire(c, v.decouper(nom.uppercase(Locale.getDefault()), petit, v.gras, r.width() - pad * 2, 1).firstOrNull() ?: "", r.left + pad, r.top + pad + petit, petit, v.encre3, a, v.gras, espacement = 0.1f)
        return r.top + pad + (if (nom.isNotEmpty()) petit * 1.6f else 0f)
    }

    private fun echelle(r: RectF, h: Float): Float = min(h, r.height() / 0.31f)

    /** Un bitmap dans `dest`, recadré (couvrir) ou entier (contenir), coins arrondis de la carte. */
    private fun poserImage(c: Canvas, bm: Bitmap, dest: RectF, couvrir: Boolean, a: Float, rayon: Float, recue: Long) {
        val ratio = if (couvrir) max(dest.width() / bm.width, dest.height() / bm.height) else min(dest.width() / bm.width, dest.height() / bm.height)
        val lw = bm.width * ratio; val lh = bm.height * ratio
        r1.set(dest.centerX() - lw / 2, dest.centerY() - lh / 2, dest.centerX() + lw / 2, dest.centerY() + lh / 2)
        c.save()
        chemin.reset(); chemin.addRoundRect(dest, rayon, rayon, Path.Direction.CW); c.clipPath(chemin)
        val apparition = ((SystemClock.uptimeMillis() - recue) / 500f).coerceIn(0f, 1f)
        pImage.alpha = (a * 255 * (0.25f + 0.75f * apparition)).toInt().coerceIn(0, 255)
        c.drawBitmap(bm, null, r1, pImage)
        c.restore()
    }

    /** Trois points qui battent : l'image arrive. */
    private fun attente(c: Canvas, cx: Float, cy: Float, h: Float, a: Float, temps: Float) {
        v.pForme.style = Paint.Style.FILL
        for (k in 0 until 3) {
            val b = 0.4f + 0.6f * (0.5f + 0.5f * kotlin.math.sin(temps * 3f - k * 0.8f))
            v.pForme.color = v.or; v.pForme.alpha = (a * 255 * b).toInt()
            c.drawCircle(cx + (k - 1) * h * 0.03f, cy, h * 0.008f, v.pForme)
        }
    }

    /** Un texte posé sur une image, sur sa petite pastille (toujours lisible, quel que soit le fond). */
    private fun etiquette(c: Canvas, s: String, cx: Float, cy: Float, taille: Float, a: Float) {
        if (s.isEmpty()) return
        val l = v.largeur(s, taille, v.normal)
        r0.set(Math.round(cx - l / 2 - taille * 0.4f).toFloat(), Math.round(cy - taille * 0.8f).toFloat(), Math.round(cx + l / 2 + taille * 0.4f).toFloat(), Math.round(cy + taille * 0.5f).toFloat())
        v.pForme.style = Paint.Style.FILL; v.pForme.color = v.carte or 0xFF000000.toInt(); v.pForme.alpha = (a * 225).toInt()
        c.drawRoundRect(r0, r0.height() * 0.35f, r0.height() * 0.35f, v.pForme)
        v.ecrire(c, s, cx, cy + taille * 0.18f, taille, v.encre, a, v.normal, Paint.Align.CENTER)
    }

    private fun meteo(c: Canvas, r: RectF, h: Float, hu: Float, nom: String, t: CasesVeille.Typee.Meteo, a: Float, pf: Float, temps: Float) {
        val e = echelle(r, h)
        v.cartePosee(c, r, e, a)
        val pad = min(r.height(), e * 0.3f) * 0.14f
        val haute = r.height() > hu * 1.5f
        val rIcone = min(r.height() * (if (haute) 0.16f else 0.3f), r.width() * 0.12f)
        val cxI = r.left + pad + rIcone; val cyI = if (haute) r.top + pad + rIcone * 1.2f else r.centerY()
        v.dessinerIcone(c, t.etat, cxI, cyI, rIcone, a * pf, temps)
        val tTemp = rIcone * 1.1f
        val x = cxI + rIcone * 1.5f
        v.ecrire(c, v.deg(t.temperature), x, cyI + tTemp * 0.35f, tTemp, v.encre, a, v.fin)
        val etat = t.etatTexte.ifEmpty { v.libelle(t.etat) }
        val lT = v.largeur(v.deg(t.temperature), tTemp, v.fin)
        v.ecrire(c, v.decouper(etat, tTemp * 0.42f, v.normal, max(1f, r.right - pad - (x + lT + pad * 0.5f)), 1).firstOrNull() ?: "", x + lT + pad * 0.5f, cyI + tTemp * 0.3f, tTemp * 0.42f, v.encre2, a, v.normal)
        if (!haute || t.previsions.isEmpty()) return
        // Les prévisions : une colonne par jour, au pixel près.
        val n = t.previsions.size
        val haut = cyI + rIcone * 1.6f; val bas = r.bottom - pad
        if (bas - haut < e * 0.08f) return
        val cols = GrilleVeille.repartir(GrilleVeille.px(r.left + pad), GrilleVeille.px(r.width() - pad * 2), IntArray(n) { 1 }, GrilleVeille.px(pad * 0.4f))
        val tJour = min((bas - haut) * 0.18f, e * 0.03f)
        for (i in 0 until n) {
            val p = t.previsions[i]
            val cx = cols[2 * i] + cols[2 * i + 1] / 2f
            v.ecrire(c, jourCourt(p), cx, haut + tJour, tJour, v.encre3, a, v.gras, Paint.Align.CENTER)
            val ri = min((bas - haut) * 0.17f, cols[2 * i + 1] * 0.28f)
            v.dessinerIcone(c, p.etat, cx, haut + tJour * 1.6f + ri, ri, a * pf, temps)
            val y = haut + tJour * 1.6f + ri * 2.2f + tJour
            v.ecrire(c, v.deg(p.max), cx - tJour * 0.3f, y, tJour * 1.05f, v.encre, a, v.normal, Paint.Align.RIGHT)
            v.ecrire(c, v.deg(p.min), cx + tJour * 0.3f, y, tJour * 1.05f, v.encre3, a, v.normal)
        }
    }

    // Libellés de dates : calculés une fois par objet (le relevé en apporte de nouveaux toutes les minutes).
    private val libelles = java.util.WeakHashMap<Any, String>()
    private val fmtJour = SimpleDateFormat("EEE", Locale.getDefault())
    private val fmtHeure = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fmtJourLong = SimpleDateFormat("EEEE", Locale.getDefault())

    private fun jourCourt(p: CasesVeille.Prevision): String = libelles.getOrPut(p) {
        p.quandMs?.let { fmtJour.format(java.util.Date(it)).trimEnd('.').replaceFirstChar { c -> c.uppercase() } } ?: ""
    }

    private fun quand(ev: CasesVeille.Evenement): String = libelles.getOrPut(ev) {
        val ms = ev.debutMs ?: return@getOrPut ""
        val cal = Calendar.getInstance(); val aujourdhui = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.timeInMillis = ms
        val jourEv = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.timeInMillis = System.currentTimeMillis(); cal.add(Calendar.DAY_OF_YEAR, 1)
        val demain = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        val jour = when (jourEv) {
            aujourdhui -> ctx.getString(R.string.agenda_aujourdhui)
            demain -> ctx.getString(R.string.agenda_demain)
            else -> fmtJourLong.format(java.util.Date(ms)).replaceFirstChar { c -> c.uppercase() }
        }
        jour + "  ·  " + if (ev.journee) ctx.getString(R.string.agenda_journee) else fmtHeure.format(java.util.Date(ms))
    }

    private fun agenda(c: Canvas, r: RectF, h: Float, hu: Float, nom: String, t: CasesVeille.Typee.Agenda, a: Float) {
        val e = echelle(r, h)
        v.cartePosee(c, r, e, a)
        var y = entete(c, r, h, nom, a)
        val pad = min(r.height(), e * 0.3f) * 0.12f
        val tQuand = min(e * 0.024f, hu * 0.16f); val tTitre = tQuand * 1.3f
        if (t.evenements.isEmpty()) { v.ecrire(c, ctx.getString(R.string.agenda_vide), r.left + pad, y + tTitre, tTitre, v.encre2, a, v.normal); return }
        val pas = tQuand * 1.25f + tTitre * 1.45f
        for (ev in t.evenements) {
            if (y + pas > r.bottom - pad * 0.5f) break
            v.ecrire(c, quand(ev), r.left + pad, y + tQuand, tQuand, v.or, a, v.gras)
            v.ecrire(c, v.decouper(ev.titre, tTitre, v.normal, r.width() - pad * 2, 1).firstOrNull() ?: "", r.left + pad, y + tQuand * 1.25f + tTitre, tTitre, v.encre, a, v.normal)
            y += pas
        }
    }

    private fun zoneMedia(r: RectF, h: Float, haut: Float): RectF {
        val pad = min(r.height(), h * 0.3f) * 0.08f
        r0.set(r.left + pad, haut, r.right - pad, r.bottom - pad)
        return r0
    }

    private fun imageAvecElements(c: Canvas, r: RectF, h: Float, nom: String, t: CasesVeille.Typee.Image, a: Float) {
        v.cartePosee(c, r, echelle(r, h), a)
        val zone = RectF(zoneMedia(r, h, entete(c, r, h, nom, a)))
        val fond = image(t.url)
        if (fond == null) { attente(c, zone.centerX(), zone.centerY(), h, a, SystemClock.uptimeMillis() / 1000f); return }
        // Un plan, une photo : l'image entière (contenir), les éléments posés à leurs pourcentages.
        poserImage(c, fond.bitmap, zone, couvrir = false, a = a, rayon = h * 0.012f, recue = fond.recue)
        val img = RectF(r1)
        for (k in t.calques) {
            val ci = image(k.url) ?: continue
            val lw = img.width() * k.largeur / 100f; val lh = lw * ci.bitmap.height / max(1, ci.bitmap.width)
            val cx = img.left + img.width() * k.x / 100f; val cy = img.top + img.height() * k.y / 100f
            r0.set(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2)
            pImage.alpha = (a * 255).toInt(); c.drawBitmap(ci.bitmap, null, r0, pImage)
        }
        val taille = (img.height() * 0.05f).coerceIn(h * 0.016f, h * 0.026f)
        for (el in t.elements) etiquette(c, el.texte, img.left + img.width() * el.x / 100f, img.top + img.height() * el.y / 100f, taille, a)
    }

    private fun radar(c: Canvas, r: RectF, h: Float, nom: String, t: CasesVeille.Typee.Radar, a: Float) {
        v.cartePosee(c, r, echelle(r, h), a)
        val zone = RectF(zoneMedia(r, h, entete(c, r, h, nom, a)))
        val fond = image(t.url)
        if (fond == null) { attente(c, zone.centerX(), zone.centerY(), h, a, SystemClock.uptimeMillis() / 1000f); return }
        poserImage(c, fond.bitmap, zone, couvrir = false, a = a, rayon = h * 0.012f, recue = fond.recue)
        val calque = t.calque?.url?.let { image(it) } ?: return
        val img = RectF(r1)
        poserImage(c, calque.bitmap, img, couvrir = false, a = a, rayon = h * 0.012f, recue = calque.recue)
    }

    private var directPose: String? = null
    private val rDirect = RectF()

    private fun camera(c: Canvas, r: RectF, h: Float, nom: String, t: CasesVeille.Typee.Camera, a: Float, temps: Float, dx: Float, dy: Float) {
        v.cartePosee(c, r, echelle(r, h), a)
        val zone = RectF(zoneMedia(r, h, entete(c, r, h, nom, a)))
        val couche = v.cameras
        val voulu = directVoulu
        val enDirect = voulu != null && voulu == t.direct && couche != null
        var videoVisible = false
        if (enDirect) {
            // Une seule caméra en direct : le flux léger, posé sur la case (la couche vidéo ne suit pas la toile : on ajoute la dérive).
            rDirect.set(zone.left + dx, zone.top + dy, zone.right + dx, zone.bottom + dy)
            couche!!.garderSeulement(setOf(voulu!!))
            couche.montrer(listOf(voulu to RectF(rDirect)), a, h * 0.012f)
            directPose = voulu; directMontre = true
            videoVisible = couche.enDirect(voulu)
            if (couche.echoue(voulu)) { echecs[voulu] = (echecs[voulu] ?: 0) + 1; couche.garderSeulement(emptySet()); directPose = null }
        }
        if (!videoVisible) {
            val img = image(t.url)
            if (img != null) poserImage(c, img.bitmap, zone, couvrir = true, a = a, rayon = h * 0.012f, recue = img.recue)
            else attente(c, zone.centerX(), zone.centerY(), h, a, temps)
        }
        // Le point : rouge et battant en direct, or pour une image.
        val rp = h * 0.007f
        v.pForme.style = Paint.Style.FILL
        v.pForme.color = if (videoVisible) 0xFFD13B3B.toInt() else v.or
        v.pForme.alpha = (a * 255 * (if (videoVisible) 0.6f + 0.4f * kotlin.math.sin(temps * 4f) else 1f)).toInt().coerceIn(0, 255)
        c.drawCircle(r.right - rp * 3f, r.top + rp * 3f, rp, v.pForme)
    }

    private val iframe = IframeVeille(v)

    private fun iframe(c: Canvas, r: RectF, h: Float, nom: String, t: CasesVeille.Typee.Iframe, a: Float, dx: Float, dy: Float) {
        v.cartePosee(c, r, echelle(r, h), a)
        val zone = zoneMedia(r, h, entete(c, r, h, nom, a))
        // Une seule iframe à la fois : la première de l'écran ; les autres gardent leur nom.
        if (iframeVue) return
        iframeVue = true
        iframe.montrer(t.url, zone.left + dx, zone.top + dy, zone.width(), zone.height(), a)
    }

    private fun ecartLisible(ts: Long?): String {
        if (ts == null) return ""
        val (u, n) = CasesVeille.ecart(ts, System.currentTimeMillis())
        return when (u) {
            "instant" -> ctx.getString(R.string.ecart_instant)
            "min" -> ctx.getString(R.string.ecart_min, n.toInt())
            "h" -> ctx.getString(R.string.ecart_h, n.toInt())
            "hier" -> ctx.getString(R.string.ecart_hier)
            else -> ctx.getString(R.string.ecart_j, n.toInt())
        }
    }

    // « il y a 5 min » change au plus une fois par minute : recalculé à la minute, pas à chaque image.
    private var minuteClips = -1L
    private val legendes = HashMap<CasesVeille.Clip, String>()

    private fun clips(c: Canvas, r: RectF, h: Float, nom: String, t: CasesVeille.Typee.Clips, a: Float) {
        val e = echelle(r, h)
        v.cartePosee(c, r, e, a)
        val haut = entete(c, r, h, nom, a)
        val pad = min(r.height(), e * 0.3f) * 0.08f
        if (t.liste.isEmpty()) { v.ecrire(c, ctx.getString(R.string.clips_vide), r.left + pad, haut + e * 0.03f, e * 0.026f, v.encre2, a, v.normal); return }
        val minute = System.currentTimeMillis() / 60_000L
        if (minute != minuteClips) { minuteClips = minute; legendes.clear() }
        // Autant de vignettes 16/9 que la case en tient, sur une ou deux rangées, au pixel près.
        val zoneH = r.bottom - pad - haut
        val rangs = if (zoneH > r.width() * 0.45f && t.liste.size > 2) 2 else 1
        val legende = (e * 0.022f).coerceAtLeast(h * 0.014f)
        val hCase = (zoneH - pad * (rangs - 1)) / rangs
        val hVig = hCase - legende * 1.6f
        val parRang = max(1, min(t.liste.size, ((r.width() - pad * 2) / max(1f, hVig * 16f / 9f + pad)).toInt()))
        val n = min(t.liste.size, parRang * rangs)
        val cols = GrilleVeille.repartir(GrilleVeille.px(r.left + pad), GrilleVeille.px(r.width() - pad * 2), IntArray(parRang) { 1 }, GrilleVeille.px(pad))
        for (i in 0 until n) {
            val k = t.liste[i]
            val col = i % parRang; val rang = i / parRang
            val x = cols[2 * col].toFloat(); val lw = cols[2 * col + 1].toFloat()
            val y = Math.round(haut + rang * (hCase + pad)).toFloat()
            r0.set(x, y, x + lw, y + hVig)
            val img = image(k.vignette)
            if (img != null) poserImage(c, img.bitmap, RectF(r0), couvrir = true, a = a, rayon = h * 0.01f, recue = img.recue)
            else { v.pForme.style = Paint.Style.FILL; v.pForme.color = v.encre3; v.pForme.alpha = (a * 40).toInt(); c.drawRoundRect(r0, h * 0.01f, h * 0.01f, v.pForme) }
            val texte = legendes.getOrPut(k) { listOf(k.camera, k.objetNom.ifEmpty { k.objet }, ecartLisible(k.quandMs)).filter { it.isNotEmpty() }.joinToString("  ·  ") }
            v.ecrire(c, v.decouper(texte, legende, v.normal, lw, 1).firstOrNull() ?: "", x, y + hVig + legende * 1.3f, legende, v.encre2, a, v.normal)
        }
    }
}

/**
 * L'iframe d'une case (carte iframe de Home Assistant) : une seule WebView, légère (pas de son automatique, pas de
 * zoom, pas de focus), posée sur la case par-dessus le dessin. Cachée dès que la case quitte l'écran, détruite une
 * minute plus tard (une autre veille, un autre écran n'en paient pas le prix).
 */
class IframeVeille(private val ancre: View) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var url = ""
    private val detruireTard = Runnable { detruire() }

    @SuppressLint("SetJavaScriptEnabled")
    fun montrer(adresse: String, x: Float, y: Float, l: Float, h: Float, alpha: Float) {
        val hote = ancre.parent as? FrameLayout ?: return
        main.removeCallbacks(detruireTard)
        var w = web
        if (w == null) {
            w = try { WebView(ancre.context) } catch (_: Throwable) { return }
            try {
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.mediaPlaybackRequiresUserGesture = true
                w.settings.allowFileAccess = false
                w.settings.setSupportZoom(false)
                w.isFocusable = false; w.isFocusableInTouchMode = false
                w.isVerticalScrollBarEnabled = false; w.isHorizontalScrollBarEnabled = false
                w.overScrollMode = View.OVER_SCROLL_NEVER
                w.webViewClient = object : WebViewClient() {
                    // Un certificat que l'appareil ne reconnaît pas : jamais outre, la case garde son nom.
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
                }
                w.clipToOutline = true
                // Juste au-dessus du dessin de la veille, sous la vidéo des caméras et la page de Home Assistant.
                hote.addView(w, hote.indexOfChild(ancre) + 1, FrameLayout.LayoutParams(1, 1))
                web = w
            } catch (_: Throwable) { try { w.destroy() } catch (_: Throwable) {}; return }
        }
        val lw = l.toInt().coerceAtLeast(1); val lh = h.toInt().coerceAtLeast(1)
        val lp = w.layoutParams
        if (lp.width != lw || lp.height != lh) w.layoutParams = FrameLayout.LayoutParams(lw, lh)
        w.x = Math.round(x).toFloat(); w.y = Math.round(y).toFloat()
        w.alpha = alpha
        if (w.visibility != View.VISIBLE) { w.visibility = View.VISIBLE; try { w.onResume() } catch (_: Exception) {} }
        if (adresse != url) { url = adresse; try { w.loadUrl(adresse) } catch (_: Exception) {} }
    }

    fun masquer() {
        val w = web ?: return
        if (w.visibility == View.VISIBLE) {
            w.visibility = View.INVISIBLE
            try { w.onPause() } catch (_: Exception) {}
            main.removeCallbacks(detruireTard); main.postDelayed(detruireTard, 60_000)
        }
    }

    fun detruire() {
        main.removeCallbacks(detruireTard)
        val w = web ?: return
        web = null; url = ""
        try { (w.parent as? FrameLayout)?.removeView(w) } catch (_: Exception) {}
        try { w.stopLoading(); w.loadUrl("about:blank"); w.destroy() } catch (_: Exception) {}
    }
}
