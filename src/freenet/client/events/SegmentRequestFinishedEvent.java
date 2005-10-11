package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class SegmentRequestFinishedEvent extends SplitFileEvent  {
    public static final int code = 0x32;
    
    private int exitCode;

    public SegmentRequestFinishedEvent(SegmentHeader h, boolean downloading, int exitCode) {
        super(h, downloading);
        this.exitCode = exitCode;
    }

    public final int getExitCode() { return exitCode; }
    public final String getDescription() { 
        return "SplitFile segment request finished [" 
            + (getHeader().getSegmentNum() + 1) + "/" + getHeader().getSegments() + "]: " +
            exitCodeToString(exitCode);
    }

    public final int getCode() { return code; }
}

