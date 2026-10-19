package com.company.campaign.fullpublish;

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
 * The only external input an explicit host full publication confirmation accepts.
 *
 * <p>It identifies the server-stored full preparation by identifier and exact hash, supplies a bounded
 * idempotency key and explicitly acknowledges the exact risk codes the preparation froze. There is no field for
 * a Draft, Manifest, Journey, Schedule, topology, crate index, readiness decision, runtime-availability pin,
 * owner, DataScope, expiry or version pin: the boundary re-loads the preparation and the saved Draft and
 * re-proves every one of those facts from trusted host state.
 *
 * <p>Unknown fields are refused when the record is read, so a forged payload cannot smuggle a mutation
 * document or a trusted fact alongside the accepted fields.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FullPublishCommand(
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String preparationId,
        @NotNull ContentHash preparationHash,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String idempotencyKey,
        @NotNull @Size(max = RiskSummary.MAX_RISK_CODES) List<@NotBlank String> confirmations
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public FullPublishCommand {
        confirmations = confirmations == null ? null : List.copyOf(confirmations);
    }

    /**
     * The canonical hash of this request's exact publishable intent.
     *
     * <p>It covers the full preparation identity, the bounded idempotency key and the complete acknowledgement
     * list in canonical order, and nothing else: it is the value an already-used idempotency key is compared
     * against, so the same key with any structurally different request is a conflict rather than a silent
     * replay.
     *
     * <p>The list is sorted but never de-duplicated, so a lossy normalization can never collapse a duplicated
     * acknowledgement onto the hash of an accepted request and have it replayed once the preparation was
     * consumed. The boundary separately refuses a repeated acknowledgement as an inconsistent request.
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
                preparationId, preparationHash, idempotencyKey, List.copyOf(accepted)));
    }

    /** The bounded, safe outward projection of one confirmation's own identifying material. */
    private record RequestMaterial(
            String preparationId,
            ContentHash preparationHash,
            String idempotencyKey,
            List<String> confirmations) {
    }
}