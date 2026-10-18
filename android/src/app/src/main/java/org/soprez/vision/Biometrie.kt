// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Empreinte ou visage à la place du code parent (Vision 1.68.0) — seulement sur l'appareil d'un
 * parent. Android ne sait pas QUI pose son doigt : sur le téléphone d'un enfant, c'est son
 * empreinte. Donc Home Assistant décide (flag « biometrie » au relevé, docs/deverrouillage.md §6),
 * et l'appli éteint la biométrie si le flag manque, vaut faux, ou date de plus de 24 h.
 *
 * Liée au matériel : la biométrie doit débloquer une clé du Keystore (BIOMETRIC_STRONG) ; un
 * « succès » forgé par une autre appli ne déchiffre rien. La clé est invalidée dès que la
 * biométrie de l'appareil change (une empreinte ajoutée) : il faut alors le code parent une fois
 * pour la réarmer. Le code parent reste toujours proposé.
 */
object Biometrie {
    private const val ALIAS = "vision_parent_biometrie"

    /** Lot 6 (APP-37) : ce qu'on fait quand l'invite se ferme sur une erreur. */
    enum class Suite { RIEN, CODE, ANNULER }

    /**
     * Logique pure (essayée en JUnit) : « Code de gestion » (bouton négatif) est déjà traité par son bouton ; trop
     * d'essais ou capteur indisponible → le code ; tout le reste (Retour, toucher à côté, délai, annulation) → annuler.
     * Codes de BiometricPrompt : 1 matériel indisponible, 7 et 9 trop d'essais, 11 aucune empreinte, 12 pas de
     * capteur, 13 bouton négatif ; 5 annulé, 10 annulé par l'utilisateur, 3 délai.
     */
    fun suiteErreur(erreur: Int): Suite = when (erreur) {
        13 -> Suite.RIEN
        1, 7, 9, 11, 12 -> Suite.CODE
        else -> Suite.ANNULER
    }
    private const val PREFS = "biometrie"

    /** Utilisable ici et maintenant : permise par HA, matériel « fort », Android 11 et plus, armée par le code. */
    fun disponible(ctx: Context): Boolean = try {
        etat(ctx) == Etat.ACTIVEE
    } catch (_: Exception) { false } // jamais au prix de l'écran : le code parent reste

    /** Après un code parent juste, sur un appareil où HA permet la biométrie : on (ré)arme la clé. */
    fun armerApresCode(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30 || !Deverrouillage.biometriePermise(ctx)) return
        try {
            creerCle()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("armee", true).apply()
        } catch (_: Exception) {}
    }

    /** Pourquoi la biométrie est (ou n'est pas) proposée ici : pour le dire en clair dans les réglages. */
    enum class Etat { ANDROID_TROP_ANCIEN, MATERIEL_ABSENT, NON_AUTORISEE_PAR_HA, A_ACTIVER, ACTIVEE }

    fun etat(ctx: Context): Etat {
        if (Build.VERSION.SDK_INT < 30) return Etat.ANDROID_TROP_ANCIEN
        val bm = ctx.getSystemService(BiometricManager::class.java)
        val possible = try { bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS }
                       catch (_: Exception) { false } // 1.55.1 : sans la permission, Android levait une exception ici
        if (!possible) return Etat.MATERIEL_ABSENT
        if (!Deverrouillage.biometriePermise(ctx)) return Etat.NON_AUTORISEE_PAR_HA
        return if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("armee", false)) Etat.ACTIVEE else Etat.A_ACTIVER
    }

    /** Le parent coupe la biométrie sur cet appareil (le code parent reste). */
    fun couper(ctx: Context) = desarmer(ctx)

    private fun desarmer(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("armee", false).apply()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
    }

    private fun creerCle(): SecretKey {
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        g.init(spec.build())
        return g.generateKey()
    }

    /**
     * Demande l'empreinte ou le visage. `ok` n'est appelé que si la clé du Keystore a bien été
     * débloquée (chiffrement réussi). `code` : l'utilisateur préfère le code parent. `annule` (lot 6, APP-37) : l'invite
     * a été fermée (Retour, toucher à côté, délai) : l'écran appelant se referme proprement, au lieu de rester transparent.
     */
    fun demander(act: Activity, raison: String, ok: () -> Unit, code: () -> Unit, annule: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 30) { code(); return }
        val chiffre = try {
            val cle = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey) ?: run { desarmer(act); code(); return }
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, cle) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // La biométrie de l'appareil a changé : on la coupe, le code parent la réarmera.
            desarmer(act)
            // 1.71.0 : une boîte plutôt qu'un toast, qui serait coupé en grands caractères.
            Ui.dialogue(act).setTitle(act.getString(R.string.biometrie_reactiver_titre))
                .setMessage(act.getString(R.string.biometrie_reactiver_texte))
                .setCancelable(false).setPositiveButton(act.getString(R.string.biometrie_entrer_code)) { _, _ -> code() }.show()
            return
        } catch (_: Exception) { code(); return }
        val invite = BiometricPrompt.Builder(act)
            .setTitle("Vision")
            .setSubtitle(raison)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(act.getString(R.string.biometrie_code_gestion), act.mainExecutor) { _, _ -> code() }
            .build()
        invite.authenticate(BiometricPrompt.CryptoObject(chiffre), CancellationSignal(), act.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(erreur: Int, message: CharSequence?) {
                    when (Biometrie.suiteErreur(erreur)) {
                        Suite.RIEN -> {}
                        Suite.CODE -> code()
                        Suite.ANNULER -> annule()
                    }
                }

                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    val c = r.cryptoObject?.cipher
                    val vrai = try { c != null && c.doFinal(ByteArray(16)).isNotEmpty() } catch (_: Exception) { false }
                    if (vrai) ok() else code()
                }
            })
    }
}
