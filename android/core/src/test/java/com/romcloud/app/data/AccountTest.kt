package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountTest {

    @Test
    fun `code lu dans le QR code de la TV, ou saisi a la main`() {
        assertEquals("AB23CD45", Account.codeFromQr("romcloud://pair?code=AB23CD45"))
        assertEquals("AB23CD45", Account.codeFromQr("ab23 cd45"))
        assertEquals("AB23CD45", Account.codeFromQr("AB23-CD45"))
        // Autre QR code (adresse web…) : refusé.
        assertNull(Account.codeFromQr("https://example.com/une/page/quelconque"))
        assertNull(Account.codeFromQr("abc"))
    }

    @Test
    fun `qr code de la demande de connexion`() {
        assertEquals("romcloud://pair?code=AB23CD45", PairRequest("AB23CD45", "secret", "2026-01-01T00:00:00Z").qrContent)
    }
}
