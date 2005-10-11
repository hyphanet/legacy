package freenet.client.events;
import freenet.client.ClientEvent;
import freenet.client.FreenetURI;

/**
 * Indicates that a URI was generated. 
 *
 * @author oskar
 */
public class GeneratedURIEvent implements ClientEvent {

    private FreenetURI uri;
    private String comment;

    public GeneratedURIEvent(String comment, FreenetURI uri) {
        this.comment = comment;
        this.uri = uri;
    }

    public int getCode() {
        return 667; // what do I know?
    }

    public FreenetURI getURI() {
        return uri;
    }

    public String getDescription() {
        return comment + (uri == null ? "" : (" - " + uri.toString()));
    }

}
