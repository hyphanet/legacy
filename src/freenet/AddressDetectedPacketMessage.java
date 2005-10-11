package freenet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;

import freenet.presentation.MuxProtocol;
import freenet.support.Logger;
import freenet.support.io.WriteOutputStream;

/**
 * Message sent when we detect the other side of a connection's IP address.
 * Used for on-network IP address detection (for firewalled nodes).
 * @author amphibian
 */
class AddressDetectedPacketMessage extends AbstractNonTrailerPeerPacketMessage {
	
	// Addresses detected
	// Any not recognized from packet will be ignored.
	final Inet4Address ip4addr;
	
	final BaseConnectionHandler bch;
	
	byte[] content;
	
	/**
	 * @param peerHandler the PeerHandler this message came from.
	 */
	AddressDetectedPacketMessage(PeerHandler peerHandler,
	        BaseConnectionHandler bch, Inet4Address addr) {
		// 20 seconds
		super(peerHandler, 20000);
		this.ip4addr = addr;
		this.bch = bch;
	}

    /**
	 * @return null, or an Inet4Address.
	 */
	public Inet4Address getInet4Address() {
		return ip4addr;
	}
	
	public void notifyFailure(SendFailedException sfe) {
		// Oh well
	}
	
	public int getPriorityDelta() {
		return -5; // moderately important
	}
	
	public int getLength() {
		return getContent().length;
	}
	
    public AddressDetectedPacketMessage(FieldSet fs, MuxConnectionHandler mch) 
    	throws IOException {
        super(mch.peerHandler, 20000);
        this.bch = mch;
        String s = fs.getString("ip4");
        if(s == null) throw new IOException("no IPv4 address");
        // FIXME: should only accept IP addresses, not names
        ip4addr = (Inet4Address) Inet4Address.getByName(s);
    }

	private FieldSet getFieldSet() {
		// REDFLAG: For now, we only support IPv4 addresses.
		FieldSet fs = new FieldSet();
		fs.put("ip4", ip4addr.getHostAddress());
		return fs;
	}

	public byte[] getContent() {
		FieldSet fs = getFieldSet();
		if(content == null) {
		    ByteArrayOutputStream baos = new ByteArrayOutputStream();
		    try {
                fs.writeFields(new WriteOutputStream(baos));
            } catch (IOException e) {
                Core.logger.log(this, "Impossible: "+e, e, Logger.ERROR);
            }
		    byte[] buf = baos.toByteArray();
		    content = super.constructMessage(buf, 0, buf.length);
		}
	    return content;
	}

	public void notifySuccess(TrailerWriter tw) {
		// Don't care
	}

	public int getTypeCode() {
		return PeerPacketMessage.TYPE_DETECT;
	}

	public void resolve(Presentation p, boolean onlyIfNeeded) {
		if(!(p instanceof MuxProtocol))
			throw new IllegalArgumentException("Unsupported Presentation: "+p);
	}

	public void execute() {
	    bch.setDetectedAddress(ip4addr);
	}

	public boolean isRequest() {
		return false;
	}

    public boolean hasMRI() {
        return false;
    }

    public double sendingMRI() {
        return -1;
    }
}
