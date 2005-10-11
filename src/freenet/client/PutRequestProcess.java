package freenet.client;
import freenet.client.listeners.DoneListener;
import freenet.client.events.GeneratedURIEvent;
import freenet.client.events.CollisionEvent;
import freenet.support.Bucket;
import freenet.support.ArrayBucket;
import freenet.support.BucketFactory;
import freenet.support.NullBucket;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.Redirect;
import freenet.client.metadata.InvalidPartException;
import freenet.Core; // for logging
import freenet.support.Logger;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;
/*
 * This is just an example. Obviously the advanced redirect features
 * need to be added.
 */
public class PutRequestProcess extends ControlRequestProcess {

    private String cipherName;
    private boolean skipDS;
    private boolean collided = false;

    public PutRequestProcess(FreenetURI uri, int htl, String cipherName, 
                             Metadata metadata,  MetadataSettings ms,
                             Bucket data, BucketFactory ptBuckets,
                             int recursionLevel, boolean descend) {
	this(uri, htl, cipherName, metadata, ms, data, ptBuckets, recursionLevel, descend, false);
    }
    
    public PutRequestProcess(FreenetURI uri, int htl, String cipherName,
			     Metadata metadata,  MetadataSettings ms,
			     Bucket data, BucketFactory ptBuckets,
			     int recursionLevel, boolean descend, boolean skipDS) {
        super(uri, htl, data, ptBuckets, recursionLevel, descend,
              ms);
	if(ms == null) throw new NullPointerException("ms NULL!");
        this.metadata = metadata;
//         System.err.println("Creating PutRequestProcess (a): " +
// 			   this.metadata+" ("+this+"): "+descend);
        this.cipherName = cipherName;
        nextLevel = true; // puts start at the next level.
        this.skipDS = skipDS;
    }

    public synchronized Request getNextRequest() {
        if (aborted || failed)
            return null;
        if (nextLevel) {
            if (next == null && metadata != null && follow) {
// 		System.err.println("Getting document, uri. uri="+uri+
// 				   ", metadata="+metadata);
                DocumentCommand d = 
                    (uri.getMetaString() == null ? 
                     null : 
                     metadata.getDocument(uri.getMetaString()));
                FreenetURI nuri;
                if (d != null) {
                    nuri = uri.popMetaString();
                } else {
                    d = metadata.getDefaultDocument();
                    nuri = uri;
                }
                if (d != null)
                    next = d.getPutProcess(nuri, htl, cipherName, data, 
                                           ptBuckets, recursionLevel, 
                                           follow);
// 		System.err.println("next = "+next+", d = "+d+", nuri = "+nuri);
            }
//             System.err.println(recursionLevel +  " LALA " + next);

            Request nr = null;
            if (next != null) {
                nr = next.getNextRequest();
                if (next.failed()) {
                    failed = true;
		    error = getNextFailedErrorString(next.getThrowable(),
						     next.getError());
		    origThrowable = next.getThrowable();
		    Core.logger.log(this, "Request failed: "+error, origThrowable,
				    Logger.MINOR);
                    return null;
                }
            }
	    
            if (next == null || (nr == null && follow)) {

                Bucket mdBucket = new ArrayBucket();
//                 System.err.println("I'm spawning myself: " + recursionLevel);
                try {
                    // We get here after the leaf RequestProcess has finished
                    // and follow is false.  metadata is *being modified/updated*
                    // as requests run.

                    // HACK: I need to get the checksum that was computed
                    //       by the SplitFileInsertProcess into the InfoPart.
                    //
                    // REDFLAG: unintended consequences??? 
                    if (msettings.getChecksum() != null) {
                        metadata.updateChecksum(msettings.getChecksum());
                    }

                    OutputStream md = mdBucket.getOutputStream();
                    if (metadata != null) 
                        metadata.writeTo(md);
                    md.close();
                    
                    try {
                        r = new PutRequest(htl, uri, // .setMetaString(null), 
                                           cipherName, mdBucket, 
                                           next == null ? data : new NullBucket(),
					   skipDS);
                    } catch (InsertSizeException ise) {
                        System.err.println("next:" + next);
                        
                        if ((next != null) && (next instanceof SplitFileInsertProcess)) {
                            // Handle large SplitFiles by inserting the metadata under 
                            // a CHK and creating a redirect to it.
                            DocumentCommand redirect = new DocumentCommand(metadata);
                            try {
                                redirect.addPart(new Redirect(new FreenetURI("CHK@")));

                            }
                            catch (InvalidPartException ipe) {
                                // Don't think this can happen.
                                // Give up and rethrow the original exception.
                                throw ise;
                            }
                            
                            metadata = new Metadata(new MetadataSettings());
                            metadata.addCommand(redirect);
                            next = redirect.getPutProcess(uri, htl, cipherName, new NullBucket(), 
                                                          ptBuckets, 0, 
                                                          false);
                            if (next.failed()) {
                                failed = true;
				error = getNextFailedErrorString(next.getThrowable(),
								 next.getError());
                                origThrowable = next.getThrowable();
                                return null;
                            }
                            nr = next.getNextRequest();
                            if (nr == null) {
                                // Don't think this can happen.
                                error = "Creating redirect to large metadata failed.";
                                System.err.println("BUG: REDFLAG:Creating redirect to large metadata failed.");
                            }
                            return nr;
                        }
                        else {
                            throw ise;
                        }
                    }
                } catch (IOException e) {
                    throw new Error("IOException when reading to memory: " +
                                    e);
                } catch (InsertSizeException e) {
                    failed = true;
		    error = "Tried to insert a key with invalid length: "+
			e.toString();
		    origThrowable = e;
                    return null;
                }
                nextLevel = false;
                //                next = null; // it's over
                //System.err.println("I've spawned myself: " + recursionLevel);
                dl = new DoneListener();
                r.addEventListener(dl);
                r.addEventListener(new NewURIListener());
                return r;
            } else {
                //System.err.println("GOT NULL: " + recursionLevel); 
                return nr;
            }
        } else if (dl == null) {
            return null;
        } else {
            //System.err.println("WAITING:  " + recursionLevel);
	    if(Core.logger.shouldLog(Logger.DEBUG,this)) {
		Core.logger.log(this, "Waiting for "+this, Logger.DEBUG);
	    }
	    bufferEvents = true;
	    if(v == null) v = new Vector();
	    if(v != null) v.clear();
            dl.strongWait();
            if (r.state() != Request.DONE) {
		if(collided) {
		    error = "Key collision: the key "+uri+
			" is already present on Freenet";
		    bufferEvents = false;
		    v.clear();
		} else {
		    error = null;
		}
		origThrowable = new 
		    WrongStateException("after waiting for next process"+
					(error == null ? "" : 
					 (": "+error)), 
					Request.DONE, r.state());
		if(error == null) error = origThrowable.toString();
                failed = true;
		if(bufferEvents) {
		    bufferEvents = false;
		    for(int x=0;x<v.size();x++) {
			try {
			    ClientEvent e = (ClientEvent)v.elementAt(x);
			    Core.logger.log(this, "Event Received Before Failure: "+
					    e.getDescription(), Logger.DEBUG);
			} catch (Throwable t) {
				Core.logger.log(this, "Caught "+t, t, Logger.ERROR);
			}
		    }
		    v.clear();
		}
	    }
	    //System.err.println("WAITED: " + recursionLevel);
            //   if (recursionLevel == 1)
            //    throw new RuntimeException("tag");
            return null;
        }
    }
    
    protected boolean bufferEvents = false;
    protected Vector v = null;
    
    // FIXME: sanitize this, see GetRequestProcess, and coalesce into ControlRP
    
    private class NewURIListener implements ClientEventListener {

        public void receive(ClientEvent ce) {
	    if(bufferEvents) {
		if(v.size() > 256) v.removeElementAt(0); // FIXME: slow
		v.add(ce);
	    }
            if (ce instanceof GeneratedURIEvent) {
		
                uri = ((GeneratedURIEvent) ce).getURI(); 
                // System.err.println(recursionLevel + " LALA Setting URI " +
                //                   uri);
            }
	    if (ce instanceof CollisionEvent) {
		Core.logger.log(this, "CollisionEvent "+ce+" received by "+
				PutRequestProcess.this, Logger.MINOR);
		collided = true;
		bufferEvents = false;
	    }
        }
	
    }
    
}






