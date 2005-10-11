package freenet.client.events;

import freenet.client.ClientEvent;

/**
 * The TransferStartedEvent is produced when a data transfer
 * is started.  
 *
 * @author oskar
 **/
public class TransferStartedEvent implements ClientEvent {
    public static final int METADATA=0, DATA=1, TOTAL=2;
    public static final int code = 0x80;
    private long[] segmentLengths;

    public TransferStartedEvent(long lengths[]) {
        super();
        this.segmentLengths=lengths;
    }

    public TransferStartedEvent(long len) {
        this(new long[] {-1, -1, len});
    }
        
    public final String getDescription() {
        return "Transfer of " + getLength() + " bytes started.";
    }

    /**
     * Returns the length of data we expect to receive.
     * @return The amount of data to transfer.    
     */
    public final long getLength() {
        return segmentLengths[TOTAL];
    }

    public final long getMetadataLength() {
        return segmentLengths[METADATA];
    }
    public final long getSegmentLength(int lSegmentIndex){
    	return segmentLengths[lSegmentIndex];
    }
    public final int getSegmentCount(){
    	return segmentLengths.length-1; //Dont include the TOTAL segment
    }

    public final long getDataLength() {
        return segmentLengths[DATA];
    }

    public final int getCode() {
        return code;
    }
}
