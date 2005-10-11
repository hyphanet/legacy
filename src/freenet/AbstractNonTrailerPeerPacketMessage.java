/*
 * Created on Apr 10, 2004
 */
package freenet;

/**
 * Base class for simple PeerPacketMessage's that
 * aren't carrying trailing fields. Deriving from this
 * class instead of directly from AbstractPeerPacketMessage
 * relieves the message coder of/prevents him from writing some
 * trailer-only related methods.
 * 
 * @author Iakin
 */

public abstract class AbstractNonTrailerPeerPacketMessage extends AbstractPeerPacketMessage {
	AbstractNonTrailerPeerPacketMessage(PeerHandler peerHandler, long maxAge) {
		super(peerHandler, maxAge);
	}
	
	/**
	 * Non-trailer messages never have a trailer :)
	 * Enforce that behaviour.
	 */
    public final boolean hasTrailer() {
        return false;
    }
    
    /**
	 * And the length of that non-existant trailer is always zero
	 * Enforce that behaviour.
	 */
    public final long trailerLength() {
        return 0;
    }
    
    /**
     * And, well, we dont mux non-existant trailers...
     * Enforce that behaviour.
     */
    public final int trailerMuxCode() {
        return 0;
    }
    
    public boolean wasInsert() {
        return false;
    }
}
