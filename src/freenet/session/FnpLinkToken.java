package freenet.session;

import java.math.BigInteger;
import java.util.Arrays;

import freenet.Identity;

final class FnpLinkToken {
	protected static final long KEY_LIFETIME = 36000000; // 1 hour
	protected static final long GRACE_PERIOD = 900000; // 15 minutes

	//private Vector links;
	private Identity peerIdentity, myIdentity;
	private BigInteger hk;
	private byte[] k;
	private long expires;

	protected FnpLinkToken(
		Identity peer,
		Identity me,
		byte[] key,
		BigInteger hk) {
		this.k = key;
		peerIdentity = peer;
		myIdentity = me;
		this.hk = hk;
		expires = System.currentTimeMillis() + KEY_LIFETIME;
	}

	public long inboundExpiresAt() {
		return expires + GRACE_PERIOD;
	}

	public long outboundExpiresAt() {
		return expires;
	}

	//protected void expire(Hashtable registry) {
	public void expire() {
		Arrays.fill(k, (byte) 0);

		//for (int i=0; i<links.size(); i++) {
		//    ConnectionHandler ch=(ConnectionHandler)links.elementAt(i);
		//            removePhysicalLink(ch);
		//}

		//registry.remove(peerIdentity);
		//registry.remove(hk);
	}

	protected void finalize() throws Throwable {
		Arrays.fill(k, (byte) 0);
	}

	Identity getMyIdentity() {
		return myIdentity;
	}

	Identity getPeerIdentity() {
		return peerIdentity;
	}

	BigInteger getKeyHash() {
		return hk;
	}

	byte[] getKey() {
		return k;
	}
}
