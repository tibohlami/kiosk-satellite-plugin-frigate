package io.github.tibohlami.ksplugins.frigate.model;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class FrigateEventTest {

    @Test
    public void testParseStandardFrigateEvent() {
        String jsonStr = "{\n" +
                "  \"id\": \"1710500123.456-abcdef\",\n" +
                "  \"camera\": \"camera_parking_avant\",\n" +
                "  \"label\": \"person\",\n" +
                "  \"top_score\": 0.89,\n" +
                "  \"start_time\": 1710500120.0,\n" +
                "  \"end_time\": 1710500145.0,\n" +
                "  \"has_clip\": true,\n" +
                "  \"has_snapshot\": true,\n" +
                "  \"zones\": [\"driveway\", \"front_yard\"]\n" +
                "}";

        JSONObject json = new JSONObject(jsonStr);
        FrigateEvent event = FrigateEvent.fromJson(json);

        assertEquals("1710500123.456-abcdef", event.getId());
        assertEquals("camera_parking_avant", event.getCamera());
        assertEquals("person", event.getLabel());
        assertEquals(0.89, event.getTopScore(), 0.001);
        assertEquals(25.0, event.getDurationSeconds(), 0.001);
        assertEquals("25s", event.getFormattedDuration());
        assertEquals("👤", event.getLabelIcon());
        assertTrue(event.hasClip());
        assertTrue(event.hasSnapshot());
        assertEquals(2, event.getZones().size());
        assertEquals("driveway", event.getZones().get(0));
    }

    @Test
    public void testParseFrigateReviewEvent() {
        String jsonStr = "{\n" +
                "  \"id\": \"review-12345\",\n" +
                "  \"camera\": \"cam_parking\",\n" +
                "  \"start_time\": 1710500000.0,\n" +
                "  \"end_time\": 1710500090.0,\n" +
                "  \"severity\": \"alert\",\n" +
                "  \"data\": {\n" +
                "    \"objects\": [\"car\"],\n" +
                "    \"zones\": [\"parking\"]\n" +
                "  }\n" +
                "}";

        JSONObject json = new JSONObject(jsonStr);
        FrigateEvent event = FrigateEvent.fromJson(json);

        assertEquals("review-12345", event.getId());
        assertEquals("cam_parking", event.getCamera());
        assertEquals("car", event.getLabel());
        assertEquals("🚗", event.getLabelIcon());
        assertEquals(90.0, event.getDurationSeconds(), 0.001);
        assertEquals("1m 30s", event.getFormattedDuration());
        assertEquals("alert", event.getSeverity());
        assertEquals(1, event.getZones().size());
    }
}
