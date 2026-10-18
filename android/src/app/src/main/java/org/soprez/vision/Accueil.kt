// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Ce que l'accueil montre et dans quel ordre, propre à chaque appareil :
 * les applications visibles, l'ordre des cases, les dossiers, le mode
 * (applications, applications et widgets, widgets seuls), les widgets choisis.
 *
 * Une case est soit un paquet (« com.spotify.music »), soit un dossier
 * (« dossier:ab12 »). Les dossiers vivent en JSON : [{id, nom, pkgs: [...]}].
 */
object Accueil {
    /** Accueil commencé vide (1.53.0) : les applis nouvelles ne s'y posent pas d'elles-mêmes. */
    private const val ACCUEIL_VIDE = "accueil_vide"

    private const val PREFS = "lanceur"
    private const val MASQUEES = "masquees"
    private const val ORDRE = "ordre"
    private const val DOSSIERS = "dossiers"
    const val PREFIXE_DOSSIER = "dossier:"

    class Dossier(val id: String, var nom: String, val pkgs: MutableList<String>) {
        val cle get() = PREFIXE_DOSSIER + id
    }

    class Case(val cle: String, val pkg: String? = null, val dossier: Dossier? = null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ réglages simples

    /**
     * Les applis cachées de l'accueil. 1.75.0 : sur une télé installée à neuf, l'accueil part vide et l'on choisit
     * ce qu'on y met (« choisies ») : est alors cachée toute appli installée qui n'a pas été choisie.
     */
    fun masquees(ctx: Context): Set<String> {
        if (!teleChoisies(ctx)) return prefs(ctx).getStringSet(MASQUEES, emptySet()) ?: emptySet()
        val choisies = choisies(ctx)
        return installeesCache(ctx).filter { it !in choisies }.toSet()
    }

    // ------------------------------------------------------------------ 1.75.0 : accueil télé vide au départ

    private const val TELE_MODE = "tele_mode"
    private const val TELE_CHOISIES = "tele_choisies"
    const val MODE_CHOISIES = "choisies"
    const val MODE_MASQUEES = "masquees"

    /** Logique pure : une installation neuve part vide ; une télé déjà équipée (mise à jour, ou accueil retouché) garde le sien. */
    fun modeTeleDepart(premiereInstallation: Boolean, dejaPersonnalise: Boolean): String =
        if (premiereInstallation && !dejaPersonnalise) MODE_CHOISIES else MODE_MASQUEES

    /** À l'ouverture de l'accueil télé : fixe le mode une seule fois, puis n'y touche plus. */
    fun initialiserTele(ctx: Context) {
        if (prefs(ctx).contains(TELE_MODE)) return
        val premiere = try {
            val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            i.firstInstallTime == i.lastUpdateTime
        } catch (_: Exception) { false }
        val retouche = (prefs(ctx).getStringSet(MASQUEES, emptySet()) ?: emptySet()).isNotEmpty() ||
            ordre(ctx).isNotEmpty() || dossiers(ctx).isNotEmpty()
        prefs(ctx).edit().putString(TELE_MODE, modeTeleDepart(premiere, retouche)).apply()
    }

    fun teleChoisies(ctx: Context): Boolean = prefs(ctx).getString(TELE_MODE, MODE_MASQUEES) == MODE_CHOISIES
    fun choisies(ctx: Context): Set<String> = prefs(ctx).getStringSet(TELE_CHOISIES, emptySet()) ?: emptySet()

    @Volatile private var cacheInstallees: Pair<Int, Set<String>>? = null
    private fun installeesCache(ctx: Context): Set<String> {
        val g = Paquets.generation
        cacheInstallees?.let { (gen, s) -> if (gen == g) return s }
        val s = applicationsInstallees(ctx).map { it.activityInfo.packageName }.toSet()
        cacheInstallees = g to s
        return s
    }

    fun cacher(ctx: Context, pkg: String, cachee: Boolean) {
        if (teleChoisies(ctx)) {
            val c = HashSet(choisies(ctx))
            if (cachee) c.remove(pkg) else c.add(pkg)
            prefs(ctx).edit().putStringSet(TELE_CHOISIES, c).apply()
        } else {
            val s = HashSet(prefs(ctx).getStringSet(MASQUEES, emptySet()) ?: emptySet())
            if (cachee) s.add(pkg) else s.remove(pkg)
            prefs(ctx).edit().putStringSet(MASQUEES, s).apply()
        }
        if (cachee) {
            // Une appli cachée sort aussi de son dossier.
            val ds = dossiers(ctx)
            ds.forEach { it.pkgs.remove(pkg) }
            ecrireDossiers(ctx, ds.filter { it.pkgs.isNotEmpty() })
        }
    }

    fun ordre(ctx: Context): List<String> = (prefs(ctx).getString(ORDRE, "") ?: "").split(",").filter { it.isNotEmpty() }
    fun ecrireOrdre(ctx: Context, l: List<String>) = prefs(ctx).edit().putString(ORDRE, l.joinToString(",")).apply()

    // ------------------------------------------------------------------ dossiers

    fun dossiers(ctx: Context): MutableList<Dossier> {
        val brut = prefs(ctx).getString(DOSSIERS, "[]") ?: "[]"
        val sortie = ArrayList<Dossier>()
        try {
            val a = JSONArray(brut)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val p = o.optJSONArray("pkgs")
                sortie.add(Dossier(o.optString("id"), o.optString("nom", Tr.s(R.string.accueil_dossier)), (0 until (p?.length() ?: 0)).map { p!!.optString(it) }.toMutableList()))
            }
        } catch (_: Exception) {}
        return sortie
    }

    fun ecrireDossiers(ctx: Context, ds: List<Dossier>) {
        val a = JSONArray()
        ds.forEach { d -> a.put(JSONObject().put("id", d.id).put("nom", d.nom).put("pkgs", JSONArray(d.pkgs))) }
        prefs(ctx).edit().putString(DOSSIERS, a.toString()).apply()
    }

    /** Crée un dossier avec ces deux applis, à la place de la première. */
    fun creerDossier(ctx: Context, cases: List<String>, a: String, b: String): Dossier {
        val d = Dossier(java.lang.Long.toHexString(System.currentTimeMillis()).takeLast(6), Tr.s(R.string.accueil_dossier), mutableListOf(a, b))
        val ds = dossiers(ctx); ds.add(d); ecrireDossiers(ctx, ds)
        val l = ArrayList(cases)
        val k = l.indexOf(a)
        l.remove(a); l.remove(b)
        l.add(k.coerceIn(0, l.size), d.cle)
        ecrireOrdre(ctx, l)
        return d
    }

    fun ajouterAuDossier(ctx: Context, cases: List<String>, dossierCle: String, pkg: String) {
        val ds = dossiers(ctx)
        val d = ds.firstOrNull { it.cle == dossierCle } ?: return
        if (pkg !in d.pkgs) d.pkgs.add(pkg)
        ecrireDossiers(ctx, ds)
        ecrireOrdre(ctx, cases.filter { it != pkg })
    }

    /** Sort une appli d'un dossier et la remet sur l'accueil, juste après le dossier. */
    fun sortirDuDossier(ctx: Context, cases: List<String>, dossierCle: String, pkg: String) {
        val ds = dossiers(ctx)
        val d = ds.firstOrNull { it.cle == dossierCle } ?: return
        d.pkgs.remove(pkg)
        val l = ArrayList(cases)
        val k = l.indexOf(dossierCle)
        if (d.pkgs.size <= 1) {
            // Un dossier d'une seule appli n'a plus de raison d'être.
            ds.remove(d)
            if (k >= 0) l.removeAt(k)
            d.pkgs.forEach { l.add(k.coerceIn(0, l.size), it) }
        }
        l.add((k + 1).coerceIn(0, l.size), pkg)
        ecrireDossiers(ctx, ds)
        ecrireOrdre(ctx, l)
    }

    fun renommerDossier(ctx: Context, dossierCle: String, nom: String) {
        val ds = dossiers(ctx)
        ds.firstOrNull { it.cle == dossierCle }?.nom = nom.trim().ifEmpty { Tr.s(R.string.accueil_dossier) }
        ecrireDossiers(ctx, ds)
    }

    // ------------------------------------------------------------------ les cases

    /** Toutes les applications lançables, par ordre alphabétique. Sur téléphone, Vision elle-même en fait partie (ses réglages) ; sur télé, la roue y mène. */
    fun applicationsInstallees(ctx: Context): List<ResolveInfo> {
        val pm = ctx.packageManager
        val vues = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (r in pm.queryIntentActivities(i, 0)) {
                val pkg = r.activityInfo.packageName
                if (pkg == ctx.packageName || pkg in vues) continue
                vues[pkg] = r
            }
        }
        if (!ReglagesTvActivity.estTele(ctx)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(ctx.packageName)
            pm.queryIntentActivities(i, 0).firstOrNull { it.activityInfo.name.endsWith("SetupActivity") }?.let { vues[ctx.packageName] = it }
        }
        // 1.81.0 (point 14 bis) : le Mode Party, une « appli » de l'accueil quand Home Assistant la propose ici.
        if (Party.tuile(ctx)) vues[Party.CLE] = ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = Party.CLE; name = PartyActivity::class.java.name; nonLocalizedLabel = Party.NOM; applicationInfo = ctx.applicationInfo
            }
            nonLocalizedLabel = Party.NOM
        }
        return vues.values.sortedBy { it.loadLabel(pm).toString().lowercase(Locale.FRANCE) }
    }

    /** Les cases de l'accueil, dans l'ordre : applis visibles hors dossiers, et dossiers. Les nouvelles à la fin. */
    fun cases(ctx: Context): List<Case> {
        val masquees = masquees(ctx)
        val ordre = ordre(ctx)
        val installees = applicationsInstallees(ctx).associateBy { it.activityInfo.packageName }
        val ds = dossiers(ctx)
        ds.forEach { d -> d.pkgs.retainAll { it in installees && it !in masquees } }
        val dansDossier = ds.flatMap { it.pkgs }.toSet()
        val parCle = HashMap<String, Case>()
        installees.keys.filter { it !in masquees && it !in dansDossier }.forEach { parCle[it] = Case(it, pkg = it) }
        ds.filter { it.pkgs.isNotEmpty() }.forEach { parCle[it.cle] = Case(it.cle, dossier = it) }
        val sortie = ArrayList<Case>()
        ordre.forEach { cle -> parCle.remove(cle)?.let { sortie.add(it) } }
        sortie.addAll(parCle.values.sortedBy { c -> if (c.pkg != null) installees[c.pkg]!!.loadLabel(ctx.packageManager).toString().lowercase(Locale.FRANCE) else c.dossier!!.nom.lowercase(Locale.FRANCE) })
        return sortie
    }

    /** Déplace une case à l'indice voulu (les autres se décalent). */
    fun deplacer(ctx: Context, cases: List<String>, cle: String, vers: Int) {
        val l = ArrayList(cases)
        val k = l.indexOf(cle)
        if (k < 0) return
        l.removeAt(k)
        l.add(vers.coerceIn(0, l.size), cle)
        ecrireOrdre(ctx, l)
    }

    /** La dernière appli ouverte depuis l'accueil : au retour, la sélection se remet dessus. */
    @Volatile var dernierLance: String = ""

    fun lancer(ctx: Context, pkg: String) {
        dernierLance = pkg
        if (pkg == Party.CLE) { Party.ouvrir(ctx); return }
        if (pkg == ctx.packageName) { try { ctx.startActivity(Intent(ctx, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}; return }
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    fun etiquette(ctx: Context, pkg: String): String = if (pkg == Party.CLE) Party.NOM else try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    /**
     * 1.86.7 (l'auteur : « l'icône musique n'est pas comme les autres ») : la tuile Musique au même gabarit que les applis —
     * un disque plein (couleur d'accent du thème actif), la note monochrome dessus, teintée pour le contraste.
     */
    fun iconeMusique(ctx: Context): Drawable {
        val th = Local.themeEffectif(ctx, Palette.LANCEUR)
        val fond = th.or
        val encre = listOf(th.fond, th.encre).maxByOrNull { Math.abs(Palette.luminance(it) - Palette.luminance(fond)) } ?: th.fond
        val cote = 192
        val disque = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fond); setSize(cote, cote) }
        val note = ctx.getDrawable(R.drawable.ic_musique)!!.mutate().apply { setTint(encre) }
        val marge = (cote * 0.26f).toInt()
        return android.graphics.drawable.LayerDrawable(arrayOf(disque, note)).apply { setLayerInset(1, marge, marge, marge, marge) }
    }

    fun icone(ctx: Context, pkg: String): Drawable? = if (pkg == Party.CLE) iconeMusique(ctx) else try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }

    /** L'accueil d'origine (celui de Google ou du fabricant), pour y retourner d'une touche. */
    fun accueilSysteme(ctx: Context): Intent? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val autres = ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).filter { it.activityInfo.packageName != ctx.packageName }
        val r = autres.firstOrNull { it.activityInfo.packageName.contains("launcher") } ?: autres.firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(r.activityInfo.packageName, r.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Le dock du téléphone : trois applications au choix, par défaut téléphone, messages et navigateur. */
    const val DOCK_MAX = 5

    /**
     * Lot accueil (point 3) : le dock ne garde que les applis présentes (installées, activées, lançables pour cet
     * utilisateur). Avant, le dock choisi gardait une appli tant qu'Android en donnait une icône (vrai encore pour
     * une appli désactivée), et le dock par défaut n'était revérifié qu'au rebâtissage suivant.
     */
    fun dock(ctx: Context): List<String> {
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dock", null)
        val choisi = brut?.let { try { val a = JSONArray(it); (0 until a.length()).map { i -> a.getString(i) } } catch (_: Exception) { emptyList() } }
        return NettoyageAccueil.dockAffiche(choisi, { dockParDefaut(ctx).mapNotNull { it.pkg } }) { presente(ctx, it) }
    }

    // ------------------------------------------------------------------ lot accueil (point 3) : présence d'une appli

    @Volatile private var cachePresence: Pair<Int, java.util.concurrent.ConcurrentHashMap<String, Boolean>>? = null

    /**
     * Vrai si l'appli est installée, activée et lançable pour cet utilisateur (Mode Party : si sa tuile est
     * proposée ; Vision : toujours). Gardé en mémoire jusqu'au prochain changement d'appli (Paquets.generation).
     */
    fun presente(ctx: Context, pkg: String): Boolean {
        if (pkg == Party.CLE) return Party.tuile(ctx)
        if (pkg == ctx.packageName) return true
        val g = Paquets.generation
        val c = cachePresence?.takeIf { it.first == g }?.second
            ?: java.util.concurrent.ConcurrentHashMap<String, Boolean>().also { cachePresence = g to it }
        return c.getOrPut(pkg) { NettoyageAccueil.presente(etatPaquet(ctx, pkg)) }
    }

    /**
     * L'état du paquet pour cet utilisateur, lu à l'instant (sans mémoire). `null` : inconnu du système.
     * MATCH_UNINSTALLED / MATCH_DISABLED : on veut le voir même désinstallé pour cet utilisateur ou désactivé,
     * pour le dire absent en connaissance de cause (FLAG_INSTALLED, réglage d'activation).
     */
    fun etatPaquet(ctx: Context, pkg: String): NettoyageAccueil.EtatPaquet? {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val drapeaux = PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_DISABLED_COMPONENTS
        val ai = try { pm.getApplicationInfo(pkg, drapeaux) } catch (_: Exception) { return null }
        val reglage = try { pm.getApplicationEnabledSetting(pkg) } catch (_: Exception) { return null }
        val lancable = try { pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null } != null
        return NettoyageAccueil.EtatPaquet((ai.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0, reglage, ai.enabled, lancable)
    }

    fun ecrireDock(ctx: Context, pkgs: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dock", JSONArray(pkgs.take(DOCK_MAX)).toString()).apply()
    }

    class Raccourci(val nom: String, val intent: Intent, val pkg: String?, val icone: Int)

    fun dockParDefaut(ctx: Context): List<Raccourci> {
        val pm = ctx.packageManager
        fun qui(i: Intent): String? = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" }
        val sortie = ArrayList<Raccourci>()
        val tel = Intent(Intent.ACTION_DIAL)
        qui(tel)?.let { sortie.add(Raccourci(ctx.getString(R.string.accueil_dock_telephone), tel, it, R.drawable.ic_tel)) }
        val sms = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
        qui(sms)?.let { sortie.add(Raccourci(ctx.getString(R.string.accueil_dock_messages), sms, it, R.drawable.ic_sms)) }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.qwant.com"))
        qui(nav)?.let { sortie.add(Raccourci(ctx.getString(R.string.accueil_dock_navigateur), nav, it, R.drawable.ic_nav)) }
        val photo = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        qui(photo)?.let { sortie.add(Raccourci(ctx.getString(R.string.accueil_dock_photo), photo, it, R.drawable.ic_photo)) }
        return sortie
    }

    // ------------------------------------------------------------------ téléphone : disposition libre, comme Android

    const val COLONNES = 4
    const val PREFIXE_WIDGET = "widget:"

    /** L'accueil défile (une longue grille) ou se feuillette (des pages). */
    const val MODE_DEFILEMENT = "defilement"
    const val MODE_PAGES = "pages"
    /** l'auteur (13 octobre) : toutes les applis autorisées en liste alphabétique, avec un index sur le côté (DispositionAccueil). */
    const val MODE_ALPHA = "alpha"
    fun mode(ctx: Context): String = prefs(ctx).getString("mode", MODE_DEFILEMENT) ?: MODE_DEFILEMENT
    fun poserMode(ctx: Context, m: String) { prefs(ctx).edit().putString("mode", m).apply() }

    /** En mode pages : le nombre de lignes d'une page (0 = pas de pages) ; une case ne chevauche jamais deux pages. */
    @Volatile var lignesParPage = 0
    private fun surUnePage(row: Int, h: Int): Boolean = lignesParPage <= 0 || row / lignesParPage == (row + h - 1) / lignesParPage

    /** Une case posée sur la grille : une appli, un dossier ou un widget, à une colonne et une ligne, sur w × h cellules. */
    class Place(val cle: String, var col: Int, var row: Int, var w: Int, var h: Int) {
        val estWidget get() = cle.startsWith(PREFIXE_WIDGET)
        val estDossier get() = cle.startsWith(PREFIXE_DOSSIER)
        val code get() = cle.removePrefix(PREFIXE_WIDGET)
        fun couvre(c: Int, r: Int) = c in col until col + w && r in row until row + h
    }

    /** Les widgets posables : code, nom, largeur, hauteur (en cellules), publics (vide = tous). */
    class Widget(val code: String, val nom: String, val w: Int, val h: Int, val publics: Set<String> = emptySet())
    val WIDGETS: List<Widget> get() = listOf(
        Widget("horloge", Tr.s(R.string.accueil_widget_horloge), 4, 2),
        // 1.90.5 (retour d'essai) : l'horloge stylisée de Vision, aussi posable dans la grille (mêmes réglages que dans la zone du haut).
        Widget(ZoneHaut.HORLOGE, Tr.s(R.string.zonehaut_widget_horloge), 2, 2),
        Widget("infos", Tr.s(R.string.accueil_widget_infos), 4, 1, setOf("enfant")),
        Widget("devoirs", Tr.s(R.string.accueil_widget_devoirs), 4, 2, setOf("enfant")),
        Widget("temps", Tr.s(R.string.accueil_widget_temps), 4, 1, setOf("enfant")),
        Widget("demandes", Tr.s(R.string.accueil_widget_demandes), 4, 2, setOf("parent")),
        Widget("enfants", Tr.s(R.string.accueil_widget_personnes), 4, 2, setOf("parent")),
        Widget("chauffage", Tr.s(R.string.accueil_widget_chauffage), 2, 2),
        Widget("fioul", Tr.s(R.string.accueil_widget_fioul), 2, 2),
        Widget("ecole", Tr.s(R.string.accueil_widget_ecole), 4, 2),
        Widget("camera", Tr.s(R.string.accueil_widget_camera), 2, 2),
        Widget("batteries", Tr.s(R.string.accueil_widget_batteries), 2, 2),
        Widget("maison", Tr.s(R.string.accueil_widget_presents), 4, 1),
    )
    fun widget(code: String): Widget? = WIDGETS.firstOrNull { it.code == code }

    // ------------------------------------------------------------------ 15-10 : widgets selon les données du lieu

    /**
     * Les widgets intégrés qui montrent des données de Home Assistant (météo, chauffage, école, caméra, batteries,
     * personnes…) : proposés seulement si le lieu a ces données (l'auteur : Vision va dans d'autres foyers, rien en dur).
     * Les autres (horloge stylisée, temps d'écran, demandes) suivent seulement les modules.
     */
    val DEPENDANTS = setOf("horloge", "chauffage", "fioul", "ecole", "infos", "devoirs", "camera", "batteries", "enfants", "maison")

    /** Retient « widgets_disponibles » du dernier relevé /veille (absent : un ancien Home Assistant, rien ne change). */
    fun noterDisponibles(ctx: Context, r: JSONObject) {
        val a = r.optJSONArray("widgets_disponibles") ?: return
        val l = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        prefs(ctx).edit().putString("widgets_disponibles", JSONArray(l).toString()).apply()
    }

    /** La liste retenue, ou null si Home Assistant n'en a jamais envoyé. */
    fun disponibles(ctx: Context): Set<String>? = prefs(ctx).getString("widgets_disponibles", null)
        ?.let { s -> try { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } catch (_: Exception) { null } }

    /** Le widget [code] peut-il être proposé, d'après la liste [disponibles] de Home Assistant (null : on ne sait pas, oui). Pur. */
    fun disponible(code: String, disponibles: Set<String>?): Boolean = disponibles == null || code !in DEPENDANTS || code in disponibles

    fun disposition(ctx: Context): MutableList<Place> {
        val brut = prefs(ctx).getString("disposition", null)
        val sortie = ArrayList<Place>()
        if (brut != null) try {
            val a = JSONArray(brut)
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); sortie.add(Place(o.optString("cle"), o.optInt("col"), o.optInt("row"), o.optInt("w", 1), o.optInt("h", 1))) }
        } catch (_: Exception) {}
        return sortie
    }

    fun ecrireDisposition(ctx: Context, l: List<Place>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("cle", it.cle).put("col", it.col).put("row", it.row).put("w", it.w).put("h", it.h)) }
        prefs(ctx).edit().putString("disposition", a.toString()).apply()
    }

    fun libre(l: List<Place>, col: Int, row: Int, w: Int, h: Int, sauf: String? = null): Boolean {
        if (col < 0 || row < 0 || col + w > COLONNES || !surUnePage(row, h)) return false
        return l.none { p -> p.cle != sauf && (0 until w).any { dc -> (0 until h).any { dr -> p.couvre(col + dc, row + dr) } } }
    }

    /** La première place libre pour une case w × h, en balayant ligne par ligne. */
    fun placeLibre(l: List<Place>, w: Int, h: Int, sauf: String? = null): Pair<Int, Int> {
        var row = 0
        while (row < 400) {
            for (col in 0..(COLONNES - w)) if (libre(l, col, row, w, h, sauf)) return col to row
            row++
        }
        return 0 to row
    }

    fun placeA(l: List<Place>, col: Int, row: Int): Place? = l.firstOrNull { it.couvre(col, row) }

    /**
     * Met la disposition d'équerre avec ce qui est installé : les applis
     * disparues s'en vont, les nouvelles arrivent à la première place libre.
     * Au tout premier passage, toutes les applis visibles sont posées, après
     * les widgets de départ du profil.
     */
    fun synchroniser(ctx: Context, profil: String): MutableList<Place> {
        val l = disposition(ctx)
        val premier = !prefs(ctx).contains("disposition")
        val installees = applicationsInstallees(ctx).map { it.activityInfo.packageName }.toSet()
        val ds = dossiers(ctx)
        ds.forEach { d -> d.pkgs.retainAll { it in installees } }
        ecrireDossiers(ctx, ds.filter { it.pkgs.isNotEmpty() })
        val dansDossier = ds.flatMap { it.pkgs }.toSet()
        l.removeAll { p -> (!p.estWidget && !p.estDossier && (p.cle !in installees || p.cle in dansDossier)) || (p.estDossier && ds.none { it.cle == p.cle && it.pkgs.isNotEmpty() }) || (p.estWidget && widget(p.code) == null && !WidgetsAndroid.estAndroid(p.code) && !WidgetsHa.estHa(p.code)) }
        if (premier) {
            // 1.53.0 : un accueil neuf est vide (ni applis ni widgets) ; on y pose ce qu'on veut. Le tiroir
            // garde toutes les applis. Un accueil déjà construit (appareil existant) garde son habitude.
            prefs(ctx).edit().putString("profil_depart", profil).putBoolean(ACCUEIL_VIDE, true).apply()
        }
        val vide = prefs(ctx).getBoolean(ACCUEIL_VIDE, false)
        val posees = l.map { it.cle }.toSet()
        val masquees = masquees(ctx)
        // Les dossiers connus mais pas posés reviennent, puis les applis nouvelles (ou toutes, la première fois).
        ds.filter { it.pkgs.isNotEmpty() && it.cle !in posees }.forEach { d -> val (c, r) = placeLibre(l, 1, 1); l.add(Place(d.cle, c, r, 1, 1)) }
        val connues = HashSet(prefs(ctx).getStringSet("connues", emptySet()) ?: emptySet())
        if (!vide) installees.filter { it !in posees && it !in dansDossier && it !in masquees && (premier || it !in connues) }
            .sortedBy { etiquette(ctx, it).lowercase(Locale.FRANCE) }
            .forEach { pkg -> val (c, r) = placeLibre(l, 1, 1); l.add(Place(pkg, c, r, 1, 1)) }
        prefs(ctx).edit().putStringSet("connues", HashSet(installees)).apply()
        ecrireDisposition(ctx, l)
        return l
    }

    /** L'état d'accueil enregistré, tel quel (sans filtrer ce qui n'est plus installé) : voir NettoyageAccueil. */
    fun etatEnregistre(ctx: Context): NettoyageAccueil.EtatAccueil {
        val brutDock = prefs(ctx).getString("dock", null)
        val dock = brutDock?.let { try { val a = JSONArray(it); (0 until a.length()).map { i -> a.getString(i) } } catch (_: Exception) { null } }
        return NettoyageAccueil.EtatAccueil(disposition(ctx), dossiers(ctx), ordre(ctx), dock, masquees(ctx),
            prefs(ctx).getStringSet("connues", emptySet()) ?: emptySet())
    }

    fun ecrireEtat(ctx: Context, e: NettoyageAccueil.EtatAccueil) {
        val ed = prefs(ctx).edit()
        if (prefs(ctx).contains("disposition")) {
            val a = JSONArray()
            e.places.forEach { a.put(JSONObject().put("cle", it.cle).put("col", it.col).put("row", it.row).put("w", it.w).put("h", it.h)) }
            ed.putString("disposition", a.toString())
        }
        val d = JSONArray()
        e.dossiers.forEach { x -> d.put(JSONObject().put("id", x.id).put("nom", x.nom).put("pkgs", JSONArray(x.pkgs))) }
        ed.putString(DOSSIERS, d.toString())
        ed.putString(ORDRE, e.ordre.joinToString(","))
        e.dock?.let { ed.putString("dock", JSONArray(it).toString()) }
        ed.putStringSet(MASQUEES, HashSet(e.masquees))
        if (prefs(ctx).contains("connues")) ed.putStringSet("connues", HashSet(e.connues))
        ed.apply()
    }

    fun retirerDeLaGrille(ctx: Context, cle: String) {
        val l = disposition(ctx); l.removeAll { it.cle == cle }; ecrireDisposition(ctx, l)
        if (!cle.startsWith(PREFIXE_WIDGET) && !cle.startsWith(PREFIXE_DOSSIER)) cacher(ctx, cle, true)
        // Un widget Home Assistant retiré : son réglage part avec lui.
        val code = cle.removePrefix(PREFIXE_WIDGET)
        if (cle.startsWith(PREFIXE_WIDGET) && WidgetsHa.estHa(code)) WidgetsHaStock.supprimer(ctx, WidgetsHa.id(code))
    }

    /** Redimensionne une case (widget Android) : sur place si la nouvelle taille tient, sinon à la première place libre. */
    fun redimensionner(ctx: Context, cle: String, w: Int, h: Int): Boolean {
        val l = disposition(ctx)
        val p = l.firstOrNull { it.cle == cle } ?: return false
        val col = p.col.coerceAtMost(COLONNES - w).coerceAtLeast(0)
        if (libre(l, col, p.row, w, h, sauf = cle)) { p.col = col; p.w = w; p.h = h }
        else { val (c, r) = placeLibre(l, w, h, sauf = cle); p.col = c; p.row = r; p.w = w; p.h = h }
        ecrireDisposition(ctx, l)
        return true
    }

    /** Pose une case à une place précise (redimensionnement par le cadre : la place a déjà été vérifiée, Redim). */
    fun poserPlace(ctx: Context, cle: String, col: Int, row: Int, w: Int, h: Int) {
        val l = disposition(ctx)
        val p = l.firstOrNull { it.cle == cle } ?: return
        p.col = col; p.row = row; p.w = w; p.h = h
        ecrireDisposition(ctx, l)
    }

    fun ajouterALaGrille(ctx: Context, cle: String, w: Int = 1, h: Int = 1) {
        val l = disposition(ctx)
        if (l.any { it.cle == cle }) return
        if (!cle.startsWith(PREFIXE_WIDGET) && !cle.startsWith(PREFIXE_DOSSIER)) cacher(ctx, cle, false)
        val (c, r) = placeLibre(l, w, h)
        l.add(Place(cle, c, r, w, h)); ecrireDisposition(ctx, l)
    }

    /**
     * Supprime une page entière de l'accueil (mode pages), comme Android : ses applis
     * sont masquées (elles restent dans le tiroir), ses dossiers défaits et masqués,
     * ses widgets retirés ; les pages suivantes remontent d'un cran.
     */
    fun supprimerPage(ctx: Context, page: Int, lignes: Int) {
        if (lignes <= 0) return
        val l = disposition(ctx)
        val debut = page * lignes; val fin = debut + lignes
        val dedans = l.filter { it.row in debut until fin }
        val ds = dossiers(ctx)
        val masquees = HashSet(masquees(ctx))
        dedans.forEach { p ->
            when {
                p.estWidget -> {}
                p.estDossier -> { ds.firstOrNull { it.cle == p.cle }?.let { d -> masquees.addAll(d.pkgs); ds.remove(d) } }
                else -> masquees.add(p.cle)
            }
        }
        ecrireDossiers(ctx, ds)
        prefs(ctx).edit().putStringSet("masquees", masquees).apply()
        l.removeAll { it.row in debut until fin }
        l.forEach { if (it.row >= fin) it.row -= lignes }
        ecrireDisposition(ctx, l)
    }

    /** Pose une nouvelle case (widget ou appli) au plus près d'une cellule : utilisé quand on la glisse depuis le panneau. */
    fun poserSurGrille(ctx: Context, cle: String, col: Int, row: Int, w: Int = 1, h: Int = 1) {
        ajouterALaGrille(ctx, cle, w, h)
        deplacerSurGrille(ctx, cle, col, row)
    }

    /** Déplace une case ; la cible occupée par une case de même taille s'échange, sinon on cherche la place libre la plus proche. */
    fun deplacerSurGrille(ctx: Context, cle: String, col: Int, row: Int) {
        val l = disposition(ctx)
        val p = l.firstOrNull { it.cle == cle } ?: return
        val c0 = col.coerceIn(0, COLONNES - p.w); val r0 = row.coerceAtLeast(0)
        if (libre(l, c0, r0, p.w, p.h, sauf = cle)) { p.col = c0; p.row = r0; ecrireDisposition(ctx, l); return }
        val autre = placeA(l.filter { it.cle != cle }, c0, r0)
        if (autre != null && autre.w == p.w && autre.h == p.h) {
            val (ac, ar) = autre.col to autre.row
            autre.col = p.col; autre.row = p.row; p.col = ac; p.row = ar
            ecrireDisposition(ctx, l); return
        }
        // La place libre la plus proche de la cible.
        val meilleur = Glisser.caseLibre(c0, r0, p.w, p.h, COLONNES, (l.maxOfOrNull { it.row + it.h } ?: 0) + p.h + 1) { c, r -> libre(l, c, r, p.w, p.h, sauf = cle) }
        meilleur?.let { p.col = it.first; p.row = it.second; ecrireDisposition(ctx, l) }
    }

    /** Où tombe une appli tirée du tiroir ou d'un dossier, lâchée sur (col, row) : là si c'est libre, sinon la case libre la plus proche. */
    fun caseAjout(l: List<Place>, pkg: String, col: Int, row: Int): Pair<Int, Int> {
        val lignes = maxOf(l.maxOfOrNull { it.row + it.h } ?: 0, row) + 2
        return Glisser.caseLibre(col, row, 1, 1, COLONNES, lignes) { c, r -> libre(l, c, r, 1, 1, sauf = pkg) } ?: placeLibre(l, 1, 1, sauf = pkg)
    }

    /**
     * Une appli tirée du tiroir ou d'un dossier et lâchée sur l'accueil : jamais en double. Dans un dossier,
     * elle en sort ; déjà sur l'accueil, elle se déplace ; sinon elle s'ajoute. Elle va à la case visée, ou à
     * la libre la plus proche.
     */
    fun poserAppli(ctx: Context, pkg: String, col: Int, row: Int) {
        dossiers(ctx).firstOrNull { pkg in it.pkgs }?.let { sortirSurGrille(ctx, it.cle, pkg) }
        ajouterALaGrille(ctx, pkg)
        val l = disposition(ctx)
        val p = l.firstOrNull { it.cle == pkg } ?: return
        val (c, r) = caseAjout(l, pkg, col, row)
        p.col = c; p.row = r; ecrireDisposition(ctx, l)
    }

    /** Deux applis l'une sur l'autre : un dossier à la place de la cible. */
    fun fusionnerSurGrille(ctx: Context, cible: String, source: String): String {
        val l = disposition(ctx)
        val pc = l.firstOrNull { it.cle == cible } ?: return cible
        val ds = dossiers(ctx)
        val dossier: Dossier
        if (cible.startsWith(PREFIXE_DOSSIER)) {
            dossier = ds.first { it.cle == cible }
            if (source !in dossier.pkgs) dossier.pkgs.add(source)
        } else {
            dossier = Dossier(java.lang.Long.toHexString(System.currentTimeMillis()).takeLast(6), Tr.s(R.string.accueil_dossier), mutableListOf(cible, source))
            ds.add(dossier)
        }
        ecrireDossiers(ctx, ds)
        l.removeAll { it.cle == source || it.cle == cible }
        l.add(Place(dossier.cle, pc.col, pc.row, 1, 1))
        ecrireDisposition(ctx, l)
        return dossier.cle
    }

    /** Sort une appli d'un dossier vers la grille, près du dossier. */
    fun sortirSurGrille(ctx: Context, dossierCle: String, pkg: String) {
        val ds = dossiers(ctx)
        val d = ds.firstOrNull { it.cle == dossierCle } ?: return
        d.pkgs.remove(pkg)
        val l = disposition(ctx)
        val pd = l.firstOrNull { it.cle == dossierCle }
        if (d.pkgs.size <= 1) {
            ds.remove(d); l.removeAll { it.cle == dossierCle }
            d.pkgs.forEach { reste -> val (c, r) = if (pd != null && libre(l, pd.col, pd.row, 1, 1)) pd.col to pd.row else placeLibre(l, 1, 1); l.add(Place(reste, c, r, 1, 1)) }
        }
        ecrireDossiers(ctx, ds)
        val (c, r) = placeLibre(l, 1, 1)
        l.add(Place(pkg, c, r, 1, 1))
        ecrireDisposition(ctx, l)
    }

    fun defaireSurGrille(ctx: Context, dossierCle: String) {
        val ds = dossiers(ctx)
        val d = ds.firstOrNull { it.cle == dossierCle } ?: return
        val l = disposition(ctx)
        l.removeAll { it.cle == dossierCle }
        d.pkgs.forEach { pkg -> val (c, r) = placeLibre(l, 1, 1); l.add(Place(pkg, c, r, 1, 1)) }
        ds.remove(d); ecrireDossiers(ctx, ds); ecrireDisposition(ctx, l)
    }
}
