package freenet.client.http;

import javax.servlet.http.HttpServletRequest;

import freenet.Connection;


/**
 * Helper class used to check whether the 
 * connection a HttpServletRequest came in on has been 
 * dropped.
 *
 * @author     <a href="mailto:giannijohansson@attbi.com">Gianni Johansson</a>
 **/
class ConnectionPoller {

    private Connection conn = null;
    private int intervalMs = 60000;
    private Runnable notifiable = null;

    private Thread pollingThread = null;

    /**
     * Polls the connection the request came in on every 
     * intervalMs milliseconds and invokes notifiable if 
     * the connection is dropped.
     **/
    ConnectionPoller(HttpServletRequest req, int intervalMs, Runnable notifiable) {
        Connection c = null;

        // This depends on a hack the constructor of
        // freenet.support.servlet.ServletRequestImpl.
        c = (Connection)req.getAttribute("freenet.Connection");
        if (c != null) {
            if (intervalMs < 5000) {
                throw new IllegalArgumentException("Can't poll that fast: " + intervalMs);
            }
            this.conn = c;
            this.notifiable = notifiable;
            this.intervalMs = intervalMs;
            
            pollingThread = new PollingThread();
            pollingThread.start();
        }
        else {
            System.err.println("ConnectionPoller.<ctr> -- Couldn't get Connection from request.");
        }
    }
    /**
     * Stops polling.
     **/
    synchronized void stop() {
        if (pollingThread != null) {
            Thread ref = pollingThread;
            pollingThread = null;
            ref.interrupt();
        }
    }

    // Hack to detect when the client has dropped the connection. 
    private final boolean connectionDropped() {
	return conn.isOutClosed();
    }

    class PollingThread extends Thread {
        public void run() {
            boolean dropped = false;
            synchronized(ConnectionPoller.this) {
                try {
                    while (pollingThread != null) {
                        if (connectionDropped()) {
                            dropped = true;
                            break;
                        }
                        ConnectionPoller.this.wait(intervalMs);
                    }
                }
                catch (InterruptedException ie) {
                    // NOP. Breaks out of loop
                }
                catch (Exception e) {
                    System.err.println("Unexpected exception:");
                    e.printStackTrace();
                }
                finally {
                    pollingThread = null;
                }
            }
            // Unlocked scope.
            if (dropped) {
                notifiable.run();
            }
        }
    }
}
