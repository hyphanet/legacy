package freenet.client.events;

import freenet.message.client.FEC.SegmentHeader;
/**
 * Class to feed all the SegmentHeader's to SplitFileStatus
 *
 * @author amphibian
 */
public class SplitFileStartedEvent extends SplitFileEvent {
    public static final int code = 0x4A;
    protected SegmentHeader[] headers;
    
    public SplitFileStartedEvent(SegmentHeader[] headers, boolean requesting) {
	super(headers[0], requesting);
	this.headers = headers;
    }
    
    public String getDescription() {
	return "Starting SplitFile insert.";
    }
    
    public SegmentHeader[] headers() {
	return headers;
    }
    
    public int getCode() {
	return code;
    }
}
