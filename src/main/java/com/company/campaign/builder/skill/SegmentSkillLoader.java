package com.company.campaign.builder.skill;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The one strict loader for the versioned Segment Skill resource.
 *
 * <p>It refuses malformed UTF-8, malformed JSON, duplicate object keys, unknown/missing/null fields, alternate
 * casing, duplicate or unknown Tool identifiers, unknown contract versions, unsupported terminal states,
 * unsafe data classifications and any attempted Save/Apply/Publish/Pause/Retry/Runtime/SQL/repository/
 * provider-credential capability. Every rejection is one stable {@link SegmentSkillErrorCodes} code and echoes
 * no part of the rejected resource.
 *
 * <p>The loader reads only the named classpath resource; it never reads the file system, the environment, the
 * clock or a random source.
 */
public final class SegmentSkillLoader {

    /** The canonical classpath location of the one v1.3 rule-based Skill resource. */
    public static final String DEFAULT_RESOURCE = "skills/segment-rule-based-v1.json";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** Loads and validates the committed default Skill resource. */
    public SegmentSkillDefinition loadDefault() {
        return load(DEFAULT_RESOURCE);
    }

    /** Loads and validates the named classpath Skill resource. */
    public SegmentSkillDefinition load(String resourcePath) {
        String path = normalizePath(resourcePath);
        byte[] bytes;
        try (InputStream stream = SegmentSkillLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.RESOURCE_MISSING, "The Skill resource is missing");
            }
            bytes = stream.readNBytes(SegmentSkillLimits.MAX_RESOURCE_BYTES + 1);
        } catch (IOException failure) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.RESOURCE_MISSING, "The Skill resource is unreadable");
        }
        return parse(bytes);
    }

    /** Parses and validates one Skill resource from raw bytes. */
    public SegmentSkillDefinition parse(byte[] utf8) {
        if (utf8 == null || utf8.length == 0) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.RESOURCE_MISSING, "The Skill resource is missing");
        }
        if (utf8.length > SegmentSkillLimits.MAX_RESOURCE_BYTES) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.RESOURCE_OVERSIZED, "The Skill resource exceeds its byte bound");
        }
        SegmentSkillResource resource = readResource(decodeUtf8(utf8));
        return resource.toDefinition();
    }

    private static SegmentSkillResource readResource(String text) {
        try {
            SegmentSkillResource resource = CANONICAL_JSON.read(text, SegmentSkillResource.class);
            if (resource == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.JSON_MALFORMED, "The Skill resource is not one JSON object");
            }
            return resource;
        } catch (ContractException failure) {
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(failure.code())) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.JSON_DUPLICATE_KEY, "The Skill resource repeats a field");
            }
            if (ContractErrorCodes.CONTRACT_UNKNOWN_FIELD.equals(failure.code())) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.UNKNOWN_FIELD, "The Skill resource carries an unknown field");
            }
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.JSON_MALFORMED, "The Skill resource is not well-formed");
        }
    }

    private static String normalizePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.RESOURCE_MISSING, "A Skill resource path is required");
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (path.contains("..")) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.RESOURCE_MISSING, "A Skill resource path must not traverse");
        }
        return path;
    }

    private static String decodeUtf8(byte[] utf8) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.ENCODING_INVALID, "The Skill resource is not strict UTF-8");
        }
    }
}