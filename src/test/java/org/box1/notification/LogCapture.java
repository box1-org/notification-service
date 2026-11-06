package org.box1.notification;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.jboss.logmanager.ExtLogRecord;

/** Captures every log record the service writes, with its MDC and any exception, for the customer-identifier scan. */
final class LogCapture extends Handler {
    private static final LogCapture INSTANCE = new LogCapture();
    private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
    private final SimpleFormatter formatter = new SimpleFormatter();

    private LogCapture() {}

    static synchronized LogCapture install() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler == INSTANCE) {
                return INSTANCE;
            }
        }
        root.addHandler(INSTANCE);
        return INSTANCE;
    }

    @Override
    public void publish(LogRecord record) {
        StringBuilder line = new StringBuilder(formatter.formatMessage(record));
        if (record instanceof ExtLogRecord ext) {
            for (Map.Entry<String, String> entry : ext.getMdcCopy().entrySet()) {
                line.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        if (record.getThrown() != null) {
            line.append(' ').append(record.getThrown());
        }
        lines.add(line.toString());
    }

    List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    public void flush() {
        // In memory.
    }

    @Override
    public void close() {
        lines.clear();
    }
}
