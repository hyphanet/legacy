package freenet.client.events;

import freenet.support.Bucket;
import freenet.client.*;

/**
 * The SegmentCompletedEvent is produced by the library when a
 * portion of the data (currently the metadata and data portions) 
 * have been sucessfully transfered.
 * This event contains the file descriptor of the segment.
 *
 * @author scott
 **/
public class SegmentCompleteEvent implements ClientEvent {
    public static final int code = 0x84;
    protected Bucket segDescriptor;

    public SegmentCompleteEvent(Bucket sd) {
        segDescriptor=sd;
    }

    public String getDescription() {
        return "Transfer of one segment to a temporary file completed.";
    }
    
    public int getCode() {
        return code;
    }

    /**
     * Returns the file's produced by the transfer.
     * The first descriptor will contain the data File, the second,
     * if it exists, contains the metadata File.
     */
    public Bucket getDescriptor() {
        return segDescriptor;
    }
}



