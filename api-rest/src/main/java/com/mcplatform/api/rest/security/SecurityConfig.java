package com.mcplatform.api.rest.security;

import com.mcplatform.application.webauth.port.TokenVerifier;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security wiring (web layer). The JWT filter establishes identity for the protected web
 * surface {@code /api/web/**}; EVERYTHING ELSE stays {@code permitAll} — the existing plugin/internal
 * REST endpoints (economy, punishment, report, permission, bridge token/redeem) and actuator are
 * untouched (SC-007). CSRF is disabled globally (stateless Bearer API; the cookie-bearing refresh/logout
 * endpoints are protected by SameSite=Strict + a required {@code X-Refresh} header). Authorization is NOT
 * here — only authentication; rights come from the PermissionResolver at request time (Constitution §12).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/web/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (request, response, ex) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(TokenVerifier tokenVerifier) {
        return new JwtAuthenticationFilter(tokenVerifier);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${mcplatform.webauth.cors.allowed-origin:http://localhost:3000}") String allowedOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));     // explicit origin, never "*" (credentialed)
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Refresh"));
        config.setAllowCredentials(true);                     // browser may send the httpOnly refresh cookie
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
