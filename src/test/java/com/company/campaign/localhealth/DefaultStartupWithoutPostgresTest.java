package com.company.campaign.localhealth;

import com.company.campaign.CampaignApplication;
import com.company.campaign.persistence.CampaignDatasourceProperties;
import com.company.campaign.readiness.MyBatisPlusReadinessStore;
import com.company.campaign.readiness.ReadinessPostgresVerification;
import com.company.campaign.readiness.ReadinessStatus;
import com.company.campaign.readiness.ReadinessStorageMode;
import com.company.campaign.readiness.ReadinessStorageProbe;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement: the normal application starts without PostgreSQL configuration, creates no embedded database,
 * wires no readiness store and reports an honest in-memory blocker state.
 *
 * <p>This is the counterpart of the opt-in configuration: it proves the default build creates no persistence
 * bean at all, so no test and no local run can accidentally exercise a database.
 */
@SpringBootTest(classes = CampaignApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DefaultStartupWithoutPostgresTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ReadinessLocalHealthService healthService;

    @Test
    void theDefaultApplicationCreatesNoDataSourceNoFlywayAndNoReadinessStore() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MyBatisPlusReadinessStore.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SqlSessionFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ReadinessStorageProbe.class)).isEmpty();
        assertThat(context.containsBean(CampaignDatasourceProperties.DATASOURCE_BEAN_NAME)).isFalse();
        assertThat(context.containsBean(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)).isFalse();
        assertThat(context.containsBean("campaignBusinessFlyway")).isFalse();
    }

    @Test
    void theDefaultStorageStateIsInMemoryAndNothingVerified() {
        LocalHealthData data = healthService.snapshot();

        assertThat(data.process()).isEqualTo(LocalHealthData.PROCESS_UP);
        assertThat(data.storageMode()).isEqualTo(ReadinessStorageMode.IN_MEMORY);
        assertThat(data.postgresqlVerification()).isEqualTo(ReadinessPostgresVerification.NOT_RUN);
        assertThat(data.readiness().status()).isEqualTo(ReadinessStatus.BLOCKED.name());
        assertThat(data.readiness().blockers()).isNotEmpty();
    }

    @Test
    void theLocalHealthEndpointIsWiredButClaimsNothing() {
        assertThat(context.getBeanNamesForType(LocalHealthController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ReadinessLocalHealthService.class)).hasSize(1);
    }
}
