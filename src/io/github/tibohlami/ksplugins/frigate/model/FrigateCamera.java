package io.github.tibohlami.ksplugins.frigate.model;

/**
 * Model representing a Frigate camera discovered via /api/config or /api/stats.
 */
public class FrigateCamera {

    private final String name;
    private final String friendlyName;
    private final double cameraFps;
    private final double detectionFps;
    private final boolean enabled;

    public FrigateCamera(String name, String friendlyName, double cameraFps, double detectionFps, boolean enabled) {
        this.name = name;
        this.friendlyName = friendlyName != null && !friendlyName.isEmpty() ? friendlyName : formatName(name);
        this.cameraFps = cameraFps;
        this.detectionFps = detectionFps;
        this.enabled = enabled;
    }

    public static String formatName(String rawName) {
        if (rawName == null) return "";
        return rawName.replace('_', ' ')
                .replace('-', ' ')
                .trim();
    }

    public String getName() { return name; }
    public String getFriendlyName() { return friendlyName; }
    public double getCameraFps() { return cameraFps; }
    public double getDetectionFps() { return detectionFps; }
    public boolean isEnabled() { return enabled; }

    @Override
    public String toString() {
        return friendlyName;
    }
}
