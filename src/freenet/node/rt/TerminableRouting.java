/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.rt;

import freenet.Core;
import freenet.Identity;
import freenet.Key;
import freenet.keys.CHK;
import freenet.node.Node;
import freenet.support.Logger;

/**
 * Base class for NGRouting and TreeRouting
 * Does diagnostics, the static method staticTerminateRouting is called
 * from states. Hence public.
 */
public abstract class TerminableRouting implements Routing {
	protected boolean terminated = false;
	protected boolean noDiag = false;
	protected final boolean wasLocal;
	protected final Key k;
	protected final Node node;
	boolean logDEBUG = false;
	protected boolean willSendRequests;
	
	public TerminableRouting(Key k, boolean wasLocal, Node node, boolean willSendRequests) {
	    this.logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		this.k = k;
		this.wasLocal = wasLocal;
		this.node = node;
		this.willSendRequests = willSendRequests;
	}

	public void terminate(
		boolean success,
		boolean routingRelated,
		boolean endOfRoute) {
		if (terminated)
			return;
		terminated = true;
		reallyTerminate(success, routingRelated, endOfRoute);
	}

	public void terminateNoDiagnostic() {
		if (terminated)
			return;
		terminated = true;
		noDiag = true;
		willSendRequests = false;
		reallyTerminate(false, false, false);
	}

	protected void reallyTerminate(
		boolean success,
		boolean routingRelated,
		boolean endOfRouteReached) {
		if (noDiag)
			return;
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"reallyTerminate("
					+ success
					+ ","
					+ routingRelated
					+ ") for "
					+ this,
				new Exception("debug"),
				Logger.DEBUG);
		if(willSendRequests)
		staticReallyTerminate(success, routingRelated, endOfRouteReached, 
		        wasLocal, k);
	}

	public static void staticReallyTerminate(
		boolean success,
		boolean routingRelated,
		boolean endOfRouteReached,
		boolean localRequest,
		Key k) {
		Core.diagnostics.occurrenceBinomial(
			"requestSuccessRatio",
			1,
			success ? 1 : 0);
		if (localRequest)
		    Core.diagnostics.occurrenceBinomial(
		        "localRequestSuccessRatio",
		        1,
		        success ? 1 : 0);
		if (!success) {
			Core.diagnostics.occurrenceBinomial(
				"requestFailureRoutingOrNotRatio",
				1,
				routingRelated ? 1 : 0);
			if (routingRelated)
				Core.diagnostics.occurrenceBinomial(
					"routingFailRNFRatio",
					1,
					endOfRouteReached ? 1 : 0);
			Core.diagnostics.occurrenceBinomial(
				"requestFailRNFRatio",
				1,
				endOfRouteReached ? 1 : 0);
		} else {
		    Core.diagnostics.occurrenceBinomial(
		        "requestSuccessRoutingOrNotRatio",
		        1,
		        routingRelated ? 1 : 0);
		}
		if (routingRelated) {
			Core.diagnostics.occurrenceBinomial(
				"routingSuccessRatio",
				1,
				success ? 1 : 0);
			if (localRequest)
			    Core.diagnostics.occurrenceBinomial(
			        "localRoutingSuccessRatio",
			        1,
			        success ? 1 : 0);
			if (!endOfRouteReached)
				Core.diagnostics.occurrenceBinomial(
					"routingSuccessRatioNoRNF",
					1,
					success ? 1 : 0);
			if (k instanceof CHK)
				Core.diagnostics.occurrenceBinomial(
					"routingSuccessRatioCHK",
					1,
					success ? 1 : 0);
			if (k instanceof CHK && !endOfRouteReached)
				Core.diagnostics.occurrenceBinomial(
					"routingSuccessRatioCHKNoRNF",
					1,
					success ? 1 : 0);
		}
	}

	protected void finalize() {
		if (!terminated) {
			Core.logger.log(this, "Did not terminate " + this, Logger.NORMAL);
		}
	}

	protected boolean freeConn(Identity id) {
		long end, start = 0;
		if (logDEBUG) {
			Core.logger.log(this, "Checking for free conn...", Logger.DEBUG);
			start = System.currentTimeMillis();
		}
		boolean ret = node.connections.isOpen(id) &&
			node.connections.canSendRequests(id);
		if (logDEBUG) {
			end = System.currentTimeMillis();
			Core.logger.log(
				this,
				"Checking for free conn for "
					+ id
					+ " took "
					+ (end - start)
					+ " for "
					+ this
					+ ", result="
					+ ret,
				Logger.DEBUG);
		}
		return ret;
	}
}
