// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.api;

import io.github.tibohlami.ksplugins.frigate.model.DaySummary;
import io.github.tibohlami.ksplugins.frigate.model.FrigateCamera;
import io.github.tibohlami.ksplugins.frigate.model.FrigateEvent;
import io.github.tibohlami.ksplugins.frigate.model.RecordingSegment;
import io.github.tibohlami.ksplugins.frigate.model.ReviewSegment;
import io.github.tibohlami.ksplugins.frigate.model.TimelineBoxEntry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless JSON response parsers for Frigate HTTP API endpoints.
 */
final class FrigateJsonParser {

    private FrigateJsonParser() {}

    static List<RecordingSegment> parseRecordings(String response) throws Exception {
        JSONArray array = new JSONArray(response);
        List<RecordingSegment> segments = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            double start = obj.optDouble("start_time", 0.0);
            double end = obj.optDouble("end_time", start);
            int motion = obj.optInt("motion", 0);
            int objects = obj.optInt("objects", 0);
            if (end > start && start > 0) {
                segments.add(new RecordingSegment(start, end, motion, objects));
            }
        }
        return segments;
    }

    static List<ReviewSegment> parseReviewSegments(String response, long fallbackEnd) throws Exception {
        JSONArray array = new JSONArray(response);
        List<ReviewSegment> reviews = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String cam = obj.optString("camera", "");
            if (FrigateApiClient.isVirtualReplayCamera(cam)) continue;
            double start = obj.optDouble("start_time", 0.0);
            double end = obj.isNull("end_time")
                    ? (double) fallbackEnd
                    : obj.optDouble("end_time", (double) fallbackEnd);
            if (start > 0 && end >= start) {
                String id = obj.optString("id", null);
                String revSev = obj.optString("severity", "detection");
                if (revSev.isEmpty()) revSev = "detection";
                String thumbPath = obj.isNull("thumb_path") ? null : obj.optString("thumb_path", null);
                boolean hasBeenReviewed = obj.optBoolean("has_been_reviewed", false);
                List<String> objs = new ArrayList<>();
                List<String> detIds = new ArrayList<>();
                JSONObject data = obj.optJSONObject("data");
                if (data != null) {
                    collectNonEmptyStrings(data.optJSONArray("objects"), objs);
                    collectNonEmptyStrings(data.optJSONArray("detections"), detIds);
                }
                reviews.add(new ReviewSegment(id, cam, start, Math.max(end, start + 1.0),
                        revSev, objs, detIds, thumbPath, hasBeenReviewed));
            }
        }
        return reviews;
    }

    static List<TimelineBoxEntry> parseEventTimelineBoxes(String response, String fallbackEventId) throws Exception {
        JSONArray array = new JSONArray(response);
        List<TimelineBoxEntry> entries = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            double ts = obj.optDouble("timestamp", 0.0);
            JSONObject data = obj.optJSONObject("data");
            if (ts <= 0.0 || data == null) continue;
            JSONArray boxArr = data.optJSONArray("box");
            if (boxArr == null || boxArr.length() != 4) continue;
            float bx = (float) boxArr.optDouble(0, -1.0);
            float by = (float) boxArr.optDouble(1, -1.0);
            float bw = (float) boxArr.optDouble(2, -1.0);
            float bh = (float) boxArr.optDouble(3, -1.0);
            if (bw > 0f && bh > 0f && bx >= 0f && by >= 0f) {
                entries.add(new TimelineBoxEntry(
                        obj.optString("source_id", fallbackEventId),
                        obj.optString("class_type", ""),
                        data.optString("label", ""),
                        ts, bx, by, bw, bh));
            }
        }
        Collections.sort(entries, (a, b) -> Double.compare(a.timestamp, b.timestamp));
        return entries;
    }

    static List<FrigateEvent> parseEvents(String response) throws Exception {
        JSONArray array = new JSONArray(response);
        List<FrigateEvent> events = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            FrigateEvent ev = FrigateEvent.fromJson(array.getJSONObject(i));
            if (!FrigateApiClient.isVirtualReplayCamera(ev.getCamera())) {
                events.add(ev);
            }
        }
        return events;
    }

    static List<FrigateCamera> parseCamerasFromStats(String response) throws Exception {
        JSONObject stats = new JSONObject(response);
        JSONObject camerasJson = stats.optJSONObject("cameras");
        List<FrigateCamera> cameras = new ArrayList<>();
        if (camerasJson != null) {
            Iterator<String> keys = camerasJson.keys();
            while (keys.hasNext()) {
                String camName = keys.next();
                if (FrigateApiClient.isVirtualReplayCamera(camName)) {
                    continue;
                }
                JSONObject camData = camerasJson.optJSONObject(camName);
                double camFps = camData != null ? camData.optDouble("camera_fps", 0.0) : 0.0;
                double detFps = camData != null ? camData.optDouble("detection_fps", 0.0) : 0.0;
                cameras.add(new FrigateCamera(camName, null, camFps, detFps, true));
            }
        }
        return cameras;
    }

    static List<String> parseTrackedLabelsFromConfig(String response) throws Exception {
        JSONObject cfg = new JSONObject(response);
        Set<String> labels = new LinkedHashSet<>();
        collectTrackedLabels(cfg.optJSONObject("objects"), labels);

        JSONObject camerasObj = cfg.optJSONObject("cameras");
        if (camerasObj != null) {
            Iterator<String> keys = camerasObj.keys();
            while (keys.hasNext()) {
                String camName = keys.next();
                if (FrigateApiClient.isVirtualReplayCamera(camName)) continue;
                JSONObject camCfg = camerasObj.optJSONObject(camName);
                if (camCfg != null) {
                    collectTrackedLabels(camCfg.optJSONObject("objects"), labels);
                }
            }
        }
        return new ArrayList<>(labels);
    }

    static List<DaySummary> parseEventsSummary(String response) throws Exception {
        JSONArray array = new JSONArray(response);
        List<DaySummary> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String day = obj.optString("day", "").trim();
            if (day.length() > 10 && day.charAt(4) == '-' && day.charAt(7) == '-') {
                day = day.substring(0, 10);
            }
            String cam = obj.optString("camera", "").trim();
            String lbl = obj.optString("label", "").trim();
            int count = obj.optInt("count", obj.optInt("events", 0));
            if (day.isEmpty() || count <= 0 || FrigateApiClient.isVirtualReplayCamera(cam)) continue;
            out.add(new DaySummary(day, cam, lbl, count));
        }
        return out;
    }

    static List<DaySummary> parseReviewSummary(String response) throws Exception {
        String trimmed = response != null ? response.trim() : "";
        List<DaySummary> out = new ArrayList<>();
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                addReviewSummaryEntry(out, obj != null ? obj.optString("day", "") : "", obj);
            }
        } else if (trimmed.startsWith("{")) {
            JSONObject root = new JSONObject(trimmed);
            JSONArray summaryArr = root.optJSONArray("summary");
            if (summaryArr != null) {
                for (int i = 0; i < summaryArr.length(); i++) {
                    JSONObject obj = summaryArr.optJSONObject(i);
                    addReviewSummaryEntry(out, obj != null ? obj.optString("day", "") : "", obj);
                }
            } else {
                Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject obj = root.optJSONObject(key);
                    if (obj != null) {
                        addReviewSummaryEntry(out, obj.optString("day", key), obj);
                    }
                }
            }
        }
        return out;
    }

    private static void collectNonEmptyStrings(JSONArray arr, List<String> dest) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) dest.add(s);
        }
    }

    private static void collectTrackedLabels(JSONObject objSection, Set<String> dest) {
        if (objSection == null) return;
        JSONArray track = objSection.optJSONArray("track");
        if (track == null) return;
        for (int i = 0; i < track.length(); i++) {
            String lbl = track.optString(i, "").trim();
            if (!lbl.isEmpty()) dest.add(lbl);
        }
    }

    private static void addReviewSummaryEntry(List<DaySummary> out, String rawDay, JSONObject obj) {
        if (obj == null || rawDay == null) return;
        String day = rawDay.trim();
        if (day.length() >= 10 && day.charAt(4) == '-' && day.charAt(7) == '-') {
            day = day.substring(0, 10);
        } else {
            return;
        }
        int alerts = Math.max(obj.optInt("total_alert", 0), obj.optInt("reviewed_alert", 0));
        int dets = Math.max(obj.optInt("total_detection", 0), obj.optInt("reviewed_detection", 0));
        int total = alerts + dets;
        if (total <= 0) {
            total = obj.optInt("count", obj.optInt("events", 0));
        }
        if (total > 0) {
            out.add(new DaySummary(day, "", "", total));
        }
    }
}
