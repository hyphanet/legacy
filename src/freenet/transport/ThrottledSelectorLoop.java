package freenet.transport;
//QUESTION: does this belong in this package?

import java.io.IOException;
import java.net.Socket;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

import freenet.diagnostics.ExternalContinuous;
import freenet.support.Logger;
import freenet.support.io.Bandwidth;
import freenet.support.io.Bandwidth.BandwidthToken;

public abstract class ThrottledSelectorLoop extends AbstractSelectorLoop {

	/** The time at which to reregister everything on the throttleDisabledQueue */
	protected long reregisterThrottledTime = -1;

	/**
	 * The queue of ChannelAttachmentPairs disabled because of bandwidth
	 * limiting.
	 */
    protected final LinkedList throttleDisabledQueue = new LinkedList();

    protected final Set dontReregister =
		Collections.synchronizedSet(new HashSet());

	private int throttleQueueLength = 0;

	protected boolean shortTimeout = false;

	public final int throttleQueueLength() {
		return throttleQueueLength;
	}

	/*
	 * Whether we are currently throttling - i.e. whether we have disabled
	 * registrations and deregistered throttled connections temporarily to
	 * backoff.
	 */
	protected boolean throttling = false;

	/**
	 * The number of bytes not yet accounted for on throttling
	 */
	protected int bytesRemainingOnThrottle = 0;

	public static final int OVERHEAD = 24;
	// Estimate based on hearsay about TCP and ethernet
	// FIXME: make this configurable

	/** Sync on this to prevent SelectorLoop thread from un-throttling */
	protected Object throttleLock = new Object();

	protected Bandwidth bw;
	protected int timerGranularity;

	public ThrottledSelectorLoop(Logger logger, ExternalContinuous closePairLifetimeCallback, 
	        Bandwidth bw, int timerGranularity)
		throws IOException {
	    super(logger, closePairLifetimeCallback);
		this.bw = bw;
		this.timerGranularity = timerGranularity;
	}

	public ThrottledSelectorLoop(Logger logger, ExternalContinuous closePairLifetimeCallback)
		throws IOException {
	    super(logger, closePairLifetimeCallback);
		this.bw = null;
	}

	public void setBandwidth(Bandwidth bw) {
		this.bw = bw;
	}

	public final void throttleBeforeSelect() {
	    if(bw == null) return;
		logDebug = logger.shouldLog(Logger.DEBUG, this);
		if (throttling) {
			long now = System.currentTimeMillis();
			if (logDebug)
				logger.log(
					this,
					"Still throttling at " + now,
					Logger.DEBUG);
			if (now >= reregisterThrottledTime) {
				synchronized (throttleLock) {
					if (bytesRemainingOnThrottle > 0) {
						if (logDebug)
							logger.log(
								this,
								"Calling throttle with remaining "
									+ bytesRemainingOnThrottle
									+ " bytes",
								Logger.DEBUG);
						BandwidthToken tok =
							bw.chargeBandwidthAsync(bytesRemainingOnThrottle);
						if (tok.sleepUntil > 0) {
							reregisterThrottledTime = tok.sleepUntil;
							bytesRemainingOnThrottle -= tok.availableNow;
							if (logDebug)
								logger.log(
									this,
									"Still not ready - sleeping for "
										+ (reregisterThrottledTime - now)
										+ " ms",
									Logger.DEBUG);
						} else {
							reregister(now);
						}
					}
				}
			}
			now = System.currentTimeMillis();
			if (throttling) {
				if (timeout > (reregisterThrottledTime - now)
					&& reregisterThrottledTime > now) {
					timeout = (int) (reregisterThrottledTime - now);
				}
			} else
				timeout = shortTimeout ? 0 : TIMEOUT;
		} else
			timeout = shortTimeout ? 0 : TIMEOUT;
		shortTimeout = false;
		if (logDebug)
			logger.log(this, "Set timeout to " + timeout, Logger.DEBUG);
	}

	private void reregister(long now) {
		logger.log(
			this,
			"Reregistering throttled connections at " + now,
			Logger.MINOR);
		throttling = false;
		reregisterThrottledTime = -1;
		int registered = 0;
		int closed = 0;
		while (!throttleDisabledQueue.isEmpty()) {
			throttleQueueLength = throttleDisabledQueue.size();
			ChannelAttachmentPair current =
				(ChannelAttachmentPair) (throttleDisabledQueue.getFirst());
			throttleDisabledQueue.removeFirst();
			//faster when no indexing
			// Hopefully the selector doesn't reorder them?
			try {
				if (!current.channel.isOpen()) {
					queueClose(current);
					closed++;
				} else {
					if (dontReregister.remove(current.channel)) {
						logger.log(
							this,
							"Not reregistering " + current,
							Logger.DEBUG);
						continue;
					}
					current.channel.register(
						sel,
						myKeyOps(),
						current.attachment);
					registered++;
					//logger.log(this, "Reregistered "+current,
					//				Logger.DEBUG);
					// Already thinks it is registered
				}
			} catch (CancelledKeyException e) {
				if (logger.shouldLog(Logger.DEBUG, this))
					logger.log(
						this,
						"Key (" + current + ") cancelled but not removed: " + e,
						e,
						Logger.ERROR);
				else
					logger.log(
						this,
						"Key cancelled but not removed: " + e,
						e,
						Logger.ERROR);
				queueClose(current);
				closed++;
			} catch (ClosedChannelException e) {
				if (logger.shouldLog(Logger.DEBUG, this))
					logger.log(
						this,
						"Key ("
							+ current
							+ ") channel closed but not removed: "
							+ e,
						e,
						Logger.ERROR);
				else
					logger.log(
						this,
						"Key channel closed but not removed: " + e,
						e,
						Logger.ERROR);
				queueClose(current);
				closed++;
				continue;
			}
		}
		throttleQueueLength = throttleDisabledQueue.size();
		now = System.currentTimeMillis();
		logger.log(
			this,
			"Reregistered throttled connections: "
				+ registered
				+ " registered, "
				+ closed
				+ " closed, "
				+ sel.keys().size()
				+ " keys at "
				+ now,
			Logger.MINOR);
		timeout = 0;
	}

	int currentPseudoThrottledTotal = 0;

	public void putBandwidth(int bytes) {
		synchronized (throttleLock) {
			currentPseudoThrottledTotal += bytes;
		}
	}

	protected final void throttleConnections(
		int bytesRead,
		int throttledBytesRead,
		int pseudoThrottledBytesRead) {
		if (bw == null)
			return;
		logDebug = logger.shouldLog(Logger.DEBUG, this);
		if (bytesRead > 0) {
			if (logDebug)
				logger.log(
					this,
					"Bytes moved total this loop: "
						+ bytesRead
						+ ", bytes that need throttling: "
						+ throttledBytesRead
						+ ", pseudo-throttled: "
						+ pseudoThrottledBytesRead,
					Logger.DEBUG);
		}
		long now = System.currentTimeMillis();
		if (bytesRead < 0
			|| throttledBytesRead < 0
			|| pseudoThrottledBytesRead < 0)
			throw new IllegalArgumentException("something negative this way comes");
		synchronized (throttleLock) {
			currentPseudoThrottledTotal += pseudoThrottledBytesRead;
		}
		if (logDebug && pseudoThrottledBytesRead > 0)
			logger.log(
				this,
				"Added "
					+ pseudoThrottledBytesRead
					+ " for a "
					+ "total of "
					+ currentPseudoThrottledTotal
					+ " bytes "
					+ "waiting (pseudothrottled)",
				Logger.DEBUG);
		if (throttledBytesRead == 0) {
			return;
		}
		synchronized (throttleLock) {
			throttledBytesRead += currentPseudoThrottledTotal;
			currentPseudoThrottledTotal = 0;
		}
		if (reregisterThrottledTime > System.currentTimeMillis())
			logger.log(
				this,
				"throttleConnections("
					+ bytesRead
					+ ","
					+ throttledBytesRead
					+ ") called BEFORE LAST THROTTLE "
					+ "EXPIRED!: now="
					+ now
					+ ", reregisterThrottledTime="
					+ reregisterThrottledTime,
				Logger.ERROR);
		if (throttledBytesRead > 0) {
			BandwidthToken tok = bw.chargeBandwidthAsync(throttledBytesRead);
			long sleepTime = tok.sleepUntil;
			if (sleepTime > 0) {
				reregisterThrottledTime = sleepTime;
				bytesRemainingOnThrottle =
					throttledBytesRead - tok.availableNow;
				logger.log(
					this,
					"Unregistering bwlimited sockets "
						+ " until "
						+ reregisterThrottledTime
						+ " (at "
						+ now
						+ " ("
						+ (sleepTime - now)
						+ " ms for "
						+ throttledBytesRead
						+ " bytes, remaining bytes="
						+ bytesRemainingOnThrottle
						+ ")",
					Logger.MINOR);
				throttling = true;
				// Now iterate through registered keys, and disable
				// those which belong to throttled connections
				Set allKeys = sel.keys();
				Iterator it = allKeys.iterator();
				int deregistered = 0;
				while (it.hasNext()) {
					SelectionKey curKey = (SelectionKey) (it.next());
					if (!curKey.isValid()) {
						if (logDebug)
							logger.log(
								this,
								"Invalid "
									+ curKey
									+ "("
									+ curKey.channel()
									+ ","
									+ curKey.attachment()
									+ " on selector in throttleConnections, ignoring",
								Logger.DEBUG);
						onInvalidKey(curKey);
						continue;
					}
					SocketChannel sc = (SocketChannel) (curKey.channel());
					if (sc == null || (!sc.isOpen()) || (!sc.isConnected())) {
						logger.log(
							this,
							"Closing " + sc + " (" + curKey.attachment() + ")",
							Logger.DEBUG);
						queueClose(
							((SocketChannel) (curKey.channel())),
							(NIOCallback) (curKey.attachment()));
						continue;
					}
					if (shouldThrottle(curKey.attachment())) {
						SocketChannel channel =
							(SocketChannel) (curKey.channel());
						ChannelAttachmentPair pair =
							new ChannelAttachmentPair(
								channel,
								curKey.attachment());
						// 						logger.log(this, "Deregistering "+pair,
						// 										Logger.DEBUG);
						curKey.cancel();
						synchronized (throttleLock) {
							throttleDisabledQueue.add(pair);
							throttleQueueLength = throttleDisabledQueue.size();
							deregistered++;
						}
						if (logDebug)
							logger.log(
								this,
								"Deregistered " + curKey.attachment(),
								Logger.DEBUG);
					}
				}
				if (logDebug)
					logger.log(
						this,
						"Deregistered "
							+ deregistered
							+ " keys, TDQ.size="
							+ throttleDisabledQueue.size(),
						Logger.DEBUG);
			}
		}
	}

	protected void onInvalidKey(SelectionKey key) {
		// Do nothing by default
	}

	protected boolean shouldThrottle(Object o) {
		NIOCallback cb = ((NIOCallback) (o));
		return cb.shouldThrottle();
	}

	protected abstract int myKeyOps();

	public void onClosed(SelectableChannel sc) {
		if (sc == null)
			throw new NullPointerException();
		synchronized (throttleLock) {
			// FIXME: linear scaling with number of disabled
			// connections (just like everything else here :( ).
			Iterator i = throttleDisabledQueue.iterator();
			while (i.hasNext()) {
				ChannelAttachmentPair current =
					(ChannelAttachmentPair) i.next();
				if (current.channel == sc) {
					i.remove();
					throttleQueueLength = throttleDisabledQueue.size();
					break;
				}
			}
		}
	}

	public final void register(SelectableChannel ch, Object attachment) {
		dontReregister.remove(ch);
		super.register(ch, attachment);
	}

	protected void queueClose(CloseQueue.CloseTriplet chan) {
		dontReregister.remove(chan.sc);
		super.queueClose(chan);
	}

	/**
	 * @return true if the pair should be registered NOW, false if later
	 */
	public final boolean shouldRegister(ChannelAttachmentPair c) {
		synchronized (throttleLock) {
			if (!throttling)
				return true;
			SocketChannel chan = (SocketChannel) (c.channel);
			if (chan == null)
				return true;
			Socket sock = chan.socket();
			// FIXME: should pass around tcpConns, not look them up every time
			tcpConnection con =
				(tcpConnection.getConnectionForSocket(sock));
			if (con == null) {
				if (!chan.isOpen())
					return true;
				if (!chan.isConnected())
					return true;
				logger.log(
					this,
					"CANNOT FIND CONNECTION FOR OPEN PAIR " + c,
					new Exception("grrr"),
					Logger.ERROR);
				return true;
			}
			if (con.shouldThrottle())
				return false;
			return true;
		}
	}
}
