package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ActionProviderException;
import com.company.campaign.action.ActionSupport;
import com.company.campaign.action.ProviderOutcome;
import com.company.campaign.action.ProviderRequest;
import com.company.campaign.action.ProviderResult;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One small strict wire contract, shared by both production Provider adapters.
 *
 * <p>The request carries only the allowlisted {@link ProviderRequest} projection, its existing hashes and the
 * exact business idempotency key. {@link #KEY_HEADER} carries the same key as one documented header, and
 * {@link #assertHeaderMatchesBody} re-proves header/body equality <em>before</em> any transport happens, so a
 * mismatched key can never be sent. A reconciliation query sends only the encoded key and never a body.
 *
 * <p>The response contract is exact: an unexpected or duplicate field, a wrong contract version, action kind or
 * key, an unknown outcome, a reference or instant on a non-applied outcome, a non-identifier reference, a
 * non-machine reason, malformed UTF-8, an unsafe control character, a non-JSON or non-object document and an
 * empty body are all refused with one bounded, non-disclosing code. A {@code SUCCEEDED} result is never invented
 * from an HTTP status: it exists only when the exact response body states the applied outcome and carries the
 * bounded Provider reference the outcome requires.
 */
public final class ProductionProviderWireContract {

    /** The one documented header the exact business idempotency key is also sent in. */
    public static final String KEY_HEADER = "Idempotency-Key";

    /** The one documented header the exact contract version is also sent in. */
    public static final String CONTRACT_HEADER = "X-Campaign-Contract-Version";

    /** The one accepted content type of a strict response. */
    public static final String CONTENT_TYPE = "application/json";

    private static final CanonicalJson JSON = new CanonicalJson();

    /** Control characters that must never appear raw in a returned document. */
    private static final Pattern UNSAFE_CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "contractVersion", "actionKind", "idempotencyKey", "outcome", "providerReference",
            "acceptedInstant", "reasonCode");

    private ProductionProviderWireContract() {
    }

    // ------------------------------------------------------------------ request

    /**
     * The one bounded request body of one submission: only the allowlisted projection, its hashes and the key.
     *
     * <p>Absent optional projection fields are omitted rather than sent as {@code null}, so the body is exactly
     * the present material and nothing else.
     */
    public static byte[] submissionBody(ProviderRequest request, String idempotencyKey) {
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", ProductionProviderProperties.CONTRACT_VERSION);
        body.put("actionKind", request.actionKind().code());
        body.put("idempotencyKey", idempotencyKey);
        body.put("requestId", request.requestId());
        body.put("requestHash", request.requestHash());
        body.put("providerRequestHash", request.providerRequestHash());
        put(body, "channelCode", request.channelCode());
        put(body, "templateLogicalId", request.templateLogicalId());
        put(body, "templateVersionId", request.templateVersionId());
        put(body, "templateContentHash", request.templateContentHash());
        put(body, "rewardLogicalId", request.rewardLogicalId());
        put(body, "rewardVersionId", request.rewardVersionId());
        put(body, "rewardContentHash", request.rewardContentHash());
        put(body, "rewardQuantity", request.rewardQuantity());
        put(body, "rewardCurrency", request.rewardCurrency());
        put(body, "rewardFaceValue",
                request.rewardFaceValue() == null ? null : request.rewardFaceValue().toPlainString());
        put(body, "recipientRef", request.recipientRef());
        put(body, "providerRoutingPin", request.providerRoutingPin());
        return JSON.write(body);
    }

    private static void put(Map<String, Object> body, String field, Object value) {
        if (value != null) {
            body.put(field, value);
        }
    }

    /**
     * Re-proves that the documented key header and the body's key are byte-identical, before any transport.
     *
     * <p>It fails closed with one bounded code rather than sending a request whose header and body disagree.
     */
    public static void assertHeaderMatchesBody(String headerKey, byte[] body) {
        JsonNode root = parseObjectText(decodeUtf8(body));
        JsonNode key = root.get("idempotencyKey");
        if (headerKey == null || key == null || !key.isTextual() || !headerKey.equals(key.asText())) {
            throw new ActionProviderException(ProductionProviderErrorCodes.PROVIDER_KEY_MISMATCH);
        }
    }

    // ------------------------------------------------------------------ response

    /** Converts one accepted strict response into the existing bounded {@link ProviderResult}. */
    public static ProviderResult responseResult(byte[] response, ActionKind expectedKind, String expectedKey) {
        ActionSupport.requireKey(expectedKey, "idempotencyKey");
        String text = decodeUtf8(response);
        if (UNSAFE_CONTROL.matcher(text).find()) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_UNSAFE);
        }
        JsonNode root = parseObjectText(text);
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            if (!RESPONSE_FIELDS.contains(names.next())) {
                throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
            }
        }
        String version = requiredText(root, "contractVersion");
        if (!ProductionProviderProperties.CONTRACT_VERSION.equals(version)) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
        }
        if (!expectedKind.code().equals(requiredText(root, "actionKind"))) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
        }
        if (!expectedKey.equals(requiredText(root, "idempotencyKey"))) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
        }
        ProviderOutcome outcome = outcome(requiredText(root, "outcome"));
        String reasonCode = requiredText(root, "reasonCode");
        requireMachineCode(reasonCode);

        JsonNode referenceNode = root.get("providerReference");
        JsonNode instantNode = root.get("acceptedInstant");
        if (!outcome.applied()) {
            if (referenceNode != null || instantNode != null) {
                // A non-applied outcome must not claim a reference or an accepted instant it denies.
                throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
            }
            return ProviderResult.of(expectedKey, outcome, null, null, reasonCode);
        }
        if (referenceNode == null || !referenceNode.isTextual()) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
        }
        String providerReference = referenceNode.asText();
        requireIdentifier(providerReference);
        Instant acceptedInstant = null;
        if (instantNode != null) {
            if (!instantNode.isTextual()) {
                throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
            }
            try {
                acceptedInstant = Instant.parse(instantNode.asText());
            } catch (DateTimeException failure) {
                throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
            }
        }
        try {
            return ProviderResult.of(expectedKey, outcome, providerReference, acceptedInstant, reasonCode);
        } catch (RuntimeException failure) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
        }
    }

    private static JsonNode parseObjectText(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (RuntimeException failure) {
            throw new ActionProviderException(ProductionProviderErrorCodes.PROVIDER_RESPONSE_MALFORMED);
        }
        if (root == null || !root.isObject()) {
            throw new ActionProviderException(ProductionProviderErrorCodes.PROVIDER_RESPONSE_MALFORMED);
        }
        return root;
    }

    private static String decodeUtf8(byte[] document) {
        if (document == null || document.length == 0) {
            throw new ActionProviderException(ProductionProviderErrorCodes.PROVIDER_RESPONSE_EMPTY);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(document))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new ActionProviderException(ProductionProviderErrorCodes.PROVIDER_RESPONSE_NOT_UTF8);
        }
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
        }
        return node.asText();
    }

    private static ProviderOutcome outcome(String value) {
        for (ProviderOutcome candidate : ProviderOutcome.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
    }

    private static void requireIdentifier(String value) {
        if (!ActionSupport.IDENTIFIER.matcher(value).matches()) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
        }
    }

    private static void requireMachineCode(String value) {
        if (!ActionSupport.MACHINE_CODE.matcher(value).matches()) {
            throw contractFailure(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID);
        }
    }

    private static ActionProviderException contractFailure(String code) {
        return new ActionProviderException(code);
    }
}