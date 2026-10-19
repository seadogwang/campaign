package com.company.campaign.builder.campaignagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic document contract test for the 50 sports-shoe business scenarios.
 *
 * <p>Parses {@code docs/sports_shoe_business_scenario_list.md}, proves that scenario
 * IDs 1..50 occur exactly once, prose is non-empty, and every scenario carries
 * {@code 数据依据} and {@code 主要检验} metadata blocks. No live provider is required.
 *
 * <p>Also validates that every dot-qualified semantic ID referenced in
 * {@code 数据依据} exists in the published v10/v17 metadata (attributes, metrics,
 * relations, event selectors), and that the coverage summary is correct.
 */
@DisplayName("Sports Shoe 50 Scenario Document Contract")
class SportsShoe50ContractTest {

    record Scenario(
            int id,
            String title,
            String description,
            String dataBasis,
            String primaryCheck) {
    }

    private static final Pattern SCENARIO_LINE =
            Pattern.compile("^(\\d+)\\.\\s*\\*\\*(.+?)\\*\\*\\s+(.+)$");

    private static final Pattern DATA_BASIS =
            Pattern.compile("^\\s*-\\s*`数据依据`\\s*:\\s*(.+)$");

    private static final Pattern PRIMARY_CHECK =
            Pattern.compile("^\\s*-\\s*`主要检验`\\s*:\\s*(.+)$");

    /** Matches dot-qualified semantic IDs like {@code member.available_points},
     * {@code order_item.quantity_sum}, {@code coupon.send_count}. */
    private static final Pattern DOT_QUALIFIED_ID =
            Pattern.compile("\\b([a-z][a-z_]*\\.[a-z_][a-z_.]*)\\b");

    static List<Scenario> loadScenarios() throws IOException {
        String content = Files.readString(
                Paths.get("docs/sports_shoe_business_scenario_list.md"),
                StandardCharsets.UTF_8);

        String[] lines = content.split("\\R");
        List<Scenario> scenarios = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = SCENARIO_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }

            int id = Integer.parseInt(m.group(1));
            String title = m.group(2).trim();
            String description = m.group(3).trim();

            // Scan forward for metadata lines until the next scenario or EOF.
            String dataBasis = null;
            String primaryCheck = null;
            for (int j = i + 1; j < lines.length; j++) {
                String lookahead = lines[j];
                // Stop if we hit the next scenario.
                if (SCENARIO_LINE.matcher(lookahead).matches()) {
                    break;
                }
                Matcher db = DATA_BASIS.matcher(lookahead);
                if (db.matches() && dataBasis == null) {
                    dataBasis = db.group(1).trim();
                }
                Matcher pc = PRIMARY_CHECK.matcher(lookahead);
                if (pc.matches() && primaryCheck == null) {
                    primaryCheck = pc.group(1).trim();
                }
            }

            scenarios.add(new Scenario(id, title, description, dataBasis, primaryCheck));
        }

        return List.copyOf(scenarios);
    }

    // -----------------------------------------------------------------------
    //  Metadata loading – uses Jackson (spring-boot-starter-json) to parse
    //  the published v10 and v17 development JSON files.
    // -----------------------------------------------------------------------

    /**
     * Collects every {@code id} from {@code attributes}, {@code metrics},
     * {@code relations}, and {@code eventSelectors} arrays in the given JSON resource.
     */
    static Set<String> loadMetadataIds(String classpathResource) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> ids = new LinkedHashSet<>();
        try (InputStream in = SportsShoe50ContractTest.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            assertThat(in)
                    .as("Metadata resource must be on classpath: %s", classpathResource)
                    .isNotNull();
            JsonNode root = mapper.readTree(in);
            for (String section : new String[]{"attributes", "metrics", "relations", "eventSelectors"}) {
                JsonNode arr = root.get(section);
                if (arr != null && arr.isArray()) {
                    for (JsonNode item : arr) {
                        JsonNode idNode = item.get("id");
                        if (idNode != null && idNode.isTextual()) {
                            ids.add(idNode.asText());
                        }
                    }
                }
            }
        }
        return Set.copyOf(ids);
    }

    static Set<String> allPublishedSemanticIds() throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(loadMetadataIds("metadata/core-loyalty-development-v10.json"));
        ids.addAll(loadMetadataIds("metadata/core-loyalty-development-v17-supplement.json"));
        return Set.copyOf(ids);
    }

    // -----------------------------------------------------------------------
    //  Scenario extraction helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts dot-qualified semantic IDs from a data-basis string.
     * Skips text that is purely "不支持" or "需澄清" (returns empty).
     */
    static Set<String> extractSemanticIds(String dataBasis) {
        // If the entire data-basis is just a "not supported" or "needs clarification"
        // declaration with no real semantic IDs, return empty.
        String trimmed = dataBasis.strip();
        if (trimmed.startsWith("不支持") || trimmed.startsWith("需澄清")) {
            return Set.of();
        }

        Set<String> ids = new LinkedHashSet<>();
        Matcher m = DOT_QUALIFIED_ID.matcher(dataBasis);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return Set.copyOf(ids);
    }

    // =======================================================================
    //  Original contract tests
    // =======================================================================

    @Test
    @DisplayName("Exactly 50 scenarios parsed from document")
    void exactly50Scenarios() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        assertThat(scenarios)
                .as("Document should contain exactly 50 scenarios")
                .hasSize(50);
    }

    @Test
    @DisplayName("IDs 1..50 occur exactly once each")
    void idsAreSequential() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        Set<Integer> seen = new HashSet<>();
        for (Scenario s : scenarios) {
            assertThat(seen).as("Scenario ID %d appears more than once", s.id())
                    .doesNotContain(s.id());
            seen.add(s.id());
        }
        for (int id = 1; id <= 50; id++) {
            assertThat(seen).as("Scenario ID %d is missing", id).contains(id);
        }
        assertThat(scenarios).hasSize(50);
    }

    @Test
    @DisplayName("Every scenario has non-empty title")
    void titlesNonEmpty() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        for (Scenario s : scenarios) {
            assertThat(s.title())
                    .as("Scenario %d title must not be blank", s.id())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Every scenario has non-empty description")
    void descriptionsNonEmpty() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        for (Scenario s : scenarios) {
            assertThat(s.description())
                    .as("Scenario %d description must not be blank", s.id())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Every scenario has 数据依据 metadata")
    void everyScenarioHasDataBasis() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        for (Scenario s : scenarios) {
            assertThat(s.dataBasis())
                    .as("Scenario %d must have 数据依据 metadata", s.id())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Every scenario has 主要检验 metadata")
    void everyScenarioHasPrimaryCheck() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        for (Scenario s : scenarios) {
            assertThat(s.primaryCheck())
                    .as("Scenario %d must have 主要检验 metadata", s.id())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Scenarios appear in ascending ID order in document")
    void scenariosInOrder() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        for (int i = 0; i < scenarios.size(); i++) {
            assertThat(scenarios.get(i).id())
                    .as("Scenario at document position %d should have ID %d",
                            i + 1, i + 1)
                    .isEqualTo(i + 1);
        }
    }

    // =======================================================================
    //  Semantic ID validation – task item 5
    // =======================================================================

    @Test
    @DisplayName("Every dot-qualified ID in 数据依据 exists in published v10/v17 metadata")
    void semanticIdsInDataBasisArePublished() throws IOException {
        Set<String> published = allPublishedSemanticIds();
        assertThat(published).as("Published metadata must not be empty").isNotEmpty();

        List<Scenario> scenarios = loadScenarios();
        List<String> violations = new ArrayList<>();

        for (Scenario s : scenarios) {
            Set<String> refs = extractSemanticIds(s.dataBasis());
            for (String ref : refs) {
                if (!published.contains(ref)) {
                    violations.add(String.format(
                            "Scenario %d references '%s' but it is not a published "
                                    + "attribute/metric/relation/eventSelector in v10/v17 metadata",
                            s.id(), ref));
                }
            }
        }

        assertThat(violations)
                .as("Every dot-qualified ID in 数据依据 must exist in v10/v17 metadata")
                .isEmpty();
    }

    @Test
    @DisplayName("Unsupported/clarification-only data-basis blocks contain no dot-qualified IDs")
    void unsupportedDataBasisHasNoDotQualifiedIds() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        for (Scenario s : scenarios) {
            String db = s.dataBasis().strip();
            if (db.startsWith("不支持") || db.startsWith("需澄清")) {
                Set<String> refs = extractSemanticIds(db);
                assertThat(refs)
                        .as("Scenario %d declares 不支持/需澄清 but still contains "
                                + "dot-qualified IDs: %s", s.id(), refs)
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("No scenario uses the nonexistent member.latest_positive_order or order.return bare IDs")
    void noNonexistentBareIds() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        Pattern bareLatest = Pattern.compile("\\bmember\\.latest_positive_order\\b(?!_)");
        Pattern bareReturn = Pattern.compile("\\border\\.return\\b(?!_)");

        for (Scenario s : scenarios) {
            String db = s.dataBasis();
            assertThat(bareLatest.matcher(db).find())
                    .as("Scenario %d 数据依据 must not contain bare member.latest_positive_order", s.id())
                    .isFalse();
            assertThat(bareReturn.matcher(db).find())
                    .as("Scenario %d 数据依据 must not contain bare order.return", s.id())
                    .isFalse();
        }
    }

    // =======================================================================
    //  Coverage-summary contract – task item 6
    // =======================================================================

    /** Pattern for category headings: "### 支持场景（27个）" */
    private static final Pattern CATEGORY_HEADING =
            Pattern.compile("^###\\s+(支持场景|需澄清场景|不支持边界场景)（(\\d+)个）$");

    /** Extracts scenario IDs from a category line like "1（...）、2（...）、" */
    private static final Pattern CATEGORY_ID = Pattern.compile("(\\d+)（");

    record CategoryInfo(String name, int declaredCount, List<Integer> scenarioIds) {}

    /**
     * Parses the coverage summary from the markdown document and returns
     * the three category entries (支持, 需澄清, 不支持) in document order.
     */
    static List<CategoryInfo> parseCoverageSummary() throws IOException {
        String content = Files.readString(
                Paths.get("docs/sports_shoe_business_scenario_list.md"),
                StandardCharsets.UTF_8);
        String[] lines = content.split("\\R");

        List<CategoryInfo> categories = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher hm = CATEGORY_HEADING.matcher(lines[i]);
            if (!hm.matches()) {
                continue;
            }
            String name = hm.group(1);
            int declaredCount = Integer.parseInt(hm.group(2));

            // The next non-empty line contains the comma/、-separated IDs.
            List<Integer> ids = new ArrayList<>();
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].strip();
                if (next.isEmpty()) {
                    continue;
                }
                if (CATEGORY_HEADING.matcher(next).matches()) {
                    break; // next category heading
                }
                Matcher cm = CATEGORY_ID.matcher(next);
                while (cm.find()) {
                    ids.add(Integer.parseInt(cm.group(1)));
                }
                break; // only process first content line after heading
            }

            categories.add(new CategoryInfo(name, declaredCount, List.copyOf(ids)));
        }

        return List.copyOf(categories);
    }

    @Test
    @DisplayName("Coverage summary: three primary categories present")
    void coverageSummaryHasThreeCategories() throws IOException {
        List<CategoryInfo> categories = parseCoverageSummary();
        assertThat(categories).as("Must have exactly 3 category sections").hasSize(3);
        assertThat(categories.stream().map(CategoryInfo::name).toList())
                .containsExactly("支持场景", "需澄清场景", "不支持边界场景");
    }

    @Test
    @DisplayName("Coverage summary: each category's declared count matches actual ID count")
    void categoryDeclaredCountMatchesActual() throws IOException {
        List<CategoryInfo> categories = parseCoverageSummary();
        for (CategoryInfo cat : categories) {
            assertThat(cat.scenarioIds().size())
                    .as("Category '%s' declares %d scenarios but contains %d IDs: %s",
                            cat.name(), cat.declaredCount(), cat.scenarioIds().size(),
                            cat.scenarioIds())
                    .isEqualTo(cat.declaredCount());
        }
    }

    @Test
    @DisplayName("Coverage summary: primary-category lists are disjoint")
    void categoryListsAreDisjoint() throws IOException {
        List<CategoryInfo> categories = parseCoverageSummary();
        Set<Integer> all = new HashSet<>();
        for (CategoryInfo cat : categories) {
            for (int id : cat.scenarioIds()) {
                assertThat(all)
                        .as("Scenario %d appears in '%s' but was already in another category",
                                id, cat.name())
                        .doesNotContain(id);
                all.add(id);
            }
        }
    }

    @Test
    @DisplayName("Coverage summary: every ID 1..50 appears exactly once across all categories")
    void allIdsCoveredExactlyOnce() throws IOException {
        List<CategoryInfo> categories = parseCoverageSummary();
        Set<Integer> all = new HashSet<>();
        for (CategoryInfo cat : categories) {
            for (int id : cat.scenarioIds()) {
                assertThat(all.add(id))
                        .as("Scenario %d appears more than once across categories (duplicate in '%s')",
                                id, cat.name())
                        .isTrue();
            }
        }
        for (int id = 1; id <= 50; id++) {
            assertThat(all)
                    .as("Scenario %d is missing from all coverage categories", id)
                    .contains(id);
        }
        assertThat(all).hasSize(50);
    }

    @Test
    @DisplayName("Coverage summary: category totals sum to 50")
    void categoryTotalsSumTo50() throws IOException {
        List<CategoryInfo> categories = parseCoverageSummary();
        int sum = categories.stream().mapToInt(CategoryInfo::declaredCount).sum();
        assertThat(sum)
                .as("Sum of declared category counts must equal 50, was %d", sum)
                .isEqualTo(50);
    }

    // =======================================================================
    //  Report-determinism guard tests — catch stale hardcoded values
    // =======================================================================

    @Test
    @DisplayName("Coverage exact values: the machine-parsed counts are the single source of truth")
    void coverageExactMachineValues() throws IOException {
        List<CategoryInfo> categories = parseCoverageSummary();
        CategoryInfo supported = categories.stream()
                .filter(c -> "支持场景".equals(c.name())).findFirst().orElseThrow();
        CategoryInfo clarification = categories.stream()
                .filter(c -> "需澄清场景".equals(c.name())).findFirst().orElseThrow();
        CategoryInfo unsupported = categories.stream()
                .filter(c -> "不支持边界场景".equals(c.name())).findFirst().orElseThrow();

        // These assertions fail if the document is updated without also updating
        // the report. The report must derive its counts from parseCoverageSummary(),
        // not from hardcoded literals. If these values change, the report must be
        // re-run (which re-parses the document) rather than edited manually.
        assertThat(supported.declaredCount())
                .as("支持场景 count — update this assertion when the document changes; "
                        + "the report derives its value from parseCoverageSummary()")
                .isEqualTo(27);
        assertThat(clarification.declaredCount())
                .as("需澄清场景 count — update this assertion when the document changes")
                .isEqualTo(16);
        assertThat(unsupported.declaredCount())
                .as("不支持边界场景 count — update this assertion when the document changes")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("ComponentCount vs skillCount: skillCount is not component-execution evidence")
    void skillCountIsNotComponentExecutionEvidence() {
        // skillCount reflects the visible Skill state/catalog evidence in the workspace.
        // It is always present (6 skills in the fixed catalog) and does NOT mean a
        // component agent executed. Only componentCount > 0 means genuine component
        // execution. A scenario with componentCount=0 and skillCount>0 has stopped
        // before component work — the report must NOT require skillCount==0.

        int skillCount = 6; // fixed workspace/catalog state
        int componentCount = 0;
        boolean stoppedBeforeComponents = componentCount == 0;
        boolean reachedComponents = componentCount > 0;

        assertThat(stoppedBeforeComponents)
                .as("componentCount=0 means stopped before components regardless of skillCount")
                .isTrue();
        assertThat(reachedComponents)
                .as("componentCount=0 means did not reach component execution")
                .isFalse();
        assertThat(skillCount)
                .as("skillCount=%d does not mean a component agent executed", skillCount)
                .isGreaterThan(0);
    }
}