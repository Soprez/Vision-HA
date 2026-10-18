// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Locale

/**
 * Les modules activables : Home Assistant envoie `"modules": [identifiants des modules actifs]` dans la
 * réponse au relevé (/api/pc_parental/poll) et dans celle de l'espace gestion (/api/pc_parental/parent).
 * Champ absent (serveur ancien) : tout est actif.
 *
 * Un module coupé disparaît de l'appli et l'appareil cesse d'agir à son titre ; rien de ce qui a été réglé
 * sur l'appareil n'est effacé, si bien qu'à la réactivation tout revient tel quel.
 *
 * La partie « pure » (lecture, décisions afficher / agir) ne touche pas Android : elle est essayée en JUnit
 * (ModulesTest). `null` y veut toujours dire « champ absent, tout actif ».
 */
object Modules {
    const val PLAGES = "plages"
    const val TEMPS = "temps"
    const val BLOCAGE = "blocage"
    const val FILTRAGE = "filtrage"
    const val DEMANDES = "demandes"
    const val DEVERROUILLAGE = "deverrouillage"
    const val DOUBLE_VALIDATION = "double_validation"
    const val PAUSE = "pause"
    const val MESSAGES = "messages"
    const val PREAVIS = "preavis"
    const val VEILLE = "veille"
    const val VEILLE_TABLEAUX = "veille_tableaux"
    const val ENTITES = "entites"
    const val ACCUEIL = "accueil"
    const val WIDGETS = "widgets"
    const val HABILLAGE = "habillage"
    const val OPTIMISATIONS = "optimisations"
    const val PROTECTIONS = "protections"
    const val PAUSE_GENERALE = "pause_generale"
    const val CONCENTRATION = "concentration"
    /** Réveil (coupé par défaut côté HA) : absent de `modules` = l'appareil désarme ses réveils. */
    const val REVEIL = "reveil"
    /** Niveau sonore (backlog n° 31) : coupé par défaut dans Home Assistant ; le micro de l'appareil, des nombres seulement. */
    const val SON = "son"

    val TOUS: List<String> = listOf(PLAGES, TEMPS, BLOCAGE, FILTRAGE, DEMANDES, DEVERROUILLAGE, DOUBLE_VALIDATION, PAUSE,
        MESSAGES, VEILLE, VEILLE_TABLEAUX, ENTITES, ACCUEIL, WIDGETS, HABILLAGE, OPTIMISATIONS, PROTECTIONS,
        PAUSE_GENERALE)

    // ------------------------------------------------------------------ Logique pure

    /** Les identifiants reçus, propres (minuscules, sans blanc) ; null reste null (champ absent). */
    fun normaliser(ids: List<String>?): Set<String>? =
        ids?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }?.toSet()

    /** Forme rangée dans les préférences : « a,b,c » ; null = champ absent (tout actif). */
    fun encoder(actifs: Set<String>?): String? = actifs?.sorted()?.joinToString(",")

    fun decoder(s: String?): Set<String>? = s?.split(',')?.let { normaliser(it) }

    fun actifDans(actifs: Set<String>?, id: String): Boolean = actifs == null || id in actifs

    /** L'ensemble effectif (absent = tous). */
    fun effectifs(actifs: Set<String>?): Set<String> = actifs ?: TOUS.toSet()

    /** Les modules qui viennent d'être coupés, et ceux qui reviennent. */
    fun coupes(avant: Set<String>?, apres: Set<String>?): Set<String> = effectifs(avant) - effectifs(apres)
    fun repris(avant: Set<String>?, apres: Set<String>?): Set<String> = effectifs(apres) - effectifs(avant)

    /** L'appareil ferme-t-il encore quelque chose (appareil fermé, applis, sites des règles) ? */
    fun regles(a: Set<String>?): Boolean = actifDans(a, PLAGES) || actifDans(a, TEMPS) || actifDans(a, BLOCAGE)

    /** Ce qui touche aux horaires : ouvrir ou fermer pour un temps, réouverture, planning, temps d'écran. */
    fun horaires(a: Set<String>?): Boolean = actifDans(a, PLAGES) || actifDans(a, TEMPS)

    /**
     * Un widget de l'accueil (code Vision, « android:<id> » pour celui d'une autre appli, « ha:<id> » pour un widget
     * Home Assistant) est-il montré ? Masqué n'est pas supprimé : il revient tel quel à la réactivation du module.
     */
    fun widgetVisible(a: Set<String>?, code: String): Boolean = when {
        code.startsWith(WidgetsAndroid.PREFIXE) -> actifDans(a, WIDGETS)
        code.startsWith(WidgetsHa.PREFIXE) -> actifDans(a, ENTITES)
        code == "horloge" -> true
        code == "temps" -> horaires(a)
        code == "demandes" -> actifDans(a, DEMANDES)
        code == "enfants" -> regles(a)
        // infos, devoirs, école, chauffage, fioul, caméra, batteries, maison : des entités de Home Assistant.
        else -> actifDans(a, ENTITES)
    }

    /** Un tableau de l'écran de veille : l'horloge reste ; les tableaux de Home Assistant suivent leur module. */
    fun tableauVeille(a: Set<String>?, code: String): Boolean = code == "horloge" || actifDans(a, VEILLE_TABLEAUX)

    /** Une notification du relevé (`notify`) : de quel module relève-t-elle, et est-il actif ? */
    fun notificationPermise(a: Set<String>?, demande: Boolean, acces: Boolean, deverrouillage: Boolean): Boolean = when {
        deverrouillage -> actifDans(a, DEVERROUILLAGE)
        demande || acces -> actifDans(a, DEMANDES)
        else -> actifDans(a, MESSAGES)
    }

    /** Ce que porte le bouton « Gérer… » d'un appareil dans l'espace gestion. */
    fun gestesGestion(a: Set<String>?, android: Boolean): List<String> {
        val l = ArrayList<String>()
        if (actifDans(a, PLAGES)) l.add("planning")
        if (actifDans(a, TEMPS)) l.add("limites")
        l.add("à qui")
        if (android && actifDans(a, PAUSE)) l.add("pause")
        return l
    }

    /** Une empreinte courte, pour savoir qu'un écran doit se rebâtir. */
    fun signature(a: Set<String>?): String = encoder(a) ?: "*"

    // ------------------------------------------------------------------ Sur l'appareil

    @Volatile private var cache: Set<String>? = null
    @Volatile private var charge = false
    private val principal by lazy { Handler(Looper.getMainLooper()) }

    fun actifs(ctx: Context): Set<String>? {
        if (!charge) synchronized(this) { if (!charge) { cache = decoder(Config(ctx).modules); charge = true } }
        return cache
    }

    fun actif(ctx: Context, id: String): Boolean =
        actifDans(actifs(ctx), id) &&
            // 17-10 (retour d'essai) : l'écran de veille de Vision coupé pour CET appareil depuis Home Assistant (EcranAppareil) —
            // toutes les entrées de la veille passent par ce contrôle.
            (id != VEILLE || EcranAppareil.veilleVision(ctx))

    /** Sans contexte (le VPN) : ce qui a déjà été lu ; rien de lu encore = tout actif. */
    fun actifEnMemoire(id: String): Boolean = !charge || actifDans(cache, id)

    /** Le champ `modules` d'une réponse : null s'il est absent ou illisible (tout actif). */
    fun lire(r: JSONObject): Set<String>? {
        val a = r.optJSONArray("modules") ?: return null
        return normaliser((0 until a.length()).map { a.optString(it) })
    }

    /** Retient la liste d'une réponse (relevé ou espace gestion) et applique ce qui change. */
    fun memoriser(ctx: Context, r: JSONObject) {
        val app = ctx.applicationContext
        val apres = lire(r)
        val avant = actifs(app)
        if (apres == avant) return
        Config(app).modules = encoder(apres)
        cache = apres
        appliquerChangement(app, coupes(avant, apres), repris(avant, apres))
    }

    /** L'appareil cesse proprement d'agir au titre d'un module coupé ; rien n'est effacé. */
    private fun appliquerChangement(ctx: Context, coupes: Set<String>, repris: Set<String>) {
        if (FILTRAGE in coupes) DnsVpnService.arreter(ctx)
        if (FILTRAGE in repris && DnsVpnService.autorise(ctx)) DnsVpnService.demarrer(ctx)
        if (VEILLE in coupes) principal.post { try { Veille.fermer() } catch (_: Exception) {} }
        if (MESSAGES in coupes) MessagesRiches.suspendre()
        if (SON in coupes) MesureSonService.arreter(ctx)
        if (PROTECTIONS in coupes) retirerAdministrateur(ctx)
        // Les protections reprises : l'administrateur de l'appareil doit être redonné à l'écran
        // (Android l'exige) ; l'étape réapparaît dans les réglages de Vision et la notification le signale.
    }

    /** Sans le module « protections », Vision ne s'oppose plus à sa désinstallation. */
    private fun retirerAdministrateur(ctx: Context) {
        try {
            if (!AdminReceiver.estActif(ctx)) return
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.removeActiveAdmin(AdminReceiver.composant(ctx))
        } catch (_: Exception) {}
    }
}
