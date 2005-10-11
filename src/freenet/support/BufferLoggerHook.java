package freenet.support;

/**
 * A LoggerHook that buffers the last N log statements as objects.
 * @author oskar
 */
public class BufferLoggerHook extends LoggerHook {

    public static class LogEntry {

        private long time;
        private Class source;
        private String message;
        private Throwable e;
        private int priority;

        public LogEntry(long time, Class source, String message, Throwable e, 
                        int priority) {
            this.time = time;
            this.source = source;
            this.message = message;
            this.e = e;
            this.priority = priority;
        }

        public long time() {
            return time;
        }

        public Class source() {
            return source;
        }

        public String message() {
            return message;
        }

        public Throwable exception() {
            return e;
        }

        public int priority() {
            return priority;
        }

    }

    private LogEntry[] buffer;
    private int pos;
    private boolean filled;

    public BufferLoggerHook(int bufferSize) {
    	super(Logger.DEBUG);
        buffer = new LogEntry[bufferSize];
        pos = 0;
        filled = false;
    }
        
    public synchronized void log(Object o, Class source, String message, 
                                 Throwable e, int priority) {
        buffer[pos] = new LogEntry(System.currentTimeMillis(), source, 
                                   message, e, priority);
        pos++;
        if (pos == buffer.length) {
            filled = true;
            pos = 0;
        }
    }

    public synchronized LogEntry[] getBuffer() {
        if (!filled) {
            LogEntry[] le = new LogEntry[pos];
            System.arraycopy(buffer, 0, le, 0, pos);
            return le;
        } else {
            LogEntry[] le = new LogEntry[buffer.length];
            System.arraycopy(buffer, pos, le, 0, buffer.length - pos);
            System.arraycopy(buffer, 0, le, buffer.length - pos, pos);
            return le;
        }
    }

    /**
     * Returns the buffer size (even if it is not full).
     */
    public int size() {
        return buffer.length;
    }

    public long minFlags() {
	return 0;
    }

    public long notFlags() {
	return INTERNAL;
    }

    public long anyFlags() {
	return ERROR | NORMAL | MINOR | DEBUG;
    }
}
