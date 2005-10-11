package freenet.client;

public class KeyNotInManifestException extends Exception {
    public KeyNotInManifestException(String s) {
        super(s);
    }
    public KeyNotInManifestException() {
	super();
    }
}
