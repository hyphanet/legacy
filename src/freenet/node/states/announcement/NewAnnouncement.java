package freenet.node.states.announcement;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Identity;
import freenet.Key;
import freenet.SendFailedException;
import freenet.Version;
import freenet.message.Accepted;
import freenet.message.AnnouncementFailed;
import freenet.message.AnnouncementReply;
import freenet.message.NodeAnnouncement;
import freenet.message.QueryRejected;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.State;
import freenet.support.Logger;

/**
 * Initial state for the announcements.
 * 
 * @author oskar
 */

public class NewAnnouncement extends AnnouncementState {

	public NewAnnouncement(
		long id,
		NodeReference announcee,
		int depth,
		int hopsToLive,
		byte[] commitVal) {
		super(id, announcee, depth, hopsToLive, commitVal);
	}

	NewAnnouncement(ReplyPending p) {
		super(p);
	}

	public String getName() {
		return "New Announcement";
	}

	public State receivedMessage(Node n, NodeAnnouncement na) {
		try {
			origRec = n.getPeer(na.getRef());
			if (origRec == null) {
				Core.logger.log(
					this,
					"Failed to determine Peer for NodeReference",
					Logger.NORMAL);
				return null;
			}

			na.source.getPeerHandler().receivedRequest();
			
			// Don't accept if overloaded and we are not the first peer
			if ((!(origRec.getIdentity().equals(announcee.getIdentity()))) &&
			        (!n.acceptRequest(null, na.getHopsToLive(), null, null))) {

				n.sendMessage(
					new QueryRejected(
						id,
						na.getHopsToLive(),
						"Node overloaded",
						null),
					origRec,
					getTime(2));
				// see announcing.SendAnnouncement

				n.loadStats.receivedQuery(false);
				return null;
			}
			n.loadStats.receivedQuery(true);

			// enforce aloofness
			String vers = na.getRef().getVersion();
			if (vers != null && !Version.checkGoodVersion(vers)) {
				String reason = Version.explainBadVersion(vers);
				Core.logger.log(
					this,
					"Rejecting query from host of type " + vers + ": " + reason,
					Logger.MINOR);
				n.sendMessage(
					new QueryRejected(
						id,
						na.getHopsToLive(),
						reason,
						null),
					origRec,
					getTime(2));

				return null;
			}

			if (!announcee.checkAddresses(n.transports)) {
				if (Core.logger.shouldLog(Logger.MINOR,this))
					Core.logger.log(
						this,
						"Rejecting announcement because addresses "
							+ "of announcee wrong: "
							+ announcee.toString(),
						Logger.MINOR);
				n.sendMessage(
					new QueryRejected(
						id,
						na.getHopsToLive(),
						"Broken addresses.",
						null),
					origRec,
					getTime(2));

			}
			Identity i = announcee.getIdentity();
			// FIXME: prevent node announcing many times while newbie
			if (n.rt.references(i) && !(n.rt.isNewbie(i,n.connections.isOpen(i)))) {
				if (Core.logger.shouldLog(Logger.DEBUG, this))
					Core.logger.log(
						this,
						"Previously knew Announcee, rejecting.",
						Logger.DEBUG);
				n.sendMessage(
					new AnnouncementFailed(
						id,
						AnnouncementFailed.KNOWN_ANNOUNCEE),
					origRec,
					getTime(2));

				return null;
			} else if ((depth + hopsToLive) > Node.maxHopsToLive) {
				if (Core.logger.shouldLog(Logger.DEBUG, this))
					Core.logger.log(
						this,
						"Too high HTL on Announcement, rejecting.",
						Logger.DEBUG);
				n.sendMessage(
					new AnnouncementFailed(
						id,
						AnnouncementFailed.UNACCEPTABLE_HTL),
					origRec,
					getTime(2));

				return null;
			}

			na.setSource(n.getNodeReference());

			na.incDepth();
			na.decHopsToLive();

			// Return Accepted
			n.sendMessage(
				new Accepted(id),
				origRec,
				getTime(2));

		} catch (CommunicationException e) {
			Core.logger.log(
				this,
				"Failed to return reply to Announcement",
				Logger.MINOR);
			return null;
		}
		// Our random commit value
		myVal = new byte[20];
		Core.getRandSource().nextBytes(myVal);

		// update commitVal
		synchronized (ctx) {
			ctx.update(myVal);
			ctx.update(commitVal);
			ctx.digest(true, commitVal, 0);
		}
		//na.setCommitVal(commitVal); side effect...

		if (hopsToLive > 0) {
			return sendOn(n, na);

		} else { // end of the line
			try {
				AnnouncementReply ar = new AnnouncementReply(id, myVal);
				NoExecute ne = new NoExecute(id);
				n.schedule(getTime(depth), ne);
				n.sendMessage(ar, origRec, getTime(depth));
				//System.err.println("MYVAL: " + HexUtil.bytesToHex(myVal));
				return new LastNode(this, ne);
			} catch (CommunicationException e) {
				Core.logger.log(
					this,
					"Failed to return reply to Announcement: " + e,
					Logger.MINOR);
				return null;
			}
		}
	}

	/**
	 * Routes a forwards an announcement.
	 * 
	 * @param n
	 *            The node
	 * @param na
	 *            The nodeAnnouncement message
	 * @return The state we are in after sending.
	 */
	State sendOn(Node n, NodeAnnouncement na) {

		if (routes == null) { // first time
			// Route. No sense in taking too much entropy. (And this way
			// I can test Tavin's routing table :-) ).
			long l = Core.getRandSource().nextLong();
			Key k =
				new Key(
					new byte[] {
						(byte) l,
						(byte) (l >> 8),
						(byte) (l >> 16),
						(byte) (l >> 24),
						(byte) (l >> 32),
						(byte) (l >> 40),
						(byte) (l >> 48),
						(byte) (l >> 56)});
			routes = n.rt.route(k, hopsToLive, 0, false, true, false, false, true);
			// Announcements count as requests for rate limiting purposes
		}

		routed++;

		while (true) {
			if (Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(
					this,
					"Trying to route "
						+ Long.toHexString(id)
						+ " - iteration "
						+ routed
						+ " of "
						+ MAX_ROUTING_TIMES,
					Logger.DEBUG);

			Identity ident = routes.getNextRoute();
			if(ident == null) break;
			
			NodeReference nr = n.rt.getNodeReference(ident);
			if (nr == null) continue;

			if (origRec != null && origRec.equalsIdent(ident))
				// don't loop back to the sender
				continue;

			if (Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(
					this,
					"Forwarding query (" + Long.toHexString(id) + ") to: " + nr,
					Logger.DEBUG);
			try {
				n.sendMessage(na, nr, getTime(1));

				lastAddr = n.getPeer(nr);
				NoReply nrm = new NoReply(id);
				n.schedule(getTime(1), nrm);
				return new ReplyPending(this, nrm);
			} catch (SendFailedException ce) {
				routes.earlyTimeout();
			}
		}
		try {
			n.sendMessage(
				new QueryRejected(
					id,
					hopsToLive,
					"All routes failed.",
					na.otherFields),
				origRec,
				getTime(2));
		} catch (SendFailedException e) {
			Core.logger.log(
				this,
				"Failed to send back QueryRejected.",
				e,
				Logger.MINOR);
			routes.earlyTimeout();
		}
		return new AnnouncementDone(this);
	}
}
