package com.company.campaign.production.audit;

import com.company.campaign.action.ActionAuditRecord;
import com.company.campaign.action.ActionBoundaryException;
import com.company.campaign.conversation.CampaignConversationAuditRecord;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleDisposition;
import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.runtime.RuntimeBoundaryException;
import com.company.campaign.security.audit.SecurityAuditEvent;
import com.company.campaign.security.audit.SecurityDisposition;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The five production adapters of the accepted audit ports.
 *
 * <p>Every assertion here is <strong>non-database evidence</strong>: the writer runs over a scripted JDBC
 * boundary, not PostgreSQL.
 */
class ProductionAuditAdaptersTest {

    private static final CanonicalJson CANONICAL = new CanonicalJson();

    @Test
    void everyPortAppendsExactlyOneRowForOneRecord() {
        ScriptedProductionAuditJdbcBoundary boundary = new ScriptedProductionAuditJdbcBoundary();
        Ports ports = new Ports(ProductionAuditTestFixtures.ledgerOver(boundary));

        ports.record(ProductionAuditTestFixtures.securityEvent());
        ports.record(ProductionAuditTestFixtures.conversationRecord());
        ports.record(ProductionAuditTestFixtures.lifecycleEvent());
        ports.record(ProductionAuditTestFixtures.runtimeRecord());
        ports.recordAll(List.of(ProductionAuditTestFixtures.actionRecord()));

        assertThat(boundary.committedRows()).hasSize(5);
        assertThat(boundary.statements()).containsOnly("insert");
        assertThat(boundary.calls().stream().filter("getConnection"::equals).count()).isEqualTo(5);
    }

    @Test
    void theLogicalKeysOfTheFivePortsAreDistinctAndDomainSeparated() {
        assertThat(ProductionAuditTestFixtures.allEntries().stream()
                        .map(entry -> entry.logicalKey().value())
                        .toList())
                .doesNotHaveDuplicates()
                .doesNotContainNull()
                .hasSize(5);
        assertThat(ProductionAuditTestFixtures.allEntries().stream()
                        .map(entry -> entry.type().domain().wireName())
                        .toList())
                .containsExactly("SECURITY", "CONVERSATION", "LIFECYCLE", "RUNTIME", "ACTION");
        assertThat(ProductionAuditTestFixtures.allEntries().stream()
                        .map(entry -> entry.eventId())
                        .toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void eachPortKeepsItsOwnClosedTypeAndFormatVersion() {
        assertThat(ProductionAuditTestFixtures.securityEntry().type())
                .isEqualTo(ProductionAuditEventType.SECURITY_DECISION);
        assertThat(ProductionAuditTestFixtures.conversationEntry().type())
                .isEqualTo(ProductionAuditEventType.CONVERSATION_TURN);
        assertThat(ProductionAuditTestFixtures.lifecycleEntry().type())
                .isEqualTo(ProductionAuditEventType.LIFECYCLE_COMMAND);
        assertThat(ProductionAuditTestFixtures.runtimeEntry().type())
                .isEqualTo(ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT);
        assertThat(ProductionAuditTestFixtures.actionEntry().type())
                .isEqualTo(ProductionAuditEventType.ACTION_OUTBOX_AUDIT);
    }

    @Test
    void theSecurityAdapterCarriesEveryAcceptedField() {
        ProductionAuditEntry entry = ProductionAuditTestFixtures.securityEntry();
        JsonNode source = sourceDocument(entry);
        assertThat(source.get("action").textValue()).isEqualTo("RESOURCE_AUTHORIZATION");
        assertThat(source.get("disposition").textValue()).isEqualTo("ALLOWED");
        assertThat(source.get("reasonCode").textValue()).isEqualTo("CAMPAIGN_RESOURCE_ALLOWED");
        assertThat(source.get("durationNanos").longValue()).isEqualTo(1234L);
        assertThat(source.get("occurredAt").textValue()).isEqualTo(ProductionAuditTestFixtures.OCCURRED_AT.toString());
        assertThat(source.get("resourceKind").textValue()).isEqualTo("SEGMENT");
        assertThat(source.get("resourceFingerprint").textValue())
                .isEqualTo(ProductionAuditTestFixtures.resourceFingerprint().hash().value());
        assertThat(entry.sourceHash()).isEqualTo(ProductionAuditTestFixtures.hash("redacted-projection"));
        assertThat(entry.dispositionCode()).isEqualTo("ALLOWED");
        assertThat(entry.reasonCode()).isEqualTo("CAMPAIGN_RESOURCE_ALLOWED");
        assertThat(entry.actionCode()).isEqualTo("RESOURCE_AUTHORIZATION");
        assertThat(entry.resourceFingerprint())
                .isEqualTo(ProductionAuditTestFixtures.resourceFingerprint().hash());
    }

    @Test
    void aNotFoundSecurityDecisionCarriesNoResourceFingerprint() {
        ProductionAuditEntry entry = ProductionSecurityAuditSink.entry(
                ProductionAuditTestFixtures.securityEvent(SecurityDisposition.NOT_FOUND, "CAMPAIGN_RESOURCE_ABSENT"));
        assertThat(entry.resourceFingerprint()).isNull();
        assertThat(entry.dispositionCode()).isEqualTo("NOT_FOUND");
        assertThat(new String(ProductionAuditCodec.canonicalBytes(entry), StandardCharsets.UTF_8))
                .contains("\"resourceFingerprint\":null")
                .contains("\"resourceKind\":null");
    }

    @Test
    void theConversationAdapterCarriesEveryAcceptedFieldAndNoSourceHash() {
        ProductionAuditEntry entry = ProductionAuditTestFixtures.conversationEntry();
        JsonNode source = sourceDocument(entry);
        assertThat(source.get("routeKind").textValue()).isEqualTo("TURN");
        assertThat(source.get("phase").textValue()).isEqualTo("ROUTING");
        assertThat(source.get("accepted").booleanValue()).isTrue();
        assertThat(source.get("code").textValue()).isEqualTo("TURN_ACCEPTED");
        assertThat(source.get("revision").longValue()).isEqualTo(3L);
        assertThat(source.get("conversationFingerprint").textValue())
                .isEqualTo(ProductionAuditTestFixtures.hash("conversation-key").value());
        assertThat(entry.sourceHash()).isNull();
        assertThat(entry.dispositionCode()).isEqualTo(ProductionConversationAuditSink.DISPOSITION_ACCEPTED);
        assertThat(entry.reasonCode()).isEqualTo("TURN_ACCEPTED");
        assertThat(entry.actionCode()).isEqualTo("TURN");
        assertThat(entry.resourceFingerprint()).isEqualTo(ProductionAuditTestFixtures.hash("conversation-key"));
    }

    @Test
    void theLifecycleAdapterCarriesEveryAcceptedFieldAndItsContentHash() {
        ProductionAuditEntry entry = ProductionAuditTestFixtures.lifecycleEntry();
        JsonNode source = sourceDocument(entry);
        assertThat(source.get("action").textValue()).isEqualTo("APPLY");
        assertThat(source.get("disposition").textValue()).isEqualTo("APPLIED");
        assertThat(source.get("planHash").textValue())
                .isEqualTo(ProductionAuditTestFixtures.hash("plan-material").value());
        assertThat(source.get("contentHash").textValue())
                .isEqualTo(ProductionAuditTestFixtures.hash("content-material").value());
        assertThat(entry.sourceHash()).isEqualTo(ProductionAuditTestFixtures.hash("content-material"));
        assertThat(entry.actionCode()).isEqualTo("APPLY");
        assertThat(entry.dispositionCode()).isEqualTo("APPLIED");
    }

    @Test
    void theLifecycleAdapterRecordsAWholeBatchAtomically() {
        ScriptedProductionAuditJdbcBoundary boundary = new ScriptedProductionAuditJdbcBoundary();
        ProductionLifecycleAuditSink sink = new ProductionLifecycleAuditSink(
                ProductionAuditTestFixtures.ledgerOver(boundary));

        sink.recordAll(List.of(
                ProductionAuditTestFixtures.lifecycleEvent(),
                ProductionAuditTestFixtures.lifecycleEvent("CAMPAIGN_APPLY_ACCEPTED", "content-material-2")));

        assertThat(boundary.committedRows()).hasSize(2);
        assertThat(boundary.calls().stream().filter("commit"::equals).count()).isEqualTo(1);
        assertThatCode(() -> sink.recordAll(List.of())).doesNotThrowAnyException();
    }

    @Test
    void theRuntimeAdapterReProvesTheSourceContractsOwnHash() {
        RuntimeAuditRecord record = ProductionAuditTestFixtures.runtimeRecord();
        ProductionAuditEntry entry = ProductionAuditTestFixtures.runtimeEntry();
        RuntimeAuditRecord recomputed = RuntimeAuditRecord.of(
                record.recordId(),
                record.tenantId(),
                record.reasonCode(),
                record.subjectKind(),
                record.subjectId(),
                record.executionKey(),
                record.beforeRevision(),
                record.afterRevision(),
                record.occurredAt());
        assertThat(entry.sourceHash()).isEqualTo(recomputed.recordHash()).isEqualTo(record.recordHash());
        assertThat(entry.eventId())
                .isEqualTo(ProductionAuditIdentity.eventIdFingerprint(
                        ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT, record.recordId()).value())
                .isNotEqualTo(record.recordId());
        JsonNode source = sourceDocument(entry);
        assertThat(source.get("executionKeyFingerprint").textValue())
                .isEqualTo(ProductionAuditIdentity.executionKeyFingerprint("execution-1").value());
        assertThat(source.get("subjectFingerprint").textValue())
                .isEqualTo(ProductionAuditIdentity.subjectFingerprint("subject-1").value());
        assertThat(source.get("topic").textValue()).isEqualTo(record.topic());
    }

    @Test
    void theActionAdapterReProvesTheSourceContractsOwnHash() {
        ActionAuditRecord record = ProductionAuditTestFixtures.actionRecord();
        ProductionAuditEntry entry = ProductionAuditTestFixtures.actionEntry();
        ActionAuditRecord recomputed = ActionAuditRecord.of(
                record.recordId(),
                record.tenantId(),
                record.reasonCode(),
                record.subjectKind(),
                record.subjectId(),
                record.beforeRevision(),
                record.afterRevision(),
                record.occurredAt());
        assertThat(entry.sourceHash()).isEqualTo(recomputed.recordHash()).isEqualTo(record.recordHash());
        assertThat(entry.eventId())
                .isEqualTo(ProductionAuditIdentity.eventIdFingerprint(
                        ProductionAuditEventType.ACTION_OUTBOX_AUDIT, record.recordId()).value())
                .isNotEqualTo(record.recordId());
    }

    @Test
    void everyPortWithChangedOutcomeMaterialKeepsTheSameLogicalKey() {
        assertThat(ProductionSecurityAuditSink.entry(ProductionAuditTestFixtures.securityEvent(
                        SecurityDisposition.DENIED, "CAMPAIGN_RESOURCE_DENIED"))
                .logicalKey())
                .isEqualTo(ProductionAuditTestFixtures.securityEntry().logicalKey());
        assertThat(ProductionLifecycleAuditSink.entry(ProductionAuditTestFixtures.lifecycleEvent(
                        LifecycleDisposition.REJECTED, "CAMPAIGN_APPLY_REJECTED"))
                .logicalKey())
                .isEqualTo(ProductionAuditTestFixtures.lifecycleEntry().logicalKey());
        assertThat(ProductionConversationAuditSink.entry(
                        ProductionAuditTestFixtures.conversationRecord("TURN_REJECTED", 3L))
                .logicalKey())
                .isEqualTo(ProductionAuditTestFixtures.conversationEntry().logicalKey());
    }

    @Test
    void theRuntimeAndActionAdaptersRefuseANullOrEmptyBatch() {
        ProductionRuntimeAuditSink runtime = new ProductionRuntimeAuditSink(newBoundaryLedger());
        assertThatThrownBy(() -> runtime.recordAll(List.of())).isInstanceOf(RuntimeBoundaryException.class);
        assertThatThrownBy(() -> runtime.recordAll(null)).isInstanceOf(RuntimeBoundaryException.class);

        ProductionActionAuditSink action = new ProductionActionAuditSink(newBoundaryLedger());
        assertThatThrownBy(() -> action.recordAll(List.of())).isInstanceOf(ActionBoundaryException.class);
        assertThatThrownBy(() -> action.recordAll(null)).isInstanceOf(ActionBoundaryException.class);
        assertThatThrownBy(() -> action.rollbackAll(List.of(ProductionAuditTestFixtures.actionRecord())))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_ROLLBACK_INVALID));
    }

    @Test
    void theRuntimeAndActionOverridesStillAcceptRealBatches() {
        ScriptedProductionAuditJdbcBoundary boundary = new ScriptedProductionAuditJdbcBoundary();
        MyBatisPlusProductionAuditLedger ledger = ProductionAuditTestFixtures.ledgerOver(boundary);

        new ProductionRuntimeAuditSink(ledger).recordAll(List.of(
                ProductionAuditTestFixtures.runtimeRecord(),
                ProductionAuditTestFixtures.runtimeRecord("record-2", "EXECUTION_CREATED", 0L, 1L)));
        ProductionActionAuditSink action = new ProductionActionAuditSink(ledger);
        List<ActionAuditRecord> actionBatch = List.of(
                ProductionAuditTestFixtures.actionRecord(),
                ProductionAuditTestFixtures.actionRecord("record-4", "ACTION_RESERVED", 0L, 1L));
        action.recordAll(actionBatch);
        action.commitAll(actionBatch);

        assertThat(boundary.committedRows()).hasSize(4);
    }

    // ------------------------------------------------------------------ helpers

    private static MyBatisPlusProductionAuditLedger newBoundaryLedger() {
        return ProductionAuditTestFixtures.ledgerOver(new ScriptedProductionAuditJdbcBoundary());
    }

    private static JsonNode sourceDocument(ProductionAuditEntry entry) {
        return CANONICAL.readTree(entry.sourceDocument());
    }

    /** All five adapters over one ledger, so a caller can pick the port it needs. */
    private record Ports(MyBatisPlusProductionAuditLedger ledger) {

        void record(SecurityAuditEvent event) {
            new ProductionSecurityAuditSink(ledger).record(event);
        }

        void record(CampaignConversationAuditRecord record) {
            new ProductionConversationAuditSink(ledger).record(record);
        }

        void record(LifecycleAuditEvent event) {
            new ProductionLifecycleAuditSink(ledger).record(event);
        }

        void record(RuntimeAuditRecord record) {
            new ProductionRuntimeAuditSink(ledger).record(record);
        }

        void recordAll(List<ActionAuditRecord> records) {
            ProductionActionAuditSink sink = new ProductionActionAuditSink(ledger);
            sink.recordAll(records);
            sink.commitAll(records);
        }
    }
}
