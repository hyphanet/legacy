package freenet.session;

import java.math.BigInteger;
import java.util.Enumeration;
import java.util.Hashtable;

import net.i2p.util.NativeBigInteger;

import freenet.Authentity;
import freenet.CommunicationException;
import freenet.ConnectFailedException;
import freenet.Connection;
import freenet.Core;
import freenet.DSAAuthentity;
import freenet.DSAIdentity;
import freenet.Identity;
import freenet.crypt.DiffieHellman;
import freenet.crypt.Digest;
import freenet.crypt.SHA1;
import freenet.support.Logger;

/**
 * The LinkManager keeps track of inter-node cryptography.
 * 
 * @author oskar (changed name to FnpLinkManager)
 * @author Scott
 */
public final class FnpLinkManager implements LinkConstants, LinkManager {

	public static final int DESIGNATOR = 1;

	/** Stores the keys that are active at any given time. */
	private final Hashtable activeLinks, activePeers;

	private Object currentLock = new Object();
	private int currentNegotiations = 0;

	private int negotiationLimit;

	/** Discard expired links. */
	public synchronized void cleanupLinks() {
		long now = System.currentTimeMillis();
		Enumeration e = activeLinks.keys();
		while (e.hasMoreElements()) {
			BigInteger li = (BigInteger) e.nextElement();
			FnpLinkToken l = (FnpLinkToken) activeLinks.get(li);
			if (l != null && now > l.inboundExpiresAt()) {
				removeLink(l, li);
			}
		}
	}

	public int countActiveLinks() {
		return activeLinks.size();
	}

	public int countActivePeers() {
		return activePeers.size();
	}

	public FnpLinkManager() {
		this(25);
	}

	public FnpLinkManager(int negotiationLimit) {
		this.negotiationLimit = negotiationLimit;
		activePeers = new Hashtable(5);
		activeLinks = new Hashtable(5);
		DiffieHellman.init();
	}

	public Link createOutgoing(
		Authentity privMe,
		Identity pubMe,
		Identity bob,
		Connection c)
		throws CommunicationException {
		long startTime = System.currentTimeMillis();
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"createOutgoing() at " + startTime,
				Logger.DEBUG);
		synchronized (currentLock) {
			currentNegotiations++;
			// Don't stop outgoing negotiations because of negotiation limit
		}

		long negUpTime = System.currentTimeMillis();
		long negUpLen = negUpTime - startTime;
		if (logDEBUG || negUpLen > 500)
			Core.logger.log(
				this,
				"currentNegotiations up took " + negUpLen,
				negUpLen > 500 ? Logger.MINOR : Logger.DEBUG);

		long solicitTime;
		try {
			if (!(privMe instanceof DSAAuthentity)
				|| !(pubMe instanceof DSAIdentity)
				|| !(bob instanceof DSAIdentity)) {
				Core.logger.log(
					this,
					"Cannot negoiate inbound with non DSA " + " keys.",
					Logger.ERROR);
				throw new RuntimeException("Keys not DSA");
			}

			FnpLink l = new FnpLink(this, c);
			l.solicit(
				(DSAAuthentity) privMe,
				(DSAIdentity) pubMe.getKey(),
				(DSAIdentity) bob.getKey(),
				true);
			solicitTime = System.currentTimeMillis();
			long solicitLength = solicitTime - negUpTime;
			if (logDEBUG || solicitLength > 1000)
				Core.logger.log(
					this,
					"solicit() took " + solicitLength + " at " + solicitTime,
					solicitLength > 1000 ? Logger.MINOR : Logger.DEBUG);
			Core.diagnostics.occurrenceContinuous(
				"authorizeTime",
				solicitTime - negUpTime);
			return l;
		} finally {
			synchronized (currentLock) {
				currentNegotiations--;
			}
		}
	}

	public Link acceptIncoming(Authentity privMe, Identity pubMe, Connection c)
		throws CommunicationException {
		synchronized (currentLock) {
			if (currentNegotiations >= negotiationLimit) {
				Core.logger.log(
					this,
					"Too many ongoing negotiations! ("
						+ currentNegotiations
						+ "/"
						+ negotiationLimit
						+ ")",
					Logger.DEBUG);
				throw new ConnectFailedException(
					c.getPeerAddress(),
					"Too many ongoing " + "negotiations.");
			}
			currentNegotiations++;
		}
		try {

			if (!(privMe instanceof DSAAuthentity)
				|| !(pubMe instanceof DSAIdentity)) {
				Core.logger.log(
					this,
					"Cannot negoiate inbound with non DSA " + " keys.",
					Logger.ERROR);
				throw new RuntimeException("Keys not DSA");
			}

			long time = System.currentTimeMillis();
			FnpLink l = new FnpLink(this, c);
			l.accept((DSAAuthentity) privMe, (DSAIdentity) pubMe, LAX);
			Core.diagnostics.occurrenceContinuous(
				"authorizeTime",
				System.currentTimeMillis() - time);
			return l;
		} finally {
			synchronized (currentLock) {
				currentNegotiations--;
			}
		}
	}

	/**
	 * Adds a key to the link manager. The key will be automatically expired by
	 * the link manager when KEY_LIFETIME milliseconds pass.
	 */
	public FnpLinkToken addLink(Identity remotePK, Identity me, byte[] k) {

		Digest ctx = SHA1.getInstance();
		byte[] hk = new byte[ctx.digestSize() >> 3];
		ctx.update(k);
		ctx.digest(true, hk, 0);

		FnpLinkToken oldLt = (FnpLinkToken) activePeers.get(remotePK);
		if (oldLt != null) {
			removeLink(oldLt);
		}

		BigInteger linkIdentifier = new NativeBigInteger(1, hk);

        FnpLinkToken lt = new FnpLinkToken(remotePK, me, k, linkIdentifier);
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"Adding link to peer " + remotePK,
				Logger.DEBUG);
		Core.diagnostics.occurrenceCounting("liveLinks", 1);
		synchronized (this) {
			activeLinks.put(linkIdentifier, lt);
			activePeers.put(remotePK, lt);
		}
		return lt;
	}

	public synchronized void removeLink(FnpLinkToken l) {
		removeLink(l, l.getKeyHash());
	}

	public synchronized void removeLink(FnpLinkToken l, BigInteger li) {
		Core.logger.log(this, "removing link " + l, Logger.DEBUG);
		l.expire(); // FIXME ??? Nah, it just doesn't do much.
		activeLinks.remove(li);
		activePeers.remove(l.getPeerIdentity());
		Core.diagnostics.occurrenceCounting("liveLinks", -1);
	}

	/**
	 * Searches for the given hashed key in the active links. (Note the
	 * misnomer, this is used on inbound restarts but covers all links)
	 * 
	 * @return The link structure, if found, null otherwise.
	 */
	public synchronized FnpLinkToken searchInboundLinks(BigInteger linkIdentifier) {
		FnpLinkToken lt = (FnpLinkToken) activeLinks.get(linkIdentifier);
		return lt == null
			|| System.currentTimeMillis() > lt.inboundExpiresAt() ? null : lt;
	}

	/**
	 * Searches for the given remote public key in the active links. (Note the
	 * misnomer, this is used on inbound restarts but covers all links).
	 * 
	 * @return The link structure, if found, null otherwise.
	 */
	public synchronized FnpLinkToken searchOutboundLinks(Identity linkIdentifier) {
		FnpLinkToken lt = (FnpLinkToken) activePeers.get(linkIdentifier);
		return lt == null
			|| System.currentTimeMillis() > lt.outboundExpiresAt() ? null : lt;
	}

	public final int designatorNum() {
		return DESIGNATOR;
	}
}
