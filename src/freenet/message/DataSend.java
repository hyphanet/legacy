package freenet.message;

import java.io.IOException;

import freenet.BaseConnectionHandler;
import freenet.Core;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.Key;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.Storables;
import freenet.node.Node;
import freenet.node.ds.KeyCollisionException;
import freenet.node.ds.KeyOutputStream;
import freenet.node.ds.StoreIOException;
import freenet.node.states.data.EatData;
import freenet.node.states.data.ReceiveData;
import freenet.support.Logger;
import freenet.support.io.DataNotValidIOException;
import freenet.support.io.DiscontinueInputStream;
import freenet.support.io.VerifyingInputStream;

/**
 * This is the DataSend message
 *
 * @see freenet.node.Node
 * @see freenet.Address
 * @author Brandon Wiley (blanu@uts.cc.utexas.edu)
 * @author Ian Clarke (I.Clarke@strs.co.uk)
 * @author oskar (fingered everything)
 */

public abstract class DataSend extends PossTrailerNodeMessage {

    /** The length of the trailing field */
    private long length;
    
    private boolean logDEBUG;
    
    public DataSend(long idnum, FieldSet otherfields,
                    DiscontinueInputStream data, long length) {
        super(idnum, otherfields, data);
        this.length = length;
	logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	if(logDEBUG)
	    Core.logger.log(this, "DataSend constructed", new Exception("debug"),
			    Logger.DEBUG);
    }

    public DataSend(BaseConnectionHandler source, RawMessage raw)
        throws InvalidMessageException {

        super(source, raw);

        if (raw.trailingFieldLength != 0) {
            length = raw.trailingFieldLength;
            in = raw.trailingFieldStream;
        } else 
            throw new InvalidMessageException("Data sending message requires "
                                              + "the trailing field length to "
                                              + "be specified");
	logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
    }
    
    public boolean hasTrailer() {
	return length != 0;
    }
    
    public long trailerLength() {
	return length;
    }
    
    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
        RawMessage raw=super.toRawMessage(t,ph);
        
        // this was screwing the sending of DataReply because
        // the trailing field length wasn't getting filled in
        //if (in != null) {
            //raw.trailingFieldStream = in;
            raw.trailingFieldLength = length;
            raw.trailingFieldName="Data";
        //}

        return raw;
    }

    public Storables getStorables() {
        return otherFields == null ? null : Storables.readFrom(otherFields);
    }

    public long length() {
        return length;
    }

    public void setTrailerLength(long len) {
        this.length = len;
    }

    /**
     * Sets up the caching of the data in the this message to the nodes
     * datastore.
     *
     * @param  n            The node to cache into.
     * @param  searchKey    The key to cache as.
     * @param  ignoreDS      If true, ignore the datastore rather than throwing KeyCollisionException
     * @return a DataState that will read the data into the store
     * @exception StoreIOException If writing to the datastore fails.
     * @exception IOException  If creating the stream to CACHE the data fails.
     * @exception DataNotValidIOException  If the data does not validate
     *                                     for the provided key.
     */
    public ReceiveData cacheData(Node n, Key searchKey, boolean ignoreDS)
	throws IOException, DataNotValidIOException,
	       KeyCollisionException {
        Storables storables = getStorables();
        if (storables == null) {
            throw new DataNotValidIOException(Presentation.CB_BAD_KEY);
        }
        VerifyingInputStream vis = searchKey.verifyStream(in, storables, length);
	if(logDEBUG)
	    Core.logger.log(this, "Trying to cache data: "+searchKey+":"+length+
			    ":"+in, Logger.DEBUG);
	KeyOutputStream out;
	try {
	    out = n.ds.putData(searchKey, length, storables, ignoreDS);
	} catch (IOException e) {
	    if(e instanceof StoreIOException) throw e;
	    else throw new StoreIOException(e);
	}
        return new ReceiveData(Core.getRandSource().nextLong(), this.id, vis, out, length);
    }
    
    /** Used to swallow a DataInsert.
      */
    public void eatData(Node n) {
        try {
            Storables storables = getStorables();
            if (storables == null) throw new IOException();
            VerifyingInputStream vin = new VerifyingInputStream(in, length);
            (new EatData(Core.getRandSource().nextLong(), vin, length)).schedule(n);
            // FIXME -- should use ControlInputStream but i haven't made
            //          sure it will work yet
        }
        catch (IOException e) {
            closeIn();
        }
    }
}
