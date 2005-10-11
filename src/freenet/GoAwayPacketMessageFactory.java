package freenet;

import freenet.node.Main;

/**
 * Message factory for GoAwayPacketMessage's
 * @author amphibian
 */
public class GoAwayPacketMessageFactory implements PeerPacketMessageFactory {

    public int getMessageTypeCode() {
        return PeerPacketMessage.TYPE_GO_AWAY;
    }

    public PeerPacketMessage create(MuxConnectionHandler mch, byte[] buf,
            int offset, int length, boolean needsCopy) {
        return new GoAwayPacketMessage(mch.peerHandler, Main.node);
    }

}
