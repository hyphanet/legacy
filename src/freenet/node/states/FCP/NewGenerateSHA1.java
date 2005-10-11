package freenet.node.states.FCP;

import freenet.Core;
import freenet.FieldSet;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.crypt.SHA1;
import freenet.crypt.Util;
import freenet.message.client.Failed;
import freenet.message.client.GenerateSHA1;
import freenet.message.client.Success;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.HexUtil;
import freenet.support.Logger;

public class NewGenerateSHA1 extends NewClientRequest {

    public NewGenerateSHA1(long id, PeerHandler source) {
        super(id, source);
    }
    
    public String getName() {
        return "New Client GenerateSHA1";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof GenerateSHA1)) {
            throw new BadStateException("expecting GenerateSHA1");
        }

        GenerateSHA1 gs = (GenerateSHA1) mo;
        
        long dlen = gs.getDataLength();
        long mlen = gs.getMetadataLength();
        try {
            if (mlen != 0) {
                // We don't expect any metadata.
                throw new RuntimeException("GenerateSHA1 got unexpected metadata.");
            }    
            byte[] hash = Util.hashStream(SHA1.getInstance(), 
					  gs.getDataStream(), dlen);
            FieldSet fs = new FieldSet();
            fs.put("SHA1", HexUtil.bytesToHex(hash));
            sendMessage(new Success(id, fs));
        }
        catch (Exception e) {
            Core.logger.log(this, "GenerateSHA1 failed", e, Logger.MINOR);
            sendMessage(new Failed(id, e.getMessage()));
        }
        return null;
    }
}


