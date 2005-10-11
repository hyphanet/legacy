package freenet.client.events;

/**
 * The TransferCompletedEvent is produced when a data transfer
 * has completed sucessfully.  
 *
 * @author oskar
 **/
public class TransferCompletedEvent extends StreamEvent {
    public static final int code = 0x82;

    public TransferCompletedEvent(long bytes) {
        super(bytes);
    }

    public final String getDescription() {
        return "Transfer ended with " + getProgress() + " bytes moved.";
    }
    
    public final int getCode() {
        return code;
    }
}



