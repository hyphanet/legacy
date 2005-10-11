package freenet.interfaces;

public class ServiceException extends Exception {
    public ServiceException(String reason) {
        super(reason);
    }
}
