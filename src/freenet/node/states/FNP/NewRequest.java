package freenet.node.states.FNP;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Identity;
import freenet.Key;
import freenet.Message;
import freenet.PeerHandler;
import freenet.PeerPacketMessage;
import freenet.Version;
import freenet.diagnostics.ExternalBinomial;
import freenet.diagnostics.ExternalContinuous;
import freenet.message.Accepted;
import freenet.message.QueryRejected;
import freenet.message.Request;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.State;
import freenet.node.states.request.RequestAbortException;
import freenet.node.states.request.RequestSendCallback;
import freenet.support.Logger;

public abstract class NewRequest extends State {

	private static ExternalBinomial inboundAggregateRequests = Node.diagnostics.getExternalBinomialVariable("inboundAggregateRequests");
	private static ExternalContinuous incomingHopsToLive = Node.diagnostics.getExternalContinuousVariable("incomingHopsToLive");

	// Chance to decrease HTL p:
	//      p = 1 - e^(k*HTL^2)     where k == HTL_FACTOR
	//
	// a value of -1.5 gives:
	//          p
	//        ---
	// HTL 1  .78
	// HTL 2  .99
	//
	public static final double HTL_FACTOR = -1.5;
	
	protected Identity sourceID;

	protected NewRequest(long id) {
		super(id);
	}

	protected void genReceived(Node n, Request mo, boolean canAsync)
		throws RequestAbortException {

		// Augmented because is on the routingTime path

		long startTime = System.currentTimeMillis();
		boolean shouldLog = Core.logger.shouldLog(Logger.DEBUG, this);

		n.logRequest(mo.searchKey); // count for load balancing includes those we reject

		sourceID = mo.getSourceID();
		
		PeerHandler ph = mo.getSource();
		
		NodeReference ref = ph.getReference();
		
		String vers = ref == null ? null : ref.getVersion();

		ph.receivedRequest();
		
		long time1 = System.currentTimeMillis();

		logTime(1, time1 - startTime, shouldLog);

		try {
		    // don't accept requests until they have identified.
		    // Just because they don't necessarily have an ADDRESS
		    // doesn't mean they don't have a REFERENCE.
		    if(ref == null) {
		        String reason = "No Identify";
		        Message m = 
		            new QueryRejected(
						    id,
							mo.hopsToLive,
							reason,
							mo.otherFields);
		        n.sendMessageAsync(
						m,
						sourceID,
						PeerPacketMessage.NORMAL,
						Core.hopTime(1, 0),
						null);
		        throw new RequestAbortException(null);
		    }
		    
			// enforce version aloofness
			if (vers != null && !Version.checkGoodVersion(vers)) {
				String reason = Version.explainBadVersion(vers);
				Core.logger.log(
					this,
					"Rejecting query from host of type " + vers + ": " + reason,
					Logger.MINOR);
				if (ph.timeSinceLastMessageSent() > ph.rejectOldVersion(false)
					&& !n.rejectingConnections()) {
					Message m =
						new QueryRejected(
						    id,
							mo.hopsToLive,
							reason,
							mo.otherFields);
					n.sendMessageAsync(
						m,
						sourceID,
						PeerPacketMessage.NORMAL,
						Core.hopTime(1, 0),
						null);
				} // Slow down old, stupid nodes!
				//Do not add to loadstats here since a lot of nodes out there will be rejected due to version incompatibility
				//Bumping loadstats for requests from them causes us to not announce to version-compatible nodes.
				//TODO: Put this back in when we are seeing better stable/unstable network separation
				//n.loadStats.receivedQuery(false); 
				throw new RequestAbortException(null);
			} else {
				ph.rejectOldVersion(true);
			}

			long time3 = System.currentTimeMillis();
			logTime(2, time3 - time1, shouldLog);

			long time4 = System.currentTimeMillis();
			logTime(3, time4 - time3, shouldLog);

			// decrement HTL
			int htl = mo.hopsToLive;
			if (htl < 1)
				htl = 1;
			else if (htl > Node.maxHopsToLive)
				htl = Node.maxHopsToLive;

			long time5 = System.currentTimeMillis();
			logTime(4, time5 - time4, shouldLog);

			double threshold;
			if (htl == Node.maxHopsToLive)
				// If it is == maxHopsToLive, 50% chance of not decrementing
				// This is for plausible deniability
				threshold = 0.5F;
			else
				threshold = Math.exp(HTL_FACTOR * htl * htl);

			if (threshold < 1 - Core.getRandSource().nextDouble()) {
				//Core.logger.log(this, "Decrementing HTL",
				//             Logger.DEBUG);
				--htl;
			} else {
				//Core.logger.log(this, "Not decrementing HTL",
				//             Logger.DEBUG);
			}
			

			long time6 = System.currentTimeMillis();
			logTime(5, time6 - time5, shouldLog);

			// Enforce request rate limiting
			if (!n
				.acceptRequest(
					mo.searchKey,
					mo.getHopsToLive(),
					mo.source.peerAddress(),
					vers)) {
				String reason = "Node overloaded";
				if (shouldLog) Core.logger.log(
					this,
					"Rejecting query, rate limit exceeded.",
					Logger.DEBUG);
				Node.myPQueryRejected.report(1.0);
				if (!n.rejectingConnections()) {
					Message m = new QueryRejected(id, htl, 1, reason,
						// ^--- attenuate routing.
					        mo.otherFields);
					n.sendMessageAsync(
						m,
						sourceID,
						PeerPacketMessage.NORMAL,
						Core.hopTime(1,0),
						null);
				}
				n.loadStats.receivedQuery(false);
				inboundAggregateRequests.count(1,0);
				throw new RequestAbortException(null);
			}
			Node.myPQueryRejected.report(0.0);
			long time7 = System.currentTimeMillis();

			logTime(6, time7 - time6, shouldLog);

			mo.hopsToLive = htl; // <- actually, I think Tavin recreates
			//    the messages now anyways.

			Key k = mo.searchKey;
			if (k.log2size() > Node.maxLog2DataSize) {
				String reason = "File too big";
				Message m =
					new QueryRejected(
						id,
						mo.hopsToLive,
						reason,
						mo.otherFields);
				n.sendMessageAsync(
					m,
					sourceID,
					PeerPacketMessage.NORMAL,
					Core.hopTime(1,0),
					null);
			}

			// Any request that gets past the rate limiting.
			inboundAggregateRequests.count(1,1);
			n.loadStats.receivedQuery(true);

			// reply with Accepted A.S.A.P.
			long time = System.currentTimeMillis();
			incomingHopsToLive.count(htl);
			if (shouldLog)
				Core.logger.log(
					this,
					"Time so far for "
						+ Long.toHexString(id)
						+ " genReceived: "
						+ (time - startTime),
					Logger.DEBUG);
			if (shouldLog)
				Core.logger.log(
					this,
					"Chain "
						+ Long.toHexString(id)
						+ " sending Accepted "
						+ "at "
						+ (time - mo.stateTime)
						+ " millis after stateTime ("
						+ (time - mo.getReceivedTime())
						+ ")",
					Logger.DEBUG);
			Message m = new Accepted(id);
			if (canAsync) {
				RequestSendCallback cb =
					new RequestSendCallback("Accepted", n, this);
				n.sendMessageAsync(
					m,
					sourceID,
					PeerPacketMessage.NORMAL,
					Core.hopTime(1,0),
					cb);
			} else
				n.sendMessage(m, sourceID, Core.hopTime(1,0));
			// discount the seconding time from the routing time measurement
			// this needs some cleaning...
			long acceptTime = System.currentTimeMillis() - time;
			if (shouldLog)
				Core.logger.log(
					this,
					"Chain "
						+ Long.toHexString(id)
						+ " took "
						+ acceptTime
						+ " millis to send Accepted",
					Logger.DEBUG);
			mo.setReceivedTime(acceptTime + mo.getReceivedTime());

		} catch (CommunicationException e) {
			Core.logger.log(
				this,
				"Failed to send initial response to Request.",
				e,
				Logger.MINOR);
			throw new RequestAbortException(null);
		}
	}

	private void logTime(int num, long time, boolean shouldLog) {
		if (shouldLog) {
			Core.logger.log(
				this,
				"logTime: "
					+ Long.toHexString(id)
					+ ":"
					+ num
					+ ". "
					+ time
					+ " ms.",
				time > 500 ? Logger.MINOR : Logger.DEBUG);
		}
	}

	/**
	 * Requests in state new do nothing when lost.
	 */
	public final void lost(Node n) {
	}
}