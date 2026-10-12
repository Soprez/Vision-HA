// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Écran plein affiché quand une appli bloquée s'ouvre, ou quand l'appareil
 * est fermé (téléphone comme télé). Aux couleurs du thème choisi sur l'appareil
 * (le même que l'accueil), avec l'œil de Vision. On y lit ce qui est fermé,
 * pourquoi, jusqu'à quand, et on peut demander un déverrouillage, du temps ou
 * l'accès : la demande part chez un gestionnaire, qui répond depuis son
 * téléphone. L'écran se retire tout seul dès que l'accès rouvre.
 */
class BlockActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var titre: TextView
    private lateinit var pourquoi: TextView
    private lateinit var quand: TextView
    private lateinit var ligneQuand: View
    private lateinit var zoneDemande: LinearLayout
    private var demandeEnvoyee = 0L
    /** 1.82.2 (bug de Alex) : la fenêtre « motif » ouverte ; tant qu'elle l'est, l'écran ne se retire pas. */
    private var dialogueDemande: android.app.AlertDialog? = null
    private var degrade: GradientDrawable? = null

    /** Le thème avec lequel l'écran est peint : s'il change (réglage, mode sombre, plage de nuit), on repeint tout. */
    private var signatureTheme = ""

    private val boucle = object : Runnable {
        override fun run() {
            if (!devraitRester()) { accueil(); finish(); return }
            // Lot Accueil (point 4) : thème changé pendant l'affichage → l'écran suit, sauf pendant la saisie d'un motif.
            if (signature(Ui.themeActif(this@BlockActivity)) != signatureTheme && dialogueDemande?.isShowing != true) construire()
            else peindre()
            h.postDelayed(this, 2000)
        }
    }

    private fun signature(t: Theme) = "${t.nom}|${t.fond}|${t.carte}|${t.encre}|${t.or}|${t.sombre}"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        construire()
    }

    /**
     * Lot Accueil (point 4) : l'écran est en singleTask ; un deuxième blocage (une autre appli, une autre raison)
     * arrive ici et non dans onCreate. On prend la nouvelle demande et on reconstruit tout l'écran pour elle.
     */
    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (i == null) return
        val avant = intent.getStringExtra("pkg") ?: ""
        setIntent(i)
        if ((i.getStringExtra("pkg") ?: "") != avant) {
            // La fenêtre de motif ouverte et la demande de temps en cours concernaient l'appli précédente.
            try { dialogueDemande?.dismiss() } catch (_: Exception) {}
            dialogueDemande = null; demandeEnvoyee = 0L
        }
        construire()
    }

    /** Tout l'écran, aux couleurs du thème de l'appareil du moment, pour la demande en cours ([getIntent]). */
    private fun construire() {
        Ui.charger(this)
        signatureTheme = signature(Ui.themeActif(this))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND
        }

        // Le fond du thème de l'appareil, à peine teinté de son accent en haut (plus de bleu nuit fixe). Il habille toute
        // la fenêtre, bandeaux des barres système compris (sinon une ligne de rupture sous l'heure et sous les gestes).
        degrade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Palette.melanger(Ui.FOND, Ui.OR, 0.10f), Ui.FOND))
        window.setBackgroundDrawable(degrade)
        val tele = ReglagesTvActivity.estTele(this)
        val m = Marges.calculer(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
            resources.displayMetrics.density, tele)
        val fond = Ui.colonne(this).apply {
            gravity = Gravity.CENTER
            setPadding(m.cote, m.haut, m.cote, m.bas)
        }
        // L'œil de Vision, le logo officiel tel quel (sans teinte ni rond de couleur derrière) ; plus petit sur télé,
        // où la hauteur utile (zone sûre comprise) est comptée.
        val d = Ui.dp(this, if (tele) 56f else 96f)
        val oeil = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_vision)
            contentDescription = "Vision"
        }
        titre = Ui.texte(this, "", 26f, Ui.TEXTE, gras = true, centre = !tele)
        if (tele) {
            // Télé : l'œil à côté du titre, pour que tout tienne dans la zone sûre sans défiler (taille 100 %).
            val tete = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            tete.addView(oeil, LinearLayout.LayoutParams(d, d).apply { rightMargin = Ui.dp(this@BlockActivity, 18f) })
            tete.addView(titre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            fond.addView(tete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        } else {
            fond.addView(oeil, LinearLayout.LayoutParams(d, d).apply { gravity = Gravity.CENTER; bottomMargin = Ui.dp(this@BlockActivity, 22f) })
            fond.addView(titre)
        }

        // Pourquoi, et jusqu'à quand : une carte du thème, textes en encre pleine (lisibles partout).
        val infos = Ui.carte(this, Ui.CARTE).apply { gravity = Gravity.CENTER_HORIZONTAL }
        fun ligne(etiquette: String, valeur: TextView): View = Ui.colonne(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Ui.texte(this@BlockActivity, etiquette, 13f, Ui.TEXTE_2, gras = true, centre = true))
            addView(Ui.marge(this@BlockActivity, valeur, haut = 2f))
        }
        pourquoi = Ui.texte(this, "", 17f, Ui.TEXTE, centre = true)
        quand = Ui.texte(this, "", 17f, Ui.TEXTE, centre = true)
        infos.addView(ligne(getString(R.string.block_pourquoi), pourquoi))
        ligneQuand = ligne(getString(R.string.block_jusqua_quand), quand)
        infos.addView(Ui.marge(this, ligneQuand, haut = 12f))
        fond.addView(Ui.marge(this, infos, haut = 18f).apply {
            (layoutParams as LinearLayout.LayoutParams).width = ViewGroup.LayoutParams.MATCH_PARENT
        })

        zoneDemande = Ui.colonne(this).apply { gravity = Gravity.CENTER }
        // 1.87.0 (recette visuelle) : toute la largeur de la carte. En largeur « au plus juste », la zone prenait celle
        // de son premier bouton : à 360 dp, « Demander du temps » était rogné et la rangée 15 min / 30 min / 1 h coupée.
        fond.addView(Ui.marge(this, zoneDemande, haut = 16f).apply {
            (layoutParams as LinearLayout.LayoutParams).width = ViewGroup.LayoutParams.MATCH_PARENT
        })
        construireDemande()

        // Appareil fermé ou non, on peut toujours appeler, écrire, régler son réveil, ouvrir Pronote.
        val permises = if (ReglagesTvActivity.estTele(this)) emptyList() else Etat.toujoursPermises(this)
        if (permises.isNotEmpty()) {
            fond.addView(Ui.marge(this, Ui.texte(this, getString(R.string.block_toujours_accessibles), 13f, Ui.TEXTE_2, gras = true, centre = true), haut = 30f))
            // Lot Accueil (point 4) : une grille qui revient à la ligne (plus une rangée qui sortait de l'écran
            // en grands caractères ou sur un écran étroit) ; les noms passent à la ligne, jamais coupés.
            val rang = GrilleCases(this, caseMinDp = 76f, caseMaxDp = 112f)
            for (a in permises.take(6)) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(Ui.dp(this@BlockActivity, 10f), Ui.dp(this@BlockActivity, 8f), Ui.dp(this@BlockActivity, 10f), Ui.dp(this@BlockActivity, 4f))
                    isClickable = true; isFocusable = true; contentDescription = a.nom
                    setOnClickListener { try { packageManager.getLaunchIntentForPackage(a.pkg)?.let { startActivity(it) } } catch (_: Exception) {} }
                }
                Ui.anneauFocus(cell, Ui.themeActif(this), 16f)
                val t = Ui.dp(this, 50f)
                cell.addView(android.widget.ImageView(this).apply { setImageDrawable(Accueil.icone(this@BlockActivity, a.pkg)) }, LinearLayout.LayoutParams(t, t))
                cell.addView(Ui.marge(this, Ui.texte(this, a.nom, 12f, Ui.TEXTE, centre = true), haut = 5f).apply {
                    (layoutParams as LinearLayout.LayoutParams).width = ViewGroup.LayoutParams.MATCH_PARENT
                })
                rang.addView(cell)
            }
            fond.addView(Ui.marge(this, rang, haut = 8f).apply {
                (layoutParams as LinearLayout.LayoutParams).width = ViewGroup.LayoutParams.MATCH_PARENT
            })
        }

        // Centré quand tout tient ; sinon (paysage, grands caractères) on fait défiler au lieu de couper. Sur télé, le
        // défilement reste dans la zone sûre (5 % de chaque côté, que certains téléviseurs rognent) : la marge
        // est portée par le cadre qui défile, qui coupe à ce bord-là et non au bord de l'écran.
        val defile = DefileZoneSure(this).apply {
            isFillViewport = true; background = null
            setPadding(m.zoneCote, m.zoneHaut, m.zoneCote, m.zoneHaut); clipToPadding = true
            addView(fond)
        }
        setContentView(defile, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        peindre()
    }

    /** Trois durées à demander ; après l'envoi, on attend la réponse d'un parent. */
    private fun construireDemande() {
        zoneDemande.removeAllViews()
        construireDeverrouillage()
        // Module « demandes » coupé dans Home Assistant : ni accès ni temps à demander d'ici.
        if (!Modules.actif(this, Modules.DEMANDES)) return
        val pkg = intent.getStringExtra("pkg") ?: ""
        if (!Etat.verrouilleEffectif() && pkg.isNotEmpty()) {
            // Appli fermée (pas la session) : on demande l'accès à cette appli.
            val lib = Usage.libelle(this, pkg)
            val refuse = Acces.refuseJusqua("apps", pkg)
            if (refuse > 0) {
                // Un parent vient de dire non : pas de nouvelle demande avant l'heure dite.
                val c = Ui.carte(this, Ui.CARTE); c.gravity = Gravity.CENTER
                c.addView(Ui.texte(this, getString(R.string.block_demande_refusee), 15f, Ui.OR, gras = true, centre = true))
                c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.block_redemander_a, Etat.heure(refuse)), 13f, Ui.TEXTE_2, centre = true), haut = 6f))
                zoneDemande.addView(c)
            } else if (Acces.dejaDemande("apps", pkg)) {
                val c = Ui.carte(this, Ui.CARTE); c.gravity = Gravity.CENTER
                c.addView(Ui.texte(this, getString(R.string.block_demande_envoyee), 15f, Ui.OR, gras = true, centre = true))
                c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.block_prevenu_reponse, Vocabulaire.decideur(this)), 13f, Ui.TEXTE_2, centre = true), haut = 6f))
                zoneDemande.addView(c)
            } else {
                val btn = Ui.boutonPrimaire(this, getString(R.string.acces_demander_acces, lib)) {
                    dialogueDemande = Acces.dialogue(this, "apps", pkg, lib) { construireDemande() }
                }
                btn.isFocusableInTouchMode = false
                // Recette lot 1 : toute la largeur, hauteur libre (un nom de paquet long passe sur plusieurs lignes, jamais rogné).
                zoneDemande.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); btn.requestFocus()
            }
            return
        }
        if (demandeEnvoyee > 0) {
            val c = Ui.carte(this, Ui.CARTE)
            c.gravity = Gravity.CENTER
            c.addView(Ui.texte(this, getString(R.string.block_demande_envoyee), 15f, Ui.OR, gras = true, centre = true))
            c.addView(Ui.marge(this, Ui.texte(this,
                getString(R.string.block_ouvrira_seul, Vocabulaire.decideur(this)),
                13f, Ui.TEXTE_2, centre = true), haut = 6f))
            zoneDemande.addView(c)
            return
        }
        zoneDemande.addView(Ui.texte(this, getString(R.string.block_demander_du_temps), 15f, Ui.TEXTE, gras = true, centre = true))
        val r = Ui.rangeeBoutons(this).apply { gravity = Gravity.CENTER }
        var premier: View? = null
        listOf(15 to Tr.duree(15), 30 to Tr.duree(30), 60 to Tr.duree(60)).forEachIndexed { k, (min, lbl) ->
            val btn = Ui.boutonSecondaire(this, lbl) { demander(min) }
            btn.isFocusableInTouchMode = false
            r.addView(Ui.marge(this, btn, haut = 10f, gauche = if (k == 0) 0f else 10f))
            if (premier == null) premier = btn
        }
        zoneDemande.addView(r)
        premier?.requestFocus()
    }

    /** Déverrouillage à distance (1.68.0) : un parent approuve, l'appareil s'ouvre pour cette action. */
    private fun construireDeverrouillage() {
        if (!Config(this).inscrit || !Modules.actif(this, Modules.DEVERROUILLAGE)) return
        val pkg = intent.getStringExtra("pkg") ?: ""
        val raison = intent.getStringExtra("raison") ?: ""
        val action = when {
            raison.startsWith(RAISON_REGLAGE) -> "reglages"
            !Etat.verrouilleEffectif() && pkg.isNotEmpty() && Deverrouillage.connue("app:$pkg") -> "app:$pkg"
            else -> "parent"
        }
        val b = Ui.boutonSecondaire(this, getString(R.string.manifeste_demander_deverrouillage)) { DeverrouillageActivity.ouvrir(this, action) }
        b.isFocusableInTouchMode = false
        zoneDemande.addView(Ui.marge(this, b, bas = 14f))
    }

    private fun demander(minutes: Int) {
        Config(this).demandeTemps = minutes
        AgentService.demarrer(this)
        demandeEnvoyee = System.currentTimeMillis()
        construireDemande()
    }

    // VisionApp pose le fond uni du thème sur la fenêtre au démarrage de l'écran : le dégradé reprend sa place juste après.
    override fun onStart() {
        super.onStart()
        degrade?.let { window.setBackgroundDrawable(it) }
    }

    override fun onResume() {
        super.onResume()
        h.removeCallbacks(boucle)
        h.post(boucle)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(boucle)
    }

    private fun peindre() {
        val pkg = intent.getStringExtra("pkg") ?: ""
        val raison = intent.getStringExtra("raison") ?: ""
        val t = TextesBlocage.de(
            verrouille = Etat.verrouilleEffectif(),
            message = Etat.message,
            reglageProtege = raison.startsWith(RAISON_REGLAGE),
            appli = if (pkg.isEmpty()) "" else Usage.libelle(this, pkg),
            motif = if (pkg.isEmpty()) "" else Etat.raisonDe(pkg),
            codeMotif = if (pkg.isEmpty()) "" else Etat.codeRaisonDe(pkg),
            reouverture = Etat.prochainChangement.takeIf { it > System.currentTimeMillis() }?.let { Etat.quand(it) } ?: "",
        )
        titre.text = Ui.coupable(t.titre)
        pourquoi.text = t.pourquoi
        quand.text = t.quand
        ligneQuand.visibility = if (t.quand.isEmpty()) View.GONE else View.VISIBLE
        // Une demande restée sans réponse : au bout de dix minutes, on laisse redemander.
        if (demandeEnvoyee > 0 && System.currentTimeMillis() - demandeEnvoyee > 10 * 60_000L) {
            demandeEnvoyee = 0; construireDemande()
        }
    }

    /** L'écran reste tant que l'appareil est fermé, ou tant que l'appli visée reste au premier plan. */
    private fun devraitRester(): Boolean {
        if (Config(this).modeParent) return false
        // « Réglage protégé » : sans le module « protections », plus rien à garder fermé.
        if ((intent.getStringExtra("raison") ?: "").startsWith(RAISON_REGLAGE) && !Modules.actif(this, Modules.PROTECTIONS)) return false
        if (Etat.verrouilleEffectif()) return true
        val pkg = intent.getStringExtra("pkg") ?: return false
        if (Etat.doitBloquer(this, pkg) == null) return false
        // 1.82.2 (bug de Alex : le motif se fermait pendant la saisie) : la fenêtre de demande ouverte, le clavier
        // ou Vision elle-même au premier plan ne veulent pas dire que l'enfant a quitté l'appli.
        if (dialogueDemande?.isShowing == true) return true
        val dernier = Usage.dernierPaquet
        return dernier == pkg || dernier == packageName || Usage.estClavier(this, dernier)
    }

    private fun accueil() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Exception) {}
    }

    // On ne quitte pas l'écran avec Retour ; Accueil est géré par le système.
    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean =
        if (code == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(code, e)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* ignoré */ }

    companion object {
        /**
         * Marqueur interne (jamais affiché : TextesBlocage écrit le titre) d'un réglage protégé par la protection
         * des réglages. Valeur française historique gardée telle quelle : d'autres fichiers la passent aussi.
         */
        const val RAISON_REGLAGE = "Réglage protégé par Vision"

        @Volatile private var dernierAffichage = 0L

        fun afficher(ctx: Context, raison: String, pkg: String) {
            val maintenant = System.currentTimeMillis()
            if (maintenant - dernierAffichage < 700) return
            dernierAffichage = maintenant
            try {
                ctx.startActivity(Intent(ctx, BlockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("raison", raison)
                    putExtra("pkg", pkg)
                })
            } catch (_: Exception) {}
        }
    }
}

/**
 * Lot Accueil (point 4) : le calcul de la grille « Toujours accessibles » (logique pure, testée).
 */
object GrilleAcces {
    /**
     * Le nombre de colonnes pour [n] cases d'au moins [caseMin] px dans [dispo] px : autant que la place le permet
     * (au moins une, au plus [n]), puis réparties pour que les rangées soient équilibrées (6 cases sur 4 de large :
     * 3 + 3 plutôt que 4 + 2). 0 s'il n'y a rien à placer.
     */
    fun colonnes(n: Int, dispo: Int, caseMin: Int): Int {
        if (n <= 0) return 0
        val tiennent = (dispo / caseMin.coerceAtLeast(1)).coerceIn(1, n)
        val rangees = (n + tiennent - 1) / tiennent
        return (n + rangees - 1) / rangees
    }

    /** Le nombre de rangées pour [n] cases sur [colonnes] colonnes. */
    fun rangees(n: Int, colonnes: Int): Int = if (n <= 0 || colonnes <= 0) 0 else (n + colonnes - 1) / colonnes
}

/**
 * Une grille centrée de cases de même largeur, qui revient à la ligne selon la place. Une case n'est jamais plus
 * étroite que le plus long mot de son libellé (les noms passent à la ligne entre deux mots, jamais au milieu).
 */
private class GrilleCases(ctx: Context, private val caseMinDp: Float, private val caseMaxDp: Float) : ViewGroup(ctx) {
    private var colonnes = 1
    private var largeurCase = 0
    private val hauteurs = ArrayList<Int>()
    private val ecart = Ui.dp(ctx, 6f)

    private fun visibles(): List<View> = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }

    /** La largeur du plus long mot des textes de [v], avec les marges intérieures de [v] et du texte. */
    private fun motLePlusLong(v: View): Int {
        if (v is TextView) {
            val mot = v.text.split(Regex("\\s+")).maxOfOrNull { v.paint.measureText(it) } ?: 0f
            return kotlin.math.ceil(mot).toInt() + v.totalPaddingLeft + v.totalPaddingRight + 2
        }
        if (v is ViewGroup) return (0 until v.childCount).maxOfOrNull { motLePlusLong(v.getChildAt(it)) }?.let { it + v.paddingLeft + v.paddingRight } ?: 0
        return 0
    }

    override fun onMeasure(largeurSpec: Int, hauteurSpec: Int) {
        val cases = visibles()
        val mode = MeasureSpec.getMode(largeurSpec)
        val dispo = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else MeasureSpec.getSize(largeurSpec) - paddingLeft - paddingRight
        val min = maxOf(Ui.dp(context, caseMinDp), cases.maxOfOrNull { motLePlusLong(it) } ?: 0)
        colonnes = GrilleAcces.colonnes(cases.size, dispo, min).coerceAtLeast(1)
        largeurCase = minOf(dispo / colonnes, maxOf(min, Ui.dp(context, caseMaxDp))).coerceAtLeast(0)
        hauteurs.clear()
        val libre = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (rang in cases.chunked(colonnes)) {
            var h = 0
            for (c in rang) { c.measure(MeasureSpec.makeMeasureSpec(largeurCase, MeasureSpec.EXACTLY), libre); h = maxOf(h, c.measuredHeight) }
            hauteurs.add(h)
        }
        val hauteur = hauteurs.sum() + ecart * (hauteurs.size - 1).coerceAtLeast(0) + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(colonnes * largeurCase + paddingLeft + paddingRight, largeurSpec), resolveSize(hauteur, hauteurSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val interieur = r - l - paddingLeft - paddingRight
        var y = paddingTop
        visibles().chunked(colonnes).forEachIndexed { k, rang ->
            var x = paddingLeft + ((interieur - rang.size * largeurCase) / 2).coerceAtLeast(0)
            for (c in rang) { c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight); x += largeurCase }
            y += (hauteurs.getOrNull(k) ?: 0) + ecart
        }
    }
}

/**
 * Un cadre qui défile dans une zone sûre (sa marge intérieure) : quand le focus descend sur un bouton, le défilement
 * l'amène entier à l'intérieur de la marge, et non contre le bord de l'écran où la marge le couperait.
 */
private class DefileZoneSure(ctx: android.content.Context) : android.widget.ScrollView(ctx) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int {
        val elargi = android.graphics.Rect(rect)
        elargi.top -= paddingTop; elargi.bottom += paddingBottom
        return super.computeScrollDeltaToGetChildRectOnScreen(elargi)
    }
}
