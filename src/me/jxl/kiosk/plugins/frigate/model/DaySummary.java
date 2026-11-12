// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.model;

/**
 * Aggregated per-day event/review count returned by Frigate summary endpoints.
 */
public class DaySummary {
    public final String day;
    public final String camera;
    public final String label;
    public final int count;

    public DaySummary(String day, String camera, String label, int count) {
        this.day = day;
        this.camera = camera;
        this.label = label;
        this.count = count;
    }
}
