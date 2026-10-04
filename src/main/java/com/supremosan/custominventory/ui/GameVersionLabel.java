package com.supremosan.custominventory.ui;

import com.hypixel.hytale.common.util.java.ManifestUtil;

/** Runtime game version shown beneath the native Early Access label. */
final class GameVersionLabel {
    private GameVersionLabel() {
    }

    static String text() {
        String version = ManifestUtil.getVersion();
        if (version == null || version.isBlank() || "UNKNOWN".equals(version) || "NoJar".equals(version)) {
            return "dev";
        }
        return version.startsWith("v") ? version : "v" + version;
    }
}
