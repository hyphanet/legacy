package freenet.interfaces;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

import freenet.BaseConnectionHandler;
import freenet.CommunicationException;
import freenet.Connection;
import freenet.Core;
import freenet.NegotiationFailedException;
import freenet.OpenConnectionManager;
import freenet.Presentation;
import freenet.PresentationHandler;
import freenet.SessionHandler;
import freenet.node.Node;
import freenet.session.Link;
import freenet.session.LinkManager;
import freenet.support.Logger;
import freenet.support.io.NIOInputStream;
import freenet.transport.tcpConnection;

/**
 * Runs connections using standard Freenet
 * session and presentation negotiation.
 */
public final class FreenetConnectionRunner implements ConnectionRunner {
    
    protected final Node node;
    protected SessionHandler sh;
    protected PresentationHandler ph;
    protected OpenConnectionManager ocm;
    protected int maxInvalid;
    protected boolean limitIncomingConnections;


    /**
     * @param node        the Node containing the SessionHandler,
     *                    PresentationHandler, and the node's key pair
     * @param ocm         passed to the ConnectionHandler constructor
     * @param maxInvalid  passed to the ConnectionHandler constructor
     */
    public FreenetConnectionRunner(Node node,
                                   SessionHandler sh, PresentationHandler ph,
                                   OpenConnectionManager ocm, int maxInvalid,
                                   boolean limitIncomingConnections) {
        this.node = node;
        this.sh = sh;
        this.ph = ph;
        this.ocm = ocm;
        this.maxInvalid = maxInvalid;
        this.limitIncomingConnections = limitIncomingConnections;
    }
    
    public void handle(Connection conn) {
    	BaseConnectionHandler ch = null;
        boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this); 

        if(limitIncomingConnections &&
                !(node.wantIncomingConnection())) {
        	// FIXME: can we move this back even further so we don't have
        	// to allocate a tcpConnection (with its buffers)?
            Core.logger.log(this, "Dumped incoming connection because we don't want it",
                    Logger.MINOR);
            Core.diagnostics.occurrenceBinomial("incomingConnectionsAccepted", 1, 0);
            conn.close();
            return;
        }

		Core.diagnostics.occurrenceBinomial("incomingConnectionsAccepted", 1, 1);
        boolean success = false;
        long startMs = System.currentTimeMillis();

        try {
            Link l;
            Presentation p;
            
            conn.setSoTimeout(Core.authTimeout);
            InputStream raw = conn.getIn();
            int i = raw.read() << 8 | raw.read();
            if (i < 0)
                throw new EOFException();
            LinkManager lm = sh.get(i);
            if (lm == null) {
                throw new NegotiationFailedException(conn.getPeerAddress(),
                                                     "Unsupported link 0x"
                                                     + Integer.toHexString(i));
            }
            
            l = lm.acceptIncoming(node.privateKey, 
                                  node.identity, 
                                  conn);
            l.getOutputStream().flush(); // Or it won't get written at all
	    
	    // FIXME: this should go with the first packet somehow
            InputStream crypt = l.getInputStream();
            if(crypt == null) throw new IOException("null input stream");
            i = crypt.read() << 8 | crypt.read();
            if (i < 0)
                throw new EOFException();
            p = ph.get(i);
            if(logDEBUG)
                Core.logger.log(this, "Got: "+p+" for "+i+" from "+ph,
                        Logger.DEBUG);
            if (p == null) {
                throw new NegotiationFailedException(conn.getPeerAddress(),
                                                     l.getPeerIdentity(),
                                                     "Unsupported presentation 0x"
                                                     + Integer.toHexString(i));
            }
            conn.enableThrottle();
            ch = p.createConnectionHandler(ocm, node, l, node.ticker(), maxInvalid, Core.maxPadding, false,tcpConnection.getRSL(),tcpConnection.getWSL());
            try {
		if(logDEBUG) Core.logger.log(this,"starting transfer from NIOIS to CH",Logger.DEBUG);
		Socket sock = null;
		try {
		    sock = ((tcpConnection)conn).getSocket();
		} catch (IOException e) {
		    return;
		}
		NIOInputStream niois = ((tcpConnection)conn).getUnderlyingIn();
		niois.switchReader(ch);
		if(niois.alreadyClosedLink()) {
		    Core.logger.log(this, "Already closed link, not registering: "+
				    this+", terminating "+ch, Logger.MINOR);
		    conn.close();
		    ch.terminate();
		    return;
		}
		// is now unregistered, it will not be checked until we are
		// registered
		if(logDEBUG) Core.logger.log(this, "NIOInputStream "+niois+" unregistered for "+this+":"+
				ch, Logger.DEBUG);
		/*try {
		  sc.configureBlocking(false);
		  } catch (IOException e) {
		  Core.logger.log(this, "Cannot configure nonblocking mode on SocketChannel!", Logger.ERROR);
		  }*/
		if(sock != null) {
		    // Some done by niois.unregister
		    if(!ch.isOpen())
			throw new IOException("Conn closed before registering with OCM: "+ch);
		    ch.registerOCM();
// 		    tcpConnection.getRSL().register(sock, ch);
// 		    tcpConnection.getRSL().scheduleMaintenance(ch);
		} // else already closed
		//ch.run(); // we already have our own thread
		success = true;
		if(logDEBUG)
			Core.logger.log(this, "Apparent success: "+ch,
					Logger.DEBUG);
	    } finally {
		if(!success) ch.terminate();
	    }
	} catch (IOException e) {
	    Core.logger.log(this, "Inbound connection failed: " + e + " on " + conn, e, Logger.MINOR);
	    conn.close();
	    if(ch != null) ch.terminate();
	} catch (CommunicationException e) {
	    Core.logger.log(this, "Inbound connection failed: " + e + " on " + conn, e, Logger.MINOR);
	    conn.close();
	    if(ch != null) ch.terminate();
	} finally {
            Core.diagnostics.occurrenceBinomial("inboundConnectionRatio",1,success ? 1 : 0);
            if (success) {
                long connectingTime = System.currentTimeMillis() - startMs;
                Core.diagnostics.occurrenceContinuous("inboundConnectingTime", 
                                                      connectingTime);

            }
        }

	if(logDEBUG) Core.logger.log(this, "Leaving handle("+conn+")", Logger.DEBUG);
    }
    
    public void starting() {
        // Do nothing
    }
    
    public boolean needsThread() { return true; }
}






