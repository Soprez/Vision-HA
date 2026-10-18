// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast

/**
 * 1.76.0 (point 6 bis) : les widgets Home Assistant de Vision, aussi en widgets Android système, ajoutables sur
 * n'importe quel lanceur. Même liste blanche que l'accueil Vision (le catalogue /widgets/entites que le serveur
 * filtre), mêmes appels avec le secret de l'appareil (jamais de jeton global), même dessin (VueWidgetHa rendue
 * dans une image, que le système affiche).
 *
 * - Ajout : le lanceur ouvre WidgetHaConfigActivity (entité, puis style).
 * - Rafraîchissement : à chaque relevé du service Vision, au plus une fois par minute, seulement si un état a
 *   changé (et toutes les 15 min pour les graphiques) ; le système relance aussi toutes les 30 min (secours).
 * - Toucher : bouton et interrupteur agissent (si la liste blanche le permet) ; les autres se rafraîchissent.
 */
object WidgetsSysteme {
    private const val PREFS = "widgets_systeme"
    const val ACTION_TOUCHER = "org.soprez.vision.WIDGET_HA_TOUCHER"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var dernierReleve = 0L
    @Volatile private var dernierGraphique = 0L
    @Volatile private var empreinte = ""

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun reglage(ctx: Context, appWidgetId: Int): WidgetsHa.Reglage? = WidgetsHa.decoder(prefs(ctx).getString("a:$appWidgetId", null))
    fun ecrire(ctx: Context, appWidgetId: Int, r: WidgetsHa.Reglage) = prefs(ctx).edit().putString("a:$appWidgetId", WidgetsHa.encoder(r)).apply()
    fun oublier(ctx: Context, appWidgetId: Int) = prefs(ctx).edit().remove("a:$appWidgetId").apply()

    fun ids(ctx: Context): IntArray = try {
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WidgetHaSystemeProvider::class.java))
    } catch (_: Exception) { IntArray(0) }

    /** Logique pure : la grille (colonnes × lignes) qui correspond à une taille en dp, pour la mise en page. */
    fun cases(largeurDp: Int, hauteurDp: Int): Pair<Int, Int> =
        ((largeurDp + 20) / 90).coerceIn(1, 6) to ((hauteurDp + 20) / 90).coerceIn(1, 4)

    /** Taille actuelle du widget (dp), d'après ce que le lanceur annonce ; à défaut, 2 × 1. */
    private fun tailleDp(ctx: Context, id: Int): Pair<Int, Int> {
        val o = try { AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id) } catch (_: Exception) { Bundle() }
        val portrait = ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val h = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return (if (w > 0) w else 160) to (if (h > 0) h else 80)
    }

    /** Le message à montrer à la place du widget, ou null s'il peut être dessiné. */
    private fun empechement(ctx: Context, r: WidgetsHa.Reglage?): String? = when {
        !Config(ctx).inscrit -> ctx.getString(R.string.widgets_sys_pas_relie)
        !Modules.actif(ctx, Modules.ENTITES) -> ctx.getString(R.string.widgets_sys_desactives)
        r == null -> ctx.getString(R.string.widgets_sys_a_regler)
        WidgetsHaStock.entite(ctx, r.entites.first()) == null -> ctx.getString(R.string.widgets_sys_plus_permise)
        else -> null
    }

    /** Dessine un widget (fil principal) et le pose. */
    fun dessiner(ctx: Context, id: Int) {
        val app = ctx.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { dessiner(app, id) }; return }
        val mgr = AppWidgetManager.getInstance(app)
        val r = reglage(app, id)
        val vues = RemoteViews(app.packageName, R.layout.widget_ha_systeme)
        val toucher = PendingIntent.getBroadcast(app, id, Intent(app, WidgetHaSystemeProvider::class.java).setAction(ACTION_TOUCHER)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        vues.setOnClickPendingIntent(R.id.widget_racine, toucher)
        val motif = empechement(app, r)
        if (motif != null || r == null) {
            vues.setViewVisibility(R.id.widget_image, View.GONE)
            vues.setViewVisibility(R.id.widget_message, View.VISIBLE)
            vues.setTextViewText(R.id.widget_message, motif ?: "")
            try { mgr.updateAppWidget(id, vues) } catch (_: Exception) {}
            return
        }
        val (wDp, hDp) = tailleDp(app, id)
        if (listeDefilante(app, r, hDp) && dessinerListe(app, mgr, id, r, wDp, hDp, toucher)) return
        val image = image(app, r, wDp, hDp)
        vues.setViewVisibility(R.id.widget_message, View.GONE)
        vues.setViewVisibility(R.id.widget_image, View.VISIBLE)
        vues.setImageViewBitmap(R.id.widget_image, image)
        val e = WidgetsHaStock.entite(app, r.entites.first())
        vues.setContentDescription(R.id.widget_racine, (r.libelle.ifEmpty { e?.nom ?: "" }) + " : " +
            WidgetsHa.valeurAffichee((FluxWidgets.etats[r.entites.first()] ?: WidgetsHa.EtatEntite(e?.etat ?: "")).etat, e?.unite ?: "", e?.domaine ?: "", e?.deviceClass ?: ""))
        try { mgr.updateAppWidget(id, vues) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ détail qui défile (1.90.1)

    /** 1.90.1 : une tuile qui montre un détail, assez haute : son détail devient une liste système qui défile. */
    fun listeDefilante(ctx: Context, r: WidgetsHa.Reglage, hDp: Int): Boolean {
        if (hDp < 90 || r.style != WidgetsHa.TUILE) return false
        val e = WidgetsHaStock.entite(ctx, r.entites.first()) ?: return false
        if (!WidgetsHa.montreDetail(WidgetsHa.affichage(r, e))) return false
        val st = FluxWidgets.etats[e.entityId] ?: WidgetsHa.EtatEntite(e.etat, e.attributs, "")
        return WidgetsHa.detail(e, st).second > 0
    }

    /** Les lignes de la liste (toutes celles reçues ; « … et N de plus » seulement si Home Assistant en a gardé). */
    fun lignesDetail(ctx: Context, id: Int): List<String> {
        val r = reglage(ctx, id) ?: return emptyList()
        val e = WidgetsHaStock.entite(ctx, r.entites.first()) ?: return emptyList()
        val st = FluxWidgets.etats[e.entityId] ?: WidgetsHa.EtatEntite(e.etat, e.attributs, "")
        val (lignes, total) = WidgetsHa.detail(e, st)
        return lignes.map { "• " + WidgetsHa.texteLigne(it) } +
            listOfNotNull(if (total > lignes.size) ctx.getString(R.string.wha_detail_autres, total - lignes.size) else null)
    }

    /** La tuile dessinée sans son détail, et la hauteur (px) où commence la zone du détail ; null si elle n'en a pas. */
    fun imageSansDetail(ctx: Context, r: WidgetsHa.Reglage, wDp: Int, hDp: Int, exemple: Boolean = false): Pair<Bitmap, Int>? {
        val (cw, ch) = cases(wDp, hDp)
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceIn(48, 1400); val h = (hDp * d).toInt().coerceIn(48, 1400)
        val theme = Local.themeEffectif(ctx, Palette.APPLI)
        val vue = VueWidgetHa(ctx, theme, r.copy(w = cw, h = ch), lectureSeule = true, exemple = exemple, detailExterne = true)
        val sw = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY); val sh = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        vue.measure(sw, sh); vue.layout(0, 0, w, h)
        vue.maj(); vue.measure(sw, sh); vue.layout(0, 0, w, h)
        val zone = vue.zoneDetail ?: return null
        var y = 0; var v: View = zone
        while (v !== vue) { y += v.top; v = (v.parent as? View) ?: return null }
        if (y <= 0 || y >= h - (24 * d).toInt()) return null
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        vue.draw(Canvas(image))
        return image to y
    }

    private fun dessinerListe(app: Context, mgr: AppWidgetManager, id: Int, r: WidgetsHa.Reglage, wDp: Int, hDp: Int, toucher: PendingIntent): Boolean {
        val (image, y) = imageSansDetail(app, r, wDp, hDp) ?: return false
        val vues = RemoteViews(app.packageName, R.layout.widget_ha_liste)
        vues.setImageViewBitmap(R.id.widget_haut, Bitmap.createBitmap(image, 0, 0, image.width, y))
        vues.setImageViewBitmap(R.id.widget_bas, Bitmap.createBitmap(image, 0, y, image.width, image.height - y))
        vues.setOnClickPendingIntent(R.id.widget_racine, toucher)
        val service = Intent(app, WidgetHaListeService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        service.data = android.net.Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
        @Suppress("DEPRECATION") vues.setRemoteAdapter(R.id.widget_liste, service)
        val modele = PendingIntent.getBroadcast(app, 100_000 + id, Intent(app, WidgetHaSystemeProvider::class.java).setAction(ACTION_TOUCHER)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        vues.setPendingIntentTemplate(R.id.widget_liste, modele)
        val e = WidgetsHaStock.entite(app, r.entites.first())
        vues.setContentDescription(R.id.widget_racine, r.libelle.ifEmpty { e?.nom ?: "" })
        return try {
            mgr.updateAppWidget(id, vues)
            @Suppress("DEPRECATION") mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_liste)
            true
        } catch (_: Exception) { false }
    }

    /**
     * L'image d'un widget à cette taille (dp), dessinée par la même vue que l'accueil Vision (fil principal).
     * `exemple` : des données d'exemple (aperçu, essais).
     */
    fun image(ctx: Context, r: WidgetsHa.Reglage, wDp: Int, hDp: Int, exemple: Boolean = false): Bitmap {
        val (cw, ch) = cases(wDp, hDp)
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceIn(48, 1400); val h = (hDp * d).toInt().coerceIn(48, 1400)
        val theme = Local.themeEffectif(ctx, Palette.APPLI)
        // Très bas (une demi-rangée) : une présentation compacte, nom et valeur, lisibles ; sinon la vue de l'accueil.
        if (hDp < 90) return compacte(ctx, r, w, h, theme, exemple)
        // Une jauge sur une seule rangée n'a pas la place de son arc : elle se montre en tuile.
        val reglage = if (r.style == WidgetsHa.JAUGE && ch < 2) r.copy(style = WidgetsHa.TUILE) else r
        val hist = if (r.style == WidgetsHa.GRAPHIQUE) WidgetsHaStock.historiqueGarde(r.entites.first(), r.periode) else null
        val vue = VueWidgetHa(ctx, theme, reglage.copy(w = cw, h = ch), lectureSeule = true, exemple = exemple, historiqueInitial = hist)
        val sw = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY); val sh = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        vue.measure(sw, sh); vue.layout(0, 0, w, h)
        // Deuxième passe : la vue ajuste ses textes à la taille réelle (ContenuAjuste).
        vue.maj(); vue.measure(sw, sh); vue.layout(0, 0, w, h)
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        vue.draw(Canvas(image))
        return image
    }

    /**
     * La présentation compacte d'un widget très bas : « 21,5 °C » en grand, le nom dessous (ou à côté si la place
     * le permet). Les deux textes rapetissent ensemble jusqu'à tenir, sans coupure ni points de suspension.
     */
    private fun compacte(ctx: Context, r: WidgetsHa.Reglage, w: Int, h: Int, theme: Theme, exemple: Boolean): Bitmap {
        val id = r.entites.first()
        val e = WidgetsHaStock.entite(ctx, id) ?: if (exemple) WidgetsHa.exemple(r.style) else null
        val st = FluxWidgets.etats[id] ?: WidgetsHa.EtatEntite(e?.etat ?: "")
        val nom = r.libelle.ifEmpty { e?.nom ?: id }
        val valeur = when (r.style) {
            WidgetsHa.BOUTON -> "▶"
            WidgetsHa.INTERRUPTEUR -> WidgetsHa.libelleEtat(e?.domaine ?: "", e?.deviceClass ?: "", st.etat)
            else -> WidgetsHa.valeurAffichee(st.etat, e?.unite ?: "", e?.domaine ?: "", e?.deviceClass ?: "")
        }
        val d = ctx.resources.displayMetrics.density
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(image)
        val fond = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = theme.carte }
        val bord = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = theme.carteBord; style = android.graphics.Paint.Style.STROKE; strokeWidth = d }
        val rect = android.graphics.RectF(d / 2, d / 2, w - d / 2, h - d / 2)
        val rayon = minOf(h / 2f, 24 * d)
        c.drawRoundRect(rect, rayon, rayon, fond); c.drawRoundRect(rect, rayon, rayon, bord)
        val marge = 12 * d
        val pv = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = theme.encre; typeface = Polices.outfit(ctx) }
        val pn = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = theme.encre2; typeface = Polices.demiGras(ctx) }
        val dispoL = w - 2 * marge; val dispoH = h - 2 * marge * 0.6f
        // Côte à côte si la largeur le permet, sinon l'une sous l'autre ; on réduit la taille jusqu'à tout faire tenir.
        var t = 20 * d
        while (t > 7 * d) {
            pv.textSize = t; pn.textSize = t * 0.72f
            val cote = pv.measureText(valeur) + 10 * d + pn.measureText(nom) <= dispoL && t * 1.2f <= dispoH
            val dessous = maxOf(pv.measureText(valeur), pn.measureText(nom)) <= dispoL && t * 1.2f + t * 0.9f <= dispoH
            if (cote) {
                val y = h / 2f - (pv.descent() + pv.ascent()) / 2
                c.drawText(valeur, marge, y, pv); c.drawText(nom, marge + pv.measureText(valeur) + 10 * d, y, pn)
                return image
            }
            if (dessous) {
                val total = t * 1.15f + t * 0.85f
                val y1 = (h - total) / 2 - pv.ascent()
                c.drawText(valeur, marge, y1, pv); c.drawText(nom, marge, y1 + t * 1.0f, pn)
                return image
            }
            t -= d
        }
        // Rien ne tient (minuscule) : la valeur seule, à la taille qui tient.
        pv.textSize = minOf(dispoH, 14 * d)
        while (pv.measureText(valeur) > dispoL && pv.textSize > 6 * d) pv.textSize -= d
        c.drawText(valeur, marge, h / 2f - (pv.descent() + pv.ascent()) / 2, pv)
        return image
    }

    /** Relit les états (et l'historique des graphiques) puis redessine tout ; hors du fil principal. */
    fun rafraichir(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val ids = ids(app)
        if (ids.isEmpty()) return
        val reglages = ids.toList().mapNotNull { id -> reglage(app, id)?.let { id to it } }
        if (Config(app).inscrit && Modules.actif(app, Modules.ENTITES)) {
            try { if (WidgetsHaStock.catalogueAPerime(app)) WidgetsHaStock.recharger(app) } catch (_: Exception) {}
            val entites = reglages.flatMap { it.second.entites }.toSet()
            // 1.88.0 : le détail des widgets qui le montrent (éléments d'une liste, attributs).
            val details = reglages.mapNotNull { (_, r) -> WidgetsHaStock.entite(app, r.entites.first())?.takeIf { WidgetsHa.montreDetail(WidgetsHa.affichage(r, it)) }?.let { r.entites.first() } }
            val etats = try { WidgetsHaStock.etats(app, entites, details) } catch (_: Exception) { emptyMap() }
            etats.forEach { (k, v) -> FluxWidgets.etats[k] = v }
            val graphiques = force || System.currentTimeMillis() - dernierGraphique > 15 * 60_000L
            if (graphiques) {
                dernierGraphique = System.currentTimeMillis()
                reglages.filter { it.second.style == WidgetsHa.GRAPHIQUE }.forEach { (_, r) ->
                    try { WidgetsHaStock.historique(app, r.entites.first(), r.periode) } catch (_: Exception) {}
                }
            }
            val nouvelle = etats.entries.sortedBy { it.key }.joinToString("|") { it.key + "=" + it.value.etat + "#" + it.value.detail.hashCode() } + "|" + ids.joinToString(",")
            if (!force && !graphiques && nouvelle == empreinte) return
            empreinte = nouvelle
        }
        ids.forEach { dessiner(app, it) }
    }

    /** Appelé à chaque relevé du service : au plus une fois par minute. */
    fun auReleve(ctx: Context) {
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierReleve < 60_000L) return
        dernierReleve = maintenant
        rafraichir(ctx)
    }

    /** Toucher un widget : agir (bouton, interrupteur permis) ou rafraîchir. Hors du fil principal. */
    fun toucher(ctx: Context, id: Int) {
        val app = ctx.applicationContext
        val r = reglage(app, id)
        val e = r?.let { WidgetsHaStock.entite(app, it.entites.first()) }
        // 1.90.0 (retour d'essai) : « Au toucher » — les détails de l'entité ou une vue de Home Assistant, ou rien.
        if (r != null && e != null) {
            val (t, c) = WidgetsHa.toucher(r, e)
            when (t) {
                WidgetsHa.TOUCHER_RIEN -> return
                WidgetsHa.TOUCHER_DETAILS -> { OuvrirHa.ouvrir(app, OuvrirHa.cheminDetails(e.entityId)); return }
                WidgetsHa.TOUCHER_VUE -> { OuvrirHa.ouvrir(app, c); return }
            }
        }
        // 1.88.0 : confirmation demandée, ou réglage d'une valeur / d'une liste : une petite fenêtre de Vision s'ouvre.
        if (r != null && e != null && WidgetsHa.pilotable(e, tele = false) && (e.confirmer || r.style == WidgetsHa.REGLAGE)) {
            WidgetHaActionActivity.ouvrir(app, id)
            return
        }
        val service = when {
            r == null || e == null || !WidgetsHa.pilotable(e, tele = false) -> null
            r.style == WidgetsHa.INTERRUPTEUR -> WidgetsHa.serviceBascule(e, (FluxWidgets.etats[e.entityId] ?: WidgetsHa.EtatEntite(e.etat)).etat)
            r.style == WidgetsHa.BOUTON -> WidgetsHa.serviceBouton(e)
            else -> null
        }
        if (service != null && e != null) {
            // 1.77.0 (règle du projet) : l'état attendu s'affiche tout de suite ; un refus remet l'état d'avant et s'explique.
            val avant = FluxWidgets.etats[e.entityId]
            val base = avant ?: WidgetsHa.EtatEntite(e.etat, e.attributs, "")
            Geste.etatAttendu(base.etat, service)?.let { FluxWidgets.etats[e.entityId] = base.copy(etat = it); dessiner(app, id) }
            val res = WidgetsHaStock.agir(app, e.entityId, service)
            if (res.question != null) { WidgetHaActionActivity.ouvrir(app, id); return }
            val echec = res.message
            if (echec != null) {
                if (avant != null) FluxWidgets.etats[e.entityId] = avant else FluxWidgets.etats.remove(e.entityId)
                dessiner(app, id)
                main.post { Ui.toast(app, echec, genre = OeilModele.Genre.ERREUR) }
            }
            try { Thread.sleep(700) } catch (_: InterruptedException) {}
        }
        rafraichir(app, force = true)
    }
}

/** Le fournisseur des widgets système « Vision · Home Assistant ». */
class WidgetHaSystemeProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val r = goAsync()
        Thread { try { WidgetsSysteme.rafraichir(ctx, force = true) } finally { r.finish() } }.start()
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = WidgetsSysteme.dessiner(ctx, id)

    override fun onDeleted(ctx: Context, ids: IntArray) { ids.forEach { WidgetsSysteme.oublier(ctx, it) } }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == WidgetsSysteme.ACTION_TOUCHER) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val r = goAsync()
            Thread { try { WidgetsSysteme.toucher(ctx, id) } finally { r.finish() } }.start()
            return
        }
        super.onReceive(ctx, intent)
    }
}
