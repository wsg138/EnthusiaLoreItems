package net.enthusia.loreitems.domain;

public record TemplateRevision(long value) implements Comparable<TemplateRevision> {
    private static final long MIN_REVISION = 1L;

    public TemplateRevision {
        if (value < MIN_REVISION) {
            throw new IllegalArgumentException("Template revision must be positive");
        }
    }

    public TemplateRevision next() {
        return new TemplateRevision(Math.addExact(value, 1));
    }

    @Override
    public int compareTo(TemplateRevision other) {
        return Long.compare(value, other.value);
    }
}
