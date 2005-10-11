package freenet.node.states.FCP;

import freenet.Core;
import freenet.FieldSet;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.client.AbstractClientKey;
import freenet.client.ClientSSK;
import freenet.client.FreenetURI;
import freenet.message.client.Failed;
import freenet.message.client.InvertPrivateKey;
import freenet.message.client.Success;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Logger;

/** State to handle the FCP command to invert a private key to its
 *  public value. 
 * @author giannij
 */
public class NewInvertPrivateKey extends NewClientRequest {

    public NewInvertPrivateKey(long id, PeerHandler source) {
        super(id, source);
    }
    
    public String getName() {
        return "New Client NewInvertPrivateKey";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof InvertPrivateKey)) {
            throw new BadStateException("expecting NewInvertPrivateKey");
        }
        InvertPrivateKey gpk = (InvertPrivateKey) mo;
                    
        try {
            String privateValue = gpk.getPrivateValue();
            String publicValue = "";
            if (privateValue.startsWith("freenet:SSK@")) {
                // We are inverting a URI...

                
                FreenetURI uri = new freenet.client.FreenetURI(privateValue);
                ClientSSK key = (ClientSSK) AbstractClientKey.
                    createFromInsertURI(new java.util.Random(), uri);
                
                publicValue = key.getURI().toString();

                //System.err.println("REDFLAG: public : " + publicValue);
                //System.err.println("REDFLAG: private: " + privateValue);

            }
            else {
                // We are inverting a key...
                // This will generate the public key with the extra 3 characters for the 
                // SSK key type appended to the end.  While not strictly correct, I think
                // this what client authors actually want. i.e. "the thing I need to put
                // between 'freenet:SSK@' and '/' to make a valid public key".
                FreenetURI uri = 
                    new freenet.client.FreenetURI("freenet:SSK@" + privateValue + "/foo");
                // hmmm... null used to work, but a lot has changed.
                //ClientSSK key = (ClientSSK)AbstractClientKey.createFromInsertURI(null, uri);
                ClientSSK key = (ClientSSK)AbstractClientKey.createFromInsertURI(new java.util.Random(),
                                                                                 uri);
                publicValue = key.getURI().toString(); 
                publicValue = publicValue.substring(12, publicValue.indexOf("/")); 
            }

            FieldSet fs = new FieldSet();
            fs.put("Public", publicValue);
            sendMessage(new Success(id, fs));
        }
        catch (Exception e) {
            Core.logger.log(this, "InvertPrivateKey failed", e, Logger.MINOR);
            sendMessage(new Failed(id, e.getMessage()));
        }
        return null;
    }
}


