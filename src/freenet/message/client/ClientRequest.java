package freenet.message.client;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.client.FreenetURI;
import freenet.node.Node;

/** Superclass of FCP ClientGet and ClientPut messages.
  * @author tavin
  */
public abstract class ClientRequest extends ClientMessage {

    protected FreenetURI uri;
    protected final int hopsToLive;
    protected boolean RemoveLocal;

    public ClientRequest(BaseConnectionHandler source, RawMessage raw) {
        this(source, raw, false);
    }
    
    public ClientRequest(BaseConnectionHandler source, RawMessage raw, boolean getData) {
        super(source, raw, getData);
        int htl = -1;
        RemoveLocal = false;
        try {
            uri = new FreenetURI(otherFields.getString("URI"));
            otherFields.remove("URI");
            htl = Integer.parseInt(otherFields.getString("HopsToLive"), 16);
	    htl = Node.perturbHTL(htl);
            otherFields.remove("HopsToLive");
            if (otherFields.containsKey("Flags")) {
		int flags=Integer.parseInt(otherFields.getString("Flags"), 16);
		// *** DO NOT USE THIS! PERIOD! Use RemoveLocalKey=true
		// instead.
	        if ((flags&1)>0)
                    RemoveLocal = true;
                else
                    RemoveLocal = false;
            }
	    
            if (otherFields.containsKey("RemoveLocalKey")) {
		String val = otherFields.getString("RemoveLocalKey");
		if(val.equalsIgnoreCase("yes") ||
		   val.equalsIgnoreCase("true"))
		    RemoveLocal = true;
	    }
	}
        catch (Exception e) {
            formatError = true;
        }
        
        if (htl < 0) formatError = true;
        else if (htl > Node.maxHopsToLive) htl = Node.maxHopsToLive;

        hopsToLive = htl;
    }
    
    public FreenetURI getURI() {
        return uri;
    }

    public int getHTL() {
        return hopsToLive;
    }
    
    public boolean getRemoveLocal() {
    	return RemoveLocal;
    }
}


