package com.company.campaign.readiness;

import com.company.campaign.persistence.PersistenceAdapters;
import com.company.campaign.persistence.entity.ReadinessAssessmentEntity;
import com.company.campaign.persistence.entity.ReadinessAuditEntity;
import com.company.campaign.persistence.entity.ReadinessEvidenceEntity;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The MyBatis-Plus readiness adapter.
 *
 * <p>It implements the same append-only, idempotent, conflict-detecting and stale-write-safe contract as
 * {@link com.company.campaign.readiness.ReadinessStore}, using MyBatis-Plus mappers and XML-defined SQL instead of raw JDBC. Every
 * property the readiness boundary depends on is preserved:
 * <ul>
 *   <li><strong>explicit transactions</strong> — every write runs on one {@link SqlSession} opened with
 *       {@link ExecutorType#REUSE} and committed or rolled back as one unit, so evidence, assessment, audit
 *       and the revision bump commit together or not at all;</li>
 *   <li><strong>row locking</strong> — the scope row is locked {@code FOR UPDATE} through an XML-mapped
 *       statement, so two concurrent appends cannot both advance the revision;</li>
 *   <li><strong>bounded reads</strong> — every read checks its row count against a configured maximum and
 *       refuses with a stable code rather than silently truncating.</li>
 * </ul>
 *
 * <p>The XML statements are defined in four mapper XML files:
 * <ul>
 *   <li>{@code com/company/campaign/persistence/mapper/ReadinessScopeMapper.xml}</li>
 *   <li>{@code com/company/campaign/persistence/mapper/ReadinessEvidenceMapper.xml}</li>
 *   <li>{@code com/company/campaign/persistence/mapper/ReadinessAssessmentMapper.xml}</li>
 *   <li>{@code com/company/campaign/persistence/mapper/ReadinessAuditMapper.xml}</li>
 * </ul>
 *
 * <p>This class does not import {@code java.sql.*} or {@code javax.sql.*}; instant and hash conversion
 * delegates to {@link PersistenceAdapters}.
 */
public final class MyBatisPlusReadinessStore implements ReadinessStore {

    private static final TypeReference<List<Map<String, Object>>> OBJECT_LIST = new TypeReference<>() {
    };

    // ---------------------------------------------------------------- statement ids

    private static final String NAMESPACE_SCOPE =
            "com.company.campaign.persistence.mapper.ReadinessScopeMapper";
    private static final String NAMESPACE_EVIDENCE =
            "com.company.campaign.persistence.mapper.ReadinessEvidenceMapper";
    private static final String NAMESPACE_ASSESSMENT =
            "com.company.campaign.persistence.mapper.ReadinessAssessmentMapper";
    private static final String NAMESPACE_AUDIT =
            "com.company.campaign.persistence.mapper.ReadinessAuditMapper";

    private static final String SELECT_REVISION =
            NAMESPACE_SCOPE + ".selectRevision";
    private static final String INSERT_SCOPE =
            NAMESPACE_SCOPE + ".insertScope";
    private static final String LOCK_REVISION =
            NAMESPACE_SCOPE + ".lockRevision";
    private static final String UPDATE_REVISION =
            NAMESPACE_SCOPE + ".updateRevision";
    private static final String SELECT_MATERIAL_HASH =
            NAMESPACE_EVIDENCE + ".selectMaterialHash";
    private static final String INSERT_EVIDENCE =
            NAMESPACE_EVIDENCE + ".insertEvidence";
    private static final String SELECT_EVIDENCE =
            NAMESPACE_EVIDENCE + ".selectEvidence";
    private static final String SELECT_ASSESSMENT_HASH =
            NAMESPACE_ASSESSMENT + ".selectAssessmentHash";
    private static final String INSERT_ASSESSMENT =
            NAMESPACE_ASSESSMENT + ".insertAssessment";
    private static final String SELECT_LATEST_ASSESSMENT =
            NAMESPACE_ASSESSMENT + ".selectLatestAssessment";
    private static final String INSERT_AUDIT =
            NAMESPACE_AUDIT + ".insertAudit";
    private static final String SELECT_AUDIT =
            NAMESPACE_AUDIT + ".selectAudit";

    // ---------------------------------------------------------------- state

    private final SqlSessionFactory sqlSessionFactory;
    private final int maxRows;

    /**
     * Convenience constructor defaulting the evidence read bound to
     * {@link ReadinessLimits#MAX_EVIDENCE_PER_READ}.
     */
    public MyBatisPlusReadinessStore(SqlSessionFactory sqlSessionFactory) {
        this(sqlSessionFactory, ReadinessLimits.MAX_EVIDENCE_PER_READ);
    }

    /**
     * @param sqlSessionFactory the MyBatis session factory wired to the readiness database
     * @param maxRows           a positive read bound not exceeding
     *                          {@link ReadinessLimits#MAX_EVIDENCE_PER_READ}
     */
    public MyBatisPlusReadinessStore(SqlSessionFactory sqlSessionFactory, int maxRows) {
        this.sqlSessionFactory = ReadinessSupport.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
        if (maxRows < 1 || maxRows > ReadinessLimits.MAX_EVIDENCE_PER_READ) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                    "A MyBatis-Plus readiness read bound must be positive and within the configured maximum.");
        }
        this.maxRows = maxRows;
    }

    // ---------------------------------------------------------------- ReadinessStore

    @Override
    public long revision(EvidenceScope scope) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        return inTransaction(session -> {
            ensureScopeRow(session, key);
            return readRevision(session, key, SELECT_REVISION);
        });
    }

    @Override
    public EvidenceAppendResult appendEvidence(
            EvidenceScope scope,
            RequirementEvidence evidence,
            ReadinessAuditEvent audit,
            long expectedRevision) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        RequirementEvidence record = ReadinessSupport.requireNonNull(evidence, "evidence");
        ReadinessAuditEvent event = ReadinessSupport.requireNonNull(audit, "audit");
        if (record.environment() != key.environment()) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_EVIDENCE_ORIGIN_REJECTED,
                    "Evidence recorded in one environment cannot be stored in another scope.");
        }
        return inTransaction(session -> {
            ensureScopeRow(session, key);
            long committed = readRevision(session, key, LOCK_REVISION);
            if (committed != expectedRevision) {
                return new EvidenceAppendResult(ReadinessDisposition.STALE_REVISION, record, committed);
            }
            Optional<String> existing = readMaterialHash(session, key, record.identityHash());
            if (existing.isPresent()) {
                boolean same = existing.get().equals(record.materialHash().value());
                return new EvidenceAppendResult(
                        same ? ReadinessDisposition.DUPLICATE : ReadinessDisposition.CONFLICT,
                        record,
                        committed);
            }
            long next = committed + 1;
            insertEvidence(session, key, record, next);
            insertAudit(session, key, event.withDisposition(ReadinessAuditDisposition.RECORDED), next);
            bumpRevision(session, key, next, record.observedAt());
            return new EvidenceAppendResult(ReadinessDisposition.RECORDED, record, next);
        });
    }

    @Override
    public AssessmentAppendResult appendAssessment(
            EvidenceScope scope,
            ReadinessAssessment assessment,
            ReadinessAuditEvent audit,
            long expectedRevision) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        ReadinessAssessment value = ReadinessSupport.requireNonNull(assessment, "assessment");
        ReadinessAuditEvent event = ReadinessSupport.requireNonNull(audit, "audit");
        if (value.environment() != key.environment()) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "An assessment of one environment cannot be stored in another scope.");
        }
        return inTransaction(session -> {
            ensureScopeRow(session, key);
            long committed = readRevision(session, key, LOCK_REVISION);
            if (committed != expectedRevision) {
                return new AssessmentAppendResult(ReadinessDisposition.STALE_REVISION, value, committed);
            }
            Optional<String> existing = readAssessmentHash(session, key, value.assessmentId());
            if (existing.isPresent()) {
                boolean same = existing.get().equals(value.assessmentHash().value());
                return new AssessmentAppendResult(
                        same ? ReadinessDisposition.DUPLICATE : ReadinessDisposition.CONFLICT,
                        value,
                        committed);
            }
            long next = committed + 1;
            insertAssessment(session, key, value, next);
            insertAudit(session, key, event.withDisposition(ReadinessAuditDisposition.RECORDED), next);
            bumpRevision(session, key, next, value.evaluatedAt());
            return new AssessmentAppendResult(ReadinessDisposition.RECORDED, value, next);
        });
    }

    @Override
    public List<RequirementEvidence> evidence(EvidenceScope scope, ReadinessLevel level) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        ReadinessLevel requested = ReadinessSupport.requireNonNull(level, "level");
        List<RequirementEvidence> records = new ArrayList<>();
        inTransaction(session -> {
            for (ReadinessRequirement requirement : ReadinessCatalog.requirementsFor(requested)) {
                records.addAll(readEvidenceFor(session, key, requirement));
                if (records.size() > maxRows) {
                    throw new ReadinessException(
                            ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                            "The readiness evidence read for this level exceeds its configured bound.");
                }
            }
            return null;
        });
        return List.copyOf(records);
    }

    @Override
    public Optional<ReadinessAssessment> latestAssessment(EvidenceScope scope, ReadinessLevel level) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        ReadinessLevel requested = ReadinessSupport.requireNonNull(level, "level");
        return inTransaction(session -> {
            Map<String, Object> params = scopeParams(key);
            params.put("level", requested.name());
            List<ReadinessAssessmentEntity> entities = session.selectList(SELECT_LATEST_ASSESSMENT, params);
            if (entities.size() > 1) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                        "A readiness assessment read exceeded its configured bound.");
            }
            if (entities.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toAssessment(entities.get(0), key));
        });
    }

    /** The bounded audit trail of one scope, for a host that exposes it internally. */
    public List<ReadinessAuditEvent> auditEvents(EvidenceScope scope, int limit) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        if (limit < 1 || limit > ReadinessLimits.MAX_AUDIT_PER_READ) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                    "A MyBatis-Plus readiness audit read bound must be positive and within the configured maximum.");
        }
        return inTransaction(session -> {
            Map<String, Object> params = scopeParams(key);
            List<ReadinessAuditEntity> entities = session.selectList(SELECT_AUDIT, params);
            if (entities.size() > limit) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                        "A readiness audit read exceeded its configured bound.");
            }
            return entities.stream()
                    .map(entity -> toAudit(entity, key))
                    .toList();
        });
    }

    // ------------------------------------------------------------------ transaction

    private <T> T inTransaction(Function<SqlSession, T> work) {
        SqlSession session = sqlSessionFactory.openSession(ExecutorType.REUSE);
        try {
            T result = work.apply(session);
            session.commit();
            return result;
        } catch (ReadinessException exception) {
            rollbackQuietly(session);
            throw exception;
        } catch (RuntimeException exception) {
            rollbackQuietly(session);
            throw unavailable(exception);
        } finally {
            closeQuietly(session);
        }
    }

    // ------------------------------------------------------------------ scope row

    private static void ensureScopeRow(SqlSession session, EvidenceScope scope) {
        Map<String, Object> params = scopeParams(scope);
        params.put("revision", 0L);
        params.put("updatedAt", java.time.Instant.EPOCH);
        session.insert(INSERT_SCOPE, params);
    }

    private static long readRevision(SqlSession session, EvidenceScope scope, String statementId) {
        Map<String, Object> params = scopeParams(scope);
        Long revision = session.selectOne(statementId, params);
        if (revision == null) {
            return 0L;
        }
        if (revision < 0) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_STORE_UNAVAILABLE,
                    "A readiness scope row carries no committed revision.");
        }
        return revision;
    }

    // ------------------------------------------------------------------ idempotency hashes

    private static Optional<String> readMaterialHash(
            SqlSession session, EvidenceScope scope, ContentHash identity) {
        Map<String, Object> params = scopeParams(scope);
        params.put("identityHash", identity.value());
        String hash = session.selectOne(SELECT_MATERIAL_HASH, params);
        return Optional.ofNullable(hash);
    }

    private static Optional<String> readAssessmentHash(
            SqlSession session, EvidenceScope scope, String assessmentId) {
        Map<String, Object> params = scopeParams(scope);
        params.put("assessmentId", assessmentId);
        String hash = session.selectOne(SELECT_ASSESSMENT_HASH, params);
        return Optional.ofNullable(hash);
    }

    // ------------------------------------------------------------------ inserts

    private static void insertEvidence(
            SqlSession session, EvidenceScope scope, RequirementEvidence record, long revision) {
        Map<String, Object> params = scopeParams(scope);
        params.put("evidenceId", record.evidenceId());
        params.put("formatVersion", record.formatVersion());
        params.put("requirementCode", record.requirement().code());
        params.put("status", record.status().name());
        params.put("origin", record.origin().name());
        params.put("subject", record.subject());
        params.put("versionRef", record.version());
        params.put("evidenceHash", record.evidenceHash().value());
        params.put("evidenceReference", record.evidenceReference());
        params.put("productionGateEvidence", record.productionGateEvidence());
        params.put("reasonCode", record.reasonCode());
        params.put("observedAt", record.observedAt());
        params.put("expiresAt", record.expiresAt());
        params.put("identityHash", record.identityHash().value());
        params.put("materialHash", record.materialHash().value());
        params.put("committedRevision", revision);
        session.insert(INSERT_EVIDENCE, params);
    }

    private static void insertAssessment(
            SqlSession session, EvidenceScope scope, ReadinessAssessment assessment, long revision) {
        Map<String, Object> params = scopeParams(scope);
        params.put("assessmentId", assessment.assessmentId());
        params.put("formatVersion", assessment.formatVersion());
        params.put("level", assessment.level().name());
        params.put("status", assessment.status().name());
        params.put("evaluatedAt", assessment.evaluatedAt());
        params.put("acceptedEvidenceJson", PersistenceAdapters.canonicalJsonWrite(
                assessment.acceptedEvidence().stream().map(EvidenceReference::material).toList()));
        params.put("blockersJson", PersistenceAdapters.canonicalJsonWrite(
                assessment.blockers().stream().map(ReadinessBlocker::material).toList()));
        params.put("assessmentHash", assessment.assessmentHash().value());
        params.put("committedRevision", revision);
        session.insert(INSERT_ASSESSMENT, params);
    }

    private static void insertAudit(
            SqlSession session, EvidenceScope scope, ReadinessAuditEvent audit, long revision) {
        Map<String, Object> params = scopeParams(scope);
        params.put("formatVersion", audit.formatVersion());
        params.put("action", audit.action().name());
        params.put("disposition", audit.disposition().name());
        params.put("reasonCode", audit.reasonCode());
        params.put("occurredAt", audit.occurredAt());
        params.put("actorRef", audit.actorRef());
        params.put("requestCorrelation", audit.requestCorrelation());
        params.put("requirementCode", audit.requirementCode());
        params.put("statusTransition", audit.statusTransition());
        params.put("evidenceIdentityHash",
                PersistenceAdapters.contentHashValue(audit.evidenceIdentityHash()));
        params.put("subjectFingerprint",
                PersistenceAdapters.contentHashValue(audit.subjectFingerprint()));
        params.put("assessmentHash",
                PersistenceAdapters.contentHashValue(audit.assessmentHash()));
        params.put("committedRevision", revision);
        session.insert(INSERT_AUDIT, params);
    }

    // ------------------------------------------------------------------ revision bump

    private static void bumpRevision(
            SqlSession session, EvidenceScope scope, long revision, java.time.Instant at) {
        Map<String, Object> params = scopeParams(scope);
        params.put("revision", revision);
        params.put("updatedAt", at);
        session.update(UPDATE_REVISION, params);
    }

    // ------------------------------------------------------------------ evidence read

    private List<RequirementEvidence> readEvidenceFor(
            SqlSession session, EvidenceScope scope, ReadinessRequirement requirement) {
        Map<String, Object> params = scopeParams(scope);
        params.put("requirementCode", requirement.code());
        List<ReadinessEvidenceEntity> entities = session.selectList(SELECT_EVIDENCE, params);
        if (entities.size() > maxRows) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                    "A readiness evidence read exceeded its configured bound.");
        }
        return entities.stream()
                .map(entity -> toEvidence(entity, scope))
                .toList();
    }

    // ------------------------------------------------------------------ entity → domain

    private static RequirementEvidence toEvidence(ReadinessEvidenceEntity entity, EvidenceScope scope) {
        return new RequirementEvidence(
                entity.getFormatVersion(),
                entity.getEvidenceId(),
                ReadinessCatalog.require(entity.getRequirementCode()),
                EvidenceStatus.valueOf(entity.getStatus()),
                EvidenceOrigin.valueOf(entity.getOrigin()),
                scope.environment(),
                entity.getSubject(),
                entity.getVersionRef(),
                new ContentHash(entity.getEvidenceHash()),
                entity.getEvidenceReference(),
                Boolean.TRUE.equals(entity.getProductionGateEvidence()),
                entity.getObservedAt(),
                entity.getExpiresAt(),
                entity.getReasonCode(),
                new ContentHash(entity.getIdentityHash()),
                new ContentHash(entity.getMaterialHash()));
    }

    private static ReadinessAssessment toAssessment(ReadinessAssessmentEntity entity, EvidenceScope scope) {
        List<EvidenceReference.ReferenceMaterial> accepted;
        List<ReadinessBlocker.BlockerMaterial> blockers;
        try {
            accepted = PersistenceAdapters.canonicalJsonRead(
                    entity.getAcceptedEvidenceJson(), OBJECT_LIST).stream()
                    .map(MyBatisPlusReadinessStore::toReferenceMaterial)
                    .toList();
            blockers = PersistenceAdapters.canonicalJsonRead(
                    entity.getBlockersJson(), OBJECT_LIST).stream()
                    .map(MyBatisPlusReadinessStore::toBlockerMaterial)
                    .toList();
        } catch (RuntimeException exception) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_STORE_UNAVAILABLE,
                    "A persisted readiness assessment document is unreadable.",
                    Map.of(),
                    exception);
        }
        List<EvidenceReference> references = accepted.stream()
                .map(material -> new EvidenceReference(
                        material.evidenceId(),
                        ReadinessCatalog.require(material.requirementCode()),
                        EvidenceOrigin.valueOf(material.origin()),
                        material.identityHash(),
                        material.materialHash()))
                .toList();
        List<ReadinessBlocker> ordered = blockers.stream()
                .map(material -> new ReadinessBlocker(
                        material.ordinal(),
                        material.code(),
                        ReadinessCatalog.require(material.requirementCode()),
                        ReadinessLevel.valueOf(material.level())))
                .toList();
        return new ReadinessAssessment(
                entity.getFormatVersion(),
                entity.getAssessmentId(),
                ReadinessLevel.valueOf(entity.getLevel()),
                ReadinessStatus.valueOf(entity.getStatus()),
                scope.environment(),
                entity.getEvaluatedAt(),
                references,
                ordered,
                PersistenceAdapters.contentHashFromString(entity.getAssessmentHash()));
    }

    private static ReadinessAuditEvent toAudit(ReadinessAuditEntity entity, EvidenceScope scope) {
        return new ReadinessAuditEvent(
                entity.getFormatVersion(),
                ReadinessAuditAction.valueOf(entity.getAction()),
                ReadinessAuditDisposition.valueOf(entity.getDisposition()),
                entity.getReasonCode(),
                entity.getOccurredAt(),
                scope.tenantId(),
                scope.programId(),
                scope.environment(),
                entity.getActorRef(),
                entity.getRequestCorrelation(),
                entity.getRequirementCode(),
                entity.getStatusTransition(),
                PersistenceAdapters.contentHashFromString(entity.getEvidenceIdentityHash()),
                PersistenceAdapters.contentHashFromString(entity.getSubjectFingerprint()),
                PersistenceAdapters.contentHashFromString(entity.getAssessmentHash()));
    }

    private static EvidenceReference.ReferenceMaterial toReferenceMaterial(Map<String, Object> raw) {
        return new EvidenceReference.ReferenceMaterial(
                (String) raw.get("evidenceId"),
                (String) raw.get("requirementCode"),
                (String) raw.get("origin"),
                new ContentHash((String) raw.get("identityHash")),
                new ContentHash((String) raw.get("materialHash")));
    }

    private static ReadinessBlocker.BlockerMaterial toBlockerMaterial(Map<String, Object> raw) {
        Object ordinal = raw.get("ordinal");
        return new ReadinessBlocker.BlockerMaterial(
                ordinal instanceof Number number ? number.intValue() : -1,
                (String) raw.get("code"),
                (String) raw.get("requirementCode"),
                (String) raw.get("level"));
    }

    // ------------------------------------------------------------------ parameter helpers

    private static Map<String, Object> scopeParams(EvidenceScope scope) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", scope.tenantId());
        params.put("programId", scope.programId());
        params.put("environment", scope.environment().name());
        return params;
    }

    // ------------------------------------------------------------------ rollback & close

    /**
     * Rolls the unit back, ignoring a rollback failure: the caller already has the original failure to
     * report, and a rollback that cannot run must not mask it.
     */
    private static void rollbackQuietly(SqlSession session) {
        if (session == null) {
            return;
        }
        try {
            session.rollback();
        } catch (RuntimeException ignored) {
            // The transaction is already unusable; the caller reports the original failure.
        }
    }

    /** Closes the session, ignoring a close failure: it cannot change the outcome already observed. */
    private static void closeQuietly(SqlSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // A close failure cannot change the outcome the caller already observed.
        }
    }

    /**
     * The one non-disclosing failure for every way the persistence boundary can fail.
     *
     * <p>A missing mapper XML, an unreachable server, a timeout and a constraint violation all become the
     * same bounded code, so a caller cannot probe which part of the boundary is unavailable.
     */
    private static ReadinessException unavailable(Exception cause) {
        return new ReadinessException(
                ReadinessErrorCodes.READINESS_STORE_UNAVAILABLE,
                "The readiness persistence boundary could not complete the transaction.",
                Map.of(),
                cause);
    }
}