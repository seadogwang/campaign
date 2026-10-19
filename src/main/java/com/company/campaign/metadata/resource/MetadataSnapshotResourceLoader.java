package com.company.campaign.metadata.resource;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Strict classpath loader for externally authored Metadata snapshots. */
public final class MetadataSnapshotResourceLoader {

    private static final int MAX_RESOURCE_BYTES = 1_048_576;
    private static final CanonicalJson JSON = new CanonicalJson();

    private MetadataSnapshotResourceLoader() {
    }

    /** Loads, strictly binds, validates, canonicalizes and hashes one classpath Metadata resource. */
    public static MetadataSnapshot load(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank() || resourcePath.contains("..")) {
            throw invalid("A Metadata resource path is invalid", null);
        }
        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        try (InputStream input = MetadataSnapshotResourceLoader.class.getClassLoader()
                .getResourceAsStream(normalized)) {
            if (input == null) {
                throw invalid("A Metadata resource is unavailable", null);
            }
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw invalid("A Metadata resource exceeds the size limit", null);
            }
            MetadataSnapshotResource resource = JSON.read(
                    new String(bytes, StandardCharsets.UTF_8), MetadataSnapshotResource.class);
            return resource.publish();
        } catch (IOException failure) {
            throw invalid("A Metadata resource cannot be read", failure);
        }
    }

    private static MetadataException invalid(String message, Throwable cause) {
        return new MetadataException(
                MetadataErrorCodes.METADATA_VALUE_INVALID,
                message,
                Map.of(),
                cause);
    }
}
