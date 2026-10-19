package com.company.campaign.builder.segment;

import com.company.campaign.shared.canonical.ContentHash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Loads the single versioned model-facing Segment prompt contract from a UTF-8 resource. */
public final class SegmentPromptContract {

    static final String RESOURCE = "prompts/segment-intent-v1.3.md";
    static final int MAX_RESOURCE_BYTES = 65_536;

    private static final LoadedContract CONTRACT = loadResource(RESOURCE, "segment-intent-prompt");

    public static final String VERSION = CONTRACT.version();
    public static final String TEXT = CONTRACT.text();
    public static final ContentHash HASH = ContentHash.sha256(TEXT.getBytes(StandardCharsets.UTF_8));

    private SegmentPromptContract() {
    }

    static LoadedContract loadResource(String resourcePath, String versionPrefix) {
        try (InputStream stream = SegmentPromptContract.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("The prompt contract resource is missing");
            }
            byte[] bytes = stream.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new IllegalStateException("The prompt contract resource exceeds its size bound");
            }
            return parse(decode(bytes), versionPrefix);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException("The Segment prompt contract resource is not valid UTF-8", failure);
        }
    }

    static LoadedContract parse(String resource) {
        return parse(resource, "segment-intent-prompt");
    }

    static LoadedContract parse(String resource, String versionPrefix) {
        String normalized = resource.replace("\r\n", "\n");
        int separator = normalized.indexOf("\n\n");
        if (separator < 0) {
            throw new IllegalStateException("The prompt contract requires a version header");
        }
        String header = normalized.substring(0, separator).strip();
        String prefix = "version: ";
        if (!header.startsWith(prefix)) {
            throw new IllegalStateException("The prompt contract version header is invalid");
        }
        String version = header.substring(prefix.length()).strip();
        if (!version.matches(java.util.regex.Pattern.quote(versionPrefix) + "/[1-9][0-9]*")) {
            throw new IllegalStateException("The prompt contract version is invalid");
        }
        String text = normalized.substring(separator + 2).strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("The prompt contract text is empty");
        }
        return new LoadedContract(version, text);
    }

    record LoadedContract(String version, String text) {
    }
}
