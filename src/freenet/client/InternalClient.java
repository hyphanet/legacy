package freenet.client;

import freenet.*;
import freenet.client.events.*;
import freenet.client.listeners.*;
import freenet.crypt.*;
import freenet.message.*;
import freenet.node.*;
import freenet.node.states.request.*;
import freenet.support.Bucket;
import freenet.support.io.*;
import java.io.*;
import java.util.Enumeration;
import freenet.support.Logger;

/** Implementation of Client that uses the node's message handling
  * states to perform requests.
  * @author tavin
  */
public class InternalClient implements ClientFactory {

    private final Node node;
    private VirtualClient vc;
    
    /** Create a new ClientFactory for spawning Clients to process requests.
      * @param node  the running node implementation
      */    
    public InternalClient(Node node) {
        this.node = node;
	if(node == null) 
	    throw new IllegalArgumentException("null node pointer to "+
					       "InternalClient");
        vc = new VirtualClient(node, Core.getRandSource(), node.bf);
    }
    
    public boolean isOverloaded() {
	return node.rejectingRequests(false);
    }
    
    public boolean isReallyOverloaded() {
	return node.rejectingConnections();
    }
    
    public Client getClient(Request req) throws UnsupportedRequestException,
                                                IOException, KeyException {
        if (req instanceof GetRequest) {
            return new ClientMessageVector(
                new NewInternalGet(Core.getRandSource().nextLong(),
                                   new InternalGetToken((GetRequest) req))
            );
        }
        else if (req instanceof PutRequest) {
            return new ClientMessageVector(
                new NewInternalPut(Core.getRandSource().nextLong(),
                                   new InternalPutToken((PutRequest) req))
            );
        }
        // see if VirtualClient can handle it ..
        else return vc.getClient(req);
    }

    public boolean supportsRequest(Class req) {
        return GetRequest.class.isAssignableFrom(req)
            || PutRequest.class.isAssignableFrom(req)
            || vc.supportsRequest(req);
    }

    private final class ClientMessageVector implements Client, NodeMessageObject {
        
        private final InternalClientState s;
        
        public ClientMessageVector(InternalClientState s) {
            this.s = s;
        }
        
        public final long id() {
            return s.id();
        }

        public final boolean isExternal() {
            return true; // Changed by Oskar - can't get ReceivedData otherwise
            // (also it is correct, since this ID does leave the node).
        }
        
        // we're just a vector to get this State where it belongs
        public final State getInitialState() {
            return s.ft.isAlive() ? s : null;
        }
        
        // node overflowed, inform the user
        public final void drop(Node n) {
            s.lost(n);
        }
        
        public final void start() {
            if (s.ft.isAlive()) node.schedule(this);
        }

        public final int blockingRun() {
            start();
            s.ft.dl.waitDone();
            return s.ft.state();
        }

        public final boolean cancel() {
            return s.ft.cancel();
        }

        public final Enumeration getEvents() {
            return s.ft.cel.events();
        }
    }

    
    //=== FeedbackToki =========================================================
    
    private abstract class InternalFeedbackToken implements FeedbackToken {
        
        final Request request;
        DoneListener dl;
        CollectingEventListener cel;
        
        ClientKey clientKey;
	
	long skipBytes = 0;
        InternalDataChunkOutputStream dcos = null;
	Object dcosSync;
	
	public String toString() {
	    return InternalFeedbackToken.this.getClass().getName()+
		": request={"+request+"}, key="+clientKey+", skipBytes="+skipBytes;
	}
	
        public InternalFeedbackToken(Request req) {
            this.request = req;
	    dcosSync = new Object();
            this.dl  = new DoneListener();
            this.cel = new CollectingEventListener();
            req.addEventListener(dl);
            req.addEventListener(cel);
        }

        final boolean isAlive() {
            return state() >= Request.INIT;
        }

        final void checkAlive() throws SendFailedException {
            if (!isAlive()) throw new SendFailedException(
                null, "Request reached state "+request.stateName()
            );
        }

        final int state() {
            return request.state();
        }

        
        // We return the StateReachedEvent so that it 
        // can be sent in an unlocked scope.
        synchronized final StateReachedEvent state(int state) {
            request.state(state); // ok, not locked for (Get/Put)Request
            return new StateReachedEvent(state);
        }
        
        // DO NOT call this while holding a lock on (this).
        // If you do, client event handlers may deadlock.
        //
        // e.g.:
        // Thread 0:
        // InternalFeedBackToken -- locked
        //    --> client event handler
        //        --> ClientObject -- waits for lock on ClientObject
        //
        // Thread 1:
        // ClientObject -- locked
        //   --> Client.cancel()
        //       --> InternalFeedBackToken.cancel -- waits for lock on 
        //                                           InternalFeedBackToken
        //
        //
        // Send an event to the listeners.
        final void unlockedProduceEvent(ClientEvent evt) {
	    if(Core.logger.shouldLog(Logger.DEBUG, this))
		Core.logger.log(this, "Producing event: "+evt+" ("+this+")",
				Logger.DEBUG);
            if (evt == null) {
                return;
            }
            if (request == null) {
                return;
            }
            request.produceEvent(evt);
        }
        
        final boolean cancel() {
            ClientEvent evt = null;
            synchronized (InternalFeedbackToken.this) {
                if (state() >= Request.INIT && state() < Request.DONE) {
                    evt = state(Request.CANCELLED);
                }
            }
            unlockedProduceEvent(evt);
            return evt != null;
        }
        
        final void fail(Exception e) {
            ClientEvent evt = null;
            synchronized (InternalFeedbackToken.this) {
                if (state() >= Request.INIT && state() < Request.DONE) {
                    evt = state(Request.FAILED);
                }
            }
            if (evt != null) {
                unlockedProduceEvent(new ExceptionEvent(e));
                unlockedProduceEvent(evt);
            }
        }
        
        public void queryRejected(Node n, int htl, String reason,
                                  FieldSet fs, int unreachable,
                                  int restarted, int rejected,
                                  int backedOff, MessageSendCallback cb)
            throws SendFailedException {
            ClientEvent evt = null;
            synchronized (InternalFeedbackToken.this) {
                checkAlive();
                evt = state(Request.FAILED);
            }
            
            unlockedProduceEvent(new RouteNotFoundEvent(reason, unreachable,
                                                        restarted, rejected,
                                                        backedOff));
            unlockedProduceEvent(evt);
        }

        public synchronized void restarted(Node n, long millis,
					   MessageSendCallback cb, String reason) 
	    throws SendFailedException {
            synchronized (InternalFeedbackToken.this) {
                checkAlive();
            }
	    synchronized (dcosSync) {
		if(dcos != null) {
		    synchronized(dcos) {
			if(dcos.chunkPos > skipBytes) {
			    skipBytes = dcos.chunkPos;
			    if(skipBytes != 0)
			    	if(Core.logger.shouldLog(Logger.DEBUG,this))
				Core.logger.log(this, "Restarting InternalClient request for "+clientKey+", next pass will skip first "+skipBytes, Logger.MINOR);
				else
				Core.logger.log(this, "Restarting InternalClient request for a clientKey, next pass will skip first "+skipBytes, Logger.MINOR);
			}
		    }
		}
	    }
            unlockedProduceEvent(new RestartedEvent(millis, reason));
        }
	
	class InternalDataChunkOutputStream extends 
	    FilterDataChunkOutputStream {
	    InternalDataChunkOutputStream(OutputStream out, long length, 
					  long chunkSize) {
		super(out, length, chunkSize);
	    }
	    
	    long chunkPos = 0;
	    
	    protected void sendChunk(int chunkSize) throws IOException {
		synchronized(this) {
		    long oldPos = chunkPos;
		    chunkPos += chunkSize;
		    if(chunkPos <= skipBytes) return;
		    if(oldPos < skipBytes) {
			// oldPos < skipBytes < chunkPos
			int offset = (int)(skipBytes - oldPos);
			int length = (int)(chunkPos - (skipBytes - oldPos));
			out.write(buffer, offset, length);
		    } else {
			out.write(buffer, 0, chunkSize);
		    }
		} // Must sync on the whole block because otherwise skipBytes might end up wrong if we close
	    }
	}
    }
    
    private class InternalGetToken extends InternalFeedbackToken {
        private final GetRequest req;
        boolean transferStarted = false;
        
        public InternalGetToken(GetRequest req) {
            super(req);
            this.req = req;
	    this.req.htl = Node.perturbHTL(this.req.htl);
            req.addEventListener(new TransferCompleteListener());
        }
        
        private class TransferCompleteListener implements ClientEventListener {
            public final void receive(ClientEvent ce) {
                // hrm.. not sure I like this but with the current framework it's
                // the only way to gauge when the request can be considered DONE
                if (ce instanceof TransferCompletedEvent ||
                		ce instanceof ExceptionEvent ||
                		ce instanceof ErrorEvent ||
                		ce instanceof TransferFailedEvent) {
                	// ExceptionEvent means something messed up, so
                	// terminate the request. TransferCompletedEvent
                	// means we actually transferred the data, so
                	// terminate the request.
                    ClientEvent evt = null;
                    synchronized (InternalGetToken.this) {
                        if (isAlive()) { 
                        	if(ce instanceof TransferCompletedEvent)
                            evt = state(Request.DONE);
                        	else
                        		evt = state(Request.FAILED);
                        }
                    }
                    unlockedProduceEvent(evt);
                }
            }
	    
	    public String toString() {
		return super.toString()+
		    ":"+InternalGetToken.this.toString();
	    }
        }
        
        public final void insertReply(Node n, long millis) {}  // not
        public void storeData(Node n, NodeReference nr, FieldSet fs, long rate, int hopsSinceReset, MessageSendCallback cb)
	{}  // don't care
        
        public void dataNotFound(Node n, long timeOfQuery, 
				 MessageSendCallback cb) 
	    throws SendFailedException {
            ClientEvent evt = null;
            synchronized (InternalGetToken.this) {
                checkAlive();
                evt = state(Request.FAILED);
                
            }
            if (evt != null) {
                unlockedProduceEvent(new DataNotFoundEvent());
                unlockedProduceEvent(evt);
            }
        }

        public synchronized TrailerWriter dataFound(Node n, Storables storables,
						    long transLength)
            throws SendFailedException {
            Document doc;
            OutputStream ret = null;
            ClientEvent transferEvt = null;
            ClientEvent exceptionEvt = null;
            ClientEvent failedEvt = null;
            synchronized (InternalGetToken.this) {
                try {
                    checkAlive();
		    doc = clientKey.decode(storables, transLength);
		    
                    Bucket[] buckets = { req.meta, req.data };
                    long[] lengths   = { doc.metadataLength(), 
					 doc.dataLength(),
					 doc.length() };
		    Core.logger.log(this, "Lengths: "+lengths[0]+","+lengths[1]+
				    " for "+this, Logger.DEBUG);
                    // a little sneaky here, we'll pass this to
                    // SegmentOutputStream too, knowing it will
                    // will ignore the last length
                    transferEvt = new TransferStartedEvent(lengths);
                    
                    OutputStream tmpOut = new 
			SegmentOutputStream(req, 
					    clientKey.getPartSize(),
					    buckets, lengths, true);
		    tmpOut = doc.decipheringOutputStream(tmpOut);

		    synchronized(dcosSync) {
			dcos = new InternalDataChunkOutputStream(tmpOut, 
								 doc.length(),
								 clientKey.getPartSize());
			
			tmpOut = dcos;
		    }
                    
                    ret =
                        new CBStripOutputStream(tmpOut,
						clientKey.getPartSize(), 
						clientKey.getControlLength());
                } catch (DataNotValidIOException e) {
                    exceptionEvt = new DocumentNotValidEvent(e);
                    failedEvt = state(Request.FAILED);
                    ret = null;
                } catch (KeyException e) {
		    exceptionEvt = new ExceptionEvent(e);
		    failedEvt = state(Request.FAILED);
		    ret = null;
		}
            }
            unlockedProduceEvent(transferEvt);
            unlockedProduceEvent(exceptionEvt);
            unlockedProduceEvent(failedEvt);
            if(ret == null) return null;
            else return new OutputStreamTrailerWriter(ret);
        }
    }
    
    private class InternalPutToken extends InternalFeedbackToken {
        
        private final PutRequest req;
        private boolean keyCollision = false;
        
        public InternalPutToken(PutRequest req) {
            super(req);
            this.req = req;
	    this.req.htl = Node.perturbHTL(this.req.htl);
        }

        public final void dataNotFound(Node n, long timeOfQuery,
				       MessageSendCallback cb) {}  // not
        
        public void insertReply(Node n, long millis)
                                    throws SendFailedException {
            ClientEvent uriEvt = null;
            ClientEvent pendingEvt = null;
            synchronized (InternalPutToken.this) {
                checkAlive();
                //keyCollision = true; REDFLAG: check with Oskar. I think this was just plain wrong...
                uriEvt = new GeneratedURIEvent("Insert URI",
                                               clientKey.getURI());
                pendingEvt = new PendingEvent(millis);
            }
            unlockedProduceEvent(uriEvt);
            unlockedProduceEvent(pendingEvt);
        }
        
        public void storeData(Node n, NodeReference nr, FieldSet fs, long rate, int hopsSinceReset, MessageSendCallback cb)
                                        throws SendFailedException {
            ClientEvent evt = null;
            synchronized (InternalPutToken.this) {
                if (!keyCollision) {
                    checkAlive();
                    evt = state(Request.DONE);
                }
            }
            unlockedProduceEvent(evt);
        }
        
        public synchronized TrailerWriter dataFound(Node n, Storables storables,
						    long ctLength) throws SendFailedException {
	    if(Core.logger.shouldLog(Logger.DEBUG, this))
		Core.logger.log(this, "dataFound() on "+this, Logger.DEBUG);
            ClientEvent uriEvt = null;
            ClientEvent collisionEvt = null;
            ClientEvent terminalEvt = null;
            synchronized (InternalPutToken.this) {
                checkAlive();
                uriEvt = new GeneratedURIEvent("Insert URI",
                                               clientKey.getURI());
                if ("CHK".equals(clientKey.keyType())) {
                    // CHK collision counts as success.
		    keyCollision = true;
                    terminalEvt = state(Request.DONE);
                }
                else {
                    keyCollision = true; 
                    collisionEvt = new CollisionEvent(clientKey);
                    terminalEvt = state(Request.FAILED);
                }
            }

            unlockedProduceEvent(uriEvt);
            unlockedProduceEvent(collisionEvt);
            unlockedProduceEvent(terminalEvt);
            return null;
        }

    }
    

    //=== states ===============================================================
    
    private abstract class InternalClientState extends State {
        
        final InternalFeedbackToken ft;
        
        public InternalClientState(long id, InternalFeedbackToken ft) {
            super(id);
            this.ft = ft;
        }
        
        public final void lost(Node n) {
            ft.fail(new RuntimeException("Node states overflowed."));
        }
    }

    private class NewInternalGet extends InternalClientState {

        private InternalGetToken ft;

        public NewInternalGet(long id, InternalGetToken ft) throws KeyException {
            super(id, ft);
            this.ft = ft;
            ft.clientKey = AbstractClientKey.createFromRequestURI(ft.req.uri);
            if (ft.clientKey.getKey() == null)
                throw new KeyException("got null Key");

			Key k = ft.clientKey.getKey();
//            if (ft.req.getNonLocal()) {
//                // For now we implement non-local requests by
//                // deleting the key from the datastore, just like
//                // in FCP.  Underwhelming :-( -gj
//                if (node.ds.contains(k)) {
//                    node.ds.remove(k, false);
//                }
//            }
//            
            Core.diagnostics.occurrenceContinuous("startedRequestHTL",
            		ft.req.htl);
            
            Node.recentKeys.add(k);
            
            ClientEvent evt = ft.state(Request.PREPARED);
            ft.unlockedProduceEvent(evt);
        }
    
        public final String getName() {
            return "New Internal Get";
        }
    
        public State received(Node n, MessageObject mo) throws StateException {
            if (!(mo instanceof ClientMessageVector)) {
                throw new BadStateException("Not a ClientMessageVector: "+mo);
            }
	    
	    if(Core.logger.shouldLog(Logger.DEBUG,this))
		Core.logger.log(this, "Received: "+mo+" for "+this, 
				Logger.DEBUG);
	    
            Core.diagnostics.occurrenceCounting("inboundClientRequests", 1);

            ClientEvent evt = null;
            State s = null;
            Pending p;
            RequestInitiator ri;
            synchronized (ft) {
                if (!ft.isAlive()) return null;
                evt = ft.state(Request.REQUESTING);
            }
            /** REDFLAG: 
             * Locking here is uncertain; however if we run the .received in
             * the ft lock, we get a deadlock.
             */
            ri = 
                    new RequestInitiator(id, System.currentTimeMillis());
            p = new DataPending(id, ft.req.htl, ft.clientKey.getKey(),
                    null, ft, ri, ft.req.getNonLocal(), false);
                s = p.received(n, ri);
            n.logRequest(ft.clientKey.getKey());
            ft.unlockedProduceEvent(evt);
            return s; // REDFLAG: return was synchronized, ok?
        }
    }

    private class NewInternalPut extends InternalClientState {

        private InternalPutToken ft;
        private DataInsert dim;
        
        public NewInternalPut(long id, InternalPutToken ft) throws KeyException,
                                                                    IOException {
            super(id, ft);
            this.ft = ft;
            
            // set up ClientKey
            ft.clientKey = AbstractClientKey.createFromInsertURI(Core.getRandSource(), ft.req.uri);
            try {
                ft.clientKey.setCipher(ft.req.cipherName);
            }
            catch (UnsupportedCipherException e) {
                throw new KeyException(""+e);
            }

            long total = ft.clientKey.getTotalLength(ft.req.meta.size() + ft.req.data.size());
            
            // set up encoded key data
            Bucket ctBucket = node.bf.makeBucket(total);
            InputStream ctStream = null;
            try {
                ctStream = ft.clientKey.encode(ft.req.meta, ft.req.data, ctBucket);
                ctStream = new FreeBucketInputStream(ctStream, node.bf, ctBucket);
                ctBucket = null;
            }
            finally {
                if (ctBucket != null)
                    node.bf.freeBucket(ctBucket);
            }
            EventInputStream ein = new EventInputStream(ctStream, ft.req,
                                                        total >> 4, ft.req.meta.size(),
							ft.req.data.size(), total, true);
            // prepare the insert
            ft.req.clientKey = ft.clientKey;

            Storables storables = ft.clientKey.getStorables();
            FieldSet root = new FieldSet();
            storables.addTo(root);

                dim = new DataInsert(id, root, ein, 
                                 ft.clientKey.getTotalLength());
            ClientEvent evt = ft.state(Request.PREPARED);
            ft.unlockedProduceEvent(evt);
        }
        
        public final String getName() {
            return "New Internal Put";
        }
        
        public State received(Node n, MessageObject mo) throws StateException {
            if (!(mo instanceof ClientMessageVector)) {
                throw new BadStateException("Not a ClientMessageVector: "+mo);
            }

            Node.diagnostics.occurrenceCounting("inboundClientRequests", 1);

            // walk through client event semantics while initiating
            // the InsertPending state and sending the DataInsert
            ClientEvent evt = null;
            State s = null;
            synchronized (ft) {
                // REDFLAG: do any calls within this scope
                //          cause events to be dispatched?????
                //          Back to this.
                if (!ft.isAlive()) return null;
                evt = ft.state(Request.REQUESTING);
            }

            /** REDFLAG: 
             * Locking here is uncertain; however if we run the .received in
             * the ft lock, we get a deadlock.
             */

                RequestInitiator ri = 
                    new RequestInitiator(id, System.currentTimeMillis());

                Pending p = new InsertPending(id, ft.req.htl, ft.clientKey.getKey(),
                                              null, ft, ri, false, ft.req.getNonLocal());
                // We probably don't know the key.
                n.logRequest(null);
                s = p.received(n, ri);

                try {
                    s = s.received(n, dim);
            } catch (BadStateException e) {
                    // key collision, or something
                    dim.drop(n);
                }
            // REDFLAG: out of order events???
            ft.unlockedProduceEvent(evt);
            return s; // REDFLAG: return was synchronized, ok?
        }
    }
}

