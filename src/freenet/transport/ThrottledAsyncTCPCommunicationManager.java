/*
 * Created on Mar 22, 2004
 *
 */
package freenet.transport;

import freenet.support.io.Bandwidth;

/**
 * @author Iakin
 */
public interface ThrottledAsyncTCPCommunicationManager extends AsyncTCPCommunicationManager {
	public void putBandwidth(int bytes);
	public void setBandwidth(Bandwidth bw);
	public long getTotalTransferedThrottlableBytes();
	public long getTotalTransferedPseudoThrottlableBytes();
	public int throttleQueueLength();
}
