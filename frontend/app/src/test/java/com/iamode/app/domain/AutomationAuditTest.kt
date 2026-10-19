package com.iamode.app.domain

import com.iamode.app.domain.model.Alert
import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.policy.AutomationAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationAuditTest {
    private fun alert(kind: AlertKind, body: String = "private draft text") =
        Alert(1, kind, "Activity title", body, "conversation", 0)

    @Test fun `approval is actionable and does not expose stored alert body`() {
        val item = AutomationAudit.present(alert(AlertKind.APPROVAL))
        assertTrue(item.needsAttention)
        assertEquals("Review", item.actionLabel)
        assertFalse(item.summary.contains("private draft text"))
    }

    @Test fun `contact policy block is actionable`() {
        val item = AutomationAudit.present(alert(AlertKind.POLICY))
        assertTrue(item.needsAttention)
        assertEquals("Review", item.actionLabel)
    }

    @Test fun `completed automation is shown outside attention queue`() {
        val item = AutomationAudit.present(alert(AlertKind.AUTOMATION))
        assertFalse(item.needsAttention)
        assertEquals("View", item.actionLabel)
    }
}
