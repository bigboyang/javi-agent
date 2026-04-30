package com.agent.common;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

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
 */
public final class ProtoEncoder {

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_64BIT  = 1;
    public static final int WIRE_LEN    = 2;
    public static final int WIRE_32BIT  = 5;

    private ProtoEncoder() {}

    // ---- ThreadLocal BAOS 풀 (packed 인코딩 내부 임시 버퍼용) ----

    private static final ThreadLocal<ArrayDeque<ByteArrayOutputStream>> TL_POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static ByteArrayOutputStream borrow(int hint) {
        ByteArrayOutputStream b = TL_POOL.get().poll();
        if (b == null) b = new ByteArrayOutputStream(hint);
        else b.reset();
        return b;
    }

    private static void release(ByteArrayOutputStream b) {
        TL_POOL.get().push(b);
    }

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

    /** uint64 (varint-encoded) 필드. OTel histogram count 등 uint64 타입에 사용. */
    public static void writeUint64Field(ByteArrayOutputStream out, int fieldNumber, long value) {
        if (value == 0L) return;
        writeTag(out, fieldNumber, WIRE_VARINT);
        writeRawVarint64(out, value);
    }

    /**
     * sint32 (zigzag-encoded varint) 필드. ExponentialHistogram의 scale, Buckets의 offset 등에 사용.
     * 0도 유효한 값이므로 반드시 기록한다 (scale=0은 기본 버킷 너비, offset=0은 첫 버킷이 인덱스 0).
     */
    public static void writeSint32Field(ByteArrayOutputStream out, int fieldNumber, int value) {
        writeTag(out, fieldNumber, WIRE_VARINT);
        writeRawVarint32(out, (value << 1) ^ (value >> 31));
    }

    /** fixed32 (span flags 등) 필드. */
    public static void writeFixed32Field(ByteArrayOutputStream out, int fieldNumber, int value) {
        if (value == 0) return;
        writeTag(out, fieldNumber, WIRE_32BIT);
        out.write( value        & 0xFF);
        out.write((value >>  8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    /**
     * string 필드를 빈 문자열이더라도 강제로 쓴다.
     * AnyValue.string_value처럼 빈 문자열로 타입을 명시해야 할 때 사용.
     */
    public static void writeStringAlways(ByteArrayOutputStream out, int fieldNumber, String value) {
        byte[] bytes = (value != null ? value : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeTag(out, fieldNumber, WIRE_LEN);
        writeLengthDelimited(out, bytes);
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

    public static void writePackedUint64(ByteArrayOutputStream out, int fieldNumber, long[] values) {
        if (values == null || values.length == 0) return;
        ByteArrayOutputStream packed = borrow(values.length * 4);
        try {
            for (long v : values) writeRawVarint64(packed, v);
            writeTag(out, fieldNumber, WIRE_LEN);
            writeLengthDelimited(out, packed.toByteArray());
        } finally {
            release(packed);
        }
    }

    public static void writePackedFixed64(ByteArrayOutputStream out, int fieldNumber, long[] values) {
        if (values == null || values.length == 0) return;
        ByteArrayOutputStream packed = borrow(values.length * 8);
        try {
            for (long v : values) writeFixed64(packed, v);
            writeTag(out, fieldNumber, WIRE_LEN);
            writeLengthDelimited(out, packed.toByteArray());
        } finally {
            release(packed);
        }
    }

    public static void writePackedDouble(ByteArrayOutputStream out, int fieldNumber, double[] values) {
        if (values == null || values.length == 0) return;
        ByteArrayOutputStream packed = borrow(values.length * 8);
        try {
            for (double v : values) writeDouble(packed, v);
            writeTag(out, fieldNumber, WIRE_LEN);
            writeLengthDelimited(out, packed.toByteArray());
        } finally {
            release(packed);
        }
    }

    // ---- Hex 변환 (traceId / spanId) ----

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
}
