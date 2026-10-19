package com.company.campaign.query.sql;

import com.company.campaign.shared.canonical.CanonicalJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Strict classpath loader for externally authored physical storage catalogs. */
public final class StorageCatalogResourceLoader {

    private static final int MAX_RESOURCE_BYTES = 1_048_576;
    private static final CanonicalJson JSON = new CanonicalJson();

    private StorageCatalogResourceLoader() {
    }

    public static StorageCatalog load(String resourcePath) {
        String normalized = normalize(resourcePath);
        try (InputStream input = StorageCatalogResourceLoader.class.getClassLoader()
                .getResourceAsStream(normalized)) {
            if (input == null) {
                throw invalid("storageResourceUnavailable");
            }
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw invalid("storageResourceSize");
            }
            StorageCatalogResource resource = JSON.read(
                    new String(bytes, StandardCharsets.UTF_8), StorageCatalogResource.class);
            return resource.publish();
        } catch (IOException failure) {
            throw invalid("storageResourceRead");
        }
    }

    private static String normalize(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank() || resourcePath.contains("..")) {
            throw invalid("storageResourcePath");
        }
        return resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
    }

    private static SqlException invalid(String invariant) {
        return new SqlException(
                SqlErrorCodes.SQL_CATALOG_INVALID,
                invariant,
                "An external storage catalog resource is invalid.");
    }
}
