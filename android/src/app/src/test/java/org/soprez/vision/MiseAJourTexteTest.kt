// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.67.0 : jamais de code d'erreur d'Android (en anglais) dans le texte montré à l'utilisateur. */
class MiseAJourTexteTest {
    @Test fun aucunCodeTechnique() {
        for (statut in listOf(PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED,
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_STORAGE, 42)) {
            val t = MiseAJour.texteResultat(statut)
            assertFalse(t, Regex("INSTALL_|FAILED|file://|Exception|[0-9][0-9]").containsMatchIn(t))
            assertTrue(t, t.endsWith("."))
        }
    }

    @Test fun echecVerificationProposeDInstaller() {
        assertTrue(MiseAJour.texteResultat(PackageInstaller.STATUS_FAILURE_ABORTED).contains("Installer"))
    }
}
