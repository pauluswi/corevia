package com.pswied.corevia.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private final CoreviaSecurityProperties properties = new CoreviaSecurityProperties();
    private final JwtTokenService jwtTokenService = new JwtTokenService(properties);

    @Test
    void validTokenIsAccepted() {
        String token = jwtTokenService.createToken("demo-user");

        assertThat(jwtTokenService.isValid(token)).isTrue();
        assertThat(jwtTokenService.subject(token)).isEqualTo("demo-user");
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtTokenService.createToken("demo-user");
        String tampered = token.substring(0, token.length() - 1) + "A";

        assertThat(jwtTokenService.isValid(tampered)).isFalse();
    }
}
