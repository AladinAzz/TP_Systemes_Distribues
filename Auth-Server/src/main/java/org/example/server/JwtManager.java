package org.example.server;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.util.Date;

public class JwtManager {
    // Utilisation d'une clé secrète générée pour signer les JWT
    // En production, cette clé devrait être externalisée !
    private final Key secretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
    
    // Durée de validité du token (ex: 2 heures)
    private static final long EXPIRATION_TIME = 2 * 60 * 60 * 1000;

    public String generateToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + EXPIRATION_TIME);

        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(secretKey)
                .compact();
    }
    
    /**
     * Valide le token et retourne le nom d'utilisateur extrait s'il est valide.
     * @param token Le token JWT
     * @return Le nom d'utilisateur ou null si le token est invalide.
     */
    public String validateTokenAndGetUsername(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (Exception e) {
            System.err.println("Token invalide : " + e.getMessage());
            return null;
        }
    }
}
