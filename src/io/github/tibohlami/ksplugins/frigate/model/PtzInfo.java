// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ONVIF PTZ capabilities and presets returned by {@code GET /api/<camera>/ptz/info}.
 */
public final class PtzInfo {

    public static final PtzInfo UNSUPPORTED = new PtzInfo(false, false, Collections.emptyList());

    public final boolean hasPanTilt;
    public final boolean hasZoom;
    public final List<String> presets;

    public PtzInfo(boolean hasPanTilt, boolean hasZoom, List<String> presets) {
        this.hasPanTilt = hasPanTilt;
        this.hasZoom = hasZoom;
        this.presets = presets != null
                ? Collections.unmodifiableList(new ArrayList<>(presets))
                : Collections.emptyList();
    }

    public boolean isSupported() {
        return hasPanTilt || hasZoom || !presets.isEmpty();
    }

    public static PtzInfo fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return UNSUPPORTED;
        try {
            JSONObject obj = new JSONObject(json);
            boolean pt = false;
            boolean zoom = false;
            JSONArray features = obj.optJSONArray("features");
            if (features != null) {
                for (int i = 0; i < features.length(); i++) {
                    String f = features.optString(i, "").trim().toLowerCase();
                    if (f.startsWith("pt")) pt = true;
                    if (f.startsWith("zoom")) zoom = true;
                }
            }
            List<String> presetList = new ArrayList<>();
            JSONArray presetsArr = obj.optJSONArray("presets");
            if (presetsArr != null) {
                for (int i = 0; i < presetsArr.length(); i++) {
                    String p = presetsArr.optString(i, "").trim();
                    if (!p.isEmpty()) presetList.add(p);
                }
            }
            return new PtzInfo(pt, zoom, presetList);
        } catch (Throwable ignored) {
            return UNSUPPORTED;
        }
    }
}
