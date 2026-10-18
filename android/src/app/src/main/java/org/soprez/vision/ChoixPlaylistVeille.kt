// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Le choix de la playlist de la veille (l'auteur, 15/10) : jamais une impasse.
 *
 * - En haut, une recherche : les playlists de tous les fournisseurs connectés à Music Assistant (YouTube Music…).
 * - Puis « Désactivée » (le défaut), les favorites de Music Assistant en tête, et toutes les autres playlists de sa
 *   bibliothèque en dessous (ordre donné par Home Assistant).
 * - Un cœur sur chaque playlist ([CoeurFavori], le même que l'appli Musique) : il ajoute ou retire le favori dans
 *   Music Assistant, sans ouvrir son interface (Décideur ou télé).
 * - Liste vide (bibliothèque sans playlist) : une phrase claire et une action en un appui — chercher une playlist.
 * À la télécommande : haut / bas d'une ligne à l'autre, droite atteint le cœur, OK choisit.
 */
object ChoixPlaylistVeille {

    private fun clavier(v: View) {
        val imm = v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        v.post { imm.showSoftInput(v, 0) }
    }

    /** La zone de texte de la ligne cochée (« ✓ … »), sinon la première ligne. */
    private fun ligneCochee(liste: LinearLayout): View? {
        var premiere: View? = null
        for (i in 0 until liste.childCount) {
            val l = liste.getChildAt(i) as? LinearLayout ?: continue
            val texte = l.getChildAt(0) as? LinearLayout ?: continue
            if (premiere == null) premiere = texte
            if ((texte.getChildAt(0) as? TextView)?.text?.startsWith("✓") == true) return texte
        }
        return premiere
    }

    fun ouvrir(act: Activity, apres: () -> Unit) {
        val th = Ui.themeActif(act)
        val dp = { v: Float -> Ui.dp(act, v) }
        val corps = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(4f), dp(20f), dp(4f)) }
        val recherche = EditText(act).also { ClavierTele.brancher(it) }.apply {
            hint = act.getString(R.string.choixplaylist_chercher_indice); inputType = android.text.InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(th.encre); setHintTextColor(th.encre3); textSize = 16f
        }
        corps.addView(recherche, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val etat = Ui.sansCesure(TextView(act)).apply { textSize = 15f; setTextColor(th.encre2); setPadding(0, dp(8f), 0, dp(4f)); visibility = View.GONE }
        corps.addView(etat)
        val liste = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        corps.addView(ScrollView(act).apply { addView(liste) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        lateinit var boite: AlertDialog

        fun ligne(titre: String, sousTitre: String, choisie: Boolean, coeur: View?, choisir: () -> Unit): View {
            val l = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52f) }
            val texte = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; setPadding(dp(10f), dp(8f), dp(8f), dp(8f))
                contentDescription = listOf(titre, sousTitre).filter { it.isNotEmpty() }.joinToString(", ")
                Ui.anneauFocus(this, th, 12f, agrandir = false)
                setOnClickListener { choisir() }
            }
            texte.addView(Ui.sansCesure(TextView(act)).apply { text = (if (choisie) "✓  " else "") + titre; textSize = 17f; setTextColor(if (choisie) th.or else th.encre) })
            if (sousTitre.isNotEmpty()) texte.addView(Ui.sansCesure(TextView(act)).apply { text = sousTitre; textSize = 13f; setTextColor(th.encre3) })
            l.addView(texte, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (coeur != null) l.addView(coeur, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            return l
        }

        fun montrer(l: List<VeilleAudio.Playlist>, vide: String?, actionVide: (() -> Unit)?) {
            liste.removeAllViews()
            val courante = VeilleAudio.choisie(act)
            liste.addView(ligne(act.getString(R.string.veilleaudio_desactivee), "", courante == null, null) { boite.dismiss(); VeilleAudio.choisir(act, null); apres() })
            var favoritesFinies = false
            for ((i, p) in l.withIndex()) {
                // Un intertitre discret entre les favorites et le reste de la bibliothèque.
                if (!p.favori && !favoritesFinies && i > 0 && l[i - 1].favori) {
                    favoritesFinies = true
                    liste.addView(Ui.sansCesure(TextView(act)).apply { text = act.getString(R.string.choixplaylist_autres); textSize = 13f; setTextColor(th.encre3); setPadding(dp(10f), dp(12f), 0, dp(2f)) })
                }
                val coeur = CoeurFavori(act, th, p.uri, p.favori, surErreur = { m -> Ui.toast(act, m) })
                liste.addView(ligne(p.nom, p.source, p.uri == courante?.uri, coeur) { boite.dismiss(); VeilleAudio.choisir(act, p); apres() })
            }
            etat.visibility = if (vide != null) View.VISIBLE else View.GONE
            etat.text = vide ?: ""
            if (vide != null && actionVide != null) liste.addView(Ui.boutonPrimaire(act, act.getString(R.string.choixplaylist_chercher_action)) { actionVide() })
        }

        fun chercher() {
            val t = recherche.text.toString().trim()
            if (t.length < 2) { recherche.requestFocus(); return }
            etat.visibility = View.VISIBLE; etat.text = act.getString(R.string.choixplaylist_recherche_en_cours)
            Thread {
                val r = try { Result.success(VeilleAudio.recherche(act, t)) } catch (x: Net.Echec) { Result.failure(Exception(Party.messageErreur(x.code, x.corps))) } catch (_: Exception) { Result.failure(Exception(Party.messageErreur(0, null))) }
                act.runOnUiThread {
                    if (act.isFinishing || !boite.isShowing) return@runOnUiThread
                    r.onSuccess { l -> montrer(l, if (l.isEmpty()) act.getString(R.string.choixplaylist_rien_trouve, t) else null, null) }
                    r.onFailure { e -> etat.text = act.getString(R.string.veilleaudio_erreur, e.message ?: "") }
                }
            }.apply { isDaemon = true }.start()
        }
        recherche.setOnClickListener { clavier(it) }
        recherche.setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { chercher(); true } else false }

        boite = Ui.dialogue(act).setTitle(act.getString(R.string.veilleaudio_titre_choix)).setView(corps)
            .setPositiveButton(act.getString(R.string.choixplaylist_chercher), null)
            .setNegativeButton(act.getString(R.string.commun_lot2_annuler), null).create()
        boite.setOnShowListener { boite.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { chercher() } }
        // Le clavier reste caché à l'ouverture (sur une télé, il couvrait la liste) : il vient quand on choisit la recherche.
        boite.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        boite.show()
        etat.visibility = View.VISIBLE; etat.text = act.getString(R.string.choixplaylist_chargement)
        Thread {
            val r = try { Result.success(VeilleAudio.playlists(act)) } catch (x: Net.Echec) { Result.failure(Exception(Party.messageErreur(x.code, x.corps))) } catch (_: Exception) { Result.failure(Exception(Party.messageErreur(0, null))) }
            act.runOnUiThread {
                if (act.isFinishing || !boite.isShowing) return@runOnUiThread
                r.onSuccess { l ->
                    // Bibliothèque sans aucune playlist : la recherche tout de suite, avec une phrase claire.
                    if (l.isEmpty()) montrer(l, act.getString(R.string.veilleaudio_aucune)) { recherche.requestFocus(); clavier(recherche) }
                    else montrer(l, null, null)
                    // Le focus sur la ligne cochée (la playlist en cours, ou « Désactivée ») ; liste vide : la recherche.
                    if (l.isEmpty()) recherche.requestFocus() else ligneCochee(liste)?.requestFocus()
                }
                r.onFailure { e -> etat.text = act.getString(R.string.veilleaudio_erreur, e.message ?: "") }
            }
        }.apply { isDaemon = true }.start()
    }
}
