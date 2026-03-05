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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InstrumentationScopeInfo)) return false;
        InstrumentationScopeInfo that = (InstrumentationScopeInfo) o;
        return java.util.Objects.equals(name, that.name)
                && java.util.Objects.equals(version, that.version)
                && java.util.Objects.equals(schemaUrl, that.schemaUrl);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, version, schemaUrl);
    }
}
