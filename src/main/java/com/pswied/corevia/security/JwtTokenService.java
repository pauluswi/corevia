package com.pswied.corevia.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService {

    private final CoreviaSecurityProperties properties;
    private final SecretKey signingKey;

    public JwtTokenService(CoreviaSecurityProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createToken(String subject) {
        return Jwts.builder()
            .setSubject(subject)
            .setIssuer(properties.getIssuer())
            .setAudience(properties.getAudience())
            .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(signingKey, SignatureAlgorithm.HS256)
            .compact();
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Jws<Claims> claims = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .requireIssuer(properties.getIssuer())
                .requireAudience(properties.getAudience())
                .build()
                .parseClaimsJws(token);
            return claims.getBody().getExpiration() != null && claims.getBody().getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    public String subject(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
