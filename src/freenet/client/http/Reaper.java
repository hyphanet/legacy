/*
  This code is part of fproxy, an HTTP proxy server for Freenet.
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

package freenet.client.http;

import java.util.Vector;

class Reaper implements Runnable {

    protected Vector list = new Vector();
    protected volatile boolean run = true;
    protected int pollingIntervalMs = -1;

    public Reaper(int pollingIntervalMs) {
	this.pollingIntervalMs = pollingIntervalMs;
    }

    public void add(Reapable r) {
        if (r == null) {
            return; //hmmm...
        }

	synchronized (list) {
	    if (!list.contains(r)) {
		list.addElement(r);
	    }
	}
    }

    public void remove(Reapable r) {
	synchronized (list) {
	    if (list.contains(r)) {
		list.removeElement(r);
	    }
	    list.trimToSize();
	}
    }

    public void run() {
	//System.err.println("Reaper.run -- started");
	Vector expired = new Vector();
	try {
	    int i;
	    while(run) {
		expired.removeAllElements();

		// Make a list of objects that need to be reaped.
		synchronized(list) {
		    for (i = 0; i < list.size(); i++) {
			final Reapable r = (Reapable)list.elementAt(i);
			if (r.isExpired()) {
			    expired.addElement(r);
			    list.setElementAt(null, i);
			}
		    }
		    
                    i = 0;
		    while ((list.size() > 0) && (i < list.size())) {
			if (list.elementAt(i) == null) {
			    list.removeElementAt(i);
			    continue;
			}
			i++;
		    }
		    list.trimToSize();
		}
		
		// Reap them without holding a lock on list
		for (i = 0; i < expired.size(); i++) {
		    try {
			if (((Reapable)expired.elementAt(i)).reap()) {
			    expired.setElementAt(null, i);
			}
		    }
		    catch (Exception e) {
			// This is paranoia. reap() SHOULD NEVER THROW.
			System.err.println("Reaper.run -- ignored Exception: " + e);
		    }
		}
		
		synchronized(list) {
		    // Put back objects that couldn't be reaped.
		    for (i = 0; i < expired.size(); i++) {
			if (expired.elementAt(i) != null) {
			    list.addElement(expired.elementAt(i));
			}
		    }
 
		    list.wait(pollingIntervalMs);
		}
	    }
	}
	catch (InterruptedException ie) {
	    // NOP, busts us out of loop.
	}
	//System.err.println("Reaper.run -- exited");
    }
}




