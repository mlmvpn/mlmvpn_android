package com.mlmvpn.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CarrierTest {
    @Test fun `iranian operator codes map to the names the screens show`() {
        assertEquals(Carrier.MCI, Carrier.codeOf("43211"))
        assertEquals(Carrier.MTN, Carrier.codeOf("43235"))
        assertEquals(Carrier.RIGHTEL, Carrier.codeOf("43220"))
        assertEquals(Carrier.OTHER, Carrier.codeOf("43270"))
        assertEquals(Carrier.OTHER, Carrier.codeOf("310260"))
    }
}
