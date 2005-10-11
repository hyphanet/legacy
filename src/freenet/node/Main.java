package freenet.node;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.Random;
import java.util.Vector;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import freenet.Address;
import freenet.Authentity;
import freenet.BadAddressException;
import freenet.ContactCounter;
import freenet.Core;
import freenet.DSAAuthentity;
import freenet.FieldSet;
import freenet.Identity;
import freenet.KeepaliveSender;
import freenet.Key;
import freenet.ListenException;
import freenet.ListeningAddress;
import freenet.MessageHandler;
import freenet.OpenConnectionManager;
import freenet.PresentationHandler;
import freenet.SessionHandler;
import freenet.Ticker;
import freenet.TransportHandler;
import freenet.Version;
import freenet.client.BackgroundInserter;
import freenet.client.FECTools;
import freenet.client.http.FproxyServlet;
import freenet.config.Config;
import freenet.config.Params;
import freenet.config.Setup;
import freenet.crypt.BlockCipher;
import freenet.crypt.DSAGroup;
import freenet.crypt.Global;
import freenet.crypt.Util;
import freenet.diagnostics.AutoPoll;
import freenet.diagnostics.Diagnostics;
import freenet.diagnostics.DiagnosticsCategory;
import freenet.diagnostics.DiagnosticsCheckpoint;
import freenet.diagnostics.DiagnosticsException;
import freenet.diagnostics.StandardDiagnostics;
import freenet.fs.dir.Buffer;
import freenet.fs.dir.Directory;
import freenet.fs.dir.FileNumber;
import freenet.fs.dir.LossyDirectory;
import freenet.fs.dir.NativeFSDirectory;
import freenet.interfaces.ConnectionRunner;
import freenet.interfaces.FreenetConnectionRunner;
import freenet.interfaces.LocalNIOInterface;
import freenet.interfaces.NIOInterface;
import freenet.interfaces.PublicNIOInterface;
import freenet.interfaces.Service;
import freenet.interfaces.ServiceException;
import freenet.interfaces.ServiceLoader;
import freenet.interfaces.servlet.SingleHttpServletContainer;
import freenet.message.Accepted;
import freenet.message.AnnouncementComplete;
import freenet.message.AnnouncementExecute;
import freenet.message.AnnouncementFailed;
import freenet.message.AnnouncementReply;
import freenet.message.DataInsert;
import freenet.message.DataReply;
import freenet.message.DataRequest;
import freenet.message.Identify;
import freenet.message.InsertReply;
import freenet.message.InsertRequest;
import freenet.message.NodeAnnouncement;
import freenet.message.QueryAborted;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.StoreData;
import freenet.message.VoidMessage;
import freenet.message.client.ClientGet;
import freenet.message.client.ClientHello;
import freenet.message.client.ClientInfo;
import freenet.message.client.ClientPut;
import freenet.message.client.GenerateCHK;
import freenet.message.client.GenerateSHA1;
import freenet.message.client.GenerateSVKPair;
import freenet.message.client.GetDiagnostics;
import freenet.message.client.Illegal;
import freenet.message.client.InvertPrivateKey;
import freenet.message.client.FEC.FECDecodeSegment;
import freenet.message.client.FEC.FECEncodeSegment;
import freenet.message.client.FEC.FECMakeMetadata;
import freenet.message.client.FEC.FECSegmentFile;
import freenet.message.client.FEC.FECSegmentSplitFile;
import freenet.node.ds.DataStore;
import freenet.node.ds.FSDataStore;
import freenet.node.rt.BootstrappingDecayingRunningAverageFactory;
import freenet.node.rt.ConstantDecayingRunningAverage;
import freenet.node.rt.DataObjectRoutingStore;
import freenet.node.rt.EdgeKludgingBinaryRunningAverage;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.node.rt.NGRoutingTable;
import freenet.node.rt.NodeEstimatorFactory;
import freenet.node.rt.NodeSortingFilterRoutingTable;
import freenet.node.rt.NodeSortingRoutingTable;
import freenet.node.rt.RoutingTable;
import freenet.node.rt.RunningAverage;
import freenet.node.rt.RunningAverageFactory;
import freenet.node.rt.SelfAdjustingDecayingRunningAverage;
import freenet.node.rt.SimpleRunningAverage;
import freenet.node.rt.SlidingBucketsKeyspaceEstimatorFactory;
import freenet.node.rt.StandardNodeEstimatorFactory;
import freenet.node.rt.StandardNodeStats;
import freenet.node.rt.StoredRoutingTable;
import freenet.node.states.announcing.Announcing;
import freenet.node.states.maintenance.Checkpoint;
import freenet.presentation.ClientProtocol;
import freenet.presentation.FCPRawMessage;
import freenet.presentation.FNPRawMessage;
import freenet.presentation.MuxProtocol;
import freenet.session.FnpLinkManager;
import freenet.session.PlainLinkManager;
import freenet.support.BooleanCallback;
import freenet.support.BucketFactory;
import freenet.support.Checkpointed;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.KeyHistogram;
import freenet.support.Logger;
import freenet.support.SimpleDataObjectStore;
import freenet.support.TempBucketFactory;
import freenet.support.TempBucketHook;
import freenet.support.io.CommentedReadInputStream;
import freenet.support.io.ParseIOException;
import freenet.support.io.ReadInputStream;
import freenet.support.io.WriteOutputStream;
import freenet.thread.FastThreadFactory;
import freenet.thread.QThreadFactory;
import freenet.thread.ThreadFactory;
import freenet.thread.YThreadFactory;
import freenet.transport.ReadSelectorLoop;
import freenet.transport.TCP;
import freenet.transport.VoidAddress;
import freenet.transport.WriteSelectorLoop;
import freenet.transport.tcpAddress;
import freenet.transport.tcpConnection;
import freenet.transport.tcpTransport;

/**
 * This contains the execution code for starting a node. Moved from Node.java
 */

public class Main {

    /**
     * Gets passed in to ReadSelectorLoop. The purpose of this class is to tell
     * ReadSelectorLoop when a long job time would be significant.
     */
    public static class MainLoadBooleanCallback implements BooleanCallback {

        public boolean value() {
            if (node == null) return false;
            if (Node.beganTime == -1) return false;
            long now = System.currentTimeMillis();
            if (now - Node.beganTime < 600 * 1000) // avoid startup spike
                    return false;
            if (node.estimatedLoad(true) > 1.0) return false;
            return true;
        }
    }

    public static boolean publicNode = false;

    public static Node node = null; // everything else is static

    public static Params params = null;

    public static NodeSortingRoutingTable origRT;

    public static File paramFile;

    public static TransportHandler th = null;

    public static tcpTransport tcp = null;

    public static int listenPort = -1;

    /**
     * Contains the address of the node in a a.b.c.d:port or hostname:port style
     */
    private static String oldTCPAddressAndPort = null;

    private static BlockCipher cipher;

    private static Authentity privateKey;

    private static long ARKversion;

    private static long initialARKversion = 0;

    private static byte[] ARKcrypt = null;

    private static NodeReference myRef = null;

    //    private static Object ARKInserterLock = new Object();
    private static IPAddressDetector ipDetector = null;

    private static File tempDir = null;

    private static NativeFSDirectory newDir;

    private static LossyDirectory dsDir = null;

    private static NodeConfigUpdater configUpdater = null;

    private static FnpLinkManager FNPmgr = null;

    private static int timerGranularity;

    private static Identity id = null;

    /**
     * Used to avoid evaluating string concatenations for debug messages when
     * they aren't going to be used. Set after logging has been initialized.
     */
    private static boolean logDEBUG = false;

    public static NodeConfigUpdater getConfigUpdater() {
        return configUpdater;
    }

    public static final String[] defaultRCfiles = new String[] {
            "freenet.conf", "freenet.ini", ".freenetrc"};

    private static final Config switches = new Config();

    static {
        //System.err.println("Main.java static initialization start.");
        // If this is moved further down the file, it may run after
        // InetAddress initialization. If you move it without checking
        // first that it does not cause InetAddress to ignore the setting
        // and cache everything forever, I will revoke your CVS perms!
        //    - amphibian (thanks to Pascal)
        //
        // Make java not cache DNS indefinitely. This is a big problem to
        // users with dyndns addresses... spoofability is also a problem,
        // but not relevant to freenet because the identity check will fail
        Security.setProperty("networkaddress.cache.ttl", "300");
        Security.setProperty("sun.net.inetaddr.ttl", "300");
        // Five minutes is probably not too much.
        // We don't do any of our own caching, and it's on the routing path,
        // so it is arguably necessary

        Node.class.toString(); // Force Node's static to run first
        // See comments in Core.java in getConfig: either the above
        // was added later, or it doesn't work. Allegedly the static
        // initialization runs when Node.getConfig (inherited from
        // Core.getConfig) calls Class.forName("freenet.node.Node")
        // But experiments suggest the above does work and the
        // Class.forName is redundant. 2003-10-16

        String dc = defaultRCfiles[0];

        switches.addOption("help", 'h', 0, null, 10);
        switches.addOption("system", 0, null, 11);
        switches.addOption("version", 'v', 0, null, 12);
        switches.addOption("manual", 0, null, 13);
        switches.addOption("export", 'x', 1, "-", 20);
        switches.addOption("seed", 's', 1, "-", 21);
        switches.addOption("onTheFly", 1, "-", 30);
        switches.addOption("config", 'c', 1, dc, 40);
        switches.addOption("paramFile", 'p', 1, null, 41);

        switches.shortDesc("help", "prints this help message");
        switches.shortDesc("system", "prints JVM properties");
        switches.shortDesc("version", "prints out version info");
        switches.shortDesc("manual", "prints a manual in HTML");

        switches.argDesc("export", "<file>|-");
        switches.shortDesc("export", "exports a signed NodeReference");

        switches.argDesc("seed", "<file>|-");
        switches.shortDesc("seed", "seeds routing table with refs");

        switches.argDesc("onTheFly", "<file>|-");
        switches
                .shortDesc(
                        "onTheFly",
                        "writes a comma delimited list of config options that can be changed on the fly");

        switches.argDesc("config", "<file>");
        switches.shortDesc("config", "generates or updates config file");

        switches.argDesc("paramFile", "<file>");
        switches.shortDesc("paramFile",
                "path to a config file in a non-default location");
        // System.err.println("Main.java static initialization end.");
    }

    /**
     * Start Fred on his journey to world dominatrix^H^H^Hion
     */
    public static void main(String[] args) throws Throwable {
        try {
            // process command line
            Params sw = new Params(switches.getOptions());
            sw.readArgs(args);
            if (sw.getParam("help") != null) {
                usage();
                return;
            }
            if (sw.getParam("system") != null) {
                System.getProperties().list(System.out);
                return;
            }
            if (sw.getParam("version") != null) {
                version();
                return;
            }
            if (sw.getParam("manual") != null) {
                manual();
                return;
            }

            if (sw.getParam("onTheFly") != null) {
                String file = sw.getString("onTheFly");
                PrintStream out = System.out;
                if (!file.equals("-"))
                        try {
                            OutputStream os = new BufferedOutputStream(
                                    new FileOutputStream(file));
                            out = new PrintStream(os);
                        } catch (FileNotFoundException e) {
                            System.err.println("Error creating file " + file);
                            return;
                        }
                Method[] options = NodeConfigUpdater.ConfigOptions.class
                        .getDeclaredMethods();
                out.print(options[0].getName());
                for (int x = 1; x < options.length; x++)
                    out.print(", " + options[x].getName());
                out.print("\n");
                out.close();
                return;
            }

            args = sw.getArgs(); // remove switches recognized so far
            params = new Params(Node.getConfig().getOptions());

            // attempt to load config file
            String paramFileString = sw.getParam("paramFile");
            try {
                if (paramFileString == null) {
                    for (int x = 0; x < defaultRCfiles.length; x++) {
                        String s = defaultRCfiles[x];
                        if (new File(s).exists()) {
                            paramFileString = s;
                            paramFile = new File(paramFileString);
                            params.readParams(s);
                            break;
                        }
                    }
                    if (paramFile == null) {
                    // If we still haven't found one, make the node
                    // report the standard error to the user
                    throw new FileNotFoundException(); }
                } else {
                    paramFile = new File(paramFileString);
                    params.readParams(paramFile);
                }
            } catch (FileNotFoundException e) {
                if (sw.getParam("config") == null) {
                    if (paramFile == null) {
                        System.err
                                .println("Couldn't find any of the following configuration files:");
                        System.err.println("    "
                                + Fields.commaList(defaultRCfiles));
                        System.err
                                .println("If you have just installed this node, use --config with no");
                        System.err
                                .println("arguments to create a config file in the current directory,");
                        System.err
                                .println("or --config <file> to create one at a specific location.");
                        paramFile = new File("freenet.conf");
                    } else {
                        System.err.println("Couldn't find configuration file: "
                                + paramFile);
                        paramFile = new File("freenet.conf");
                    }
                    return;
                }
            }
            params.readArgs(args);
            // I want config after readArgs, which must be after readParams
            // which mandates the hack in the catch block above.
            if (sw.getParam("config") != null) {
                try {
                    Setup set = new Setup(System.in, System.out, new File(sw
                            .getString("config")), false, params);
                    set.dumpConfig();
                } catch (IOException e) {
                    System.err.println("Error while creating config: " + e);
                }
                return;
            }

            // bail out if there were unrecognized switches
            // (we take no args)
            if (params.getNumArgs() > 0) {
                usage();
                return;
            }

            // note if we're a public node (and shouldn't run certain things)
            // can be removed once we come up with a better method using some
            // kind of user permissions
            if (params.getBoolean("publicNode")) {
                Main.publicNode = true;
                Node
                        .getConfig()
                        .addOption(
                                "mainport.params.servlet.7.params.sfDisableWriteToDisk",
                                1, true, 4181);
            }

            // set up runtime logging
            Core.setupLogger(params, sw.getParam("export") != null);

            // Now that logging is initialized the debug log message evaluation
            // shortcut can be set.
            logDEBUG = Core.logger.shouldLog(Logger.DEBUG, Main.class);

            // Wait until after the log is set up so we can use it.
            removeObsoleteParameters();

            timerGranularity = calculateGranularity();

            //runMiscTests();

            // NOTE: we are slowly migrating stuff related to setting up
            //        the runtime environment for the node into the Main
            //        class (and out of Core and Node)
            //
            //        this includes stuff like registering different
            //        message types, key types, diagnostics counters, etc.,
            //        and anything that involves the config params

            // set up static parameters in Core and Node
            Core.logger.log(Main.class, "Starting Freenet (" + Version.nodeName
                    + ") " + Version.nodeVersion + " node, build #"
                    + Version.buildNumber + " on JVM "
                    + System.getProperty("java.vm.vendor") + ":"
                    + System.getProperty("java.vm.name") + ":"
                    + System.getProperty("java.vm.version"), Logger.NORMAL);

            Node.init(params);

            Node.maxThreads = params.getInt("maximumThreads");
            Node.targetMaxThreads = params.getInt("targetMaxThreads");
            Node.threadFactoryName = params.getString("threadFactory");
            // need for initDiagnostics
            Node.tfTolerableQueueDelay = params.getInt("tfTolerableQueueDelay");
            Node.tfAbsoluteMaxThreads = params.getInt("tfAbsoluteMaxThreads");

            // Backward compatible defaults.
            if (Node.maxThreads <= 0) {
                if (Node.threadFactoryName.equals("Q")) {
                    Node.threadFactoryName = "F";
                }
                Node.maxThreads = -Node.maxThreads;
            }

            // see also NodeConfigUpdater.java
            if (Node.targetMaxThreads < 0) {
                Node.targetMaxThreads = Node.maxThreads;
            }

            if (Node.tfAbsoluteMaxThreads <= 0) {
                Node.tfAbsoluteMaxThreads = 1000000;
            }

            // enable diagnostics
            //if (params.getBoolean("doDiagnostics"))
            initDiagnostics(Core.logger);

            // load node secret keys file

            Core.logger.log(Main.class, "loading node keys: " + Node.nodeFile,
                    Logger.NORMAL);

            cipher = (Node.storeCipherName == null ? null : Util
                    .getCipherByName(Node.storeCipherName,
                            Node.storeCipherWidth));

            loadNodeFile();

            boolean strictAddresses = !params.getBoolean("localIsOK");

            // FIXME this seems to be redundant?
            // done differently in startNode(), where it
            // asks for params.getInt("negotiationLimit")
            // but presently 2003-10-16 there is no such param.
            int negotiationLimit = Node.maxThreads / 4;
            if (negotiationLimit == 0) {
                negotiationLimit = Integer.MAX_VALUE;
            }

            // set up stuff for handling the node's transports,
            // sessions, and presentations
            th = new TransportHandler();
            SessionHandler sh = new SessionHandler();
            PresentationHandler ph = new PresentationHandler();

            tcp = new TCP(100, strictAddresses);
            th.register(tcp);
            FNPmgr = new FnpLinkManager(negotiationLimit);
            sh.register(FNPmgr, 100);
            // Mux is mandatory
            //			ph.register(new FreenetProtocol(), 100);

            ph.register(new MuxProtocol(), 200);

            listenPort = params.getInt("listenPort");

            int ipDetectorInterval = params.getInt("ipDetectorInterval");
            if (ipDetectorInterval > 0)
                ipDetector = new IPAddressDetector(ipDetectorInterval * 1000);
            else
                ipDetector = null;

            // get the node's physical addresses
            Address[] addr;
            String preferedAddress = null;
            if (oldTCPAddressAndPort != null) {
                //A new node doesn't have an old address
                int colon = oldTCPAddressAndPort.indexOf(':');
                if (colon != -1) {
                    preferedAddress = oldTCPAddressAndPort.substring(0, colon);
                }
            }
            Address paddr = null;
            if (preferedAddress != null && tcp.checkAddress(preferedAddress)) {
                try {
                    paddr = (tcpAddress) tcp.getAddress(preferedAddress);
                } catch (BadAddressException e) {
                    Core.logger.log(Main.class, "Caught " + e
                            + " parsing old address", e, Logger.ERROR);
                }
            }
            addr = getAddresses(paddr);

            // we have enough to generate the node reference now
            myRef = Node.makeNodeRef(privateKey, addr, sh, ph, ARKversion,
                    ARKcrypt);

            if (logDEBUG) {
                Core.logger.log(Main.class, "Old address: "
                        + oldTCPAddressAndPort, Logger.DEBUG);
            }
            tcpAddress a = getTcpAddress();
            if (logDEBUG) {
                Core.logger
                        .log(Main.class, "New address: "
                                + ((a == null) ? "(null)" : a.toString()),
                                Logger.DEBUG);
            }

            if (a != null && !(a.toString().equals(oldTCPAddressAndPort))) {
                Core.logger.log(Main.class,
                        "Address has changed since last run", Logger.MINOR);
                redetectAddress();
            }

            // --export
            // (no need to load up the full node)
            if (sw.getParam("export") != null) {
                writeFieldSet(myRef.getFieldSet(), sw.getString("export"));
                Core.closeFileLogger();
                return;
            }

            // load up the FS

            boolean firstTime = true;

            Core.logger.log(Main.class, "starting filesystem", Logger.NORMAL);

            Node.storeType = params.getString("storeType");
            Directory dir = newDir = new NativeFSDirectory(Node.storeFiles[0],
                    Node.storeSize, Node.storeBlockSize, Node.useDSIndex,
                    Node.storeMaxTempFraction, Node.maxNodeFilesOpen);

            Node.storeFiles = new File[] { Node.storeFiles[0]};

            // load DS
            Core.logger.log(Main.class, "loading data store", Logger.NORMAL);

            dsDir = new LossyDirectory(0x0001, dir);
            DataStore ds = new FSDataStore(dsDir, Node.storeSize / 100);

            // rename old RT files
            Core.logger.log(Main.class, "Renaming old routing table files",
                    Logger.DEBUG);

            String rtn = "rtnodes_";
            File[] nodesFile = { new File(Node.routingDir, rtn + "a"),
                    new File(Node.routingDir, rtn + "b")};

            rtn = rtn.concat("" + listenPort);
            File[] altNodesFile = { new File(Node.routingDir, rtn + "a"),
                    new File(Node.routingDir, rtn + "b")};

            for (int x = 0; x < 2; x++) {
                if ((!nodesFile[x].exists()) && altNodesFile[x].exists()) {
                    if (!altNodesFile[x].renameTo(nodesFile[x])) {
                        if (!(nodesFile[x].delete() && altNodesFile[x]
                                .renameTo(nodesFile[x]))) {
                            Core.logger.log(Main.class, "Cannot rename "
                                    + altNodesFile[x] + " to " + nodesFile[x]
                                    + ". This would be useful"
                                    + " because then you could change the port"
                                    + " without causing the routing table to"
                                    + " disappear.", Logger.NORMAL);
                            nodesFile[x] = altNodesFile[x];
                        }
                    }
                }
            }

            String rtp = "rtprops_";
            File[] propsFile = { new File(Node.routingDir, rtp + "a"),
                    new File(Node.routingDir, rtp + "b")};

            rtp = rtp.concat("" + listenPort);
            File[] altPropsFile = { new File(Node.routingDir, rtp + "a"),
                    new File(Node.routingDir, rtp + "b")};

            for (int x = 0; x < 2; x++) {
                if ((!propsFile[x].exists()) && altPropsFile[x].exists()) {
                    if (!altPropsFile[x].renameTo(propsFile[x])) {
                        if (!(propsFile[x].delete() && altPropsFile[x]
                                .renameTo(propsFile[x]))) {
                            Core.logger.log(Main.class, "Cannot rename "
                                    + altPropsFile[x] + " to " + propsFile[x]
                                    + ". This would be useful"
                                    + " because then you could change the port"
                                    + " without causing the routing table to"
                                    + " disappear.", Logger.NORMAL);
                            propsFile[x] = altPropsFile[x];
                        }
                    }
                }
            }

            // load RT
            Core.logger.log(Main.class, "loading routing table", Logger.NORMAL);

            SimpleDataObjectStore rtNodes = null;
            SimpleDataObjectStore rtProps = null;

            // swap if b is newer
            if (propsFile[1].lastModified() > propsFile[0].lastModified()) {
                nodesFile = new File[] { nodesFile[1], nodesFile[0]};
                propsFile = new File[] { propsFile[1], propsFile[0]};
            }

            long rtLastModified = propsFile[0].lastModified();

            for (int i = 0; i < nodesFile.length; i++) {
                try {
                    if (rtNodes != null) {
                        if ((!rtNodes.truncated) && (!rtProps.truncated))
                                continue;
                    }
                    rtNodes = new SimpleDataObjectStore(nodesFile, i);
                    rtProps = new SimpleDataObjectStore(propsFile, i);
                } catch (IOException e) {
                    Core.logger.log(Main.class,
                            "One of the routing table files was " + "corrupt.",
                            e, Logger.MINOR);
                    rtNodes = rtProps = null;
                }
            }

            if (rtNodes == null || rtProps == null) {
                Core.logger.log(Main.class,
                        "Routing table corrupt! Trying to reseed.",
                        Logger.ERROR);
                for (int x = 0; x < nodesFile.length; x++) {
                    nodesFile[x].delete();
                    propsFile[x].delete();
                }
                rtNodes = new SimpleDataObjectStore(nodesFile, 0);
                rtProps = new SimpleDataObjectStore(propsFile, 0);
            }

            if (rtNodes.truncated || rtProps.truncated)
                    Core.logger.log(Main.class,
                            "One of the routing table files was truncated",
                            Logger.NORMAL);

            try {
                rtNodes.flush();
                rtProps.flush();
            } catch (IOException e) {
                Core.logger.log(Main.class, "Flushing routing table failed!",
                        e, Logger.ERROR);
            }

            DataObjectRoutingStore routingStore = new DataObjectRoutingStore(
                    rtNodes, rtProps);

            int maxARKLookups = (int) (Node.maxThreads * ((double) Node.maxARKThreadsFraction));
            if (Node.maxThreads == 0) maxARKLookups = Node.rtMaxRefs;
            NodeSortingRoutingTable rt;
            // Old RT does not implement NodeSorter
            //			if (Node.routingTableImpl.equalsIgnoreCase("classic")) {
            //				CPAlgoRoutingTable cprt =
            //					new CPAlgoRoutingTable(
            //						routingStore,
            //						Node.rtMaxNodes,
            //						Node.rtMaxRefs,
            //						Node.failuresLookupARK,
            //						Node.minARKDelay,
            //						Node.minCP,
            //						maxARKLookups,
            //						Core.getRandSource());
            //
            //				rt = cprt;
            //			} else if (Node.routingTableImpl.equalsIgnoreCase("ng")) {

            // Don't accept more than 200 bits from FieldSets.
            // But use 1000 bits internally.
            RunningAverageFactory rafProbability = EdgeKludgingBinaryRunningAverage
                    .factory(1000, 200);
            RunningAverageFactory rafTime = new BootstrappingDecayingRunningAverageFactory(
                    0, 3600 * 1000, 20);
            RunningAverageFactory rafTransferRate = new BootstrappingDecayingRunningAverageFactory(
                    0, 1000.0 * 1000.0 * 1000.0, 20);
            KeyspaceEstimatorFactory tef = new SlidingBucketsKeyspaceEstimatorFactory(
                    rafTime, rafProbability, rafTransferRate, 16, 0.05, Node.doEstimatorSmoothing, Node.useFastEstimators);
            NodeEstimatorFactory nef = new StandardNodeEstimatorFactory(
                    rafProbability, rafTime, tef, Core.getRandSource());
            double initFastestTransfer;
            // FIXME: put this in Node as a param
            if (Node.inputBandwidthLimit != 0) {
                initFastestTransfer = Node.inputBandwidthLimit;
                Core.logger.log(Main.class, "From input: "
                        + initFastestTransfer, Logger.NORMAL);
            } else if (Node.outputBandwidthLimit != 0) {
                initFastestTransfer = (double) Node.outputBandwidthLimit * 4;
                // total guess
                Core.logger.log(Main.class, "From output: "
                        + initFastestTransfer, Logger.NORMAL);
            } else {
                initFastestTransfer = 131072;
                Core.logger.log(Main.class, "From default: "
                        + initFastestTransfer, Logger.NORMAL);
                // COMPLETE guess!
            }
            Core.logger.log(Main.class, "Setting default initTransferRate"
                    + " to " + initFastestTransfer, Logger.NORMAL);
            NGRoutingTable ngrt = new NGRoutingTable(routingStore,
                    Node.rtMaxNodes, nef, tef, rafProbability, rafTime,
                    initFastestTransfer, Node.routingDir);
            Core.logger.log(Main.class, "Created new NGRT", Logger.NORMAL);
            rt = ngrt;

            // See above
            //			} else {
            //				String s = "Invalid routingTableImpl " + Node.routingTableImpl;
            //				Core.logger.log(Main.class, s, Logger.ERROR);
            //				System.err.println(s);
            //				Core.closeFileLogger();
            //				rt = null; // grr javac
            //				System.exit(1);
            //			}

            //              RoutingTable rt = new ProbabilityRoutingTable(routingStore,
            //                                                            Node.rtMaxNodes,
            //                                                            Node.rtMaxRefs,
            //                                                            Core.randSource);

            origRT = rt;
            id = privateKey.getIdentity();
            rt = new NodeSortingFilterRoutingTable(rt, id);

            // load FT

            FailureTable ft = new FailureTable(params
                    .getInt("failureTableSize"), params
                    .getInt("failureTableItems"), params
                    .getLong("failureTableTime"));

            // rename old LoadStats files

            String lsn = "lsnodes_";
            File[] lsFile = { new File(Node.routingDir, lsn + "a"),
                    new File(Node.routingDir, lsn + "b")};

            lsn = lsn.concat("" + listenPort);
            File[] altLsFile = { new File(Node.routingDir, lsn + "a"),
                    new File(Node.routingDir, lsn + "b")};

            for (int x = 0; x < 2; x++) {
                if ((!lsFile[x].exists()) && altLsFile[x].exists()) {
                    if (!altLsFile[x].renameTo(lsFile[x])) {
                        if (!(lsFile[x].delete() && altLsFile[x]
                                .renameTo(lsFile[x]))) {
                            Core.logger.log(Main.class, "Cannot rename "
                                    + altLsFile[x] + " to " + lsFile[x]
                                    + ". This would be useful"
                                    + " because then you could change the port"
                                    + " without causing the load stats data to"
                                    + " disappear.", Logger.NORMAL);
                            lsFile[x] = altLsFile[x];
                        }
                    }
                }
            }

            // load LoadStats

            // swap if b is newer
            if (lsFile[1].lastModified() > lsFile[0].lastModified()) {
                lsFile = new File[] { lsFile[1], lsFile[0]};
            }

            SimpleDataObjectStore lsNodes = null;
            for (int i = 0; i < lsFile.length; i++) {
                try {
                    if (lsNodes != null && !lsNodes.truncated) {
                        continue;
                    }
                    lsNodes = new SimpleDataObjectStore(lsFile, i);
                } catch (IOException e) {
                    Core.logger.log(Main.class,
                            "One of the load stats files was " + "corrupt.", e,
                            Logger.MINOR);
                    lsNodes = null;
                    if (lsFile[i].delete()) {
                        lsNodes = new SimpleDataObjectStore(lsFile, i);
                    } else {
                        String err = "Can't delete corrupt loadstats file "
                                + lsFile[i].getAbsolutePath() + "!";
                        System.err.println(err);
                        Core.logger.log(Main.class, err, Logger.ERROR);
                    }
                }
            }

            if (lsNodes == null) {
                String err = "Could not load or create a valid loadstats file!";
                System.err.println(err);
                Core.logger.log(Main.class, err, Logger.ERROR);
                System.exit(27);
            }

            LoadStats loadStats = new LoadStats(myRef, lsNodes,
                    Node.lsMaxTableSize, Node.lsAcceptRatioSamples,
                    Node.lsHalfLifeHours, Core.diagnostics, null,
                    Node.defaultResetProbability);

            Core.logger.log(Main.class, "Loaded stats", Logger.NORMAL);

            BucketFactory bf = loadTempBucketFactory(Node.storeFiles[0],
                    Node.routingDir);
            Core.logger.log(Main.class, "Loaded bucket factory", Logger.NORMAL);

            if (rt.initialRefsCount() != 0) firstTime = false;

            try {
                // --seed
                // (now that we have the routing table)
                if (sw.getParam("seed") != null) {
                    Vector seeds = Main.readSeedStrings(sw.getString("seed"));
                    Core.logger.log(Main.class, "read seed nodes",
                            Logger.NORMAL);
                    Main.seed(rt, seeds, false, false);
                    Core.logger.log(Main.class, "seeded routing table",
                            Logger.NORMAL);
                }

                // run node
                else {
                    spawnNode(sh, ph, addr, firstTime, dir, ds,
                            rtLastModified, routingStore, rt, ft, loadStats, 
                            bf, Node.routingDir);
                    Node.firstTime = firstTime;
                    node.join(); // join all interface threads
                }
            } catch (Throwable t) {
                System.err.println("Caught " + t + " running or seeding node");
                t.printStackTrace(System.err);
                Core.logger.log(Main.class, "Caught " + t
                        + " running or seeding node", t, Logger.ERROR);
                throw t;
            } finally {
                // save the latest state information
                routingStore.checkpoint();
            }
        } catch (DiagnosticsException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err
                    .println("The internal diagnostics could not be initialized.");
            //System.err.println("Set doDiagnostics=no in the configuration to
            // disable them.");
            System.exit(1);
        } catch (ListenException e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.println("Could not bind to listening port(s)"
                    + " - maybe another node is running?");
            System.err.println("Or, you might not have privileges"
                    + " to bind to a port < 1024.");
            System.exit(1);
        } catch (Exception e) {
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(s);
            e.printStackTrace(ps);
            ps.flush();
            Core.logger.log(Main.class, "Unexpected Exception: "
                    + e.getClass().getName() + "\n" + s.toString(),
                    Logger.ERROR);
            throw e;
        } catch (OutOfMemoryError e) {
            System.gc();
            System.runFinalization();
            System.gc();
            System.runFinalization();
            System.err.println("Caught, in Main:");
            System.err.println(e);
            System.gc();
            System.runFinalization();
            e.printStackTrace(System.err);
        } catch (Throwable t) {
            System.err.println("Caught " + t);
            t.printStackTrace(System.err);
            Core.logger.log(Main.class, "Caught " + t, t, Logger.ERROR);
        } finally {
            Core.getRandSource().close();
        }
    }

    //Spawns a node, TODO: return created information is a struct instead of
    // setting static variables
    private static void spawnNode(SessionHandler sh,
            PresentationHandler ph, Address[] addr, boolean firstTime,
            Directory dir, DataStore ds, long rtLastModified,
            DataObjectRoutingStore routingStore, NodeSortingRoutingTable rt,
            FailureTable ft, LoadStats loadStats, BucketFactory bf, 
            File routingDir)
            throws IOException, Throwable, BadAddressException, ListenException {
        // load seed nodes
        String seedNodesFile = params.getString("seedFile");
        Core.logger.log(Main.class, "otherpath: seedFile=" + seedNodesFile,
                Logger.DEBUG);
        if (seedNodesFile != null && seedNodesFile.length() > 0) {
            try {
                boolean seed = false;
                Enumeration e = routingStore.elements();
                if (!e.hasMoreElements()) {
                    Core.logger.log(Main.class,
                            "Reseeding because no keys in rt", Logger.MINOR);
                    seed = true;
                } else {
                    long tt = (new File(seedNodesFile)).lastModified();
                    if (tt > rtLastModified) {
                        Core.logger.log(Main.class,
                                "Reseeding because seedNodesFile more up "
                                        + "to date than store: " + tt + " vs "
                                        + rtLastModified, Logger.MINOR);
                        seed = true;
                    }
                }
                if (rt.initialRefsCount() == 0) seed = true;

                if (seed) {
                    Vector seedNodes = null;
                    seedNodes = readSeedStrings(seedNodesFile);
                    seed(rt, seedNodes, false, false);
                    Core.logger.log(Main.class, "seeded routing table",
                            Logger.NORMAL);
                } else {
                    Core.logger.log(Main.class, "not seeding routing table",
                            Logger.NORMAL);
                }
                System.gc();
                System.runFinalization();
                routingStore.checkpoint(); // save data
            } catch (FileNotFoundException e) {
                Core.logger.log(Main.class, "Seed file not found: "
                        + seedNodesFile, Logger.NORMAL);
            }
        }

        Core.logger.log(Main.class, "starting node", Logger.NORMAL);

        node = new Node(privateKey, myRef, dir, bf, ds, rt, ft, th, sh, ph,
                loadStats, routingDir);

        if (logDEBUG)
                Core.logger.log(Main.class, "Created node", Logger.DEBUG);

        if (origRT instanceof StoredRoutingTable)
                ((StoredRoutingTable) origRT).setNode(node);

        if (logDEBUG)
                Core.logger.log(Main.class, "Set node on RT", Logger.DEBUG);

        // write the new IP address into the node file
        if (addr.length > 0) writeNodeFile();

        if (logDEBUG)
                Core.logger.log(Main.class, "Written node file", Logger.DEBUG);

        // Initialize FCP FEC support
        try {
            int cacheSize = params.getInt("FECInstanceCacheSize");
            // Worst case memory high water mark is
            // 24MB * maxCodecs. Don't make it too big.
            int maxCodecs = params.getInt("FECMaxConcurrentCodecs");
            Node.fecTools = new FECTools(params, node.bf, cacheSize, maxCodecs);
        } catch (Exception e) {
            Core.logger.log(Main.class, "Couldn't initialize FEC support!",
                    Logger.ERROR);
            System.err.println("Couldn't initialize FEC support!");
        }

        if (logDEBUG)
                Core.logger.log(Main.class, "Initialized FEC", Logger.DEBUG);

        // Must create it for stuff in mainport dependant on it
        configUpdater = new NodeConfigUpdater(Node.configUpdateInterval);

        Core.logger.log(Main.class, "Starting Core...", Logger.MINOR);

        startNode(addr); // run Core

        // Handle watchme
        if (params.getBoolean("watchme")) {
            new Checkpoint(Node.watchme).schedule(node);
        }

        if (origRT instanceof NGRoutingTable)
			new Checkpoint(((NGRoutingTable) origRT).getCheckpointed()).schedule(node);

        // schedule initial connection opening
        node.scheduleOpenAllConnections();

        if (Node.configUpdateInterval != 0)
                try {
                    new Checkpoint(configUpdater).schedule(node);
                } catch (Throwable e) {
                    Core.logger.log(Main.class,
                            "On-the-fly config updater unable to start.",
                            Logger.ERROR);
                }

        if (Node.seednodesUpdateInterval != 0) {
            new Checkpoint(new SeednodesUpdater(Node.seednodesUpdateInterval,
                    rt)).schedule(node);
        }

        if (Node.aggressiveGC != 0)
                new Checkpoint(new GarbageCollectionCheckpointed())
                        .schedule(node);

        // Set up the background inserter.
        BackgroundInserter.setSharedInstance(node.newBackgroundInserter(5, 128,
                node.client, bf));
        BackgroundInserter.getInstance().start();

        // schedule checkpoints for link cleanup,
        // routing table saves, and diagnostics aggregation

        DiagnosticsCheckpoint diagnosticsCheckpoint = new DiagnosticsCheckpoint(
                Core.autoPoll, Core.diagnostics);

        new Checkpoint(sh).schedule(node);
        new Checkpoint(routingStore).schedule(node);
        new Checkpoint(diagnosticsCheckpoint).schedule(node);
        new Checkpoint(ft).schedule(node);
        new Checkpoint(node.connections).schedule(node);
    	new Checkpoint(node.getRateLimitingWriterCheckpoint()).schedule(node);

        // KeepaliveSender
        Thread keepaliveSenderThread = new Thread(new KeepaliveSender(
                node.connections, node.queueManager), "Keep-alive message sender");
        keepaliveSenderThread.setDaemon(true);
        keepaliveSenderThread.start();

        // Not a good idea!
        //					if (origRT instanceof NGRoutingTable)
        //						new Checkpoint(new NewNodeContactor(node)).schedule(
        //							node);
        new Checkpoint(loadStats).schedule(node);
        if (ipDetector != null) new Checkpoint(ipDetector).schedule(node);
        if (Node.useDSIndex)
                new Checkpoint((NativeFSDirectory) dir).schedule(node);

        // schedule announcements
        if (params.getBoolean("doAnnounce")) {
            boolean useRT = params.getBoolean("announcementUseRT");
            int htl = params.getInt("announcementHTL");
            int interval = params.getInt("announcementPollInterval");
            int delay = firstTime ? params.getInt("announcementFirstDelay")
                    : interval;
            
            Announcing.placeAnnouncing(node, htl, interval, delay);
            Core.logger.log(Main.class, "Will announce at HTL " + htl
                    + " with interval " + interval, Logger.DEBUG);
        }
    }

    /**
     * @param origRT2
     * @param seedNodes
     * @param b
     */
    private static void seed(RoutingTable rt, Vector n, boolean force, boolean gentle) {
		boolean logDEBUG = Node.logger.shouldLog(Logger.DEBUG,Main.class);
		if(logDEBUG) Core.logger.log(
				Main.class,
				"Seeding Routing Table: " + n.size() + " nodes",
				Logger.DEBUG);
		for(int i=0;i<Node.rtMaxNodes;i++) {
		    if(n.size() == 0) break;
			int x = Core.getRandSource().nextInt(n.size());
			if (x < 0) x = -x;
			String s = (String)n.get(x);
			n.remove(x);
			// Now turn it into a FieldSet
			// FIXME: GROSS HACK!
			// No idea how to avoid it though.
			// ReadInputStream's schizophrenia is a real pain here.
			// The only way to avoid it is to parse all the seednodes
			// up front, and that requires gobs of memory.
			// FIXME: WILL NOT WORK IF SEEDNODES ARE EVER BINARY!
			Core.logger.log(Main.class, "s = \n"+s, Logger.DEBUG);
			byte[] buf;
            try {
                buf = s.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e1) {
                Core.logger.log(Main.class, "WTF?: "+e1, e1, Logger.ERROR);
                break;
            }
            ByteArrayInputStream bais = new ByteArrayInputStream(buf);
			ReadInputStream ris = new ReadInputStream(bais);
			FieldSet fs;
			try {
			    fs = new FieldSet(ris);
			} catch (IOException e) {
			    Core.logger.log(Main.class, "Skipped reference - truncated: "+e+":\n" +s,
			            Logger.NORMAL);
			    continue;
			}
			Core.logger.log(Main.class, "fs = \n"+fs, Logger.DEBUG);
            // If there an estimator fieldset then remove it.
            FieldSet estimator = fs.getSet("Estimator");
            if (estimator != null) {
                fs.remove("Estimator");
            }
            
            NodeReference ref;
            try {
                ref = new NodeReference(fs);
            } catch (BadReferenceException e) {
                Core.logger.log(Main.class,
                                "Skipped bad NodeReference while reading seed nodes",
                                e, Logger.ERROR);
                continue;
            }
			Core.logger.log(Main.class, "ref = \n"+ref, Logger.DEBUG);
            if (ref.noPhysical()) {
                Core.logger.log(Main.class,
                        "Skipping NodeReference with no physical address in seednodes!: "
                                + ref, Logger.ERROR);
                continue;
            }
            if(ref.badVersion()) {
                Core.logger.log(Main.class,
                        "Skipping too old node reference: "+ref,
                        Logger.MINOR);
            }
			if(logDEBUG) Core.logger.log(Main.class, "Node " + ref, Logger.DEBUG);
			if (force || !rt.references(ref.getIdentity())) {
				if(logDEBUG) Core.logger.log(Main.class, "Doing node " + ref, Logger.DEBUG);
				int r = Core.getRandSource().nextInt();
				int c = ref.hashCode();
				byte[] k = new byte[18];
				for (int j = 0; j < 16; ++j)
					k[j] =
						(byte) (0xff
							& (r >> (8 * (j / 4)) ^ c >> (8 * (j % 4))));
				if (logDEBUG) {
					Core.logger.log(Main.class,
						"Referencing node " + ref + " to key " + k,
						Logger.MINOR);
				}
				if (gentle) {
					if (rt.wantUnkeyedReference(ref))
					    rt.reference(new Key(k), ref.getIdentity(), ref, estimator);
				} else {
				    rt.reference(new Key(k), ref.getIdentity(), ref, estimator);
				}
			} else if (rt.references(ref.getIdentity())) {
				rt.reference(null, ref.getIdentity(), ref, estimator);
			}
		}
    }

    public static void reseed(String file, boolean force, boolean weak) throws FileNotFoundException {
        Vector v = readSeedStrings(file);
        seed(node.rt, v, force, weak);
    }

    public static void reseed(File file, boolean force, boolean weak) throws FileNotFoundException {
        Vector v = readSeedStrings(file);
        seed(node.rt, v, force, weak);
    }

    private static Vector readSeedStrings(String file) throws FileNotFoundException {
        InputStream in;
        in = (file.equals("-") ? System.in : new FileInputStream(
                file));
        BufferedInputStream bis = new BufferedInputStream(in, SEEDNODES_READING_BUFFERSIZE);
        return readSeedStrings(bis);
    }

    private static Vector readSeedStrings(File file) throws FileNotFoundException {
        InputStream in;
        in = new FileInputStream(file);
        BufferedInputStream bis = new BufferedInputStream(in, SEEDNODES_READING_BUFFERSIZE);
        return readSeedStrings(bis);
    }
    
    /**
     * @param seedNodesFile
     * @return
     */
    private static Vector readSeedStrings(InputStream bis) {
        CommentedReadInputStream rin = new CommentedReadInputStream(bis, "#");
        
        Vector v = new Vector();
        
        StringBuffer sb = new StringBuffer(50*1024);
        while(true) {
            String line;
            try {
                line = rin.readln();
            } catch (IOException e) {
                v.add(new String(sb));
                sb.setLength(0);
                break;
            }
            sb.append(line);
            sb.append('\n');
            if(line.equals("End")) {
                v.add(new String(sb));
                sb.setLength(0);
            }
        }
        
        return v;
    }

    protected static BucketFactory loadTempBucketFactory(File storeFile,
            File routingDir) {
        // load temp bucket factory
        Core.logger.log(Main.class, "loading temp bucket factory",
                Logger.NORMAL);

        String tempName;

        tempName = params.getString("tempDir");
        if (tempName == null || tempName.length() == 0)
                tempName = params.getString("FECTempDir");

        if (params.getBoolean("tempInStore")) {
            Core.logger.log(Main.class, "Activating tempInStore", Logger.DEBUG);
            TempBucketFactory.setHook(new NativeFSTempBucketHook());
        }

        if (tempName == null || tempName.length() == 0) {
            tempDir = new File(storeFile, "temp");
        }

        if (tempDir == null) {
            if (tempName == null || tempName.length() == 0) {
                tempDir = new File(routingDir, "client-temp");
            } else {
                if (!tempName.endsWith(File.separator))
                        tempName += File.separator;
                tempDir = new File(tempName);
            }
        }

        if (!tempDir.exists()) {
            if (!tempDir.mkdir()) {
                String error = "WARNING: FECTempDir does not exist, and "
                        + "could not create";
                System.err.println(error);
                Core.logger.log(Main.class, error + " (it is " + tempDir + ")",
                        Logger.ERROR);
                System.exit(1);
            }
        } else if (!tempDir.isDirectory()) {
            String error = "WARNING: FECTempDir is occupied by a file, cannot use";
            System.err.println(error);
            Core.logger.log(Main.class, error, Logger.ERROR);
            System.exit(1);
        }

        Node.tempDir = tempDir;
        Core.logger.log(Main.class, "Node temp dir: " + tempDir, Logger.MINOR);
        System.setProperty("java.io.tmpdir", tempDir.toString());

        // Clean out
        File[] d = tempDir.listFiles();
        for (int x = 0; x < d.length; x++) {
            if (!d[x].isDirectory()) d[x].delete();
        }

        BucketFactory bf = new TempBucketFactory(tempDir.toString());

        Core.logger
                .log(Main.class, "loaded temp bucket factory", Logger.NORMAL);

        if (Node.storeBlockSize >= 128) // sanity check
                TempBucketFactory.defaultIncrement = Node.storeBlockSize;

        return bf;
    }

    // This is a hack to get the windows installer limping
    // along. It should evenutally be removed. --gj 20021005
    private final static void removeObsoleteParameters() {
        if (params.getSet("fproxy") != null) {
            params.remove("fproxy");
            Core.logger.log(Main.class,
                    "WORKAROUND: Ignoring obsolete fproxy.* lines in freenet.conf/ini. "
                            + "You can remove them.", Logger.NORMAL);
        }

        if (params.getSet("nodeinfo") != null) {
            params.remove("nodeinfo");
            Core.logger.log(Main.class,
                    "WORKAROUND: Ignoring obsolete nodeinfo.* lines in freenet.conf/ini. "
                            + "You can remove them.", Logger.NORMAL);

        }

        if (params.getSet("nodestatus") != null) {
            params.remove("nodestatus");
            Core.logger.log(Main.class,
                    "WORKAROUND: Ignoring obsolete nodestatus.* lines in freenet.conf/ini. "
                            + "You can remove them.", Logger.NORMAL);
        }

        if (params.getString("services") != null) {
            String[] list = Fields.commaList(params.getString("services"));
            boolean sawMainPort = false;
            int count = 0;
            int i = 0;
            for (i = 0; i < list.length; i++) {
                if (list[i].equals("mainport")) {
                    sawMainPort = true;
                } else if (list[i].equals("fproxy")) {
                    list[i] = null;
                    count++;
                    Core.logger
                            .log(
                                    Main.class,
                                    "WORKAROUND: Ignoring obsolete fproxy entry from services list "
                                            + "in freenet.conf/ini.  You can remove it.",
                                    Logger.NORMAL);
                    //continue;
                } else if (list[i].equals("nodeinfo")) {
                    list[i] = null;
                    count++;
                    Core.logger
                            .log(
                                    Main.class,
                                    "WORKAROUND: Ignoring obsolete nodeinfo entry from services list "
                                            + "in freenet.conf/ini.  You can remove it.",
                                    Logger.NORMAL);
                } else if (list[i].equals("nodestatus")) {
                    list[i] = null;
                    count++;
                    Core.logger
                            .log(
                                    Main.class,
                                    "WORKAROUND: Ignoring obsolete nodestatus entry from services list "
                                            + "in freenet.conf/ini.  You can remove it.",
                                    Logger.NORMAL);
                }
            }

            if (count > 0 || (!sawMainPort)) {
                int length = list.length - count;
                if (!sawMainPort) {
                    length++;
                }
                String[] newList = new String[length];
                int index = 0;
                if (!sawMainPort) {
                    newList[index] = "mainport";
                    index++;
                    // Hmmm... do we really want to make it impossible to turn
                    // off mainport?
                    Core.logger
                            .log(
                                    Main.class,
                                    "WORKAROUND: mainport is missing from the services list "
                                            + "in freenet.conf/ini.  You should add it.",
                                    Logger.NORMAL);
                }
                for (i = 0; i < list.length; i++) {
                    if (list[i] != null) {
                        newList[index] = list[i];
                        index++;
                    }
                }
                params.put("services", Fields.commaList(newList));
            }
        }
    }

    /**
     * Get all valid addresses for this node, from the ipAddress setting in
     * params, from the local and network IP address detectors. Currently only
     * returns one address.
     * 
     * @param preferred
     *            Preferred address, if any. Usually this is the previous
     *            address from the node file.
     * @return Addresses for this node
     */
    private static Address[] getAddresses(Address preferred) {
        // Firstly, if there is an explicit ipAddress set, honour it.
        String ipAddress = params.getString("ipAddress");
        if (ipAddress.equals("")) ipAddress = null;
        if (ipAddress != null) {
            // We have a configured address.
            // That doesn't mean it's a usable one...
            String addr = ipAddress + ':' + listenPort;
            if (tcp.checkAddress(addr)) {
                Address a;
                try {
                    a = tcp.getAddress(addr);
                    return new Address[] { a};
                } catch (BadAddressException e) {
                    // :(
                    logBadAddress(addr, e);
                }
            } else {
                logBadAddress(addr, null);
            }
        }
        // We DO NOT have a configured address
        if (preferred != null && preferred instanceof VoidAddress)
                preferred = null;
        if (!(preferred == null || preferred instanceof tcpAddress)) {
            Core.logger.log(Main.class, "Preferred address is not TCP!: "
                    + preferred, new Exception("debug"), Logger.ERROR);
            preferred = null;
        }
        Inet4Address preferredIA = null;
        if (preferred != null) {
            tcpAddress p = (tcpAddress) preferred;
            try {
                preferredIA = (Inet4Address) (p.getHost());
            } catch (UnknownHostException e) {
                preferredIA = null;
            }
        }
        // Now, the local detection code
        if (ipDetector != null) {
            Inet4Address iaDetected = (Inet4Address) ipDetector
                    .getAddress(preferredIA);
            // Null means no valid addresses
            if (iaDetected != null) {
                try {
                    Address aDetected = new tcpAddress(tcp, iaDetected,
                            listenPort);
                    return new Address[] { aDetected};
                } catch (BadAddressException e) {
                    Core.logger.log(Main.class,
                            "IPAddressDetector returned invalid address: "
                                    + iaDetected + ": " + e, e, Logger.ERROR);
                }
            }
        }
        // Last chance: on-network IP detection
        if (node != null) {
            Inet4Address detected = node.connections.topDetectedValidAddress();
            if (detected != null) {
                try {
                    Address aDetected = new tcpAddress(tcp, detected,
                            listenPort);
                    return new Address[] { aDetected};
                } catch (BadAddressException e) {
                    Core.logger
                            .log(Main.class,
                                    "Network address detector returned "
                                            + "invalid address: " + detected
                                            + ": " + e, e, Logger.ERROR);
                }
            }
        }
        // Aaaargh!
        return new Address[] { new VoidAddress()};
    }

    /**
     * @param a
     * @param e
     */
    private static void logBadAddress(String a, BadAddressException e) {
        String err = "Address " + a + " seemed incorrect. "
                + ((e == null) ? "" : e.getMessage())
                + "To use Freenet you must have "
                + "a globally addressable Internet " + "address set correctly.";
        if (e != null)
            Core.logger.log(Main.class, err, e, Logger.ERROR);
        else
            Core.logger.log(Main.class, err, Logger.ERROR);
        System.err.println(err);
        if (e != null) e.printStackTrace(System.err);
    }

    /**
     * Actually sets up the interfaces and starts running the Core.
     * 
     * @throws IOException
     *             for an I/O error reading seed nodes or config files
     * @throws BadAddressException
     *             if there was a problem getting listening addresses or allowed
     *             host addresses
     * @throws ListenException
     *             if one of the interfaces couldn't listen
     */
    private static void startNode(Address[] addr) throws IOException,
            BadAddressException, ListenException {
        // set up a thread group, the threadpool, and the OCM

        tcpConnection.startSelectorLoops(Core.logger, Core.diagnostics,
                new MainLoadBooleanCallback(), Node.logInputBytes,
                Node.logOutputBytes);

        ThreadGroup tg = new ThreadGroup(node.toString());
        tg.setMaxPriority(Thread.NORM_PRIORITY);
        ThreadFactory tf = null;
        if (Node.threadFactoryName.equals("Y")) {
            tf = new YThreadFactory(tg, Node.targetMaxThreads, "YThread-",
                    Node.tfTolerableQueueDelay, Node.tfAbsoluteMaxThreads);
        } else if (Node.threadFactoryName.equals("F")) {
            tf = new FastThreadFactory(tg, Node.maxThreads);
        } else if (Node.threadFactoryName.equals("Q")) {
            tf = new QThreadFactory(tg, Node.targetMaxThreads);
        }

        // Keep a hold of the the ThreadFactory so that we can use
        // it for rate limiting in Node.acceptRequest().
        Node.threadFactory = tf;

        int maxConn = Node.maxNodeConnections;

        if (Node.isWin95)
            Core.logger
                    .log(
                            Main.class,
                            "Detected Windows 95!! Limiting connections severely according to known limitations of this OS. We believe win95 is limited to 50 internet connections (but it might be 100, if you KNOW what it is, tell us, devl@freenetproject.org). Freenet will not work well on this machine!",
                            Logger.ERROR);
        else if (Node.isWin9X) {
            Core.logger
                    .log(
                            Main.class,
                            "Detected Windows 98/ME. Limiting connections accordingly. To get rid of this message, use a proper operating system - sorry",
                            Logger.NORMAL);
            if (maxConn > 80) maxConn = 80;
        } else if (Node.isWinCE) {
            Core.logger.log(Main.class, "You are crazy (os.name = "
                    + Node.sysName + ")", Logger.NORMAL);
        }
        OpenConnectionManager ocm = new OpenConnectionManager(maxConn,
                Node.maxOpenConnectionsNewbieFraction, node.rt);

        // set up interfaces
        Vector iv = new Vector();

        // we start an FNP interface for each of our external addresses
        ConnectionRunner fnp = new FreenetConnectionRunner(node, node.sessions,
                node.presentations, ocm, 3, true);
        // FNP: Open to all the Net

        TCP fntcp = new TCP(1, false);
        ListeningAddress fnla = fntcp.getListeningAddress("" + listenPort,
                Node.dontLimitClients);
        ContactCounter contactCounter = null;
        if (params.getBoolean("logInboundContacts")
                && (Core.inboundContacts == null)) {
            // REDFLAG: This works because getAddresses()
            //          only returns one address which is
            //          assumed to be tcp. If getAddresses()
            //          is ever fixed, this code may break.
            //
            // Enable monitoring of inbound contact addresses
            // via NodeStatusServlet.
            Core.inboundContacts = new ContactCounter();
        }
        contactCounter = Core.inboundContacts;
        iv.addElement(new PublicNIOInterface(fnla, node, tf, fnp,
                contactCounter, "FNP"));

        if (params.getBoolean("logOutboundContacts")) {
            // Enable monitoring of outbound contact addresses
            // via NodeStatusServlet.
            Core.outboundContacts = new ContactCounter();
        }

        if (params.getBoolean("logInboundRequests")) {
            // Enable monitoring of per host inbound
            // request stats via NodeStatusServlet
            Core.inboundRequests = new ContactCounter();
        }

        if (params.getBoolean("logOutboundRequests")) {
            // Enable monitoring of per host outbound
            // request stats via NodeStatusServlet
            Core.outboundRequests = new ContactCounter();
        }

        // Distribution of inbound DataRequests
        // Non-optional because we want to use it for request triage
        Core.requestDataDistribution = new KeyHistogram();

        if (params.getBoolean("logInboundInsertRequestDist")) {
            // Enable monitoring of the distribution of
            // incoming InsertRequests via NodeStatusServlet
            Core.requestInsertDistribution = new KeyHistogram();
        }

        // Distribution of successful inbound DataRequests
        // Non-optional because we want to use it for request triage
        Core.successDataDistribution = new KeyHistogram();

        if (params.getBoolean("logSuccessfulInsertRequestDist")) {
            // Enable monitoring of the distribution of
            // successful incoming InsertRequests via NodeStatusServlet
            Core.successInsertDistribution = new KeyHistogram();
        }

        // Not here, this get's called after
        // Node.init()
        //overloadLow = params.getFloat("overloadLow");
        //overloadHigh = params.getFloat("overloadHigh");
        //System.err.println("This happens later");

        // the FCP interface
        // FIXME: this seems to be redundant? Anyway, there is
        // no such parameter as negotiationLimit as of 2003-10-16
        int negotiationLimit = params.getInt("negotiationLimit");
        SessionHandler clientSh = new SessionHandler();
        clientSh.register(new FnpLinkManager(negotiationLimit), 10);
        clientSh.register(new PlainLinkManager(), 20);

        PresentationHandler clientPh = new PresentationHandler();
        clientPh.register(new ClientProtocol(), 100);

        String fcpHosts = params.getString("fcpHosts");
        TCP ltcp;

        // if fcpHosts is specified, we'll listen on all interfaces
        // otherwise we listen only on loopback
        if (fcpHosts == null || fcpHosts.trim().length() == 0) {
            ltcp = new TCP(InetAddress.getByName("127.0.0.1"), 1, false);
        } else {
            ltcp = new TCP(1, false);
        }

        ConnectionRunner fcp = new FreenetConnectionRunner(node, clientSh,
                clientPh, ocm, 1, false);

        ListeningAddress la = ltcp.getListeningAddress(""
                + params.getInt("clientPort"), Node.dontLimitClients);

        iv.addElement(new LocalNIOInterface(la, tf, fcp, fcpHosts, -1, -1,
                "FCP"));

        // Test HTTP interface
        //         try {
        //             MultipleHttpServletContainer container =
        //                 new MultipleHttpServletContainer(node, false);
        //             // FIXME: hardcoded
        //             Params mainportParams = (Params)(params.getSet("mainport"));
        //             mainportParams = (Params)(mainportParams.getSet("params"));
        //             container.init(mainportParams, "mainport.params");
        //             LocalHTTPInterface testInterface =
        //                  new LocalHTTPInterface(ltcp.getListeningAddress(9999,
        // Node.dontLimitClients), container, "127.0.0.0/8", Node.maxThreads/5,
        // Node.maxThreads/3);
        //             iv.addElement(testInterface);
        //         } catch (ServiceException e) {
        //             Core.logger.log(Main.class, "Could not initialize service: "+e, e,
        //                             Logger.ERROR);
        //         }
        // plus services
        String[] services = params.getList("services");
        if (services != null && !services[0].equals("none")) {
            for (int i = 0; i < services.length; ++i) {
                Core.logger.log(Main.class, "loading service: " + services[i],
                        Logger.NORMAL);
                Params fs = (Params) params.getSet(services[i]);
                if (fs == null) {
                    fs = new Params();
                    Core.logger.log(Main.class, "No config params for "
                            + services[i], Logger.DEBUG);
                }
                /*
                 * { Core.logger.log(Main.class, "No configuration parameters
                 * found for: "+services[i], Logger.ERROR); continue;
                 */
                try {
                    if (logDEBUG) {
                        Core.logger.log(Main.class, "Dumping fs: "
                                + fs.toString(), Logger.DEBUG);
                    }
                    Service svc = loadService(fs, services[i]);
                    iv.addElement(LocalNIOInterface.make(fs, tf, svc,
                            Node.dontLimitClients, Node.maxThreads / 5,
                            Node.maxThreads / 3, services[i]));
                    // FIXME: totally arbitrary limits
                }
                //catch (Exception e) {
                // Need to catch link errors.
                catch (Throwable e) {
                    Core.logger.log(Main.class, "Failed to load service: "
                            + services[i], e, Logger.ERROR);
                }
            }
        }

        // start the Core

        NIOInterface[] interfaces = new NIOInterface[iv.size()];
        iv.copyInto(interfaces);

        MessageHandler mh = new StateChainManagingMessageHandler(node, params
                .getInt("messageStoreSize"));
        initMessageHandler(mh);

        Ticker ticker = new Ticker(mh, tf);

        node.begin(ticker, ocm, interfaces, false);
    }

    private static Service loadService(Params fs, String serviceName)
            throws IOException, ServiceException {
        Service service;

        String className = fs.getString("class");
        if (className == null || className.trim().length() == 0)
                throw new ServiceException("No class given");
        Class cls;
        try {
            if (logDEBUG) {
                Core.logger.log(Main.class, "Trying to load " + className,
                        Logger.DEBUG);
            }
            cls = Class.forName(className.trim());
            if (logDEBUG) {
                Core.logger.log(Main.class, "Loading " + className,
                        Logger.DEBUG);
            }
        } catch (ClassNotFoundException e) {
            throw new ServiceException("" + e);
        }

        if (Servlet.class.isAssignableFrom(cls)) {
            if (HttpServlet.class.isAssignableFrom(cls))
                service = new SingleHttpServletContainer(node, cls, true);
            else
                throw new ServiceException("I'm too dumb for: " + cls);
        } else {
            if (logDEBUG) {
                Core.logger.log(Main.class, "Loading class " + cls,
                        Logger.DEBUG);
            }
            service = ServiceLoader.load(cls, node, true);
        }

        Config serviceConf = service.getConfig();
        //     Core.logger.log(Main.class, "Service config: "+serviceConf,
        //             Logger.DEBUG);
        if (serviceConf != null) {
            Params params;
            if (fs.getSet("params") != null) { // read from Params
                params = new Params(serviceConf.getOptions(), (Params) fs
                        .getSet("params"));
            } else if (fs.get("params") != null) { // or external file
                params = new Params(serviceConf.getOptions());
                params.readParams(fs.getString("params"));
            } else {
                params = new Params(serviceConf.getOptions());
            }
            service.init(params, serviceName);
        }

        return service;
    }

    private static void initDiagnostics(Logger logger)
            throws DiagnosticsException {

        // FIXME: we now have the capability to store
        //         diagnostics data in the datastore

        // set up diagnostics
        String statsDir = params.getString("diagnosticsPath");

        Diagnostics d = new StandardDiagnostics(logger, statsDir);

        // Categories
        DiagnosticsCategory connections = d.addCategory("Connections",
                "Data regarding the connections.", null);
        DiagnosticsCategory outConn = d.addCategory("Outbound",
                "Connections established.", connections);
        DiagnosticsCategory inConn = d.addCategory("Inbound",
                "Connections accepted.", connections);

        DiagnosticsCategory messages = d.addCategory("Messages",
                "Data regarding messages and the routing of messages", null);

        DiagnosticsCategory threading = d.addCategory("Threading",
                "Data regarding the thread pool, job scheduling, "
                        + "and job execution", null);

        DiagnosticsCategory client = d.addCategory("Client",
                "Data regarding the client level", null);

        DiagnosticsCategory routing = d.addCategory("Routing",
                "Data specifically regarding routing", messages);

        DiagnosticsCategory transport = null;

        if (Node.logOutputBytes || Node.logInputBytes)
                transport = d.addCategory("Transport",
                        "Data regarding the transports (i.e., TCP)", null);

        if (Node.logOutputBytes) {
            d.registerCounting("outputBytes", Diagnostics.MINUTE,
                    "The number of bytes written via TCP, "
                            + "excluding connections to localhost."
                            + "Includes 24 byte estimated packet overhead",
                    transport);
            d.registerCounting("outputBytesVeryHigh", Diagnostics.MINUTE,
                    "Very high priority external bytes written via TCP.",
                    transport);
            d.registerCounting("outputBytesHigh", Diagnostics.MINUTE,
                    "High priority external bytes written via TCP.", transport);
            d.registerCounting("outputBytesNormal", Diagnostics.MINUTE,
                    "Normal priority external bytes written via TCP.",
                    transport);
            d.registerCounting("outputBytesLow", Diagnostics.MINUTE,
                    "Low priority external bytes written via TCP.", transport);
            d
                    .registerCounting(
                            "outputBytesTrailingAttempted",
                            Diagnostics.MINUTE,
                            "Bytes attempted to write on trailing field sends over the network.",
                            transport);
            d
                    .registerCounting(
                            "outputBytesTrailerChunks",
                            Diagnostics.MINUTE,
                            "The number of bytes written on trailing field sends over the network.",
                            transport);
            d
                    .registerCounting(
                            "outputBytesTrailerChunksInsert",
                            Diagnostics.MINUTE,
                            "The number of bytes written on trailing field sends from inserts over the network.",
                            transport);
            d.registerCounting("outputBytesPaddingOverhead",
                    Diagnostics.MINUTE,
                    "The number of bytes written solely for padding purposes.",
                    transport);
            d.registerCounting("outputBytesMRI", Diagnostics.MINUTE,
                    "The number of bytes written solely to communicate MRIs.",
                    transport);
            tcpConnection.logBytes = true;
        }

        if (Node.logInputBytes) {
            d.registerCounting("inputBytes", Diagnostics.MINUTE,
                    "The number of bytes read from the network, excluding from "
                            + "localhost and other local/LAN addresses.",
                    transport);
        }

        // connections

        d
                .registerBinomial(
                        "incomingConnectionsAccepted",
                        Diagnostics.MINUTE,
                        "The number of connections incoming, and the number we accepted.",
                        connections);
        d.registerContinuous("connectionLifeTime", Diagnostics.HOUR,
                "The amount of time that connections stay open." + " In ms.",
                connections);
        d.registerBinomial("connectionTimedout", Diagnostics.MINUTE,
                "The number of connections closed when "
                        + "we timed them out. Success marks waiting "
                        + "until the end of the configured connectionTimeout,"
                        + "otherwise it was forced by overflow.", connections);
        d.registerCounting("peerClosed", Diagnostics.MINUTE,
                "The number of connections that were closed by " + "the peer.",
                connections);
        d.registerCounting("liveConnections", Diagnostics.MINUTE,
                "The number of connections established minus "
                        + "the number closed.", connections);
        d.registerCounting("liveLinks", Diagnostics.MINUTE,
                "The number of cached links established minus "
                        + "the number forgotten.", connections);
        d.registerContinuous("connectionMessages", Diagnostics.MINUTE,
                "The number of messages sent over the open "
                        + "connection which was just terminated.", connections);
        d.registerCounting("readLockedConnections", Diagnostics.MINUTE,
                "The number of connections that start waiting for "
                        + "the trailing fields to be read, minus those that "
                        + "finish.", connections);
        d.registerCounting("connectionResetByPeer", Diagnostics.MINUTE,
                "The number of times we got Connection reset by "
                        + "peer errors", connections);

        // outbound connections

        d.registerContinuous("connectingTime", Diagnostics.MINUTE,
                "The amount of time it takes to establish a "
                        + "new connection fully. In ms.", outConn);
        d.registerContinuous("socketTime", Diagnostics.MINUTE,
                "The amount of time it takes to open a socket "
                        + "to other nodes. In ms.", outConn);
        d.registerContinuous("authorizeTime", Diagnostics.MINUTE,
                "The amount of time it takes to authorize new "
                        + "connections. In ms.", outConn);
        d.registerBinomial("connectionRatio", Diagnostics.MINUTE,
                "The success rate of new outbound connections.", outConn);
        d.registerBinomial("outboundRestartRatio", Diagnostics.MINUTE,
                "The number of outbound connections that "
                        + "restart an existing session (key).", outConn);
        d.registerCounting("outboundOpenerConnections", Diagnostics.MINUTE,
                "The number of connections the Connection Opener starts",
                outConn);

        // inbound connections

        d
                .registerContinuous(
                        "inboundConnectingTime",
                        Diagnostics.MINUTE,
                        "The amount of time it takes to establish an "
                                + "inbound connection as measured from FreenetConnectionRunner.handle().",
                        inConn);

        d.registerBinomial("inboundConnectionRatio", Diagnostics.MINUTE,
                "The success rate of new inbound connections.", inConn);

        d.registerCounting("incomingConnections", Diagnostics.MINUTE,
                "The number of incoming connections.", inConn);
        d.registerCounting("inboundConnectionsDispatched", Diagnostics.MINUTE,
                "The number of inbound connection dispatch attempts"
                        + " via PublicInterfaces.", inConn);
        d.registerCounting("inboundConnectionsConnLimitRej",
                Diagnostics.MINUTE,
                "The number of inbound connection's rejected "
                        + "because of connection limit.", inConn);
        d.registerCounting("inboundConnectionsThreadLimitRej",
                Diagnostics.MINUTE,
                "The  number of inbound connection's rejected "
                        + "because of thread limit.", inConn);
        d.registerCounting("inboundConnectionsAccepted", Diagnostics.MINUTE,
                "The  number of inbound connection's accepted.", inConn);
        d.registerBinomial("inboundRestartRatio", Diagnostics.MINUTE,
                "The number of inbound connections that "
                        + "restart an existing session (key).", inConn);
        d.registerCounting("readinessSelectionScrewed", Diagnostics.MINUTE,
                "The number of times NIO makes a zero byte "
                        + "read after the JVM told it that the socket "
                        + "was readable.", inConn);

        // messages

        d.registerContinuous("hopTime", Diagnostics.MINUTE,
                "The time taken per hop on requests that "
                        + "reach termination. Note that the values are "
                        + "means of the remaining hops, so deviation will "
                        + "be incorrect. In ms.", messages);
        // FIXME: need to think about this:
        //         is there a good way to capture statistics on this?
        //d.registerContinuous("storeDataTime", d.HOUR,
        //                     "The amount time each piece of data is kept in"
        //                     + " the cache.");
        //
        // ^^^ this was supposed to be the amount of time to receive the
        //     StoreData message after the data is received

        d.registerCounting("liveChains", Diagnostics.HOUR,
                "The number of request chains started minus "
                        + "the number completed.", messages);
        d.registerCounting("incomingRequests", Diagnostics.MINUTE,
                "The number of DataRequest queries received.", messages);
        d.registerCounting("incomingInserts", Diagnostics.MINUTE,
                "The number of InsertRequest queries received.", messages);
        d.registerCounting("lostRequestState", Diagnostics.HOUR,
                "The number of live, pending requests that "
                        + "were dropped due to resource limits, not "
                        + "counting those awaiting the final StoreData",
                messages);
        d.registerCounting("lostAwaitingStoreData", Diagnostics.HOUR,
                "The number of live, pending requests that were "
                        + "dropped due to resource limits, but were in "
                        + "the final phase of waiting for the StoreData",
                messages);
        // FIXME: these aren't really message related... are they?
        d.registerCounting("lookupARKattempts", Diagnostics.MINUTE,
                "The number of requests attempting to fetch ARKs", messages);
        d.registerCounting("startedLookupARK", Diagnostics.HOUR,
                "The number of times we start to request an ARK", messages);
        d.registerCounting("fetchedLookupARK", Diagnostics.HOUR,
                "The number of times we fetched an ARK", messages);
        d.registerCounting("validLookupARK", Diagnostics.HOUR,
                "The number of times we fetched a *valid* ARK", messages);
        d.registerCounting("successLookupARK", Diagnostics.HOUR,
                "The number of times we fetched a valid ARK that "
                        + "actually lead to successful connections", messages);

        d.registerCounting("queuedBackgroundInsert", Diagnostics.HOUR,
                "The number of times we queued a background insert", messages);
        d.registerCounting("successBackgroundInsert", Diagnostics.HOUR,
                "The number of times a background insert completed "
                        + "successfully", messages);
        d.registerCounting("failureBackgroundInsert", Diagnostics.HOUR,
                "The number of times a background insert failed", messages);
        d.registerCounting("pcacheAccepted", Diagnostics.MINUTE,
                "Keys accepted into the datastore by the "
                        + "probabilistic caching mechanism", messages);
        d.registerCounting("pcacheRejected", Diagnostics.MINUTE,
                "Keys rejected by the probabilistic caching "
                        + "mechanism; node will attempt to remove them",
                messages);
        d.registerCounting("pcacheFailedDelete", Diagnostics.MINUTE,
                "Keys rejected by the probabilistic caching "
                        + "mechanism, but not deleted because of being used "
                        + "since commit", messages);
        d.registerCounting("prefAccepted", Diagnostics.MINUTE,
                "Keys accepted into routing table by the "
                        + "probabilistic reference mechanism", messages);
        d.registerCounting("prefRejected", Diagnostics.MINUTE,
                "Keys not referenced in routing table because "
                        + "rejected by the probabilistic routing mechanism",
                messages);
        d.registerCounting("storeDataAwaitingStoreData", Diagnostics.MINUTE,
                "StoreData sent while in AwaitingStoreData", messages);
        d.registerCounting("storeDataReceivingInsert", Diagnostics.MINUTE,
                "StoreData sent while in ReceivingInsert", messages);
        d.registerCounting("storeDataSendingReply", Diagnostics.MINUTE,
                "StoreData sent while in SendingReply", messages);
        d
                .registerContinuous(
                        "incomingHopsToLive",
                        Diagnostics.MINUTE,
                        "The hopsToLive value on incoming messages (after decrementing).",
                        messages);
        d.registerContinuous("startedRequestHTL", Diagnostics.MINUTE,
                "The hopsToLive value when a request is started", messages);
        d.registerContinuous("finalHTL", Diagnostics.MINUTE,
                "The hopsToLive value on the message that finally "
                        + "succeeded or failed due to routing.", messages);
        d.registerContinuous("incomingHopsSinceReset", Diagnostics.MINUTE,
                "The hopsSinceReset value on incoming messages", messages);
        d.registerContinuous("timeBetweenFailedRequests", Diagnostics.MINUTE,
                "The time between hits to the same entry "
                        + "in the table of recently failed keys.", messages);
        d.registerContinuous("failureTableBlocks", Diagnostics.MINUTE,
                "The number of requests that are blocked by "
                        + "each entry on the failure table.", messages);
        d
                .registerCounting(
                        "failureTableIgnoredDNFs",
                        Diagnostics.MINUTE,
                        "The number of DataNotFound messages that the failure "
                                + "table blocked from the routing estimators and stats.",
                        messages);
        d.registerContinuous("sendingReplyHTL", Diagnostics.MINUTE,
                "The remaining Hops To Live on non-local "
                        + "queries served from the datastore.", messages);

        d.registerBinomial("inboundAggregateRequests", Diagnostics.MINUTE,
                "The number of inbound queries of all types."
                        + "If the request is not QueryRejected by the rate "
                        + "limiting it counts as a success.", messages);

        // grrrr.... would like to make these two a binomial but
        // I can't see an easy way to do it. --gj
        d.registerBinomial("outboundAggregateRequests", Diagnostics.MINUTE,
                "The number of outbound queries of all types.", messages);
        d.registerCounting("inboundQueryRejecteds", Diagnostics.MINUTE,
                "The number of QueryRejected messages received.", messages);

        d.registerCounting("inboundClientRequests", Diagnostics.MINUTE,
                "The number of client (FCP, InternalClient for fproxy) "
                        + "data and insert requests.", messages);

        d.registerCounting("announcedTo", Diagnostics.HOUR,
                "The number of other nodes that the node was successfully "
                        + "announced to.", messages);

        d.registerCounting("restartedRequests", Diagnostics.HOUR,
                "The number of (Insert/Data)requests that were "
                        + "restarted at this node due to response timeout. "
                        + "This is no longer used, see "
                        + "restartedRequestAccepted.", messages);

        d.registerBinomial("restartedRequestAccepted", Diagnostics.HOUR,
                "Counts instances when requests are restarted "
                        + "locally because of a response timeout. If the "
                        + "request had already been Accepted, it counts as "
                        + "a success.", messages);

        d.registerContinuous("expiredPacketPriority", Diagnostics.MINUTE,
                "Counts the priority of each packet that is expired due to being in the"
                        + "outbound queue longer than its expiryTime()",
                messages);

        d
                .registerContinuous(
                        "searchFailedCount",
                        Diagnostics.MINUTE,
                        "Counts the number of QueryRejected's or timeouts before "
                                + "a given data request DNFed, succeeded or had a transfer fail.",
                        routing);
        d.registerContinuous("backedOffCount", Diagnostics.MINUTE,
                "Counts the number of backed off nodes before a given "
                        + "request DNFed, succeeded or had a transfer fail.",
                routing);
        d
                .registerContinuous(
                        "noConnCount",
                        Diagnostics.MINUTE,
                        "Counts the number of nodes which were skipped because of "
                                + "no available connection before a given request DNFed, succeeded "
                                + "or had a transfer fail.", routing);
        d
                .registerContinuous(
                        "routedToChoiceRank",
                        Diagnostics.MINUTE,
                        "Counts the number of nodes which we either skipped because of "
                                + "no free connections, or because they were backed off, or rejected "
                                + "our query, or timed out, before reaching the node on which we got "
                                + "a DNF, a success or a failed transfer.  Probably not comparable "
                                + "between NGRouting and TreeRouting", routing);
        d.registerContinuous("routingTime", Diagnostics.MINUTE,
                "The amount of time between when requests are "
                        + "dispatched (client call or message received) "
                        + "and when the first route is found. Note that "
                        + "this may include network activity such as DNS "
                        + "name resolution.", routing);

        d.registerContinuous("preRoutingTime", Diagnostics.MINUTE,
                "The amount of time between when requests are "
                        + "dispatched and Pending receives the message.",
                routing);

        d.registerContinuous("subRoutingTime", Diagnostics.MINUTE,
                "The amount of time between when requests are "
                        + "received by Pending and when the first route "
                        + "is found.", routing);

        d.registerContinuous("searchDataRoutingTime", Diagnostics.MINUTE,
                "The amount of time used to check that the key "
                        + "is not in the datastore. Not logged if the key IS "
                        + "in the datastore - see searchDataTime", routing);

        d.registerContinuous("getRouteTime", Diagnostics.MINUTE,
                "The amount of time taken to actually create the "
                        + "route object from the routing table.", routing);

        d.registerContinuous("stillInSendOnTime", Diagnostics.MINUTE,
                "The time to get route from the route object.", routing);

        d.registerContinuous("stillStillInSendOnTime", Diagnostics.MINUTE,
                "The time to call getPeer() on the route.", routing);

        d.registerContinuous("regotTime", Diagnostics.MINUTE, "Another bit.",
                routing);

        d.registerContinuous("messageInitialStateTime", Diagnostics.MINUTE,
                "The time to get a message from creation to execution.",
                routing);

        d.registerContinuous("searchFoundDataTime", Diagnostics.MINUTE,
                "The time Pending took to find a file in the datastore.",
                routing);

        d.registerContinuous("searchNotFoundDataTime", Diagnostics.MINUTE,
                "The time Pending took to not find a file in the datastore.",
                routing);
        d.registerContinuous("messageSendTime", Diagnostics.MINUTE,
                "The total time taken to send a message", messages);
        d.registerContinuous("messageSendTimeContactable", Diagnostics.MINUTE,
                "The total time taken to send a message to a "
                        + "node we have contact details for", messages);
        d.registerContinuous("messageSendTimeNonContactable",
                Diagnostics.MINUTE,
                "The total time taken to send a message to a "
                        + "node we don't have contact details for", messages);
        d.registerContinuous("messageSendTimeRequest", Diagnostics.MINUTE,
                "The total time taken to send a DataRequest or "
                        + "InsertRequest message to a node in the routing "
                        + "table", messages);
        d.registerContinuous("messageSendTimeNonRequest", Diagnostics.MINUTE,
                "The total time taken to send a message other "
                        + "than a DataRequest or InsertRequest", messages);
        d
                .registerContinuous(
                        "messageSendTimeRT",
                        Diagnostics.MINUTE,
                        "The total time taken to send a message to a node in the routing table",
                        messages);
        d
                .registerContinuous(
                        "messageSendTimeNonRT",
                        Diagnostics.MINUTE,
                        "The total time taken to send a message to a node not in the routing table",
                        messages);
        d.registerContinuous("messageSendTimeNoQR", Diagnostics.MINUTE,
                "same as above (W), no QR", messages);
        d
                .registerContinuous("messageSendTimeTrailerChunk",
                        Diagnostics.MINUTE,
                        "The total time taken to send a trailer chunk packet",
                        messages);
        d.registerContinuous("messageSendQueueSize", Diagnostics.MINUTE,
                "Average size of all message queues - L", messages);
        d
                .registerContinuous(
                        "messageSendInterarrivalTime",
                        Diagnostics.MINUTE,
                        "Time between two messages getting enqueued - needed for queueing theory analysis",
                        messages);
        d.registerContinuous("messageSendInterarrivalTimeNoQR",
                Diagnostics.MINUTE, "inter-arrivals, no QR", messages);
        d.registerContinuous("messageSendServiceTime", Diagnostics.MINUTE,
                "Time it takes to send the message after out of the queue",
                messages);
        d.registerContinuous("messageSendServiceTimeNoQR", Diagnostics.MINUTE,
                "service, no QR", messages);
        d.registerContinuous("messagePacketSizeSent", Diagnostics.MINUTE,
                "Size of a message packet sent", messages);
        d.registerContinuous("messagesInPacketSent", Diagnostics.MINUTE,
                "Number of messages in a sent packet", messages);
        d.registerContinuous("messagesInPacketReceived", Diagnostics.MINUTE,
                "Number of messages in a received packet", messages);
        d.registerBinomial("messageSuccessRatio", Diagnostics.MINUTE,
                "The number of message sends attempted, "
                        + "and the number that succeeded.", messages);
        d.registerContinuous("requestsQueued", Diagnostics.MINUTE,
                "The number of requests in the queue", messages);
        d.registerContinuous("queueAvailableNodes", Diagnostics.MINUTE,
                "The number of nodes acceptable to the request queue", messages);
        d.registerBinomial("requestQueueingSuccess", Diagnostics.MINUTE,
                "The number of requests queued, and the number sent", messages);
        d
                .registerContinuous(
                        "incomingRequestInterval",
                        Diagnostics.MINUTE,
                        "The minimum request interval received from a route. Reported "
                                + "for every message received containing a valid MRI value.",
                        routing);
        d
                .registerContinuous(
                        "outgoingRequestInterval",
                        Diagnostics.MINUTE,
                        "The minimum request interval sent out from the node. Reported "
                                + "for every message sent which contains a valid MRI value.",
                        routing);
        d.registerContinuous("globalQuotaPerHour", Diagnostics.MINUTE,
                "The global quota in requests per hour for rate limiting",
                routing);
        d
                .registerContinuous(
                        "globalQuotaPerHourRaw",
                        Diagnostics.MINUTE,
                        "The global quota in requests per hour for rate limiting, before smoothing",
                        routing);
        d.registerContinuous("rateLimitingLoad", Diagnostics.MINUTE,
                "The node load for rate limiting purposes, averaged over the last "
                        + "10 minutes, as actually used by getGlobalQuota",
                routing);
        d.registerContinuous("rawRateLimitingLoad", Diagnostics.MINUTE,
                "The node load for rate limiting purposes. Target is 1.0",
                routing);
        d.registerContinuous("rawEstimatedIncomingRequestsPerHour",
                Diagnostics.MINUTE,
                "The estimated incoming requests per hour as used by getGlobalQuota(), before "
                        + "averaging", routing);
        d.registerContinuous("estimatedIncomingRequestsPerHour",
                Diagnostics.MINUTE,
                "The estimated incoming requests per hour as used by getGlobalQuota(), after "
                        + "averaging", routing);
        d
                .registerContinuous(
                        "receivedTransferSize",
                        Diagnostics.MINUTE,
                        "Complete size of received transfers, not dependent upon success",
                        routing);
        d
                .registerContinuous(
                        "sentTransferFailureSize",
                        Diagnostics.MINUTE,
                        "Complete size of sent transfers that failed to complete, logged at time of failure",
                        routing);
        d
                .registerContinuous(
                        "sentTransferSuccessSize",
                        Diagnostics.MINUTE,
                        "Complete size of sent transfers that completed successfully, logged at time of completion",
                        routing);
        d.registerContinuous("normalizedSuccessTime", Diagnostics.MINUTE,
                "Time to successfully find and transfer a file, "
                        + "normalized for size equal to the store average "
                        + "file size", routing);
        d
                .registerContinuous(
                        "fullSearchTime",
                        Diagnostics.MINUTE,
                        "Full time from start of routing to start of transfer for successful requests",
                        routing);
        d.registerContinuous("successSearchTime", Diagnostics.MINUTE,
                "Search time for successful requests", routing);
        d.registerContinuous("diffSearchSuccessTime", Diagnostics.MINUTE,
                "Search time for a successful request minus "
                        + "the predicted search time. Should average at 0.",
                routing);
        d.registerContinuous("absDiffSearchSuccessTime", Diagnostics.MINUTE,
                "Absolute difference between predicted search time for a "
                        + "successful request and the actual search time.",
                routing);
        d.registerContinuous("successTransferRate", Diagnostics.MINUTE,
                "Transfer rate for successful requests in bytes per second",
                routing);
        d
                .registerContinuous(
                        "diffTransferRate",
                        Diagnostics.MINUTE,
                        "Actual transfer rate minus the predicted transfer rate "
                                + "for a successful request. Should average to 0. In bytes per second.",
                        routing);
        d
                .registerContinuous(
                        "absDiffTransferRate",
                        Diagnostics.MINUTE,
                        "Absolute difference between predicted transfer rate and "
                                + "actual transfer rate for a successful request, in bytes per second.",
                        routing);
        d.registerContinuous("requestTransferTime", Diagnostics.MINUTE,
                "Time for a successful request to complete the transfer.",
                routing);
        d.registerContinuous("requestCompletionTime", Diagnostics.MINUTE,
                "The total time taken by a successful request.", routing);
        d.registerBinomial("requestSuccessRatio", Diagnostics.MINUTE,
                "The number of requests started, and the "
                        + "number that successfully returned data.", routing);
        d.registerBinomial("localRequestSuccessRatio", Diagnostics.MINUTE,
                "The number of local requests started, and the "
                        + "number that successfully returned data.", routing);
        d.registerBinomial("insertRoutingSuccessRatio", Diagnostics.MINUTE,
                "The number of insert requests routed, and "
                        + "the number that succeeded, both excluding "
                        + "non-routing failures.", routing);
        d.registerBinomial("insertNonRoutingSuccessRatio", Diagnostics.MINUTE,
                "Insert requests that succeeded or failed, "
                        + "not due to routing.", routing);
        d.registerBinomial("insertFailRoutingNonRoutingRatio",
                Diagnostics.MINUTE,
                "Insert request failures: how many failed, and "
                        + "how many failed due to routing.", routing);
        d.registerBinomial("requestFailureRoutingOrNotRatio",
                Diagnostics.MINUTE, "The number of failing requests, and the "
                        + "number that failed due to routing.", routing);
        d.registerBinomial("requestSuccessRoutingOrNotRatio",
                Diagnostics.MINUTE,
                "The number of successful requests, and the "
                        + "number that succeeded due to routing.", routing);
        d.registerBinomial("requestFailRNFRatio", Diagnostics.MINUTE,
                "The number of requests that failed, and the "
                        + "number that failed due to running out of routes.",
                routing);
        d.registerBinomial("routingFailRNFRatio", Diagnostics.MINUTE,
                "The number of requests that failed by routing, and the "
                        + "number that failed due to running out of routes.",
                routing);
        d.registerBinomial("routingSuccessRatio", Diagnostics.MINUTE,
                "Requests that failed or succeeded by routing, "
                        + "and the number that succeeded.", routing);
        d.registerBinomial("localRoutingSuccessRatio", Diagnostics.MINUTE,
                "Local requests that failed or succeeded by routing, "
                        + "and the number that succeeded.", routing);
        d.registerBinomial("routingSuccessRatioNoRNF", Diagnostics.MINUTE,
                "Requests that failed or succeeded by routing, excluding "
                        + "those which ran out of routes.", routing);
        d
                .registerBinomial(
                        "routingSuccessRatioCHK",
                        Diagnostics.MINUTE,
                        "Requests for CHKs that failed or succeeded by routing, "
                                + "and the number that succeeded. Probably more accurate "
                                + "than routingSuccessRatio because nobody polls CHKs - "
                                + "we hope!", routing);
        d
                .registerBinomial(
                        "routingSuccessRatioCHKNoRNF",
                        Diagnostics.MINUTE,
                        "Requests that failed or succeeded by routing, excluding those "
                                + "which ran out of routes without a DNF, and those which were for "
                                + "commonly polled keytypes (SSKs, KSKs).",
                        routing);
        d.registerCounting("routingFailedInAWSD", Diagnostics.MINUTE,
                "The number of requests which failed by routing while "
                        + "awaiting a StoreData message.", routing);
        d.registerCounting("requestDataNotFound", Diagnostics.MINUTE,
                "Requests that failed due to DataNotFound", routing);
        d.registerCounting("rtAddedNodes", Diagnostics.MINUTE,
                "The number of nodes added to the routing table because of "
                        + "announcements or StoreData messages.", routing);
        d
                .registerContinuous(
                        "rtStatsMaxPDNF",
                        Diagnostics.MINUTE,
                        "The maximum pDNF of reasonably experienced nodes in "
                                + "the routing table. Used in creating new nodes' initial values.",
                        routing);
        d
                .registerContinuous(
                        "rtStatsMinPDNF",
                        Diagnostics.MINUTE,
                        "The minimum probability of a DataNotFound, of reasonably experienced nodes in "
                                + "the routing table.", routing);
        d.registerContinuous("rtStatsMaxDNFTime", Diagnostics.MINUTE,
                "The maximum estimated DataNotFound search time of experienced nodes in "
                        + "the routing table.", routing);
        d.registerContinuous("rtStatsMaxSuccessSearchTime", Diagnostics.MINUTE,
                "The maximum estimated successful search time of experienced nodes in "
                        + "the routing table.", routing);
        d
                .registerContinuous(
                        "rtStatsMaxPTransferFailed",
                        Diagnostics.MINUTE,
                        "The maximum estimated probability of a transfer failing of experienced nodes in "
                                + "the routing table.", routing);
        d
                .registerContinuous(
                        "rtStatsMinTransferFailedRate",
                        Diagnostics.MINUTE,
                        "The maximum equivalent transfer rate for transfer failures on experienced nodes "
                                + "in the routing table.", routing);
        d.registerContinuous("rtStatsMinTransferRate", Diagnostics.MINUTE,
                "The minimum estimated transfer rate of experienced nodes in "
                        + "the routing table.", routing);
        d.registerBinomial("receivedData", Diagnostics.MINUTE,
                "The number of times data was received, and "
                        + "whether receiving was successful.", routing);
        d.registerBinomial("sentData", Diagnostics.MINUTE,
                "The number of times data was sent, and whether "
                        + "sending was successful.", routing);
        d.registerBinomial("sentDataInserts", Diagnostics.MINUTE,
                "The number of times data was sent as part of an "
                        + "insert, and whether it was successfully sent.",
                routing);
        d
                .registerBinomial(
                        "sentDataNonInserts",
                        Diagnostics.MINUTE,
                        "The number of times data was sent, other than as "
                                + "part of an insert, and whether it was successfully sent.",
                        routing);
        d
                .registerCounting(
                        "sentDataFailedCancelled",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed because it was cancelled.",
                        routing);
        d
                .registerCounting(
                        "sentDataFailedWierdException",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed because of an unexpected exception.",
                        routing);
        d
                .registerCounting("sentDataFailedCB_OK", Diagnostics.MINUTE,
                        "The number of times a data send failed with code OK.",
                        routing);
        d.registerCounting("sentDataFailedCB_RESTARTED", Diagnostics.MINUTE,
                "The number of times a data send failed with code RESTARTED.",
                routing);
        d.registerCounting("sentDataFailedCB_ABORTED", Diagnostics.MINUTE,
                "The number of times a data send failed with code ABORTED.",
                routing);
        d.registerCounting("sentDataFailedCB_BAD_DATA", Diagnostics.MINUTE,
                "The number of times a data send failed with code BAD_DATA.",
                routing);
        d
                .registerCounting(
                        "sentDataFailedCB_SEND_CONN_DIED",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed with code SEND_CONN_DIED.",
                        routing);
        d
                .registerCounting(
                        "sentDataFailedCB_RECV_CONN_DIED",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed with code RECV_CONN_DIED.",
                        routing);
        d.registerCounting("sentDataFailedCB_BAD_KEY", Diagnostics.MINUTE,
                "The number of times a data send failed with code BAD_KEY.",
                routing);
        d
                .registerCounting(
                        "sentDataFailedCB_CACHE_FAILED",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed with code CACHE_FAILED.",
                        routing);
        d.registerCounting("sentDataFailedCB_CANCELLED", Diagnostics.MINUTE,
                "The number of times a data send failed with code CANCELLED.",
                routing);
        d
                .registerCounting(
                        "sentDataFailedCB_RECEIVER_KILLED",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed with code RECEIVER_KILLED.",
                        routing);
        d
                .registerCounting(
                        "sentDataFailedCB_SEND_TIMEOUT",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed with code SEND_TIMEOUT.",
                        routing);
        d
                .registerCounting(
                        "sentDataFailedCB_UNKNOWN",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed with an unrecognized code.",
                        routing);
        d
                .registerCounting(
                        "sendFailedRestartFailed",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed due to a failed restart.",
                        routing);
        d
                .registerCounting(
                        "sendFailedInsertRejected",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed due to an insert being rejected",
                        routing);
        d
                .registerCounting(
                        "sendFailedCollision",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed due to an insert collision",
                        routing);
        d
                .registerCounting(
                        "sendFailedCorruptData",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed due to receiving corrupt data",
                        routing);
        d
                .registerCounting(
                        "sendFailedCacheFailed",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed due to a local cache failure",
                        routing);
        d
                .registerCounting(
                        "sendFailedInsertTimedOut",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed because an insert timed out",
                        routing);
        d
                .registerCounting(
                        "sendFailedSourceAborted",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed because the source aborted it",
                        routing);
        d
                .registerCounting(
                        "sendFailedInsertAborted",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed because the insert was aborted by the insertor using a QueryAborted",
                        routing);
        d
                .registerCounting(
                        "sendFailedSourceRestarted",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed because the source restarted it",
                        routing);
        d
                .registerCounting(
                        "sendFailedUnknownCB",
                        Diagnostics.MINUTE,
                        "The number of times a data send failed for an unrecognized reason",
                        routing);
        d
                .registerCounting(
                        "recvConnDiedInTransfer",
                        Diagnostics.MINUTE,
                        "The number of times a transfer failed because the receiving connection died.",
                        routing);
        d
                .registerCounting(
                        "sendConnDiedInTransfer",
                        Diagnostics.MINUTE,
                        "The number of times a transfer failed because the sending connection died.",
                        routing);
        d.registerContinuous("closePairLifetime", Diagnostics.MINUTE,
                "time it takes to close a connection", messages);
        // threading
        d.registerCounting("jobsExecuted", Diagnostics.MINUTE,
                "The number of jobs executed by the threadpool", threading);
        d
                .registerCounting("overflowThreads", Diagnostics.MINUTE,
                        "The number of overflow threads spawned "
                                + "by the threadpool", threading);
        d.registerCounting("insufficientThreads", Diagnostics.MINUTE,
                "The number of times the threadpool rejected "
                        + "a job due to thread scarcity", threading);
        d.registerContinuous("tickerDelay", Diagnostics.MINUTE,
                "The delay between the time an MO is scheduled "
                        + "to execute on the ticker and the time it "
                        + "actually starts executing, in milliseconds.  "
                        + "It's a very rough measurement, but large "
                        + "values are an indicator of either a bug or a "
                        + "very heavily overloaded node.", threading);
        if (Node.threadFactoryName.equals("Q")) {
            d
                    .registerContinuous(
                            "jobsPerQThread",
                            Diagnostics.MINUTE,
                            "The number of jobs done by the QThread which just exited.",
                            threading);
        }
        if (Node.threadFactoryName.equals("Y")) {
            d
                    .registerContinuous(
                            "jobsPerYThread",
                            Diagnostics.MINUTE,
                            "The number of jobs done by the YThread which just exited.",
                            threading);
            d
                    .registerContinuous(
                            "maxQueueDelayThisYThread",
                            Diagnostics.MINUTE,
                            "maximum delay from time job was queued to time it started for YThread which just exited.",
                            threading);
            d
                    .registerContinuous(
                            "avgQueueDelayThisYThread",
                            Diagnostics.MINUTE,
                            "average delay from time job was queued to time it started for YThread which just exited.",
                            threading);
            d
                    .registerContinuous(
                            "jobQueueDelayAllYThreads",
                            Diagnostics.MINUTE,
                            "The queueDelay for each job executed by any YThread.  An occurrence is registered each time a YThread executes job.run().",
                            threading);
        }

        d.registerBinomial("segmentSuccessRatio", Diagnostics.HOUR,
                "The proportion of segments downloaded that " + "succeed.",
                client);
        d.registerContinuous("fproxyRequestTime", Diagnostics.MINUTE,
                "The time taken for FProxy requests to finish", client);

        Core.autoPoll = new AutoPoll(d, logger);
        Core.diagnostics = d;
    }

    private static void initMessageHandler(MessageHandler mh) {
        // FNP messages
        mh.addType(FNPRawMessage.class, VoidMessage.messageName,
                VoidMessage.class);
        mh.addType(FNPRawMessage.class, DataRequest.messageName,
                DataRequest.class);
        mh.addType(FNPRawMessage.class, Identify.messageName, Identify.class);
        mh.addType(FNPRawMessage.class, DataReply.messageName, DataReply.class);
        mh.addType(FNPRawMessage.class,
                freenet.message.DataNotFound.messageName,
                freenet.message.DataNotFound.class);
        mh.addType(FNPRawMessage.class, QueryRejected.messageName,
                QueryRejected.class);
        mh.addType(FNPRawMessage.class, QueryAborted.messageName,
                QueryAborted.class);
        mh.addType(FNPRawMessage.class, QueryRestarted.messageName,
                QueryRestarted.class);
        mh.addType(FNPRawMessage.class, StoreData.messageName, StoreData.class);
        mh.addType(FNPRawMessage.class, InsertRequest.messageName,
                InsertRequest.class);
        mh.addType(FNPRawMessage.class, InsertReply.messageName,
                InsertReply.class);
        mh.addType(FNPRawMessage.class, Accepted.messageName, Accepted.class);
        mh.addType(FNPRawMessage.class, DataInsert.messageName,
                DataInsert.class);
        mh.addType(FNPRawMessage.class, NodeAnnouncement.messageName,
                NodeAnnouncement.class);
        mh.addType(FNPRawMessage.class, AnnouncementReply.messageName,
                AnnouncementReply.class);
        mh.addType(FNPRawMessage.class, AnnouncementExecute.messageName,
                AnnouncementExecute.class);
        mh.addType(FNPRawMessage.class, AnnouncementComplete.messageName,
                AnnouncementComplete.class);
        mh.addType(FNPRawMessage.class, AnnouncementFailed.messageName,
                AnnouncementFailed.class);

        // FCP messages
        mh.addType(FCPRawMessage.class, ClientHello.messageName,
                ClientHello.class);
        mh.addType(FCPRawMessage.class, ClientInfo.messageName,
                ClientInfo.class);
        mh.addType(FCPRawMessage.class, GenerateSVKPair.messageName,
                GenerateSVKPair.class);
        mh.addType(FCPRawMessage.class, InvertPrivateKey.messageName,
                InvertPrivateKey.class);
        mh.addType(FCPRawMessage.class, GenerateCHK.messageName,
                GenerateCHK.class);
        mh.addType(FCPRawMessage.class, GenerateSHA1.messageName,
                GenerateSHA1.class);
        mh.addType(FCPRawMessage.class, ClientGet.messageName, ClientGet.class);
        mh.addType(FCPRawMessage.class, ClientPut.messageName, ClientPut.class);
        mh.addType(FCPRawMessage.class, GetDiagnostics.messageName,
                GetDiagnostics.class);

        // FCP Messages for Forward Error Correction (FEC)
        mh.addType(FCPRawMessage.class, FECSegmentFile.messageName,
                FECSegmentFile.class);
        mh.addType(FCPRawMessage.class, FECEncodeSegment.messageName,
                FECEncodeSegment.class);
        mh.addType(FCPRawMessage.class, FECDecodeSegment.messageName,
                FECDecodeSegment.class);
        mh.addType(FCPRawMessage.class, FECSegmentSplitFile.messageName,
                FECSegmentSplitFile.class);
        mh.addType(FCPRawMessage.class, FECMakeMetadata.messageName,
                FECMakeMetadata.class);

        mh.addType(FCPRawMessage.class, Illegal.class);

        // additional messages given in the configuration
        String[] messageList = params.getList("messageTypes");
        if (messageList != null) {
            for (int i = 0; i + 2 < messageList.length; i += 3) {
                try {
                    mh.addType(Class.forName(messageList[i]),
                            messageList[i + 1], Class
                                    .forName(messageList[i + 2]));
                } catch (ClassNotFoundException e) {
                    Core.logger.log(Main.class, "Cannot register message type",
                            e, Logger.ERROR);
                }
            }
        }
    }

    private static void copyStream(InputStream in, OutputStream out, long length)
            throws IOException {
        byte[] buf = new byte[Core.blockSize];
        while (length > 0) {
            int n = in.read(buf, 0, (int) Math.min(length, buf.length));
            if (n == -1) throw new EOFException();
            out.write(buf, 0, n);
            length -= n;
        }
    }

    private static final void copyBuffer(Buffer src, Buffer dst)
            throws IOException {
        copyStream(src.getInputStream(), dst.getOutputStream(), src.length());
    }

    /**
     * Write a FieldSet to a file or stdout ("-")
     */
    private static void writeFieldSet(FieldSet fs, String file)
            throws IOException {
        if (file.equals("-")) {
            fs.writeFields(new WriteOutputStream(System.out));
        } else {
            FileOutputStream out = new FileOutputStream(file);
            try {
                fs.writeFields(new WriteOutputStream(out));
            } finally {
                out.close();
            }
        }
    }

    private static int SEEDNODES_READING_BUFFERSIZE = 1024 * 1024;

    /**
     * @param key
     * @param ref
     * @param set
     */
    /*
     * private static void reference(Key key, NodeReference ref, FieldSet set,
     * RoutingTable rt) { if(node == null) rt.reference(key, null, ref, set);
     * else node.reference(key, null, ref, set); }
     */

    /**
     * Print version information
     */
    public static void version() {
        System.out.println(Version.nodeName + " version " + Version.nodeVersion
                + ", protocol version " + Version.protocolVersion + " (build "
                + Version.buildNumber + ", last good build "
                + Version.lastGoodBuild + ")");
    }

    /**
     * Print usage information
     */
    public static void usage() {
        version();
        System.out
                .println("Usage: java " + Main.class.getName() + " [options]");
        System.out.println();
        System.out.println("Configurable options");
        System.out.println("--------------------");
        Node.getConfig().printUsage(System.out);
        System.out.println();
        System.out.println("Command-line switches");
        System.out.println("---------------------");
        switches.printUsage(System.out);
        System.out.println();
        System.out
                .println("Send support requests to support@freenetproject.org.");
        System.out.println("Bug reports go to devl@freenetproject.org.");
    }

    /**
     * Print HTML manual
     */
    public static void manual() {
        System.out.println("<html><body>");
        manual(new PrintWriter(System.out));
        System.out.println("</body></html>");
    }

    public static void manual(PrintWriter out) {
        out.println("<br /><br />");
        out.println("<h2>Freenet Reference Daemon Documentation</h2>");
        out.println("<h3>" + Config.htmlEnc(Version.getVersionString())
                + "</h3>");
        out.println("<br />");
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance();
        out.println("<i>(This manual was automatically generated on "
                + Config.htmlEnc(df.format(new Date()))
                + ". If you have updated Freenet since then, you "
                + "may wish regenerate it.)</i>");
        out.println("<br /><br />");
        out.println("FRED (Freenet REference Daemon) is the standard "
                + "implementation of Freenet. This is the node, which "
                + "serves as a router, data cache, and personal gateway "
                + "all rolled into one. For FRED to run, it requires a "
                + "configuration file to be present - this can be created "
                + "either during the installation, by starting the node "
                + "with the --config switch (see below), or running the "
                + "freenet.config.Setup class manually.");
        out.println("<br /><br />");
        out.println("See the <a href=\"http://www.freenetproject.org/"
                + "index.php?page=docs\"> project documentation"
                + " pages</a> for more information, or ask pointed & "
                + " specific questions on the <a href=\""
                + "http://www.freenetproject.org/index.php?page=lists\">"
                + "mailing lists</a>.");
        out.println("<br /><br />");
        out.println("<br />");
        out.println("<h3>Command line switches: </h3>");
        out.println("<hr />");
        switches.printManual(out);
        out.println("<h3>Configuration options: </h3>");
        out.println("These can reside either in the configuration file "
                + "or be given as command line arguments.");
        out.println("<hr />");
        Core.getConfig().printManual(out);

    }

    public static void loadNodeFile() throws IOException {
        try {
            File f = new File(Node.nodeFile);
            if (f.length() == 0) throw new FileNotFoundException();
            InputStream in = new FileInputStream(Node.nodeFile);
            try {
                Core.logger.log(Main.class, "Reading node file...",
                        Logger.MINOR);
                CommentedReadInputStream cs = new CommentedReadInputStream(in,
                        "#");
                FieldSet fs;
                try {
                    fs = new FieldSet(cs);
                } finally {
                    cs.close();
                    in = null;
                }
                privateKey = new DSAAuthentity(fs.getSet("authentity"));
                FieldSet phys = fs.getSet("physical");
                if (phys != null) oldTCPAddressAndPort = phys.getString("tcp");

                if (logDEBUG)
                        Core.logger.log(Main.class, "Got oldAddress: "
                                + oldTCPAddressAndPort == null ? "(null)"
                                : oldTCPAddressAndPort, Logger.DEBUG);

                FieldSet ark = fs.getSet("ARK");
                String s = ark == null ? null : ark.getString("revision");
                if (s != null) {
                    if (logDEBUG)
                            Core.logger.log(Main.class,
                                    "Keeping old ARK data, revision " + s,
                                    Logger.DEBUG);
                    try {
                        ARKversion = Fields.hexToLong(s);
                    } catch (NumberFormatException e) {
                        Core.logger.log(Main.class,
                                "ARK.revision does not parse", e, Logger.ERROR);
                        throw new ParseIOException("bad node file: " + e);
                    }
                    initialARKversion = ARKversion;
                    s = ark.getString("encryption");
                    if (s != null && s.length() > 8)
                        ARKcrypt = HexUtil.hexToBytes(s);
                    else
                        ARKcrypt = null;
                    if (ARKcrypt == null) {
                        Core.logger.log(Main.class,
                                "ARK Encryption key not found!", Logger.ERROR);
                        long oldver = ARKversion;
                        newARK();
                        ARKversion = oldver++;
                    }
                    s = ark.getString("revisionInserted");
                    if (s != null) {
                        try {
                            initialARKversion = Fields.hexToLong(s);
                        } catch (NumberFormatException e) {
                            Core.logger.log(Main.class,
                                    "ARK.revisionInserted corrupt!", e,
                                    Logger.ERROR);
                        }
                    }
                    if (initialARKversion > ARKversion)
                            ARKversion = initialARKversion;
                    if (logDEBUG)
                            Core.logger.log(Main.class, "initialARKversion = "
                                    + initialARKversion + ", ARKversion = "
                                    + ARKversion, Logger.DEBUG);
                    s = ark.getString("format");
                    if (s == null || !s.equals("1")) ARKversion++; // reinsert -
                    // we
                    // inserted
                    // as old
                    // format
                } else {
                    Core.logger.log(Main.class, "No ARK.revision found",
                            Logger.MINOR);
                    // happens normally when upgrading from a pre-ARK node
                    newARK();
                    try {
                        writeNodeFile();
                    } catch (IOException e) {
                        Core.logger
                                .log(
                                        Main.class,
                                        "IOException writing node file after creating ARK!",
                                        Logger.ERROR);
                    }
                }
                Core.logger.log(Main.class, "Read node file", Logger.NORMAL);
            } finally {
                if (in != null) in.close();
            }
            if (logDEBUG && oldTCPAddressAndPort != null) {
                Core.logger.log(Main.class, "Old address was "
                        + oldTCPAddressAndPort, Logger.DEBUG);
            }
        } catch (FileNotFoundException e) {
            createNodeFile();
        }
    }

    public static void createNodeFile() throws IOException {
        Core.logger.log(Main.class, "Creating node keys: " + Node.nodeFile,
                Logger.NORMAL);

        // FIXME: nodes should generate their own DSA group
        privateKey = new DSAAuthentity(Global.DSAgroupC, Node.getRandSource());
        newARK();
        initialARKversion = 0;
        writeNodeFile();
    }

    public static void newARK() {
        ARKversion = 0;
        ARKcrypt = new byte[32]; // FIXME!
        Node.getRandSource().nextBytes(ARKcrypt);
    }

    public static void writeNodeFile() throws IOException {
        Core.logger.log(Main.class, "Writing node file...", Logger.DEBUG);
        FieldSet fs = new FieldSet();

        fs.put("authentity", privateKey.getFieldSet());

        tcpAddress tcp = getTcpAddress();
        if (tcp != null) fs.makeSet("physical").put("tcp", tcp.toString());

        if (ARKcrypt != null) {
            FieldSet ark = fs.makeSet("ARK");
            ark.put("revision", Long.toHexString(ARKversion));
            ark.put("revisionInserted", Long.toHexString(initialARKversion));
            ark.put("format", "1");
            ark.put("encryption", HexUtil.bytesToHex(ARKcrypt));
        }

        File temp = new File(Node.nodeFile + "-temp");
        File outfile = new File(Node.nodeFile);

        OutputStream out = new FileOutputStream(temp);
        try {
            fs.writeFields(new WriteOutputStream(out));
        } catch (IOException e) {
            Core.logger.log(Main.class, "Cannot write node file", e,
                    Logger.ERROR);
            System.err.println("Cannot write node file: " + e);
            e.printStackTrace(System.err);
        } finally {
            out.close();
        }

        if (!temp.renameTo(outfile)) {
            if (!(outfile.delete() && temp.renameTo(outfile))) {
                Core.logger.log(Main.class, "Cannot rename " + temp + " to "
                        + outfile, Logger.ERROR);
                System.err.println("CANNOT RENAME NODE FILE " + temp + " TO "
                        + outfile);
                return;
            }
        }
        Core.logger.log(Main.class, "Written node file", Logger.DEBUG);
    }

    /**
     * Get the current tcpAddress of the node, from the NodeReference
     */
    public static tcpAddress getTcpAddress() {
        if (myRef == null || th == null) return null;
        Address addr = null;
        try {
            addr = myRef.getAddress(tcp);
        } catch (BadAddressException e) {
            Core.logger.log(Main.class,
                    "BadAddressException getting our address from reference!",
                    e, Logger.ERROR);
            Node.badAddress = true;
            return null;
        }
        Node.badAddress = false;
        return (tcpAddress) addr;
    }

    /**
     * Get the current internet address of the node from the NodeReference
     */
    public static InetAddress getInetAddress() {
        tcpAddress addr = getTcpAddress();
        if (addr == null)
            return null;
        else {
            try {
                return addr.getHost();
            } catch (java.net.UnknownHostException e) {
                Core.logger
                        .log(Main.class, "Our own address " + addr.toString()
                                + " is not resolvable!", Logger.ERROR);
                return null;
            }
        }
    }

    public static FieldSet getPhysicalFieldSet() {
        FieldSet fs = new FieldSet();
        fs.put("tcp", getTcpAddress().toString());
        // REDFLAG: this needs fixing if we ever support multiple addresses
        return fs;
    }

    public static InetAddress getDetectedAddress() {
        IPAddressDetector d = ipDetector;
        if (d != null)
            return d.getAddress();
        else
            return null;
    }

    public static InetAddress getDetectedAddress(int x) {
        IPAddressDetector d = ipDetector;
        if (d != null)
            return d.getAddress(x);
        else
            return null;
    }

    public static Identity getIdentity() {
        return id;
    }

    public static int getTimerGranularity() {
        return timerGranularity;
    }

    public static long getInitialARKversion() {
        return initialARKversion;
    }

    public static void dumpInterestingObjects() {
        if (Core.logger.shouldLog(Logger.MINOR, Main.class)) {
            Runtime r = Runtime.getRuntime();
            long totalMem = r.totalMemory();
            long memUsed = totalMem - r.freeMemory();
            String status = "dump of interesting objects after gc in checkpoint:"
                    + "\nMemory used: "
                    + memUsed
                    + "\nTotal allocated memory: "
                    + totalMem
                    + "\ntcpConnections "
                    + freenet.transport.tcpConnection.instances
                    + "\ntcpConnections open "
                    + freenet.transport.tcpConnection.openInstances
                    + ((freenet.transport.tcpConnection.openInstances
                            - freenet.transport.tcpConnection.instances > 2) ? "\n******** ERROR: MORE OPEN THAN EXTANT! ********"
                            : "")
                    + "\nsize of socketMap "
                    + freenet.transport.tcpConnection
                            .getSocketToConnectionMapSize()
                    + "\nFnpLinks "
                    + freenet.session.FnpLink.instances
                    + "\nPlainLinks "
                    + freenet.session.PlainLink.instances
                    + "\nNIOOS "
                    + freenet.support.io.NIOOutputStream.instances
                    + "\nNIOIS "
                    + freenet.support.io.NIOInputStream.instances
                    + "\nCH "
                    + freenet.ConnectionHandler.profilingHelperTool.instances
                    + "\nCH.bufferPool: "
                    + freenet.ConnectionHandler.bufferPoolSize()
                    +
                    //"\nCH terminated " +
                    // freenet.ConnectionHandler.terminatedInstances+
                    "\nCHIS "
                    + freenet.ConnectionHandler.profilingHelperTool.CHISinstances
                    + "\nCHOS "
                    + freenet.ConnectionHandler.profilingHelperTool.CHOSinstances
                    + "\nRIS "
                    + freenet.ConnectionHandler.profilingHelperTool.RISinstances
                    + "\nSOS "
                    + freenet.ConnectionHandler.profilingHelperTool.SOSinstances
                    + "\nThrottled queue (R): "
                    + tcpConnection.getRSL().throttleQueueLength()
                    + "\nThrottled queue (W): "
                    + tcpConnection.getWSL().throttleQueueLength()
                    + "\ncloseUniqueness: "
                    + ReadSelectorLoop.closeUniquenessLength()
                    + "\ncloseQueue: "
                    + ReadSelectorLoop.closeQueueLength()
                    + "\nWSL.uniqueness: ";
            if (tcpConnection.getWSL() instanceof WriteSelectorLoop)
                status += ((WriteSelectorLoop) tcpConnection.getWSL())
                        .uniquenessLength();
            else
                status += "n/a";
            status += "\nOCM.countConnections(): "
                    + node.connections.countConnections()
                    + "\nOCM.countLRUConnections(): "
                    + node.connections.getNumberOfOpenConnections()
                    + "\nOCM.countOpenLRUConnections(): "
                    + node.connections.countOpenLRUConnections()
                    + "\nOCM.countPeerHandlers(): "
                    + node.connections.countPeerHandlers()
                    + "\nFnpLinkManager.activeLinks: "
                    + FNPmgr.countActiveLinks()
                    + "\nFnpLinkManager.activePeers: "
                    + FNPmgr.countActivePeers()
                    + "\ntcpConnection.bufferPool: "
                    + tcpConnection.bufferPoolSize()
                    + "\nReceiving transfers:\n"
                    + node.connections.dumpTransfers()
                    + "\nNativeFSDir open files: " + newDir.totalOpenFiles()
                    + "\nRandomAccessFilePool: "
                    + newDir.rafpool.totalOpenFiles() + "/"
                    + newDir.rafpool.maxOpenFiles() + "\n";
            if (tcpConnection.getWSL() instanceof WriteSelectorLoop)
                status += ((WriteSelectorLoop) tcpConnection.getWSL())
                        .analyzeUniqueness();
            else
                status += "n/a";
            status += "\n\n" + node.routingResultsByBackoffCount();
            status += "\n" + FproxyServlet.dumpRunningRequests();
            Core.logger.log(Main.class, status, Logger.MINOR);
        }
    }

    static class GarbageCollectionCheckpointed implements Checkpointed {

        // This isn't pretty, but unfortunately java is pretty dumb, and we're
        // not as careful as we should be

        public void checkpoint() {
            Core.logger.log(Main.class, "Currently used memory before GC: "
                    + (Runtime.getRuntime().totalMemory() - Runtime
                            .getRuntime().freeMemory()), Logger.DEBUG);
            System.gc();
            System.runFinalization();
            /** * FIXME: remove before release ** */
            // Why, zab?
            dumpInterestingObjects();
            Core.logger.log(Main.class, "Currently used memory after GC: "
                    + (Runtime.getRuntime().totalMemory() - Runtime
                            .getRuntime().freeMemory()), Logger.DEBUG);
        }

        public String getCheckpointName() {
            return "Garbage Collection Checkpoint";
        }

        public long nextCheckpoint() {
            if (Node.aggressiveGC == 0) return -1;
            return System.currentTimeMillis() + Node.aggressiveGC * 1000;
        }
    }

    static class TickStat {

        long user;

        long nice;

        long system;

        long spare;

        boolean read(File f) {
            String firstline;
            try {
                FileInputStream fis = new FileInputStream(f);
                ReadInputStream ris = new ReadInputStream(fis);
                firstline = ris.readln();
                ris.close();
            } catch (IOException e) {
                return false;
            }
            Core.logger
                    .log(this, "Read first line: " + firstline, Logger.DEBUG);
            if (!firstline.startsWith("cpu")) return false;
            long[] data = new long[4];
            for (int i = 0; i < 4; i++) {
                firstline = firstline.substring("cpu".length()).trim();
                firstline = firstline + ' ';
                int x = firstline.indexOf(' ');
                if (x == -1) return false;
                String firstbit = firstline.substring(0, x);
                try {
                    data[i] = Long.parseLong(firstbit);
                } catch (NumberFormatException e) {
                    return false;
                }
                firstline = firstline.substring(x);
            }
            user = data[0];
            nice = data[1];
            system = data[2];
            spare = data[3];
            Core.logger.log(this, "Read from file: user " + user + " nice "
                    + nice + " system " + system + " spare " + spare,
                    Logger.DEBUG);
            return true;
        }

        void calculate(TickStat old) {
            long userdiff = user - old.user;
            long nicediff = nice - old.nice;
            long systemdiff = system - old.system;
            long sparediff = spare - old.spare;

            if (userdiff + nicediff + systemdiff + sparediff <= 0) return;
            Core.logger.log(this, "User changed by " + userdiff + ", Nice: "
                    + nicediff + ", System: " + systemdiff + ", Spare: "
                    + sparediff, Logger.DEBUG);
            int usage = (int) ((100 * (userdiff + nicediff + systemdiff)) / (userdiff
                    + nicediff + systemdiff + sparediff));
            Core.logger.log(this, "CPU usage: " + usage, Logger.DEBUG);
        }

        void copyFrom(TickStat old) {
            user = old.user;
            nice = old.nice;
            system = old.system;
            spare = old.spare;
        }
    }

    static void runMiscTests() throws Throwable {

        RunningAverage rnfs = new SimpleRunningAverage(1000, 0);
        RunningAverage ra1hits = new SimpleRunningAverage(1000, 0);
        RunningAverage ra2hits = new SimpleRunningAverage(1000, 0);

        //	    for (int k = 0; k < 1000; k++) {
        for (double factor = 1.0; factor >= 0.0; factor -= 0.05) {
            int total_period_length = 2400;
            int node1_mri = 30;
            int node2_mri = 10;
            int totalPerMinute = (total_period_length / node1_mri)
                    + (total_period_length / node2_mri);
            totalPerMinute *= factor;
            for (int i = 0; i < 1000; i++) {
                int[] events = new int[totalPerMinute];
                for (int j = 0; j < totalPerMinute; j++)
                    events[j] = Core.getRandSource().nextInt(
                            total_period_length);
                Arrays.sort(events);
                // Set up status
                RunningAverage ra1 = new SimpleRunningAverage(5, node1_mri);
                int lastHitNode1 = 0;
                RunningAverage ra2 = new SimpleRunningAverage(5, node2_mri);
                int lastHitNode2 = 0;
                int totalHitsNode1 = 0;
                int totalHitsNode2 = 0;
                int totalRNFs = 0;
                for (int j = 0; j < events.length; j++) {
                    int time = events[j];
                    //                    Core.logger.log(Main.class, "Event at time "+time,
                    // Logger.DEBUG);
                    // Node1 is preferred
                    // Is it available?
                    int diff = time - lastHitNode1;
                    if (ra1.valueIfReported(diff) >= node1_mri) {
                        lastHitNode1 = time;
                        ra1.report(diff);
                        // Yay!
                        //                        Core.logger.log(Main.class, "Hit node 1",
                        // Logger.DEBUG);
                        totalHitsNode1++;
                        continue;
                    }
                    diff = time - lastHitNode2;
                    if (ra2.valueIfReported(diff) >= node2_mri) {
                        lastHitNode2 = time;
                        ra2.report(diff);
                        // Yay!
                        //                        Core.logger.log(Main.class, "Hit node 2",
                        // Logger.DEBUG);
                        totalHitsNode2++;
                        continue;
                    }
                    //                    Core.logger.log(Main.class, "RNF :(", Logger.DEBUG);
                    totalRNFs++;
                }
                rnfs.report(totalRNFs);
                ra1hits.report(totalHitsNode1);
                ra2hits.report(totalHitsNode2);
                Core.logger.log(Main.class,
                        "Totals: node 1 hit " + totalHitsNode1
                                + " times, node 2 hit " + totalHitsNode2
                                + " times, and " + totalRNFs + " RNFs",
                        Logger.MINOR);
            }
            Core.logger.log(Main.class, "Averages for factor " + factor + " - "
                    + totalPerMinute + " in 300 seconds: Node 1: "
                    + ra1hits.currentValue() + ", Node 2: "
                    + ra2hits.currentValue() + ", RNFs: " + rnfs.currentValue()
                    + " = " + rnfs.currentValue() / totalPerMinute,
                    Logger.NORMAL);

        }
        // Test BootstrappingDecayingRunningAverage
        BootstrappingDecayingRunningAverageFactory factory;
        factory = new BootstrappingDecayingRunningAverageFactory(0.0, 1.0, 10);
        RunningAverage bdra = factory.create(0.5);
        for (int i = 0; i < 20; i++) {
            double random = Core.getRandSource().nextDouble();
            bdra.report(random);
            Core.logger.log(Main.class, "Reported " + random + ": value now: "
                    + bdra.currentValue(), Logger.MINOR);
        }

        RunningAverageFactory rafProbability = SelfAdjustingDecayingRunningAverage
                .factory();
        RunningAverage ra = rafProbability.create(0.5);
        for (int i = 0; i < 10000; i++) {
            ra.report(1.0);
            Core.logger.log(Main.class, "Now: " + ra.currentValue(),
                    Logger.MINOR);
        }
        RunningAverageFactory rafTime = ConstantDecayingRunningAverage.factory(
                0.2, 0, 3600 * 1000);
        RunningAverageFactory rafTransferRate = ConstantDecayingRunningAverage
                .factory(0.2, 0, 1000.0 * 1000.0 * 1000.0 * 1000.0); // 1PB/sec
        KeyspaceEstimatorFactory tef = new SlidingBucketsKeyspaceEstimatorFactory(
                rafTime, rafProbability, rafTransferRate, 16, 0.05, true, true);
        NodeEstimatorFactory nef = new StandardNodeEstimatorFactory(
                rafProbability, rafTime, tef, Core.getRandSource());
        Address[] addr = new Address[] { new VoidAddress()};

        Runtime r = Runtime.getRuntime();
        // we have enough to generate the node reference now
        NodeReference ref = Node
                .makeNodeRef(new DSAAuthentity(Global.DSAgroupC, Node
                        .getRandSource()), addr, new SessionHandler(),
                        new PresentationHandler(), 0, null);
        System.gc();
        System.runFinalization();
        System.gc();
        System.runFinalization();
	long totalMemory = r.totalMemory();
	long freeMemory = r.freeMemory();
        long memoryUsed = totalMemory - freeMemory;
        Core.logger.log(Main.class, "Memory usage: " + memoryUsed +
			"(total="+totalMemory+", free="+freeMemory+")", Logger.NORMAL);
        Vector v = new Vector();
        for (int i = 0; i < 512; i++) {
            v.add(nef.create(null, null, ref, null, false, StandardNodeStats
                    .createPessimisticDefault()));
            System.gc();
            System.runFinalization();
            System.gc();
            System.runFinalization();
            long memoryUsedNow = r.totalMemory() - r.freeMemory();
            Core.logger.log(Main.class, "Memory usage now: " + memoryUsedNow
                    + ", with " + i + " estimators. Difference: "
                    + (memoryUsedNow - memoryUsed) + ", per estimator: "
                    + (memoryUsedNow - memoryUsed) / (i + 1), Logger.NORMAL);
        }

        int fds = 0;
        File fname = new File("/root/TODO");
        LinkedList fdlist = new LinkedList();
        while (true) {
            try {
                FileInputStream fis = new FileInputStream(fname);
                fdlist.add(fis);
                fds++;
                Core.logger.log(Main.class, "Opened file " + fds + " times",
                        Logger.DEBUG);
            } catch (IOException e) {
                Core.logger.log(Main.class, "Got exception " + e, Logger.DEBUG);
                break;
            }
        }
        Core.logger.log(Main.class, "We have " + fds + " fds", Logger.DEBUG);
        if (File.separator.equals("/")) {
            File f = new File("/proc/stat");
            TickStat tsOld = new TickStat();
            TickStat tsNew = new TickStat();
            tsOld.read(f);
            if (f.exists() && f.canRead()) {
                for (int x = 0; x < 1000; x++) {
                    Thread.sleep(1000);
                    if (!tsNew.read(f)) {
                        Core.logger.log(Main.class, "Failed to parse",
                                Logger.ERROR);
                    }
                    tsNew.calculate(tsOld);
                    tsOld.copyFrom(tsNew);
                }
            }
        }

        // Snip cross-platform attempt
        //         long totalTime = 0;
        //         long targetTime = 0;
        //         for(int y = 0; y < 100; y++) {
        //         long startTime = System.currentTimeMillis();
        //         Thread.sleep(100);
        //         long sleepTime = System.currentTimeMillis();
        // // Core.logger.log(Main.class, "Sleep took "+(sleepTime-startTime),
        // Logger.DEBUG);
        //         Thread.yield();
        //         targetTime += 100;
        //         long endTime = System.currentTimeMillis();
        //         long len = endTime - startTime;
        //         totalTime += len;
        // // Core.logger.log(Main.class, "Took "+len, Logger.DEBUG);
        //         }
        //         long usage = (100 * (totalTime - targetTime)) / totalTime;
        //         Core.logger.log(Main.class, "CPU usage estimate "+x+": "+
        //                 usage, Logger.DEBUG);
        for (int x = 0; x < 1000; x++) {
            long enteredTime = System.currentTimeMillis();
            // rand doesn't need to be Native
            BigInteger rand = new BigInteger(256, Core.getRandSource());
            long gotRandTime = System.currentTimeMillis();
            Core.logger.log(Main.class, "Got random in "
                    + (gotRandTime - enteredTime), Logger.DEBUG);
            DSAGroup grp = Global.DSAgroupC;
            BigInteger out = grp.getG().modPow(rand, grp.getP());
            long modPowTime = System.currentTimeMillis();
            Core.logger.log(Main.class, "modPow() in "
                    + (modPowTime - gotRandTime), Logger.DEBUG);
        }

        byte[] b = new byte[2];

        for (int y = -128; y < 127; y++) {
            b[0] = (byte) y;
            for (int x = -128; x < 127; x++) {
                b[1] = (byte) x;
                FileNumber fn = new FileNumber(b);
                Core.logger.log(Main.class, "" + x + ": " + fn.hashCode()
                        + ", " + fn.longHashCode(), Logger.DEBUG);
            }
        }

        Vector v1 = new Vector();
        Core.logger.log(Main.class, "Vector default capacity: " + v.capacity(),
                Logger.DEBUG);

        for (int x = 0; x < 256; x++) {
            v1.addElement(new Object());
            Core.logger.log(Main.class, "Vector: " + v.capacity() + " at " + x,
                    Logger.DEBUG);
        }

        for (int x = 0; x < 256; x++) {
            v.removeElementAt(0);
            Core.logger.log(Main.class, "Vector: " + v.capacity() + " at " + x,
                    Logger.DEBUG);
        }

        System.gc();
        System.runFinalization();
        System.gc();
        System.runFinalization();

        Core.logger
                .log(Main.class, "Vector NOW: " + v.capacity(), Logger.DEBUG);
        v.trimToSize();
        Core.logger
                .log(Main.class, "Vector NOW: " + v.capacity(), Logger.DEBUG);

        Core.logger.log(Main.class, "Currently used memory (A): "
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
                        .freeMemory()), Logger.DEBUG);

        Object[] vv = new Object[1024];

        Core.logger.log(Main.class, "Currently used memory (B): "
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
                        .freeMemory()), Logger.DEBUG);

        for (int x = 0; x < vv.length; x++)
            vv[x] = new Object();

        Core.logger.log(Main.class, "Currently used memory (C): "
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
                        .freeMemory()), Logger.DEBUG);

        vv = null;
        Object[] f = new Object[1024];

        Core.logger.log(Main.class, "Currently used memory (D): "
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
                        .freeMemory()), Logger.DEBUG);

        for (int x = 0; x < f.length; x++)
            f[x] = new FileNumber(new byte[23]);

        Core.logger.log(Main.class, "Currently used memory (E): "
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
                        .freeMemory()), Logger.DEBUG);

        System.gc();
        System.runFinalization();
        System.gc();
        System.runFinalization();

        Core.logger.log(Main.class, "Currently used memory: "
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
                        .freeMemory()), Logger.DEBUG);

        Random rand = new Random();

        for (int j = 0; j < 10; j++) {

            byte[] k = new byte[18];
            rand.nextBytes(k);
            FileNumber fn = new FileNumber(k);

            int x = fn.hashCode();
            long xl = fn.longHashCode();
            int y = Fields.hashCode(k);
            long yl = Fields.longHashCode(k);

            Core.logger.log(Main.class, "Generated \"random\" key, results: "
                    + x + ", " + xl + "; " + y + ", " + yl, Logger.DEBUG);
        }
        InetAddress myAddr = InetAddress.getLocalHost();
        Core.logger.log(Main.class,
                "Our address is " + myAddr.getHostAddress(), Logger.DEBUG);

        String s = Security.getProperty("networkaddress.cache.ttl");
        if (s == null) {
            Core.logger.log(Main.class, "Cache TTL STILL ZERO!", Logger.ERROR);
        } else {
            Core.logger.log(Main.class, "Cache TTL now: " + s, Logger.DEBUG);
        }
        Core.logger
                .log(Main.class, "Reset current DNS cache TTL", Logger.DEBUG);

        long t = System.currentTimeMillis();
        for (int x = 0; x < 100000; x++) {
            Thread.sleep(2000);
            long c = System.currentTimeMillis();
            InetAddress iaddr = InetAddress.getByName("pascal.rockford.com");
            long ct = System.currentTimeMillis();
            Core.logger.log(Main.class, "Address: " + iaddr.getHostAddress()
                    + " at " + (c - t) + " ms - lookup took " + (ct - c)
                    + " ms", Logger.DEBUG);
        }
    }

    static class NativeFSTempBucketHook implements TempBucketHook {

        public void enlargeFile(long curLength, long writeLength)
                throws IOException {
            if (Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(this, "enlargeFile(" + curLength + ","
                            + writeLength + ")", Logger.DEBUG);
            if (curLength > writeLength) {
                Core.logger.log(this, "curLength > writeLength!", Logger.ERROR);
                throw new IllegalArgumentException("curLength(" + curLength
                        + ") > writeLength(" + writeLength + ")!");
            }
            long x = ((NativeFSDirectory) (node.dir)).clearWrite(curLength,
                    writeLength);
            if (Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(this, "NativeFSDir returned " + x,
                            Logger.DEBUG);
            if (x < 0) throw new IOException("insufficient storage");
            if (x > 0) {
                while (x > 0) {
                    if (Core.logger.shouldLog(Logger.DEBUG, Main.class))
                            Core.logger.log(this, "Getting " + x
                                    + " bytes of space", Logger.DEBUG);
                    dsDir.getSpace(x);
                    if (Core.logger.shouldLog(Logger.DEBUG, Main.class))
                            Core.logger.log(this, "Got some space",
                                    Logger.DEBUG);
                    x = ((NativeFSDirectory) (node.dir)).clearWrite(curLength,
                            writeLength);
                }
                if (x == 0)
                    return;
                else
                    throw new IOException("insufficient storage");
            }
        }

        public void shrinkFile(long shorter, long longer) {
            if (Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(this, "shrinkFile(" + longer + ","
                            + shorter + ")", new Exception("debug"),
                            Logger.DEBUG);
            if (shorter > longer)
                    throw new IllegalArgumentException("longer(" + longer
                            + ")>shorter(" + shorter + ")!");
            long x = ((NativeFSDirectory) (node.dir)).clearWrite(longer,
                    shorter);
            if (x != 0) {
                Exception e = new Exception(
                        "Can't allocate space for temp file SHRINK!");
                Core.logger.log(this, "shrinkFile(" + longer + "," + shorter
                        + ") failed", e, Logger.ERROR);
            }
        }

        public void deleteFile(long length) {
            if (Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(this, "Deleting file of size " + length,
                            Logger.DEBUG);
            long status = ((NativeFSDirectory) (node.dir))
                    .onDeleteTempFile(length);
            if (status == 0) {
                if (Core.logger.shouldLog(Logger.DEBUG, this))
                        Core.logger.log(this, "Deleted file of size " + length
                                + " from temp space successfully.",
                                Logger.DEBUG);
            } else { // status > 0 || status < 0
                Core.logger.log(this, "Impossible to delete temp file of size "
                        + length, new Exception("debug"), Logger.ERROR);
            }
        }

        public void createFile(long length) throws IOException {
            if (Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(this, "Creating file of size " + length,
                            Logger.DEBUG);
            enlargeFile(-1, length);
        }
    }

    static int calculateGranularity() {
        long prevTime = System.currentTimeMillis();
        int count = 0;
        int minGranularity = Integer.MAX_VALUE;
        int maxGranularity = 0;
        int total = 0;
        while (true) {
            long now = System.currentTimeMillis();
            int diff = (int) (now - prevTime);
            if (diff != 0) {
                Core.logger.log(Main.class, "Timer granularity no more than "
                        + diff, Logger.DEBUG);
                count++;
                total += diff;
                if (diff < minGranularity) minGranularity = diff;
                if (diff > maxGranularity) maxGranularity = diff;
                if (count > 100) break;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
            }
            prevTime = now;
        }
        int avgGranularity = total / count;
        Core.logger.log(Main.class, "Granularity is between " + minGranularity
                + "ms and " + maxGranularity + "ms, average is "
                + avgGranularity, Logger.MINOR);
        return avgGranularity;
    }

    final static Object redetectSyncOb = new Object();

    public static void redetectAddress() {
        synchronized (redetectSyncOb) {
            Address[] addr = getAddresses(null);
            try {
                // REDFLAG: This works because getAddresses()
                //          only returns one address which is
                //          assumed to be tcp. If getAddresses()
                //          is ever fixed, this code may break.
                if (myRef.getAddress(tcp).equals(addr[0])) return;
            } catch (Throwable t) {
            }
            Core.logger.log(Main.class, "Address change: new addresses: "
                    + Fields.commaList(addr), Logger.MINOR);
            if (myRef != null) {
                String[] physical = myRef.physical;
                physical = (String[]) physical.clone();
                physical = NodeReference.setAllPhysicalAddresses(addr);
                myRef = myRef.newVersion((DSAAuthentity) privateKey, physical,
                        ARKversion);
                if (node != null) {
                    node.setNodeReference(myRef);
                    //				if (node.begun()) {
                    //					try {
                    //						synchronized (ARKInserterLock) {
                    //							if (ARKinserter == null) {
                    //								ARKinserter = new InsertARK();
                    //								new Checkpoint(ARKinserter).schedule(node);
                    //							}
                    //						}
                    //					} catch (KeyException e) {
                    //						String err =
                    //							"KeyException starting ARK insert! Report to
                    // devl@freenetproject.org: "
                    //								+ e;
                    //						Core.logger.log(Main.class, err, e, Logger.ERROR);
                    //						System.err.println(err);
                    //						e.printStackTrace(System.err);
                    //					}
                    //				}
                }
            }
        }

        try {
            writeNodeFile();
        } catch (IOException e) {
            Core.logger
                    .log(
                            Main.class,
                            "IOException trying to write out node file with new address!",
                            e, Logger.ERROR);
        }
        // TODO Auto-generated method stub

    }
}