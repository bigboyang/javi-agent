package com.agent.logs;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로그 샘플링 필터.
 *
 * <p>수집 여부를 세 단계로 결정한다:
 * <ol>
 *   <li>최소 레벨 필터 — 설정값 미만 레벨은 즉시 드랍 (기본: INFO)</li>
 *   <li>레벨별 샘플링 — DEBUG/INFO에 대해 확률적 샘플링 (기본: DEBUG=0.0, INFO=1.0)</li>
 *   <li>초당 속도 제한 — 전체 로그 처리량 상한 (기본: 1000/s, 0=무제한)</li>
 * </ol>
 *
 * <p>WARN/ERROR/FATAL은 항상 수집한다 (샘플링·속도 제한 적용 안 함).
 *
 * <p>설정 우선순위: 환경변수 > 시스템 프로퍼티 > 기본값
 * <ul>
 *   <li>JAVI_LOG_MIN_LEVEL / javi.log.min.level — 최소 수집 레벨 (기본: INFO)</li>
 *   <li>JAVI_LOG_SAMPLE_RATE_DEBUG / javi.log.sample.rate.debug — DEBUG 샘플링 비율 0.0~1.0 (기본: 0.0)</li>
 *   <li>JAVI_LOG_SAMPLE_RATE_INFO / javi.log.sample.rate.info — INFO 샘플링 비율 0.0~1.0 (기본: 1.0)</li>
 *   <li>JAVI_LOG_RATE_LIMIT_PER_SEC / javi.log.rate.limit.per.sec — 초당 최대 로그 수 (기본: 1000, 0=무제한)</li>
 * </ul>
 */
public final class LogSampler {

    // Severity ordinals — 오름차순으로 비교에 사용
    static final int TRACE = 0;
    static final int DEBUG = 1;
    static final int INFO  = 2;
    static final int WARN  = 3;
    static final int ERROR = 4;
    static final int FATAL = 5;

    /** 전역 싱글턴 — 환경변수/시스템 프로퍼티에서 자동 초기화된다. */
    public static final LogSampler INSTANCE;

    static {
        int minLevel    = levelOrdinal(get("JAVI_LOG_MIN_LEVEL",            "javi.log.min.level",             "INFO"));
        double dbgRate  = parseDouble(get("JAVI_LOG_SAMPLE_RATE_DEBUG",     "javi.log.sample.rate.debug",     "0.0"));
        double infoRate = parseDouble(get("JAVI_LOG_SAMPLE_RATE_INFO",      "javi.log.sample.rate.info",      "1.0"));
        long rateLimit  = parseLong(get("JAVI_LOG_RATE_LIMIT_PER_SEC",      "javi.log.rate.limit.per.sec",    "1000"));
        INSTANCE = new LogSampler(minLevel, dbgRate, infoRate, rateLimit);
    }

    private final int    minLevelOrdinal;
    private final double debugSampleRate;
    private final double infoSampleRate;
    private final long   rateLimitPerSec; // 0 = 무제한

    // 1초 고정 윈도우 속도 제한기
    private final AtomicLong windowStartMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong windowCount   = new AtomicLong(0);

    LogSampler(int minLevelOrdinal, double debugSampleRate, double infoSampleRate, long rateLimitPerSec) {
        this.minLevelOrdinal = minLevelOrdinal;
        this.debugSampleRate = Math.max(0.0, Math.min(1.0, debugSampleRate));
        this.infoSampleRate  = Math.max(0.0, Math.min(1.0, infoSampleRate));
        this.rateLimitPerSec = rateLimitPerSec;
    }

    /**
     * 이 로그를 수집해야 하는지 판단한다.
     *
     * @param level 로그 레벨 문자열 (TRACE/DEBUG/INFO/WARN/WARNING/ERROR/SEVERE/FATAL)
     * @return true이면 수집, false이면 드랍
     */
    public boolean shouldSample(String level) {
        int ord = levelOrdinal(level);

        // WARN 이상은 항상 수집 — 아래 두 필터를 건너뜀
        if (ord >= WARN) return true;

        // 1. 최소 레벨 필터
        if (ord < minLevelOrdinal) return false;

        // 2. 레벨별 샘플링
        double rate = (ord <= DEBUG) ? debugSampleRate : infoSampleRate;
        if (rate <= 0.0) return false;
        if (rate < 1.0 && ThreadLocalRandom.current().nextDouble() >= rate) return false;

        // 3. 초당 속도 제한 (WARN+ 제외하고 적용)
        if (rateLimitPerSec > 0 && isRateLimited()) return false;

        return true;
    }

    /**
     * 현재 설정값을 사람이 읽을 수 있는 형태로 반환한다 (시작 로그용).
     */
    public String describe() {
        return "LogSampler[minLevel=" + levelName(minLevelOrdinal)
                + " debugRate=" + debugSampleRate
                + " infoRate=" + infoSampleRate
                + " rateLimit=" + (rateLimitPerSec == 0 ? "unlimited" : rateLimitPerSec + "/s")
                + "]";
    }

    // ---- private helpers ----

    private boolean isRateLimited() {
        long now = System.currentTimeMillis();
        long start = windowStartMs.get();
        if (now - start >= 1000L) {
            // 새 윈도우로 전환 시도 (CAS로 한 스레드만 리셋)
            if (windowStartMs.compareAndSet(start, now)) {
                windowCount.set(1);
                return false;
            }
        }
        return windowCount.incrementAndGet() > rateLimitPerSec;
    }

    static int levelOrdinal(String level) {
        if (level == null) return INFO;
        switch (level.toUpperCase()) {
            case "TRACE":                return TRACE;
            case "DEBUG":                return DEBUG;
            case "WARN": case "WARNING": return WARN;
            case "ERROR": case "SEVERE": return ERROR;
            case "FATAL":                return FATAL;
            default:                     return INFO;
        }
    }

    private static String levelName(int ord) {
        switch (ord) {
            case TRACE: return "TRACE";
            case DEBUG: return "DEBUG";
            case INFO:  return "INFO";
            case WARN:  return "WARN";
            case ERROR: return "ERROR";
            case FATAL: return "FATAL";
            default:    return "INFO";
        }
    }

    private static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 1.0; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 1000L; }
    }
}
