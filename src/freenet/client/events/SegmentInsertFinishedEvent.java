package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class SegmentInsertFinishedEvent extends SplitFileEvent  {
    public static final int code = 0x37;
    
    private int exitCode;

    public SegmentInsertFinishedEvent(SegmentHeader header, boolean downloading, int exitCode) {
        super(header, downloading);
        this.exitCode = exitCode;
    }

    public final int getExitCode() { return exitCode; }
    public final String getDescription() { 
        // hmmm... other places where I need to do this? REDFLAG: revisit
        if (getHeader() == null) {
            return "SplitFile segment insert finished [?/?]: "  + exitCodeToString(exitCode);
        }

        return "SplitFile segment insert finished [" 
            + (getHeader().getSegmentNum() + 1) + "/" + getHeader().getSegments() + "]: " +
            exitCodeToString(exitCode);
    }

    public final int getCode() { return code; }
}

