package com.agent.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;

public class ResourceInfoTest {

    @Test
    public void testGetAttributes() {
        Map<String, String> attrs = ResourceInfo.getAttributes();
        assertNotNull(attrs);
        
        // Basic attributes
        assertTrue(attrs.containsKey("host.name"));
        assertTrue(attrs.containsKey("service.instance.id"));
        assertTrue(attrs.containsKey("os.type"));
        assertTrue(attrs.containsKey("telemetry.sdk.name"));
        assertEquals("javi-agent", attrs.get("telemetry.sdk.name"));
        assertEquals("java", attrs.get("telemetry.sdk.language"));
        assertEquals("1.0.0", attrs.get("telemetry.sdk.version"));
    }
}
