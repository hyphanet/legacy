/*
 * Created on Aug 5, 2004
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.message;

import java.io.IOException;
import java.io.InputStream;

import freenet.BaseConnectionHandler;
import freenet.Core;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.RawMessage;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.io.DiscontinueInputStream;

/**
 * @author amphibian
 * 
 * A NodeMessage that may have a trailer, depending on hasTrailer().
 */
public abstract class PossTrailerNodeMessage extends NodeMessage 
	implements TrailingFieldMessage {

    DiscontinueInputStream in;
    
    private boolean closed = false;

    private boolean logDEBUG;
    
    /**
     * @param id
     */
    public PossTrailerNodeMessage(long id) {
        super(id);
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
    }

    /**
     * @param source
     * @param raw
     * @throws InvalidMessageException
     */
    public PossTrailerNodeMessage(BaseConnectionHandler source, RawMessage raw)
            throws InvalidMessageException {
        super(source, raw);
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
    }

    /**
     * @param idnum
     * @param otherfields
     */
    public PossTrailerNodeMessage(long idnum, FieldSet otherfields) {
        super(idnum, otherfields);
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
    }

    public PossTrailerNodeMessage(long idnum, FieldSet otherfields,
            DiscontinueInputStream data) {
        super(idnum, otherfields);
        this.in = data;
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
    }
    
    /**
     * This will drop the trailing field (and with it the connection it was
     * to be received on) if it has not already been read.
     */
    public void drop(Node n) {
	if(logDEBUG)
	    Core.logger.log(this, "Dropping "+this+" (in="+in+"), length "+trailerLength(),
			    Logger.DEBUG);
        if (in != null) {
            closeIn();
        }
    }

    public InputStream getDataStream() {
        return in;
    }

    
    public void closeIn() {
        if(closed) return;
        if(in == null) return;
    	try {
    	    closed = true;
			in.close();
		} catch (IOException e) {
			Core.logger.log(this, "Could not close: "+in+" on "+this, e,
					Logger.NORMAL);
		}
    }

}
