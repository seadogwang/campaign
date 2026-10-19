package com.company.campaign.production.audit;

import com.company.campaign.shared.canonical.ContentHash;
import org.apache.ibatis.session.SqlSession;

import java.util.Objects;

/**
 * The explicit transaction token of one staged durable audit batch.
 *
 * <p>It is created by {@link MyBatisPlusProductionAuditLedger#stage(java.util.List)} and names exactly one
 * uncommitted batch through its canonical {@link #batchIdentity()}. The batch is appended inside one open
 * SqlSession and stays uncommitted until the accepted boundary calls {@link #commit()}, so a caller that later
 * discovers a failure can discard <em>exactly</em> this batch with {@link #rollback()} and nothing else.
 *
 * <p>Closing a token that was neither committed nor explicitly rolled back rolls it back: a leaked batch can
 * therefore never be committed by accident. Rollback is a real transaction rollback of uncommitted inserts — it
 * is never modelled by a {@code DELETE}, so it cannot touch a committed audit row.
 *
 * <p>After the unified MyBatis-Plus migration, this class holds only a MyBatis {@link SqlSession}; the JDBC
 * {@code Connection} path has been removed because the only remaining production callers use the SqlSession
 * constructor through {@link MyBatisPlusProductionAuditLedger}.
 */
final class ProductionAuditLedgerTransaction implements AutoCloseable {

    private final SqlSession sqlSession;
    private final ContentHash batchIdentity;

    private boolean finished;

    /** Constructs a transaction wrapping a MyBatis SqlSession. */
    ProductionAuditLedgerTransaction(SqlSession sqlSession, ContentHash batchIdentity) {
        this.sqlSession = Objects.requireNonNull(sqlSession, "sqlSession");
        this.batchIdentity = Objects.requireNonNull(batchIdentity, "batchIdentity");
    }

    /** The canonical identity of this batch; two different batches never share one. */
    ContentHash batchIdentity() {
        return batchIdentity;
    }

    /** Commits exactly this staged batch, then returns the session to the pool. */
    void commit() {
        requireOpen();
        try {
            sqlSession.commit();
            finished = true;
        } catch (RuntimeException failure) {
            rollbackQuietly();
            throw ProductionAuditException.unavailable(failure);
        } finally {
            closeQuietly();
        }
    }

    /** Rolls back exactly this staged batch, then returns the session to the pool. */
    void rollback() {
        requireOpen();
        try {
            sqlSession.rollback();
        } catch (RuntimeException failure) {
            throw ProductionAuditException.unavailable(failure);
        } finally {
            finished = true;
            closeQuietly();
        }
    }

    /** A token that is closed without an accepted boundary is discarded, never committed. */
    @Override
    public void close() {
        if (!finished) {
            rollbackQuietly();
            finished = true;
            closeQuietly();
        }
    }

    private void requireOpen() {
        if (finished) {
            throw new ProductionAuditException(
                    ProductionAuditErrorCodes.PRODUCTION_AUDIT_ROLLBACK_INVALID,
                    "The staged durable audit batch is no longer open.");
        }
    }

    private void rollbackQuietly() {
        try {
            sqlSession.rollback();
        } catch (RuntimeException ignored) {
            // The batch is already failing; a second failure adds nothing and must not disclose anything.
        }
    }

    private void closeQuietly() {
        try {
            sqlSession.close();
        } catch (RuntimeException ignored) {
            // Returning the session to the pool is best effort; the batch outcome is already decided.
        }
    }
}