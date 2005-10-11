package freenet.client.events;

public class TransferFailedEvent extends StreamEvent {

    public static final int code = 0x85;
    
    /**
     * @param bytes
     */
    public TransferFailedEvent(long bytes) {
        super(bytes);
    }

    public String getDescription() {
        return "Transfer failed with "+bytes+" moved.";
    }

    public int getCode() {
        return code;
    }

}
