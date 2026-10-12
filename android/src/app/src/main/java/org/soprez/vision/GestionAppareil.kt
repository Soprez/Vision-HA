// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parité avec l'agent Windows et Home Assistant (Vision 1.74.0) : ce que l'espace gestion de l'appli
 * ne savait pas encore faire sur un appareil. Plages du planning (créer, activer, supprimer), limites
 * par appli, à qui est l'appareil (principal, partage, toute la maison), durée max de pause, logiciels
 * d'un PC. Chaque geste part vers `/api/pc_parental/parent` ; Home Assistant vérifie le rôle.
 */
object GestionAppareil {
    /** [envoyer] reçoit le corps du geste (action, pc et ses champs) ; l'écran se recharge ensuite. */
    fun menu(act: Activity, a: JSONObject, personnes: JSONArray, envoyer: (JSONObject) -> Unit) {
        val id = a.optString("id")
        val nom = a.optString("nom")
        // Seuls les gestes des modules actifs dans Home Assistant (Modules.gestesGestion).
        val gestes = Modules.gestesGestion(Modules.actifs(act), a.optBoolean("android"))
        val choix = mutableListOf<Pair<String, () -> Unit>>()
        if ("planning" in gestes) choix += act.getString(R.string.gestion_ajouter_plage) to { nouvellePlage(act, id, envoyer) }
        if ("limites" in gestes) choix += act.getString(R.string.gestion_limiter_appli) to { limites(act, a, envoyer) }
        choix += act.getString(R.string.gestion_a_qui) to { proprietaire(act, a, personnes, envoyer) }
        if ("pause" in gestes) choix += act.getString(R.string.gestion_pause_max) to { pauseMax(act, id, envoyer) }
        val logiciels = a.optJSONArray("logiciels")
        if (!a.optBoolean("android") && logiciels != null && logiciels.length() > 0) choix += act.getString(R.string.gestion_logiciels_menu) to { logiciels(act, id, logiciels, envoyer) }
        dialogue(act).setTitle(nom)
            .setItems(choix.map { it.first }.toTypedArray()) { _, k -> choix[k].second() }
            .setNegativeButton(R.string.commun_fermer, null).show()
    }

    /** Les boîtes au thème actif de Vision (fond, encres, accent). */
    private fun dialogue(act: Activity) = Ui.dialogue(act)

    private fun corps(action: String, pc: String) = JSONObject().put("action", action).put("pc", pc)

    private fun champ(act: Activity, indice: String, type: Int = InputType.TYPE_CLASS_TEXT, valeur: String = "") = EditText(act).apply {
        hint = indice; inputType = type; setText(valeur); setTextColor(Ui.TEXTE); setHintTextColor(Ui.TEXTE_2)
    }

    private fun colonne(act: Activity) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; val p = Ui.dp(act, 20f); setPadding(p, p / 2, p, 0)
    }

    // ------------------------------------------------------------------ Planning

    fun nouvellePlage(act: Activity, pc: String, envoyer: (JSONObject) -> Unit) {
        val c = colonne(act)
        val nom = champ(act, act.getString(R.string.gestion_nom_plage))
        val debut = champ(act, act.getString(R.string.gestion_debut), InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        val fin = champ(act, act.getString(R.string.gestion_fin), InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        c.addView(nom); c.addView(debut); c.addView(fin)
        // Lundi d'abord (l'ordre des jours envoyés à Home Assistant), noms dans la langue de l'appareil.
        val semaine = java.text.DateFormatSymbols.getInstance().weekdays
        val noms = listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY,
            java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY).map { semaine[it].replaceFirstChar { c -> c.titlecase() } }
        val cases = noms.map { j -> Ui.caseACocher(act, j, coche = true) }
        val jours = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        cases.forEach { jours.addView(it) }
        c.addView(jours)
        c.addView(Ui.texte(act, act.getString(R.string.gestion_plage_aide), 12f, Ui.TEXTE_3))
        dialogue(act).setTitle(R.string.gestion_nouvelle_plage).setView(android.widget.ScrollView(act).apply { addView(c) })
            .setPositiveButton(R.string.gestion_ajouter) { _, _ ->
                envoyer(corps("plage", pc).put("op", "creer").put("nom", nom.text.toString().trim().ifEmpty { act.getString(R.string.gestion_plage_defaut) })
                    .put("debut", debut.text.toString()).put("fin", fin.text.toString())
                    .put("jours", JSONArray(cases.map { it.isChecked })))
            }.setNegativeButton(R.string.commun_annuler, null).show()
    }

    fun plage(act: Activity, pc: String, p: JSONObject, envoyer: (JSONObject) -> Unit) {
        val active = p.optBoolean("active", true)
        val ident = p.optInt("id")
        dialogue(act).setTitle("${p.optString("nom").ifEmpty { act.getString(R.string.gestion_plage_defaut) }} ${p.optString("debut")} → ${p.optString("fin")}")
            .setItems(arrayOf(act.getString(if (active) R.string.gestion_desactiver else R.string.gestion_reactiver), act.getString(R.string.gestion_supprimer))) { _, k ->
                if (k == 0) envoyer(corps("plage", pc).put("op", "modifier").put("regle", ident).put("active", !active))
                else dialogue(act).setMessage(R.string.gestion_supprimer_plage)
                    .setPositiveButton(R.string.gestion_supprimer) { _, _ -> envoyer(corps("plage", pc).put("op", "supprimer").put("regle", ident)) }
                    .setNegativeButton(R.string.commun_annuler, null).show()
            }.setNegativeButton(R.string.commun_fermer, null).show()
    }

    // ------------------------------------------------------------------ Limites

    private fun limites(act: Activity, a: JSONObject, envoyer: (JSONObject) -> Unit) {
        val pc = a.optString("id")
        val existantes = a.optJSONArray("limites") ?: JSONArray()
        val lignes = (0 until existantes.length()).mapNotNull { existantes.optJSONObject(it) }
        val items = lignes.map { act.getString(R.string.gestion_limite_ligne, it.optString("libelle"), it.optInt("passe"), it.optInt("minutes")) } +
            act.getString(R.string.gestion_nouvelle_limite_menu)
        dialogue(act).setTitle(R.string.gestion_limites_titre)
            .setItems(items.toTypedArray()) { _, k ->
                if (k < lignes.size) envoyer(corps("limite", pc).put("nom", lignes[k].optString("nom")).put("minutes", 0))
                else {
                    val c = colonne(act)
                    val focus = a.optString("focus")
                    val nom = champ(act, act.getString(if (a.optBoolean("android")) R.string.gestion_indice_appli else R.string.gestion_indice_logiciel), valeur = focus)
                    val minutes = champ(act, act.getString(R.string.gestion_minutes_par_jour), InputType.TYPE_CLASS_NUMBER, "60")
                    c.addView(nom); c.addView(minutes)
                    c.addView(Ui.texte(act, act.getString(R.string.gestion_cumule), 12f, Ui.TEXTE_3))
                    dialogue(act).setTitle(R.string.gestion_nouvelle_limite).setView(c)
                        .setPositiveButton(R.string.gestion_limiter) { _, _ ->
                            envoyer(corps("limite", pc).put("nom", nom.text.toString().trim()).put("minutes", minutes.text.toString().toIntOrNull() ?: 0))
                        }.setNegativeButton(R.string.commun_annuler, null).show()
                }
            }.setNegativeButton(R.string.commun_fermer, null).show()
    }

    // ------------------------------------------------------------------ À qui

    private fun proprietaire(act: Activity, a: JSONObject, personnes: JSONArray, envoyer: (JSONObject) -> Unit) {
        val pc = a.optString("id")
        val liste = (0 until personnes.length()).mapNotNull { personnes.optJSONObject(it) }
        val items = liste.map { act.getString(R.string.gestion_prenom_menu, it.optString("prenom")) } +
            act.getString(if (a.optBoolean("partage_tous")) R.string.gestion_plus_tout_le_monde else R.string.gestion_tout_le_monde)
        dialogue(act).setTitle(act.getString(R.string.gestion_a_qui_titre, a.optString("nom")))
            .setItems(items.toTypedArray()) { _, k ->
                if (k == liste.size) { envoyer(corps("partage", pc).put("tous", !a.optBoolean("partage_tous")).put("personnes", JSONArray())); return@setItems }
                val p = liste[k]
                val modes = listOf("principal" to act.getString(R.string.gestion_mode_principal), "partage" to act.getString(R.string.gestion_mode_partage),
                    "retirer" to act.getString(R.string.gestion_mode_retirer))
                dialogue(act).setTitle(p.optString("prenom"))
                    .setItems(modes.map { it.second }.toTypedArray()) { _, m ->
                        envoyer(corps("attribuer", pc).put("personne", p.optString("entite")).put("mode", modes[m].first))
                    }.setNegativeButton(R.string.commun_annuler, null).show()
            }.setNegativeButton(R.string.commun_fermer, null).show()
    }

    // ------------------------------------------------------------------ Pause, logiciels

    private fun pauseMax(act: Activity, pc: String, envoyer: (JSONObject) -> Unit) {
        val choix = listOf(15, 30, 60, 120, 240)
        dialogue(act).setTitle(R.string.gestion_pause_max_titre)
            .setItems(choix.map { Tr.duree(it) }.toTypedArray()) { _, k ->
                envoyer(corps("pause_max", pc).put("minutes", choix[k]))
            }.setNegativeButton(R.string.commun_annuler, null).show()
    }

    private fun logiciels(act: Activity, pc: String, liste: JSONArray, envoyer: (JSONObject) -> Unit) {
        val fiches = (0 until liste.length()).mapNotNull { liste.optJSONObject(it) }.sortedBy { it.optString("nom").lowercase() }
        dialogue(act).setTitle(R.string.gestion_logiciels_titre)
            .setItems(fiches.map { f -> f.optString("nom") + (f.optString("version").let { if (it.isEmpty()) "" else " $it" }) }.toTypedArray()) { _, k ->
                val f = fiches[k]
                dialogue(act).setTitle(f.optString("nom"))
                    .setMessage(R.string.gestion_desinstaller_question)
                    .setPositiveButton(R.string.gestion_desinstaller) { _, _ ->
                        envoyer(corps("logiciel", pc).put("cle", f.optString("cle")).put("geste", "desinstaller").put("confirmation", true))
                    }
                    .setNeutralButton(R.string.gestion_annuler_demande) { _, _ -> envoyer(corps("logiciel", pc).put("cle", f.optString("cle")).put("geste", "annuler")) }
                    .setNegativeButton(R.string.commun_fermer, null).show()
            }.setNegativeButton(R.string.commun_fermer, null).show()
    }
}
