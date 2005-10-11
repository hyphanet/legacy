package freenet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import freenet.node.Main;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.presentation.MuxProtocol;
import freenet.support.Logger;
import freenet.support.io.WriteOutputStream;

/**
 * @author amphibian
 */
public class IdentifyPacketMessage extends AbstractNonTrailerPeerPacketMessage {

	private final NodeReference ref;
	private final MuxConnectionHandler mch;
	private final Node node;
	byte[] content = null;
	private final double requestInterval;
	private static boolean writeRequestInterval = true;
	private static final int MESSAGE_LIFETIME=90*1000;
	
	public IdentifyPacketMessage(NodeReference ref,PeerHandler peerHandler, MuxConnectionHandler mch, 
	        double requestInterval, Node node) {
		super(peerHandler,MESSAGE_LIFETIME);
		this.ref = ref;
		this.mch = mch;
		this.node = node;
		this.requestInterval = requestInterval;
	}
	
	public void resolve(Presentation p, boolean onlyIfNeeded) {
		if(!(p instanceof MuxProtocol))
			throw new IllegalArgumentException();
	}
	
	public void notifyFailure(SendFailedException sfe) {
		Core.logger.log(this, "Failed to send IdentifyPacketMessage: "+sfe,
				sfe, Logger.NORMAL);
		mch.peerHandler.revokeConnectionSuccess();
		mch.terminate();
	}

	public int getPriorityDelta() {
		return -5;
	}

	public int getLength() {
		// Hmmm
		//TODO: More effective implementation?
		//Unfortunately this might be somewhat hard when
		//we are relying on the output of a fieldset..
		return getContent().length;
	}

	public synchronized byte[] getContent() {
		if(content == null) {
			ByteArrayOutputStream baos = 
				new ByteArrayOutputStream(256);
			WriteOutputStream wos = new WriteOutputStream(baos);
			try {
			    FieldSet fs = ref.getFieldSet();
			    if(writeRequestInterval) {
			        if(requestInterval > 0.0) {
			            fs.put("RequestInterval", Double.toString(requestInterval));
			        }
			    }
				fs.writeFields(wos);
				if(Core.logger.shouldLog(Logger.DEBUG, this))
					Core.logger.log(this, "Content of "+this+" is:\n"+fs.toString(), Logger.DEBUG);
				wos.flush();
			} catch (IOException e) {
				Core.logger.log(this, "IOException writing "+ref+" to byte[]: "+e,
						e, Logger.ERROR);
				return null;
			}
			// REDFLAG: We only support the mux Presentation - should check
			byte[] buf = baos.toByteArray();
			content = constructMessage(buf, 0, buf.length);
		}
		return content;
	}

	public void notifySuccess(TrailerWriter tw) {
		// TODO Auto-generated method stub

	}

	public void execute() {
	    if(!Version.checkGoodVersion(ref.getVersion())) {
	        PeerHandler ph = mch.peerHandler;
	        ph.innerSendMessageAsync(new GoAwayPacketMessage(ph, Main.node));
	    }
		mch.setTargetReference(ref);
		node.rt.updateReference(ref);
		mch.peerHandler.enableSendingRequests();
	}

	public int getTypeCode() {
		return TYPE_IDENTIFY;
	}

	public boolean isRequest() {
		return false;
	}

    public boolean hasMRI() {
        return writeRequestInterval;
    }

    public double sendingMRI() {
        return requestInterval;
    }
}
