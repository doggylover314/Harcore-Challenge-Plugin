package io.github.doggylover314.hardcorechallenge.core;

import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Naming for run worlds: {@code hcc_run_<n>}, {@code hcc_run_<n>_nether}, {@code hcc_run_<n>_the_end}.
 */
public final class WorldNames {
    public static final String PREFIX = "hcc_run_";
    public static final String NETHER_SUFFIX = "_nether";
    public static final String END_SUFFIX = "_the_end";

    private static final Pattern RUN_WORLD = Pattern.compile("^hcc_run_(\\d+)(_nether|_the_end)?$");

    private WorldNames() {
    }

    public static String overworld(int runNumber) {
        return PREFIX + runNumber;
    }

    public static String nether(int runNumber) {
        return overworld(runNumber) + NETHER_SUFFIX;
    }

    public static String end(int runNumber) {
        return overworld(runNumber) + END_SUFFIX;
    }

    public static List<String> all(int runNumber) {
        return List.of(overworld(runNumber), nether(runNumber), end(runNumber));
    }

    /** Whether the name belongs to any run world of this plugin. */
    public static boolean isRunWorld(String name) {
        return name != null && RUN_WORLD.matcher(name).matches();
    }

    /** The run number encoded in a run world name. */
    public static OptionalInt runNumberOf(String name) {
        if (name == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = RUN_WORLD.matcher(name);
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** The overworld name for a run world name of any dimension. */
    public static String baseOf(String name) {
        if (name.endsWith(NETHER_SUFFIX)) {
            return name.substring(0, name.length() - NETHER_SUFFIX.length());
        }
        if (name.endsWith(END_SUFFIX)) {
            return name.substring(0, name.length() - END_SUFFIX.length());
        }
        return name;
    }
}
