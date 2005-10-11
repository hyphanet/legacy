package freenet.interfaces;

public class RejectedConnectionException extends Exception {
    RejectedConnectionException(String reason) {
        super(reason);
    }
}
