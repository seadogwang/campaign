package com.company.campaign.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic contract test for the development-only business-resource seed.
 *
 * <p>The seed at {@code campaign-conversation-dev-seed/campaign-business-resources.sql} is the single source of
 * truth for the opt-in development host's default {@code campaign_business_resource} rows. This test parses the
 * file and asserts that the selectable catalog converges to exactly the resources authorised for the live-test
 * tenant/program: three communication channels (SMS, EMAIL, WECHAT), four trigger events (MEMBER_REGISTERED,
 * POINTS_ISSUED, MEMBER_TIER_UPGRADED, MEMBER_TIER_DOWNGRADED), and one promotion (COUPON). Previously seeded
 * defaults (WHATSAPP, POINTS_EXPIRING) must remain registered but fully disabled so a re-run converges old rows
 * safely.
 *
 * <p>The test does not connect to PostgreSQL — it guards the seed file itself so that an operator who applies
 * the script gets the intended catalog regardless of the database state.
 */
class CampaignBusinessResourceSeedTest {

    private static final Path SEED_FILE =
            Path.of("src", "main", "resources", "db",
                    "campaign-conversation-dev-seed", "campaign-business-resources.sql");

    private static final String EXPECTED_TENANT = "tenant-dev";
    private static final String EXPECTED_PROGRAM = "program-dev";

    /** Resource codes that must be selectable: enabled, runtime-available, and carrying an integration key. */
    private static final Set<String> EXPECTED_SELECTABLE_CHANNELS = Set.of("SMS", "EMAIL", "WECHAT");
    private static final Set<String> EXPECTED_SELECTABLE_EVENTS = Set.of(
            "MEMBER_REGISTERED", "POINTS_ISSUED", "MEMBER_TIER_UPGRADED", "MEMBER_TIER_DOWNGRADED");
    private static final Set<String> EXPECTED_SELECTABLE_PROMOTIONS = Set.of("COUPON");

    /** Resource codes that must be registered but fully disabled (idempotent convergence of old defaults). */
    private static final Set<String> EXPECTED_DISABLED_CHANNELS = Set.of("WHATSAPP");
    private static final Set<String> EXPECTED_DISABLED_EVENTS = Set.of("POINTS_EXPIRING");
    private static final Set<String> EXPECTED_DISABLED_PROMOTIONS = Set.of();

    // -- structural guards -----------------------------------------------------------

    @Test
    void theSeedFileExistsAndIsReadable() {
        assertThat(SEED_FILE).isRegularFile().isReadable();
    }

    @Test
    void theSeedFileStartsWithAnIdempotentInsertOnConflict() {
        String sql = read(SEED_FILE);
        assertThat(sql)
                .contains("INSERT INTO campaign_business_resource")
                .contains("ON CONFLICT (tenant_id, program_id, resource_type, resource_code) DO UPDATE SET");
    }

    @Test
    void theSeedFileIsNotAFlywayMigration() {
        String path = SEED_FILE.toString().replace('\\', '/');
        assertThat(path)
                .as("the dev-seed lives outside every Flyway migration location")
                .doesNotContain("campaign-conversation-migration")
                .doesNotContain("campaign-unified-migration");
    }

    // -- tenant / program scope ------------------------------------------------------

    @Test
    void everyRowBelongsToTheExpectedDevelopmentTenantAndProgram() {
        List<Row> rows = parseRows();
        for (Row row : rows) {
            assertThat(row.tenantId)
                    .as("every seed row targets the same development tenant")
                    .isEqualTo(EXPECTED_TENANT);
            assertThat(row.programId)
                    .as("every seed row targets the same development program")
                    .isEqualTo(EXPECTED_PROGRAM);
        }
    }

    // -- selectable channels ---------------------------------------------------------

    @Test
    void smsEmailAndWechatAreSelectableChannelsAndWhatsappIsDisabled() {
        List<Row> channels = parseRows().stream()
                .filter(r -> "COMMUNICATION_CHANNEL".equals(r.resourceType))
                .toList();

        Set<String> selectable = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (Row row : channels) {
            if (row.enabled && row.runtimeAvailable) {
                assertThat(row.integrationKey)
                        .as("selectable channel %s must carry an integration key", row.resourceCode)
                        .isNotNull();
                selectable.add(row.resourceCode);
            } else {
                assertThat(row.integrationKey)
                        .as("disabled channel %s must not carry an integration key", row.resourceCode)
                        .isNull();
                disabled.add(row.resourceCode);
            }
        }

        assertThat(selectable)
                .as("selectable communication channels")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SELECTABLE_CHANNELS);
        assertThat(disabled)
                .as("disabled communication channels")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_DISABLED_CHANNELS);
    }

    // -- selectable trigger events ---------------------------------------------------

    @Test
    void theFourAuthorisedEventsAreSelectableAndPointsExpiringIsDisabled() {
        List<Row> events = parseRows().stream()
                .filter(r -> "TRIGGER_EVENT".equals(r.resourceType))
                .toList();

        Set<String> selectable = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (Row row : events) {
            if (row.enabled && row.runtimeAvailable) {
                assertThat(row.integrationKey)
                        .as("selectable event %s must carry an integration key", row.resourceCode)
                        .isNotNull();
                selectable.add(row.resourceCode);
            } else {
                assertThat(row.integrationKey)
                        .as("disabled event %s must not carry an integration key", row.resourceCode)
                        .isNull();
                disabled.add(row.resourceCode);
            }
        }

        assertThat(selectable)
                .as("selectable trigger events")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SELECTABLE_EVENTS);
        assertThat(disabled)
                .as("disabled trigger events")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_DISABLED_EVENTS);
    }

    // -- selectable promotions -----------------------------------------------------

    @Test
    void couponIsTheOnlySelectablePromotion() {
        List<Row> promotions = parseRows().stream()
                .filter(r -> "PROMOTION".equals(r.resourceType))
                .toList();

        Set<String> selectable = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (Row row : promotions) {
            if (row.enabled && row.runtimeAvailable) {
                assertThat(row.integrationKey)
                        .as("selectable promotion %s must carry an integration key", row.resourceCode)
                        .isNotNull();
                selectable.add(row.resourceCode);
            } else {
                assertThat(row.integrationKey)
                        .as("disabled promotion %s must not carry an integration key", row.resourceCode)
                        .isNull();
                disabled.add(row.resourceCode);
            }
        }

        assertThat(selectable)
                .as("selectable promotions")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SELECTABLE_PROMOTIONS);
        assertThat(disabled)
                .as("disabled promotions")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_DISABLED_PROMOTIONS);
    }

    // -- integration keys are bounded development values -----------------------------

    @Test
    void everyIntegrationKeyIsADevelopmentScopedValue() {
        List<Row> rows = parseRows();
        for (Row row : rows) {
            if (row.integrationKey != null) {
                assertThat(row.integrationKey)
                        .as("integration key for %s must use the dev- prefix", row.resourceCode)
                        .startsWith("dev-");
            }
        }
    }

    // -- no unknown resource types ---------------------------------------------------

    @Test
    void everyRowHasAKnownResourceType() {
        for (Row row : parseRows()) {
            assertThat(row.resourceType)
                    .as("resource type for %s must be COMMUNICATION_CHANNEL, TRIGGER_EVENT, or PROMOTION", row.resourceCode)
                    .isIn("COMMUNICATION_CHANNEL", "TRIGGER_EVENT", "PROMOTION");
        }
    }

    // -- row count guard -------------------------------------------------------------

    @Test
    void theSeedProducesExactlyTenRows() {
        assertThat(parseRows())
                .as("exactly 10 rows: 4 channels + 5 events + 1 promotion")
                .hasSize(10);
    }

    // -- parsing helpers -------------------------------------------------------------

    /**
     * Parses each VALUES tuple from the seed file. The parser handles the simple fixed-format rows this seed
     * uses: one tuple per line, single-quoted string literals, TRUE/FALSE/NULL booleans, and ARRAY[…] literals.
     */
    private static List<Row> parseRows() {
        String sql = read(SEED_FILE);

        // Bounded extraction: everything between the VALUES keyword and the ON CONFLICT clause.
        int valuesStart = sql.indexOf("VALUES");
        int onConflictStart = sql.indexOf("ON CONFLICT");
        assertThat(valuesStart)
                .as("VALUES keyword must be present in the seed file")
                .isGreaterThanOrEqualTo(0);
        assertThat(onConflictStart)
                .as("ON CONFLICT clause must be present in the seed file")
                .isGreaterThanOrEqualTo(0);
        String block = sql.substring(valuesStart + "VALUES".length(), onConflictStart);

        // Match one parenthesised tuple at a time. Whitespace between fields (including
        // newlines and indentation) is consumed by \s*, so this handles the seed's
        // multiline tuples without needing DOTALL.
        Pattern tuple = Pattern.compile(
                "\\(\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,(?:\\s*ARRAY\\[[^\\]]*\\]\\s*,)?\\s*(TRUE|FALSE)\\s*,\\s*(TRUE|FALSE)\\s*,\\s*('[^']*'|NULL)\\s*,\\s*(\\d+)\\s*\\)");
        Matcher tupleMatcher = tuple.matcher(block);

        List<Row> rows = new ArrayList<>();
        while (tupleMatcher.find()) {
            String tenantId = tupleMatcher.group(1);
            String programId = tupleMatcher.group(2);
            String resourceType = tupleMatcher.group(3);
            String resourceCode = tupleMatcher.group(4);
            String displayName = tupleMatcher.group(5);
            boolean enabled = "TRUE".equals(tupleMatcher.group(6));
            boolean runtimeAvailable = "TRUE".equals(tupleMatcher.group(7));
            String integrationKeyRaw = tupleMatcher.group(8);
            String integrationKey = "NULL".equals(integrationKeyRaw) ? null
                    : integrationKeyRaw.substring(1, integrationKeyRaw.length() - 1); // strip quotes
            int sortOrder = Integer.parseInt(tupleMatcher.group(9));

            rows.add(new Row(tenantId, programId, resourceType, resourceCode,
                    displayName, enabled, runtimeAvailable, integrationKey, sortOrder));
        }

        assertThat(rows)
                .as("parsed rows must not be empty")
                .isNotEmpty();
        return rows;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // -- value type ----------------------------------------------------------------

    private record Row(
            String tenantId,
            String programId,
            String resourceType,
            String resourceCode,
            String displayName,
            boolean enabled,
            boolean runtimeAvailable,
            String integrationKey,
            int sortOrder) {
    }
}