package com.mlmvpn.scanner.engines.cfnet

import org.junit.Assert.*
import org.junit.Test

class CfValidationTest {
    @Test fun revokedTokenIsNotAWorkingAccount() {
        assertFalse(CfValidation.account(200, "{\"success\":true,\"result\":{\"status\":\"disabled\"}}", true))
        assertTrue(CfValidation.account(200, "{\"success\":true,\"result\":{\"status\":\"active\"}}", true))
        assertFalse(CfValidation.account(403, "{\"success\":true}", false))
    }
    @Test fun accountProbeRequiresAtLeastOneAccessibleAccount() {
        assertFalse(CfValidation.accounts(200, "{\"success\":true,\"result\":[]}"))
        assertFalse(CfValidation.accounts(403, "{\"success\":false}"))
        assertTrue(CfValidation.accounts(200, "{\"success\":true,\"result\":[{\"id\":\"account\"}]}"))
    }
    @Test fun identityRequiresParsedSecretsAndPeer() {
        assertFalse(CfValidation.identity(200, "{}"))
        assertFalse(CfValidation.identity(403, "{\"id\":\"a\",\"token\":\"b\"}"))
        assertFalse(CfValidation.identity(200, "{\"id\":\"a\",\"token\":\"b\"}"))
        assertTrue(CfValidation.identity(200, "{\"id\":\"device\",\"token\":\"test-only\",\"config\":{\"peers\":[{\"public_key\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}]}}"))
    }
    @Test fun deployRequiresSuccessEnvelopeAndExactNonce() {
        assertFalse(CfValidation.api(200, "{\"success\":false}"))
        assertFalse(CfValidation.nonce(500, "expected", "expected"))
        assertFalse(CfValidation.nonce(200, "<html>expected</html>", "expected"))
        assertTrue(CfValidation.nonce(200, "expected", "expected"))
    }
    @Test fun transferRejectsBlockPageAndFailedUploadAcknowledgement() {
        assertFalse(CfValidation.transfer(200, 262144, false, 262144))
        assertFalse(CfValidation.transfer(204, 150, true, 262144))
        assertFalse(CfValidation.transfer(204, 65536, true, 0))
        assertFalse(CfValidation.transfer(204, 65537, true, 16384))
        assertTrue(CfValidation.transfer(204, 65536, true, 16384))
    }
}
