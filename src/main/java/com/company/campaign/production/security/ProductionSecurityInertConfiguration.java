package com.company.campaign.production.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The framework security layer of the default — production-disabled — application.
 *
 * <p>Adding the servlet Spring Security modules makes Spring Boot's own web security auto-configuration eligible
 * to secure every route with a generated user. That would silently lock the existing health endpoint and the
 * opt-in development Campaign host, so this configuration always publishes one explicit, stateless, permit-all
 * {@link SecurityFilterChain}. Spring Boot's default chain then backs off, no generated user is created (the
 * application excludes it), and the default application behaves exactly as before.
 *
 * <p>It is active precisely when {@code campaign.production.enabled} is absent or {@code false}; the production
 * configuration owns the chain only when the host explicitly enables it.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnProperty(
        prefix = "campaign.production", name = "enabled", havingValue = "false", matchIfMissing = true)
public class ProductionSecurityInertConfiguration {

    /**
     * The one permit-all framework chain of the default application.
     *
     * <p>It never authenticates, never creates a session and never locks a route; it exists only so that the
     * presence of the security modules cannot change the default application's behaviour.
     */
    @Bean(name = ProductionSecurityConfiguration.FILTER_CHAIN_BEAN_NAME)
    public SecurityFilterChain campaignProductionSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}