package com.company.campaign.readiness.postgres;

import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;

/** Creates the bounded readiness logger through Flyway 11's logging SPI. */
public final class ReadinessFlywayLogCreator implements LogCreator {

    @Override
    public Log createLogger(Class<?> loggerClass) {
        return new ReadinessFlywayLog();
    }
}
