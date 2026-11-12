// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.model;

/**
 * Tracked-object timeline milestone returned by {@code /api/timeline}.
 */
public class TimelineBoxEntry {
    public final String sourceId;
    public final String classType;
    public final String label;
    public final double timestamp;
    public final float x;
    public final float y;
    public final float w;
    public final float h;

    public TimelineBoxEntry(String sourceId, String classType, String label,
                            double timestamp, float x, float y, float w, float h) {
        this.sourceId = sourceId;
        this.classType = classType;
        this.label = label;
        this.timestamp = timestamp;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }
}
