// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.content.Context
import java.util.Date
import java.util.Locale

/**
 * Les textes traduits hors d'un écran (logique pure, notifications, services) : les ressources de l'appli,
 * posées une fois au démarrage (VisionApp). Les écrans gardent leur `getString` habituel.
 *
 * Sans Android (essais JVM de `app/src/test`), aucune ressource n'est posée : une source d'essai
 * (`org.soprez.vision.TrJvm`, présente seulement dans les essais) lit les fichiers `res/values-<langue>/`
 * — le français par défaut, pour que les essais existants gardent leurs textes. En production, cette classe
 * n'existe pas et n'est jamais cherchée tant que VisionApp a posé le contexte.
 */
object Tr {
    /** Ce que la source d'essai doit savoir rendre. */
    interface Source {
        fun s(id: Int, args: Array<out Any?>): String
        fun p(id: Int, n: Int, args: Array<out Any?>): String
        fun heure(ms: Long): String
        fun locale(): Locale
    }

    @SuppressLint("StaticFieldLeak")  // le contexte de l'application, jamais celui d'un écran
    @Volatile private var ctx: Context? = null
    @Volatile private var essai: Source? = null

    fun installer(c: Context) { ctx = c.applicationContext ?: c }

    private fun source(): Source? = essai ?: try {
        (Class.forName("org.soprez.vision.TrJvm").getField("INSTANCE").get(null) as Source).also { essai = it }  // i18n-exception : nom de champ (réflexion)
    } catch (_: Throwable) { null }

    /** Une chaîne, avec ses champs positionnels (%1$s, %2$d…). */
    fun s(id: Int, vararg args: Any?): String {
        val c = ctx
        if (c != null) return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
        return source()?.s(id, args) ?: ""
    }

    /** Un pluriel : `n` choisit la forme ; les champs suivent (souvent `n` lui-même). */
    fun p(id: Int, n: Int, vararg args: Any?): String {
        val c = ctx
        if (c != null) return c.resources.getQuantityString(id, n, *args)
        return source()?.p(id, n, args) ?: ""
    }

    /** La langue d'affichage (nombres, dates) : celle des ressources de l'appli. */
    fun locale(): Locale {
        val c = ctx
        if (c != null) return if (android.os.Build.VERSION.SDK_INT >= 24) c.resources.configuration.locales[0]
            else @Suppress("DEPRECATION") c.resources.configuration.locale
        return source()?.locale() ?: Locale.getDefault()
    }

    /** L'heure selon la langue et le réglage 12/24 h du téléphone (« 18:30 », « 6:30 PM »). */
    fun heure(ms: Long): String {
        val c = ctx
        if (c != null) return android.text.format.DateFormat.getTimeFormat(c).format(Date(ms))
        return source()?.heure(ms) ?: java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault()).format(Date(ms))
    }

    /** Une date selon la langue, d'après un squelette (« EEEEdMMMM », « dMMMM », « EEE ») : l'ordre suit la locale. */
    fun date(ms: Long, squelette: String): String {
        val l = locale()
        val motif = try { android.text.format.DateFormat.getBestDateTimePattern(l, squelette) } catch (_: Throwable) { squelette }
        return java.text.SimpleDateFormat(motif, l).format(Date(ms))
    }

    /** « 1 h 30 », « 45 min », « 2 h » : les unités et l'ordre viennent des ressources. */
    /**
     * Lot 6 : une abréviation qui finit une phrase ne double pas son point (« noch 1 Std. 20 Min.. » en allemand) ;
     * les points de suspension (« ... ») ne sont pas touchés.
     */
    fun finPhrase(texte: String): String = texte.replace(Regex("""(?<!\.)\.\.(?!\.)"""), ".")

    fun duree(min: Int): String = when {
        min < 60 -> s(R.string.tr_duree_min, min)
        min % 60 == 0 -> s(R.string.tr_duree_h, min / 60)
        else -> s(R.string.tr_duree_h_min, min / 60, "%02d".format(Locale.ROOT, min % 60))
    }
}
