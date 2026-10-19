package com.company.campaign.production.security;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import com.company.campaign.persistence.CampaignDatasourceConfiguration;
import com.company.campaign.security.context.TrustedContextProvider;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * The focused opt-in configuration of the default-off production identity boundary.
 *
 * <p>It is inert unless the host sets {@code campaign.production.enabled=true}. When it is off — the default —
 * the application creates no JWT decoder, no production DataSource, no Flyway runtime, no membership repository
 * and no request-scoped provider at all, and the framework security layer is an explicit permit-all chain so the
 * existing routes and the opt-in development Campaign host are exactly as before.
 *
 * <p>When it is on it wires, fail-closed and in one place: the stateless servlet security chain that keeps
 * {@code /api/v1/health} public and requires authentication for {@code /api/v1/campaign-agent/**} and the future
 * {@code /api/v1/operations/**}; the Nimbus JWT decoder with the exact issuer, audience, signature, timestamp,
 * issued-at and asymmetric-algorithm validation; the request-scoped {@link ProductionTrustedContextProvider}; and
 * the server-side {@link ProductionMembershipRepository}, either over the shared business
 * {@link SqlSessionFactory} or a host-supplied implementation.
 *
 * <p>It refuses to start when the development Campaign Agent host is also enabled, and it never requires a
 * credential or a URL to be committed: every value comes from host configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({ProductionSecurityProperties.class, CampaignDatasourceProperties.class})
@Import(CampaignDatasourceConfiguration.class)
@ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
public class ProductionSecurityConfiguration {

    /** The one server-side membership repository. */
    public static final String REPOSITORY_BEAN_NAME = "campaignProductionMembershipRepository";

    /** The one Nimbus JWT decoder. */
    public static final String DECODER_BEAN_NAME = "campaignProductionJwtDecoder";

    /** The one stateless production security filter chain. */
    public static final String FILTER_CHAIN_BEAN_NAME = "campaignProductionSecurityFilterChain";

    /** The one request-scoped trusted-context provider. */
    public static final String PROVIDER_BEAN_NAME = "campaignProductionTrustedContextProvider";

    /** {@code /api/v1/health} stays public and bounded; it never discloses identity or configuration. */
    public static final String HEALTH_PATTERN = "/api/v1/health";

    /** The authenticated Campaign Agent routes. */
    public static final String CAMPAIGN_AGENT_PATTERN = "/api/v1/campaign-agent/**";

    /** The authenticated future production operator/runtime routes. */
    public static final String OPERATIONS_PATTERN = "/api/v1/operations/**";

    private final ProductionSecurityProperties properties;
    private final CampaignDatasourceProperties campaignDatasourceProperties;

    public ProductionSecurityConfiguration(
            ProductionSecurityProperties properties,
            CampaignDatasourceProperties campaignDatasourceProperties) {
        this.properties = properties;
        this.campaignDatasourceProperties = campaignDatasourceProperties;
    }

    @PostConstruct
    void validateLegacyConfiguration() {
        properties.inheritDatasourceFrom(campaignDatasourceProperties);
        properties.validate();
        if (properties.isEnabled()
                && properties.getMembership().getMode() == ProductionMembershipMode.POSTGRESQL) {
            campaignDatasourceProperties.validateForLegacy(
                    properties.getMembership().getUrl(), properties.getMembership().getUsername(),
                    properties.getMembership().getPassword(), properties.getMembership().getSchema());
        }
    }

    /** The one host-exclusion guard; its construction fails the start when both hosts are enabled. */
    @Bean
    public ProductionHostExclusionGuard campaignProductionHostExclusionGuard(Environment environment) {
        return new ProductionHostExclusionGuard(environment);
    }

    /**
     * The one Nimbus JWT decoder of the production boundary.
     *
     * <p>A host-supplied {@link NimbusJwtDecoder} has documented precedence, so a host that already owns the JWK
     * source keeps it; otherwise the decoder is built from the configured JWK Set URI. Either way the exact
     * issuer, audience, timestamp, issued-at and asymmetric-algorithm validation is installed here, so the host
     * cannot accidentally weaken the accepted token contract. More than one host decoder is an ambiguous wiring
     * and aborts startup.
     */
    @Bean(name = DECODER_BEAN_NAME)
    @Qualifier(DECODER_BEAN_NAME)
    @Primary
    public JwtDecoder campaignProductionJwtDecoder(
            ObjectProvider<NimbusJwtDecoder> hostDecoders,
            ObjectProvider<Clock> clocks) {
        properties.validate();
        Clock clock = clocks.orderedStream().findFirst().orElseGet(Clock::systemUTC);
        List<NimbusJwtDecoder> host = hostDecoders.orderedStream().limit(2).toList();
        if (host.size() > 1) {
            throw new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_SECURITY_CONFIGURATION_INVALID,
                    "The production identity boundary requires at most one host JWT decoder.");
        }
        NimbusJwtDecoder decoder = host.isEmpty() ? buildDecoder() : host.getFirst();
        decoder.setJwtValidator(ProductionJwtValidators.production(properties.getJwt(), clock));
        return decoder;
    }

    private NimbusJwtDecoder buildDecoder() {
        String jwkSetUri = properties.getJwt().getJwkSetUri();
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_JWK_SET_UNAVAILABLE,
                    "The production identity boundary requires a configured JWK Set URI or a host JWT decoder.");
        }
        try {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        } catch (RuntimeException failure) {
            throw new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_JWK_SET_UNAVAILABLE,
                    "The configured production JWK Set URI could not be used to build a decoder.", failure);
        }
    }

    /** The bounded JSON {@code 401} response of the production boundary. */
    @Bean
    public BoundedJsonAuthenticationEntryPoint campaignProductionAuthenticationEntryPoint() {
        return new BoundedJsonAuthenticationEntryPoint();
    }

    /** The bounded JSON {@code 403} response of the production boundary. */
    @Bean
    public BoundedJsonAccessDeniedHandler campaignProductionAccessDeniedHandler() {
        return new BoundedJsonAccessDeniedHandler();
    }

    /**
     * The one stateless servlet security chain of the production boundary.
     *
     * <p>Sessions, CSRF, form login, basic authentication and logout are all disabled: this is a stateless bearer
     * API. Only an authenticated request may reach the Campaign Agent or operations routes, and both failures use
     * the bounded JSON envelopes. The health route is public, and every other route keeps its previous policy so
     * the production switch never silently locks an unrelated endpoint.
     *
     * <p>The routes are matched with explicit {@link AntPathRequestMatcher}s rather than the String overload, so
     * the chain never needs an {@code mvcHandlerMappingIntrospector} and is exactly the same policy whether or not
     * Spring MVC shares this application context.
     */
    @Bean(name = FILTER_CHAIN_BEAN_NAME)
    public SecurityFilterChain campaignProductionSecurityFilterChain(
            HttpSecurity http,
            @Qualifier(DECODER_BEAN_NAME) JwtDecoder campaignProductionJwtDecoder,
            BoundedJsonAuthenticationEntryPoint campaignProductionAuthenticationEntryPoint,
            BoundedJsonAccessDeniedHandler campaignProductionAccessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(ant(HEALTH_PATTERN)).permitAll()
                        .requestMatchers(ant(CAMPAIGN_AGENT_PATTERN), ant(OPERATIONS_PATTERN)).authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(campaignProductionAuthenticationEntryPoint)
                        .accessDeniedHandler(campaignProductionAccessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(campaignProductionJwtDecoder))
                        .authenticationEntryPoint(campaignProductionAuthenticationEntryPoint)
                        .accessDeniedHandler(campaignProductionAccessDeniedHandler));
        return http.build();
    }

    /** One explicit ant-path matcher; it never requires Spring MVC's handler-mapping introspector. */
    private static RequestMatcher ant(String pattern) {
        return new AntPathRequestMatcher(pattern);
    }

    /**
     * The one server-side membership repository of the production boundary.
     *
     * <p>A host-supplied {@link ProductionMembershipRepository} bean has documented precedence; more than one is
     * an ambiguous wiring and aborts startup. Otherwise the validated membership mode decides: {@code POSTGRESQL}
     * builds the adapter over the shared business {@link SqlSessionFactory}, while {@code HOST} requires exactly
     * one host repository and creates no pool at all.
     */
    @Bean(name = REPOSITORY_BEAN_NAME)
    @Qualifier(REPOSITORY_BEAN_NAME)
    public ProductionMembershipRepository campaignProductionMembershipRepository(
            ListableBeanFactory beanFactory,
            @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
            ObjectProvider<SqlSessionFactory> sessionFactoryProvider) {
        properties.validate();
        List<String> hostRepositories = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(ProductionMembershipRepository.class, true, true)) {
            if (!REPOSITORY_BEAN_NAME.equals(name)) {
                hostRepositories.add(name);
            }
        }
        if (hostRepositories.size() > 1) {
            throw new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_MEMBERSHIP_REPOSITORY_AMBIGUOUS,
                    "The production identity boundary requires at most one host membership repository.");
        }
        if (hostRepositories.size() == 1) {
            return beanFactory.getBean(hostRepositories.get(0), ProductionMembershipRepository.class);
        }
        if (properties.getMembership().getMode() == ProductionMembershipMode.HOST) {
            throw new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_MEMBERSHIP_REPOSITORY_REQUIRED,
                    "The host membership mode requires exactly one host membership repository.");
        }
        SqlSessionFactory sessionFactory = sessionFactoryProvider.getIfAvailable();
        if (sessionFactory == null) {
            throw new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_MEMBERSHIP_REPOSITORY_REQUIRED,
                    "The PostgreSQL membership mode requires the shared business SqlSessionFactory.");
        }
        return new MyBatisPlusProductionMembershipRepository(sessionFactory, properties.getMembership());
    }

    /**
     * The one request-scoped trusted-context provider.
     *
     * <p>It is a request-scoped proxy, so exactly one instance is built per authenticated request and it can never
     * be reached outside one: a caller outside a bearer request fails closed. No static holder and no hand-rolled
     * per-thread holder is used.
     */
    @Bean(name = PROVIDER_BEAN_NAME)
    @Qualifier(PROVIDER_BEAN_NAME)
    @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.INTERFACES)
    public TrustedContextProvider campaignProductionTrustedContextProvider(
            @Qualifier(REPOSITORY_BEAN_NAME) ProductionMembershipRepository repository,
            ObjectProvider<Clock> clocks) {
        properties.validate();
        Clock clock = clocks.orderedStream().findFirst().orElseGet(Clock::systemUTC);
        return new ProductionTrustedContextProvider(repository, properties, clock);
    }

    /**
     * The real PostgreSQL membership runtime, created only when the production boundary is enabled and the host
     * selected {@code POSTGRESQL} mode.
     *
     * <p>It is a nested member class rather than a second condition on the outer class, so the host mode creates
     * no pool at all. It repeats the production-boundary opt-in condition because component scanning can register
     * this nested class on its own, and without that condition a production-disabled application would try to
     * build a pool it can never have properties for.
     *
     * <p>The pool, Flyway runtime and migration initializer are now provided by
     * {@code CampaignDatasourceConfiguration} over the shared business datasource.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(
            prefix = "campaign.production.membership", name = "mode", havingValue = "POSTGRESQL",
            matchIfMissing = true)
    static class PostgresMembershipRuntime {
        // The pool, Flyway and migration are now provided by CampaignDatasourceConfiguration.
        // The membership repository bean is published by the outer class over the shared SqlSessionFactory.
    }

    /** The committed migration location constants; a host cannot redirect the run. */
    static final class MigrationLocations {

        static final String PRODUCTION_MEMBERSHIP = "classpath:db/campaign-production-membership-migration";

        private MigrationLocations() {
        }
    }
}
