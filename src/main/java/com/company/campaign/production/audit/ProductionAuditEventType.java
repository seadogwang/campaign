package com.company.campaign.production.audit;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The closed audit type/format-version vocabulary of the durable ledger.
 *
 * <p>One accepted audit port maps to exactly one type, and one type fixes the domain, the wire name, the ledger
 * format version and the exact field set of its canonical source document. The vocabulary is closed on purpose:
 * a document that names an unknown type, or a type that carries a field its own vocabulary does not declare, is
 * refused by the codec rather than stored as an opaque blob.
 *
 * <p>The format version is per type and is a literal in this enum, so a future shape change appends a new type
 * (or a new version) instead of silently reinterpreting an already-committed row.
 */
public enum ProductionAuditEventType {

    /** One safe security decision of {@code com.company.campaign.security.audit.AuditSink}. */
    SECURITY_DECISION(
            ProductionAuditDomain.SECURITY,
            "1.0",
            "action",
            "disposition",
            "reasonCode",
            "durationNanos",
            "occurredAt",
            "tenantFingerprint",
            "programFingerprint",
            "userFingerprint",
            "dataScopeFingerprint",
            "resourceKind",
            "resourceFingerprint",
            "redactedProjectionHash"),

    /** One bounded Campaign conversation turn of {@code CampaignConversationAuditSink}. */
    CONVERSATION_TURN(
            ProductionAuditDomain.CONVERSATION,
            "1.0",
            "conversationFingerprint",
            "tenantFingerprint",
            "programFingerprint",
            "userFingerprint",
            "routeKind",
            "phase",
            "accepted",
            "code",
            "revision",
            "occurredAt"),

    /** One host-only lifecycle command of {@code LifecycleAuditSink}. */
    LIFECYCLE_COMMAND(
            ProductionAuditDomain.LIFECYCLE,
            "1.0",
            "action",
            "disposition",
            "reasonCode",
            "occurredAt",
            "tenantFingerprint",
            "programFingerprint",
            "userFingerprint",
            "dataScopeFingerprint",
            "resourceKind",
            "resourceFingerprint",
            "planHash",
            "contentHash"),

    /** One runtime Outbox/Audit record of {@code RuntimeAuditSink}. */
    RUNTIME_OUTBOX_AUDIT(
            ProductionAuditDomain.RUNTIME,
            "1.0",
            "recordId",
            "formatVersion",
            "tenantFingerprint",
            "topic",
            "reasonCode",
            "subjectKind",
            "subjectFingerprint",
            "executionKeyFingerprint",
            "beforeRevision",
            "afterRevision",
            "occurredAt",
            "recordHash"),

    /** One action Outbox/Audit record of {@code ActionAuditSink}. */
    ACTION_OUTBOX_AUDIT(
            ProductionAuditDomain.ACTION,
            "1.0",
            "recordId",
            "formatVersion",
            "tenantFingerprint",
            "topic",
            "reasonCode",
            "subjectKind",
            "subjectFingerprint",
            "beforeRevision",
            "afterRevision",
            "occurredAt",
            "recordHash");

    /** The exact field set of the canonical source document of this type. */
    private final Set<String> sourceFields;

    private final ProductionAuditDomain domain;
    private final String formatVersion;

    ProductionAuditEventType(
            ProductionAuditDomain domain, String formatVersion, String... sourceFields) {
        this.domain = domain;
        this.formatVersion = formatVersion;
        this.sourceFields = Set.copyOf(new LinkedHashSet<>(java.util.List.of(sourceFields)));
    }

    /** The one audit domain this type belongs to. */
    public ProductionAuditDomain domain() {
        return domain;
    }

    /** The bounded wire name of this type; it is the enum name by construction. */
    public String wireName() {
        return name();
    }

    /** The ledger format version of this type; a literal, never a value from configuration or a request. */
    public String formatVersion() {
        return formatVersion;
    }

    /** The exact field set a canonical source document of this type must carry, and must not exceed. */
    public Set<String> sourceFields() {
        return sourceFields;
    }

    /** Resolves one bounded wire name, refusing an unknown type with the codec's stable code. */
    public static ProductionAuditEventType fromWireName(String wireName) {
        if (wireName != null) {
            for (ProductionAuditEventType type : values()) {
                if (type.name().equals(wireName)) {
                    return type;
                }
            }
        }
        throw ProductionAuditCodec.invalid("eventType");
    }
}