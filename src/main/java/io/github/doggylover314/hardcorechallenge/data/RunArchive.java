package io.github.doggylover314.hardcorechallenge.data;

import io.github.doggylover314.hardcorechallenge.core.RunLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Every run, kept forever, one file per run in {@code runs/}.
 */
public final class RunArchive {
    private static final Pattern FILE_NAME = Pattern.compile("run-([1-9]\\d*)\\.yml");
    /** SnakeYAML instances are not thread-safe, so every thread gets its own (and builds it once). */
    private static final ThreadLocal<Yaml> YAML = ThreadLocal.withInitial(RunArchive::newYaml);

    private final Path folder;
    private final DataStore io;
    private final Logger logger;
    private final TreeMap<Integer, RunLog> runs = new TreeMap<>();
    /** Runs whose file could not be read. Their files are never overwritten. */
    private final TreeSet<Integer> brokenRuns = new TreeSet<>();

    public RunArchive(Path dataFolder, DataStore io, Logger logger) {
        this.folder = dataFolder.resolve("runs");
        this.io = io;
        this.logger = logger;
    }

    public void load() {
        runs.clear();
        brokenRuns.clear();
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            files.forEach(path -> {
                Matcher name = FILE_NAME.matcher(path.getFileName().toString());
                if (name.matches()) {
                    read(path, name.group(1));
                }
            });
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not list " + folder, e);
        }
        logger.info("Loaded " + runs.size() + " run(s)" + (brokenRuns.isEmpty() ? "" : ", " + brokenRuns.size() + " unreadable"));
    }

    private void read(Path path, String number) {
        try {
            Object raw = yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("not a run file");
            }
            RunLog run = RunLogCodec.decode(map);
            int fileRun = Integer.parseInt(number);
            if (run.runNumber() != fileRun) {
                throw new IllegalArgumentException("file is for run " + fileRun + " but says run " + run.runNumber());
            }
            runs.put(fileRun, run);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not read " + path + "; keeping a copy as " + path.getFileName()
                    + ".broken and never overwriting it", e);
            try {
                brokenRuns.add(Integer.parseInt(number));
            } catch (NumberFormatException ignored) {
                // Absurdly long number; nothing will ever write to it anyway.
            }
            try {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Best effort only; the original stays where it is.
            }
        }
    }

    public RunLog get(int runNumber) {
        return runs.get(runNumber);
    }

    public Collection<RunLog> all() {
        return Collections.unmodifiableCollection(runs.values());
    }

    /** The highest run number with a file, readable or not; 0 if there are none. */
    public int highestRunNumber() {
        int highest = runs.isEmpty() ? 0 : runs.lastKey();
        return brokenRuns.isEmpty() ? highest : Math.max(highest, brokenRuns.last());
    }

    /** Whether a run's file exists on disk, readable or not. */
    public boolean hasFile(int runNumber) {
        return runs.containsKey(runNumber) || brokenRuns.contains(runNumber) || Files.exists(file(runNumber));
    }

    /** Whether the run's file exists but could not be read. */
    public boolean isBroken(int runNumber) {
        return brokenRuns.contains(runNumber);
    }

    /** Stores the run and writes its file in the background. Call on the main thread. */
    public void save(RunLog run) {
        if (writable(run)) {
            io.writeAsync(file(run.runNumber()), yaml().dump(RunLogCodec.encode(run)));
        }
    }

    /** Writes the run's file immediately (used on shutdown). */
    public void saveNow(RunLog run) {
        if (writable(run)) {
            io.writeNow(file(run.runNumber()), yaml().dump(RunLogCodec.encode(run)));
        }
    }

    private boolean writable(RunLog run) {
        if (brokenRuns.contains(run.runNumber())) {
            logger.warning("Not saving run #" + run.runNumber() + ": its file is unreadable and is kept as it is");
            return false;
        }
        runs.put(run.runNumber(), run);
        return true;
    }

    public boolean delete(int runNumber) {
        if (runs.remove(runNumber) == null) {
            return false;
        }
        io.deleteAsync(file(runNumber));
        return true;
    }

    private Path file(int runNumber) {
        return folder.resolve("run-" + runNumber + ".yml");
    }

    private static Yaml yaml() {
        return YAML.get();
    }

    private static Yaml newYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        return new Yaml(options);
    }
}
