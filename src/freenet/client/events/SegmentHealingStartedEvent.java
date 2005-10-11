package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class SegmentHealingStartedEvent extends SplitFileEvent  {
    public static final int code = 0x39;
    private int reinsertions;
    public SegmentHealingStartedEvent(SegmentHeader header, boolean downloading,
                                      int reinsertions) {
        super(header, downloading);
        this.reinsertions = reinsertions;
    }

    public final String getDescription() { 
        return "SplitFile healing started [" +
            (getHeader().getSegmentNum() + 1) + "/" + getHeader().getSegments() + "]" +
            " re-inserting " + reinsertions + " unretrievable blocks.";
    }

    public final int getCode() { return code; }

    /**
     * The number of unretreivable blocks that are
     * being reinserted into Freenet.
     */
    public final int getReinsertions() { return reinsertions; }
}
