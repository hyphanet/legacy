package freenet.node.states.FCP;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.Core;
import freenet.FieldSet;
import freenet.KeyException;
import freenet.MessageSendCallback;
import freenet.OutputStreamTrailerWriter;
import freenet.PeerHandler;
import freenet.SendFailedException;
import freenet.Storables;
import freenet.TrailerWriter;
import freenet.TrailerWriterOutputStream;
import freenet.client.ClientKey;
import freenet.client.Document;
import freenet.client.FreenetURI;
import freenet.message.client.ClientMessage;
import freenet.message.client.DataChunk;
import freenet.message.client.DataFound;
import freenet.message.client.Failed;
import freenet.message.client.MetadataHint;
import freenet.message.client.URIError;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Logger;
import freenet.support.io.CBStripOutputStream;
import freenet.support.io.CopyingOutputStream;
import freenet.support.io.DataChunkOutputStream;
import freenet.support.io.DataNotValidIOException;

/** Serves as a communication relay between the state chain and
  * the FCP client after a ClientGet has been initiated.
  * @author tavin
  */
public class ClientGetToken extends FCPFeedbackToken {

    private ClientKey ckey;

    // For metadata hint
    private boolean sendHint = false;
    private FreenetURI uri = null;
    private long timeSec = -1;
    private boolean hasData = false;
    private Bucket metaData = null;
    private OutputStream mdCopy = null;
    private BucketFactory bf = null;

    // FIXME: convert to fully async
    
    ClientGetToken(long id, PeerHandler source, ClientKey ckey, 
                   boolean sendHint, FreenetURI uri, long timeSec,
                   BucketFactory bf) {
        super(id, source);
        this.ckey = ckey;
        this.sendHint = sendHint;
        this.uri = uri;
        this.timeSec = timeSec;
        this.bf = bf;
    }

    public TrailerWriter dataFound(Node n, Storables sto, long ctLength)
                                            throws SendFailedException {
        waiting = false;

        Document doc;
        try {
            doc = ckey.decode(sto, ctLength);
        } catch (DataNotValidIOException e) {
            ClientMessage msg;
            if (e.getCode() == Document.DOC_BAD_KEY) {
                msg = new URIError(id,
                    "Data found, but failed to decrypt with the decryption key given in the URI.");
            }
            else if (e.getCode() == Document.DOC_UNKNOWN_CIPHER) {
                msg = new Failed(id,
                    "Data found, but encrypted with an unsupported cipher.");
            }
            else if (e.getCode() == Document.DOC_BAD_LENGTH) {
                msg = new Failed(id,
                    "Data found, but the length given in the document header " +
                    "is inconsistent with the actual length of the key data.");
            }
            else if (e.getCode() == Document.DOC_BAD_STORABLES) {
                msg = new Failed(id, "Data found, but the storables were corrupt.");
            }
            else {
                msg = new Failed(id, "Data found, but the document header was corrupt.");
            }
            sendMessage(msg);
            return null;
        } catch (KeyException e) {
	if(!Core.logger.shouldLog(Logger.DEBUG,this))
	    Core.logger.log(this, "ClientGetToken got "+e+" trying to decode a key",
			    Logger.MINOR);
	else
	    Core.logger.log(this, "ClientGetToken got "+e+" trying to decode "+ckey,
			    Logger.MINOR);
	    ClientMessage msg = new Failed(id, "Data found, but could not start decode.");
	    sendMessage(msg);
	    return null;
	}
        OutputStream out;
        try {
            OutputStream decryptedStream = new FCPDataChunkOutputStream(doc.length(), ckey.getPartSize());
            if (sendHint && (doc.metadataLength() > 0) ) {
                // Keep a copy of the metadata so that we
                // can parse it for the redirect hint.
                metaData = bf.makeBucket(doc.metadataLength());
                mdCopy = metaData.getOutputStream();
                
                decryptedStream = new CopyingOutputStream(decryptedStream,
                                                          mdCopy,
                                                          doc.metadataLength());
            }
            hasData = (doc.length() > doc.metadataLength() && doc.length() > 0);
            out = doc.decipheringOutputStream(decryptedStream);
    
            // send DataFound message
            FieldSet fs = new FieldSet();
            fs.put("DataLength", Long.toHexString(doc.length()));
            if (doc.metadataLength() > 0)
                fs.put("MetadataLength", Long.toHexString(doc.metadataLength()));
            sendMessage(new DataFound(id, fs));
	    out = new CBStripOutputStream(out, ckey.getPartSize(), ckey.getControlLength());
	    return new OutputStreamTrailerWriter(out);
        } catch (IOException e) {
            sendMessage(new Failed(id, e.getMessage()));
            return null;
        }
    }

    // these are useless w/r/to ClientGet ..
    public void insertReply(Node n, long millis) {}
    public void storeData(Node n, NodeReference nr, FieldSet estimator, 
						  long rate, int hopsSinceReset, MessageSendCallback cb) {}

    /** Takes the unencrypted document and writes it in chunks to
      * the FCP client, driven ultimately by the SendData state.
      */

    public class FCPDataChunkOutputStream extends DataChunkOutputStream {
	protected FCPDataChunkOutputStream(long length, long chunkSize) {
	    super(length, chunkSize);
	}
	
        protected void sendChunk(int chunkSize) throws IOException {
            OutputStream out;
            try {
		TrailerWriter tw = 
		    sendMessage(new DataChunk(id, chunkSize, (pos == length) && !sendHint));
		if(tw == null) throw new NullPointerException();
		out = new TrailerWriterOutputStream(tw);
            } catch (SendFailedException e) {
		IOException ex = new IOException(e.getMessage());
		ex.initCause(e);
		throw ex;
            }
            out.write(buffer, 0, chunkSize);
            out.flush();
	    out.close();
	    
            if (sendHint && (pos == length)) {
                // Send a the redirect hint after the data.
                try {
                    InputStream mdi = null;
                    if (mdCopy != null) {
                        mdCopy.close();
                    }
                    if (metaData != null) {
                        mdi = metaData.getInputStream();
                    }
                    sendMessage(new MetadataHint(id, mdi,
                                                 uri, hasData, timeSec)); 
                } catch (SendFailedException e) {
                    throw new IOException(e.getMessage());
                } finally {
                    if (metaData != null) {
                        bf.freeBucket(metaData);
                    }
                }
            }
        }

    }
    
    // REDFLAG: delete debugging code!
    //   public final static void dumpBucket(Bucket b, String fileName) throws IOException {
    //          OutputStream out = new FileOutputStream(fileName);
    //          byte[] buffer = new byte[4096];
    //          InputStream in = b.getInputStream();
    
    //          int nRead = in.read(buffer);
    //          while (nRead > 0) {
    //              out.write(buffer, 0, nRead);
    //              nRead = in.read(buffer);
    //          }
    
    //          in.close();
    //          out.close();
    //      }
}




