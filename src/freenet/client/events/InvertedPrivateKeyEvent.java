package freenet.client.events;
import freenet.client.ClientEvent;

/**
 * Event posted when a private key is successfully
 * inverted to its public value.
 *
 * @author giannij
 */
public class InvertedPrivateKeyEvent implements ClientEvent {

    private String privateValue;
    private String publicValue;

    public InvertedPrivateKeyEvent(String publicValue, String privateValue) {
        this.publicValue = publicValue;
        this.privateValue = privateValue;
    }

    public String getDescription() {
        return "Private: " + privateValue + "\nPublic: " + publicValue;
    }

    public final int getCode() {
        return 669;
    }

    public final String getPublicValue() { return publicValue; }
    public final String getPrivateValue() { return privateValue; }
}
