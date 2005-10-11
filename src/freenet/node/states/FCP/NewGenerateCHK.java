package freenet.node.states.FCP;

import java.io.IOException;
import java.io.OutputStream;

import freenet.Core;
import freenet.FieldSet;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.client.ClientCHK;
import freenet.message.client.Failed;
import freenet.message.client.GenerateCHK;
import freenet.message.client.Success;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Bucket;
import freenet.support.Logger;

public class NewGenerateCHK extends NewClientRequest {

    public NewGenerateCHK(long id, PeerHandler source) {
        super(id, source);
    }
    
    public String getName() {
        return "New Client GenerateCHK";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof GenerateCHK)) {
            throw new BadStateException("expecting GenerateCHK");
        }
        GenerateCHK gchk = (GenerateCHK) mo;
        
        long dlen = gchk.getDataLength();
        long mlen = gchk.getMetadataLength();
	
        Bucket data = null;
        try {
            // first, fully read the data from the client
            data = n.bf.makeBucket(dlen);
            OutputStream out = data.getOutputStream();
            try { copyFully(gchk.getDataStream(), out, dlen); }
            finally { out.close(); }

            ClientCHK chk = new ClientCHK();
        
            String cipher = gchk.otherFields.getString("Cipher");
            if (cipher != null) chk.setCipher(cipher);
            
            long tmpLen;
            tmpLen = chk.getPaddedLength(dlen);
            tmpLen = chk.getTotalLength(tmpLen, chk.getPartSize(tmpLen)); 
            
            Bucket ctBucket = n.bf.makeBucket(tmpLen);
            try {
                // chk.encode() returns an InputStream that we must close
                chk.encode(data, mlen, ctBucket).close();
            }
            finally {
                n.bf.freeBucket(ctBucket);
            }
                
            FieldSet fs = new FieldSet();
            fs.put("URI", chk.getURI().toString());
            sendMessage(new Success(id, fs));
        }
        catch (Exception e) {
            Core.logger.log(this, "GenerateCHK failed", e, Logger.MINOR);
            sendMessage(new Failed(id, e.getMessage()));
        }
        finally {
            try {
                if (data != null) n.bf.freeBucket(data);
            }
            catch (IOException e) {}
        }
        
        return null;
    }
}


