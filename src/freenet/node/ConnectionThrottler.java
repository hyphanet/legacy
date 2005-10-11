/*
 * Created on Feb 16, 2004
 *
 */
package freenet.node;

/**
 * @author Iakin
 *
 */
public interface ConnectionThrottler {
	//Returns true if any incoming connections currently should be rejected
	public boolean rejectingConnections();
}
