package net.enthusia.loreitems.application;

@FunctionalInterface
public interface AnomalyWarningSink {
    void requestWarning();
}
