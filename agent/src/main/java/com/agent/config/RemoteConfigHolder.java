package com.agent.config;

/**
 * 현재 유효한 RemoteConfig 스냅샷을 보유하는 전역 홀더.
 *
 * volatile 참조를 통해 락 없이 스레드 안전한 읽기/쓰기를 보장한다.
 * 읽기는 최적화된 핫패스(샘플러, 어드바이스)에서 직접 호출된다.
 */
public final class RemoteConfigHolder {

    private static volatile RemoteConfig current = RemoteConfig.defaults();

    /** 현재 설정 스냅샷을 반환한다. 절대 null을 반환하지 않는다. */
    public static RemoteConfig get() {
        return current;
    }

    /** 새 설정 스냅샷으로 원자적으로 교체한다. */
    public static void update(RemoteConfig config) {
        if (config != null) {
            current = config;
        }
    }

    private RemoteConfigHolder() {}
}
