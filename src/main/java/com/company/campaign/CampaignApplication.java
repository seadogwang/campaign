package com.company.campaign;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The Campaign AI Builder modular monolith.
 *
 * <p>T26 makes real PostgreSQL strictly opt-in: {@link DataSourceAutoConfiguration} and
 * {@link FlywayAutoConfiguration} are excluded here so the normal application can never create an implicit
 * pool, an embedded database or a startup migration from ambient properties. A host that wants PostgreSQL
 * enables {@code campaign.readiness.postgres.enabled}, and the focused
 * {@code com.company.campaign.readiness.postgres} configuration then owns the DataSource, the ordered Flyway
 * migration run and the real {@code MyBatisPlusReadinessStore}. Without that pin the application starts with no
 * persistence bean at all and reports an honest blocker.
 *
 * <p>T35A1 adds the servlet Spring Security modules for the default-off production identity boundary, so the
 * framework's generated-user auto-configuration is excluded as well. The production-disabled default is covered
 * by the explicit permit-all chain in {@code com.company.campaign.production.security}, which keeps the existing
 * routes unlocked and creates no generated user.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class })
@EnableConfigurationProperties(CampaignDatasourceProperties.class)
public class CampaignApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampaignApplication.class, args);
    }
}
