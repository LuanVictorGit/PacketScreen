package com.packetscreen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAccessTest {

    private static WebAccess.Grant grant() {
        return new WebAccess.Grant("Admin", null, true, true, false, "world", 1, 2, 3, 0f);
    }

    @Test
    void tokensAreSingleUse() {
        WebAccess access = new WebAccess();
        String token = access.createToken(grant(), 60000);
        assertTrue(token.length() >= 40);
        WebAccess.Session session = access.redeem(token, 60000);
        assertNotNull(session);
        assertEquals("Admin", session.grant.user);
        assertNotEquals(token, session.id);
        assertNull(access.redeem(token, 60000));
        assertSame(session, access.session(session.id));
    }

    @Test
    void expiredTokensAndSessionsAreRejected() {
        WebAccess access = new WebAccess();
        String token = access.createToken(grant(), -1);
        assertNull(access.redeem(token, 60000));
        String other = access.createToken(grant(), 60000);
        WebAccess.Session session = access.redeem(other, -1);
        assertNotNull(session);
        assertNull(access.session(session.id));
    }

    @Test
    void sessionsCanBeEndedAndRevoked() {
        WebAccess access = new WebAccess();
        WebAccess.Session first = access.redeem(access.createToken(grant(), 60000), 60000);
        WebAccess.Session second = access.redeem(access.createToken(grant(), 60000), 60000);
        access.end(first);
        assertNull(access.session(first.id));
        assertNotNull(access.session(second.id));
        String pending = access.createToken(grant(), 60000);
        access.revokeAll();
        assertNull(access.session(second.id));
        assertNull(access.redeem(pending, 60000));
        assertNull(access.session(null));
        assertNull(access.session("unknown"));
    }

    @Test
    void loginAttemptsAreRateLimited() {
        WebAccess access = new WebAccess();
        assertTrue(access.allowAttempt("1.2.3.4", 3));
        assertTrue(access.allowAttempt("1.2.3.4", 3));
        assertTrue(access.allowAttempt("1.2.3.4", 3));
        assertFalse(access.allowAttempt("1.2.3.4", 3));
        assertTrue(access.allowAttempt("5.6.7.8", 3));
    }

    @Test
    void comparesSecretsSafely() {
        assertTrue(WebAccess.safeEquals("abc", "abc"));
        assertFalse(WebAccess.safeEquals("abc", "abd"));
        assertFalse(WebAccess.safeEquals("abc", null));
    }
}
