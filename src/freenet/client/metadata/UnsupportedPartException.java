package freenet.client.metadata;

public class UnsupportedPartException extends Exception {

    public UnsupportedPartException(String name) {
        super("Unknown part type: " + name);
    }

}
