package com.company.campaign.production.audit;

import com.company.campaign.action.ActionAuditRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The accepted commit boundary of the production Action audit adapter.
 *
 * <p>Every assertion here is <strong>non-database evidence</strong>: the writer runs over a scripted JDBC
 * boundary, not PostgreSQL. A missing, mismatched or repeated {@code commitAll} fails closed with the bounded
 * rollback/transaction code and commits nothing, and no accepted commit or compensating rollback ever deletes or
 * mutates a committed audit row.
 */
class ProductionActionAuditCommitBoundaryTest {

    @Test
    void commitAllWithNoStagedBatchFailsClosed() {
        ScriptedProductionAuditJdbcBoundary boundary = new ScriptedProductionAuditJdbcBoundary();
        ProductionActionAuditSink sink = new ProductionActionAuditSink(
                ProductionAuditTestFixtures.ledgerOver(boundary));

        assertThatThrownBy(() -> sink.commitAll(List.of(ProductionAuditTestFixtures.actionRecord())))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_ROLLBACK_INVALID));

        assertThat(boundary.commitCalled()).isFalse();
        assertThat(boundary.committedRows()).isEmpty();
    }

    @Test
    void commitAllForAnythingButTheStagedBatchIsRefusedAndLeavesItStaged() {
        ScriptedProductionAuditJdbcBoundary boundary = new ScriptedProductionAuditJdbcBoundary();
        ProductionActionAuditSink sink = new ProductionActionAuditSink(
                ProductionAuditTestFixtures.ledgerOver(boundary));
        List<ActionAuditRecord> staged = List.of(ProductionAuditTestFixtures.actionRecord());

        sink.recordAll(staged);

        assertThatThrownBy(() -> sink.commitAll(List.of(
                        ProductionAuditTestFixtures.actionRecord("record-9", "ACTION_RESERVED", 0L, 1L))))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_ROLLBACK_INVALID));

        // Nothing was committed, and the real batch is still the one the accepted boundary must name.
        assertThat(boundary.commitCalled()).isFalse();
        sink.commitStaged();
        assertThat(boundary.committedRows()).hasSize(1);
    }

    @Test
    void commitAllTwiceIsRefusedTheSecondTimeAndCommitsExactlyOnce() {
        ScriptedProductionAuditJdbcBoundary boundary = new ScriptedProductionAuditJdbcBoundary();
        ProductionActionAuditSink sink = new ProductionActionAuditSink(
                ProductionAuditTestFixtures.ledgerOver(boundary));
        List<ActionAuditRecord> batch = List.of(ProductionAuditTestFixtures.actionRecord());

        sink.recordAll(batch);
        sink.commitAll(batch);

        assertThatThrownBy(() -> sink.commitAll(batch))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_ROLLBACK_INVALID));

        assertThat(boundary.calls().stream().filter("commit"::equals).count()).isEqualTo(1);
        assertThat(boundary.committedRows()).hasSize(1);
    }

    @Test
    void anAcceptedCommitNeverDeletesOrMutatesACommittedAuditRow() {
        ScriptedProductionAuditJdbcBoundary boundary = new ScriptedProductionAuditJdbcBoundary();
        ProductionActionAuditSink sink = new ProductionActionAuditSink(
                ProductionAuditTestFixtures.ledgerOver(boundary));
        List<ActionAuditRecord> committed = List.of(
                ProductionAuditTestFixtures.actionRecord(),
                ProductionAuditTestFixtures.actionRecord("record-2", "ACTION_RESERVED", 0L, 1L));

        sink.recordAll(committed);
        sink.commitAll(committed);
        Map<String, String> durable = boundary.committedRows();

        // A later stage that is compensated never touches the committed rows.
        List<ActionAuditRecord> compensated = List.of(
                ProductionAuditTestFixtures.actionRecord("record-3", "ACTION_RESERVED", 0L, 1L));
        sink.recordAll(compensated);
        sink.rollbackAll(compensated);

        assertThat(boundary.committedRows()).isEqualTo(durable).hasSize(2);
        assertThat(boundary.statements()).containsOnly("insert");
    }
}