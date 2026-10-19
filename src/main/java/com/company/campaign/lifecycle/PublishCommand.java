package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.RiskSummary;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The only external input an explicit host Publish confirmation accepts.
 *
 * <p>It identifies the server-stored preparation by identifier and exact hash, supplies a bounded
 * idempotency key, and explicitly acknowledges the exact risk codes the preparation froze. There is no
 * field for a Draft, Manifest, Published Definition, readiness projection, version pin, owner or expiry:
 * the boundary re-loads the preparation and the saved Draft and re-proves every one of those facts.
 *
 * <p>Unknown fields are refused when the record is read, so a forged payload cannot smuggle a mutation
 * document or a trusted fact alongside the accepted fields.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PublishCommand(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String preparationId,
        @NotNull ContentHash preparationHash,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String idempotencyKey,
        @NotNull @Size(max = RiskSummary.MAX_RISK_CODES) List<@NotBlank String> confirmations
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public PublishCommand {
        confirmations = confirmations == null ? null : List.copyOf(confirmations);
    }

    /**
     * The canonical hash of this request's exact publishable intent.
     *
     * <p>It covers the preparation identity, the bounded idempotency key and the complete acknowledgement
     * list in canonical order, and nothing else: it is the value an already-used idempotency key is compared
     * against, so the same key with any structurally different request is a conflict rather than a silent
     * replay.
     *
     * <p>The list is sorted but never de-duplicated. A lossy normalization would collapse
     * {@code [A, A, B]} onto {@code [A, B]}, so a duplicate confirmation could reuse the hash of an accepted
     * request and be replayed as one — bypassing the confirmation validation the service performs once the
     * preparation is consumed. Every structurally different accepted command therefore hashes differently,
     * and the service separately refuses a repeated acknowledgement as an inconsistent request.
     */
    public ContentHash requestHash() {
        Objects.requireNonNull(preparationId, "preparationId");
        Objects.requireNonNull(preparationHash, "preparationHash");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        List<String> accepted = new ArrayList<>(Objects.requireNonNull(confirmations, "confirmations"));
        for (String code : accepted) {
            Objects.requireNonNull(code, "confirmation");
        }
        accepted.sort(String::compareTo);
        return CANONICAL_JSON.hash(new RequestMaterial(
                preparationId,
                preparationHash,
                idempotencyKey,
                List.copyOf(accepted)));
    }

    /** The bounded, safe outward projection of one confirmation's own identifying material. */
    private record RequestMaterial(
            String preparationId,
            ContentHash preparationHash,
            String idempotencyKey,
            List<String> confirmations) {
    }
}