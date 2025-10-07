package com.example.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.*;
import org.slf4j.Marker;

import java.util.List;
import java.util.Map;

/**
 * A simple wrapper around ILoggingEvent that overrides the formatted message.
 */
class MaskedLoggingEvent implements ILoggingEvent {

    private final ILoggingEvent delegate;
    private final String maskedMessage;

    MaskedLoggingEvent(ILoggingEvent delegate, String maskedMessage) {
        this.delegate = delegate;
        this.maskedMessage = maskedMessage;
    }

    @Override
    public String getThreadName() {
        return delegate.getThreadName();
    }

    @Override
    public Level getLevel() {
        return delegate.getLevel();
    }

    @Override
    public String getMessage() {
        return maskedMessage;
    }

    @Override
    public Object[] getArgumentArray() {
        return delegate.getArgumentArray();
    }

    @Override
    public String getFormattedMessage() {
        return maskedMessage;
    }

    @Override
    public String getLoggerName() {
        return delegate.getLoggerName();
    }

    @Override
    public LoggerContextVO getLoggerContextVO() {
        return delegate.getLoggerContextVO();
    }

    @Override
    public IThrowableProxy getThrowableProxy() {
        return delegate.getThrowableProxy();
    }

    @Override
    public StackTraceElement[] getCallerData() {
        return delegate.getCallerData();
    }

    @Override
    public boolean hasCallerData() {
        return delegate.hasCallerData();
    }

    @Override
    public Map<String, String> getMDCPropertyMap() {
        return delegate.getMDCPropertyMap();
    }

    @Override
    public Map<String, String> getMdc() {
        return delegate.getMdc();
    }

    @Override
    public long getTimeStamp() {
        return delegate.getTimeStamp();
    }

    @Override
    public void prepareForDeferredProcessing() {
        delegate.prepareForDeferredProcessing();
    }

    @Override
    public List<Marker> getMarkerList() {
        return delegate.getMarkerList();
    }

    @Override
    public long getSequenceNumber() {
        return delegate.getSequenceNumber();
    }

    @Override
    public List<org.slf4j.event.KeyValuePair> getKeyValuePairs() {
        return delegate.getKeyValuePairs();
    }

    @Override
    public int getNanoseconds() {
        return delegate.getNanoseconds();
    }
}