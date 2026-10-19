package com.company.campaign.preview.testsupport;

import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test-only authorization service that separates the separate Preview-sample decision from the Metadata
 * dependency decisions.
 *
 * <p>Every non-sample request is delegated to the accepted fail-closed boundary, so planning and validation
 * keep their real behavior. A {@code PREVIEW_SAMPLE} request is answered by the configured canned outcome —
 * including a null decision, a wrong-kind approval, a wrong-permission approval, a denial or a thrown
 * failure — so a test can prove the sample path fails closed without touching the genuine security core.
 */
public final class PreviewAuthorizationStub implements AuthorizationService {

    private final AuthorizationService delegate;
    private final AuthorizationDecision sampleDecision;
    private final boolean sampleThrows;
    private final AtomicInteger sampleCalls = new AtomicInteger();
    private final List<AuthorizationRequest> sampleRequests = new ArrayList<>();

    private PreviewAuthorizationStub(
            AuthorizationService delegate, AuthorizationDecision sampleDecision, boolean sampleThrows) {
        this.delegate = delegate;
        this.sampleDecision = sampleDecision;
        this.sampleThrows = sampleThrows;
    }

    /** A stub that answers every sample request with the supplied canned decision (possibly null). */
    public static PreviewAuthorizationStub sample(AuthorizationService delegate, AuthorizationDecision decision) {
        return new PreviewAuthorizationStub(delegate, decision, false);
    }

    /** A stub whose sample boundary throws, to prove a thrown failure denies the sample. */
    public static PreviewAuthorizationStub throwingSample(AuthorizationService delegate) {
        return new PreviewAuthorizationStub(delegate, null, true);
    }

    /** A stub that allows exactly the first {@code allowedCalls} Metadata decisions and denies the rest. */
    public static AuthorizationService allowFirstMetadata(int allowedCalls) {
        AtomicInteger remaining = new AtomicInteger(allowedCalls);
        return request -> {
            ResourceKind kind = request == null || request.resource() == null
                    ? null
                    : request.resource().kind();
            boolean allowed = remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0;
            if (allowed && kind != null) {
                return AuthorizationDecision.allow(kind, kind.requiredPermission());
            }
            return AuthorizationDecision.deny(
                    com.company.campaign.security.authorization.AuthorizationDisposition.PERMISSION_DENIED,
                    com.company.campaign.security.authorization.AuthorizationReason.MISSING_PERMISSION,
                    kind,
                    kind == null ? CampaignPermission.CAMPAIGN_READ : kind.requiredPermission());
        };
    }

    @Override
    public AuthorizationDecision authorize(AuthorizationRequest request) {
        ResourceKind kind = request == null || request.resource() == null ? null : request.resource().kind();
        if (kind == ResourceKind.PREVIEW_SAMPLE) {
            sampleCalls.incrementAndGet();
            sampleRequests.add(request);
            if (sampleThrows) {
                throw new IllegalStateException("sample boundary failure with secret-like text");
            }
            return sampleDecision;
        }
        return delegate.authorize(request);
    }

    /** How many times the separate sample decision was requested. */
    public int sampleCallCount() {
        return sampleCalls.get();
    }

    /** The exact sample requests the service issued, in order. */
    public List<AuthorizationRequest> sampleRequests() {
        return List.copyOf(sampleRequests);
    }
}