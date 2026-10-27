package com.tickethub.notification.config;

import com.tickethub.common.security.JwtAuthConverter;
import com.tickethub.common.security.SecurityErrorHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Event-driven: no public API. Health/metrics are open, the dead-letter admin API needs ADMIN, everything else is denied. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthConverter converter, SecurityErrorHandlers errors) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }
}
