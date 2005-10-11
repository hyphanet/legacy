package freenet;

import java.util.Vector;

import freenet.node.Node;
import freenet.support.Logger;

class PeerPacket {
    Presentation p;

    final PeerPacketMessage[] messages;

    byte[] data; // plaintext. Encrypt at send time.

    int sentBytes;

    boolean hasTrailer = false;

    boolean hasCloseMessage = false;

    boolean mux = false;

    int priority;

    public String toString() {
        return super.toString() + ": " + (messages == null ? "(null)" : messages.length+"") + " msgs, "
                + (data == null ? "(null)" : "(" + data.length + " bytes)")
                + (hasTrailer ? "(trailer)" : "") + ", sentBytes=" + sentBytes
                + ", prio=" + priority;
    }

    /**
     * Create a PeerPacket
     */
    PeerPacket(PeerPacketMessage[] msgs, Presentation p,
            PeerHandler ph, boolean mux) {
        this.mux = mux;
        boolean logMINOR = Core.logger.shouldLog(Logger.MINOR,this);
        int prio = freenet.transport.WriteSelectorLoop.MESSAGE + 1;
        sentBytes = 0;
        int totalLength = 0;
        boolean hasMRI = false;
        Vector v = new Vector(msgs.length + 2);
        for (int i = 0; i < msgs.length; i++) {
            PeerPacketMessage m = msgs[i];
            m.resolve(p, false);
            totalLength += m.getLength();
            if (m.hasTrailer()) {
                if ((!mux) && i != (msgs.length - 1))
                        throw new IllegalArgumentException(
                                "trailers can only be attached to the LAST message!: msg "
                                        + i + " of " + msgs.length + " (" + m
                                        + ") has trailer");
                hasTrailer = true;
            }
            if (m.isCloseMessage()) hasCloseMessage = true;
            prio += m.getPriorityDelta();
            if (m.hasMRI()) hasMRI = true;
            v.add(m);
        }
        if (mux) {
            if (!hasMRI) {
                MRIPacketMessage m = ph.mriPacketMessage();
                if(m != null) {
                m.resolve(p, false);
                v.add(m);
                totalLength += m.getLength();
            }
            }
            // Now pad it
            int targetLength = Node.padPacketSize(totalLength);
            if (targetLength == totalLength) {
                // Don't need to pad it ! Cool !
                if(logMINOR) Core.logger.log(this, "Didn't need to pad "+super.toString()+": "+
                        totalLength+" bytes, "+msgs.length+" messages", Logger.MINOR);
            } else {
                targetLength = Node.padPacketSize(totalLength + 4); // void overhead
                // Create a low-level void message of the appropriate length
                int voidLength = (targetLength - totalLength) - 4;
                if(logMINOR) Core.logger.log(this, "Need to pad "+totalLength+" bytes out to "+targetLength+
                        ", creating void packet of length "+voidLength+" for "+msgs.length+
                        " messages", Logger.MINOR);
                VoidPacketMessage msg = new VoidPacketMessage(voidLength, ph);
                v.add(msg);
                totalLength = targetLength;
                Core.diagnostics.occurrenceCounting("outputBytesPaddingOverhead", voidLength + 4);
            }
        }
        data = new byte[totalLength];
        int x = 0;
        messages = new PeerPacketMessage[v.size()];
        for (int i = 0; i < v.size(); i++) {
            PeerPacketMessage m = (PeerPacketMessage)(v.get(i));
            messages[i] = m;
            byte[] msgBytes = m.getContent();
            System.arraycopy(msgBytes, 0, data, x, msgBytes.length);
            x += msgBytes.length;
        }
        if(x != totalLength) throw new IllegalStateException("Inconsistent length: totalLength="+
                totalLength+", x="+x);
        priority = prio;
    }

    public int getLength() {
        return data.length;
    }

    /**
     * @return the actual packet bytes, after encryption
     */
    public byte[] getBytes() {
        return data;
    }

    public boolean hasTrailer() {
        return hasTrailer;
    }

    public long trailerLength() {
        return messages[messages.length - 1].trailerLength();
    }

    public boolean hasCloseMessage() {
        return hasCloseMessage;
    }

    public int countMessages() {
        return messages.length;
    }

    public int priority() {
        return priority;
    }

    /**
     * Notify clients of message send completion
     * 
     * @param finished
     *            whether the packet has finished sending. If false, we are
     *            still sending it.
     * @param successfullySentBytes
     *            the number of bytes successfully sent. Can be zero. Bytes
     *            after this number might have been sent, but we got an error
     *            and can't be sure.
     * @param tw
     *            the TrailerWriter attached to the final message, if
     *            necessary.
     */
    public void jobDone(boolean finished, int successfullySentBytes,
            Peer sentTo, TrailerWriter tw, PeerHandler ph) {
        int msgStartOffset = 0;
        int prevMsgStartOffset = 0;
        boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        if (logDEBUG)
                Core.logger.log(this, this.toString() + ".jobDone(" + finished
                        + "," + successfullySentBytes + "," + sentTo + "," + tw
                        + ")", Logger.DEBUG);
        // Could unfold this a bit by keeping the offset in the array on the
        // object
        PeerPacketMessage prev = null;
        for (int i = 0; i < (messages.length + 1); i++) {
            if (logDEBUG)
                    Core.logger.log(this, "Message " + i + ": " + "prev="
                            + prev + ", msgStartOffset=" + msgStartOffset
                            + ", prevMsgStartOffset=" + prevMsgStartOffset
                            + ", finished=" + finished + " for " + this,
                            Logger.DEBUG);
            if ((!finished) && msgStartOffset > successfullySentBytes) {
                // msgStartOffset - if the prev message ends after where we
                // have sent up to, we can't do anything with it
                if (logDEBUG)
                        Core.logger.log(this, "Finished loop in jobDone on "
                                + this, Logger.DEBUG);
                prev = null;
                break;
            }
            if (msgStartOffset < sentBytes) {
                // If the prev message ends before the bytes we sent last time
                // end, we have already processed it
                prev = i >= messages.length ? null : messages[i];
                prevMsgStartOffset = msgStartOffset;
                msgStartOffset += prev == null ? 0 : prev.getLength();
                if (logDEBUG)
                        Core.logger.log(this, "Skipping " + i
                                + " in jobDone on " + this
                                + ", successfullySentBytes="
                                + successfullySentBytes, Logger.DEBUG);
                continue;
            }
            // msgStartOffset >= sentBytes
            if (msgStartOffset == sentBytes) {
                // Last time sent a whole message and no more
                // Last message is dealt with
                if (logDEBUG)
                        Core.logger.log(this, "Prev message " + prev
                                + " dealt with in jobDone on " + this,
                                Logger.DEBUG);
            } else {
                if (logDEBUG)
                        Core.logger.log(this, "Didn't finish last message for "
                                + this + ": " + prev, Logger.DEBUG);
                // msgStartOffset > sentBytes
                // Last message was not dealt with last time
                // prev != null because msgStartOffset > 0
                int messageLength = prev.getLength();
                int messageBytesSent = successfullySentBytes - prevMsgStartOffset;
                if (logDEBUG)
                        Core.logger.log(this, "prevLen = " + messageLength
                                + ", successfullySentBytes="
                                + successfullySentBytes + ", msgStartOffset="
                                + msgStartOffset + ", prevMsgStartOffset="
                                + prevMsgStartOffset + ", diff=" + messageBytesSent
                                + ", finished=" + finished, Logger.DEBUG);
                if (finished || messageBytesSent >= messageLength) {
                    if (messageBytesSent >= messageLength) {
                        if (mux && prev.hasTrailer()) {
                            // Can't use the provided trailerwriter, there won't be one,
                            // so create one
                            TrailerWriter t = ph.trailerWriteManager
                                    .makeTrailerWriter(prev.trailerMuxCode(), prev.wasInsert());
                            prev.notifySuccess(t);
                        } else
                            prev.notifySuccess(tw);
                    } else {
                        String excuse = "Sent " + (messageBytesSent >= 0 ? messageBytesSent : 0)
                                + " bytes ("
                                + (getLength() - successfullySentBytes)
                                + " of packet in notifyDone";
                        SendFailedException sfe = new SendFailedException(
                                sentTo.getAddress(), sentTo.getIdentity(),
                                excuse, false);
                        prev.notifyFailure(sfe);
                    }
                }
            }
            prev = i >= messages.length ? null : messages[i];
            prevMsgStartOffset = msgStartOffset;
            msgStartOffset += prev == null ? 0 : prev.getLength();
        }
        sentBytes = successfullySentBytes;
        if (logDEBUG)
                Core.logger.log(this, toString() + ".jobDone finished",
                        Logger.DEBUG);
    }

    /**
     * @return true if any message on this packet carries an MRI.
     */
    public boolean messagesWithMRI() {
        for (int i = 0; i < messages.length; i++) {
            if (messages[i].hasMRI()) return true;
        }
        return false;
    }
}