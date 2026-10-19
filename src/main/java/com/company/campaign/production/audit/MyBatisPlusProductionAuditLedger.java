package com.company.campaign.production.audit;

import com.company.campaign.persistence.entity.CampaignProductionAuditLedgerEntity;
import com.company.campaign.shared.canonical.ContentHash;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one append-only durable audit writer backed by MyBatis-Plus.
 *
 * <p>It owns exactly two constant mapper statements — one insert and one identity lookup — and never issues any
 * other SQL. There is deliberately no update, no delete, no truncate and no DDL anywhere in this class, so a
 * committed audit row can never be mutated or removed by this build.
 *
 * <p>Every write follows the same shape:
 * <ol>
 *   <li>the complete batch is validated and canonicalized <em>before</em> a session is opened, so a codec,
 *       binding or bounds failure can never leave a half-open session behind;</li>
 *   <li>all rows are appended inside one explicit SqlSession with no auto-commit, a bounded batch size
 *       and one commit — all of them or none;</li>
 *   <li>an exact replay (same immutable logical key, same document hash) is a no-op that writes nothing, while
 *       the same logical key with a different document hash is a bounded conflict that writes nothing;</li>
 *   <li>any MyBatis, codec or binding failure maps to one non-disclosing unavailable code that carries neither the
 *       statement, the document nor the driver message.</li>
 * </ol>
 *
 * <p>{@link #stage(List)} is the same write without the commit: it returns an explicit
 * {@link ProductionAuditLedgerTransaction} token naming exactly the uncommitted batch, so the one accepted port
 * whose contract stages an audit batch and later compensates it can roll back precisely that batch and never a
 * committed row.
 */
final class MyBatisPlusProductionAuditLedger {

    /**
     * The one constant insert. The conflict target is the immutable logical event key, and the action is
     * {@code do nothing}: the writer never updates an existing row, so an exact replay leaves committed history
     * untouched and a changed replay is detected by the identity lookup below rather than by an overwrite.
     */
    static final String INSERT_STATEMENT =
            "com.company.campaign.persistence.mapper.CampaignProductionAuditLedgerMapper.insertAuditEntry";

    /** The one constant identity lookup; it reads a committed row's canonical document hash. */
    static final String FIND_DOCUMENT_HASH_STATEMENT =
            "com.company.campaign.persistence.mapper.CampaignProductionAuditLedgerMapper.findExistingDocumentHash";

    private final SqlSessionFactory sqlSessionFactory;
    private final ProductionAuditProperties.Ledger properties;

    MyBatisPlusProductionAuditLedger(SqlSessionFactory sqlSessionFactory, ProductionAuditProperties.Ledger properties) {
        this.sqlSessionFactory = Objects.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * Appends one complete batch atomically, or writes nothing.
     *
     * <p>An empty batch is a successful no-op: the port that forbids an empty batch refuses it before reaching
     * here, and the port that accepts one records nothing.
     */
    void record(List<ProductionAuditEntry> entries) {
        List<ProductionAuditEntry> batch = validate(entries);
        if (batch.isEmpty()) {
            return;
        }
        SqlSession session = sqlSessionFactory.openSession(false);
        try {
            appendSqlSession(session, batch);
            session.commit();
        } catch (ProductionAuditException failure) {
            rollbackQuietly(session);
            throw failure;
        } catch (RuntimeException failure) {
            rollbackQuietly(session);
            throw ProductionAuditException.unavailable(failure);
        } finally {
            closeQuietly(session);
        }
    }

    /**
     * Stages one complete batch in an open, uncommitted session and returns its token.
     *
     * <p>The batch is appended but not committed: the caller owns the accepted boundary. Closing the token
     * without committing discards the batch, and {@link ProductionAuditLedgerTransaction#rollback()} discards
     * exactly this batch. Neither path deletes or mutates a committed row.
     */
    ProductionAuditLedgerTransaction stage(List<ProductionAuditEntry> entries) {
        List<ProductionAuditEntry> batch = validate(entries);
        if (batch.isEmpty()) {
            throw batchInvalid();
        }
        SqlSession session = sqlSessionFactory.openSession(false);
        ProductionAuditLedgerTransaction transaction = new ProductionAuditLedgerTransaction(
                session, ProductionAuditIdentity.batchIdentity(batch));
        try {
            appendSqlSession(session, batch);
            return transaction;
        } catch (ProductionAuditException failure) {
            transaction.close();
            throw failure;
        } catch (RuntimeException failure) {
            transaction.close();
            throw ProductionAuditException.unavailable(failure);
        }
    }

    // ------------------------------------------------------------------ validation

    /**
     * Validates and canonicalizes the complete batch before any session is opened.
     *
     * <p>{@link ProductionAuditEntry#canonicalDocument()} forces the codec to run here, so a malformed,
     * oversized or non-canonical document is refused while no session is held.
     */
    private List<ProductionAuditEntry> validate(List<ProductionAuditEntry> entries) {
        if (entries == null) {
            throw batchInvalid();
        }
        if (entries.size() > properties.getMaxBatchRecords()) {
            throw batchInvalid();
        }
        List<ProductionAuditEntry> batch = new ArrayList<>(entries.size());
        for (ProductionAuditEntry entry : entries) {
            if (entry == null) {
                throw batchInvalid();
            }
            if (entry.sourceDocument().length() > properties.getMaxSourceDocumentChars()) {
                throw batchInvalid();
            }
            entry.canonicalDocument();
            batch.add(entry);
        }
        return List.copyOf(batch);
    }

    private static ProductionAuditException batchInvalid() {
        return new ProductionAuditException(
                ProductionAuditErrorCodes.PRODUCTION_AUDIT_BATCH_INVALID,
                "The durable audit batch is empty, oversized or carries a null or out-of-bounds entry.");
    }

    // ------------------------------------------------------------------ append

    /**
     * Appends every entry of a validated batch into one open SqlSession.
     *
     * <p>Each row is inserted through the one constant mapper statement. An {@code on conflict do nothing}
     * insert that returns 0 means the key was already claimed; the committed document hash is then read to
     * decide whether this is an exact replay (idempotent) or a conflict (refused).
     */
    private void appendSqlSession(SqlSession session, List<ProductionAuditEntry> batch) {
        for (ProductionAuditEntry entry : batch) {
            CampaignProductionAuditLedgerEntity entity = toEntity(entry);
            int inserted = session.insert(INSERT_STATEMENT, entity);
            if (inserted == 0) {
                verifyExactReplaySqlSession(session, entry);
            }
        }
    }

    /**
     * Decides an {@code on conflict do nothing} insert: an exact replay is idempotent, anything else is a
     * conflict.
     *
     * <p>The lookup reads the <em>committed</em> row's canonical document hash. Equal means the same logical
     * event with the same material was already appended and this batch writes nothing for it; different means
     * the same logical event was presented with changed material, which the ledger refuses as a bounded
     * conflict. A missing row cannot happen under the unique constraint, but is still refused rather than
     * assumed away. {@code selectOne} throws when more than one row matches, which is also refused rather
     * than truncated.
     */
    private void verifyExactReplaySqlSession(SqlSession session, ProductionAuditEntry entry) {
        Map<String, String> param = Map.of("logicalKey", entry.logicalKey().value());
        String committedValue;
        try {
            committedValue = session.selectOne(FIND_DOCUMENT_HASH_STATEMENT, param);
        } catch (RuntimeException failure) {
            throw ProductionAuditException.unavailable(failure);
        }
        if (committedValue == null) {
            throw ProductionAuditException.unavailable();
        }
        ContentHash committed;
        try {
            committed = new ContentHash(committedValue);
        } catch (RuntimeException failure) {
            throw ProductionAuditException.unavailable(failure);
        }
        if (!committed.equals(entry.documentHash())) {
            throw new ProductionAuditException(
                    ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFLICT,
                    "The durable audit ledger already holds a different document for this logical event.");
        }
    }

    // ------------------------------------------------------------------ entity mapping

    private static CampaignProductionAuditLedgerEntity toEntity(ProductionAuditEntry entry) {
        CampaignProductionAuditLedgerEntity entity = new CampaignProductionAuditLedgerEntity();
        entity.setLogicalKey(entry.logicalKey().value());
        entity.setEventId(entry.eventId());
        entity.setDomain(entry.type().domain().wireName());
        entity.setEventType(entry.type().wireName());
        entity.setFormatVersion(entry.type().formatVersion());
        entity.setTenantFingerprint(entry.tenantFingerprint() == null ? null : entry.tenantFingerprint().value());
        entity.setProgramFingerprint(entry.programFingerprint() == null ? null : entry.programFingerprint().value());
        entity.setResourceFingerprint(entry.resourceFingerprint() == null ? null : entry.resourceFingerprint().value());
        entity.setDispositionCode(entry.dispositionCode());
        entity.setReasonCode(entry.reasonCode());
        entity.setActionCode(entry.actionCode());
        entity.setTopicCode(entry.topicCode());
        entity.setSourceHash(entry.sourceHash() == null ? null : entry.sourceHash().value());
        entity.setSourceDocument(entry.sourceDocument());
        entity.setDocumentHash(entry.documentHash().value());
        entity.setOccurredAt(entry.occurredAt());
        return entity;
    }

    // ------------------------------------------------------------------ session helpers

    private static void rollbackQuietly(SqlSession session) {
        if (session == null) {
            return;
        }
        try {
            session.rollback();
        } catch (RuntimeException ignored) {
            // Nothing is left to do with the session; the caller already has a bounded failure to raise.
        }
    }

    private static void closeQuietly(SqlSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // Returning the session to the pool is best effort; the batch outcome is already decided.
        }
    }
}