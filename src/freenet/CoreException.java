package freenet;

/**
 * Used to indicate the Core is not ready to perform
 * a certain task, or that the Core hit a fatal error
 * trying to begin().
 */
public class CoreException extends RuntimeException {

    public CoreException(String s) {
        super(s);
    }
}
