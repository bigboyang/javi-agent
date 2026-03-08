package com.agent.metric;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 값 분포 히스토그램.
 *
 * <p>count, sum, min, max를 추적한다. 외부 의존성 없이 JDK만으로 구현하기 위해
 * percentile 계산 대신 min/max/avg를 제공한다.
 */
public final class Histogram {

    private final String name;
    private final LongAdder count = new LongAdder();
    private final LongAdder sum = new LongAdder();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

    Histogram(String name) {
        this.name = name;
    }

    public void record(long value) {
        count.increment();
        sum.add(value);
        updateMin(value);
        updateMax(value);
    }

    public long getCount() {
        return count.sum();
    }

    public long getSum() {
        return sum.sum();
    }

    public long getMin() {
        long v = min.get();
        return v == Long.MAX_VALUE ? 0 : v;
    }

    public long getMax() {
        long v = max.get();
        return v == Long.MIN_VALUE ? 0 : v;
    }

    public double getAvg() {
        long c = count.sum();
        return c == 0 ? 0.0 : (double) sum.sum() / c;
    }

    public String getName() {
        return name;
    }

    private void updateMin(long value) {
        long current;
        do {
            current = min.get();
            if (value >= current) return;
        } while (!min.compareAndSet(current, value));
    }

    private void updateMax(long value) {
        long current;
        do {
            current = max.get();
            if (value <= current) return;
        } while (!max.compareAndSet(current, value));
    }
}
