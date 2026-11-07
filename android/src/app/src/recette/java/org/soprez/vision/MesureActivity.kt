// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * COPIE DE RECETTE SEULEMENT (prototype D0 de la découverte appli → sites) : lance le VPN DNS de la copie et écrit, toutes les 5 s,
 * dans `files/mesure.json`, comment les requêtes DNS ont été attribuées (comptes par résultat et par paquet, domaines demandés par
 * 3 applis ou plus). Aucun envoi, aucun Home Assistant. `--es action stop` arrête le VPN et fige le fichier.
 */
class MesureActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val debut = System.currentTimeMillis()
    private lateinit var vue: TextView
    private val boucle = object : Runnable { override fun run() { ecrire(applicationContext, debut); main.postDelayed(this, 5_000) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        vue = TextView(this).apply { textSize = 14f; setPadding(32, 64, 32, 32) }
        setContentView(vue)
        if (intent.getStringExtra("action") == "stop") { DnsVpnService.arreter(this); ecrire(applicationContext, debut); StatsAttribution.ecouteur = null; vue.text = "VPN de recette arrêté"; return }
        val i = VpnService.prepare(this)
        if (i != null) @Suppress("DEPRECATION") startActivityForResult(i, 7) else demarrer()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) demarrer() else vue.text = "VPN refusé"
    }

    private fun demarrer() {
        StatsAttribution.vider(); Origines.vider()
        val ctx = applicationContext
        val t0 = System.currentTimeMillis()
        StatsAttribution.ecouteur = { ecrire(ctx, t0) }   // écrit depuis le service : ne dépend pas de la vie de cet écran
        DnsVpnService.demarrer(this)
        vue.text = "Mesure de l'attribution en cours (VPN de recette)…"
        main.post(boucle)
    }

    private fun ecrire(ctx: android.content.Context, t0: Long) {
        val o = Origines.instantane()
        val j = JSONObject()
            .put("duree_s", (System.currentTimeMillis() - t0) / 1000)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("mode", AttributionVpn.mode())
            .put("vpn_actif", DnsVpnService.actif)
            .put("resultats", JSONObject(StatsAttribution.instantane() as Map<*, *>))
            .put("par_paquet", JSONObject(o.first as Map<*, *>))
            .put("domaines_par_nb_applis", JSONObject(o.second as Map<*, *>))
        File(ctx.filesDir, "mesure.json").writeText(j.toString(1))
    }

    override fun onDestroy() { main.removeCallbacks(boucle); super.onDestroy() }
}
