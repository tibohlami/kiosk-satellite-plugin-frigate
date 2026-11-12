// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.model;

import java.util.Collections;
import java.util.List;

/**
 * Frigate review segment returned by {@code /api/review}.
 */
public class ReviewSegment {
    public final String id;
    public final String camera;
    public final double startTime;
    public final double endTime;
    public final String severity;
    public final List<String> objects;
    public final List<String> detectionIds;
    public final String thumbPath;
    public final boolean hasBeenReviewed;

    public ReviewSegment(double startTime, double endTime, String severity, List<String> objects) {
        this(null, null, startTime, endTime, severity, objects, null, null, false);
    }

    public ReviewSegment(String id, String camera, double startTime, double endTime,
                         String severity, List<String> objects, List<String> detectionIds) {
        this(id, camera, startTime, endTime, severity, objects, detectionIds, null, false);
    }

    public ReviewSegment(String id, String camera, double startTime, double endTime,
                         String severity, List<String> objects, List<String> detectionIds,
                         String thumbPath, boolean hasBeenReviewed) {
        this.id = id;
        this.camera = camera;
        this.startTime = startTime;
        this.endTime = endTime;
        this.severity = severity;
        this.objects = objects != null ? objects : Collections.emptyList();
        this.detectionIds = detectionIds != null ? detectionIds : Collections.emptyList();
        this.thumbPath = (thumbPath != null && !thumbPath.trim().isEmpty()) ? thumbPath.trim() : null;
        this.hasBeenReviewed = hasBeenReviewed;
    }
}
