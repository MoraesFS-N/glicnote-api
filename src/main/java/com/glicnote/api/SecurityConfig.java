package com.glicnote.api;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.auth.issuer}") String issuer,
        @Value("${app.auth.jwks-uri}") String jwks, @Value("${app.auth.audience}") String audience) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithms(algorithms -> {
            algorithms.clear(); algorithms.add(SignatureAlgorithm.RS256); algorithms.add(SignatureAlgorithm.ES256);
        }).build();
        decoder.setJwtValidator(validator(issuer,audience));
        return decoder;
    }
    static OAuth2TokenValidator<Jwt> validator(String issuer,String audience) {
        OAuth2TokenValidator<Jwt> claims=jwt -> {
            try {
                UUID.fromString(jwt.getSubject());
                if(!jwt.getAudience().contains(audience) || !"authenticated".equals(jwt.getClaimAsString("role"))
                    || Boolean.TRUE.equals(jwt.getClaimAsBoolean("is_anonymous")) || jwt.getExpiresAt()==null) {
                    throw new IllegalArgumentException();
                }
                return OAuth2TokenValidatorResult.success();
            } catch(RuntimeException e) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claims);
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET,"/health","/privacy.html").permitAll()
                .requestMatchers("/v1/**").authenticated().anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
            .build();
    }
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins}") String origins) {
        var config=new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type"));
        config.setAllowCredentials(false);
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",config); return source;
    }
}
