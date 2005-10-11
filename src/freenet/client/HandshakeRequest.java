package freenet.client;

/** Represents a HandshakeRequest (ClientHello in FCP)
  * @author tavin
  */
public class HandshakeRequest extends Request {

    String prot, node;

    public HandshakeRequest() {
        super();
    }

    public String getProtocolVersion() {
        return prot;
    }
    
    public String getNodeDescription() {
        return node;
    }

}
