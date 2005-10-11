package freenet.client.events;

/**
 * Base class for block specific SplitFile events.
 * @author giannij
 */
import freenet.message.client.FEC.SegmentHeader;

public abstract class BlockEvent extends SplitFileEvent {
    private int index;
    private boolean isData;
    private int htl;

    protected BlockEvent(SegmentHeader header, boolean downloading, int index, boolean isData, int htl) {
        super(header, downloading);
        this.index = index;
        this.isData = isData;
        this.htl = htl;
    }

    protected final String formatMsg(String text) {
        final String blockDesc = isData ? "data" : "check"; 
        return text + " " + blockDesc + " block " +
            Integer.toString(index) + ":[" +
            Integer.toString(getHeader().getSegmentNum() + 1) + "/" + 
            Integer.toString(getHeader().getSegments()) + "] "  +
            "(htl=" + Integer.toString(htl) + ")";
    }

    public final int index() { return index; }
    public final boolean isData() { return isData; }
    public final int htl() { return htl; }
}

