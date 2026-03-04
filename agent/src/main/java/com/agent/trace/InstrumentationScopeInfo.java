package com.agent.trace;

/**
 * 트레이서가 속한 instrumentation 정보(이름/버전/스키마).
 */
public final class InstrumentationScopeInfo {
    private final String name;
    private final String version;
    private final String schemaUrl;

    public InstrumentationScopeInfo(String name, String version, String schemaUrl) {
        this.name = name;
        this.version = version;
        this.schemaUrl = schemaUrl;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getSchemaUrl() {
        return schemaUrl;
    }
}
