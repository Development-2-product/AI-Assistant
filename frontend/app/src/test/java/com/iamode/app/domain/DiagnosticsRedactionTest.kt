package com.iamode.app.domain

import com.iamode.app.core.diagnostics.redactDiagnosticDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsRedactionTest {
    @Test fun `diagnostics keep useful failure codes while redacting identifiers`() {
        val result = redactDiagnosticDetail("alex.smith@example.com: HTTP 503; call +91 98765 43210")
        assertEquals("a***@example.com: HTTP 503; call [redacted number]", result)
        assertFalse(result.contains("alex.smith") || result.contains("98765"))
    }
}
