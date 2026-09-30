package me.ganamaga.katharsis_updater;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.Optional;
import java.util.zip.ZipFile;

/** Finding the running jar, and putting a new one in its place. */
public final class JarFiles {

    private JarFiles() {
    }

    /**
     * The jar {@code type} was loaded from, if it came from a plain file.
     * Loaders that hand out classes from anything else, such as NeoForge's,
     * give no answer; {@link #findJarContaining} is the fallback.
     */
    public static Optional<Path> jarOf(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null
                    || !"file".equals(source.getLocation().getProtocol())) {
                return Optional.empty();
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")
                    ? Optional.of(path)
                    : Optional.empty();
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return Optional.empty();
        }
    }

    /**
     * The jar in {@code directory} that holds {@code type}'s class file,
     * whatever it has been renamed to.
     */
    public static Optional<Path> findJarContaining(Path directory, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : jars) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    if (zip.getEntry(entry) != null) {
                        return Optional.of(jar);
                    }
                } catch (IOException e) {
                    // Not a readable jar; certainly not ours.
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Replaces the running jar with {@code staged}, under the new release's
     * file name.
     *
     * <p>Safe while the old jar is still open, on systems that allow it: the
     * new file is renamed over the old path in one step, and a JVM that has
     * the old one open keeps reading the old contents. Where the old jar
     * cannot be removed (Windows, while it is loaded), nothing is changed, so
     * the server never ends up with two copies of the plugin.</p>
     *
     * @param staged  the verified download
     * @param current the running jar
     * @param newName the file name to install it under
     * @return where the new jar now is
     * @throws IOException if it could not be installed; the old jar is then
     *                     still in place and {@code staged} is kept
     */
    public static Path install(Path staged, Path current, String newName) throws IOException {
        if (!ReleaseClient.isSafeFilename(newName)) {
            throw new IOException("refusing to install a file named '" + newName + "'");
        }
        Path running = current.toAbsolutePath().normalize();
        Path directory = running.getParent();
        Path target = directory.resolve(newName);
        // Hidden, and not a .jar, so no loader mistakes it for a plugin while it is being written.
        Path part = directory.resolve("." + newName + ".part");
        try {
            Files.copy(staged, part, StandardCopyOption.REPLACE_EXISTING);
            moveReplacing(part, target);
            if (!target.equals(running)) {
                try {
                    Files.delete(running);
                } catch (IOException e) {
                    // Two copies would stop the server from starting: back out.
                    Files.deleteIfExists(target);
                    throw new IOException("could not remove " + current.getFileName()
                            + " to make way for " + newName + ": " + e.getMessage(), e);
                }
            }
        } finally {
            Files.deleteIfExists(part);
        }
        Files.deleteIfExists(staged);
        return target;
    }

    /** Moves {@code source} over {@code target}, in one step where the filesystem can. */
    static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
