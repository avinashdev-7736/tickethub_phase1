package com.tickethub.apigateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * The gateway rejects missing/invalid/expired tokens early. Role checks (ADMIN-only endpoints) and
 * ownership checks stay in the services, which validate the same JWT again (defence in depth).
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/internal/**").denyAll()                        // service-to-service only
                        .pathMatchers(HttpMethod.GET, "/events", "/events/*", "/shows", "/shows/*", "/shows/*/seats").permitAll()
                        .pathMatchers(HttpMethod.POST, "/payments/webhook").permitAll() // signature-verified by Payment
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
