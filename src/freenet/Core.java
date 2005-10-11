/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import freenet.config.Config;
import freenet.config.Params;
import freenet.crypt.CryptoElement;
import freenet.crypt.Digest;
import freenet.crypt.RandomSource;
import freenet.crypt.RandomSourcePool;
import freenet.crypt.SHA1;
import freenet.crypt.ThrottledAsyncEntropyYarrow;
import freenet.diagnostics.AutoPoll;
import freenet.diagnostics.Diagnostics;
import freenet.diagnostics.VoidDiagnostics;
import freenet.interfaces.NIOInterface;
import freenet.node.ConnectionOpenerManager;
import freenet.node.states.request.RequestState;
import freenet.support.BufferLoggerHook;
import freenet.support.LoggerHookChain;
import freenet.support.FileLoggerHook;
import freenet.support.HexUtil;
import freenet.support.Irreversible;
import freenet.support.IrreversibleException;
import freenet.support.KeyHistogram;
import freenet.support.Logger;
import freenet.support.VoidLogger;
import freenet.transport.ListenSelectorLoop;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * This is a Wrapper object that contains the components of a Node in the
 * Adaptive Network.  It uses a Network object to communicate with other
 * Nodes in the Network.
 *
 * @author <A HREF="mailto:I.Clarke@strs.co.uk">Ian Clarke</A>
 * @author <a href="mailto:blanu@uts.cc.utexas.edu">Brandon Wiley</a>
 * @author oskar
 */

public class Core {

    private static final Config config = new Config();
    
    static {
        // System.err.println("Core.java static initialization start.");
        config.addOption("authTimeout",            1, 120000,  3100); // 120 sec
        config.addOption("connectionTimeout",      1, 600000, 3150); // 10 min
        config.addOption("hopTimeExpected",        1, 4000,   3200); // 4 sec
        config.addOption("hopTimeDeviation",       1, 7000,   3201); // 7 sec
        config.addOption("threadFactory",          1, "Y",    3249);
        config.addOption("targetMaxThreads",       1, -1,     3250);
        config.addOption("maximumThreads",         1, 120,    3251);
        config.addOption("tfTolerableQueueDelay",  1, 200,    3252);
		config.addOption("tfAbsoluteMaxThreads",   1, 500,    3253); // 500 threads
        config.addOption("blockSize",              1, 4096,   3300); // 4k
        config.addOption("streamBufferSize",       1, 16384,  3350); // 16 k
        config.addOption("maximumPadding",         1, 65536,  3400); // 64 k

        // authTimeout
        config.setExpert ("authTimeout", true);
        config.argDesc   ("authTimeout", "<millis>");
        config.shortDesc ("authTimeout", "timeout for crypto setup");
        config.longDesc  ("authTimeout",
                          "How long to wait for authentication before giving up (in milliseconds)"
                          );
        
        // connectionTimeout
        config.setExpert ("connectionTimeout", true);
        config.argDesc   ("connectionTimeout", "<millis>");
        config.shortDesc ("connectionTimeout", "timeout of idle connections.");
        config.longDesc  ("connectionTimeout",
                          "How long to listen on an inactive connection before closing",
                          "(if reply address is known)"                                        
                          );
        
        // hopTimeExpected
        config.setExpert ("hopTimeExpected", true);
        config.argDesc   ("hopTimeExpected", "<millis>");
        config.shortDesc ("hopTimeExpected", "average time for each hop in routing");
        config.longDesc  ("hopTimeExpected",
                          "The expected time it takes a Freenet node to pass a message.",
                          "Used to calculate timeout values for requests."               
                          );
        
        // hopTimeDeviation
        config.setExpert ("hopTimeDeviation", true);
        config.argDesc   ("hopTimeDeviation", "<millis>");
        config.shortDesc ("hopTimeDeviation", "std. devn. for hopTimeExpected");
        config.longDesc  ("hopTimeDeviation",
                          "The expected standard deviation in hopTimeExpected."
                          );
        
        // threadFactory
        config.setExpert ("threadFactory", true);
        config.argDesc   ("threadFactory", "<Q or F or Y or ...>");
        config.shortDesc ("threadFactory", "Select which implementation of ThreadFactory to use.");
        config.longDesc  ("threadFactory",
                          "Select which implementation of ThreadFactory to use.  " + 
                          "Q: QThreadFactory. " + 
                          "F: FastThreadFactory. " +
                          "Y: YetAnotherThreadFactory (default)."
                          );
        
        // targetMaxThreads
        config.setExpert ("targetMaxThreads", true);
        config.argDesc   ("targetMaxThreads", "<integer>");
        config.shortDesc ("targetMaxThreads", "target max. no. of threads in the pool");
        config.longDesc  ("targetMaxThreads",
                          "Target maximum number of threads in pool.  As this is approached," +
                          "the node will begin rejecting requests and possibly connections." +
                          "Default -1 means use value of maximumThreads parameter."
                          );

        // maximumThreads
        config.setExpert ("maximumThreads", true);
        config.argDesc   ("maximumThreads", "<integer>");
        config.shortDesc ("maximumThreads", "max. no. of threads in the pool");
        config.longDesc  ("maximumThreads",
                          "Should we use thread management?  If this number is defined and non-zero, " +
                          "this specifies the max number of threads in the pool.  If this is overrun, " +
                          "connections will be rejected and events won't execute on time.  " +
						  "Certain thread-consuming activities limit themselves to using a fraction of this value."
                          );
        
        // tfTolerableQueueDelay
        config.setExpert ("tfTolerableQueueDelay", true);
        config.argDesc   ("tfTolerableQueueDelay", "<milliseconds>");
        config.shortDesc ("tfTolerableQueueDelay", "delay before threadFactory starts making threads");
        config.longDesc  ("tfTolerableQueueDelay",
                          "Some thread factories may queue jobs for a short period before creating new threads, in case some active threads finish jobs in the mean time.  tfTolerableQueueDelay specifies how long jobs must wait before new threads are created for it.  If tfAbsoluteMaxThreads is specified, then this delay increases inversely with the number of allowed but not created threads.");
        
        // tfAbsoluteMaxThreads
        config.setExpert ("tfAbsoluteMaxThreads", true);
        config.argDesc   ("tfAbsoluteMaxThreads", "abs max # threads");
        config.shortDesc ("tfAbsoluteMaxThreads", "Absolute maximum number of threads");
        config.longDesc  ("tfAbsoluteMaxThreads",
                          "Thread factories which queue jobs before creating new threads can actually limit the number of threads.  The required wait time tfTolerableQueueDelay is multiplied by tfAbsoluteMaxThreads and divided by the number allowed but not created yet.  Thus, if 90% of the allowed threads exist, the delay is increased 10 fold.  If all of the allowed threads exist, no more threads will be created.  A value of zero means no limit.");
        
        // blockSize
        config.setExpert ("blockSize", true);
        config.argDesc   ("blockSize", "<bytes>");
        config.shortDesc ("blockSize", "size of byte blocks when copying data");
        config.longDesc  ("blockSize",
                          "What size should the blocks have when moving data?"
                          );

        // streamBufferSize
        config.setExpert ("streamBufferSize",true);
        config.argDesc   ("streamBufferSize","<bytes>");
        config.shortDesc ("streamBufferSize",
                          "The default size of stream buffers.");
        
        // maximumPadding
        config.setExpert("maximumPadding",true);
        config.argDesc("maximumPadding","<bytes>");
        config.shortDesc("maximumPadding","The max. bytes between messages");
        config.longDesc("maximumPadding",
                        "The maximum number of bytes of padding to allow between messages",
                        "and in Void messages.");

        // System.err.println("Core.java static initialization end.");
    }

    
    //=== static members and static initialization =============================

    /** whether classwide init() has occurred */
    protected static Irreversible initialized = new Irreversible(false);

    /** Time to wait on authentication reads */
    public static int authTimeout;
    
    /** The number of milliseconds to leave a silent connection open */ 
    public static int connectionTimeout;
    
    /** The Expected per Node time of a hop */
    public static int hopTimeExpected;
    
    /** The expected standard deviation from the per hop time */
    public static int hopTimeDeviation;
    
    /** The size used for memory buffers when copying data */
    public static int blockSize;
    
    /** The size used for stream buffers */
    public static int streamBufferSize = 16384;

    /** The maximum padding between messages */
    public static int maxPadding;

    /** The central PRNG */
    private static RandomSource randSource;
    
    /** The object that logs events */
    public static Logger logger = new VoidLogger(); //Start out with a dummy logger..
    
	private static FileLoggerHook loggerHook = null;
    
    public static PrintStream logStream = System.out;
    
    protected ListenSelectorLoop interfaceLoop;
    
    /** The diagnostics module */
    public static Diagnostics diagnostics = new VoidDiagnostics();
    // avoid NPEs in client code
    // running in another JVM
    /** Autopoll jobs for diagnostics */
    public static AutoPoll autoPoll = new AutoPoll(diagnostics, logger);

    /** Per host inbound contact monitoring **/
    public static ContactCounter inboundContacts = null;

    /** Per host inbound request monitoring. Note this includes all request types. **/
    public static ContactCounter inboundRequests = null;

    /** Per host outbound contact monitoring **/
    public static ContactCounter outboundContacts = null;

    /** Per host outbound request monitoring. Note this includes all request types. **/
    public static ContactCounter outboundRequests = null;

    /** Distribution of inbound DataRequests over the keyspace **/
    public static KeyHistogram requestDataDistribution = null;

    /** Distribution of inbound InsertRequests over the keyspace **/
    public static KeyHistogram requestInsertDistribution = null;

    /** Distribution of successful inbound DataRequests over the keyspace **/
    public static KeyHistogram successDataDistribution = null;

    /** Distribution of successful inbound InsertRequests over the keyspace **/
    public static KeyHistogram successInsertDistribution = null;

    /**
     * @return
     */
    public static Config getConfig() {

        try {
            // Enforce initialization of all of the freenet.node.Node
            // class's static fields and scopes.  freenet.node.Node
            // uses static initialization to set up some config
            // parameters.  This call forces this to happen *before*
            // the first instantiation of freenet.node.Node.  As of
            // now (2003-08-27) freenet.node.Main uses static
            // members of freenet.node.Node before the first
            // instantiation; that won't work without this call.
            // Have a look at Sun BugID 4419673 for details.  TODO:
            // Remove this hack when initialization order (or
            // something) is changed.
            // 2003-10-16 Experiments using println suggest that
            // Class.forName is redundant and Node.class.toString()
            // called early in Main.java works to run Node's static init.
            // System.err.println("Core.java about to call Class.forName()");
            Class.forName("freenet.node.Node"); 
            // System.err.println("Core.java finished call Class.forName()");

        } catch (ClassNotFoundException e) {
            //Really baaaad.. maybe someone renamed this class
            e.printStackTrace();
        }
        return config;
    }

    /**
     * Returns the probability of success of a request that would go into
     * a given bin for stats purposes
     */ /* fixme: can we optimize these somehow? */
    public static float pSuccess(int bin, boolean detail, boolean inserts)
    {
        int x = binRequest(bin, detail, inserts);
        int y = binSuccess(bin, detail, inserts);
        //if(x == 0) return 0;
        if (x <= (int)((requestDataDistribution.getTotal() / (binLength(detail)*3) + 1) ) )
            {
                return Float.NaN;
            }
        return ((float)y / (float)x);
    }

    public static int binRequest(int x, boolean detail, boolean inserts)
    {
        return bin(x, false, detail, inserts);
    }

    public static int binSuccess(int x, boolean detail, boolean inserts)
    {
        return bin(x, true, detail, inserts);
    }

    public static int bin(int x, boolean success, boolean detail, boolean inserts)
    {
        if(success)
            {
                if(detail) {
                    return inserts ? successInsertDistribution.getBiggerBin(x) :
                        successDataDistribution.getBiggerBin(x);
                } else {
                    return inserts ? successInsertDistribution.getBin(x) :
                        successDataDistribution.getBin(x);
                }
            } else {
                if(detail) {
                    return inserts ? requestInsertDistribution.getBiggerBin(x) :
                        requestDataDistribution.getBiggerBin(x);
                } else {
                    return inserts ? requestInsertDistribution.getBin(x) :
                        requestDataDistribution.getBin(x);
                }
            }
    }
    
    public static int binLength(boolean detail)
    {
        return detail ? requestDataDistribution.lengthBigger() :
            requestDataDistribution.length();
    }
    
    /** Returns the most successful bin
     */
    public static int binMostSuccessful(boolean detail, boolean inserts)
    {
        int ret = -1;
        float pHighest = 0;
        int iMax = binLength(detail);
        for(int i=0;i<iMax;i++)
            {
                int x = binRequest(i, detail, inserts);
                if(x>0)
                    {
                        float p = pSuccess(i, detail, inserts);
                        if(p>pHighest || 
                           (p == pHighest && ret != -1 && 
                            binRequest(i, detail, inserts) >
                            binRequest(ret, detail, inserts)))
                            {
                                pHighest = p;
                                ret = i;
                            }
                    }
            }
        return ret;
    }
    
    /**
     * Sets the logging object to be used for logging messages
     * @param l a Logger object that will log messages to an output stream.
     */
    public static void setLogger(Logger l) {
        logger = l;
    }

    /** @return  the global logger
     * I put this here for plugins to use .. if we redesign things
     * this might be better than having them all accessing Core.logger.
     */
    public static Logger getLogger() {
        return logger;
    }

    /**
     * Sets the core properties of Freenet.
     * @param params a Params object containing any command line or
     *                 config file settings. 
     * @throws CoreException  if Core was already initialized
     */
    public static void init(Params params) throws CoreException {
        try {
            initialized.change();
        } catch (IrreversibleException e) {
            throw new CoreException("already initialized");
        }
        authTimeout       = params.getInt("authTimeout");
        connectionTimeout = params.getInt("connectionTimeout");
        hopTimeExpected   = params.getInt("hopTimeExpected");
        hopTimeDeviation  = params.getInt("hopTimeDeviation");
        blockSize         = params.getInt("blockSize");
        streamBufferSize  = params.getInt("streamBufferSize");
        maxPadding        = params.getInt("maximumPadding");
    }


    //=== instance members =====================================================

    /** The node's cryptographic identity */
    public final Identity identity;
    /** And private key */
    public final Authentity privateKey;

    /** Registry of supported transports */
    public TransportHandler transports;
    /** Registry of supported session (link) layers */
    public SessionHandler sessions;
    /** Registry of supported protocols */
    public PresentationHandler presentations;

    /** Whether this Core has begun operating.
     * The timer, connections, and interfaces
     * are set when the Core is begun.
     */
    private final Irreversible begun = new Irreversible(false);

    /** The Ticker to schedule execution of MessageObjects with */
    private Ticker timer;

    /** Caches active connections (AFH) */
    public OpenConnectionManager connections;

    /** Opens new connections */
    public ConnectionOpenerManager connectionOpener;

    /** The interfaces this Core is listening on */
    public NIOInterface[] interfaces;

    /** The threads running the interfaces */
    //private Thread[] interfaceThreads;
    Thread interfaceLoopThread;
    
     private Object waitForBegin = new Object();

    /**
     * Create a new Freenet Core.
     * @param privateKey  The node's private key!
     * @param identity    And public key.
     * @param th  A TransportHandler registering the available transports.
     * @param sh  A SessionHandler registering the available sessions.
     * @param ph  A PresentationHandler registering the available protocols.
     */
    public Core(Authentity privateKey, Identity identity, 
                TransportHandler th, SessionHandler sh,
                PresentationHandler ph) {

		if(!initialized.state())
			throw new IllegalStateException("Not initialized yet!");

    	this.privateKey = privateKey;
        this.identity = identity;
        this.transports = th;
        this.sessions = sh;
        this.presentations = ph;

        logger.log(this, this.toString() + " (build "+Version.buildNumber+")",
                   Logger.MINOR);
    }

    /** @return  something for the logs.. */
    public String toString() {
        return "Freenet Core: " + HexUtil.bytesToHex(identity.fingerprint());
    }

    /**
     * Adopts the ticker for scheduling MessageObjects and starts
     * listening on the given interfaces.  Returns immediately.
     *
     * @see java.lang.Thread#setDaemon(boolean daemon)
     * @see Core#join()
     *
     * @param t       The ticker for scheduling of MOs.
     * @param ocm     The OpenConnectionManager to use.
     * @param inter   The interfaces to listen on.  May be null
     *                (FIXME: why? Even firewalled nodes...?).
     * @param daemon  The daemon-ness of the interface threads.
     * @throws CoreException  if enough threads couldn't be obtained
     *                        to run the ticker and all interfaces,
     *                        or if this Core was already begun once
     */
    public void begin(Ticker t,
                      OpenConnectionManager ocm, NIOInterface[] inter,
                      boolean daemon) throws CoreException {
        try {
            begun.change();
        } catch (IrreversibleException e) {
            throw new CoreException("already begun");
        }
        
        timer = t;
        connections = ocm;
        interfaces  = (inter == null ? new NIOInterface[0] : inter);
        
        logger.log(this, "Starting ticker..", Logger.NORMAL);
        Thread ticker = new Thread(timer, "Ticker");
        if (ticker == null)
            throw new CoreException("ran out of threads");
        ticker.setDaemon(true);
        ticker.setPriority(Thread.MAX_PRIORITY);
        ticker.start();

        try{

            interfaceLoop = new ListenSelectorLoop(logger, 
                    diagnostics.getExternalContinuousVariable("closePairLifetime"));
            interfaceLoopThread = new Thread (interfaceLoop," interface thread");

            logger.log(this, "Starting interfaces..", Logger.NORMAL); 
            for (int i = 0;i < interfaces.length; i++) {
            	try {
            		interfaces[i].register(interfaceLoop);
            	} catch (Throwable tt) {
            		logger.log(this, "Could not register interface "+interfaces[i]+
            				": caught "+tt, tt, Logger.ERROR);
            	}
            }

            logger.log(this, "starting ListenSelector..", Logger.NORMAL);
            interfaceLoopThread.start();
        }catch(IOException e) {System.err.println("couldn't create interfaces");e.printStackTrace();}

        beganTime = System.currentTimeMillis();
        synchronized(waitForBegin) {
            waitForBegin.notifyAll();
        }
    }
    
    public static long beganTime = -1;

    public boolean begun() {
        return begun.state();
    }

    /** Joins the current thread with all the interface threads,
     * meaning this method doesn't return until they all stop running.
     * @throws CoreException  if the interface threads don't exist yet
     *                        (Core not begun)
     */
    public void join() throws CoreException {
        /*if (interfaceThreads == null) throw new CoreException("Core not begun");
          try {
          for (int i=0; i<interfaceThreads.length; ++i)
          interfaceThreads[i].join();
          }*/
        if (interfaceLoopThread == null) throw new CoreException("Core not begun");
        while(true) {
            try {
                interfaceLoopThread.join();
            } catch (InterruptedException e) {
                if(interfaceLoopThread.isAlive()) continue;
            }
        }
    }

    /**
     * Wait for the Core to start
     */
    public void waitForBegin() {
        if(timer != null) return;
        synchronized(waitForBegin) {
            while(!begun.state()) {
                try {
                    waitForBegin.wait(1000);
                } catch (InterruptedException e) {
                    // Don't care
                }
            }
        }
    }
    
    /**
     * Stops all running interfaces and their threads
     * (join() will return).
     * @throws CoreException  if this Core wasn't begun yet
     */
    public void stop() throws CoreException {
        if (interfaces == null) throw new CoreException("Core not begun");
        //try{
        for (int i = 0 ; i < interfaces.length ; i++)
            interfaces[i].listen(false);
        //}catch(IOException e){System.err.println("couldn't stop interfaces");e.printStackTrace();}
    }

    /**
     * Sees if the Transport is supported by any available interface.
     */
    public boolean hasInterfaceFor(Transport t) {
        if (interfaces == null) throw new CoreException("Core not begun");
        for (int i = 0 ; i < interfaces.length ; i++) {
            if (interfaces[i].transport().equals(t))
                return true;
        }
        return false;
    }

    
    
    /**
     * Returns an open connection to a node, either by making a new one
     * taking one from the cache.
     * @param peer The Address of the other Freenet node/client
     * @return A ConnectionHandler that handles the new connection
     * @exception ConnectFailedException  if the Connection could not be opened
     *                                    or a new node did not respond to 
     *                                    handshake.
     */
    //     public final ConnectionHandler makeConnection(Peer p)
    //         throws CommunicationException {
    //         return makeConnection(p, 0);
    //     }
    
    /**
     * Returns an open connection to a node, or null if there aren't any
     * free. Will ONLY take them from the cache, will not create new ones.
     */
    //     public final ConnectionHandler getConnection(Peer p)
    //         throws CommunicationException {
    //         return connections.findFreeConnection(p.getIdentity());
    //     }
    
    /**
     * Returns an open connection to a node, either by making a new one
     * taking one from the cache.
     * @param peer     The peer to connect to.
     * @param timeout  The time to wait before throwing a 
     *                 ConnectFailedException when establishing new 
     *                 connections.
     */
    //     public final ConnectionHandler makeConnection(Peer p, long timeout) 
    //                                         throws CommunicationException {
    //         //if (connections == null)
    //         //    throw new CoreException("Core not begun");
    //         return connections.getConnection(this, p, timeout);
    //     }

    
    /**
     * Send the message using the appropriate protocol over a free or 
     * new connection.
     */
    public final TrailerWriter sendMessage(Message m, Peer p, long timeout) 
        throws SendFailedException {
        return connections.sendMessage(m, p.getIdentity(), null, timeout,
                                       PeerPacketMessage.NORMAL, presentations.getDefault());
    }

    // Digest for signatures
    private static final Digest ctx = SHA1.getInstance();

    /**
     * Signs a FieldSet using DSS.
     * @param fs     The FieldSet to sign.
     * @param field  The field name to insert the signature as in the FieldSet.
     *               If this is null then it is not inserted.
     * @return       The signature
     */
    public CryptoElement sign(FieldSet fs, String field) {
        byte[] b;
        synchronized(ctx) {
            if (field != null)
                fs.hashUpdate(ctx,new String[] {field});
            else
                fs.hashUpdate(ctx);
            b = ctx.digest();
        }
        
        CryptoElement sig = privateKey.sign(b);
        
        if (field != null)
            fs.put(field, sig.writeAsField());
        return sig;
    }

    /**
     * Signs a digest.
     * @param b  The digest to sign
     */
    public CryptoElement sign(byte[] b) {
        return privateKey.sign(b);
    }

    /**
     * @return  the Core's Ticker
     * @throws CoreException  if the Core hasn't begun yet
     */
    public final Ticker ticker() {
        if (timer == null) throw new CoreException("Core not begun");
        return timer;
    }

    /**
     * Schedule the MO to run on the ticker immediately.
     */
    public final void schedule(MessageObject mo) {
        if (timer == null) throw new CoreException("Core not begun");
        timer.add(0, mo);
    }
    
    /**
     * Run the MO if it can be done quickly, or schedule it on the ticker
     * @param runNowIfFast if true, run the message immediately if it will
     * run quickly.
     */
    public final void schedule(MessageObject mo, boolean runNowIfFast) {
        if(timer == null) throw new CoreException("Core not begun");
        timer.addNowOrRun(mo);
    }
    
    /**
     * Schedule the MO to run on the ticker after a delay.
     */
    public final void schedule(long millis, MessageObject mo) {
        if (timer == null) throw new CoreException("Core not begun");
        timer.add(millis, mo);
    }


    
    // statistical data
    // we might want to move this somewhere..

    /** 
     * @param extraTimePerHop	the queueing time per hop
     * @return The upper bound of a one sided 97.5% confidence interval
     *         for the time it should take to get a reply based on the
     *         the hopTimeExpected and hopTimeDeviation values from
     *         the config (and the assumption that the time is normal).
     *         In milliseconds.
     */
    public static final long hopTime(int htl, int extraTimePerHop) {
        return (long) (htl*(hopTimeExpected+extraTimePerHop) + 1.96*Math.sqrt(htl)*hopTimeDeviation);
    }
    
    /** @return  The expected time to get the StoreData after the
     *          Accepted/InsertReply is received (i.e. counting from
     *          when the transfer upstream is started).
     * Mostly used for inserts.
     * @param htl     the number of hops upstream of this node
     * @param length  the trailing-field length of the insert
     * @param extraTimePerHop	the queueing time per hop
     */
    public static final long storeDataTime(int htl, long length, int extraTimePerHop) {
        return 2*( hopTime(htl+1+RequestState.TIMEOUT_EXTRA_HTL, extraTimePerHop) + length );
        // roughly 1000 bytes/second
        // and totally ignoring covariances
        // and what have you, with a big
        // fat doubling......
    }
    /**
     * @return Returns the randSource.
     */
    public static RandomSource getRandSource() {
        //Hmmm.. I dont think we need to synchornize on this because:
        //1. Does it really matter if we would
        //2. Do we multithread as early as the first call here..
        if(randSource == null){
        	//Use a RandomSourcePool of 'ThrottledAsyncEntropyYarrow's instead of a single one
        	//in order to decrease the chance for lock contention on access to this very well-used resource
            randSource = new RandomSourcePool(new RandomSource[]{
				new ThrottledAsyncEntropyYarrow((new File("/dev/urandom").exists()? "/dev/urandom" : "prng.seed"), "SHA1", "Rijndael",true,20),
				new ThrottledAsyncEntropyYarrow((new File("/dev/urandom").exists()? "/dev/urandom" : "prng.seed"), "SHA1", "Rijndael",false,20),
				new ThrottledAsyncEntropyYarrow((new File("/dev/urandom").exists()? "/dev/urandom" : "prng.seed"), "SHA1", "Rijndael",false,20),
				new ThrottledAsyncEntropyYarrow((new File("/dev/urandom").exists()? "/dev/urandom" : "prng.seed"), "SHA1", "Rijndael",false,20),
				new ThrottledAsyncEntropyYarrow((new File("/dev/urandom").exists()? "/dev/urandom" : "prng.seed"), "SHA1", "Rijndael",false,20)
            });
        }
        return randSource;
    }

    public static void setupErrorLogger(String detail) {
		if(logger != null && !(logger instanceof VoidLogger))
			throw new IllegalStateException("Cannot set up logger, logger already set up");
		LoggerHookChain chl =new LoggerHookChain(); 
        loggerHook = new FileLoggerHook(
                System.err,
                "d (c, t, p): m",
                "",
                Logger.NORMAL);
		chl.addHook(loggerHook);
		chl.setThreshold(Logger.NORMAL);
        loggerHook.start();
        logger = chl;
		chl.setDetailedThresholds(detail);
    }
    
	public static void setupLogger(Params params,boolean dontCheck) {
		if(logger != null && !(logger instanceof VoidLogger))
			throw new IllegalStateException("Cannot set up logger, logger already set up");
		LoggerHookChain chl =new LoggerHookChain(); 

		String fname = params.getString("logFile");
		String logFormat = params.getString("logFormat");
		String logDate = params.getString("logDate");

		try {
			if (fname==null || fname.equalsIgnoreCase("NO") || fname.equalsIgnoreCase("STDERR") || fname.equalsIgnoreCase("STDOUT")) {
				PrintStream stream=System.err;
				if ( fname == null || fname.equalsIgnoreCase("NO") || fname.equalsIgnoreCase("STDERR") ) {
          			stream = System.err;
        		} else if (fname.equalsIgnoreCase("STDOUT")) {
          			stream = System.out;
          		}
				loggerHook =
					new FileLoggerHook(
						stream,
						logFormat,
						logDate,
						Logger.NORMAL);				
			} else {
				loggerHook =
					new FileLoggerHook(
						params.getBoolean("logRotate"),
						fname,
						logFormat,
						logDate,
						Logger.NORMAL,
						dontCheck,
						params.getBoolean("logOverwrite"));
				
				String logInterval = params.getString("logRotateInterval");
			    if (logInterval!=null) loggerHook.setInterval(logInterval);
			    
				int logMaxLinesCached = params.getInt("logMaxLinesCached");
				long logMaxBytesCached = params.getLong("logMaxBytesCached");
				loggerHook.setMaxListLength(logMaxLinesCached);
				loggerHook.setMaxListBytes(logMaxBytesCached);
				loggerHook.setUseNativeGzip(
					params.getBoolean("logRotateUseNativeGzip"));
			    
			}
			loggerHook.start();

			logStream = loggerHook.getStream();
		} catch (IOException e) {
			System.err.println("Opening log file failed!");
		}
		
		//Register filelogger with the chainlogger
		chl.addHook(loggerHook);
		// Add a buffering hook for the last couple of entries as well
		chl.addHook(new BufferLoggerHook(20));

		//Set initial loglevel, chain-logger will propagate settings to chain-items
		String thresh = params.getString("logLevel");
		String detailedLevels = params.getString("logLevelDetail");
		chl.setThreshold(thresh);
		chl.setDetailedThresholds(detailedLevels);

		logger = chl;
	}

	/**
	 * @return The number of log-bytes currently buffered for writing to disk
	 */
	public static long listLogBytes() {
		return loggerHook.listBytes();
	}

	public static void closeFileLogger() {
		loggerHook.close();
	}

    /**
     * @return whether we want a single incoming connection, right now.
     */
    public boolean wantIncomingConnection() {
        return true;
    }

    /**
     * Queue timeout. Here because there is no other obvious place to put it,
     * and hopTime is here.
     */
    public static int queueTimeout(int size, boolean isInsert, boolean isLocalAndFirstTime) {
		if(isLocalAndFirstTime) {
		    return 60*1000; 
		    // local requests that have not been tried get 60 seconds,
		    // hopefully this will prevent instant RNFs.
		} else {
		    // 10 secs/meg for requests, 20 secs/meg for inserts
		    int perMeg = 10000;
		    int timeout = (perMeg * size) / (1024 * 1024);
		    // Minimum 3 seconds
		    // Most successful requests take 10 hops, so this might cost up to 30 seconds
		    // However longer queueing means we get fewer RNFs network wide, so hopefully it will cost less than that.
		    if(timeout < 3000) timeout = 3000;
		    if(isInsert) timeout *= 2;
		    return timeout;
		}
    }
}



