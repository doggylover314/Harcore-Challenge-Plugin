package io.github.doggylover314.hardcorechallenge.data;

import io.github.doggylover314.hardcorechallenge.core.RunLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Every run, kept forever, one file per run in {@code runs/}.
 */
public final class RunArchive {
    private final Path folder;
    private final DataStore io;
    private final Logger logger;
    private final Map<Integer, RunLog> runs = new TreeMap<>();

    public RunArchive(Path dataFolder, DataStore io, Logger logger) {
        this.folder = dataFolder.resolve("runs");
        this.io = io;
        this.logger = logger;
    }

    public void load() {
        runs.clear();
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(path -> path.getFileName().toString().matches("run-\\d+\\.yml")).forEach(path -> {
                try {
                    Object raw = yaml().load(Files.readString(path, StandardCharsets.UTF_8));
                    if (raw instanceof Map<?, ?> map) {
                        RunLog run = RunLogCodec.decode(map);
                        runs.put(run.runNumber(), run);
                    }
                } catch (IOException | RuntimeException e) {
                    logger.log(Level.WARNING, "Could not read " + path + "; skipping it", e);
                }
            });
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not list " + folder, e);
        }
        logger.info("Loaded " + runs.size() + " run(s)");
    }

    public RunLog get(int runNumber) {
        return runs.get(runNumber);
    }

    public Collection<RunLog> all() {
        return Collections.unmodifiableCollection(runs.values());
    }

    /** Stores the run and writes its file in the background. Call on the main thread. */
    public void save(RunLog run) {
        runs.put(run.runNumber(), run);
        io.writeAsync(file(run.runNumber()), yaml().dump(RunLogCodec.encode(run)));
    }

    /** Writes the run's file immediately (used on shutdown). */
    public void saveNow(RunLog run) {
        runs.put(run.runNumber(), run);
        io.writeNow(file(run.runNumber()), yaml().dump(RunLogCodec.encode(run)));
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
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        return new Yaml(options);
    }
}
