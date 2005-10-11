package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.Core;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.Message;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.Version;
import freenet.crypt.EntropySource;
import freenet.support.Fields;
import freenet.support.Logger;

/**
 * Superclass for messages that are sent between nodes (basically those
 * that have a UniqueID field).
 *
 * @author oskar
 */
public abstract class NodeMessage extends Message {

    private static EntropySource uniqueIdEntropy = new EntropySource();

    void logHeisenbug(String s, long id) {
	String details = "";
	try {
	    details = ", OS: "+System.getProperty("os.name")+
		", JVM Vendor: "+System.getProperty("java.vm.vendor")+
		", JVM Name: "+System.getProperty("java.vm.name")+
		", JVM Version: "+System.getProperty("java.vm.version")+
		", Build: "+Version.buildNumber;
	} catch (Throwable t) {details = "";} 
	// Try to print error even if OS details throw
	String o = "YOUR NODE CAUSED THE HEISENBUG - "+
	    "PLEASE REPORT TO FREENET DEV MAILING "+
	    " LIST! (TYPE "+s+": "+Long.toHexString(id)+
	    details+")";
	Exception e = new Exception("heisenbug debug");
	Core.logger.log(this, o, e, Logger.ERROR);
	System.err.println(o);
	e.printStackTrace(System.err);
    }

    /**
     * Creates a new message with the Node's requestInterval
     * @param id The message's Unique ID, should be a random long.
     */
    protected NodeMessage(long id) {
        super(id);
        if (id == 0) {
            Core.logger.log(this, "Creating NodeMessage with 0 ID", new Exception("debug"), Logger.DEBUG);
        } else if (id == -1) {
            Core.logger.log(this, "Creating NodeMessage with -1 ID", new Exception("debug"), Logger.DEBUG);
        } else if (((id >>> 32) & 0xffffffffL) == (id & 0xffffffffL)) {
            logHeisenbug("A", id);
        }
    }
    
    /**
     * Creates a new message.
     * @param  id        The message's Unique ID, should be a random long.
     * @param  otherFields  The remaining set of fields which are not directly
     *                      related to routing the message.
     */
    protected NodeMessage(long id, FieldSet otherFields) {
		super(id, otherFields);
		//Hack to eliminate relaying of old-style RI to other nodes. TODO: Remove when
		//no oldstyle RI-sending nodes are still out in the wild
		if(this.otherFields != null)  
			this.otherFields.remove("RequestInterval");
		if (id == 0) {
			Core.logger.log(this, "Creating NodeMessage with 0 ID", Logger.DEBUG);
		} else if (id == -1) {
			Core.logger.log(this, "Creating NodeMessage with -1 ID", Logger.DEBUG);
		} else if (((id >>> 32) & 0xffffffffL) == (id & 0xffffffffL)) {
			logHeisenbug("B", id);
		}
	}
    
    /**
     * Creates a message from a RawMessage, as read from a presentation
     * @param source The connectionhandler that @param raw was recieved from
     * @param raw The RawMessage. Please note that the RawMessage is 
     *            consumed after this. Both the normal and trailing
     *            fields are corrupted. Use toRawMessage() to restore it.
     **/   
    protected NodeMessage(BaseConnectionHandler source, RawMessage raw) throws InvalidMessageException {
		super(source, 0, raw);
		id = readId(raw.fs);
		if(this.otherFields != null)
		    this.otherFields.remove("RequestInterval");
		// readId should throw if the id is invalid
		// if id is STILL invalid, the id HAS CHANGED and there is a bug in the JVM
		if (((id >>> 32) & 0xffffffffL) == (id & 0xffffffffL)) {
			logHeisenbug("C", id);
			throw new InvalidMessageException("Created heisenbug UniqueID: " + Long.toHexString(id) + ". killed chain");
		}
	}
    
    private static long readId(FieldSet fs) throws InvalidMessageException {
        try {
            String ids = fs.getString("UniqueID");
            if (ids == null)
                throw new InvalidMessageException("no UniqueID field");
            long id = Fields.hexToLong(ids);
            if (((id>>>32) & 0xffffffffL) == (id & 0xffffffffL)) {
                throw new InvalidMessageException("Read heisenbug UniqueID: "
                                + Long.toHexString(id) + ". killed chain");
            }
            Core.getRandSource().acceptEntropy(uniqueIdEntropy, id, 64);
            fs.remove("UniqueID");
            return id;
        } catch (NumberFormatException e) {
            throw new InvalidMessageException("Broken id number: " 
                                              + e.getMessage());
        }
    }
    
    /** Converts this message to something that can be sent over the wire,
      * using the given Presentation.
      */
    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
        boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        RawMessage r = super.toRawMessage(t,ph);
        r.fs.put("UniqueID", Long.toHexString(id));
        
        // do heisenbug testing
        if(((id >>> 32) & 0xffffffffL) == (id & 0xffffffffL)) {
            String s = "PANIC! Just recreated heisenbug! ID: "
                                        + Long.toHexString(id);
            System.err.println(s);
            Exception e = new Exception();
            e.printStackTrace();
            Core.logger.log(NodeMessage.class, s, e, Logger.ERROR);
        }
        if(logDEBUG)
            Core.logger.log(this, "Returning "+this, Logger.DEBUG);
        return r;
    }
    
    /** Part of the MessageObject implementation. Obsoleted by getID below
    * TODO: rename id() getID() in the MessageObject definition
    * @return the unique ID of this message
    */
    public long id() {
        return getID();
    }
	/** Part of the MessageObject implementation.
    * @return the unique ID of this message
    */
	public long getID(){
		return id;
	}

	public void onSent(PeerHandler target) {
	}
	
    /**
     * Send this message to the given Peer.  "Back" is, in truth, optional..
     */
    //public OutputStream sendBack(Node n, Peer p) throws SendFailedException {
    //    try {
    //        return n.makeConnection(p).sendMessage(this);
    //    }
    //    catch (ConnectFailedException e) {
    //        throw new SendFailedException(e);
    //    }
    //}
}