package com.company.campaign.localhealth;

import com.company.campaign.readiness.ReadinessStorageProbe;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the local health endpoint's service.
 *
 * <p>The storage probe is resolved from the context rather than created here: when a host configured the
 * opt-in PostgreSQL runtime, its real probe is the one that answers; otherwise the service falls back to the
 * stateless in-memory probe. There is therefore never an ambiguous pair of probes, and nothing here creates a
 * DataSource, a migration or a readiness store — the normal application still starts with no persistence bean
 * and reports an honest {@code IN_MEMORY} / {@code NOT_RUN} / {@code BLOCKED} state.
 */
@Configuration(proxyBeanMethods = false)
public class LocalHealthConfiguration {

    @Bean
    public ReadinessLocalHealthService readinessLocalHealthService(ObjectProvider<ReadinessStorageProbe> probes) {
        ReadinessStorageProbe probe = probes.getIfAvailable(ReadinessStorageProbe::inMemory);
        return new ReadinessLocalHealthService(probe);
    }
}