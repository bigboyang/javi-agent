package com.agent.logs.sdkLog;

import com.agent.logs.AgentLogger;
import java.util.logging.Level;

/**
 * Helper for API misuse logging.
 *
 * <p>API 오용을 탐지했을 때 경고 로그를 남기기 위한 내부 로거다.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class ApiUsageLogger {

  /** Log at WARN level (API misuse is always noteworthy). */
  public static void log(String message) {
    AgentLogger.warn("[API-MISUSE] " + message);
  }

  public static void log(String message, Level level) {
    if (level.intValue() >= Level.WARNING.intValue()) {
      AgentLogger.warn("[API-MISUSE] " + message);
    } else {
      AgentLogger.debug("[API-MISUSE] " + message);
    }
  }

  private ApiUsageLogger() {}
}
