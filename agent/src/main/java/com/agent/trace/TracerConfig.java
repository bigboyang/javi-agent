package com.agent.trace;

/**
 * Tracer 동작을 제어하는 최소 설정 객체.
 */
public final class TracerConfig {
    private static final TracerConfig ENABLED = new TracerConfig(true);
    private static final TracerConfig DISABLED = new TracerConfig(false);

    private final boolean enabled;

    private TracerConfig(boolean enabled) {
        this.enabled = enabled;
    }

    public static TracerConfig enabled() {
        return ENABLED;
    }

    public static TracerConfig disabled() {
        return DISABLED;
    }

    public static TracerConfig defaultConfig() {
        return ENABLED;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
