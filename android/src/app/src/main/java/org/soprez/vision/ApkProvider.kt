// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Donne l'APK téléchargé à l'installateur système, et rien d'autre.
 *
 * Android exige une URI `content://` pour installer un paquet. Ce fournisseur
 * ne sert que les fichiers du dossier `maj/` du cache de Vision : impossible
 * d'en lire un autre à travers lui.
 */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val nom = uri.lastPathSegment ?: throw FileNotFoundException()
        if (nom.contains('/') || nom.contains("..") || !nom.endsWith(".apk")) throw FileNotFoundException()
        val f = File(MiseAJour.dossier(context!!), nom)
        if (!f.isFile) throw FileNotFoundException(nom)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    companion object {
        const val AUTORITE = "org.soprez.vision.apk"
        fun uri(nom: String): Uri = Uri.parse("content://$AUTORITE/$nom")
    }
}
