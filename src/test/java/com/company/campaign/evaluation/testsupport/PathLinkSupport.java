package com.company.campaign.evaluation.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Best-effort creation of filesystem links for containment tests.
 *
 * <p>A Windows account without {@code SeCreateSymbolicLinkPrivilege} (or Developer Mode) cannot create
 * symbolic links, but it can still create NTFS junctions, and Java's {@link Path#toRealPath} follows both.
 * Tests therefore prefer a real symbolic link — which is what Unix uses — and fall back to a directory
 * junction on Windows. Callers must treat a {@code false} return as "this platform cannot express the link"
 * and skip the link-dependent assertion; the resolver's containment decision is covered directly and always,
 * so no security property depends on the platform being able to create a link.
 */
public final class PathLinkSupport {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private PathLinkSupport() {
    }

    /** Creates a link to a directory, returning {@code false} when this account cannot create one. */
    public static boolean tryCreateDirectoryLink(Path link, Path target) {
        if (tryCreateSymbolicLink(link, target)) {
            return true;
        }
        // On Windows a directory junction is a reparse point that needs no special privilege and is resolved
        // by Path#toRealPath exactly like a directory symbolic link.
        return WINDOWS && tryRunMklink(link, target, "/J");
    }

    /** Creates a link to a file, returning {@code false} when this account cannot create one. */
    public static boolean tryCreateFileLink(Path link, Path target) {
        if (tryCreateSymbolicLink(link, target)) {
            return true;
        }
        return WINDOWS && tryRunMklink(link, target);
    }

    private static boolean tryCreateSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
    }

    private static boolean tryRunMklink(Path link, Path target, String... linkFlags) {
        try {
            String[] command = new String[3 + linkFlags.length + 2];
            command[0] = "cmd";
            command[1] = "/c";
            command[2] = "mklink";
            System.arraycopy(linkFlags, 0, command, 3, linkFlags.length);
            command[command.length - 2] = link.toString();
            command[command.length - 1] = target.toString();
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (process.waitFor() != 0) {
                return false;
            }
            return Files.exists(link);
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}