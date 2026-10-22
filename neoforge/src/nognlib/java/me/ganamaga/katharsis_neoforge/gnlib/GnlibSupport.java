package me.ganamaga.katharsis_neoforge.gnlib;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_neoforge.config.CompanionSettings;
import me.ganamaga.katharsis_neoforge.lock.Locks;
import me.ganamaga.katharsis_neoforge.proxy.ProxyLink;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * GNLib serves remote content on Minecraft 1.21.1 only, so this line's build
 * has nothing to hook into it; the 1.21.1 line's copy of this class does.
 */
public final class GnlibSupport {

    public static final String GNLIB_ID = "gnlib";

    private static final Logger LOGGER = LogUtils.getLogger();

    private GnlibSupport() {
    }

    /** From the mod's constructor. */
    public static void install(Locks locks, ProxyLink link, CompanionSettings settings, Path settingsFile) {
        if (ModList.get().isLoaded(GNLIB_ID)) {
            LOGGER.info("GNLib is installed, but this build of the mod has no GNLib remote content support: "
                    + "GNLib serves remote content on Minecraft 1.21.1 only.");
        }
    }
}
