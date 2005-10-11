/*
 * Created on Feb 17, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.transport.test;

import freenet.Connection;
import freenet.interfaces.ConnectionRunner;


public class SimpleConnectionRunner implements ConnectionRunner {
	Connection conn;
	public void handle(Connection conn) {
		this.conn = conn;
	}
	public void starting() {
	}
	public boolean needsThread() {
		return false;
	}
}