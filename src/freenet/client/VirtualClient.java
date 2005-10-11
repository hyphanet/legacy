package freenet.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Random;

import freenet.Core;
import freenet.client.events.ExceptionEvent;
import freenet.client.events.GeneratedKeyPairEvent;
import freenet.client.events.GeneratedURIEvent;
import freenet.client.events.StateReachedEvent;
import freenet.client.listeners.CollectingEventListener;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.crypt.SHA1;
import freenet.crypt.Util;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.SegmentHeader;
import freenet.node.Node;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.HexUtil;
import freenet.support.Logger;

/** VirtualClient knows how to deal with client Requests that don't need
  * to go over the wire, such as ComputeCHKRequest and ComputeSVKRequest.
  */
final class VirtualClient implements ClientFactory {

    protected Node node;
    protected Random randSource;
    protected BucketFactory bucketFactory;
    
    VirtualClient(Node n, Random r, BucketFactory bf) {
        node = n;
        randSource = r;
        bucketFactory = bf;
    }

    public final Client getClient(Request req) throws UnsupportedRequestException {
        return new VirtualClientInstance(req);
    }

    public boolean supportsRequest(Class req) {
        try {
            VirtualClientInstance.class.getMethod("execute", new Class[] {req});
        }
        catch (Exception e) {
            return false;
        }
        return true;
    }

    private class VirtualClientInstance implements Client {
    
        Request req;
        CollectingEventListener cel;
        Method exec;

        private VirtualClientInstance(Request req)
                                throws UnsupportedRequestException {
            try {
                exec = this.getClass().getMethod("execute",
                                                 new Class[] {req.getClass()});
            }
            catch (Exception e) {
                e.printStackTrace();
                throw new UnsupportedRequestException();
            }
            req.addEventListener(cel = new CollectingEventListener());
            this.req = req;
            state(Request.PREPARED);
        }
        
        public final void start() {
            blockingRun();
        }

        public final boolean cancel() {
            return false;
        }

        public int blockingRun() {
            try {
                if (req instanceof SplitFileGetRequest) {
                    // This is a hack.  But I haven't been able to
                    // come up with a better way to make it work.
                    //
                    // IMPORTANT: Create before REQUESTING transition so that
                    //            SplitFileRequestProcess can grab the manager
                    //            reference in the REQUESTING state changed event.
                    ((SplitFileGetRequest)req).manager = 
                        new SplitFileRequestManager((SplitFileGetRequest)req);
                }
                else if (req instanceof SplitFilePutRequest) {
                    // This is a hack.  But I haven't been able to
                    // come up with a better way to make it work.
                    //
                    // IMPORTANT: Create before REQUESTING transition so that
                    //            SplitFileInsertProcess can grab the manager
                    //            reference in the REQUESTING state changed event.
                    ((SplitFilePutRequest)req).manager = 
                        new SplitFileInsertManager((SplitFilePutRequest)req);
                }
                
                state(Request.REQUESTING);
                exec.invoke(this, new Object[] {req});
                state(Request.DONE);
            }
            catch (Exception e) {
                req.produceEvent(new ExceptionEvent(e));
                state(Request.FAILED);
            }
            return req.state();
        }

        public final Enumeration getEvents() {
            return cel.events();
        }
    
        private void state(int state) {
            if (state >= Request.FAILED && state <= Request.DONE) {
                req.state(state);
                req.produceEvent(new StateReachedEvent(state));
            }
            else throw new IllegalArgumentException(
                "State value was not between FAILED and DONE"
            );
        }

        // Tavin, your execute methods have to be public if you
        // want to use Class.getMethod(String, Class[]) above.
        // see http://java.sun.com/j2se/1.3/docs/api/java/lang/Class.html
        //           #getMethod(java.lang.String, java.lang.Class[])
        // --gj
        
        public void execute(ComputeCHKRequest req) throws Exception {
            ClientCHK chk = new ClientCHK();
            chk.setCipher(req.cipherName);
            Bucket ctBucket = bucketFactory.makeBucket(
              chk.getTotalLength(req.meta.size() + req.data.size())
            );
            try {
                chk.encode(req.meta, req.data, ctBucket).close();
            }
            finally {
                bucketFactory.freeBucket(ctBucket);
            }
            req.clientKey = chk;
            req.produceEvent(new GeneratedURIEvent("Generated CHK",
                                                   chk.getURI()));
        }

        public void execute(ComputeSHA1Request req) throws Exception {
            InputStream in = null;
            try {
                in = req.data.getInputStream();
                byte[] hash = Util.hashStream(SHA1.getInstance(), in, 
					      req.data.size());
                req.sha1 = HexUtil.bytesToHex(hash);
            }
            finally {
                if (in != null) {
                    try {in.close();} catch (Exception e) {}
                }
            }
        }
    
        public void execute(ComputeSVKPairRequest req) throws Exception {
            ClientSVK svk = new ClientSVK(randSource);
            req.clientKey = svk;
            req.produceEvent(new GeneratedKeyPairEvent(
                svk.getPrivateKey(),
                svk.getPublicKeyFingerPrint(),
		svk.getCryptoKey()));
	    // FIXME: hmm. Does this do what we want? 
	    // See FCPClient$FCPComputeSVKPair.doit()
	}
	
        ////////////////////////////////////////////////////////////
        // FEC SplitFile Requesting commands
        ////////////////////////////////////////////////////////////
        public void execute(SegmentSplitFileRequest req) throws Exception {

            Bucket metadata = null;
            try {
                metadata = Node.fecTools.getBucketFactory().makeBucket(-1);

                DocumentCommand doc = new DocumentCommand(new MetadataSettings());
                doc.addPart(req.sf);
                Metadata md = new Metadata(new MetadataSettings());
                md.addDocument(doc);
                OutputStream mdStream = metadata.getOutputStream();
                md.writeTo(mdStream);
                mdStream.close();
                if(metadata.size() == 0) throw new IOException
					 ("written zero length metadata");

                // Hmmmm...
                // Why doesn' h&m just contain a header array and block array?
                FECTools.HeaderAndMap[] ret = 
                    Node.fecTools.segmentSplitFile(0, metadata);
                req.headers = new SegmentHeader[ret.length];
                req.maps = new BlockMap[ret.length];
                for (int i = 0; i < ret.length; i++) {
                    req.headers[i] = ret[i].header;
                    req.maps[i] = ret[i].map;
                }
            }
            catch(Exception e){
				Core.logger.log(this, "Encountered exception while performing SegmentSplitFileRequest",e,Logger.ERROR);
				throw e;
            }
            finally {
                if (metadata != null) {
                    Node.fecTools.getBucketFactory().freeBucket(metadata);
                }
            }
        }

        public void execute(DecodeSegmentRequest req) throws Exception {
            // REDFLAG: decide how/where to check arguments

            // REDFLAG: Change spec so clients don't have to do this?
            int[] withOffset = new int[req.checkIndices.length];
            for (int i = 0; i < req.checkIndices.length; i++) {
                withOffset[i] = req.checkIndices[i] + req.header.getBlockCount();
            }
            
            Bucket[] blocks = new Bucket[req.dataIndices.length + withOffset.length];
            System.arraycopy(req.data, 0, blocks, 0, req.data.length);
            System.arraycopy(req.checks, 0, blocks, req.data.length, req.checks.length);
                  
            Bucket[] ret = null;
            try {
                ret = Node.fecTools.decodeSegment(req.header, 
                                                  req.dataIndices, 
                                                  withOffset, 
                                                  req.requestedIndices,
                                                  blocks);
                
                // deep copy  
                for (int j = 0; j < ret.length; j++) {
                    BucketTools.copy(ret[j], req.decoded[j]);
                }
            }
            finally {
                BucketTools.freeBuckets(Node.fecTools.getBucketFactory(), ret);
            }
        }

        public void execute(SplitFileGetRequest req) throws Exception {
            // Blocks until the SplitFileRequest finishes.
            req.manager.execute(req.cf);
        }

        ////////////////////////////////////////////////////////////
        // FEC SplitFile Inserting commands
        ////////////////////////////////////////////////////////////

        // REDFLAG: Completely untested.

        public void execute(SegmentFileRequest req) throws Exception {
            req.headers = Node.fecTools.segmentFile(-1, req.algoName, req.length);
        }

        public void execute(EncodeSegmentRequest req) throws Exception {
            Bucket[] ret = null;
            try {
                ret = Node.fecTools.encodeSegment(req.header, 
                                                  req.checkIndices,
                                                  req.data);
                // deep copy  
                for (int j = 0; j < ret.length; j++) {
                    BucketTools.copy(ret[j], req.checks[j]);
                }
            }
            finally {
                BucketTools.freeBuckets(Node.fecTools.getBucketFactory(), ret);
            }
        }

        public void execute(MakeMetadataRequest req) throws Exception {
            Node.fecTools.makeSplitFile(req.headers, req.maps, 
                                        req.description, req.mimeType, req.checksum,
                                        req.metaData); 
        }

        public void execute(SplitFilePutRequest req) throws Exception {
            // Blocks until the SplitInsert finishes.
            req.manager.execute(req.cf);
        }
    }
    
    public boolean isOverloaded() {
	return node.rejectingRequests(false);
    }
    
    public boolean isReallyOverloaded() {
	return node.rejectingConnections();
    }
    
}







