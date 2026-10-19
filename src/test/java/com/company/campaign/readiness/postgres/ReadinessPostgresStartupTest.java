package com.company.campaign.readiness.postgres;

import com.company.campaign.CampaignApplication;
import com.company.campaign.persistence.CampaignDatasourceConfigurationException;
import com.company.campaign.persistence.CampaignDatasourceErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Requirement: enabling PostgreSQL mode without a usable configuration fails closed.
 *
 * <p>Both cases here abort the application start rather than degrading into a different engine or an
 * unverified pool: a missing credential is refused before anything connects, and an unreachable server is
 * refused while the pool is created. Neither case can produce a started application, so neither can produce
 * readiness evidence.
 *
 * <p>The test uses a closed local port, so it needs no server and no credential of its own.
 */
class ReadinessPostgresStartupTest {

    @Test
    void anEnabledModeWithAMissingCredentialFailsClosed() {
        Throwable thrown = runExpectingFailure(
                "campaign.datasource.enabled=true",
                "campaign.datasource.url=jdbc:postgresql://127.0.0.1:5432/campaign",
                "campaign.datasource.username=readiness-user",
                "campaign.datasource.schema=campaign_readiness_t26_startup");

        assertThat(boundedFailure(thrown).code())
                .isEqualTo(CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_CONFIGURATION_INVALID);
    }

    @Test
    void anEnabledModeWithAnUnreachableServerFailsClosed() {
        Throwable thrown = runExpectingFailure(
                "campaign.datasource.enabled=true",
                "campaign.datasource.url=jdbc:postgresql://127.0.0.1:1/campaign",
                "campaign.datasource.username=readiness-user",
                "campaign.datasource.password=not-a-real-credential",
                "campaign.datasource.connection-timeout-ms=250",
                "campaign.datasource.validation-timeout-ms=250",
                "campaign.datasource.schema=campaign_readiness_t26_startup");

        assertThat(boundedFailure(thrown).code())
                .isEqualTo(CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE);
        // Whatever the framework wraps it in, the thrown text must not disclose the credential or the URL.
        assertThat(messages(thrown))
                .doesNotContain("not-a-real-credential")
                .doesNotContain("jdbc:postgresql://127.0.0.1:1")
                .doesNotContain("127.0.0.1")
                .doesNotContain("Connection to");
    }

    private static Throwable runExpectingFailure(String... properties) {
        // Command-line arguments carry the highest precedence, so the opt-in pin cannot be shadowed by the
        // repository's own application.yml default.
        String[] arguments = java.util.Arrays.stream(properties).map(value -> "--" + value).toArray(String[]::new);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(CampaignApplication.class)
                .web(WebApplicationType.NONE);
        return catchThrowable(() -> builder.run(arguments));
    }

    /** The bounded configuration failure anywhere in the throwable chain. */
    private static CampaignDatasourceConfigurationException boundedFailure(Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (current instanceof CampaignDatasourceConfigurationException failure) {
                return failure;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        throw new AssertionError("No bounded PostgreSQL configuration failure was thrown; thrown=" + thrown);
    }

    /** Every message in the throwable chain, joined, for a disclosure assertion. */
    private static String messages(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        Throwable current = thrown;
        while (current != null) {
            text.append(current.getMessage()).append('|');
            current = current.getCause() == current ? null : current.getCause();
        }
        return text.toString();
    }
}
