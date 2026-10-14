package com.pswied.corevia.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CoreviaSecurityProperties securityProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(CoreviaSecurityProperties securityProperties, JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.securityProperties = securityProperties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> {
                if (!securityProperties.isEnabled()) {
                    authorize.anyRequest().permitAll();
                    return;
                }
                authorize
                    .requestMatchers("/actuator/health", "/h2-console/**").permitAll()
                    .anyRequest().authenticated();
            })
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        if (securityProperties.isEnabled()) {
            http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
