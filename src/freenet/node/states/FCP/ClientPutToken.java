package freenet.node.states.FCP;

import freenet.FieldSet;
import freenet.MessageSendCallback;
import freenet.PeerHandler;
import freenet.SendFailedException;
import freenet.Storables;
import freenet.TrailerWriter;
import freenet.message.client.KeyCollision;
import freenet.message.client.Pending;
import freenet.message.client.Success;
import freenet.node.Node;
import freenet.node.NodeReference;

/** This is the FeedbackToken given to the InsertRequestInitiator.
  * @author tavin
  */
public class ClientPutToken extends FCPFeedbackToken {

    private String uri, priv, pub;
    private boolean keyCollision = false;

    ClientPutToken(long id, PeerHandler source, String uri) {
        this(id, source, uri, null, null);
    }
    
    ClientPutToken(long id, PeerHandler source,
                   String uri, String priv, String pub) {
        super(id, source);
        this.uri  = uri;
        this.priv = priv;
        this.pub  = pub;
    }

    private final FieldSet getFields() {
        FieldSet fs = new FieldSet();
        if (uri  != null) fs.put("URI", uri);
        if (priv != null) fs.put("PrivateKey", priv);
        if (pub  != null) fs.put("PublicKey", pub);
        return fs;
    }
    
    public TrailerWriter dataFound(Node n, Storables storables, long ctLength)
                                                throws SendFailedException {
        keyCollision = true;
        sendMessage(new KeyCollision(id, getFields()));
        return null;
    }

    public void insertReply(Node n, long millis) throws SendFailedException {
        FieldSet fs = getFields();
        fs.put("Timeout", Long.toHexString(millis));
        sendMessage(new Pending(id, fs));
    }

    public void storeData(Node n, NodeReference nr, FieldSet estimator, 
						  long rate, int hopsSinceReset, MessageSendCallback cb)
	throws SendFailedException {
        if (!keyCollision)
            sendMessage(new Success(id, getFields()));
    }
}



