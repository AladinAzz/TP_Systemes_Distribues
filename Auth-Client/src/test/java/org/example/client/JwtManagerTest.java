package org.example.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JwtManagerTest {

    @Test
    public void generateTokenAndValidate_ShouldReturnUsername() {
        JwtManager jwtManager = new JwtManager();

        String token = jwtManager.generateToken("alice");

        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals("alice", jwtManager.validateTokenAndGetUsername(token));
    }

    @Test
    public void validateToken_WithTamperedToken_ShouldReturnNull() {
        JwtManager jwtManager = new JwtManager();
        String token = jwtManager.generateToken("alice");

        String tamperedToken = token + "x";

        assertNull(jwtManager.validateTokenAndGetUsername(tamperedToken));
    }
}
