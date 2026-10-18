// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONObject

/**
 * Le cœur « favori » de Vision (l'auteur, 15/10) : un bouton qui ajoute ou retire un élément des favoris de Music Assistant,
 * sans ouvrir son interface. Un seul composant et un seul appel pour le choix de la playlist de veille ET l'appli
 * Musique de Vision (session Banc de tests Musique) : `POST /api/pc_parental/party/musique`, `quoi: "favori"`, `uri`,
 * `favori` — côté Home Assistant musique.basculer_favori (client de l'intégration Music Assistant ; Décideur ou télé).
 *
 * - Plein quand c'est un favori, en contour sinon ; focalisable (télécommande), annoncé à TalkBack.
 * - L'appui change l'état aussitôt, l'appel part en fond ; en cas d'échec l'état revient et [surErreur] reçoit la phrase.
 */
class CoeurFavori(ctx: Context, private val th: Theme, val uri: String, favoriAuDepart: Boolean,
                  private val surChange: (Boolean) -> Unit = {}, private val surErreur: (String) -> Unit = {}) : View(ctx) {

    var favori: Boolean = favoriAuDepart
        private set(v) { field = v; decrire(); invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val chemin = Path()
    @Volatile private var enCours = false

    init {
        isFocusable = true; isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        Ui.anneauFocus(this, th, 999f, agrandir = true)
        decrire()
        setOnClickListener { basculer() }
    }

    private fun decrire() {
        contentDescription = context.getString(if (favori) R.string.coeur_retirer else R.string.coeur_ajouter)
    }

    private fun basculer() {
        if (enCours) return
        enCours = true
        val voulu = !favori
        favori = voulu
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        Thread {
            val erreur = try { appeler(context, uri, voulu); null } catch (x: Net.Echec) { Party.messageErreur(x.code, x.corps) } catch (_: Exception) { Party.messageErreur(0, null) }
            post {
                enCours = false
                if (erreur != null) { favori = !voulu; surErreur(erreur) } else surChange(voulu)
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onMeasure(w: Int, h: Int) {
        val c = Ui.dp(context, 44f)
        setMeasuredDimension(resolveSize(c, w), resolveSize(c, h))
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val t = minOf(width, height) * 0.5f
        val cx = width / 2f; val cy = height / 2f + t * 0.06f
        // Un cœur : deux arcs et une pointe, dans un carré de côté t.
        chemin.reset()
        chemin.moveTo(cx, cy + t * 0.42f)
        chemin.cubicTo(cx - t * 0.95f, cy - t * 0.12f, cx - t * 0.42f, cy - t * 0.78f, cx, cy - t * 0.28f)
        chemin.cubicTo(cx + t * 0.42f, cy - t * 0.78f, cx + t * 0.95f, cy - t * 0.12f, cx, cy + t * 0.42f)
        chemin.close()
        p.color = if (favori) 0xFFD13B3B.toInt() else th.encre2
        p.style = if (favori) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        p.strokeWidth = t * 0.12f
        c.drawPath(chemin, p)
    }

    companion object {
        /** L'appel commun (veille et Musique) ; une exception parlante si Home Assistant refuse ou ne répond pas. */
        fun appeler(ctx: Context, uri: String, favori: Boolean): Boolean {
            val cfg = Config(ctx)
            val r = Net.post(ctx, cfg, "/api/pc_parental/party/musique",
                JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("quoi", "favori").put("uri", uri).put("favori", favori), 9_000)
            return r.optBoolean("favori", favori)
        }
    }
}
