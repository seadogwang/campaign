package com.company.campaign.builder.testsupport;

import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Test authorization stub that records every request it receives.
 *
 * <p>Recording is what lets a test prove the compiler authorizes exactly once, for the right resource kind,
 * resource identity and trusted context.
 */
public final class RecordingAuthorizationService implements AuthorizationService {

    private final Function<AuthorizationRequest, AuthorizationDecision> responder;
    private final List<AuthorizationRequest> requests = new ArrayList<>();
    private boolean failWithException;

    public RecordingAuthorizationService() {
        this(request -> AuthorizationDecision.allow(ResourceKind.CAMPAIGN, CampaignPermission.CAMPAIGN_EDIT));
    }

    public RecordingAuthorizationService(Function<AuthorizationRequest, AuthorizationDecision> responder) {
        this.responder = responder;
    }

    /** Makes every call raise, to prove a thrown boundary failure fails the compile closed. */
    public RecordingAuthorizationService failingWithException() {
        this.failWithException = true;
        return this;
    }

    @Override
    public AuthorizationDecision authorize(AuthorizationRequest request) {
        requests.add(request);
        if (failWithException) {
            throw new IllegalStateException("authorization backend unavailable");
        }
        return responder.apply(request);
    }

    public List<AuthorizationRequest> requests() {
        return List.copyOf(requests);
    }

    public int callCount() {
        return requests.size();
    }
}