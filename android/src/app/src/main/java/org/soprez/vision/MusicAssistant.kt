// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * 1.94 (demande de l'auteur : « ajouter Music Assistant à l'accueil ») : une tuile qui ouvre l'interface de Music Assistant.
 *
 * Depuis la 2.5.12.14.3, Vision ne fait plus lui-même ce que Music Assistant ne propose pas en service Home Assistant (file
 * complète, retirer ou déplacer un titre, fondu, ajout automatique, appairage Sendspin) : il répond « à faire dans Music
 * Assistant ». Cette tuile, et ces réponses rendues cliquables, mènent en un geste à l'endroit où le faire.
 *
 * Home Assistant dit, dans /veille, si Music Assistant est installé ET si le rôle de cet appareil le permet (bloc `musique_ma`
 * absent sinon : la tuile n'existe pas dans le catalogue, un Demandeur ne la voit jamais). Il donne le port de l'interface et le
 * chemin du panneau d'ingress ; jamais d'adresse interne.
 *
 * Ce qui s'ouvre, dans l'ordre ([choisir], pur) : l'appli Music Assistant si elle est installée ; sinon, sur le réseau local,
 * l'interface web à l'adresse locale de Home Assistant que l'appareil connaît déjà, au port donné ; sinon l'ingress de Home
 * Assistant, par l'adresse où l'appareil joint Home Assistant (d'où qu'on soit).
 */
object MusicAssistant {
    /** Les paquets connus de l'appli Music Assistant (Companion). */
    val PAQUETS = listOf("io.music_assistant.companion", "io.music_assistant.client")
    const val PORT_DEFAUT = 8095

    data class Info(val port: Int, val ingress: String, val lecteur: String)

    sealed class Ouverture {
        data class App(val paquet: String) : Ouverture()
        data class Web(val url: String) : Ouverture()
        object Aucune : Ouverture()
    }

    private const val PREFS = "musique_ma"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Retient le bloc `musique_ma` du dernier /veille ; absent ou nul (HA le refuse à ce rôle, ou Music Assistant n'est pas là) : oublié. */
    fun noter(ctx: Context, r: JSONObject) {
        if (!r.has("musique_ma")) return                       // un ancien Home Assistant : on ne sait pas, rien ne change
        val b = r.optJSONObject("musique_ma")
        val e = prefs(ctx).edit()
        if (b == null) e.clear()
        else e.putBoolean("oui", true).putInt("port", b.optInt("port", PORT_DEFAUT)).putString("ingress", b.optString("ingress")).putString("lecteur", b.optString("lecteur"))
        e.apply()
    }

    fun info(ctx: Context): Info? {
        val p = prefs(ctx)
        if (!p.getBoolean("oui", false)) return null
        return Info(p.getInt("port", PORT_DEFAUT), p.getString("ingress", "").orEmpty(), p.getString("lecteur", "").orEmpty())
    }

    // ------------------------------------------------------------------ pur (essais JVM)

    /** L'hôte d'une adresse du réseau local (192.168.x.y, 10.x, 172.16–31.x, « .local »), ou null (nom public, adresse externe). */
    fun hoteLocal(adresse: String): String? {
        val hote = try { Uri.parse(adresse.trim()).host.orEmpty() } catch (_: Exception) { "" }
        if (hote.isEmpty()) return null
        if (hote.endsWith(".local")) return hote
        val o = hote.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4 || hote.split('.').size != 4 || o.any { it !in 0..255 }) return null
        return hote.takeIf { o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) }
    }

    /** Quoi ouvrir ? [paquet] : l'appli Music Assistant installée (ou null) ; [urlActive] : l'adresse par laquelle Home Assistant répond maintenant. */
    fun choisir(info: Info, paquet: String?, urlActive: String): Ouverture {
        if (paquet != null) return Ouverture.App(paquet)
        val local = hoteLocal(urlActive)
        if (local != null && info.port in 1..65535) return Ouverture.Web("http://$local:${info.port}/")
        val base = urlActive.trim().trimEnd('/')
        if (info.ingress.startsWith("/") && base.startsWith("http")) return Ouverture.Web(base + info.ingress)
        return Ouverture.Aucune
    }

    // ------------------------------------------------------------------ ouvrir

    private fun paquetInstalle(ctx: Context): String? = PAQUETS.firstOrNull { p ->
        try { ctx.packageManager.getLaunchIntentForPackage(p) != null } catch (_: Exception) { false }
    }

    /** Ouvre Music Assistant (appli, ou interface web). Rend faux si rien n'est possible d'ici. */
    fun ouvrir(act: Activity): Boolean {
        val info = info(act) ?: return false
        val cfg = Config(act)
        val o = choisir(info, paquetInstalle(act), cfg.urlActive.ifEmpty { cfg.adresses().firstOrNull().orEmpty() })
        return when (o) {
            is Ouverture.App -> try { act.startActivity(act.packageManager.getLaunchIntentForPackage(o.paquet)!!); true } catch (_: Exception) { ouvrirWeb(act, info, cfg) }
            is Ouverture.Web -> ouvrirWeb(act, info, cfg)
            Ouverture.Aucune -> false
        }
    }

    private fun ouvrirWeb(act: Activity, info: Info, cfg: Config): Boolean {
        val o = choisir(info, null, cfg.urlActive.ifEmpty { cfg.adresses().firstOrNull().orEmpty() }) as? Ouverture.Web ?: return false
        // Télé : l'interface s'ouvre dans la fenêtre de Vision (le navigateur de la télé, quand il y en a un, ne se pilote pas à la télécommande).
        if (ReglagesTvActivity.estTele(act)) { act.startActivity(Intent(act, MaWebActivity::class.java).putExtra("url", o.url)); return true }
        return try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.url))); true } catch (_: Exception) {
            act.startActivity(Intent(act, MaWebActivity::class.java).putExtra("url", o.url)); true
        }
    }

    // ------------------------------------------------------------------ « À faire dans Music Assistant » cliquable

    @Volatile private var visible: WeakReference<Activity>? = null
    private var dialogueOuvert = false

    /** Suit l'écran de Vision au premier plan (la phrase venue de Home Assistant peut arriver de n'importe quel écran). */
    fun suivre(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { visible = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (visible?.get() === a) visible = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /**
     * Une réponse de Home Assistant porte `ouvrir_ma` : la phrase « à faire dans Music Assistant » reste dite à l'endroit où elle
     * s'affiche, et un geste mène à Music Assistant. Sans le droit ou sans Music Assistant (pas de bloc), rien de plus que la phrase.
     */
    fun proposer(message: String) {
        val a = visible?.get() ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (a.isFinishing || dialogueOuvert || info(a) == null) return@post
            dialogueOuvert = true
            Ui.dialogue(a).setTitle(R.string.ma_titre).setMessage(message)
                .setNegativeButton(R.string.commun_fermer, null)
                .setPositiveButton(R.string.ma_ouvrir) { _, _ -> if (!ouvrir(a)) Ui.toast(a, a.getString(R.string.ma_indisponible), genre = OeilModele.Genre.ERREUR) }
                .setOnDismissListener { dialogueOuvert = false }
                .show()
        }
    }
}
