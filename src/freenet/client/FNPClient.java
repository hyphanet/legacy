package freenet.client;

import freenet.*;
import freenet.support.*;
import freenet.support.io.*;
import freenet.message.*;
import freenet.node.states.request.RequestState;
import freenet.client.events.*;
import freenet.client.listeners.*;
import freenet.crypt.*;
import java.io.*;
import java.util.Enumeration;
/** 
 * FNP version of Client library
 * @author oskar
 * @author tavin
 */
public class FNPClient implements ClientFactory {

    protected final VirtualClient vc;
    protected final ClientCore core;
    protected Peer target;
    protected BucketFactory ctBuckets;

    /** 
     * Create a new ClientFactory for spawning Clients to process requests.
     * @param core    A running ClientCore object that provides 
     *                Freenet services.
     * @param target  The node to send messages to. Ideally this should be a
     *                node running locally.
     */    
    public FNPClient(ClientCore core, Peer target) {
        this(core, target, new TempBucketFactory());
    }

    /** 
     * Create a new ClientFactory for spawning Clients to process requests.
     * @param core    A running ClientCore object that provides 
     *                Freenet services.
     * @param target  The node to send messages to. Ideally this should be a
     *                node running locally.
     * @param ctBuckets  A factory to produce temporary buckets for ciphertext.
     */
    public FNPClient(ClientCore core, Peer target, BucketFactory ctBuckets) {
        this(core, ctBuckets);
        this.target = target;
    }

    /**
     * Requires setting target manually.
     */
    protected FNPClient(ClientCore core, BucketFactory ctBuckets) {
        this.core = core;
        this.ctBuckets = ctBuckets;
        vc = new VirtualClient(null, Core.getRandSource(), ctBuckets);
    }

    protected FNPClient(ClientCore core) {
        this(core, new TempBucketFactory());
    }
    
    
    public boolean supportsRequest(Class c) {
        return GetRequest.class.isAssignableFrom(c) ||
               PutRequest.class.isAssignableFrom(c) ||
               vc.supportsRequest(c);
    }

    public void stop() {
        core.stop();
    }

    public Client getClient(Request req)
        throws UnsupportedRequestException, KeyException {

        if (req instanceof GetRequest)
            return new BRequestInstance((GetRequest) req);
        else if (req instanceof PutRequest)
            return new BInsertInstance((PutRequest) req);
        else
            return vc.getClient(req);
    }

    /** BInstance, BRequestInstance, and BInsertInstance implement Client
      * and do the grunt work of executing a request.  They handle all the
      * necessary negotiation with the Freenet node, and will
      * generate periodic events for any ClientEventListeners
      * registered with the Request object.
      *
      * At the end of a request, if it was sucessful, a 
      * TransferCompletedEvent will be generated, indicating the
      * end of a transfer.
      */
    private abstract class BInstance implements Client, Runnable {

        final long id;
        final Request oreq;
        Key key;
        ClientKey ckey;
        CollectingEventListener events;

        Thread executor;

        BInstance(Request req) {
            this.oreq = req;
            id = Core.getRandSource().nextLong();
        }

        public final void start() {
            executor = new Thread(this, "FNP Client: "
                                        + this.getClass().getName());
            executor.start();
        }

        public final int blockingRun() {
            executor = Thread.currentThread();
            run();
            return state();
        }
        
        final int state() {
            return oreq.state();
        }

        synchronized final void state(int state) {
            oreq.state(state);
            oreq.produceEvent(new StateReachedEvent(state));
        }

        synchronized final boolean tryState(int state) {
            if (state() != Request.CANCELLED) {
                state(state);
                return true;
            }
            else return false;
        }

        public synchronized boolean cancel() {
            if (state() >= Request.INIT && state() < Request.DONE) {
                state(Request.CANCELLED);
                executor.interrupt();
                return true;
            }
            else return false;
        }
        
        OutputStream sendMessage(Message m, long timeout)
	    throws CommunicationException {
	    TrailerWriter tw = 
		core.sendMessage(m, target, timeout);
            OutputStream out = new TrailerWriterOutputStream(tw);
            oreq.produceEvent(new SendEvent(target,m,""));
            return out;
        }

        ClientMessageObject getNextReply(long id, long millis)
                                                throws InterruptedException {
            //try {
                ClientMessageObject m = core.cmh.getNextReply(id,millis);
                if (m != null)
                    oreq.produceEvent(new ReceiveEvent(m instanceof Message
                                                      ? target : null, m, ""));
                return m;
            //} catch (InterruptedException e) {
            //    return null;
            //}
        }        

        abstract void prepare() throws Exception;

        abstract void doit() throws Exception;

        abstract void cleanup();

        public void run() {
            try {
                events = new CollectingEventListener();
                oreq.addEventListener(events);
                synchronized (this) {
                    if (state() == Request.CANCELLED)
                        return;
                    prepare();
                    state(Request.PREPARED);
                }
                doit();
            } catch (Exception e) {
                synchronized(this) {
                    if (state() == Request.CANCELLED)
                        return;
                    e.printStackTrace();
                    oreq.produceEvent(new ExceptionEvent(e));
                    state(Request.FAILED);
                }
            } finally {
                cleanup();
            }
        }

        public final Enumeration getEvents() {
            return events.events();
        }
    }

    private class BRequestInstance extends BInstance {

        private final GetRequest req;

        BRequestInstance(GetRequest req) throws KeyException {
            super(req);
            this.req = req;
            ckey = AbstractClientKey.createFromRequestURI(req.uri);
            key  = ckey.getKey();
            if (key == null) throw new KeyException("got null Key");
        }

        final void prepare() {}
        final void cleanup() {}

        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return;

            DataRequest dr = new DataRequest(id, req.htl, key,
                                             core.identity);

            sendMessage(dr, Core.hopTime(1, 0));

            getResponse();
        }

        /* jumping so we can reenter here */
        private void getResponse() throws Exception {
            long waitTime = Core.hopTime(1, 0);
            boolean accepted = false, receivedData = false, receivedSD = false;

            while (state() == Request.REQUESTING) {
                ClientMessageObject m = getNextReply(id, waitTime);
                
                if (receivedData && !receivedSD) { // only expecting StoreData
                    if (m instanceof StoreData) {
                        Core.logger.log(this, "Received StoreData",
                                        Logger.DEBUG);
                        tryState(Request.DONE);
                    } else if (m == null) {
                        Core.logger.log(this, "Did NOT receive StoreData",
                                        Logger.MINOR);
                        tryState(Request.DONE);
                    } else {
                        Core.logger.log(this, "Delayed message: " + m,
                                        Logger.DEBUG);
                    }
                } else if (accepted == false && m instanceof Accepted) {
                    waitTime = RequestState.hopTimeHTL(req.htl, RequestState.remoteQueueTimeout((int)key.getExpectedDataLength(), false));
                    accepted = true;
                } else if (m instanceof StoreData) {
                    receivedSD = true;
                } else if (m instanceof DataReply) {
                    
                    DataReply reply = (DataReply) m;
                    try {

                        Storables storables = reply.getStorables();
                        Document doc = ckey.decode(storables, reply.length());
                    
                        VerifyingInputStream vin =
                            key.verifyStream(reply.getDataStream(), 
                                             storables, reply.length());
                        //vin.stripControls(true);
                        EventInputStream ein = 
                            new EventInputStream(vin, req,
                                                 ckey.getTotalLength() >> 4,
						 doc.length(), 
						 doc.metadataLength(),
                                                 ckey.getTotalLength());

                        req.meta.resetWrite();
                        req.data.resetWrite();
                        
                        Bucket[] buckets = { req.meta, req.data };
                        long[] lengths   = { doc.metadataLength(), 
                                             doc.dataLength(), doc.length() };
                        
                        req.produceEvent(new TransferStartedEvent(lengths));
                        
                        OutputStream out = null;
                        try {
                            out = new SegmentOutputStream(ckey.getPartSize(),
                                                          buckets, lengths);
                            out = doc.decipheringOutputStream(out);
			    // FIXME: do we handle Restarted during streaming correctly?
                            out = new CBStripOutputStream(out,
                                                          ckey.getPartSize(),
                                                          ckey.getControlLength());
                            
                            readData(ein, out, reply.length());
                        } finally {
                            if (out != null) out.close();
                        }
                        
                        receivedData = true;
                        
                        if (receivedSD)
                            tryState(Request.DONE);
                        else
                            waitTime = 10000;  // FIXME
                        
                    } catch (DataNotValidIOException dnv) {
                        //dnv.printStackTrace();
                        if (dnv.getCode() != Presentation.CB_RESTARTED) {
                            req.produceEvent(new ExceptionEvent(dnv));
                            tryState(Request.FAILED);
                        }
                    } finally {
                        reply.getDataStream().close();
                    }
                } else if (m instanceof QueryRestarted) {
                    waitTime = RequestState.hopTimeHTL(req.htl, RequestState.remoteQueueTimeout((int)key.getExpectedDataLength(), false));
                    req.produceEvent(new RestartedEvent(waitTime, null));
                } else if (m instanceof DataNotFound) {
                    req.produceEvent(new DataNotFoundEvent());
                    tryState(Request.FAILED);
                } else { 
                    if (m == null)
                        req.produceEvent(new NoReplyEvent());
                    else if (m instanceof QueryRejected)
                        req.produceEvent(new RouteNotFoundEvent("Got QueryRejected",
                                                                0, 0, 1, 0));
                    else
                        req.produceEvent(new ErrorEvent("Got an unexpected " +
                                                    "internal MessageObject: " 
                                                        + m ));
                    tryState(Request.FAILED);
                }
            }
        }
        
        private void readData(InputStream in, OutputStream out, long len)
            throws IOException, DataNotValidIOException {
            byte[] buffer=new byte[Core.blockSize];
            
            int rc=0;
            do {
                if (state() == Request.CANCELLED) {
                    Core.logger.log(this, "Aborting download",
                                    Logger.DEBUG);
                    return;
                }
                rc=in.read(buffer, 0, (int)Math.min(len, buffer.length));
                
                if (rc>0) {
                    len-=rc;
                    out.write(buffer, 0, rc);
                }
            } while (rc!=-1 && len!=0);

            while (rc != -1) { // eat padding
                rc = in.read(buffer);
            }
        }
    }

    private class BInsertInstance extends BInstance {

        private final PutRequest req;
        private Storables storables;
        
        private Bucket ctBucket;
        private InputStream processedInput;
        
        public BInsertInstance(PutRequest req) 
            throws KeyException {

            super(req);
            this.req = req;
            ckey = AbstractClientKey.createFromInsertURI(Core.getRandSource(),
                                                         req.uri);
            try {
                ckey.setCipher(req.cipherName);
            }
            catch (UnsupportedCipherException e) {
                throw new KeyException(""+e);
            }
        }

        void prepare() throws Exception {
            long total = ckey.getTotalLength(req.meta.size() + req.data.size());
            ctBucket = ctBuckets.makeBucket(total);
            processedInput =
                new FreeBucketInputStream(ckey.encode(req.meta, req.data, ctBucket),
                                          ctBuckets, ctBucket);
            ctBucket = null;
            
            processedInput = new EventInputStream(processedInput, req,
                                                  total >> 4, req.meta.size(),
						  req.data.size(), total);
            storables = ckey.getStorables();
            // encrypt and calculate hash/signature
            req.produceEvent(new GeneratedURIEvent("Insert URI",
                                                   ckey.getURI()));
            // set key and storables, set ClientKey in Request object
            key = ckey.getKey();
            req.clientKey = ckey;
        }

        void cleanup() {
            try {
                if (processedInput != null)
                    processedInput.close();
                else if (ctBucket != null)
                    ctBuckets.freeBucket(ctBucket);
            }
            catch (IOException e) {}  // log it ??
        }

        void doit() throws Exception {
            if (!tryState(Request.REQUESTING))
                return;
            
            InsertRequest ir = new InsertRequest(id, req.htl, key, 
                                                 core.identity);
            sendMessage(ir, Core.hopTime(1, 0));

            long waitTime = Core.hopTime(1, 0);
            boolean accepted = false, sentData = false;
            
            while (state() == Request.REQUESTING) {
                ClientMessageObject reply = 
                    getNextReply(id, waitTime); 
            
                if (sentData) {
                    if (reply instanceof StoreData) {
                        Core.logger.log(this, "Received StoreData",
                                        Logger.DEBUG);
                        tryState(Request.DONE);                 
                    } else if (reply == null) {
                        Core.logger.log(this, "Did NOT receive StoreData",
                                        Logger.MINOR);
                        tryState(Request.DONE);
                    } else if (reply instanceof DataReply) {
                        keyCollision((DataReply) reply, ckey);
                        tryState(Request.FAILED);
                    } else if (reply instanceof InsertReply) {
                        waitTime = 24 * 60 * 60 * 1000; // he...
                        req.produceEvent(new PendingEvent(waitTime));
                    } else {
                        Core.logger.log(this, "Delayed message: " + 
                                        reply, Logger.DEBUG);
                    }
                } else if (!accepted && reply instanceof Accepted) {
                    accepted = true;
                    waitTime = RequestState.hopTimeHTL(req.htl, RequestState.remoteQueueTimeout((int)req.clientKey.getPaddedLength(), true));
                    FieldSet root=new FieldSet();
                    storables.addTo(root);
                    DataInsert di = 
                        new DataInsert(id, root,  
                                       ckey.getTotalLength());
                    OutputStream out = sendMessage(di, waitTime);
                    req.produceEvent(new TransferStartedEvent(di.length()));
                    try {
                        writeData(out, ckey.getTotalLength());
                        sentData = true;
                    } finally {
                        out.close();
                    }
                } else if (reply instanceof QueryRestarted) {
                    req.produceEvent(new RestartedEvent(waitTime, null));
                } else if (reply instanceof InsertReply) {
                    waitTime = 24 * 60 * 60 * 1000; // he...
                    req.produceEvent(new PendingEvent(waitTime));
                } else if (reply instanceof DataReply) {
                    keyCollision((DataReply) reply, ckey);
                    tryState(Request.FAILED);
                } else {
                    if (reply instanceof QueryRejected) {
                        req.produceEvent(new RouteNotFoundEvent("Got QueryRejected",
                                                                0, 0, 1, 0));
                    } else if (reply == null) {
                        req.produceEvent(new NoReplyEvent());
                    } else {
                        req.produceEvent(new ErrorEvent("Got an unexpected " +
                                                        "internal MessageObject: " 
                                                        + reply ));
                    }
                    tryState(Request.FAILED);
                }
            }
        }
        
        private void keyCollision(DataReply dr, ClientKey ckey) throws IOException {
            req.produceEvent(new CollisionEvent(ckey));
            InputStream in = dr.getDataStream();
            try {
                byte[] buffer = new byte[0xffff];
                int rc = 0;
                while (rc != -1) { // eat padding
                    rc = in.read(buffer);
                }
            } finally {
                in.close();
            }
        }

        private void writeData(OutputStream out, long length) 
            throws IOException {
            
            int i;
            byte[] b = new byte[Core.blockSize];
            while (length > 0 && ((i = processedInput.read(b, 0, (int) 
                                                           Math.min(b.length, 
                                                                    length))) 
                                  != -1) && state() != Request.CANCELLED) {
                out.write(b, 0, i);
                length -= i;
            }
            try {
                processedInput.close();
            } finally {
                processedInput = null;
            }
        }
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
