package freenet.node;

import freenet.CommunicationException;
import freenet.ConnectionJob;
import freenet.Core;
import freenet.Identity;
import freenet.OpenConnectionManager;
import freenet.PeerHandler;
import freenet.node.rt.RoutingTable;
import freenet.support.Checkpointed;
import freenet.support.Logger;

public class ConnectionOpener implements Checkpointed {

	protected final RoutingTable rt; // to check whether still open

	protected final Node node;

	protected final Identity id;

	protected final OpenConnectionManager ocm;

    protected final PeerHandler ph;

    protected final ConnectionOpenerManager com;
    
    protected final int openerIndex;

	protected boolean logDEBUG;

    boolean fired = false; // one-shot..

    public String toString() {
        return super.toString() + ": id=" + id;
	}

    public ConnectionOpener(Identity id, RoutingTable rt,
            OpenConnectionManager ocm, Node node,
            PeerHandler ph, ConnectionOpenerManager com, int idx) {
        this.rt = rt;
		this.node = node;
		this.id = id;
        this.ph = ph;
        this.ocm = ocm;
        this.com = com;
        this.openerIndex = idx;
		logDEBUG = Node.logger.shouldLog(Logger.DEBUG, this);
	}

	public String getCheckpointName() {
		//return "Connection opener @ " + id;
		NodeReference ref = ocm.getNodeReference(id);
		// @ means it gets coalesced on the Env page
        if (ref == null) return "Opening connection @ [Forgotten node]";
		else
			return "Opening connection @ "+ref.firstPhysicalToString();
	}

	public long nextCheckpoint() {
        // One-shot
        if(fired) return -1;
        else return System.currentTimeMillis();
	}

    protected boolean needsOpen() {
        return !ph.isConnected();
	}

	public void checkpoint() {
        fired = true;
        try {
		logDEBUG = Node.logger.shouldLog(Logger.DEBUG, this);
		boolean logMINOR = Node.logger.shouldLog(Logger.MINOR, this);
		if (logDEBUG)
                    Core.logger.log(this, "Running checkpoint on " + this,
				Logger.DEBUG);
            if (!needsOpen()) return;
            NodeReference ref = ph.getReference();
            if (ref == null) ref = rt.getNodeReference(id);
		if (ref == null || ref.noPhysical()) {
			if (logMINOR)
                        Core.logger.log(this, "No ref or not useful ref: " + id
                                + ": " + ref, Logger.MINOR);
			return;
		}
			ph.attemptingOutboundConnection();
		long startTime = System.currentTimeMillis();
		try {
			if (logMINOR)
                        Core.logger.log(this, "Opening connection to " + ref,
					Logger.MINOR);
                Core.diagnostics.occurrenceCounting(
                        "outboundOpenerConnections", 1);
			//look this stat ye mighty and despair
                ConnectionJob.createConnection(node, node.getPeer(ref), -1);
			// -1 means run blocking

			long diff = System.currentTimeMillis() - startTime;
			ph.succeededOutboundConnection();
			// createConnection won't return a cached conn.
			// It might return one another thread started the open of, but
			// it won't return one that's already been used.
			rt.reportConnectionSuccess(id, diff);
			// FIXME: possible race on isCached?
			if (logMINOR)
                        Core.logger.log(this, "Opened connection to " + ref,
					Logger.MINOR);
		} catch (CommunicationException e) {
			if (logMINOR)
                        Core.logger.log(this,
                                "Could not establish connection to " + ref
                                        + ": " + e + " (" + this + ")", e,
					Logger.MINOR);
                if (ph != null) ph.failedOutboundConnection();
			if (startTime > 0)
                        rt.reportConnectionFailure(id, System
                                .currentTimeMillis()
                                - startTime);
		}
        } finally {
            com.openerTerminated(this, openerIndex);
	}
	}
}
