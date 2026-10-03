package io.github.doggylover314.hardcorechallenge.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Decides whether a folder may be deleted. Deleting the wrong folder is the one mistake this
 * plugin cannot undo, so every deletion goes through here.
 */
public final class DeletionGuard {
    private DeletionGuard() {
    }

    /**
     * @param target         folder we want to delete
     * @param protectedRoots folders that must survive, together with everything inside them that
     *                       they depend on: loaded worlds, the server's main level, the world container
     * @param allowedRoots   folders the target must be inside (the server's world container), so a
     *                       stored path that points at another server is never touched
     * @return empty if deletion is allowed, otherwise the reason it is refused
     */
    public static Optional<String> refusal(Path target, Collection<Path> protectedRoots, Collection<Path> allowedRoots) {
        if (target == null) {
            return Optional.of("no path");
        }
        Path normalized = target.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        if (fileName == null) {
            return Optional.of("path has no file name: " + normalized);
        }
        if (!WorldNames.isRunWorld(fileName.toString())) {
            return Optional.of("not a run world folder: " + normalized);
        }
        if (!isInside(normalized, allowedRoots)) {
            return Optional.of("outside this server's world container: " + normalized);
        }
        for (Path root : protectedRoots) {
            if (root == null) {
                continue;
            }
            Path protectedPath = root.toAbsolutePath().normalize();
            if (protectedPath.equals(normalized)) {
                return Optional.of("folder is in use: " + normalized);
            }
            if (protectedPath.startsWith(normalized)) {
                return Optional.of("folder contains an in-use folder " + protectedPath + ": " + normalized);
            }
        }
        return Optional.empty();
    }

    /** Whether the target is strictly inside one of the roots (a root itself does not count). */
    public static boolean isInside(Path target, Collection<Path> roots) {
        if (target == null) {
            return false;
        }
        Path normalized = target.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path base = root.toAbsolutePath().normalize();
            if (!base.equals(normalized) && normalized.startsWith(base)) {
                return true;
            }
        }
        return false;
    }
}
