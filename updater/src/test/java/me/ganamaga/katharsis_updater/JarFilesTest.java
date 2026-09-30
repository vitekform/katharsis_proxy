package me.ganamaga.katharsis_updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarFilesTest {

    @TempDir
    Path dir;

    @Test
    void replacesAJarOfTheSameName() throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Path running = Files.writeString(mods.resolve("katharsis_neoforge-0.1.jar"), "old");
        Path staged = Files.writeString(dir.resolve("staged.jar"), "new");

        Path installed = JarFiles.install(staged, running, "katharsis_neoforge-0.1.jar");

        assertEquals(running.toAbsolutePath().normalize(), installed);
        assertEquals("new", Files.readString(running));
        assertFalse(Files.exists(staged));
        assertEquals(List.of("katharsis_neoforge-0.1.jar"), names(mods));
    }

    @Test
    void renamesToTheNewVersionAndRemovesTheOld() throws IOException {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path running = Files.writeString(plugins.resolve("katharsis_proxy-0.1.jar"), "old");
        Files.writeString(plugins.resolve("other.jar"), "someone else");
        Path staged = Files.writeString(dir.resolve("staged.jar"), "new");

        Path installed = JarFiles.install(staged, running, "katharsis_proxy-0.2.jar");

        assertEquals(plugins.resolve("katharsis_proxy-0.2.jar").toAbsolutePath().normalize(), installed);
        assertEquals("new", Files.readString(installed));
        assertEquals(List.of("katharsis_proxy-0.2.jar", "other.jar"), names(plugins));
    }

    @Test
    void findsItsJarByContentWhateverItIsCalled() throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Files.writeString(mods.resolve("broken.jar"), "not a zip");
        zip(mods.resolve("unrelated.jar"), "com/example/Thing.class");
        zip(mods.resolve("renamed.jar"), JarFilesTest.class.getName().replace('.', '/') + ".class");

        assertEquals(mods.resolve("renamed.jar"), JarFiles.findJarContaining(mods, JarFilesTest.class).orElseThrow());
        assertTrue(JarFiles.findJarContaining(dir.resolve("missing"), JarFilesTest.class).isEmpty());
    }

    @Test
    void stagingIsCleared() throws IOException {
        Path staging = Files.createDirectories(dir.resolve("updates"));
        Files.writeString(staging.resolve("a.jar"), "x");
        Files.writeString(staging.resolve("a.jar.part"), "x");
        Files.writeString(staging.resolve("README.txt"), "x");
        Updater.clearStaging(staging);
        assertEquals(List.of("README.txt"), names(staging));
        Updater.clearStaging(dir.resolve("missing"));
    }

    private static void zip(Path path, String entry) throws IOException {
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.closeEntry();
        }
    }

    private static List<String> names(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }
}
