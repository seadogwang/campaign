package com.company.campaign.testsupport;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one assertion of what persistence T26 may put on the build.
 *
 * <p>T25 forbade every database dependency outright because the readiness adapter had to prove its shape
 * without a database. T26 requires the ordered migrations and the real MyBatis-Plus readiness store to run
 * against PostgreSQL, so the build now carries Flyway and the PostgreSQL JDBC driver. This helper replaces
 * the blanket string check with a stricter contract:
 *
 * <ul>
 *   <li>no embedded or alternative engine (H2, HSQLDB, Derby) and no JPA/Hibernate dependency, ever;</li>
 *   <li>only three opt-in database artifacts are admissible — {@code flyway-core},
 *       {@code flyway-database-postgresql} and the PostgreSQL JDBC driver — plus the JDBC starter that
 *       supplies the pool and transaction support;</li>
 *   <li>every one of those declares no version of its own, so the only pinned version is the one the Spring
 *       Boot BOM manages; a floating or hand-edited version fails the build.</li>
 * </ul>
 *
 * <p>The application still starts with no persistence bean at all unless a host explicitly enables
 * {@code campaign.readiness.postgres.enabled}; that runtime guarantee is asserted by
 * {@code DefaultStartupWithoutPostgresTest}.
 */
public final class OptInDatabaseDependencies {

    /** The exact database artifacts the opt-in PostgreSQL mode may add, pinned through the BOM or a Maven property. */
    public static final Set<String> ALLOWED_OPT_IN_ARTIFACTS = Set.of(
            "org.flywaydb:flyway-core",
            "org.flywaydb:flyway-database-postgresql",
            "org.postgresql:postgresql",
            "org.springframework.boot:spring-boot-starter-jdbc",
            "com.baomidou:mybatis-plus-spring-boot3-starter");

    /** Engine and ORM artifacts that must never appear, whatever the task. */
    private static final Set<String> FORBIDDEN_ARTIFACTS = Set.of(
            "h2", "hsqldb", "derby", "derbyclient", "derbytools",
            "hibernate-core", "hibernate-entitymanager", "hibernate-validator-orm",
            "spring-boot-starter-data-jpa", "spring-orm");

    private static final Set<String> FORBIDDEN_GROUP_PREFIXES = Set.of(
            "com.h2database", "org.hsqldb", "org.apache.derby", "org.hibernate");

    private OptInDatabaseDependencies() {
    }

    /** Asserts the repository build carries only the adapter-independent, BOM-pinned opt-in dependencies. */
    public static void assertPinnedOptInDatabaseDependencies(Path pom) throws IOException {
        Document document = parse(pom);
        List<Element> dependencies = dependencies(document);

        List<String> violations = new ArrayList<>();
        List<String> optIn = new ArrayList<>();
        for (Element dependency : dependencies) {
            String groupId = childText(dependency, "groupId");
            String artifactId = childText(dependency, "artifactId");
            String coordinate = groupId + ":" + artifactId;
            if (FORBIDDEN_ARTIFACTS.contains(artifactId)
                    || FORBIDDEN_GROUP_PREFIXES.stream().anyMatch(groupId::startsWith)) {
                violations.add(coordinate);
                continue;
            }
            boolean databaseRelated = artifactId.toLowerCase(Locale.ROOT).contains("flyway")
                    || artifactId.toLowerCase(Locale.ROOT).contains("postgres")
                    || artifactId.toLowerCase(Locale.ROOT).contains("hikari")
                    || artifactId.toLowerCase(Locale.ROOT).contains("mybatis")
                    || FORBIDDEN_GROUP_PREFIXES.stream().noneMatch(groupId::startsWith)
                            && (artifactId.equals("spring-boot-starter-jdbc") || artifactId.equals("spring-jdbc"));
            if (databaseRelated) {
                optIn.add(coordinate);
                if (!ALLOWED_OPT_IN_ARTIFACTS.contains(coordinate)) {
                    violations.add(coordinate);
                }
                Element versionElement = childElement(dependency, "version");
                if (versionElement != null) {
                    String version = versionElement.getTextContent().trim();
                    // A Maven-property-pinned version (e.g. ${mybatis-plus...version}) is allowed;
                    // only a hardcoded or floating version is a violation.
                    if (!version.startsWith("${")) {
                        violations.add(coordinate + " declares its own version: " + version);
                    }
                }
            }
        }

        assertThat(violations)
                .as("forbidden or unpinned database dependencies in pom.xml")
                .isEmpty();
        assertThat(optIn)
                .as("the opt-in PostgreSQL dependencies must be present and BOM-pinned")
                .contains("org.flywaydb:flyway-core", "org.postgresql:postgresql");
    }

    /** The dependency coordinates the build declares, in document order, for diagnostics. */
    public static List<String> coordinates(Path pom) throws IOException {
        return dependencies(parse(pom)).stream()
                .map(dependency -> childText(dependency, "groupId") + ":" + childText(dependency, "artifactId"))
                .collect(Collectors.toList());
    }

    private static Document parse(Path pom) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(pom.toFile());
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("pom.xml cannot be parsed", exception);
        }
    }

    private static List<Element> dependencies(Document document) {
        List<Element> dependencies = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("dependency");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) {
                dependencies.add(element);
            }
        }
        return dependencies;
    }

    private static Element childElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && name.equals(element.getNodeName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element element = childElement(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }
}
