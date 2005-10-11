package freenet.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.math.BigInteger;
import java.util.Enumeration;
import java.util.Vector;

import freenet.Address;
import freenet.Connection;
import freenet.Core;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.client.events.CollisionEvent;
import freenet.client.events.DataNotFoundEvent;
import freenet.client.events.ErrorEvent;
import freenet.client.events.ExceptionEvent;
import freenet.client.events.GeneratedKeyPairEvent;
import freenet.client.events.GeneratedURIEvent;
import freenet.client.events.InvertedPrivateKeyEvent;
import freenet.client.events.PendingEvent;
import freenet.client.events.RestartedEvent;
import freenet.client.events.RouteNotFoundEvent;
import freenet.client.events.StateReachedEvent;
import freenet.client.events.TransferStartedEvent;
import freenet.client.listeners.CollectingEventListener;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.crypt.DSAPrivateKey;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.SegmentHeader;
import freenet.presentation.ClientProtocol;
import freenet.session.LinkManager;
import freenet.session.PlainLinkManager;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.FileBucket;
import freenet.support.Logger;
import freenet.support.TempBucketFactory;
import freenet.support.io.BucketInputStream;
import freenet.support.io.WriteOutputStream;


/** 
 * ClientFactory implementation for FCP
 *
 * @author <a href="mailto:rrkapitz@stud.informatik.uni-erlangen.de">Ruediger Kapitza</a>
 * @author <a href="mailto:giannijohansson@mediaone.net">Gianni Johansson</a>
 */
public class FCPClient implements ClientFactory {

    public static final int BUFFER_SIZE = 4096;

    public static final Presentation protocol = new ClientProtocol();
    
    protected TempBucketFactory bf = new TempBucketFactory();

    protected Address target;

    /** 
     * Create a new ClientFactory for spawning Clients to process fcp-requests.
     * @param target  The fcp-service to send messages to.
     */    
    public FCPClient(Address target) {
        setTarget(target);
    }

    /** Requires setting of target manually.
      */
    protected FCPClient() {}
    
    protected void setTarget(Address target) {
        this.target = target;
    }

    ////////////////////////////////////////////////////////////
    // ClientFactory interface implementation
    public Client getClient(Request req) throws UnsupportedRequestException {
        if (req instanceof GetRequest)
            return new FCPRequest((GetRequest) req);
        else if (req instanceof PutRequest)
            return new FCPInsert((PutRequest) req);
        else if (req instanceof ComputeCHKRequest)
            return new FCPComputeCHK((ComputeCHKRequest) req);
        else if (req instanceof ComputeSHA1Request)
            return new FCPComputeSHA1((ComputeSHA1Request) req);
        else if (req instanceof ComputeSVKPairRequest)
            return new FCPComputeSVKPair((ComputeSVKPairRequest) req);
        else if (req instanceof InvertPrivateKeyRequest)
            return new FCPInvertPrivateKey((InvertPrivateKeyRequest) req);
        else if (req instanceof HandshakeRequest)
            return new FCPHandshake((HandshakeRequest) req);

        // Fake SplitFile "meta commands" which don't go over
        // the wire.
        else if (req instanceof SplitFileGetRequest) {
            return new FCPSplitFileRequest((SplitFileGetRequest) req);
        }
        else if (req instanceof SplitFilePutRequest) {
            return new FCPSplitFileInsert((SplitFilePutRequest) req);
        }

        // Real FEC commands which do go over the wire.
        else if (req instanceof SegmentSplitFileRequest)
            return new FCPSegmentSplitFile((SegmentSplitFileRequest) req);
        else if (req instanceof DecodeSegmentRequest)
            return new FCPDecodeSegment((DecodeSegmentRequest) req);
        else if (req instanceof SegmentFileRequest)
            return new FCPSegmentFile((SegmentFileRequest) req);
        else if (req instanceof EncodeSegmentRequest)
            return new FCPEncodeSegment((EncodeSegmentRequest) req);
        else if (req instanceof MakeMetadataRequest)
            return new FCPMakeMetadata((MakeMetadataRequest) req);
        else
            throw new UnsupportedRequestException();
    }

    public boolean supportsRequest(Class req) {
        return GetRequest.class.isAssignableFrom(req) ||
            PutRequest.class.isAssignableFrom(req) ||
            ComputeCHKRequest.class.isAssignableFrom(req) ||
            ComputeSHA1Request.class.isAssignableFrom(req) ||
            ComputeSVKPairRequest.class.isAssignableFrom(req) ||
            InvertPrivateKeyRequest.class.isAssignableFrom(req) ||

            // Fake SplitFile "meta commands"
            SplitFileGetRequest.class.isAssignableFrom(req) ||
            SplitFilePutRequest.class.isAssignableFrom(req) ||

            // FEC requests
            SegmentSplitFileRequest.class.isAssignableFrom(req) ||
            DecodeSegmentRequest.class.isAssignableFrom(req) ||
            SegmentFileRequest.class.isAssignableFrom(req) ||
            EncodeSegmentRequest.class.isAssignableFrom(req);
    }    

    ////////////////////////////////////////////////////////////
    // Client implementations for various functions
    //
    private abstract class FCPInstance implements Client, Runnable {

        // FIXME -- FNP session support
        private final LinkManager linkManager = new PlainLinkManager();
        
        final Request request;
        final CollectingEventListener cel = new CollectingEventListener();
        
        Connection conn;
        InputStream in;
        OutputStream out;

        Thread workingThread;
        boolean started = false;
    

        FCPInstance(Request req) {
            this.request = req;
            req.addEventListener(cel);
        }

        final int state() {
            return request.state();
        }
        
        synchronized final StateReachedEvent state(int state) {
            request.state(state);
            return new StateReachedEvent(state);
        }

        // DON'T call with a lock on (this)
        final void unlockedProduceEvent(ClientEvent evt) {
            if (evt == null) {
                return;
            }
            if (request == null) {
                return;
            }
            request.produceEvent(evt);
        }

        final boolean tryState(int state) {
            ClientEvent evt = null;
            boolean ret = false;
            synchronized (FCPInstance.this) {
                if (state() != Request.CANCELLED) {
                    evt = state(state);
                    ret =  true;
                }
            }
            unlockedProduceEvent(evt);
            return ret;
        }

        public final void start() {
            if (started)
                throw new IllegalStateException("You can only start a request once.");
            else
                started = true;
            // Start a thread to handle the request asynchronously.
            workingThread = new Thread(this, "FCP Client: "+getDescription());
            workingThread.start();
        }

        public final int blockingRun() {
            if (started)
                throw new IllegalStateException("You can only start a request once.");
            else
                started = true;
            workingThread = Thread.currentThread();
            // Run the request to completion on this thread.
            run();
            return state();
        }

        public final boolean cancel() {
            ClientEvent evt = null;
            boolean ret = false;
            synchronized (FCPInstance.this) {
                if (state() >= Request.INIT && state() < Request.DONE) {
                    evt = state(Request.CANCELLED);
                    workingThread.interrupt();
                    ret = true;
                }
            }
            unlockedProduceEvent(evt);
            return ret;
        }
        
        public final Enumeration getEvents() { 
            return cel.events();
        }

        /** @return  the name of the FCP command handled by the subclass,
          *          perhaps with a URI identifier, etc.
          */
        abstract String getDescription();

        /** Called after the connection is set up and the request
          * is in the PREPARED state.
          */
        abstract void doit() throws Exception;
        
        public final void run() {
            try {
                ClientEvent evt = null;
                synchronized (FCPInstance.this) {
                    if (state() == Request.CANCELLED)
                        return;
                    negotiateConnection();
                    evt = state(Request.PREPARED);
                }
                unlockedProduceEvent(evt);
                doit();
            }
            catch (TerminalMessageException e) {
                //System.err.println("--- msg: " + e.getMessage());
                //e.printStackTrace();
                ClientEvent evtError = null;
                ClientEvent evtFailed = null;
                synchronized (FCPInstance.this) {
                    if (state() != Request.CANCELLED) {
                        evtError = new ErrorEvent(e.getErrorString());
                        evtFailed = state(Request.FAILED);
                    }
                }
                unlockedProduceEvent(evtError);
                unlockedProduceEvent(evtFailed);
            }
            catch (Exception e) {
                //System.err.println("-- unexpected exception");
                //e.printStackTrace(); // REDFLAG: Remove me.
                ClientEvent evtException = null;
                ClientEvent evtFailed = null;
                synchronized (FCPInstance.this) {
                    if (state() != Request.CANCELLED) {
                        evtException = new ExceptionEvent(e);
                        evtFailed = state(Request.FAILED);
                    }
                }
                unlockedProduceEvent(evtException);
                unlockedProduceEvent(evtFailed);
            }
            finally {
                if (conn != null) conn.close();
            }
        }
        
        private void negotiateConnection() throws Exception {
            conn = target.connect(false);
            out  = conn.getOut();
            conn.setSoTimeout((int)Core.storeDataTime(20, 1024*1024, Core.queueTimeout(1024*1024, true, false))*2);
            
            int lnum = linkManager.designatorNum();
            int pnum = protocol.designatorNum();
            
            out.write((lnum >> 8) & 0xff);
            out.write(lnum & 0xff);
            // FIXME -- negotiate session

            out.write((pnum >> 8) & 0xff);
            out.write(pnum & 0xff);

            out.flush();
                    
            in  = new BufferedInputStream(conn.getIn());
            out = new BufferedOutputStream(out);
        }
                    
        final void send(String command) throws IOException {
            send(command, null, 0, 0);
        }
        
        final void send(String command, FieldSet fs) throws IOException {
            send(command, fs, 0, 0);
        }

        final void send(String command, long dlen, long mlen) throws IOException {
            send(command, new FieldSet(), dlen, mlen);
        }

        final void send(String command, FieldSet fs, long dlen, long mlen) throws IOException {

            if (state() == Request.CANCELLED)
                throw new InterruptedIOException();
            
            WriteOutputStream writer = new WriteOutputStream(out);
            writer.writeUTF(command, '\n');

            if (fs != null) {
                String tf;
                if (dlen > 0) {
                    tf = "Data";
                    fs.put("DataLength", Long.toHexString(dlen));
                    if (mlen > 0)
                        fs.put("MetadataLength", Long.toHexString(mlen));
                    else
                        fs.remove("MetadataLength");
                }
                else {
                    tf = "EndMessage";
                    fs.remove("DataLength");
                    fs.remove("MetadataLength");
                }
                fs.writeFields(writer, tf);
            }
            else {
                writer.writeUTF("EndMessage", '\n');
            }

            writer.flush();
        }

        final void sendData(long length, InputStream data) throws IOException {
            byte[] buf = new byte[BUFFER_SIZE];
            while (length > 0) {
                if (state() == Request.CANCELLED)
                    throw new InterruptedIOException();
                int n = data.read(buf, 0, (int) Math.min(length, buf.length));
                if (n == -1) throw new EOFException();
                length -= n;
                out.write(buf, 0, n);
            }
            out.flush();
        }

        final void receiveData(long length, OutputStream data) throws IOException {
            byte[] buf = new byte[BUFFER_SIZE];
            while (length > 0) {
                if (state() == Request.CANCELLED)
                    throw new InterruptedIOException();
                int n = in.read(buf, 0, (int) Math.min(length, buf.length));
                if (n == -1) throw new EOFException();
                length -= n;
                data.write(buf, 0, n);
            }
        }
            
        final RawMessage getResponse() throws IOException, TerminalMessageException {

            boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
            
            if(logDEBUG) Core.logger.log(this, "getResponse()", Logger.DEBUG);
            
            if (state() == Request.CANCELLED)
                throw new InterruptedIOException();
            
            RawMessage m;
            try {
                m = protocol.readMessage(in);
            }
            catch (InvalidMessageException e) {
                if(logDEBUG)
                    Core.logger.log(this, "Got invalid message exception "+e, e, Logger.DEBUG);
                throw new TerminalMessageException("Got invalid message: "+e.getMessage());
            }
            if(logDEBUG)
                Core.logger.log(this, "Got "+m+": type = "+m.messageType, Logger.DEBUG);
            if (m.messageType.equals("Failed")) {
                String err = (m.fs.get("Reason") == null
                              ? "Failed (no reason given)"
                              : "Failed, reason: "+m.fs.get("Reason"));
                System.err.println("FCPClient.getResponse -- " + err);
                throw new TerminalMessageException(err);
            }
            else if (m.messageType.equals("FormatError")) {
                String err = (m.fs.get("Reason") == null
                              ? "FormatError (no reason given)"
                              : "FormatError, reason: "+m.fs.get("Reason"));
                //System.err.println("FCPClient.getResponse -- " + err);
                throw new TerminalMessageException(err);
            }
            else return m;
        }

        boolean readChunks(long length, OutputStream data)
            throws IOException, TerminalMessageException {
            while (length > 0) {
                RawMessage m = getResponse();
                if (m.messageType.equals("Restarted")) {
                    long time = (m.fs.get("Timeout") == null
                                 ? -1 : Fields.hexToLong(m.fs.getString("Timeout")));
                    return false;
                }
                else if (!m.messageType.equals("DataChunk")) {
                    throw new TerminalMessageException(
                        "Unexpected response: "+m.messageType);
                }
                else if (m.fs.get("Length") == null) {
                    throw new TerminalMessageException(
                        "Bad DataChunk; no Length field");
                }
                // got DataChunk
                long size;
                try {
                    size = Fields.hexToLong(m.fs.getString("Length"));
                }
                catch (Exception e) {
                    throw new TerminalMessageException(
                        "Bad DataChunk Length field: "+m.fs.get("Length"));
                }
                receiveData(size, data);
                length -= size;
            }

            return true;
        }
        
        protected int parseParam(RawMessage m, String name) {
            String s = m.fs.getString(name);
            if(s == null) return 0;
            try {
                return Integer.parseInt(s, 16);
            } catch (NumberFormatException e) {
                // GRRR
                // This is from FCP, so better log it
                Core.logger.log(this, "Invalid "+name+": "+s, Logger.NORMAL);
                return 0;
            }
        }
        
    }

    
    /** For node messages that kill the request, like Failed or FormatError,
      * or invalid messages.
      */
    private static class TerminalMessageException extends Exception {
        private final String err;
        private TerminalMessageException(String err) {
            this.err = err;
        }
        final String getErrorString() {
            return err;
        }
    }


    //=== ClientHello =========================================================

    private class FCPHandshake extends FCPInstance {

        private static final String COMMAND = "ClientHello";

        private final HandshakeRequest req;
        
        private FCPHandshake(HandshakeRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }

        void doit() throws Exception {
            if (tryState(Request.REQUESTING)) {
                send(COMMAND);
                RawMessage m = getResponse();
                if (m.messageType.equals("NodeHello")) {
                    req.prot = m.fs.getString("Protocol");
                    req.node = m.fs.getString("Node");        
                    tryState(Request.DONE);
                }
                else throw new TerminalMessageException(
                    "Unexpected response: " + m.messageType
                );
            }
        }
    }

    
    //=== GenerateSVKPair =====================================================

    private class FCPComputeSVKPair extends FCPInstance {

        private static final String COMMAND = "GenerateSVKPair";

        private final ComputeSVKPairRequest req;

        private FCPComputeSVKPair(ComputeSVKPairRequest req) {
            super(req);
            this.req = req;
        }
        
        final String getDescription() {
            return COMMAND;
        }
        
        void doit() throws Exception {
            if (tryState(Request.REQUESTING)) {
                send(COMMAND);
                RawMessage m = getResponse();
                if (m.messageType.equals("Success")) {
                    BigInteger priv = new BigInteger(1, Base64.decode(m.fs.getString("PrivateKey")));
		    byte[] cryptoKey;
		    String s = m.fs.getString("CryptoKey");
		    if(s != null) cryptoKey = Base64.decode(m.fs.getString("CryptoKey"));
		    else cryptoKey = null;
                    ClientSVK svk   = new ClientSSK(null, cryptoKey, "", 
						    new DSAPrivateKey(priv),
                                                    ClientSVK.getDefaultDSAGroup());
                    
                    req.clientKey = svk;
                    req.produceEvent(new GeneratedKeyPairEvent(svk.getPrivateKey(),
                                                               svk.getPublicKeyFingerPrint(),
							       svk.getCryptoKey()));
                    tryState(Request.DONE);
                }
                else throw new TerminalMessageException(
                    "Unexpected response: " + m.messageType
                );
            }
        }
    }


    //=== FCPInvertPrivateKey =====================================================

    // For now I send it over the wire so I can test the FCP implementation.
    // REDFLAG: fix. There's no reason for
    //          this to go over the wire.
    //          Pillage VirtualClient code.
    private class FCPInvertPrivateKey extends FCPInstance {

        private static final String COMMAND = "InvertPrivateKey";

        private final InvertPrivateKeyRequest req;

        private FCPInvertPrivateKey(InvertPrivateKeyRequest req) {
            super(req);
            this.req = req;
        }
        
        final String getDescription() {
            return COMMAND;
        }
        
        void doit() throws Exception {
            if (tryState(Request.REQUESTING)) {
                FieldSet fs = new FieldSet();
                fs.put("Private", req.privateValue);

                send(COMMAND, fs);
                RawMessage m = getResponse();
                if (m.messageType.equals("Success")) {
                    req.publicValue = m.fs.getString("Public");

                    // This makes the CLI ui work. 
                    // Not sure why anyone else would use it.
                    unlockedProduceEvent(new InvertedPrivateKeyEvent(req.publicValue,
                                                                     req.privateValue));
                    tryState(Request.DONE);
                }
                else throw new TerminalMessageException(
                    "Unexpected response: " + m.messageType
                );
            }
        }
    }

    //=== GenerateCHK =========================================================

    // REDFLAG: fix. There's no reason for
    //          this to go over the wire.
    //          Pillage VirtualClient code.
    private class FCPComputeCHK extends FCPInstance {

        private static final String COMMAND = "GenerateCHK";

        private final ComputeCHKRequest req;

        private FCPComputeCHK(ComputeCHKRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }
        
        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return;

            long length = req.meta.size() + req.data.size();
            
            send(COMMAND, length, req.meta.size());

            InputStream data = null;
            try {
                data = req.meta.getInputStream();
                data = new SequenceInputStream(data, req.data.getInputStream());
                data = new EventInputStream(data, req, length >> 4, req.meta.size(),
					    req.data.size(), length);
		// length is wrong (not padded), but it doesn't matter to us
		
                req.produceEvent(new TransferStartedEvent(length));
                sendData(length, data);
            }
            finally {
                if (data != null) data.close();
            }
            
            RawMessage m = getResponse();
            if (m.messageType.equals("Success")) {
                FreenetURI uri = new FreenetURI(m.fs.getString("URI"));
                req.clientKey  = (ClientCHK) ClientCHK.createFromRequestURI(uri);
                req.produceEvent(new GeneratedURIEvent("Generated CHK", uri));
                tryState(Request.DONE);
            }
            else throw new TerminalMessageException(
                "Unexpected response: " + m.messageType
            );
        }
    }
    
    //=== GenerateSHA1 =========================================================

    // REDFLAG: There's no reason for this to go over the wire.
    private class FCPComputeSHA1 extends FCPInstance {
        
        private static final String COMMAND = "GenerateSHA1";
        
        private final ComputeSHA1Request req;
        
        private FCPComputeSHA1(ComputeSHA1Request req) {
            super(req);
            this.req = req;
        }
        
        final String getDescription() {
            return COMMAND;
        }
        
        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return;
            
            long length = req.data.size();
            
            send(COMMAND, length, 0);
            
            InputStream data = null;
            try {
                data = req.data.getInputStream();
                data = new EventInputStream(data, req, length >> 4, 0, length, length);
                req.produceEvent(new TransferStartedEvent(length));
                sendData(length, data);
            }
            finally {
                if (data != null) data.close();
            }
            
            RawMessage m = getResponse();
            if (m.messageType.equals("Success")) {
                req.sha1 = m.fs.getString("SHA1");
                final String msg = "Made SHA-1 hash: " + req.sha1;
                // Hack to dump value to console for CLI clients.
                ClientEvent ce = new ClientEvent() {
                        public int getCode() { return 666; }
                        public String getDescription() { return msg; } 
                    };
                req.produceEvent(ce);

                tryState(Request.DONE);
            }
            else throw new TerminalMessageException("Unexpected response: " + 
                                                    m.messageType);
        }
    }
    
    //=== ClientPut ===========================================================
    
    private class FCPInsert extends FCPInstance {

        private static final String COMMAND = "ClientPut";

        private final PutRequest req;

        private FCPInsert(PutRequest req) {
            super(req);
            this.req = req;
        }
        
        final String getDescription() {
            return COMMAND + ": " + req.uri;
        }
        
        void doit() throws Exception {
            boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
            if (!tryState(Request.REQUESTING))
                return;

            long length = req.meta.size() + req.data.size();
            
            FieldSet fs = new FieldSet();
            fs.put("URI", req.uri.toString());
            fs.put("HopsToLive", Integer.toHexString(req.htl));
            // add support for --skipDS
            if (req.getNonLocal()) {
                fs.put("RemoveLocalKey", "true");
            }
            send(COMMAND, fs, length, req.meta.size());
            
            InputStream data = null;
            try {
                data = req.meta.getInputStream();
                data = new SequenceInputStream(data, req.data.getInputStream());
                data = new EventInputStream(data, req, length >> 4, req.meta.size(),
					    req.data.size(), length);
                req.produceEvent(new TransferStartedEvent(length));
                sendData(length, data);
            }
            finally {
                if (data != null) data.close();
            }
            if(logDEBUG)
                Core.logger.log(this, "Sent data", Logger.DEBUG);

            while (true) {
                if(logDEBUG)
                    Core.logger.log(this, "Waiting for response", Logger.DEBUG);
                RawMessage m = getResponse();
                if(logDEBUG) {
                    Core.logger.log(this, "message type is "+m.messageType+
                            " in FCPInsert.doit()", Logger.DEBUG);
                }
                if (m.messageType.equals("Pending")) {
                    FreenetURI uri = new FreenetURI(m.fs.getString("URI"));
                    req.produceEvent(new GeneratedURIEvent("Insert URI", uri));
                    if(logDEBUG)
                        Core.logger.log(this, "Insert URI: "+uri, Logger.DEBUG);
                    req.clientKey = AbstractClientKey.createFromRequestURI(uri);
                    long time = (m.fs.get("Timeout") == null
                                 ? -1 : Fields.hexToLong(m.fs.getString("Timeout")));
                    req.produceEvent(new PendingEvent(time));
                    conn.setSoTimeout((int)time+5000);
                }
                else if (m.messageType.equals("Success")) {
                    tryState(Request.DONE);
                    return;
                }
                else if (m.messageType.equals("Restarted")) {
                    long time = (m.fs.get("Timeout") == null
                                 ? -1 : Fields.hexToLong(m.fs.getString("Timeout")));
                    String why = m.fs.getString("Reason");
                    conn.setSoTimeout((int)time+5000);
                    req.produceEvent(new RestartedEvent(time, why));
                }
                else if (m.messageType.equals("RouteNotFound")) {
                    int unreachable = parseParam(m, "Unreachable");
                    int restarted = parseParam(m, "Restarted");
                    int rejected = parseParam(m, "Rejected");
                    int backedOff = parseParam(m, "BackedOff");
                    req.produceEvent(new RouteNotFoundEvent(m.fs.getString("Reason"),
                                                            unreachable,
                                                            restarted,
                                                            rejected,
                                                            backedOff));
                    tryState(Request.FAILED);
                    return;
                }
                else if (m.messageType.equals("KeyCollision")) {
                    FreenetURI uri = new FreenetURI(m.fs.getString("URI"));
                    req.clientKey  = AbstractClientKey.createFromRequestURI(uri);
                    req.produceEvent(new GeneratedURIEvent("Insert URI", uri));
		    if ("CHK".equals(uri.getKeyType())) {
			tryState(Request.DONE);
		    }
		    else
		    {
                    	req.produceEvent(new CollisionEvent(req.clientKey));
                    	tryState(Request.FAILED);
		    }
                    return;
                }
                else if (m.messageType.equals("URIError")) {
                    throw new TerminalMessageException("Bad URI: "+req.uri);
                }
                else {
                    throw new TerminalMessageException(
                        "Unexpected response: " + m.messageType
                    );
                }
            }
        }

    }

    //=== ClientGet ===========================================================
    
    private class FCPRequest extends FCPInstance {
        
        private static final String COMMAND = "ClientGet";
        
        private final GetRequest req;
        
        private FCPRequest(GetRequest req){
            super(req);
            this.req = req;
        }
        final String getDescription() {
            return COMMAND + ": " + req.uri;
        }

        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return;

            // create & send ClientGet-message
            FieldSet fs = new FieldSet();
            fs.put("URI", req.uri.toString());
            fs.put("HopsToLive", Integer.toHexString(req.htl)); 
            if (req instanceof ExtendedGetRequest) {
                // Ask the FCP server to send redirect hint
                // information.
                ExtendedGetRequest r = (ExtendedGetRequest)req;
                fs.put("MetadataHint", Boolean.TRUE.toString());
                if (r.timeSec >= 0) {
                    // Don't try to send negative longs as hex.
                    fs.put("RedirectTimeSec", Long.toHexString(r.timeSec));
                }
            }
            if (req.getNonLocal()) {
                fs.put("RemoveLocalKey", "true");
            }
            send(COMMAND, fs);

            while (true) {
                RawMessage m = getResponse();
                if (m.messageType.equals("DataFound")) {
                    if (doDataFound(m.fs)) {
                        tryState(Request.DONE);
                        return;
                    }
                }
                else if (m.messageType.equals("Restarted")) {
                    long time = (m.fs.get("Timeout") == null
                                 ? -1 : Fields.hexToLong(m.fs.getString("Timeout")));
                    String why = m.fs.getString("Reason");
                    req.produceEvent(new RestartedEvent(time, why));
                }
                else if (m.messageType.equals("DataNotFound")) {
                    req.produceEvent(new DataNotFoundEvent());

                    tryState(Request.FAILED);

                    return;
                }
                else if (m.messageType.equals("RouteNotFound")) {
                    int unreachable = parseParam(m, "Unreachable");
                    int restarted = parseParam(m, "Restarted");
                    int rejected = parseParam(m, "Rejected");
                    int backedOff = parseParam(m, "BackedOff");
                    req.produceEvent(new RouteNotFoundEvent(m.fs.getString("Reason"),
                                                            unreachable,
                                                            restarted,
                                                            rejected,
                                                            backedOff));
                    tryState(Request.FAILED);
                    return;
                }
                else if (m.messageType.equals("URIError")) {
                    throw new TerminalMessageException("Bad URI: "+req.uri);
                }
                else {
                    throw new TerminalMessageException(
                        "Unexpected response: " + m.messageType
                    );
                }
            }
        }

        private boolean doDataFound(FieldSet fs) throws TerminalMessageException,
                                                        IOException {
            if (fs.get("DataLength") == null)
                throw new TerminalMessageException("no DataLength field in DataFound");
            
            long dlen, mlen;
            try {
                dlen = Fields.hexToLong(fs.getString("DataLength"));
            }
            catch (Exception e) {
                throw new TerminalMessageException("bad DataLength field in DataFound");
            }
            try {
                if (fs.get("MetadataLength") != null)
                    mlen = Fields.hexToLong(fs.getString("MetadataLength"));
                else
                    mlen = 0;
            }
            catch (Exception e) {
                throw new TerminalMessageException("bad MetadataLength field in DataFound");
            }

            req.meta.resetWrite();
            req.data.resetWrite();

            Bucket[] buckets = { req.meta, req.data };
            long[] lengths   = { mlen, dlen-mlen, dlen };

            OutputStream data = new SegmentOutputStream(req, dlen >> 4,
                                                        buckets, lengths);

            req.produceEvent(new TransferStartedEvent(lengths));
            try {
                return readChunks(dlen, data);
            }
            finally {
                data.close();
            }
        }

        // Override FCPInstance implementation.
        boolean readChunks(long length, OutputStream data)
            throws IOException, TerminalMessageException {
            while (length > 0) {
                RawMessage m = getResponse();
                if (m.messageType.equals("Restarted")) {
                    long time = (m.fs.get("Timeout") == null
                                 ? -1 : Fields.hexToLong(m.fs.getString("Timeout")));
                    String reason = m.fs.getString("Reason");
                    req.produceEvent(new RestartedEvent(time, reason));
                    return false;
                }
                else if (!m.messageType.equals("DataChunk")) {
                    throw new TerminalMessageException(
                        "Unexpected response: "+m.messageType);
                }
                else if (m.fs.get("Length") == null) {
                    throw new TerminalMessageException(
                        "Bad DataChunk; no Length field");
                }
                // got DataChunk
                long size;
                try {
                    size = Fields.hexToLong(m.fs.getString("Length"));
                }
                catch (Exception e) {
                    throw new TerminalMessageException(
                        "Bad DataChunk Length field: "+m.fs.getString("Length"));
                }
                receiveData(size, data);
                length -= size;
            }
            
            if (req instanceof ExtendedGetRequest) {
                // Parse redirect hint information sent by the 
                // FCP server.
                RawMessage m = getResponse();
                if (m.messageType.equals("MetadataHint")) {
                    ExtendedGetRequest r = (ExtendedGetRequest)req;
                    r.timeSec = Long.parseLong(m.fs.getString("TimeSec"), 16);
                    r.kind = Integer.parseInt(m.fs.getString("Kind"), 16);        
                    r.mimeType = m.fs.getString("MimeType");
                    r.nextURI = m.fs.getString("NextURI");        
                    r.isMap = Fields.stringToBool(m.fs.getString("IsMapFile"), false);
                    r.increment = Integer.parseInt(m.fs.getString("Increment"), 16);
                    r.offset = Long.parseLong(m.fs.getString("Offset"), 16);
                }
                else {
                    throw new TerminalMessageException("Couldn't parse metadata hint.");
                }
            }

            return true;
        }
    }


    //=== FCPSegmentSplitFile =========================================================

    private class FCPSegmentSplitFile extends FCPInstance {

        private static final String COMMAND = "FECSegmentSplitFile";

        private final SegmentSplitFileRequest req;

        private FCPSegmentSplitFile(SegmentSplitFileRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }
        
        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return; 
            
            Bucket metadata = bf.makeBucket(-1);

            try {
                // Dump SplitFile into temp bucket.

                // Hmmm... 
                // Is there ever a case where it's important to allow
                // the client to specifiy MetadetaSettings?
                DocumentCommand doc = new DocumentCommand(new MetadataSettings());
                doc.addPart(req.sf);
                Metadata md = new Metadata(new MetadataSettings());
                md.addDocument(doc);
		OutputStream os = metadata.getOutputStream();
                md.writeTo(os);
		os.close();
                long length = metadata.size();
                if(length == 0) throw new IOException
				    ("written zero length metadata");
		
		
                // Yes.  I really do mean to send it as data.
                send(COMMAND, length, 0);
                
                InputStream data = null;
                try {
                    data = metadata.getInputStream();
                    sendData(length, data);
                }
                finally {
                    if (data != null) data.close();
                }
            }
            finally {
		bf.freeBucket(metadata);
            }

            RawMessage m = null;
            int segments = -1;
            Vector headers = new Vector();
            Vector maps = new Vector();

            do {
                // Read SegmentHeader
                m = getResponse();
                handleUnexpectedReply(m, "SegmentHeader");

                // hmmm... null ConnectionHandler
                headers.addElement(new SegmentHeader(null, m));
                if (headers.size() == 1) {
                    // Every segment header knows the total number of segments.
                    segments = ((SegmentHeader)headers.elementAt(0)).getSegments();
                }
                
                // Read BlockMap
                m = getResponse();
                handleUnexpectedReply(m, "BlockMap");
                maps.addElement(new BlockMap(null, m));
            } while (headers.size() < segments);
            
            
            // Copy values into request
            req.headers = new SegmentHeader[headers.size()];
            headers.copyInto(req.headers);
            req.maps = new BlockMap[maps.size()];
            maps.copyInto(req.maps);

            tryState(Request.DONE);
        }
    }

    //=== FCPDecodeSegment ===========================================================
    private class FCPDecodeSegment extends FCPInstance {

        private static final String COMMAND = "FECDecodeSegment";

        private final DecodeSegmentRequest req;

        private FCPDecodeSegment(DecodeSegmentRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }
        
        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return;

            FileBucket headerBucket = null;
            InputStream headerData = null;
            InputStream blockData = null;
            OutputStream decodedData = null;
            try {
                // Dump header to a bucket.
                headerBucket = (FileBucket)(bf.makeBucket(-1));
                dumpHeader(req.header, headerBucket);
            
                final long dataLen = req.header.getBlockSize() * req.data.length +
                    req.header.getCheckBlockSize() * req.checks.length;
                
                // The header is sent as data so we
                // have to append it's length to the data length.
                final long length = headerBucket.size() + dataLen;

                // REDFLAG: Change spec so clients don't have to do this?
                int[] withOffset = new int[req.checkIndices.length];
                for (int i = 0; i < req.checkIndices.length; i++) {
                    withOffset[i] = req.checkIndices[i] + req.header.getBlockCount();
                }
                
                // Send request.
                FieldSet fs = new FieldSet();
                fs.put("BlockList", hexList(req.dataIndices));
                fs.put("CheckList", hexList(withOffset));
                fs.put("RequestedList", hexList(req.requestedIndices));
                send(COMMAND, fs, length, 0);

                // Send header as trailing data.
                headerData = headerBucket.getInputStream();
                sendData(headerBucket.size(), headerData);

                // Then send data and check blocks.
                Bucket[] buckets = new Bucket[req.data.length + req.checks.length];
                System.arraycopy(req.data, 0, buckets, 0, req.data.length);
                System.arraycopy(req.checks, 0, buckets, req.data.length, req.checks.length);
                blockData = new BucketInputStream(buckets, dataLen);
                sendData(dataLen, blockData);

                // Get confirmation.
                RawMessage m = getResponse();
                handleUnexpectedReply(m, "BlocksDecoded");

                // Read decoded blocks.
                long[] bucketLens = new long[req.decoded.length];
                for (int i = 0; i < bucketLens.length; i++) {
                    bucketLens[i] = req.header.getBlockSize();
                }
                decodedData = new SegmentOutputStream(0, req.decoded, bucketLens);
                readChunks(req.decoded.length * req.header.getBlockSize(), decodedData);

                tryState(Request.DONE);
            }
            finally {
                if (decodedData != null) {
                    try { decodedData.close(); } catch (Exception e) {}
                }
                if (blockData != null) {
                    try { blockData.close(); } catch (Exception e) {}
                }
                if (headerData != null) {
                    try { headerData.close(); } catch (Exception e) {}
                }
                if (headerBucket != null) {
                    headerBucket.getFile().delete();
                }
            }
        }
    }

    //=== FECSegmentFile  ===========================================================
    private class FCPSegmentFile extends FCPInstance {

        private static final String COMMAND = "FECSegmentFile";

        private final SegmentFileRequest req;

        private FCPSegmentFile(SegmentFileRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }
        
        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return; 

            FieldSet fs = new FieldSet();
            if (req.algoName != null) {
                fs.put("AlgoName", req.algoName);
            }
            fs.put("FileLength", Long.toHexString(req.length));
            send(COMMAND,fs);

            int segments = 0;
            SegmentHeader[] headers = null;
            do {
                // Read SegmentHeader
                RawMessage m = getResponse();
                handleUnexpectedReply(m, "SegmentHeader");
                // hmmm... null ConnectionHandler
                SegmentHeader header = new SegmentHeader(null, m);

                if (headers == null) {
                    headers = new SegmentHeader[header.getSegments()];
                }
                headers[segments] = header;
                segments++;
            } while (segments < headers.length);
            
            req.headers = headers;
            tryState(Request.DONE);
        }
    }

    //=== FECEncodeSegment ===========================================================
    private class FCPEncodeSegment extends FCPInstance {

        private static final String COMMAND = "FECEncodeSegment";

        private final EncodeSegmentRequest req;

        private FCPEncodeSegment(EncodeSegmentRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }
        

        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return;

            FileBucket headerBucket = null;

            InputStream headerData = null;
            InputStream blockData = null;
            OutputStream checkData = null;
            try {
                // Dump header to a bucket.
                headerBucket = (FileBucket)(bf.makeBucket(-1));
                dumpHeader(req.header, headerBucket);
            
                final long dataLen = req.header.getBlockSize() * req.data.length;
                
                // The header is sent as metadata so we
                // have to append it's length to the data length.
                final long length = headerBucket.size() + dataLen;

                
                // Send request.
                FieldSet fs = new FieldSet();


                if (req.checkIndices != null) {
                    int[] withOffset = new int[req.checkIndices.length];
                    // REDFLAG: Change spec so clients don't have to do this?
                    for (int i = 0; i < req.checkIndices.length; i++) {
                        withOffset[i] = req.checkIndices[i] + req.header.getBlockCount();
                    }
                    fs.put("RequestedList", hexList(withOffset));
                }

                // This sets the Datalength and MetaDataLength fields.
                // Blocks are sent as data, header as metadata.
                send(COMMAND, fs, length, headerBucket.size());

                // Send header as metadata.
                headerData = headerBucket.getInputStream();
                sendData(headerBucket.size(), headerData);

                // Then send data blocks.
                blockData = new BucketInputStream(req.data, dataLen);
                sendData(dataLen, blockData);

                // Get confirmation.
                RawMessage m = getResponse();
                handleUnexpectedReply(m, "BlocksEncoded");

                // Read encoded blocks.
                long[] bucketLens = new long[req.checks.length];
                for (int i = 0; i < bucketLens.length; i++) {
                    bucketLens[i] = req.header.getCheckBlockSize();
                }

                checkData = new SegmentOutputStream(0, req.checks, bucketLens);
                readChunks(req.checks.length * req.header.getCheckBlockSize(), checkData);

                tryState(Request.DONE);
            }
            finally {
                if (checkData != null) {
                    try { checkData.close(); } catch (Exception e) {}
                }
                if (blockData != null) {
                    try { blockData.close(); } catch (Exception e) {}
                }
                if (headerData != null) {
                    try { headerData.close(); } catch (Exception e) {}
                }
                if (headerBucket != null) {
                    headerBucket.getFile().delete();
                }
            }
        }

    }

    //=== FECMakeMetadata.java =========================================================
    private class FCPMakeMetadata extends FCPInstance {

        private static final String COMMAND = "FECMakeMetadata";

        private final MakeMetadataRequest req;

        private FCPMakeMetadata(MakeMetadataRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }
        
        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return;

            FileBucket dataBucket = null;
            OutputStream dataOut = null;
            InputStream dataIn = null;
            OutputStream metaDataOut = null;
            try {
                // Dump headers and maps to a bucket.
                dataBucket = (FileBucket)(bf.makeBucket(-1));
                dataOut = dataBucket.getOutputStream();
                int i;
                for (i = 0; i < req.headers.length; i++) {
                    // Write header
                    RawMessage m = req.headers[i].toRawMessage(protocol,null);
                    m.writeMessage(dataOut);
                    // Write map
                    m = req.maps[i].toRawMessage(protocol,null);
                    m.writeMessage(dataOut);
                }
                dataOut.close();

                // Send request.
                FieldSet fs = new FieldSet();
                if (req.mimeType != null) {
                    fs.put("MimeType", req.mimeType);
                }
                if (req.description != null) {
                    fs.put("Description", req.description);
                }
                
                send(COMMAND, fs, dataBucket.size(), 0);

                // Send header/map list as trailing data
                dataIn = dataBucket.getInputStream();
                sendData(dataBucket.size(), dataIn);

                // Get confirmation.
                RawMessage m = getResponse();
                handleUnexpectedReply(m, "MadeMetadata");
                
                // REDFLAG: fishy. Why did I do this?
                long size = Long.parseLong(m.fs.getString("DataLength"), 16);
                

                metaDataOut = req.metaData.getOutputStream();

                readChunks(size, metaDataOut);

                tryState(Request.DONE);
            }
            finally {
                if (dataOut != null) {
                    try { dataOut.close(); } catch (Exception e) {}
                }
                if (dataIn != null) {
                    try { dataIn.close(); } catch (Exception e) {}
                }
                if (metaDataOut != null) {
                    try { metaDataOut.close(); } catch (Exception e) {}
                }
                if (dataBucket != null) {
                    dataBucket.getFile().delete();
                }
            }
        }
    }

    //=== FCPSplitFileRequest =======================================================
    // I'm definitely off the reservation here.
    // I create the empty husk of a command a) to propagate
    // events to client code and b) to get a thread to run
    // the SplitFileRequestManager request/decode state machine
    // in.  
    private class FCPSplitFileRequest extends FCPInstance {

        private static final String COMMAND = "FAKESplitFileRequest";

        private final SplitFileGetRequest req;

        // Package scope on purpose.  SplitFileRequestProcess
        // uses this to implement abort().
        SplitFileRequestManager manager = null;

        private FCPSplitFileRequest(SplitFileGetRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }

        void doit() throws Exception {
            // IMPORTANT: Create before REQUESTING transition so that
            //            SplitFileRequestProcess can grab the manager
            //            reference in the REQUESTING state changed event.
            req.manager = new SplitFileRequestManager(req);

            if (tryState(Request.REQUESTING)) {
                // Blocks until the SplitFileRequest finishes.
                req.manager.execute(req.cf);
                tryState(Request.DONE);
            }
        }
        // This request never goes over the wire.
        private void negotiateConnection() throws Exception { /*NOP*/ }
    }

    //=== FCPSplitFileInsert =======================================================
    // I'm definitely off the reservation here.
    // I create the empty husk of a command a) to propagate
    // events to client code and b) to get a thread to run
    // the SplitFileInsertManager request/decode state machine
    // in.  
    private class FCPSplitFileInsert extends FCPInstance {

        private static final String COMMAND = "FAKESplitFileInsert";

        private final SplitFilePutRequest req;

        // Package scope on purpose.  SplitFileInsertProcess
        // uses this to implement abort().
        SplitFileInsertManager manager = null;

        private FCPSplitFileInsert(SplitFilePutRequest req) {
            super(req);
            this.req = req;
        }

        final String getDescription() {
            return COMMAND;
        }

        void doit() throws Exception {
            // IMPORTANT: Create before REQUESTING transition so that
            //            SplitFileRequestProcess can grab the manager
            //            reference in the REQUESTING state changed event.
            req.manager = new SplitFileInsertManager(req);

            if (tryState(Request.REQUESTING)) {
                // Blocks until the SplitFileInsert finishes.
                req.manager.execute(req.cf);
                tryState(Request.DONE);
            }
        }
        // This request never goes over the wire.
        private void negotiateConnection() throws Exception { /*NOP*/ }
    }

    ////////////////////////////////////////////////////////////
    // Helper functions
    ////////////////////////////////////////////////////////////

    // Reduce code duplication
    private final static void handleUnexpectedReply(RawMessage m, String expectedMsg) 
        throws TerminalMessageException {
        if (!m.messageType.equals(expectedMsg)) {
            if (m.messageType.equals("Failed")) {
                String err = (m.fs.get("Reason") == null
                              ? "Failed (no reason given)"
                              : "Failed, reason: "+m.fs.get("Reason"));
                throw new TerminalMessageException(err);
            }
            else {
                String err = 
                    "Failed because of unexpected reply: " + m.messageType;
                throw new TerminalMessageException(err);
            }
        }
                    
    }

    private final static void dumpHeader(SegmentHeader header, Bucket bucket) throws IOException {
        RawMessage m = header.toRawMessage(protocol,null);
        OutputStream o = null;
        try {
            o = bucket.getOutputStream();
            m.writeMessage(o);
        }
        finally {
            if (o != null) {
                try { o.close(); } catch (Exception e) {}
            }
            o = null;
        }
    }

    private final static String hexList(int[] ints) {
        StringBuffer ret = new StringBuffer();
        for (int i = 0; i < ints.length; i ++) {
            ret.append(Integer.toString(ints[i], 16) + ",");
        }
        if (ret.length() > 0) {
            ret.setLength(ret.length() - 1);
        }
        return ret.toString();
    }
    
    public boolean isOverloaded() {
	// FIXME: implement using the status message?
	return false;
    }
    
    public boolean isReallyOverloaded() {
	// FIXME: implement using the status message?
	return false;
    }
}





