package freenet.node.states.announcement;
import java.io.IOException;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Key;
import freenet.message.AnnouncementComplete;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.KeyList;
import freenet.support.Logger;
import freenet.support.io.ParseIOException;

/**
 * Waiting for an AnnouncementComplete.
 */

public class CompletePending extends AnnouncementState {

    private Key k;
    private NoComplete nc;

    public CompletePending(AnnouncementState st, Key k,
                           NoComplete nc) {
        super(st);
        this.k = k;
        this.nc = nc;
    }

    public String getName() {
        return "Announcement Complete Pending";
    }

    public State receivedMessage(Node n, NoComplete noComplete) 
        throws BadStateException {

        if (noComplete != this.nc) {
            throw new BadStateException("Not my NoComplete.");
        }
        
        // I guess we'll be the last node then
        (new LastNode(this, null)).sendComplete(n, k);
         
        // insert into Routing table
        if(announcee.isSigned())
            n.reference(k, null, announcee, null);
        // Build an absurdly optimistic initially specialized estimator

        terminateRouting(false, false);
        // since we have added the reference
        return new AnnouncementDone(this);
    }


    public State receivedMessage(Node n, AnnouncementComplete ac) throws BadStateException {
        checkReply(ac);

        nc.cancel();
        
        try {
            ac.readKeys(depth + hopsToLive);
        }
        catch (ParseIOException e) { // ignore and refill
            Core.logger.log(this, "parse error reading key list, continuing",
                         e, Logger.MINOR);
        }
        catch (IOException e) { // 
            Core.logger.log(this, "I/O error reading key list, continuing",
                         e, Logger.MINOR);
        }
        
        KeyList keys = ac.getKeys();
        
        /*
        System.err.println("CompletePending - got this key list:");
        try {
            keys.writeTo(System.err);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        */

        keys.setCompareBase(k);
        keys.sort();
        keys.prune();

        Key[] k1 = n.ds.findClosestKeys(k, true, depth+hopsToLive);
        Key[] k2 = keys.toKeyArray();

        // both k1 and k2 are in ascending order
        // (in terms of distance from the compare base)
        
        KeyList newKeys = new KeyList();

        int i1 = 0, i2 = 0;

        int counter = depth + hopsToLive;
        while (counter-- > 0) {
            if (i1 < k1.length && i2 < k2.length) {
                newKeys.addEntry(k.compareTo(k1[i1], k2[i2]) <= 0
                                 ? k1[i1++] : k2[i2++]);
            }
            else if (i1 < k1.length) {
                newKeys.addEntry(k1[i1++]);
            }
            else if (i2 < k2.length) {
                newKeys.addEntry(k2[i2++]);
            }
            else break;
        }    

        /*
        System.err.println("CompletePending - forwarding this key list:");
        try {
            newKeys.writeTo(System.err);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        */
        ac.setKeys(newKeys);
        
        // insert into Routing table
        if(announcee.isSigned())
            n.reference(k, null, announcee, null);
        // Build an absurdly optimistic initially specialized estimator
        
        try {
            sendMessage(n, origRec, ac, newKeys, getTime(hopsToLive)*3);
        } catch (CommunicationException e) {
            Core.logger.log(this, "Failed to send AnnouncementComplete",
                         e, Logger.MINOR);
        }
	terminateRouting(false, false);
        // since we've added the ref
        return new AnnouncementDone(this);
    }
}









