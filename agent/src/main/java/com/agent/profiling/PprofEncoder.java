package com.agent.profiling;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Collapsed stacktrace → gzip-compressed pprof Profile protobuf 변환기.
 *
 * <p>pprof 파일 포맷: https://github.com/google/pprof/blob/main/proto/profile.proto
 * pprof 파일은 gzip-compressed protobuf binary 이다 (파일 포맷 수준의 gzip).
 * HTTP 전송 시 Content-Type: application/x-protobuf 이며, Content-Encoding 헤더는 사용하지 않는다.
 *
 * <p>입력 형식: {@code "root;middle;leaf"} → count (ThreadSamplingBackend 기준: 오래된 프레임이 앞)
 * pprof Sample.location_id: leaf-first (가장 최근 호출 프레임이 인덱스 0).
 */
public final class PprofEncoder {

    // Profile field numbers
    private static final int FN_PROFILE_SAMPLE_TYPE    = 1;
    private static final int FN_PROFILE_SAMPLE         = 2;
    private static final int FN_PROFILE_LOCATION       = 4;
    private static final int FN_PROFILE_FUNCTION       = 5;
    private static final int FN_PROFILE_STRING_TABLE   = 6;
    private static final int FN_PROFILE_DURATION_NANOS = 10;
    private static final int FN_PROFILE_PERIOD_TYPE    = 11;
    private static final int FN_PROFILE_PERIOD         = 12;

    // ValueType field numbers
    private static final int FN_VT_TYPE = 1;  // int64 (string index)
    private static final int FN_VT_UNIT = 2;  // int64 (string index)

    // Sample field numbers
    private static final int FN_SAMPLE_LOCATION_ID = 1; // repeated uint64, packed
    private static final int FN_SAMPLE_VALUE       = 2; // repeated int64, packed

    // Location field numbers
    private static final int FN_LOC_ID         = 1; // uint64
    private static final int FN_LOC_LINE       = 4; // repeated Line message

    // Line field numbers
    private static final int FN_LINE_FUNCTION_ID = 1; // uint64

    // Function field numbers
    private static final int FN_FUNC_ID          = 1; // uint64
    private static final int FN_FUNC_NAME        = 2; // int64 (string index)
    private static final int FN_FUNC_SYSTEM_NAME = 3; // int64 (string index)

    private PprofEncoder() {}

    /**
     * Collapsed stacktrace map → gzip-compressed pprof Profile protobuf.
     *
     * @param stackCounts  "root;middle;leaf" → sample count
     * @param profileType  "cpu" 또는 "alloc"
     * @param durationMs   샘플 수집 창 (밀리초)
     * @param periodMs     샘플링 간격 (밀리초)
     * @return gzip-compressed pprof Profile bytes
     */
    public static byte[] encode(Map<String, Integer> stackCounts,
                                 String profileType, long durationMs, long periodMs) {
        // String table: 인덱스 0은 반드시 "" (pprof spec)
        List<String> stringTable = new ArrayList<>();
        Map<String, Integer> stringIndex = new HashMap<>();
        stringTable.add("");
        stringIndex.put("", 0);

        // 레이블 인덱스 사전 등록
        int sampleTypeIdx   = intern(stringTable, stringIndex, "samples");
        int sampleUnitIdx   = intern(stringTable, stringIndex, "count");
        int periodTypeIdx   = intern(stringTable, stringIndex, profileType != null ? profileType : "cpu");
        int periodUnitIdx   = intern(stringTable, stringIndex, "nanoseconds");

        // Location / Function 테이블 (함수명 → ID, 순서 유지)
        Map<String, Long> locationByFrame  = new LinkedHashMap<>();
        List<byte[]>      encodedLocations = new ArrayList<>();
        List<byte[]>      encodedFunctions = new ArrayList<>();

        List<byte[]> encodedSamples = new ArrayList<>(stackCounts.size());

        for (Map.Entry<String, Integer> entry : stackCounts.entrySet()) {
            String stack = entry.getKey();
            int    count = entry.getValue();
            if (stack == null || stack.isEmpty() || count <= 0) continue;

            String[] frames = stack.split(";");
            long[]   locIds = new long[frames.length];

            // pprof: location_id[0] = leaf (가장 최근 호출). collapsed: frames[last] = leaf
            for (int i = 0; i < frames.length; i++) {
                String frame = frames[frames.length - 1 - i]; // 역순: leaf first
                Long locId = locationByFrame.get(frame);
                if (locId == null) {
                    long funcId = encodedFunctions.size() + 1L;
                    locId = encodedLocations.size() + 1L;

                    int nameIdx = intern(stringTable, stringIndex, frame);
                    encodedFunctions.add(encodeFunction(funcId, nameIdx));
                    encodedLocations.add(encodeLocation(locId, funcId));
                    locationByFrame.put(frame, locId);
                }
                locIds[i] = locId;
            }
            encodedSamples.add(encodeSample(locIds, count));
        }

        // Profile message 조립
        ByteArrayOutputStream profileOut = new ByteArrayOutputStream(
                encodedSamples.size() * 32 + encodedLocations.size() * 24 + stringTable.size() * 20);

        // sample_type: samples/count
        writeMessageField(profileOut, FN_PROFILE_SAMPLE_TYPE, encodeSampleType(sampleTypeIdx, sampleUnitIdx));

        for (byte[] s : encodedSamples)  writeMessageField(profileOut, FN_PROFILE_SAMPLE,   s);
        for (byte[] l : encodedLocations) writeMessageField(profileOut, FN_PROFILE_LOCATION, l);
        for (byte[] f : encodedFunctions) writeMessageField(profileOut, FN_PROFILE_FUNCTION, f);

        // string_table: index 0 = "" 포함, 모든 엔트리를 순서대로 기록
        for (String s : stringTable) writeStringFieldAlways(profileOut, FN_PROFILE_STRING_TABLE, s);

        if (durationMs > 0)
            writeInt64Field(profileOut, FN_PROFILE_DURATION_NANOS, durationMs * 1_000_000L);

        // period_type: cpu/nanoseconds
        writeMessageField(profileOut, FN_PROFILE_PERIOD_TYPE, encodeSampleType(periodTypeIdx, periodUnitIdx));
        writeInt64Field(profileOut, FN_PROFILE_PERIOD, periodMs * 1_000_000L);

        return gzip(profileOut.toByteArray());
    }

    // ---- 내부 encode helpers ----

    private static int intern(List<String> table, Map<String, Integer> idx, String s) {
        Integer i = idx.get(s);
        if (i != null) return i;
        int newIdx = table.size();
        table.add(s);
        idx.put(s, newIdx);
        return newIdx;
    }

    private static byte[] encodeSampleType(int typeIdx, int unitIdx) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        writeInt64Field(out, FN_VT_TYPE, typeIdx);
        writeInt64Field(out, FN_VT_UNIT, unitIdx);
        return out.toByteArray();
    }

    private static byte[] encodeSample(long[] locationIds, long count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(locationIds.length * 4 + 8);
        writePackedUint64(out, FN_SAMPLE_LOCATION_ID, locationIds);
        writePackedInt64(out,  FN_SAMPLE_VALUE,       new long[]{count});
        return out.toByteArray();
    }

    private static byte[] encodeLocation(long id, long functionId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        writeUint64Field(out, FN_LOC_ID, id);
        // line message: function_id only (line number 0 = unknown)
        ByteArrayOutputStream lineOut = new ByteArrayOutputStream(4);
        writeUint64Field(lineOut, FN_LINE_FUNCTION_ID, functionId);
        writeMessageField(out, FN_LOC_LINE, lineOut.toByteArray());
        return out.toByteArray();
    }

    private static byte[] encodeFunction(long id, int nameIdx) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        writeUint64Field(out, FN_FUNC_ID,          id);
        writeInt64Field(out,  FN_FUNC_NAME,        nameIdx);
        writeInt64Field(out,  FN_FUNC_SYSTEM_NAME, nameIdx);
        return out.toByteArray();
    }

    // ---- 최소 protobuf 인코딩 (ProtoEncoder 의존 없이 독립) ----

    private static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeRawVarint32(out, (fieldNumber << 3) | wireType);
    }

    private static void writeRawVarint32(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeRawVarint64(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeInt64Field(ByteArrayOutputStream out, int fieldNumber, long value) {
        if (value == 0L) return;
        writeTag(out, fieldNumber, 0); // VARINT
        writeRawVarint64(out, value);
    }

    private static void writeUint64Field(ByteArrayOutputStream out, int fieldNumber, long value) {
        if (value == 0L) return;
        writeTag(out, fieldNumber, 0); // VARINT
        writeRawVarint64(out, value);
    }

    /** string_table 항목은 빈 문자열(인덱스 0)도 반드시 기록해야 한다. */
    private static void writeStringFieldAlways(ByteArrayOutputStream out, int fieldNumber, String value) {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        writeTag(out, fieldNumber, 2); // LEN
        writeRawVarint32(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeMessageField(ByteArrayOutputStream out, int fieldNumber, byte[] encoded) {
        if (encoded == null || encoded.length == 0) return;
        writeTag(out, fieldNumber, 2); // LEN
        writeRawVarint32(out, encoded.length);
        out.write(encoded, 0, encoded.length);
    }

    private static void writePackedUint64(ByteArrayOutputStream out, int fieldNumber, long[] values) {
        if (values == null || values.length == 0) return;
        ByteArrayOutputStream packed = new ByteArrayOutputStream(values.length * 4);
        for (long v : values) writeRawVarint64(packed, v);
        writeTag(out, fieldNumber, 2); // LEN
        writeRawVarint32(out, packed.size());
        out.write(packed.toByteArray(), 0, packed.size());
    }

    private static void writePackedInt64(ByteArrayOutputStream out, int fieldNumber, long[] values) {
        if (values == null || values.length == 0) return;
        ByteArrayOutputStream packed = new ByteArrayOutputStream(values.length * 4);
        for (long v : values) writeRawVarint64(packed, v);
        writeTag(out, fieldNumber, 2); // LEN
        writeRawVarint32(out, packed.size());
        out.write(packed.toByteArray(), 0, packed.size());
    }

    private static byte[] gzip(byte[] data) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2 + 64);
             GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
            gzos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            return data; // fallback: uncompressed (드물어야 함)
        }
    }
}
