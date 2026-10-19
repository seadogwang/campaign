package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.CanonicalJsonException;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The strict request codec of the Campaign conversation-create route.
 *
 * <p>A create request may carry only one strict, empty JSON object. Every field — including a repeated key, an
 * alternate casing, a nested object, a wrong JSON shape, a non-strict UTF-8 body or an over-size body — is
 * refused before the service is reached, so no client can bind a tenant, program, user, permission,
 * conversation identity, run identity, campaign, revision or trusted instant into a create. The request id is
 * derived exactly as the turn route derives it, so both routes publish the same bounded envelope shape.
 */
public final class CampaignAgentConversationCodec {

    /** The absolute bound on one wire create body. */
    public static final int MAX_REQUEST_BYTES = 1_024;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final CampaignAgentTurnCodec TURN_CODEC = new CampaignAgentTurnCodec();

    /** Requires the one strict empty JSON object a create request may carry. */
    public void decode(byte[] body) {
        if (body == null || body.length == 0) {
            throw refuse(CampaignAgentHttpErrorCodes.REQUEST_MALFORMED, "body", "A request body is required.");
        }
        if (body.length > MAX_REQUEST_BYTES) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_LIMIT_EXCEEDED, "body",
                    "The request body exceeds its declared bound.");
        }
        JsonNode root = readTree(strictUtf8(body));
        if (!root.isObject()) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID, "document",
                    "A conversation-create request must be one JSON object.");
        }
        if (root.size() != 0) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_UNKNOWN_FIELD, "document",
                    "A conversation-create request carries a field that is not part of the create contract.");
        }
    }

    /** The deterministic, non-sensitive request identifier derived from the exact request bytes. */
    public String requestId(byte[] body) {
        return TURN_CODEC.requestId(body);
    }

    private static String strictUtf8(byte[] body) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException failure) {
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_MALFORMED, "encoding", "A request body must be strict UTF-8.");
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return CANONICAL_JSON.readTree(json);
        } catch (CanonicalJsonException failure) {
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(failure.code())) {
                throw refuse(
                        CampaignAgentHttpErrorCodes.REQUEST_DUPLICATE_KEY, "document",
                        "A request body must not repeat an object key.");
            }
            throw refuse(
                    CampaignAgentHttpErrorCodes.REQUEST_MALFORMED, "document",
                    "A request body must be one well-formed JSON document.");
        }
    }

    private static CampaignAgentException refuse(String code, String invariant, String message) {
        return CampaignAgentException.invalid(code, invariant, message);
    }
}