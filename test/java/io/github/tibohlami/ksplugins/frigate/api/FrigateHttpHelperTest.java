package io.github.tibohlami.ksplugins.frigate.api;

import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import org.junit.Test;

import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class FrigateHttpHelperTest {

    @Test
    public void testIsConfiguredFrigateHostExactAndCaseInsensitive() throws Exception {
        FrigateConfig config = new FrigateConfig(null);
        Map<String, Object> settings = new HashMap<>();
        settings.put(FrigateConfig.KEY_FRIGATE_URL, "http://frigate.lan:5000");
        config.updateFrom(settings);

        assertTrue(FrigateHttpHelper.isConfiguredFrigateHost(new URL("http://frigate.lan:5000/api/events"), config));
        assertTrue(FrigateHttpHelper.isConfiguredFrigateHost(new URL("https://FRIGATE.LAN:8971/api/thumb.jpg"), config));
        assertTrue(FrigateHttpHelper.isConfiguredFrigateHost(new URL("http://frigate.lan/snapshot.jpg"), config));
    }

    @Test
    public void testIsConfiguredFrigateHostRejectsExternalHosts() throws Exception {
        FrigateConfig config = new FrigateConfig(null);
        Map<String, Object> settings = new HashMap<>();
        settings.put(FrigateConfig.KEY_FRIGATE_URL, "http://frigate.lan:5000");
        config.updateFrom(settings);

        assertFalse(FrigateHttpHelper.isConfiguredFrigateHost(new URL("http://evil.com/thumb.jpg"), config));
        assertFalse(FrigateHttpHelper.isConfiguredFrigateHost(new URL("https://external-cdn.net/image.png"), config));
        assertFalse(FrigateHttpHelper.isConfiguredFrigateHost(new URL("http://frigate.lan.attacker.com/thumb.jpg"), config));
    }

    @Test
    public void testIsConfiguredFrigateHostNullSafety() throws Exception {
        FrigateConfig config = new FrigateConfig(null);
        assertFalse(FrigateHttpHelper.isConfiguredFrigateHost(null, config));
        assertFalse(FrigateHttpHelper.isConfiguredFrigateHost(new URL("http://frigate.lan/img.jpg"), null));
        assertFalse(FrigateHttpHelper.isConfiguredFrigateHost(null, null));
    }

    @Test
    public void testBuildAuthHeaders() {
        Map<String, String> headers = FrigateHttpHelper.buildAuthHeaders("my-secret-token");
        assertEquals("Bearer my-secret-token", headers.get("Authorization"));
        assertEquals("frigate_token=my-secret-token", headers.get("Cookie"));

        Map<String, String> bearerHeaders = FrigateHttpHelper.buildAuthHeaders("Bearer already-prefixed");
        assertEquals("Bearer already-prefixed", bearerHeaders.get("Authorization"));
        assertNull(bearerHeaders.get("Cookie"));

        assertTrue(FrigateHttpHelper.buildAuthHeaders((String) null).isEmpty());
        assertTrue(FrigateHttpHelper.buildAuthHeaders("   ").isEmpty());
    }
}
