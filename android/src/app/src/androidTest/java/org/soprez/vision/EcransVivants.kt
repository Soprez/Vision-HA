// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Intégration du 15/10 : lancer un écran (souvent avec CLEAR_TASK) pendant que celui de l'essai précédent se détruit
 * faisait planter Android dans sa propre transition sur la télé (« Activity client record must not be null …
 * TopResumedActivityChangeItem », pile sans aucune ligne de Vision). On compte les écrans de l'appli encore en vie
 * pour tout le processus d'essai, et on attend qu'il n'y en ait plus avant de lancer.
 */
object EcransVivants {
    private val vivants = AtomicInteger()
    @Volatile private var pose = false
    private val suivi = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) { vivants.incrementAndGet() }
        override fun onActivityDestroyed(a: Activity) { vivants.decrementAndGet() }
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    }

    /** À appeler au début de chaque essai (setUp) : le suivi n'est posé qu'une fois. */
    fun suivre(instr: Instrumentation) {
        if (pose) return
        synchronized(this) {
            if (!pose) { (instr.targetContext.applicationContext as Application).registerActivityLifecycleCallbacks(suivi); pose = true }
        }
    }

    /** Attend qu'aucun écran de l'appli ne soit encore en vie (5 s au plus), puis lance [intent]. */
    fun lancer(instr: Instrumentation, intent: Intent): Activity {
        suivre(instr)
        instr.waitForIdleSync()
        val fin = SystemClock.elapsedRealtime() + 5000
        while (vivants.get() > 0 && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(50)
        instr.waitForIdleSync()
        return instr.startActivitySync(intent)
    }
}
