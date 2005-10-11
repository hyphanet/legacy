package freenet.node.states.announcing;

import java.util.Hashtable;

import freenet.Core;
import freenet.Identity;
import freenet.Key;
import freenet.MessageObject;
import freenet.diagnostics.Diagnostics;
import freenet.node.AggregatingState;
import freenet.node.EventMessageObject;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.State;
import freenet.node.StateException;
import freenet.node.rt.Routing;
import freenet.support.Logger;

/**
 * This is the envelope state around the sub-chain to each target node.
 */

public class Announcing extends AggregatingState {

	static long startTime = -1;
	/**
	 * Places the Announcing state in the node's state table, where it will
	 * live.
	 * @param n               The node to announce.
	 * @param hopsToLive      HopsToLive to use on each message
	 * @param delay           How often should we wake up and check that the
	 *                        node has traffic?
	 */
	public static void placeAnnouncing(
		Node n,
		int hopsToLive,
		int delay,
		int firstDelay) {

		startTime = System.currentTimeMillis();

		Core.logger.log(
			Announcing.class,
			"Starting announce background task. HTL: "
				+ hopsToLive
				+ " Poll Interval: "
				+ delay
				+ " Threads: "
				+ Node.announcementThreads
				+ " Attempts: "
				+ Node.announcementAttempts,
			Logger.MINOR);
		n.schedule(
			0,
			new PlaceAnnouncing(
				Core.getRandSource().nextLong(),
				hopsToLive,
				delay,
				firstDelay));
	}

	private static class PlaceAnnouncing extends EventMessageObject {
		private int hopsToLive;
		private long delay;
		private long firstDelay;

		public PlaceAnnouncing(
			long id,
			int hopsToLive,
			long delay,
			long firstDelay) {
			super(id, true);
			this.hopsToLive = hopsToLive;
			this.delay = delay;
			this.firstDelay = firstDelay;
		}

		public State getInitialState() {
			return new Announcing(id, hopsToLive, delay, firstDelay);
		}

		public String toString() {
			return "Initiate announcement procedure.";
		}
	}

	private static class ScheduleAnnouncing extends EventMessageObject {
		public ScheduleAnnouncing(long id) {
			super(id, true);
		}

		public String toString() {
			return "Wake announcement procedure if there is no traffic.";
		}
	}

	private static class Announced {
		public Identity peer;
		public int times;
		public int successful;

		public Announced(Identity peer) {
			this.peer = peer;
		}

		public void success() {
			times++;
			successful++;
		}

		public void failed() {
			times++;
		}
	}

	private long delay;
	private long firstDelay;
	private boolean isFirstTime = true;
	private int hopsToLive;
	private int origHopsToLive;

	private int nextTarget;

	private Hashtable attempted; //TODO: As of now we are leaking items in this HashTable
	private int totalAttempts;
	private volatile int successes;

	private static boolean logDebug = true;

	private Announcing(
		long id,
		int hopsToLive,
		long delay,
		long firstDelay) {
		super(id, 0);

		this.hopsToLive = hopsToLive;
		this.origHopsToLive = hopsToLive;
		this.delay = delay;
		this.firstDelay = firstDelay;
		nextTarget = 0;

		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);

		attempted = new Hashtable();
		totalAttempts = 0;
		successes = 0;
	}

	public String getName() {
		return "Announcing Chains Aggregate";
	}

	long timeLastAnnounced = -1;

	public State received(Node n, MessageObject mo) throws StateException {
		if (mo instanceof PlaceAnnouncing) {
			n.schedule(isFirstTime ? firstDelay : delay, 
			        new ScheduleAnnouncing(id));
			isFirstTime = false;
		} else if (mo instanceof Completed) {
			Completed c = (Completed) mo;
			Announced a = (Announced) attempted.get(c.peer);
			if (a == null) {
				a = new Announced(c.peer);
				attempted.put(c.peer, a);
			}
			totalAttempts++;
			if (c.successful) {
				Core.logger.log(
					this,
					"Announced node successfully to "
						+ c.peer.fingerprintToString()
						+ " at depth  "
						+ c.htl
						+ ".",
					Logger.NORMAL);
				a.success();
				successes++;
				int x = hopsToLive++;
				int y = (hopsToLive * 5) / 4;
				if (y > x)
					x = y;
				if (x > origHopsToLive)
					x = origHopsToLive;
				origHopsToLive = x;
			} else {
				String s = c.reasonString;
				Core.logger.log(
					this,
					"Announcement failed to "
						+ c.peer.fingerprintToString()
						+ " at depth  "
						+ c.htl
						+ ((c == null) ? "" : (":" + s)),
					Logger.NORMAL);
				hopsToLive--;
				if (hopsToLive < 2)
					hopsToLive = 2;
				a.failed();
				if (c.terminal)
					a.times = Node.announcementAttempts;
				// Only successful announcements reset timeLastAnnounced
			}
		} else if (mo instanceof ScheduleAnnouncing) {
			try {
				double traffic =
					Core.diagnostics.getValue(
						"localQueryTraffic",
						Diagnostics.HOUR,
						Diagnostics.NUMBER_OF_EVENTS);

				double connections =
					Core.diagnostics.getValue(
						"connectingTime",
						Diagnostics.HOUR,
						Diagnostics.NUMBER_OF_EVENTS);

				Core.logger.log(
					this,
					"Traffic: " + traffic + ", Connections: " + connections,
					Logger.DEBUG);

				if (traffic < 1.0
					|| connections < 1.0
					|| (successes > 0 && successes < Node.announcementThreads)
					|| ((n.loadStats.globalQueryTraffic()
						* Node.defaultResetProbability
						> n.loadStats.localQueryTraffic())
						&& (System.currentTimeMillis() - timeLastAnnounced
							> 3600 * 1000)
						&& (System.currentTimeMillis() - startTime
							> delay * 2))) {
					// FIXME: this only ensures that we have 3 successes _THE FIRST TIME_
					if (logDebug)
						Core.logger.log(this, "Announcing", Logger.DEBUG);

					// find three peers.
					timeLastAnnounced = System.currentTimeMillis();

					int m = 0;
					int x = Node.announcementThreads - successes;
					if (x < 1)
						x = 1;
					NodeReference[] now = new NodeReference[x];

					// As long as we are newbie it's perfectly valid to announce to peers.
					// If we are not newbie there probably isn't much point anyway?
						byte[] b = new byte[8];
						Core.getRandSource().nextBytes(b);
						Routing routes =
					    n.rt.route(new Key(b),
								hopsToLive,
								0,
								false, // not insert
								true, // is announcement
								false,
								false,
								true);
						// FIXME: usually we will route, BUT we won't if the announce
						// has already visited that node. Hopefully this isn't a MAJOR
						// problem, but if so, we'll have to look a NodeEstimator.isAvailable(true)
						// - see the comments there, it's a nasty one
						while (m < now.length) {
						    Identity id = routes.getNextRoute();
						    if(id == null) break;
						    NodeReference nr = n.rt.getNodeReference(id);
						    if(nr == null) continue;
								for (int i = 0; i < m; i++) {
									if (nr.getIdentity().equals(now[i]))
										continue;
								}
								Announced a =
									(Announced) attempted.get(nr.getIdentity());
								if (a == null
									|| a.times < Node.announcementAttempts) {
									now[m] = nr;
									m++;
								}
							}
						routes.terminateNoDiagnostic();

					Core.logger.log(
						this,
						"Found " + m + " announcement targets for this node.",
						Logger.NORMAL);
					for (int i = 0; i < m; i++) {
					    // Why on earth did we use the same ID?!
						SendAnnouncement.makeTry(n, Core.getRandSource().nextLong(), now[i], hopsToLive);
					}
				} else {
					Core.logger.log(
						this,
						traffic
							+ " requests in the last hour. "
							+ "Won't announce.",
						Logger.MINOR);
					successes = 0;
				}
			} finally {
				n.schedule(delay, new ScheduleAnnouncing(id));
			}
		} else
			super.received(n, mo);

		return this; // announcing always stays!
	}

	public int priority() {
		return CRITICAL;
	}
}
