package com.agent.common.grpc;

import java.io.ByteArrayOutputStream;

/**
 * Protobuf 바이너리 인코딩 유틸리티 (외부 라이브러리 불필요).
 *
 * <p>Wire types:
 * <ul>
 *   <li>0 = Varint  (bool, enum, int32/64, uint32/64, sint32/64)</li>
 *   <li>1 = 64-bit  (fixed64, sfixed64, double)</li>
 *   <li>2 = LEN     (string, bytes, embedded message, packed repeated)</li>
 *   <li>5 = 32-bit  (fixed32, sfixed32, float)</li>
 * </ul>
 *
 * <p>메모리 안전:
 * <ul>
 *   <li>모든 메서드는 ByteArrayOutputStream을 인자로 받아 상태를 갖지 않는다 (static).</li>
 *   <li>호출자가 스택에 BAOS를 할당하므로 힙 누수 없음.</li>
 *   <li>재귀적 중첩 메시지는 두-단계(inner-first) 인코딩으로 처리한다.</li>
 * </ul>
 */
public final class ProtoEncoder {

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_64BIT  = 1;
    public static final int WIRE_LEN    = 2;

    private ProtoEncoder() {}

    // ---- 태그 ----

    public static int makeTag(int fieldNumber, int wireType) {
        return (fieldNumber << 3) | wireType;
    }

    public static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeRawVarint32(out, makeTag(fieldNumber, wireType));
    }

    // ---- Varint ----

    public static void writeRawVarint32(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public static void writeRawVarint64(ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    // ---- Fixed 64-bit (timestamps, doubles) ----

    public static void writeFixed64(ByteArrayOutputStream out, long value) {
        out.write((int)( value        & 0xFF));
        out.write((int)((value >>  8) & 0xFF));
        out.write((int)((value >> 16) & 0xFF));
        out.write((int)((value >> 24) & 0xFF));
        out.write((int)((value >> 32) & 0xFF));
        out.write((int)((value >> 40) & 0xFF));
        out.write((int)((value >> 48) & 0xFF));
        out.write((int)((value >> 56) & 0xFF));
    }

    public static void writeDouble(ByteArrayOutputStream out, double value) {
        writeFixed64(out, Double.doubleToRawLongBits(value));
    }

    // ---- Length-delimited (LEN wire type) ----

    public static void writeLengthDelimited(ByteArrayOutputStream out, byte[] data) {
        writeRawVarint32(out, data.length);
        out.write(data, 0, data.length);
    }

    public static void writeString(ByteArrayOutputStream out, int fieldNumber, String value) {
        if (value == null || value.isEmpty()) return;
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeTag(out, fieldNumber, WIRE_LEN);
        writeLengthDelimited(out, bytes);
    }

    public static void writeBytes(ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        if (value == null || value.length == 0) return;
        writeTag(out, fieldNumber, WIRE_LEN);
        writeLengthDelimited(out, value);
    }

    /** 이미 인코딩된 byte[]를 embedded message 필드로 삽입한다. */
    public static void writeMessage(ByteArrayOutputStream out, int fieldNumber, byte[] encoded) {
        if (encoded == null || encoded.length == 0) return;
        writeTag(out, fieldNumber, WIRE_LEN);
        writeLengthDelimited(out, encoded);
    }

    /** varint (enum, bool, int32) 필드. 값이 0이면 proto3 기본값으로 생략한다. */
    public static void writeVarint32(ByteArrayOutputStream out, int fieldNumber, int value) {
        if (value == 0) return;
        writeTag(out, fieldNumber, WIRE_VARINT);
        writeRawVarint32(out, value);
    }

    /** fixed64 (타임스탬프 나노초) 필드. */
    public static void writeFixed64Field(ByteArrayOutputStream out, int fieldNumber, long value) {
        if (value == 0L) return;
        writeTag(out, fieldNumber, WIRE_64BIT);
        writeFixed64(out, value);
    }

    /** double 필드. */
    public static void writeDoubleField(ByteArrayOutputStream out, int fieldNumber, double value) {
        writeTag(out, fieldNumber, WIRE_64BIT);
        writeDouble(out, value);
    }

    // ---- Packed repeated 필드 (히스토그램 버킷) ----

    /**
     * Packed repeated uint64 필드 (OTLP HistogramDataPoint.bucket_counts).
     * Wire type = LEN, 각 값을 varint64로 직렬화한다.
     */
    public static void writePackedUint64(ByteArrayOutputStream out, int fieldNumber, long[] values) {
        if (values == null || values.length == 0) return;
        ByteArrayOutputStream packed = new ByteArrayOutputStream(values.length * 4);
        for (long v : values) writeRawVarint64(packed, v);
        writeTag(out, fieldNumber, WIRE_LEN);
        writeLengthDelimited(out, packed.toByteArray());
    }

    /**
     * Packed repeated double 필드 (OTLP HistogramDataPoint.explicit_bounds).
     * Wire type = LEN, 각 값을 fixed64(little-endian IEEE 754)로 직렬화한다.
     */
    public static void writePackedDouble(ByteArrayOutputStream out, int fieldNumber, double[] values) {
        if (values == null || values.length == 0) return;
        ByteArrayOutputStream packed = new ByteArrayOutputStream(values.length * 8);
        for (double v : values) writeDouble(packed, v);
        writeTag(out, fieldNumber, WIRE_LEN);
        writeLengthDelimited(out, packed.toByteArray());
    }

    // ---- Hex 변환 (traceId / spanId) ----

    /**
     * 16진수 문자열을 byte[]로 변환한다.
     * 전체가 0인 경우 null 반환 (필드 생략 처리).
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        int len = hex.length();
        byte[] data = new byte[len / 2];
        boolean allZero = true;
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            data[i / 2] = (byte) ((hi << 4) + lo);
            if (data[i / 2] != 0) allZero = false;
        }
        return allZero ? null : data;
    }

    // ---- gRPC 5-byte 프레임 래퍼 ----

    /**
     * protobuf bytes를 gRPC length-prefix 메시지로 래핑한다.
     *
     * <p>포맷: [0x00 (no compression)] [4 bytes big-endian length] [proto bytes]
     */
    public static byte[] wrapGrpcFrame(byte[] protoBytes) {
        byte[] frame = new byte[5 + protoBytes.length];
        frame[0] = 0x00; // compressed-flag = false
        int len = protoBytes.length;
        frame[1] = (byte)(len >> 24);
        frame[2] = (byte)(len >> 16);
        frame[3] = (byte)(len >>  8);
        frame[4] = (byte) len;
        System.arraycopy(protoBytes, 0, frame, 5, protoBytes.length);
        return frame;
    }
}
