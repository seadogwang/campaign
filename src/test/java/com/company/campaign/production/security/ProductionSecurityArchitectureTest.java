package com.company.campaign.production.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architecture constraints of the production identity package.
 *
 * <p>It is a source scan and not a behavioural proof. It exists so a future change that reintroduces a hand-rolled
 * token parser, a raw SQL statement, a request-controlled identifier, an ambient clock or a request header as an
 * identity source fails loudly.
 */
class ProductionSecurityArchitectureTest {

    private static final Path PACKAGE = Path.of(
            "src", "main", "java", "com", "company", "campaign", "production", "security");

    private static final Path PROVIDER = PACKAGE.resolve("ProductionTrustedContextProvider.java");
    private static final Path REPOSITORY = PACKAGE.resolve("MyBatisPlusProductionMembershipRepository.java");
    private static final Path CONFIGURATION = PACKAGE.resolve("ProductionSecurityConfiguration.java");

    /** Imports that would give the package a manual parser, a model path, a request path or a fixture. */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.nimbusds",
            "com.company.campaign.builder.segment",
            "com.company.campaign.preview.api",
            "com.company.campaign.query.fixture",
            "com.company.campaign.dev.");

    /** Constructs that would mean ambient state, a manual clock, randomness or request-header identity. */
    private static final List<String> FORBIDDEN_CONSTRUCTS = List.of(
            "ThreadLocal",
            "Instant.now(",
            "System.currentTimeMillis",
            "System.nanoTime",
            "System.getenv",
            "new Random",
            "UUID.randomUUID",
            "getHeader(",
            "getParameter(");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void thePackageUsesNoManualParserModelOrRequestPath() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("no manual parser, model, request-SQL or fixture dependency").isEmpty();
    }

    @Test
    void thePackageUsesNoAmbientClockRandomnessThreadLocalOrRequestHeader() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_CONSTRUCTS) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("no ambient clock, randomness, thread-local or request header").isEmpty();
    }

    @Test
    void theSecurityContextHolderIsUsedOnlyByTheRequestScopedProvider() {
        List<String> users = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (read(file).contains("SecurityContextHolder")) {
                users.add(file.getFileName().toString());
            }
        }
        assertThat(users).containsExactly(PROVIDER.getFileName().toString());
    }

    @Test
    void theProviderReadsIdentityOnlyFromTheVerifiedJwtPrincipal() {
        String source = read(PROVIDER);
        assertThat(source)
                .contains("SecurityContextHolder.getContext().getAuthentication()")
                .contains("instanceof Jwt")
                .doesNotContain("getHeader")
                .doesNotContain("HttpServletRequest")
                .doesNotContain("getParameter");
    }

    @Test
    void theMembershipAdapterUsesNamedMapperStatementsAndIsBounded() {
        String source = read(REPOSITORY);
        assertThat(source)
                .contains("MEMBERSHIP_STATEMENT")
                .contains("PERMISSION_STATEMENT")
                .contains("openSession(true)")
                .contains("getMaxPermissionRows()")
                .doesNotContain("prepareStatement(")
                .doesNotContain("StringBuilder")
                .doesNotContain("append(\"select")
                .doesNotContain("append(\"SELECT")
                .doesNotContain("DriverManager");
    }

    @Test
    void theDecoderUsesSpringNimbusPrimitivesAndNoHandRolledParser() {
        String source = read(CONFIGURATION);
        assertThat(source)
                .contains("NimbusJwtDecoder")
                .contains("setJwtValidator(")
                .doesNotContain("SignedJWT")
                .doesNotContain("Base64")
                .doesNotContain("MessageDigest");
    }

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(PACKAGE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
