package com.agent.common.utils.core;

/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * {@link ThreadLocal} buffers for use when creating new derived objects such as {@link String}s.
 * These buffers are reused within a single thread - it is _not safe_ to use the buffer to generate
 * multiple derived objects at the same time because the same memory will be used. In general, you
 * should get a temporary buffer, fill it with data, and finish by converting into the derived
 * object within the same method to avoid multiple usages of the same buffer.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 * 
 * 성능 최적화용 임시 버퍼
 */
public final class TemporaryBuffers {

  private static final ThreadLocal<char[]> CHAR_ARRAY = new ThreadLocal<>();

  /**
   * A {@link ThreadLocal} {@code char[]} of size {@code len}. Take care when using a large value of
   * {@code len} as this buffer will remain for the lifetime of the thread. The returned buffer will
   * not be zeroed and may be larger than the requested size, you must make sure to fill the entire
   * content to the desired value and set the length explicitly when converting to a {@link String}.
   * 
   * 동시에 2개의 문자열을 만들 때 같은 버퍼를 공유하면 안됨
   * 즉, 버퍼를 받아 바로 채우고 바로 string으로 변환해야 함
   */
  public static char[] chars(int len) {
    char[] buffer = CHAR_ARRAY.get();
    if (buffer == null || buffer.length < len) {
      buffer = new char[len];
      CHAR_ARRAY.set(buffer);
    }
    return buffer;
  }

  // Visible for testing
  static void clearChars() {
    CHAR_ARRAY.set(null);
  }

  private TemporaryBuffers() {}
}
