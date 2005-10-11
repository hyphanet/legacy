package freenet.client.events;

/**
 * Base class for SplitFile events.
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class VerifyingChecksumEvent extends SplitFileEvent {
    public static final int code = 0x48;
    private String checksum;
    public VerifyingChecksumEvent(SegmentHeader header, boolean requesting, String checksum) {
        super(header,requesting);
        this.checksum = checksum;
    }
    public final String getChecksum() { return checksum; }
    public final String getDescription() { return "Started verifying checksum: " + checksum; }
    public final int getCode() { return code; }
}




