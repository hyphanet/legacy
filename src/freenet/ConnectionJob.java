package freenet;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Hashtable;

import freenet.node.Node;
import freenet.session.Link;
import freenet.session.LinkManager;
import freenet.support.Logger;
import freenet.support.io.NIOInputStream;
import freenet.transport.tcpConnection;

////////////////////////////////////////////////////////////
public class ConnectionJob implements Runnable {

    /** One attempt to open a conn at once to each Peer */
    private final static Hashtable connectionJobs = new Hashtable();

    private static boolean logDEBUG = true;
    
    /**
     * Creates a new Connection which is started and added.
     * 
     * @param n
     *            The Node to connect from
     * @param p
     *            The Peer to connect to
     * @param timeout
     *            The number of millisseconds before returning
     *            ConnectFailedException - this does not necessarily mean that
     *            it won't eventually succeed, but we some threads may not wish
     *            to hang around to find out. 0 means no timeout. -1 means run
     *            the connect attempt on this thread, blocking.
     * @return A running BaseConnectionHandler
     * @exception ConnectFailedException
     *                if the connection fails or timeout runs out.
     */
    public static BaseConnectionHandler createConnection(Node n, Peer p, long timeout)
    throws CommunicationException {
        ConnectionJob ct = null;
        OpenConnectionManager ocm = n.connections;
        boolean updatedRefcount = false;
        boolean weStarted = false;
        try {
            synchronized (connectionJobs) {
                ct = (ConnectionJob) connectionJobs.get(p);
                if (ct != null && ct.done) {
                    connectionJobs.remove(p);
                    ct = null;
                }
                if (ct != null) {
                    if (logDEBUG)
                        Core.logger.log(
                                ConnectionJob.class,
                                "Got " + ct + ", waiting on it",
                                Logger.DEBUG);
                } else {
                    weStarted = true;
                    ct = new ConnectionJob(n, ocm, p);
                    connectionJobs.put(p, ct);
                    if (logDEBUG)
                        Core.logger.log(
                                ConnectionJob.class,
                                "Created new ConnJob: " + ct + " for " + p,
                                Logger.DEBUG);
                }
                updatedRefcount = true;
                ct.incRefcount();
            }
            synchronized (ct) {
                if (timeout == -1 && (!ct.done) && weStarted) {
                    if (logDEBUG)
                        Core.logger.log(
                                ConnectionJob.class,
                                "Starting " + ct + " on this thread",
                                Logger.DEBUG);
                    ct.run();
                } else {
                    if (weStarted) {
                        if (logDEBUG)
                            Core.logger.log(
                                    ConnectionJob.class,
                                    "Starting " + ct + " on another thread",
                                    Logger.DEBUG);
                        n.getThreadFactory().getThread(ct);
                    }
                    long endtime = System.currentTimeMillis() + timeout;
                    while (!ct.done) {
                        try {
                            if (timeout <= 0) {
                                ct.wait(5 * 60 * 1000);
                            } else {
                                long wait =
                                    endtime - System.currentTimeMillis();
                                if (wait <= 0)
                                    break;
                                ct.wait(wait);
                            }
                        } catch (InterruptedException e) {
                            // Go around again!
                        }
                    }
                }
                Core.diagnostics.occurrenceBinomial(
                        "connectionRatio",
                        1,
                        ct.ch == null ? 0 : 1);
                if (ct.ch != null)
                    return ct.ch;
                if (ct.e != null)
                    throw ct.e;
                if (timeout <= 0)
                    Core.logger.log(
                            ConnectionJob.class,
                            "Something is very wrong new connections for "
                            + p
                            + " ("
                            + ct
                            + "). Timeout was "
                            + timeout
                            + ")",
                            Logger.ERROR);
                if (logDEBUG)
                    Core.logger.log(
                            ConnectionJob.class,
                            "Failed to connect on " + ct.ch + " with " + ct,
                            new Exception("debug"),
                            Logger.DEBUG);
                throw new ConnectFailedException(
                        p.getAddress(),
                        p.getIdentity(),
                        "Timeout reached while waiting",
                        true);
            }
        } finally {
            if (ct != null && updatedRefcount)
                ct.decRefcount();
            if (ct != null && ct.done) {
                synchronized (connectionJobs) {
                    if (connectionJobs.get(p) == ct)
                        connectionJobs.remove(p);
                }
            }
        }
    }

    /**
     * Set HardConnectionLimiter to enable special debugging for the Curus Bug
     * (simultaneous connect attempts to the same node)!. TODO: set
     * VoidAddressReferenceCounter before release
     */
    private static final AddressReferenceCounter hardConnectionLimiter =
        new HardConnectionLimiter();

    //private final AddressReferenceCounter hardConnectionLimiter = new
    // VoidAddressReferenceCounter();

    private interface AddressReferenceCounter {

        public void inc(Identity id, Address addr) throws Exception;
        // Might throw if boundaries etc exceeded

        public void dec(Identity id, Address addr);
    }

    private static class VoidAddressReferenceCounter
    implements AddressReferenceCounter {

        public void inc(Identity id, Address addr) throws Exception {
            // Do nothing
        }

        public void dec(Identity id, Address addr) {
            // Do nothing
        }
    }

    ////////////////////////////////////////////////////////////
    // Class containing helper functions to enforce hard limits on the maximum
    // number of concurrent blocked connections we allow to
    // a single address.
    //
    private static class HardConnectionLimiter
    implements AddressReferenceCounter {

        // ** Stupid support class for mapping to an int */
        private static class myInt {

            int intValue = 0;

            myInt(int iVal) {
                intValue = iVal;
            }
        }

        private int blockedConnectionCount = 0;

        private Hashtable blockedConnections = new Hashtable();

        private static final int MAXBLOCKEDCONNECTIONS = 1;

        public final void inc(Identity id, Address addr) throws ConnectFailedException {
            logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
            myInt count;
            if (logDEBUG)
                Core.logger.log(this, 
                        "Increasing blocked connection count for "
                        + addr + ":" + id.toString(), Logger.DEBUG);
            synchronized (blockedConnections) {
                blockedConnectionCount++;
                count = (myInt) blockedConnections.get(id);
                if (count == null) {
                    count = new myInt(1);
                    blockedConnections.put(id, count);
                } else {
                    //Inc even if we'll pass the limit (tested for below) so that
                    // the arithmetic works when dec is called in finally block.
                    count.intValue++;
                    if ((count.intValue - 1) >= MAXBLOCKEDCONNECTIONS) {
                        // compare to the non-inc:d value
                        // This means the createConnection hashtable isn't
                        // working!
                        if (logDEBUG)
                            Core.logger.log(
                                    this,
                                    "Too many blocked connection, aborting: "
                                    + id
                                    + " "
                                    + count.intValue,
                                    Logger.ERROR);
                        // Terminal.
                        throw new ConnectFailedException(
                                addr, id,
                                "Exceeded blocked connection limit: "
                                + count.intValue
                                + " for "
                                + addr, false);
                    }
                }
            }
            if (logDEBUG)
                Core.logger.log(
                        this,
                        "Blocked: " + addr.toString() + ":" + id + " " + count.intValue,
                        Logger.DEBUG);
        }

        public final void dec(Identity id, Address addr) {
            logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
            if (logDEBUG)
                Core.logger.log(
                        this,
                        "Decreasing blocked connection count for "
                        + addr.toString()+":"+id,
                        Logger.DEBUG);
            synchronized (blockedConnections) {
                blockedConnectionCount--;
                myInt count = (myInt) blockedConnections.get(id);
                if (count != null) {
                    if (count.intValue > 0)
                        count.intValue--;
                    else
                        blockedConnections.remove(id);
                }
            }
        }
    }
    
    private boolean done = false;

    private BaseConnectionHandler ch = null;

    private CommunicationException e = null;

    private final Node node;
    
    private final OpenConnectionManager ocm;

    private final Peer p;

    private int refcount = 0;

    private void incRefcount() {
        synchronized (connectionJobs) {
            refcount++;
        }
    }

    private void decRefcount() {
        synchronized (connectionJobs) {
            refcount--;
            if (refcount <= 0) {
                if (connectionJobs.get(p) == this)
                    connectionJobs.remove(p);
            }
        }
    }

    public ConnectionJob(Node node, OpenConnectionManager ocm, Peer p) {
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        if (logDEBUG)
            Core.logger.log(
                    this,
                    "Creating ConnectionJob (core, " + p + "): " + this,
                    new Exception("debug"),
                    Logger.DEBUG);
        this.ocm = ocm;
        this.node = node;
        this.p = p;
    }

    public void run() {
        long start = System.currentTimeMillis();
        Connection c = null;
        boolean connected = false;
        int loops = 5;
        do {
            try {
                LinkManager linkManager = p.getLinkManager();
                Presentation presentation = p.getPresentation();
                try {
                    // IMPORTANT:
                    // The connect() call below can block for
                    // a long time before failing
                    // (3 minutes on rh7.1, IBM JDK 1.3).
                    // 
                    // Fail immediately if there are too
                    // many blocked connections for
                    // the requested address.
                    hardConnectionLimiter.inc(p.getIdentity(), p.getAddress());
                    c = p.getAddress().connect(false);
                    // Will be limited but only after enableThrottle()
                    // called by CH
                    // We do not throttle at this stage -
                    // it will be wrapped after negotiation
                } finally {
                    hardConnectionLimiter.dec(p.getIdentity(), p.getAddress());
                    if (Core.outboundContacts != null) {
                        Address countAddr = null;
                        if (c != null) {
                            // Address we connected to.
                            countAddr = c.getPeerAddress();
                        } else {
                            // Address we tried to connect to.
                            countAddr = p.getAddress();
                        }
                        // Keep track of outbound connection attempts.
                        if (countAddr != null) {
                            Core.outboundContacts.incTotal(
                                    countAddr.toString());
                        } else if (c != null) {
                            Core.outboundContacts.incTotal(
                            "c.getPeerAddress.null");
                        } else {
                            Core.outboundContacts.incTotal(
                            "p.getAddress.null");
                        }
                    }
                }
                if (Core.logger.shouldLog(Logger.MINOR, this))
                    Core.logger.log(
                            this,
                            "Established connection: " + c + "(" + this +")",
                            Logger.MINOR);
                OutputStream raw = c.getOut();
                if (raw == null)
                    throw new IOException("Connection already closed");
                int i = linkManager.designatorNum();
                raw.write((i >> 8) & 0xff);
                raw.write(i & 0xff);
                if (logDEBUG)
                    Core.logger.log(
                            this,
                            "Written, creating link: " + c + "(" + this +")",
                            Logger.DEBUG);
                //raw.flush(); don't want to flush here
                Link l =
                    linkManager.createOutgoing(
                            node.privateKey,
                            node.identity,
                            p.getIdentity(),
                            c);
                if (logDEBUG)
                    Core.logger.log(
                            this,
                            "Connection between: "
                            + l.getMyAddress()
                            + " and "
                            + l.getPeerAddress()
                            + " ("
                            + this
                            + ","
                            + c
                            + ")",
                            Logger.DEBUG);

                { //Extra scope to prevent scope variable pollution //TODO: Make this code snippet into a separate method
                    /* OutputStream */
                    OutputStream crypt = l.getOutputStream();
                    int j = presentation.designatorNum();
                    if (logDEBUG)
                        Core.logger.log(
                                this,
                                "Got OStream, writing control " + c,
                                Logger.DEBUG);
                    crypt.write((j >> 8) & 0xff);
                    crypt.write(j & 0xff);
                    if (logDEBUG)
                        Core.logger.log(
                                this,
                                "Written control bytes, flushing " + c,
                                Logger.DEBUG);
                    crypt.flush();
                }

                //crypt.flush(); or here, we might as well wait
                c.enableThrottle();
                ch =
                    presentation.createConnectionHandler(
                            ocm,
                            node,
                            l,
                            node.ticker(),
                            3,
                            Core.maxPadding,
                            true,
                            tcpConnection.getRSL(),
                            tcpConnection.getWSL());
                if (logDEBUG)
                    Core.logger.log(
                            this,
                            "Got BaseConnectionHandler: " + this +"->" + ch,
                            Logger.DEBUG);
                //runCh = true;
                //ch.start();
                if (!ch.isOpen())
                    throw new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            "Conn died sending Identify",
                            true);
                if (logDEBUG)
                    Core.logger.log(
                            this,
                            "configged WSL for " + ch + " (" + this +")",
                            Logger.DEBUG);
                // IIRC we don't use sustain anymore: FIXME -- amphibian
                //                     if (!core.hasInterfaceFor(ch.transport())) {
                //                         // if we don't have an interface for this transport, we
                //                         // will ask this connection to persist.
                //                         Message m = ch.presentationType().getSustainMessage();
                //                         if (m != null)
                //                             ch.sendMessage(m);
                //                     }
                long now = System.currentTimeMillis();
                long connectingTime = now - start;
                if (logDEBUG || connectingTime > 500)
                    Core.logger.log(
                            this,
                            "connectingTime: "
                            + connectingTime
                            + " at "
                            + now
                            + " ("
                            + this
                            + ")",
                            connectingTime > 500 ? Logger.MINOR : Logger.DEBUG);
                Core.diagnostics.occurrenceContinuous(
                        "connectingTime",
                        connectingTime);
                connected = true;
            } catch (java.net.SocketException ex) {
                //just log it
                if (Core.logger.shouldLog(Logger.MINOR, this))
                    Core.logger.log(
                            this,
                            "socket exception happened - "
                            + "probably NIOOS got closed before "
                            + "finishing: "
                            + ex,
                            ex,
                            Logger.MINOR);
                this.e =
                    new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            ex.getMessage(),
                            false);
            } catch (IOException ex) {
                // Only code path that causes us to loop.
                // All the other exceptions are terminal.
                // I don't understand the intent of the looping.
                // It seems like a very bad idea to me
                // i.e. DOSing on transport level connection failure
                // is exactly the wrong thing to do.
                //
                // However It's not an issue since this code never
                // appears to be executed.
                // --gj
                ex.printStackTrace();
                this.e =
                    new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            ex.getMessage(),
                            false);
                Core.logger.log(
                        this,
                        "[LOOPING (A)!]Transport level connect "
                        + "failed to: "
                        + p.getAddress()
                        + " -- "
                        + e
                        + " ("
                        + this
                        + ","
                        + c
                        + ","
                        + ch
                        + ")",
                        e,
                        Logger.NORMAL);
                //this.e, Logger.DEBUG);
            } catch (SendFailedException ex) {
                this.e = new ConnectFailedException(ex);
                e.setIdentity(p.getIdentity());
                if (logDEBUG)
                    Core.logger.log(
                            this,
                            "Transport level connect failed to: "
                            + p.getAddress()
                            + " -- "
                            + ex
                            + " ("
                            + this
                            + ","
                            + ch
                            + ")",
                            Logger.DEBUG);
                //this.e, Logger.DEBUG);
            } catch (ConnectFailedException ex) {
                this.e = ex;
                e.setIdentity(p.getIdentity());
                if (logDEBUG)
                    Core.logger.log(
                            this,
                            "Transport level connect failed to: "
                            + p.getAddress()
                            + " -- "
                            + e
                            + " ("
                            + this
                            + ","
                            + ch
                            + ")",
                            Logger.DEBUG);
                //e, Logger.DEBUG);
                // I'll attempt to fall back on an open connection.
                // I can't decide if this is a nice hack or an ugly hack..
                //ch = findBestConnection(p.getIdentity());
                //if (ch == null) {
                //    this.e = e;
                //} else {
                //    try {
                //        for (int j = 0 ; j < 5 && !ch.sending();
                //             j++) {
                //            Core.logger.log(this,
                //                            "Waiting for CH. Sending:" +
                //                            ch.sending() + " Count: " +
                //                            ch.sendingCount(),
                //                            Logger.DEBUG);
                //                            
                //            ch.sendMessage(null);
                //        }
                //    } catch (SendFailedException sfe) {
                //        this.e = sfe;
                //    }
                //}
            } catch (NegotiationFailedException ex) {
                this.e = ex;
                if (Core.logger.shouldLog(Logger.MINOR, this))
                    Core.logger.log(
                            this,
                            "Negotiation failed with: "
                            + p.getAddress()
                            + " -- "
                            + e
                            + " ("
                            + this
                            + ","
                            + ch
                            + ")",
                            Logger.MINOR);
                //e, Logger.MINOR);
            } catch (AuthenticationFailedException ex) {
                this.e = ex;
                if (Core.logger.shouldLog(Logger.MINOR, this))
                    Core.logger.log(
                            this,
                            "Authentication failed with: "
                            + p.getAddress()
                            + " -- "
                            + e
                            + " ("
                            + this
                            + ","
                            + ch
                            + ")",
                            Logger.MINOR);
                //e, Logger.MINOR);
                //} catch (IOException e) {
                //    Core.logger.log(OpenConnectionManager.this,
                //                    "I/O error during negotiation with: "
                //                    + p.getAddress(),
                //                    e, Logger.MINOR);
                //    this.e = e;
            } catch (Throwable t) {
                this.e =
                    new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            t.getMessage(),
                            true);
                Core.logger.log(
                        this,
                        "Unknown throwable "
                        + t
                        + " while connecting to: "
                        + p.getAddress()
                        + " ("
                        + this
                        + ","
                        + ch
                        + ")",
                        t,
                        Logger.ERROR);
            }
        } while (!connected && !e.isTerminal() && --loops > 0);
        if (connected) {
            if (logDEBUG)
                Core.logger.log(
                        this,
                        "Connected: " + p.getAddress() + " " + this +" : " + ch,
                        Logger.DEBUG);
            try {
                ch.registerOCM(); // AFTER flushOut() and configWSL
                if (logDEBUG)
                    Core.logger.log(
                            this,
                            "Flushed " + this +"," + ch,
                            Logger.DEBUG);
                java.net.Socket sock;
                try {
                    sock =
                        ((freenet.transport.tcpConnection) c).getSocket();
                    if (sock == null)
                        throw new IOException("Null socket");
                } catch (IOException ex) {
                    ch = null;
                    this.e =
                        new ConnectFailedException(
                                p.getAddress(),
                                p.getIdentity(),
                                ex.getMessage(),
                                false);
                    if (logDEBUG)
                        Core.logger.log(
                                this,
                                "Could not get socket! ("
                                + this
                                + ","
                                + ch
                                + ")",
                                ex,
                                Logger.DEBUG);
                    synchronized (this) {
                        done = true;
                        this.notifyAll();
                    }
                    return;
                }
                try {
                    java.nio.channels.SocketChannel sc = sock.getChannel();
                    if (logDEBUG)
                        Core.logger.log(
                                this,
                                "tcpConnection",
                                Logger.DEBUG);
                    sc.configureBlocking(false);
                } catch (IOException ex) {
                    Core.logger.log(
                            this,
                            "Cannot configure nonblocking mode on SocketChannel! ("
                            + this
                            + ","
                            + ch
                            + "): "
                            + e,
                            Logger.ERROR);
                    this.e =
                        new ConnectFailedException(
                                p.getAddress(),
                                p.getIdentity(),
                                ex.getMessage(),
                                false);
                    Core.logger.log(
                            this,
                            "[LOOPING (B)!]Transport level connect "
                            + "failed to: "
                            + p.getAddress()
                            + " -- "
                            + e
                            + " ("
                            + this
                            + ","
                            + c
                            + ","
                            + ch
                            + ")",
                            e,
                            Logger.ERROR);
                    //this.e, Logger.DEBUG);
                    synchronized (this) {
                        done = true;
                        this.notifyAll();
                    }
                    ch = null;
                    return;
                }
                NIOInputStream niois =
                    ((tcpConnection) c).getUnderlyingIn();
                if (niois == null)
                    throw new IOException("Already closed - wierd...");
                niois.switchReader(ch);
                
                if (niois.alreadyClosedLink()) {
                    Core.logger.log(this, "Already closed link, terminating and not registering: " + this + ": " + c + ": " + ch, Logger.MINOR);
                    c.close();
                    ch.terminate();
                    throw new IOException("Already closed link");
                }
                // is now unregistered, it will not be checked until we are
                // registered
                tcpConnection.getRSL().register(sock.getChannel(), ch);
                tcpConnection.getRSL().scheduleMaintenance(
                        sock.getChannel(),
                        ch);
                if (logDEBUG)
                    Core.logger.log(this, "Registered " + this + ":" + ch, Logger.DEBUG);
                //ch.run();
            } catch (IOException ex) {
                this.e =
                    new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            ex.getMessage(),
                            false);
                if (logDEBUG)
                    Core.logger.log(
                            this,
                            "Could not get socket! ("
                            + this
                            + ","
                            + ch
                            + ") - "
                            + ex,
                            ex,
                            Logger.DEBUG);
                synchronized (this) {
                    done = true;
                    this.notifyAll();
                }
                ch = null;
                return;
            } catch (RuntimeException ex) {
                // FIXME: is there a need for notification here?
                this.e =
                    new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            ex.getMessage(),
                            true);
                c.close();
                Core.logger.log(
                        this,
                        "Unhandled throwable while handling "
                        + "connection ("
                        + this
                        + ","
                        + ch
                        + ")",
                        e,
                        Logger.ERROR);
                synchronized (this) {
                    done = true;
                    this.notifyAll();
                }
                ch = null;
                throw ex;
            } catch (Error ex) {
                // FIXME: is there a need for notification here?
                this.e =
                    new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            ex.getMessage(),
                            true);
                Core.logger.log(
                        this,
                        "Unhandled throwable while handling "
                        + "connection ("
                        + this
                        + ","
                        + ch
                        + "): "
                        + ex,
                        ex,
                        Logger.ERROR);
                synchronized (this) {
                    done = true;
                    this.notifyAll();
                }
                ch = null;
                throw ex;
            }
        } else if (ch != null) {
            if (logDEBUG)
                Core.logger.log(
                        this,
                        "Failed connection (" + this +"), terminating " + ch,
                        Logger.DEBUG);
            ch.terminate();
            ch = null;
            if (e == null) {
                this.e =
                    new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            "null ch",
                            true);
            }
        } else if (c != null) {
            if (logDEBUG)
                Core.logger.log(
                        this,
                        "Failed connection (" + this +"), no ch, closing",
                        Logger.DEBUG);
            if (e == null) {
                this.e =
                    new ConnectFailedException(
                            p.getAddress(),
                            p.getIdentity(),
                            "null c",
                            true);
            }
            c.close();
        }
        // One way or another...
        synchronized (this) {
            done = true;
            this.notifyAll();
        }
        if (logDEBUG)
            Core.logger.log(
                    this,
                    "Notified " + this +":" + ch,
                    Logger.DEBUG);
    }
}

