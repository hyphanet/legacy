package freenet.node.states.announcing;

import java.io.IOException;
import java.io.OutputStream;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Key;
import freenet.Message;
import freenet.MessageObject;
import freenet.TrailerWriterOutputStream;
import freenet.message.Accepted;
import freenet.message.AnnouncementExecute;
import freenet.message.AnnouncementFailed;
import freenet.message.AnnouncementReply;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.states.announcement.NoComplete;
import freenet.node.states.announcement.NoReply;
import freenet.support.KeyList;
import freenet.support.Logger;

/**
 * This phase initiates the second phase of the announcement when we get the
 * AnnouncementReply message back.
 * 
 * @author oskar
 */

public class ExecuteAnnouncement extends AnnouncingState {

    private NoReply nr;

    private boolean accepted = false;

    ExecuteAnnouncement(SendAnnouncement sa, NoReply nr) {
        super(sa);
        this.nr = nr;
    }

    public String getName() {
        return "Execute My Node Announcement";
    }

    public boolean receives(MessageObject mo) {
        if (mo instanceof NoReply) return nr == mo;
        else if (mo instanceof Message) return target.getIdentity().equals(
                ((Message) mo).peerIdentity());
        else
            return false;
    }

    public State receivedMessage(Node n, AnnouncementFailed af) {
        nr.cancel();
        if (af.getReason() == AnnouncementFailed.KNOWN_ANNOUNCEE) {
            Core.logger.log(this, "Announcement failed: " + af.reasonName(),
                    Logger.NORMAL);
            signalFailed(n, true, af.reasonName());
            return null;
            // don't reduce the HTL
        }
        Core.logger.log(this,
                "Announcement attempt failed: " + af.reasonName(),
                Logger.NORMAL);

        signalFailed(n, false, af.reasonName());
        return null;
    }

    public State receivedMessage(Node n, QueryRejected mo) {
        nr.cancel();
        Core.logger.log(this, "Announcement attempt failed: " + mo,
                Logger.MINOR);

        signalFailed(n, false, mo.toString());

        return null;
        // No reason to reduce HTL, target was overloaded
    }

    public State receivedMessage(Node n, NoReply noReply) 
    throws BadStateException {
        if (noReply != this.nr) throw new BadStateException("Not my NoReply");
        
        Core.logger.log(this, "Announcement attempt failed, no reply from: "
                + target, Logger.MINOR);

        signalFailed(n, false, nr.toString());

        return null;
    }

    public State receivedMessage(Node n, Accepted a) throws BadStateException {
        if (accepted)
                throw new BadStateException("Received a second accepted");
        nr.cancel();
        accepted = true;
        nr = new NoReply(id);
        // We give it a lot time, since noreply is terminal
        n.schedule(3 * getTime(hopsToLive), nr);
        return this;
    }

    public State receivedMessage(Node n, QueryRestarted a) {
        nr.cancel();
        nr = new NoReply(id);
        n.schedule(3 * getTime(hopsToLive), nr);
        return this;
    }

    public State receivedMessage(Node n, AnnouncementReply ar) {
        nr.cancel();

        byte[] resultVal = ar.getReturnValue();
        if (resultVal.length < myVal.length) {
            // nullpad
            byte[] tmp = new byte[myVal.length];
            System.arraycopy(resultVal, 0, tmp, 0, resultVal.length);
            resultVal = tmp;
        }

        for (int i = 0; i < myVal.length; i++) {
            resultVal[i] ^= myVal[i];
        }

        KeyList kl = new KeyList(new byte[][]{myVal});
        String sign = n.sign(resultVal).writeAsField();

        //System.err.println("LALA TOTAL: " +
        //                   freenet.support.HexUtil.bytesToHex(resultVal));

        AnnouncementExecute ae = new AnnouncementExecute(id, kl, sign);
        OutputStream out;
        try {
            out = new TrailerWriterOutputStream(n.sendMessage(ae, target,
                    3 * getTime(hopsToLive)));
        } catch (CommunicationException e) {
            Core.logger.log(this, "Sending AnnouncementExecute failed", e,
                    Logger.MINOR);
            // Error sending
            signalFailed(n, false, e.toString());
            return null;
        }
        try {
            kl.writeTo(out);
        } catch (IOException e) {
            Core.logger.log(this,
                    "Appending my value to AnnouncementExecute failed", e,
                    Logger.MINOR);
            signalFailed(n, false, e.toString());
            return null;
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                Core.logger.log(this, "Error closing "+out+": "+e,
                        e, Logger.NORMAL);
            }
        }

        NoComplete nc = new NoComplete(id);
        n.schedule(3 * getTime(hopsToLive), nc);
        return new CompleteAnnouncement(this, new Key(resultVal), nc);
    }
}
