package me.ganamaga.katharsis_neoforge.update;

import me.ganamaga.katharsis_updater.BuildInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateSettingsTest {

    @TempDir
    Path config;

    @Test
    @DisplayName("the first start writes the documented defaults and reads them back")
    void writesDefaults() throws IOException {
        Path file = config.resolve("sub").resolve(UpdateSettings.FILE_NAME);

        UpdateSettings settings = UpdateSettings.load(file);

        assertTrue(Files.readString(file).contains("# Which builds count as updates"), "comments are kept");
        assertTrue(settings.enabled());
        assertEquals("auto", settings.channel());
        assertTrue(settings.autoDownload());
        assertEquals(6L, settings.checkIntervalHours());
        assertEquals("", settings.url());
    }

    @Test
    @DisplayName("an admin's own file is read and left alone")
    void keepsEdits() throws IOException {
        Path file = config.resolve(UpdateSettings.FILE_NAME);
        String edited = "enabled = false\nchannel=release\nauto-download=FALSE\ncheck-interval-hours=-2\n";
        Files.writeString(file, edited);

        UpdateSettings settings = UpdateSettings.load(file);

        assertFalse(settings.enabled());
        assertEquals("release", settings.channel());
        assertFalse(settings.autoDownload());
        assertEquals(0L, settings.checkIntervalHours(), "negative means only at startup");
        assertEquals(edited, Files.readString(file));
    }

    @Test
    @DisplayName("nonsense and missing keys fall back to the defaults")
    void fallbacks() throws IOException {
        Path file = config.resolve(UpdateSettings.FILE_NAME);
        Files.writeString(file, "check-interval-hours=often\nenabled=maybe\n");

        UpdateSettings settings = UpdateSettings.load(file);

        assertEquals(6L, settings.checkIntervalHours());
        assertTrue(settings.enabled());
        assertTrue(UpdateSettings.defaults().autoDownload());
    }

    @Test
    @DisplayName("the jar says it is the mod, for one of the three lines")
    void buildInfo() throws IOException {
        BuildInfo build = BuildInfo.load(UpdateSettings.class);

        assertEquals("katharsis_neoforge", build.artifact());
        assertTrue(List.of("", "mc1.21.11", "mc26").contains(build.classifier()), build.classifier());
    }
}
