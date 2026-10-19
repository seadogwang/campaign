package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.contracts.Issue;
import com.company.campaign.security.audit.AuditResourceFingerprint;
import com.company.campaign.security.authorization.OwnedResourceRef;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The bounded, shared mechanics every host-only lifecycle service uses to classify a rejection and to name
 * the resource it addressed.
 *
 * <p>It exists so the Apply, Prepare Publish, Publish and fork boundaries cannot drift apart: one rejection
 * is classified, and one resource is fingerprinted, in exactly one place. Nothing here reads a caller value
 * — the disposition and reason are derived from the failure type and its stable code, and the fingerprint is
 * non-reversible.
 */
public final class LifecycleSupport {

    /** The audit reason code recorded when the accepted T02 authorization core reports a denial. */
    public static final String PERMISSION_DENIED_REASON = "PERMISSION_DENIED";

    /** The audit reason code recorded for an absent or cross-boundary resource. */
    public static final String RESOURCE_NOT_FOUND_REASON = "RESOURCE_NOT_FOUND";

    /**
     * The bounded number of safe preview cards one frozen publish artifact may carry.
     *
     * <p>A preparation and the definition it authorizes freeze the same projection, so they share one bound:
     * an artifact that had to carry more cards than this would already be outside the plan format's own
     * scale, and accepting an unbounded list would let a stored artifact grow without limit.
     */
    public static final int MAX_PREVIEW_CARDS = 64;

    /**
     * The bounded number of issues the deterministic final-Manifest validation port may report.
     *
     * <p>Every production step that revalidates a final Manifest — Apply, Prepare Publish and Publish —
     * shares this one bound, so a port that cannot keep its answer bounded is indistinguishable from one
     * that refuses, whichever step asks.
     */
    public static final int MAX_VALIDATION_ISSUES = 256;

    private LifecycleSupport() {
    }

    /**
     * The bounded, deterministic outcome of one final-Manifest revalidation.
     *
     * <p>It is a classification rather than an exception so the three production steps can each report the
     * same decision with their own stable code — Apply a validation failure, Prepare and Publish a stale
     * preparation — without duplicating the bounds.
     */
    public enum ManifestValidationOutcome {

        /** The exact final material passed the frozen policy with no blocking issue. */
        VALID,

        /** The final material carries a blocking issue and may not be consumed. */
        REJECTED,

        /** No trustworthy answer was produced, so the caller must fail closed. */
        UNAVAILABLE
    }

    /**
     * Runs the one bounded deterministic final-Manifest validation every production lifecycle step shares.
     *
     * <p>It fails closed on everything a trustworthy result cannot contain: a missing port, a missing
     * Manifest or context, a thrown exception, a null list, an over-bound list, a null element and any issue
     * whose severity blocks processing. A port that cannot answer is therefore indistinguishable from one
     * that refuses, and the same decision is reached by Apply, Prepare Publish and Publish.
     */
    public static ManifestValidationOutcome validateFinalManifest(
            ManifestValidationPort port,
            ResolvedCampaignManifest manifest,
            TrustedExecutionContext context) {
        if (port == null || manifest == null || context == null) {
            return ManifestValidationOutcome.UNAVAILABLE;
        }
        List<Issue> issues;
        try {
            issues = port.validate(new ManifestValidationRequest(manifest, context));
        } catch (RuntimeException exception) {
            return ManifestValidationOutcome.UNAVAILABLE;
        }
        if (issues == null || issues.size() > MAX_VALIDATION_ISSUES) {
            return ManifestValidationOutcome.UNAVAILABLE;
        }
        boolean blocking = false;
        for (Issue issue : issues) {
            if (issue == null) {
                return ManifestValidationOutcome.UNAVAILABLE;
            }
            if (issue.blocksProcessing()) {
                blocking = true;
            }
        }
        return blocking ? ManifestValidationOutcome.REJECTED : ManifestValidationOutcome.VALID;
    }

    /**
     * The bounded failure of a command presented without its trusted execution context.
     *
     * <p>Such a command cannot be audited — there is no trusted owner to record — but it must still fail
     * closed with a stable code rather than a raw {@code NullPointerException}.
     */
    public static LifecycleException missingTrustedContext() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID,
                "trustedContext",
                "A host lifecycle command requires its trusted execution context.");
    }

    /** Constant-time comparison of two content hashes, so a hash cannot be probed byte by byte. */
    public static boolean constantTimeEquals(ContentHash left, ContentHash right) {
        if (left == null || right == null) {
            return left == right;
        }
        return MessageDigest.isEqual(
                left.value().getBytes(StandardCharsets.UTF_8),
                right.value().getBytes(StandardCharsets.UTF_8));
    }

    /** The outward disposition of one rejected command, derived from the failure alone. */
    public static LifecycleDisposition dispositionOf(RuntimeException failure) {
        if (failure instanceof ResourceNotFoundException) {
            return LifecycleDisposition.NOT_FOUND;
        }
        if (failure instanceof PermissionDeniedException) {
            return LifecycleDisposition.REJECTED;
        }
        if (failure instanceof LifecycleException lifecycle) {
            return switch (lifecycle.code()) {
                case LifecycleErrorCodes.LIFECYCLE_COMPATIBILITY_UNAVAILABLE,
                     LifecycleErrorCodes.LIFECYCLE_VALIDATION_UNAVAILABLE,
                     LifecycleErrorCodes.LIFECYCLE_ACTIVATION_UNAVAILABLE,
                     LifecycleErrorCodes.LIFECYCLE_REFERENCE_ACTIVATION_UNAVAILABLE,
                     LifecycleErrorCodes.LIFECYCLE_FULL_JOURNEY_UNAVAILABLE,
                     LifecycleErrorCodes.LIFECYCLE_FULL_SCHEDULE_UNAVAILABLE,
                     LifecycleErrorCodes.LIFECYCLE_FULL_RUNTIME_UNAVAILABLE ->
                        LifecycleDisposition.UNAVAILABLE;
                case LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID -> LifecycleDisposition.INVALID;
                default -> LifecycleDisposition.REJECTED;
            };
        }
        return LifecycleDisposition.REJECTED;
    }

    /** The stable audit reason code of one rejected command, never a message and never a payload value. */
    public static String reasonOf(RuntimeException failure) {
        if (failure instanceof ResourceNotFoundException) {
            return RESOURCE_NOT_FOUND_REASON;
        }
        if (failure instanceof PermissionDeniedException) {
            return PERMISSION_DENIED_REASON;
        }
        if (failure instanceof LifecycleException lifecycle) {
            return lifecycle.code();
        }
        return LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR;
    }

    /**
     * The non-reversible fingerprint of one server-resolved Campaign resource, or {@code null}.
     *
     * <p>An identifier outside the bounded grammar yields no fingerprint rather than a thrown exception: a
     * diagnostic value must never be able to replace the bounded lifecycle reason it was meant to describe.
     */
    public static AuditResourceFingerprint fingerprintOf(String resourceId, String tenantId, String programId) {
        if (resourceId == null || resourceId.isBlank()
                || tenantId == null || tenantId.isBlank()
                || programId == null || programId.isBlank()) {
            return null;
        }
        return AuditResourceFingerprint.of(
                new OwnedResourceRef(ResourceKind.CAMPAIGN, resourceId, tenantId, programId));
    }
}