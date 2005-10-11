package freenet.client;
/**
 * Single request process for computing inverting a private SSK 
 * key to it's public value..
 *
 * @author giannij
 */
public class InvertPrivateKeyProcess extends SingleRequestProcess {

    private String privateValue;
    public InvertPrivateKeyProcess(String privateValue) {
        super(null);
        this.privateValue = privateValue;
    }

    public Request getNextRequest() {
        return getNextRequest(new InvertPrivateKeyRequest(privateValue));
    }
    
    public String getPublicValue() {
        return (dr == null ? null : 
                ((InvertPrivateKeyRequest) dr).getPublicValue());
    }
}




