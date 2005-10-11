/*
 * Created on Feb 17, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.transport.test;

import freenet.node.ConnectionThrottler;


public class SimpleConnectionThrottler implements ConnectionThrottler {
	private boolean shouldReject = false;
	public void setShouldReject(boolean shouldReject) {
		this.shouldReject = shouldReject;
	}
	public boolean rejectingConnections() {
		return shouldReject;
	}
}