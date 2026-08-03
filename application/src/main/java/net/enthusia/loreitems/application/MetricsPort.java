package net.enthusia.loreitems.application;

public interface MetricsPort {
    void setGauge(String name, long value);

    void increment(String name);

    void recordDurationNanos(String name, long durationNanos);

    static MetricsPort noOp() {
        return NoOpMetrics.INSTANCE;
    }

    enum NoOpMetrics implements MetricsPort {
        INSTANCE;

        @Override
        public void setGauge(String name, long value) {
        }

        @Override
        public void increment(String name) {
        }

        @Override
        public void recordDurationNanos(String name, long durationNanos) {
        }
    }
}
