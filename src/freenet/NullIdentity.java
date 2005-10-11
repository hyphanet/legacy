package freenet;

import java.math.BigInteger;

import freenet.crypt.CryptoElement;
import freenet.crypt.CryptoKey;

/**
 * This is an Identity implementation that represents an entity
 * with no Identity.  It can be used in comparisons.
 * @author tavin
 */
public final class NullIdentity implements Identity {

    public static final NullIdentity instance = new NullIdentity();

    private static final byte[] nullFingerprint = new byte[0];
    
    /**
     * @return  a byte array of length zero
     */
    public final byte[] fingerprint() {
        return nullFingerprint;
    }
    
    /**
     * @return  the String ""
     */
    public final String fingerprintToString() {
        return "";
    }

    /**
     * @return true
     */
    public final boolean verify(String sig, BigInteger digest) {
        return true;
    }

    /**
     * @return true
     */
    public final boolean verify(CryptoElement sig, BigInteger digest) {
        return true;
    }

    /**
     * @return null
     */
    public final FieldSet getFieldSet() {
        return null;
    }

    /**
     * @return null
     */
    public final CryptoKey getKey() {
        return null;
    }


    public final int hashCode() {
	return 0;
    }

    public final boolean equals(Object o) {
        return o instanceof NullIdentity;
    }


    public final int compareTo(Object o) {
        return o instanceof NullIdentity ? 0 : -1;
    }
}


