package freenet.node.states.request;

import freenet.CommunicationException;
import freenet.Core;
import freenet.MessageSendCallback;
import freenet.TrailerWriter;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Logger;

public class RequestSendCallback implements MessageSendCallback {
    String m;
    Node n;
    State parent;
    public RequestSendCallback(String m, Node n, State parent) {
	this.m = m;
	this.n = n;
	this.parent = parent;
    }
    
    public void setTrailerWriter(TrailerWriter tw) { }
    
    public void succeeded() {
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	    Core.logger.log(this, toString() + "succeeded sending "+m+
			 " for "+parent, Logger.DEBUG);
    }
    
    public void thrown(Exception e) {
	if(e instanceof CommunicationException) {
	    CommunicationException ce = (CommunicationException)e;
	    Core.logger.log(this,
			 "Failed to send back "+m+" to peer " +ce.peer+
			 " for "+parent, e, Logger.MINOR);
	} else {
	    Core.logger.log(this, "Unexpected exception sending "+m+
			 " for "+parent+": "+e, e, Logger.NORMAL);
	}
    }
}
