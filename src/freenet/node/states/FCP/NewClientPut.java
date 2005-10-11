package freenet.node.states.FCP;

import java.io.IOException;
import java.io.OutputStream;

import freenet.Core;
import freenet.FieldSet;
import freenet.KeyException;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.client.AbstractClientKey;
import freenet.client.Base64;
import freenet.client.ClientKey;
import freenet.client.ClientSVK;
import freenet.client.FreenetURI;
import freenet.message.DataInsert;
import freenet.message.client.ClientPut;
import freenet.message.client.Failed;
import freenet.message.client.URIError;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.node.states.request.FeedbackToken;
import freenet.node.states.request.InsertPending;
import freenet.node.states.request.RequestInitiator;
import freenet.support.Bucket;
import freenet.support.Logger;
import freenet.support.io.DiscontinueInputStream;
import freenet.support.io.FreeBucketInputStream;

/**
 * This is the state pertaining to InsertRequests in their
 * initial state.
 */

public class NewClientPut extends NewClientRequest {

    public NewClientPut(long id, PeerHandler source) {
        super(id, source);
    }
    
    /**
     * Returns the name.
     * @return "New ClientPut"
     */
    public String getName() {
        return "New ClientPut";
    }

    public State received(Node n, MessageObject mo) throws StateException {
        if (!(mo instanceof ClientPut)) {
            throw new BadStateException("expecting ClientPut");
        }

        Core.diagnostics.occurrenceCounting("inboundClientRequests", 1);

        ClientPut cpmo = (ClientPut) mo;
            
        long dlen = cpmo.getDataLength();
        long mlen = cpmo.getMetadataLength();

        if(dlen + mlen > 1 << Node.maxLog2DataSize) {
        	sendMessage(new URIError(id, "Data too big for single key"));
        	return null;
        }
        
        State s;
        DataInsert dim;
        boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        
        if(logDEBUG)
            Core.logger.log(this, "New ClientPut: data: "+dlen+", meta: "+
                    mlen+" from "+mo+" for "+this, Logger.DEBUG);
        
        Bucket data = null;
        try {
            // first, fully read the data from the client
            data = n.bf.makeBucket(dlen);
            OutputStream out = data.getOutputStream();
            try { copyFully(cpmo.getDataStream(), out, dlen); }
            finally { out.close(); }
	    
            if(logDEBUG)
                Core.logger.log(this, "Successfully transferred data bucket "+
                        this, Logger.DEBUG);
	    
            // initialize the ClientKey
            FreenetURI uri = cpmo.getURI();
            ClientKey ckey = 
                AbstractClientKey.createFromInsertURI(Core.getRandSource(), uri);
            
            String cipher = cpmo.otherFields.getString("Cipher");
            if (cipher != null) ckey.setCipher(cipher);
            
            if(logDEBUG) Core.logger.log(this, "ckey = "+ckey, Logger.DEBUG);
            
            // prepare the insert
            long tmpLen;
            tmpLen = ckey.getPaddedLength(dlen);
            tmpLen = ckey.getTotalLength(tmpLen, ckey.getPartSize(tmpLen));

            Bucket ctBucket = null;
            DiscontinueInputStream ctStream = null;
            
            if(logDEBUG)
                Core.logger.log(this, "Total padded length: "+tmpLen+" on "+this,
                        Logger.DEBUG);
            
            try {
                ctBucket = n.bf.makeBucket(tmpLen);
                ctStream = new FreeBucketInputStream(ckey.encode(data, mlen, ctBucket),
                                                     n.bf, ctBucket);
//                if (cpmo.getRemoveLocal()) {
//                	Key bkey = ckey.getKey();
//                	if (n.ds.contains(bkey)) {
//                		n.ds.remove(bkey, false);
//                	}
//            	}
            
                // set up the FeedbackToken
                FeedbackToken ft;
                if (ckey instanceof ClientSVK) {
                    ClientSVK csvk = (ClientSVK) ckey;
                    ft = new ClientPutToken( id, source, csvk.getURI().toString(),
                                             Base64.encode(csvk.getPrivateKey()),
                                             Base64.encode(csvk.getPublicKeyFingerPrint()) );
                }
                else ft = new ClientPutToken( id, source, ckey.getURI().toString() );
                
                if(logDEBUG)
                    Core.logger.log(this, "Token: "+ft+", bucket: "+ctBucket,
                            Logger.DEBUG);
                
		cpmo.setReceivedTime(System.currentTimeMillis());
		// Since we read fully from the client before starting the
		// state machine...
		
                // set up the DataInsert
                // and the InsertPending chain

                FieldSet root = new FieldSet();
                ckey.getStorables().addTo(root);
                dim = new DataInsert(id, root, ctStream, ckey.getTotalLength());
            
                RequestInitiator ri = 
                    new RequestInitiator(id, cpmo.getReceivedTime());
                s = new InsertPending(id, cpmo.getHTL(), ckey.getKey(), null, ft, 
                		ri, false, cpmo.getRemoveLocal());
                n.logRequest(null); // probably don't know the key
                
                if(logDEBUG)
                    Core.logger.log(this, "RI: "+ri+", IP: "+s+", dim="+dim+
                            " on "+this, Logger.DEBUG);
                
                s = s.received(n, ri);

                ctBucket = null;
                ctStream = null;
            }
            finally {
                if (ctStream != null)
                    ctStream.close();
                else if (ctBucket != null)
                    n.bf.freeBucket(ctBucket);
            }
        } catch (KeyException e) {
            Core.logger.log(this, "ClientPut got KeyException, sending URIError",
                         e, Logger.MINOR);
            sendMessage(new URIError(id, e.getMessage()));
            return null;
        } catch (Exception e) {
            Core.logger.log(this, "ClientPut failed", e, Logger.MINOR);
            sendMessage(new Failed(id, e.getMessage()));
            return null;
        } finally {
            try {
                if (data != null) n.bf.freeBucket(data);
            }
            catch (IOException e) {}
        }

        // send the DataInsert
        try {
            if(logDEBUG)
                Core.logger.log(this, "Sending DIM: s="+s+", dim="+dim,
                        Logger.DEBUG);
            if (s != null)
                s = s.received(n, dim);
        } catch (BadStateException e) {
            // key collision, or something
            dim.drop(n);
            if(logDEBUG)
                Core.logger.log(this, "Dropped because of "+e+": "+this,
                        e, Logger.DEBUG);
        }
        return s;
    }
}


