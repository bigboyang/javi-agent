package com.agent.logs;

import java.time.Instant;
import java.util.Map;

/**
 * 수집된 개별 로그 레코드를 나타내는 객체.
 */
public class LogRecord {
    private final Instant timestamp;
    private final String severity;
    private final String body;
    private final String traceId;
    private final String spanId;
    private final String loggerName;
    private final Map<String, String> attributes;

    public LogRecord(Instant timestamp, String severity, String body, 
                     String traceId, String spanId, String loggerName, 
                     Map<String, String> attributes) {
        this.timestamp = timestamp;
        this.severity = severity;
        this.body = body;
        this.traceId = traceId;
        this.spanId = spanId;
        this.loggerName = loggerName;
        this.attributes = attributes;
    }

    public Instant getTimestamp() { return timestamp; }
    public String getSeverity() { return severity; }
    public String getBody() { return body; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getLoggerName() { return loggerName; }
    public Map<String, String> getAttributes() { return attributes; }
}
