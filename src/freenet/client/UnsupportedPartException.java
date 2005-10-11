package freenet.client;

public class UnsupportedPartException extends RuntimeException {

    private String name;
    
    public UnsupportedPartException(String name) {
        super("Unsupported metadata part "+name);
        this.name = name;
    }

    public String getUnsupportedPartName() {
        return name;
    }
}
