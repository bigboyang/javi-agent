package com.agent.metric;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that OtlpHttpMetricExporter produces valid proto bytes.
 * The encoded bytes are written to /tmp/java_metric_bytes.hex for Go-side verification.
 */
public class OtlpMetricEncoderTest {

    @Test
    public void testGaugeEncoding() throws Exception {
        Instant now = Instant.now();
        MetricData.Point point = new MetricData.Point(42.0, Collections.emptyMap(), now);
        MetricData metric = new MetricData("test.gauge", "test gauge", "bytes",
                MetricData.MetricType.GAUGE, Collections.singletonList(point));

        byte[] encoded = OtlpHttpMetricExporter.encodeExportRequest(
                Collections.singletonList(metric), "test-service");

        assertNotNull(encoded);
        assertTrue(encoded.length > 0, "Encoded bytes should not be empty");

        writeHexFile(encoded, "/tmp/java_gauge_bytes.bin");
        System.out.println("Gauge bytes (" + encoded.length + "): " + toHex(encoded));
    }

    @Test
    public void testSumEncoding() throws Exception {
        Instant now = Instant.now();
        MetricData.Point point = new MetricData.Point(100.0,
                Map.of("service", "http", "method", "GET"), now);
        MetricData metric = new MetricData("http.request.count", "http requests", "1",
                MetricData.MetricType.SUM, Collections.singletonList(point));

        byte[] encoded = OtlpHttpMetricExporter.encodeExportRequest(
                Collections.singletonList(metric), "test-service");

        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        writeHexFile(encoded, "/tmp/java_sum_bytes.bin");
        System.out.println("Sum bytes (" + encoded.length + "): " + toHex(encoded));
    }

    @Test
    public void testHistogramEncoding() throws Exception {
        Instant now = Instant.now();
        long[] buckets = {0, 5, 10, 3, 1, 0};
        double[] bounds = {5.0, 10.0, 25.0, 50.0, 100.0};

        MetricData.HistogramPoint hp = new MetricData.HistogramPoint(
                Map.of("service", "web"), now,
                19L,     // count
                234.5,   // sum
                1.2,     // min
                98.7,    // max
                buckets, bounds);

        MetricData metric = MetricData.ofHistogram(
                "http.server.duration", "server duration", "ms",
                Collections.singletonList(hp));

        byte[] encoded = OtlpHttpMetricExporter.encodeExportRequest(
                Collections.singletonList(metric), "test-service");

        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        writeHexFile(encoded, "/tmp/java_histogram_bytes.bin");
        System.out.println("Histogram bytes (" + encoded.length + "): " + toHex(encoded));
    }

    @Test
    public void testMultipleMetrics() throws Exception {
        Instant now = Instant.now();

        MetricData gauge = new MetricData("jvm.memory.used", "", "bytes",
                MetricData.MetricType.GAUGE,
                Collections.singletonList(new MetricData.Point(123456789.0,
                        Map.of("type", "heap"), now)));

        MetricData counter = new MetricData("jvm.gc.count", "", "1",
                MetricData.MetricType.SUM,
                Collections.singletonList(new MetricData.Point(5.0,
                        Map.of("gc", "G1 Young Generation"), now)));

        byte[] encoded = OtlpHttpMetricExporter.encodeExportRequest(
                Arrays.asList(gauge, counter), "e2e-test-app");

        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        writeHexFile(encoded, "/tmp/java_multi_bytes.bin");
        System.out.println("Multi bytes (" + encoded.length + "): " + toHex(encoded));
    }

    private void writeHexFile(byte[] data, String path) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(new File(path))) {
            fos.write(data);
        }
    }

    private String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
