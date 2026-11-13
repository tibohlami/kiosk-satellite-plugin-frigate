// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.model;

/**
 * Continuous recording interval returned by {@code /api/<camera>/recordings}.
 */
public class RecordingSegment {
    public final double startTime;
    public final double endTime;
    public final int motion;
    public final int objects;

    public RecordingSegment(double startTime, double endTime, int motion, int objects) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.motion = motion;
        this.objects = objects;
    }
}
