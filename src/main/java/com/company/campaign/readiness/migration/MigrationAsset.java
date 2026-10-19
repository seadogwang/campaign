package com.company.campaign.readiness.migration;

import com.company.campaign.readiness.ReadinessErrorCodes;
import com.company.campaign.readiness.ReadinessException;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One ordered, checksum-pinned migration asset.
 *
 * <p>The name carries the order — {@code V<version>__<description>.sql} — and {@link #checksum()} pins the
 * exact bytes, so renaming, reordering or editing a committed migration is refused rather than silently
 * replayed against a database that already ran the original.
 */
public record MigrationAsset(String name, int version, String sql, ContentHash checksum) {

    /** The Flyway-compatible migration file name grammar this slice accepts. */
    private static final Pattern NAME = Pattern.compile("^V([0-9]{1,9})__([a-z0-9][a-z0-9_]{0,63})\\.sql$");

    public MigrationAsset {
        Matcher matcher = name == null ? null : NAME.matcher(name);
        if (matcher == null || !matcher.matches()) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "A readiness migration asset name is not a bounded, ordered Flyway-compatible name.");
        }
        int declared = Integer.parseInt(matcher.group(1));
        if (version != declared) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "A readiness migration asset declares a version its name does not carry.");
        }
        if (sql == null) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "A readiness migration asset must carry a statement body.");
        }
        if (checksum == null) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "A readiness migration asset must carry a pinned checksum.");
        }
    }

    /** Stamps one migration with the checksum of its own bytes. */
    public static MigrationAsset of(String name, String sql) {
        Matcher matcher = name == null ? null : NAME.matcher(name);
        if (matcher == null || !matcher.matches()) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "A readiness migration asset name is not a bounded, ordered Flyway-compatible name.");
        }
        return new MigrationAsset(
                name,
                Integer.parseInt(matcher.group(1)),
                sql,
                ContentHash.sha256(sql.getBytes(StandardCharsets.UTF_8)));
    }

    /** True when this asset's declared checksum is the checksum of its own bytes. */
    public boolean selfConsistent() {
        return !sql.isEmpty() && checksum.matches(sql.getBytes(StandardCharsets.UTF_8));
    }

    /** True when this asset carries a statement body. */
    public boolean hasBody() {
        return !sql.isBlank();
    }
}