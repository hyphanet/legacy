package freenet.client.events;

/**
 * The TransferEvent is produced every so often by an ongoing transfer.
 * It reports the number of bytes transfered.
 *
 * @author oskar
 */
public class TransferEvent extends StreamEvent {
    public static final int code = 0x81;

    /**
     * Create a new TransferEvent.
     * @param bytes  The number of bytes read/written on the stream thus far.
     */
    public TransferEvent(long bytes) {
        super(bytes);
    }
        
    public final String getDescription() {
        return getProgress() + " bytes transferred.";
    }

    public final int getCode() {
        return code;
    }
}
