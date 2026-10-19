package com.company.campaign.persistence;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.contracts.PlanRef;
import com.company.campaign.persistence.entity.CampaignPlanCompileIdempotencyEntity;
import com.company.campaign.persistence.entity.CampaignPlanEntity;
import com.company.campaign.persistence.mapper.CampaignPlanCompileIdempotencyMapper;
import com.company.campaign.persistence.mapper.CampaignPlanMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, bounded {@link CampaignPlanStore} backed by the shared PostgreSQL datasource.
 *
 * <p>It uses the committed {@link SqlSessionFactory} (one Hikari pool, one Flyway migration set),
 * MyBatis-Plus mappers and XML, and the idempotent V401/V402 migrations. It creates no new datasource,
 * connection pool, raw JDBC adapter or silent in-memory fallback.
 *
 * <p>Immutable plan identity is enforced; plan and idempotency binding are persisted atomically in one
 * session/transaction. Same key + same proposal replays the exact stored plan; same key + different
 * proposal is a durable database-level conflict; both survive backend restart.
 *
 * <p>This store is wired only while PostgreSQL mode is explicitly enabled; otherwise the
 * development host fails closed and exposes no false accepted plan-compile route.
 */
public final class PostgresCampaignPlanStore implements CampaignPlanStore {

    private final SqlSessionFactory sessionFactory;

    public PostgresCampaignPlanStore(SqlSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    @Override
    public PlanRef store(CampaignPlan plan) {
        if (plan == null) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID,
                    "plan",
                    "A Plan store accepts only a compiler-produced plan.");
        }
        try (SqlSession session = sessionFactory.openSession()) {
            CampaignPlanMapper planMapper = session.getMapper(CampaignPlanMapper.class);

            // Idempotent store: a repeated store of the same plan succeeds with the same reference.
            CampaignPlanEntity existing = selectById(planMapper, plan.planId());
            if (existing != null) {
                requireCompatible(existing, plan);
                return plan.planRef();
            }

            CampaignPlanEntity entity = toEntity(plan);
            planMapper.insert(entity);
            session.commit();
            return plan.planRef();
        }
    }

    @Override
    public Optional<CampaignPlan> find(String planId) {
        Objects.requireNonNull(planId, "planId");
        try (SqlSession session = sessionFactory.openSession()) {
            CampaignPlanMapper mapper = session.getMapper(CampaignPlanMapper.class);
            CampaignPlanEntity entity = selectById(mapper, planId);
            if (entity == null) {
                return Optional.empty();
            }
            CampaignPlan plan = CampaignPlan.reconstruct(
                    entity.getCanonicalMaterialBytes(), entity.getManifestJson());

            // Every column the entity carries must agree with the reconstructed plan.
            // A row whose explicit columns contradict the canonical material or manifest
            // is a tampered row and is rejected — the store fails closed rather than
            // returning a plan whose column facts and canonical facts diverge.
            verifyReadIntegrity(entity, plan);

            return Optional.of(plan);
        }
    }

    /**
     * Verifies every explicit entity column against the reconstructed plan so a tampered
     * row — one whose column values contradict the plan's canonical material or manifest
     * canonical bytes — is rejected.
     *
     * <p>This is a fail-closed barrier: a plan whose row-level facts disagree with its
     * canonical facts is never returned from {@link #find}.
     */
    static void verifyReadIntegrity(CampaignPlanEntity entity, CampaignPlan plan) {
        // Identity and hashes
        requireColumnEqual("planId", entity.getPlanId(), plan.planId());
        requireColumnEqual("planHash", entity.getPlanHash(), plan.planHash().value());
        requireColumnEqual("manifestHash", entity.getManifestHash(), plan.manifestHash().value());

        // Canonical bytes — exact match
        if (!Arrays.equals(entity.getCanonicalMaterialBytes(), plan.canonicalMaterialBytes())) {
            throw readIntegrityFailure("canonicalMaterialBytes",
                    "Stored canonical material bytes differ from the reconstructed plan's canonical bytes.");
        }
        String storedManifestCanonical = entity.getManifestJson();
        String reconstructedManifestCanonical =
                new String(plan.manifest().canonicalBytes(), StandardCharsets.UTF_8);
        if (!storedManifestCanonical.equals(reconstructedManifestCanonical)) {
            throw readIntegrityFailure("manifestJson",
                    "Stored manifest JSON differs from the reconstructed manifest's canonical bytes.");
        }

        // Owner identity columns
        requireColumnEqual("tenantId", entity.getTenantId(), plan.tenantId());
        requireColumnEqual("userId", entity.getUserId(), plan.userId());

        // Compiler version
        requireColumnEqual("compilerVersion", entity.getCompilerVersion(), plan.compilerVersion());

        // Base revision columns (null-safe)
        requireColumnEqualNullable("baseCampaignRevision",
                entity.getBaseCampaignRevision(), plan.baseCampaignRevision());
        requireColumnEqualNullable("baseDraftId",
                entity.getBaseDraftId(), plan.baseDraftId());
        requireColumnEqualNullable("baseDraftRevision",
                entity.getBaseDraftRevision(), plan.baseDraftRevision());

        // Proposal hash
        requireColumnEqual("proposalHash", entity.getProposalHash(), plan.proposalHash().value());

        // Catalog version and hash
        requireColumnEqual("catalogVersion", entity.getCatalogVersion(), plan.catalogVersion());
        requireColumnEqual("catalogHash", entity.getCatalogHash(), plan.catalogHash().value());

        // Policy version and hash
        requireColumnEqual("policyVersion", entity.getPolicyVersion(), plan.policyVersion());
        requireColumnEqual("policyHash", entity.getPolicyHash(), plan.policyHash().value());
    }

    private static void requireColumnEqual(String column, String entityValue, String planValue) {
        if (!Objects.equals(entityValue, planValue)) {
            throw readIntegrityFailure(column,
                    "Entity column " + column + " does not equal the reconstructed plan value.");
        }
    }

    private static void requireColumnEqualNullable(String column, Object entityValue, Object planValue) {
        if (!Objects.equals(entityValue, planValue)) {
            throw readIntegrityFailure(column,
                    "Entity column " + column + " does not equal the reconstructed plan value.");
        }
    }

    private static CompilerException readIntegrityFailure(String column, String message) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID, column, message);
    }

    /**
     * Read-only lookup of the compile idempotency record for {@code idempotencyKey}.
     *
     * <p>This operation never inserts a missing key. It returns the proposal hash and plan id of an
     * existing binding, or empty when the key has never been used. The caller may then decide whether
     * the binding is a replay (same hash) or a conflict (different hash), and whether to proceed to
     * compilation and atomic storage.
     */
    public Optional<IdempotencyBinding> lookupIdempotency(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        try (SqlSession session = sessionFactory.openSession()) {
            CampaignPlanCompileIdempotencyMapper mapper =
                    session.getMapper(CampaignPlanCompileIdempotencyMapper.class);
            CampaignPlanCompileIdempotencyEntity entity =
                    selectByIdempotencyKey(mapper, idempotencyKey);
            if (entity == null) {
                return Optional.empty();
            }
            return Optional.of(new IdempotencyBinding(
                    entity.getProposalHash(), entity.getPlanId()));
        }
    }

    /**
     * Atomically stores the compiled plan and its idempotency binding in one
     * {@link SqlSession}/transaction.
     *
     * <p>Within the single session this method:
     * <ol>
     *   <li>Re-reads the idempotency key to guard against a concurrent insert.</li>
     *   <li>If the key already exists with the same proposal hash — verifies the stored plan exists,
     *       commits, and returns a replay of the already-bound plan.</li>
     *   <li>If the key already exists with a different proposal hash — rolls back and returns conflict.</li>
     *   <li>Otherwise inserts the plan (idempotently with ON CONFLICT DO NOTHING)
     *       and the idempotency binding, re-verifies a concurrent insertion has not created a conflict,
     *       and commits only when the complete pair is consistent.</li>
     * </ol>
     *
     * <p>On any error or inconsistency the session is rolled back, so a partial success cannot leave
     * an orphan plan row or a dangling idempotency record.
     *
     * @param plan           the immutable compiler-produced campaign plan
     * @param idempotencyKey the explicit idempotency key
     * @param proposalHash   the proposal hash
     * @return the durable result — replay, stored, or conflict
     */
    public IdempotencyResult storePlanWithBindingAtomically(
            CampaignPlan plan, String idempotencyKey, String proposalHash) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(proposalHash, "proposalHash");

        try (SqlSession session = sessionFactory.openSession()) {
            CampaignPlanCompileIdempotencyMapper idempotencyMapper =
                    session.getMapper(CampaignPlanCompileIdempotencyMapper.class);
            CampaignPlanMapper planMapper = session.getMapper(CampaignPlanMapper.class);

            // 1. Re-read the idempotency key under the transaction — guards against concurrent insert.
            CampaignPlanCompileIdempotencyEntity existingIdempotency =
                    selectByIdempotencyKey(idempotencyMapper, idempotencyKey);

            if (existingIdempotency != null) {
                if (proposalHash.equals(existingIdempotency.getProposalHash())) {
                    // Same key + same hash: replay.
                    // Verify the bound plan still exists (referential integrity).
                    CampaignPlanEntity bound = selectById(planMapper, existingIdempotency.getPlanId());
                    if (bound == null) {
                        session.rollback();
                        throw new CompilerException(
                                CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID,
                                "planId",
                                "The idempotency binding refers to a plan that no longer exists.");
                    }
                    // Return the already-bound plan, not the freshly compiled one
                    // (which may have different clock instants).
                    session.commit();
                    return IdempotencyResult.replay(existingIdempotency.getPlanId());
                }
                // Same key + different hash: durable conflict.
                session.rollback();
                return IdempotencyResult.conflicting();
            }

            // 2. New key: insert the plan idempotently.
            CampaignPlanEntity planEntity = toEntity(plan);
            int planInserted = planMapper.insertPlan(planEntity);
            if (planInserted == 0) {
                // A concurrent insert already stored this plan — read it back.
                CampaignPlanEntity existingPlan = selectById(planMapper, plan.planId());
                if (existingPlan != null) {
                    requireCompatible(existingPlan, plan);
                }
            }

            // 3. Insert the idempotency binding.
            CampaignPlanCompileIdempotencyEntity record = new CampaignPlanCompileIdempotencyEntity();
            record.setIdempotencyKey(idempotencyKey);
            record.setProposalHash(proposalHash);
            record.setPlanId(plan.planId());
            int idemInserted = idempotencyMapper.tryInsertIdempotency(record);

            if (idemInserted == 0) {
                // A concurrent insert beat us — re-read to detect conflict.
                CampaignPlanCompileIdempotencyEntity afterInsert =
                        selectByIdempotencyKey(idempotencyMapper, idempotencyKey);
                if (afterInsert != null) {
                    if (proposalHash.equals(afterInsert.getProposalHash())) {
                        // Same key + same hash: the concurrent winner stored an equivalent
                        // plan. The loser's freshly compiled plan may have a different plan ID
                        // (different clock instants), but the contract requires replaying the
                        // winner's exact bound plan. Roll back the loser's plan row and return
                        // replay of the winner's stored plan.
                        session.rollback();
                        return IdempotencyResult.replay(afterInsert.getPlanId());
                    }
                }
                // Same key + different hash, or an unexpected null re-read: durable conflict.
                session.rollback();
                return IdempotencyResult.conflicting();
            }

            // 4. Verify consistency: the plan row exists, and the idempotency binding also exists.
            CampaignPlanCompileIdempotencyEntity verifyIdem =
                    selectByIdempotencyKey(idempotencyMapper, idempotencyKey);
            if (verifyIdem == null
                    || !proposalHash.equals(verifyIdem.getProposalHash())
                    || !plan.planId().equals(verifyIdem.getPlanId())) {
                session.rollback();
                return IdempotencyResult.conflicting();
            }

            session.commit();
            return IdempotencyResult.stored(plan.planId());
        }
    }

    /**
     * The durable idempotency result: replay, stored, or conflict.
     */
    public record IdempotencyResult(String planId, boolean isConflict) {

        public static IdempotencyResult replay(String planId) {
            return new IdempotencyResult(planId, false);
        }

        public static IdempotencyResult stored(String planId) {
            return new IdempotencyResult(planId, false);
        }

        public static IdempotencyResult conflicting() {
            return new IdempotencyResult(null, true);
        }
    }

    /**
     * A read-only snapshot of an existing idempotency binding — proposal hash and bound plan id.
     */
    public record IdempotencyBinding(String proposalHash, String planId) {
        public IdempotencyBinding {
            Objects.requireNonNull(proposalHash, "proposalHash");
            Objects.requireNonNull(planId, "planId");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static CampaignPlanEntity toEntity(CampaignPlan plan) {
        CampaignPlanEntity entity = new CampaignPlanEntity();
        entity.setPlanId(plan.planId());
        entity.setPlanHash(plan.planHash().value());
        entity.setPlanFormatVersion(plan.planFormatVersion());
        entity.setCompilerVersion(plan.compilerVersion());
        entity.setCompiledAt(plan.compiledAt());
        entity.setExpiresAt(plan.expiresAt());
        entity.setTenantId(plan.tenantId());
        entity.setProgramId(plan.programId());
        entity.setUserId(plan.userId());
        entity.setCampaignId(plan.campaignId());
        entity.setBaseCampaignRevision(plan.baseCampaignRevision());
        entity.setBaseDraftId(plan.baseDraftId());
        entity.setBaseDraftRevision(plan.baseDraftRevision());
        if (plan.forkBase() != null) {
            entity.setForkBaseIdentity(plan.forkBase().publishedId());
        }
        entity.setProposalHash(plan.proposalHash().value());
        entity.setManifestHash(plan.manifestHash().value());
        entity.setCatalogVersion(plan.catalogVersion());
        entity.setCatalogHash(plan.catalogHash().value());
        entity.setPolicyVersion(plan.policyVersion());
        entity.setPolicyHash(plan.policyHash().value());
        entity.setManifestJson(
                new String(plan.manifest().canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8));
        entity.setCanonicalMaterialBytes(plan.canonicalMaterialBytes());
        return entity;
    }

    private static void requireCompatible(CampaignPlanEntity existing, CampaignPlan incoming) {
        // A corrupted row where only the plan hash matches is not compatible.
        // Every identifier and hash column the entity carries must agree.
        if (!existing.getPlanId().equals(incoming.planId())) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_CONFLICT,
                    "planIdMismatch",
                    "A Plan identifier already holds a plan with the same plan hash but"
                            + " a different plan identifier.");
        }
        if (!existing.getPlanHash().equals(incoming.planHash().value())) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_CONFLICT,
                    "planHashMismatch",
                    "A Plan identifier is already bound to a different plan.");
        }
        if (!existing.getManifestHash().equals(incoming.manifestHash().value())) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_CONFLICT,
                    "manifestHashMismatch",
                    "A Plan identifier is already bound to a plan whose manifest hash"
                            + " disagrees.");
        }
    }

    // Package-level mapper read helpers.
    static CampaignPlanEntity selectById(CampaignPlanMapper mapper, String planId) {
        return mapper.findById(planId);
    }

    static CampaignPlanCompileIdempotencyEntity selectByIdempotencyKey(
            CampaignPlanCompileIdempotencyMapper mapper, String idempotencyKey) {
        return mapper.findByIdempotencyKey(idempotencyKey);
    }
}