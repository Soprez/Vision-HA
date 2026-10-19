// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 17-10 : un Home Assistant de recette, sur 127.0.0.1, pour bâtir les écrans qui le lisent (lecteur de musique…) sans
 * émulateur. Chaque chemin rend la réponse posée dans [reponses] (sinon `{}`) ; les corps reçus sont gardés dans [recus].
 * Un serveur HTTP minimal (une requête POST par connexion), le JDK de l'appli de test n'ayant pas com.sun.net.httpserver.
 */
class FauxHa : AutoCloseable {
    val reponses = HashMap<String, () -> JSONObject>()
    val recus = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    private val prise = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val adresse: String get() = "http://127.0.0.1:${prise.localPort}"

    init {
        Thread {
            while (!prise.isClosed) {
                val s = try { prise.accept() } catch (_: Exception) { break }
                Thread { try { s.use { servir(it) } } catch (_: Exception) {} }.also { it.isDaemon = true }.start()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun servir(s: java.net.Socket) {
        val entree = s.getInputStream().buffered()
        fun ligne(): String {
            val b = StringBuilder()
            while (true) { val c = entree.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }
            return b.toString()
        }
        val chemin = ligne().split(' ').getOrElse(1) { "/" }.substringBefore('?')
        var longueur = 0
        while (true) { val l = ligne(); if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val octets = ByteArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(octets, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = try { JSONObject(String(octets, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
        recus += chemin to corps
        val r = (reponses[chemin]?.invoke() ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
        val sortie = s.getOutputStream()
        sortie.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${r.size}\r\nConnection: close\r\n\r\n".toByteArray())
        sortie.write(r); sortie.flush()
    }

    override fun close() = prise.close()
}
