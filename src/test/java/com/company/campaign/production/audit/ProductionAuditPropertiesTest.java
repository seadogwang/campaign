package com.company.campaign.production.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The opt-in bounds of the default-off durable audit configuration.
 *
 * <p>It is deterministic unit evidence, not database evidence.
 */
class ProductionAuditPropertiesTest {

    @Test
    void theDefaultIsDisabledAndValidatesNothing() {
        ProductionAuditProperties properties = new ProductionAuditProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getLedger().getSchema()).isEqualTo("campaign_production_audit");
    }

    @Test
    void anEnabledConfigurationWithoutACompletePoolConfigurationFailsClosed() {
        // Enabling durable audit on its own supplies no connection setting at all, so the configuration is
        // refused rather than allowed to reach a pool — and a migration run — it was never given.
        ProductionAuditProperties enabledOnly = new ProductionAuditProperties();
        enabledOnly.setEnabled(true);
        assertThat(failureCode(enabledOnly))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);

        // A complete pool configuration that is missing its dedicated schema is refused by the same bound.
        assertThat(failureCode(properties(properties -> properties.getLedger().setSchema(null))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
    }

    @Test
    void everyRequiredConnectionSettingIsRequired() {
        assertThat(failureCode(properties(properties -> properties.getLedger().setUrl(null))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
        assertThat(failureCode(properties(properties -> properties.getLedger().setUrl(" "))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
        assertThat(failureCode(properties(properties -> properties.getLedger().setUsername(null))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
        assertThat(failureCode(properties(properties -> properties.getLedger().setPassword(null))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
    }

    @Test
    void aReservedSchemaIsRefusedWithItsOwnBoundedCode() {
        assertThat(failureCode(properties(properties -> properties.getLedger().setSchema("public"))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_SCHEMA_RESERVED);
        assertThat(failureCode(properties(properties -> properties.getLedger().setSchema("pg_catalog"))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_SCHEMA_RESERVED);
    }

    @Test
    void aMalformedSchemaIsRefusedAsAConfigurationFailure() {
        assertThat(failureCode(properties(properties -> properties.getLedger().setSchema("Campaign Audit"))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
        assertThat(failureCode(properties(properties -> properties.getLedger().setSchema(""))))
                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
    }

    @Test
    void everyBoundIsEnforced() {
        List<Consumer<ProductionAuditProperties.Ledger>> outOfBounds = new ArrayList<>();
        outOfBounds.add(ledger -> ledger.setMaximumPoolSize(0));
        outOfBounds.add(ledger -> ledger.setMaximumPoolSize(17));
        outOfBounds.add(ledger -> ledger.setMinimumIdle(5));
        outOfBounds.add(ledger -> ledger.setConnectionTimeoutMs(249));
        outOfBounds.add(ledger -> ledger.setValidationTimeoutMs(10_001));
        outOfBounds.add(ledger -> ledger.setStatementTimeoutMs(99));
        outOfBounds.add(ledger -> ledger.setStatementTimeoutMs(60_001));
        outOfBounds.add(ledger -> ledger.setLockTimeoutMs(99));
        outOfBounds.add(ledger -> ledger.setLockTimeoutMs(10_001));
        outOfBounds.add(ledger -> ledger.setIdleTransactionTimeoutMs(999));
        outOfBounds.add(ledger -> ledger.setIdleTransactionTimeoutMs(60_001));
        outOfBounds.add(ledger -> ledger.setQueryTimeoutMs(99));
        outOfBounds.add(ledger -> ledger.setMaxBatchRecords(0));
        outOfBounds.add(ledger -> ledger.setMaxBatchRecords(257));
        outOfBounds.add(ledger -> ledger.setMaxSourceDocumentChars(255));
        outOfBounds.add(ledger -> ledger.setMaxSourceDocumentChars(8_193));
        for (Consumer<ProductionAuditProperties.Ledger> mutation : outOfBounds) {
            assertThat(failureCode(properties(properties -> mutation.accept(properties.getLedger()))))
                    .as("an out-of-bounds bound is refused")
                    .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
        }
    }

    @Test
    void theValidationTimeoutMayNotExceedTheConnectionTimeout() {
        assertThat(failureCode(properties(properties -> {
            properties.getLedger().setConnectionTimeoutMs(250);
            properties.getLedger().setValidationTimeoutMs(500);
        }))).isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
    }

    @Test
    void theDefaultBoundsAreValid() {
        assertThatCode(ProductionAuditTestFixtures.properties()::validate).doesNotThrowAnyException();
    }

    @Test
    void theDiagnosticsRedactEveryConnectionValue() {
        String text = ProductionAuditTestFixtures.properties().toString();
        assertThat(text)
                .doesNotContain(ProductionAuditTestFixtures.LEDGER_URL)
                .doesNotContain(ProductionAuditTestFixtures.LEDGER_USERNAME)
                .doesNotContain(ProductionAuditTestFixtures.LEDGER_PASSWORD)
                .contains("<redacted>");
        assertThat(ProductionAuditTestFixtures.properties().getLedger().toString())
                .doesNotContain(ProductionAuditTestFixtures.LEDGER_PASSWORD);
    }

    @Test
    void theMigrationLocationIsCommittedAndClosed() {
        assertThat(ProductionAuditProperties.MIGRATION_LOCATIONS)
                .isEqualTo("classpath:db/campaign-production-audit-migration");
    }

    // ------------------------------------------------------------------ helpers

    private static ProductionAuditProperties properties(
            Consumer<ProductionAuditProperties> mutation) {
        ProductionAuditProperties properties = ProductionAuditTestFixtures.properties();
        mutation.accept(properties);
        return properties;
    }

    private static String failureCode(ProductionAuditProperties properties) {
        try {
            properties.validate();
        } catch (ProductionAuditConfigurationException failure) {
            return failure.code();
        }
        throw new AssertionError("the configuration was accepted but should have failed closed");
    }
}