package com.company.campaign.segment.intent;

import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import org.junit.jupiter.api.Test;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 4: a registered-default citation requires the exact existing registry revision and cannot be
 * forged in model output.
 */
class RegisteredDefaultBoundaryTest {

    private final SegmentIntentCodec codec = new SegmentIntentCodec();
    private final String hash = IntentFixtures.hash("CNY").value();

    @Test
    void exactRegisteredRevisionIsAccepted() {
        SegmentIntent intent = codec.parse(
                json("reporting.currency", "1", hash), "A😀B", IntentFixtures.currencyRegistry());
        assertThat(intent.clauses()).hasSize(1);
        assertThat(intent.clauses().get(0).provenance())
                .isInstanceOf(IntentProvenance.FromRegisteredDefault.class);
    }

    @Test
    void forgedDefaultIdCannotBeInvented() {
        assertFailsWithCode(
                () -> codec.parse(json("forged.default", "1", hash), "A😀B", IntentFixtures.currencyRegistry()),
                IntentErrorCodes.DEFAULT_UNKNOWN);
    }

    @Test
    void driftedVersionFailsClosed() {
        assertFailsWithCode(
                () -> codec.parse(json("reporting.currency", "2", hash), "A😀B", IntentFixtures.currencyRegistry()),
                IntentErrorCodes.DEFAULT_REVISION_MISMATCH);
    }

    @Test
    void driftedContentHashFailsClosed() {
        assertFailsWithCode(
                () -> codec.parse(
                        json("reporting.currency", "1", IntentFixtures.hash("USD").value()),
                        "A😀B", IntentFixtures.currencyRegistry()),
                IntentErrorCodes.DEFAULT_REVISION_MISMATCH);
    }

    @Test
    void missingRegistryCannotVerifyADefault() {
        assertFailsWithCode(
                () -> codec.parse(json("reporting.currency", "1", hash), "A😀B", null),
                IntentErrorCodes.DEFAULT_UNKNOWN);
    }

    @Test
    void forgedRegisteredCurrencyCannotBeInvented() {
        SegmentIntentCodec codec = this.codec;
        assertFailsWithCode(
                () -> codec.parse(
                        currencyJson("forged.default", "1", IntentFixtures.hash("CNY").value()),
                        "A😀B", IntentFixtures.currencyRegistry()),
                IntentErrorCodes.DEFAULT_UNKNOWN);
        assertFailsWithCode(
                () -> codec.parse(
                        currencyJson("reporting.currency", "2", IntentFixtures.hash("CNY").value()),
                        "A😀B", IntentFixtures.currencyRegistry()),
                IntentErrorCodes.DEFAULT_REVISION_MISMATCH);
    }

    @Test
    void registryHashAndVersionArePinnedAndChangeWithContent() {
        RegisteredDefaultSource base = IntentFixtures.currencyRegistry();
        RegisteredDefaultSource drifted = IntentFixtures.driftedRegistry();
        assertThat(base.registryHash()).isNotEqualTo(drifted.registryHash());
        assertThat(base.registryVersion()).isNotEqualTo(drifted.registryVersion());
        assertThat(base.registryHash().value()).startsWith("sha256:");
    }

    private static String currencyJson(String defaultId, String version, String contentHash) {
        return "{"
                + "\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"X\",\"negated\":false,"
                + "\"currency\":{\"mode\":\"REGISTERED_DEFAULT\",\"defaultId\":\"" + defaultId + "\","
                + "\"version\":\"" + version + "\",\"contentHash\":\"" + contentHash + "\"},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"😀\",\"start\":1,\"end\":3},"
                + "\"candidateHints\":[],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    private static String json(String defaultId, String version, String contentHash) {
        return "{"
                + "\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"X\",\"negated\":false,"
                + "\"provenance\":{\"type\":\"REGISTERED_DEFAULT\",\"defaultId\":\"" + defaultId + "\","
                + "\"version\":\"" + version + "\",\"contentHash\":\"" + contentHash + "\"},"
                + "\"candidateHints\":[],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }
}