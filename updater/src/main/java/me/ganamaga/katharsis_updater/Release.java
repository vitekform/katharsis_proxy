package me.ganamaga.katharsis_updater;

import java.util.List;
import java.util.Optional;

/**
 * The newest release as {@code latest.json} describes it.
 *
 * @param version   e.g. {@code 0.2-dev.41}
 * @param title     e.g. {@code Katharsis 0.2-dev.41}
 * @param changelog this release's notes, possibly empty
 * @param files     every jar in it, one per plugin and variant
 * @param history   the versions published since the one asked about, newest
 *                  first, starting with this one
 */
public record Release(Version version, String title, String changelog, boolean prerelease,
                      List<ReleaseFile> files, List<String> history) {

    /**
     * The jar in this release that replaces {@code build}: same artifact, same
     * classifier. Names look like {@code <artifact>-<version>[-<classifier>].jar},
     * where a classifier always starts with {@code mc}.
     */
    public Optional<ReleaseFile> fileFor(BuildInfo build) {
        String prefix = build.artifact() + "-";
        for (ReleaseFile file : files) {
            String name = file.filename();
            if (!name.startsWith(prefix) || !name.endsWith(".jar")) {
                continue;
            }
            String rest = name.substring(prefix.length(), name.length() - ".jar".length());
            if (classifierOf(rest).equals(build.classifier())) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /** {@code 0.2-mc26} to {@code mc26}; {@code 0.2} to nothing. */
    static String classifierOf(String versionAndClassifier) {
        int dash = versionAndClassifier.lastIndexOf('-');
        if (dash < 0) {
            return "";
        }
        String last = versionAndClassifier.substring(dash + 1);
        return last.startsWith("mc") ? last : "";
    }
}
