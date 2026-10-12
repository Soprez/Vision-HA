// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.0.0 (notifications v2) : le son choisi sur l'appareil passe avant celui du type ; « silencieux » gagne toujours. */
class MessagesV2UnitTest {
    @Test fun leReglageDeLAppareilDecide() {
        assertEquals(MessagesV2.CANAL_SILENCIEUX, MessagesV2.canal(MessagesV2.SON_SILENCIEUX, "Notification.Looping.Alarm", true))
        assertEquals(MessagesV2.CANAL_IMPORTANT, MessagesV2.canal(MessagesV2.SON_DISCRET, "Notification.Looping.Alarm", true))
        assertEquals(MessagesV2.CANAL, MessagesV2.canal(MessagesV2.SON_DISCRET, "", false))
    }

    @Test fun selonLeType() {
        assertEquals(MessagesV2.CANAL_INSISTANT, MessagesV2.canal(MessagesV2.SON_SELON, "Notification.Looping.Alarm", false))
        assertEquals(MessagesV2.CANAL_SILENCIEUX, MessagesV2.canal(MessagesV2.SON_SELON, "", false))
        assertEquals(MessagesV2.CANAL_IMPORTANT, MessagesV2.canal(MessagesV2.SON_SELON, "Notification.Reminder", true))
        assertEquals(MessagesV2.CANAL, MessagesV2.canal(MessagesV2.SON_SELON, "Notification.Default", false))
    }
}
