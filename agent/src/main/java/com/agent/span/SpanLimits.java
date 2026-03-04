package com.agent.span;

/**
 * Span에 기록 가능한 데이터의 상한값.
 */
public final class SpanLimits {
    private static final SpanLimits DEFAULT = new SpanLimits(128, 128, 128);

    private final int maxAttributes;
    private final int maxEvents;
    private final int maxLinks;
    private final java.util.concurrent.atomic.AtomicInteger droppedAttributes =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger droppedEvents =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger droppedLinks =
            new java.util.concurrent.atomic.AtomicInteger();

    public SpanLimits(int maxAttributes, int maxEvents, int maxLinks) {
        this.maxAttributes = maxAttributes;
        this.maxEvents = maxEvents;
        this.maxLinks = maxLinks;
    }

    public static SpanLimits defaultLimits() {
        return DEFAULT;
    }

    public int getMaxAttributes() {
        return maxAttributes;
    }

    public int getMaxEvents() {
        return maxEvents;
    }

    public int getMaxLinks() {
        return maxLinks;
    }

    public void recordDroppedAttribute() {
        droppedAttributes.incrementAndGet();
    }

    public void recordDroppedEvent() {
        droppedEvents.incrementAndGet();
    }

    public void recordDroppedLink() {
        droppedLinks.incrementAndGet();
    }

    public int getDroppedAttributes() {
        return droppedAttributes.get();
    }

    public int getDroppedEvents() {
        return droppedEvents.get();
    }

    public int getDroppedLinks() {
        return droppedLinks.get();
    }
}
