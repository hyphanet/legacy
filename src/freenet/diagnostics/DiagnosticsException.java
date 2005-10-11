package freenet.diagnostics;

/**
 * You can never have enough exceptions.
 *
 * @author oskar
 */
public class DiagnosticsException extends Exception {

    public DiagnosticsException(String message) {
        super(message);
    }

    public DiagnosticsException() {
        super();
    }


}
