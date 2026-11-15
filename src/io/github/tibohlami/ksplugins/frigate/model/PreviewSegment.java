// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.model;

/**
 * Represents an hourly preview MP4 entry returned by {@code /api/preview/<camera>/start/.../end/...}.
 */
public class PreviewSegment {
    public final String camera;
    public final String src;
    public final String type;
    public final double start;
    public final double end;

    public PreviewSegment(String camera, String src, String type, double start, double end) {
        this.camera = camera != null ? camera : "";
        this.src = src != null ? src : "";
        this.type = type != null ? type : "";
        this.start = start;
        this.end = end;
    }
}
