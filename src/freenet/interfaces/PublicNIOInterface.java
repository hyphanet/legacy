package freenet.interfaces;

import freenet.*;
import freenet.node.ConnectionThrottler;
import freenet.thread.ThreadFactory;
import freenet.support.Logger;
import freenet.transport.*;

/**
 * A PublicInterface is connected to by other nodes and can limit the
 * number of simultaneous connections, and prune idle connections.
 *
 */
public final class PublicNIOInterface extends NIOInterface {

    /**  Description of the Field */
    protected ConnectionThrottler conThrottler;
    /**  Description of the Field */
    protected ThreadFactory tf;
    /**  Description of the Field */
    protected ConnectionRunner runner;
    /**  Description of the Field */
    protected ContactCounter inboundContacts;

    /**  Description of the Field */
    protected volatile int activeConnections = 0;
    
    /**
     * @param  listenAddr           address to listen on
     * @param  conThrottler         used for connection limiting
     * @param  tf                   the ThreadFactory for connection threads
     * @param  runner               handles the connection thread
     * @param  inboundContacts      counter for incoming contacts. Can be null.
     * @exception  ListenException  Description of the Exception
     */
    
    public PublicNIOInterface(ListeningAddress listenAddr, ConnectionThrottler conThrottler,
                           ThreadFactory tf,
                           ConnectionRunner runner,
                           ContactCounter inboundContacts,
						   String symbolicName)
        throws ListenException {
        super(listenAddr,symbolicName);
	try {
	    this.listener = new tcpNIOListener((tcpListeningAddress)listenAddr);
	} catch (ListenException e) {
	    throw e;
	}
        this.conThrottler = conThrottler;
        this.tf = tf;
        this.runner = runner;
        this.inboundContacts = inboundContacts;
    }


    /**
     *  Description of the Method
     *
     * @param  conn                             Description of the Parameter
     * @exception  RejectedConnectionException  Description of the Exception
     */
    protected void dispatch(Connection conn) throws RejectedConnectionException {
    	//Core.logger.log(this, "dispatching publicInterface connection",Logger.NORMAL);

	final String threadPrefix = "Getting thread to dispatch in ";
	final String elsePrefix = "Recording inboundContacts.incSuccesses in ";
	final String contactsPrefix = "Recording inboundContacts.incTotal in ";
	final String diagnosticsPrefix = "Diagnostics in ";
	final String logMessage = "PublicInterface took more than 10 seconds! If this happens frequently, report it to devl@freenetproject.org.";

	long start = System.currentTimeMillis();
        Core.diagnostics.occurrenceCounting("inboundConnectionsDispatched", 1);

        if (inboundContacts != null) {
            inboundContacts.incTotal(conn.getPeerAddress().toString());
        }

	long timeDoneInboundContacts = System.currentTimeMillis();

	if (timeDoneInboundContacts - start > 10000)
	    Core.logger.log(this, contactsPrefix+logMessage+" Waited "+
			    (timeDoneInboundContacts-start)+" millis.",
			    new Exception("debug"), Logger.NORMAL);

        if (conThrottler.rejectingConnections()) {
	    Core.diagnostics.occurrenceCounting("inboundConnectionsThreadLimitRej",
						1);
            Core.diagnostics.occurrenceBinomial("inboundConnectionRatio",1,0);
            throw new RejectedConnectionException("thread limit reached");
        }

        Core.diagnostics.occurrenceCounting("inboundConnectionsAccepted", 1);

	long timeDoneDiagnostics = System.currentTimeMillis();

	if (timeDoneDiagnostics - timeDoneInboundContacts > 10000)
	    Core.logger.log(this, diagnosticsPrefix+logMessage+" Waited "+
			    (timeDoneDiagnostics - timeDoneInboundContacts)+
			    " millis.", new Exception("debug"),
			    Logger.NORMAL);

        if (inboundContacts != null) {
            inboundContacts.incSuccesses(conn.getPeerAddress().toString());
        }

	long time = System.currentTimeMillis();
	if(time - start > 10000)
	    Core.logger.log(this, elsePrefix+logMessage+" Waited "+
			    (time-start)+" millis.", new Exception("debug"),
			    Logger.NORMAL);
        tf.getThread(new ConnectionShell(conn));
	long x = System.currentTimeMillis();
	if(x-10000 > time)
	    Core.logger.log(this, threadPrefix+logMessage+" Waited "+(x-time)+
			    " millis.", new Exception("debug"),
			    Logger.NORMAL);
    }

    protected class ConnectionShell implements Runnable {
        /**  Description of the Field */
        protected final Connection conn;


        /**
         *Constructor for the ConnectionShell object
         *
         * @param  conn  Description of the Parameter
         */
        protected ConnectionShell(Connection conn) {
            this.conn = conn;
            if(conn == null) throw new NullPointerException();
        }


        /**  Main processing method for the ConnectionShell object */
        public void run() {
	    String contactName = "null";
            try {
                if (inboundContacts != null) {
		    contactName = conn.getPeerAddress().toString();
                    inboundContacts.incActive(contactName);
                }

                Core.diagnostics.occurrenceCounting("incomingConnections", 1);
                runner.handle(conn);
            } catch (RuntimeException t) {
                Core.logger.log(PublicNIOInterface.this,
                               "Unhandled throwable while handling connection",
                                t, Logger.ERROR);
                conn.close();
                throw t;
            } catch (Error e) {
                conn.close();
                throw e;
            } finally {

                if (inboundContacts != null) {
		    inboundContacts.decActive(contactName);
                }

                // FIXME.  this is too soon.
                // but hey, before it wasn't being called at all :)
                Core.diagnostics.occurrenceCounting("incomingConnections", -1);
            }
        }
    }

    protected void starting() {
	runner.starting();
    }

}


