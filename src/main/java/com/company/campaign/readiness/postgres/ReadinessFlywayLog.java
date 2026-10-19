package com.company.campaign.readiness.postgres;

import org.flywaydb.core.api.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The bounded Flyway log that never prints a JDBC URL or a credential.
 *
 * <p>Flyway's default logger announces the database with the full JDBC URL, which would put a host, a
 * database name and any URL-embedded credential into the application log. This logger replaces that: it
 * redacts every registered secret and every {@code jdbc:}-style URL, strips newlines and bounds the line
 * length, so a migration log line is diagnosable without disclosing the connection.
 *
 * <p>Debug, info and notice lines are dropped altogether: they are the lines that announce the connection,
 * and nothing in them is needed to operate the readiness slice.
 */
public final class ReadinessFlywayLog implements Log {

    /** The replacement text for anything that could disclose the connection. */
    static final String REDACTED = "<redacted>";

    /** The longest message this logger will emit. */
    static final int MAX_MESSAGE_LENGTH = 400;

    private static final Logger LOGGER = LoggerFactory.getLogger("campaign.readiness.postgres.migration");

    private static final Pattern JDBC_URL = Pattern.compile("jdbc:\\S+");
    private static final Pattern PASSWORD_PAIR = Pattern.compile("(?i)password=\\S+");
    private static final Pattern NEWLINE = Pattern.compile("[\\r\\n]+");

    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();

    /**
     * Registers one value that must never appear in a log line. Blank values are ignored.
     *
     * <p>It is public because this is the one shared redacting Flyway logger of the build: the opt-in Campaign
     * conversation migration run registers its own credential and URL here too, so neither slice needs a second,
     * possibly weaker, log surface.
     */
    public static void registerSecret(String secret) {
        if (secret != null && !secret.isBlank()) {
            SECRETS.add(secret);
        }
    }

    /** Clears every registered secret; used by tests and when the pool is stopped. */
    static void clearSecrets() {
        SECRETS.clear();
    }

    /** The bounded, redacted form of one message: no URL, no registered secret, no newline, bounded length. */
    static String redact(String message) {
        if (message == null) {
            return "";
        }
        String safe = NEWLINE.matcher(message).replaceAll(" ");
        for (String secret : SECRETS) {
            safe = safe.replace(secret, REDACTED);
        }
        safe = JDBC_URL.matcher(safe).replaceAll("jdbc:" + REDACTED);
        safe = PASSWORD_PAIR.matcher(safe).replaceAll("password=" + REDACTED);
        return safe.length() <= MAX_MESSAGE_LENGTH ? safe : safe.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public void debug(String message) {
        // Dropped: Flyway's debug output is where the connection details appear.
    }

    @Override
    public void info(String message) {
        // Dropped: Flyway's info line announces "Database: <jdbc url>".
    }

    @Override
    public void notice(String message) {
        // Dropped for the same reason as info.
    }

    @Override
    public void warn(String message) {
        LOGGER.warn(redact(message));
    }

    @Override
    public void error(String message) {
        LOGGER.error(redact(message));
    }

    @Override
    public void error(String message, Exception exception) {
        LOGGER.error(redact(message), bounded(exception));
    }

    /**
     * A bounded failure for the log.
     *
     * <p>The exception type is kept and the message is redacted, because a driver exception message routinely
     * embeds the connection string. The original cause chain is not attached.
     */
    private static Throwable bounded(Exception exception) {
        String type = exception == null ? "unknown" : exception.getClass().getName();
        return new RedactedFlywayFailure(type, redact(exception == null ? null : exception.getMessage()));
    }

    /** A log-only failure that carries a redacted message and never a sensitive cause chain. */
    static final class RedactedFlywayFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RedactedFlywayFailure(String type, String redactedMessage) {
            super(type + ": " + redactedMessage, null, false, false);
        }
    }
}