package freenet.node.states.announcement;

import java.io.IOException;
import java.util.Arrays;

import net.i2p.util.NativeBigInteger;
import freenet.Core;
import freenet.Key;
import freenet.crypt.SHA1;
import freenet.message.AnnouncementExecute;
import freenet.node.Node;
import freenet.support.HexUtil;
import freenet.support.KeyList;
import freenet.support.Logger;
import freenet.support.io.ParseIOException;

/**
 * A superclass for the shared code between LastNode and ExecutePending states.
 */

public abstract class ExecuteHandler extends AnnouncementState {

    protected NoExecute ne;
    /** Starts as the return value (including this node). When Execute is
     *  handled, it gets all the other values XORed to it.
     */
    protected byte[] total;

    public ExecuteHandler(AnnouncementState st, NoExecute ne,
                          byte[] total) {
        super(st);
        this.ne = ne;
        this.total = total;
    }

    
    /**
     * Execute the announcement.
     * @param n   The node
     * @param ae  AnnouncementExecute message. myVal is added to it's keylist
     *            as a sideeffect.
     * @return The announcee key if successful, null otherwise. 
     */
    protected Key executeAnnounce(Node n, AnnouncementExecute ae) {

        ne.cancel();

        try {
            ae.readKeys(depth);
        } catch (ParseIOException e) {
            Core.logger.log(this, "Parse error on AnnouncementExecute value list, rejecting",
                         e, Logger.NORMAL);
            return null;
        } catch (IOException e) {
            Core.logger.log(this, "I/O error reading value list from AnnouncementExecute",
                         e, Logger.NORMAL);
            return null;
        }
        
        KeyList kl = ae.getKeys();
        kl.xorTotal(total);
        // note the order, total already contains myVal
        kl.addEntry(myVal);
        byte[] kh = kl.cumulativeHash(SHA1.getInstance());
        //System.err.println("LALA TOTAL: " + 
        //                       freenet.support.HexUtil.bytesToHex(total));

        if (kl.size() == (depth + 1) && 
            Arrays.equals(commitVal, kh) &&
            announcee.getIdentity().verify(ae.getRefSignature(),
                                           new NativeBigInteger(1, total))) {
            
            // create key
            return new Key(total, 0, 0);

        } else {
            Core.logger.log(this, "Rejecting execute, KLSIZE: " + kl.size() 
                         + " DEPTH: " + depth + " COMMITVAL: " + 
						 HexUtil.bytesToHex(commitVal) + " KH: " +
						 HexUtil.bytesToHex(kh), Logger.NORMAL);
	    
            return null;
        }
	
    }
}
