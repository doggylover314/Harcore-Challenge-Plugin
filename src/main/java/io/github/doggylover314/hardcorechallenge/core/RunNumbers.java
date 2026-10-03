package io.github.doggylover314.hardcorechallenge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Run number bookkeeping that does not need a server: picking the next number and finding run
 * world folders on disk.
 */
public final class RunNumbers {
    private RunNumbers() {
    }

    /** One more than the highest number any source knows about, so no source is ever overwritten. */
    public static int next(int... known) {
        int highest = 0;
        for (int number : known) {
            highest = Math.max(highest, number);
        }
        return highest + 1;
    }

    /** The highest run number among run world names or folder names; 0 if there are none. */
    public static int highest(Collection<String> names) {
        int highest = 0;
        for (String name : names) {
            highest = Math.max(highest, WorldNames.runNumberOf(name).orElse(0));
        }
        return highest;
    }

    /** Run world folders directly inside a directory. A missing directory has none. */
    public static List<Path> runFolders(Path directory) {
        List<Path> found = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return found;
        }
        try (Stream<Path> children = Files.list(directory)) {
            children.filter(Files::isDirectory)
                    .filter(path -> WorldNames.isRunWorld(String.valueOf(path.getFileName())))
                    .forEach(found::add);
        } catch (IOException e) {
            // Unreadable directory: treat as empty; callers only use this to be more careful.
        }
        return found;
    }
}
