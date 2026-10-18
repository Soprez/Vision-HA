// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Logique pure (sans Android, testée en JUnit) de ce que Vision fait quand une appli est
 * désinstallée ou qu'un écran de désinstallation passe devant.
 *
 *  - [retirer] : l'état d'accueil enregistré sans l'appli supprimée (cases de la grille,
 *    dossiers, ordre de la télé, dock, applis masquées, applis « connues ») et la liste
 *    des widgets Android à libérer.
 *  - [ecranLaisse] : le garde-fou de la désinstallation demandée depuis Vision. L'écran de
 *    confirmation du système passe (appareil fermé ou protection des réglages), mais
 *    jamais s'il vise Vision elle-même.
 */
object NettoyageAccueil {

    /** L'état d'accueil, tel qu'enregistré (Accueil : disposition, dossiers, ordre, dock, masquées, connues). */
    class EtatAccueil(
        val places: List<Accueil.Place>,
        val dossiers: List<Accueil.Dossier>,
        val ordre: List<String>,
        /** null : dock jamais choisi (celui par défaut, recalculé à chaque fois). */
        val dock: List<String>?,
        val masquees: Set<String>,
        val connues: Set<String>,
    )

    class Resultat(val etat: EtatAccueil, val widgetsALiberer: List<Int>, val change: Boolean)

    /**
     * L'état d'accueil sans `pkg` : ses cases, sa place dans les dossiers (un dossier vide
     * disparaît avec sa case), dans l'ordre de la télé, dans le dock et dans les connues.
     * 17-10 (l'auteur, point 6 : « j'ai retiré Rappel plusieurs fois, il revient ») : elle RESTE dans les
     * masquées. Une absence n'est souvent que passagère (mise à jour, appli désactivée un instant) ;
     * l'oublier la faisait revenir sur l'accueil à son retour. Cacher une appli absente ne coûte rien. Les widgets
     * Android dont `widgetDuPaquet(id)` est vrai sont retirés et rendus à libérer.
     */
    fun retirer(pkg: String, e: EtatAccueil, widgetDuPaquet: (Int) -> Boolean): Resultat {
        if (pkg.isEmpty()) return Resultat(e, emptyList(), false)
        val liberer = ArrayList<Int>()
        val dossiers = e.dossiers.map { d -> Accueil.Dossier(d.id, d.nom, d.pkgs.filter { it != pkg }.toMutableList()) }
        val dossiersVides = dossiers.filter { it.pkgs.isEmpty() }.map { it.cle }.toSet()
        val places = e.places.filter { p ->
            when {
                p.cle == pkg -> false
                p.estDossier -> p.cle !in dossiersVides
                p.estWidget && p.code.startsWith(WIDGET_ANDROID) -> {
                    val id = p.code.removePrefix(WIDGET_ANDROID).toIntOrNull()
                    if (id != null && widgetDuPaquet(id)) { liberer.add(id); false } else true
                }
                else -> true
            }
        }.map { Accueil.Place(it.cle, it.col, it.row, it.w, it.h) }
        val gardes = dossiers.filter { it.pkgs.isNotEmpty() }
        val ordre = e.ordre.filter { it != pkg && it !in dossiersVides }
        val dock = e.dock?.filter { it != pkg }
        val masquees = e.masquees  // point 6 : le choix « retirée de l'accueil » survit à une absence
        val connues = e.connues - pkg
        val change = places.size != e.places.size || gardes.size != e.dossiers.size ||
            gardes.zip(e.dossiers).any { (a, b) -> a.pkgs.size != b.pkgs.size } ||
            ordre.size != e.ordre.size || dock?.size != e.dock?.size ||
            masquees.size != e.masquees.size || connues.size != e.connues.size
        return Resultat(EtatAccueil(places, gardes, ordre, dock, masquees, connues), liberer, change)
    }

    // ------------------------------------------------------------------ lot accueil (point 3) : l'appli est-elle encore là ?

    // Valeurs de PackageManager.COMPONENT_ENABLED_STATE_* (recopiées : la logique se teste hors Android).
    const val ACTIVATION_DEFAUT = 0
    const val ACTIVEE = 1
    const val DESACTIVEE = 2
    const val DESACTIVEE_PAR_UTILISATEUR = 3
    const val DESACTIVEE_JUSQU_A_USAGE = 4

    /**
     * Ce que le système dit d'un paquet pour l'utilisateur de l'appareil.
     * `installePourUtilisateur` : ApplicationInfo.FLAG_INSTALLED (faux après « pm uninstall --user 0 » d'une appli
     * système : le paquet existe encore pour le système, plus pour cet utilisateur).
     * `reglageActivation` : getApplicationEnabledSetting ; `activeParDefaut` : ApplicationInfo.enabled (manifeste) ;
     * `lancable` : une activité de lancement (téléphone ou télé) répond encore.
     */
    class EtatPaquet(val installePourUtilisateur: Boolean, val reglageActivation: Int, val activeParDefaut: Boolean, val lancable: Boolean)

    /**
     * Vrai si l'appli est installée, activée et lançable pour cet utilisateur : sinon elle n'a plus rien à faire
     * sur l'accueil, dans le dock ni dans le tiroir. `null` : le système ne la connaît plus (désinstallée).
     * Une appli désactivée (« pm disable-user », réglages > Désactiver) compte comme absente.
     */
    fun presente(e: EtatPaquet?): Boolean {
        if (e == null || !e.installePourUtilisateur || !e.lancable) return false
        return when (e.reglageActivation) {
            ACTIVEE -> true
            ACTIVATION_DEFAUT -> e.activeParDefaut
            else -> false
        }
    }

    /**
     * Le dock affiché : celui choisi (sans les applis absentes), ou, jamais choisi, celui par défaut (téléphone,
     * messages, navigateur…) recalculé, sans les absentes, trois au plus. Une appli désinstallée n'y reste jamais,
     * même si le système résout encore son intent par défaut un instant.
     */
    fun dockAffiche(choisi: List<String>?, parDefaut: () -> List<String>, presente: (String) -> Boolean): List<String> =
        if (choisi != null) choisi.distinct().filter(presente)
        else parDefaut().distinct().filter(presente).take(3)

    /** Vrai si l'état d'accueil enregistré mentionne `pkg` (case, dossier, ordre, dock, masquées, connues). */
    fun concerne(pkg: String, e: EtatAccueil): Boolean =
        e.places.any { it.cle == pkg } || e.dossiers.any { pkg in it.pkgs } || pkg in e.ordre ||
            e.dock?.contains(pkg) == true || pkg in e.masquees || pkg in e.connues

    /**
     * Le suivi des applis présentes (installées, activées, lançables), vu par les trois voies (rappel du lanceur,
     * diffusions, relevé). Un changement ne demande un rebâtissage que si l'appli passe réellement de présente à
     * absente ou l'inverse, ou si le nettoyage a retiré quelque chose de l'accueil. Une appli sans lanceur jamais
     * présente (Services Google Play, « modifié » toutes les 3 à 5 s) est ignorée, autant de fois qu'elle change.
     */
    class SuiviPresence(initiales: Collection<String>) {
        private val presentes = HashSet(initiales)
        fun etaitPresente(pkg: String): Boolean = synchronized(presentes) { pkg in presentes }
        /** Note le constat ; vrai s'il faut rebâtir (et prévenir Home Assistant). */
        fun constat(pkg: String, presente: Boolean, accueilChange: Boolean): Boolean = synchronized(presentes) {
            val avant = pkg in presentes
            if (presente) presentes.add(pkg) else presentes.remove(pkg)
            avant != presente || accueilChange
        }
    }

    /** Ce que l'accueil fait d'un changement d'appli. */
    enum class Maj { AU_RETOUR, REESSAYER, MAINTENANT }

    /** Attente maximale d'une touche relâchée avant de rebâtir quand même (touche dont le relâché est parti ailleurs). */
    const val ATTENTE_TOUCHE_MS = 1_500L

    /**
     * Quand rebâtir l'accueil après un changement d'appli : caché, au retour (empreinte) ; pendant un glisser,
     * dès qu'il finit (on réessaie) ; touche tenue, au relâché, mais jamais plus de [ATTENTE_TOUCHE_MS] ;
     * sinon tout de suite.
     */
    fun quandRebatir(auPremierPlan: Boolean, glisserEnCours: Boolean, toucheTenueDepuisMs: Long?): Maj = when {
        !auPremierPlan -> Maj.AU_RETOUR
        glisserEnCours -> Maj.REESSAYER
        toucheTenueDepuisMs != null && toucheTenueDepuisMs < ATTENTE_TOUCHE_MS -> Maj.REESSAYER
        else -> Maj.MAINTENANT
    }

    /** Préfixe du code d'un widget Android dans une case (« widget:android:<id> » → code « android:<id> »). */
    const val WIDGET_ANDROID = "android:"

    /** Délai pendant lequel l'écran de confirmation suit une demande faite depuis Vision. */
    const val FENETRE_MS = 120_000L

    /**
     * Vrai si l'écran `ecran` (un paquet) est la confirmation de désinstallation demandée depuis
     * Vision il y a `depuisMs`, pour `cible`, et doit donc passer. Jamais pour Vision elle-même
     * (`vision`), jamais hors des installateurs du système, jamais après [FENETRE_MS].
     * `texte` : le texte de l'écran quand on le connaît (accessibilité) ; il doit alors nommer
     * l'appli visée (`libelleCible`), sinon c'est un autre écran (celui de Vision, par exemple)
     * et la protection habituelle s'applique. Un écran qui nomme Vision (`nomVision`) ne passe
     * jamais, sauf si l'appli visée porte elle-même ce mot dans son nom.
     */
    fun ecranLaisse(ecran: String, installateurs: Set<String>, cible: String?, vision: String,
                    depuisMs: Long, texte: String? = null, libelleCible: String? = null, nomVision: String = "vision"): Boolean {
        if (cible.isNullOrEmpty() || cible == vision) return false
        if (ecran !in installateurs) return false
        if (depuisMs < 0 || depuisMs > FENETRE_MS) return false
        if (texte == null) return true
        val t = texte.lowercase()
        val lib = libelleCible?.lowercase()?.trim().orEmpty()
        // Écran encore vide (il se dessine) : rien à protéger pour l'instant.
        if (t.isBlank()) return true
        // L'écran nomme Vision alors que l'appli visée ne s'appelle pas ainsi : c'est Vision qu'on désinstalle.
        val nv = nomVision.lowercase()
        if (nv.isNotEmpty() && t.contains(nv) && !lib.contains(nv)) return false
        return lib.isNotEmpty() && t.contains(lib) || lib.isEmpty() && t.contains(cible.lowercase())
    }
}
