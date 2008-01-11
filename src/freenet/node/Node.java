package freenet.node;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.text.NumberFormat;
import java.util.Enumeration;

import freenet.Address;
import freenet.Authentity;
import freenet.Core;
import freenet.CoreException;
import freenet.DSAAuthentity;
import freenet.DSAIdentity;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.KeyException;
import freenet.Message;
import freenet.MessageSendCallback;
import freenet.OpenConnectionManager;
import freenet.Peer;
import freenet.PeerPacketMessage;
import freenet.Presentation;
import freenet.PresentationHandler;
import freenet.SendFailedException;
import freenet.SessionHandler;
import freenet.Ticker;
import freenet.TrailerWriter;
import freenet.TransportHandler;
import freenet.Version;
import freenet.client.BackgroundInserter;
import freenet.client.ClientFactory;
import freenet.client.FECTools;
import freenet.client.InternalClient;
import freenet.config.Config;
import freenet.config.Params;
import freenet.config.RandomPortOption;
import freenet.diagnostics.Diagnostics;
import freenet.fs.dir.Directory;
import freenet.interfaces.NIOInterface;
import freenet.node.ds.DataStore;
import freenet.node.rt.ExtrapolatingTimeDecayingEventCounter;
import freenet.node.rt.NGRouting;
import freenet.node.rt.NGRoutingTable;
import freenet.node.rt.NodeSortingRoutingTable;
import freenet.node.rt.RTDiagSnapshot;
import freenet.node.rt.RunningAverage;
import freenet.node.rt.SimpleBinaryRunningAverage;
import freenet.node.rt.SimpleIntervalledRunningAverage;
import freenet.node.rt.SynchronizedRunningAverage;
import freenet.node.rt.TimeDecayingRunningAverage;
import freenet.session.LinkManager;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Checkpointed;
import freenet.support.HexUtil;
import freenet.support.IntervalledSum;
import freenet.support.LimitCounter;
import freenet.support.LoadSaveCheckpointed;
import freenet.support.Logger;
import freenet.support.io.Bandwidth;
import freenet.support.io.ReadInputStream;
import freenet.thread.ThreadFactory;
import freenet.transport.tcpAddress;
import freenet.transport.tcpConnection;
import freenet.transport.tcpListener;

/*
 * This code is part of the Java Adaptive Network Client by Ian Clarke. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * This is a Wrapper object that contains the components of a Node in the
 * Adaptive Network. It uses a Network object to communicate with other Nodes
 * in the Network.
 * 
 * @author <A HREF="mailto:I.Clarke@strs.co.uk">Ian Clarke</A>
 * @author <a href="mailto:blanu@uts.cc.utexas.edu">Brandon Wiley</a>
 */

public class Node extends Core implements ConnectionThrottler{


	/**
	 * @author root
	 *
	 * TODO To change the template for this generated type comment go to
	 * Window - Preferences - Java - Code Generation - Code and Comments
	 */
	private class RateLimitingWriterCheckpointed extends LoadSaveCheckpointed {

		public RateLimitingWriterCheckpointed(File routingDir) {
			super(routingDir, new String[] { "ratedata_a", "ratedata_b" });
		}

		protected int checkpointPeriod() {
			return 60000;
		}

		public void writeData(DataOutputStream dos) throws IOException {
			receivedRequestCounter.writeDataTo(dos);
			acceptedExternalRequestCounter.writeDataTo(dos);
			globalQuotaAverager.writeDataTo(dos);
			sentRequestCounter.writeDataTo(dos);
		}

		protected void fillInBlanks() {
			if(receivedRequestCounter == null)
				receivedRequestCounter = new ExtrapolatingTimeDecayingEventCounter(rateLimitingInterval, 1000);
			Core.logger.log(this, "receivedRequestCounter = "+receivedRequestCounter, Logger.DEBUG);
			if(acceptedExternalRequestCounter == null)
				acceptedExternalRequestCounter = new ExtrapolatingTimeDecayingEventCounter(rateLimitingInterval, 1000);
			Core.logger.log(this, "acceptedExternalRequestCounter = "+acceptedExternalRequestCounter, Logger.DEBUG);
			if(globalQuotaAverager == null)
				globalQuotaAverager = new TimeDecayingRunningAverage(1000, rateLimitingInterval, 0, Double.MAX_VALUE);
			Core.logger.log(this, "globalQuotaAverager = "+globalQuotaAverager, Logger.DEBUG);
			if(sentRequestCounter == null)
				sentRequestCounter = new ExtrapolatingTimeDecayingEventCounter(rateLimitingInterval/2, 1000);
			Core.logger.log(this, "sentRequestCounter = "+sentRequestCounter, Logger.DEBUG);
		}

		protected void readFrom(DataInputStream dis) throws IOException {
			receivedRequestCounter = new ExtrapolatingTimeDecayingEventCounter(1000, rateLimitingInterval, dis);
			Core.logger.log(this, "Read receivedRequestCounter: "+receivedRequestCounter, Logger.MINOR);
			acceptedExternalRequestCounter = new ExtrapolatingTimeDecayingEventCounter(1000, rateLimitingInterval, dis);
			Core.logger.log(this, "Read acceptedExternalRequestCounter: "+acceptedExternalRequestCounter, Logger.MINOR);
			globalQuotaAverager = new TimeDecayingRunningAverage(1000, rateLimitingInterval, 0, Double.MAX_VALUE, dis);
			Core.logger.log(this, "Read globalQuotaAverager: "+globalQuotaAverager, Logger.MINOR);
			sentRequestCounter = new ExtrapolatingTimeDecayingEventCounter(1000, rateLimitingInterval, dis);
			Core.logger.log(this, "Read sentRequestCounter: "+sentRequestCounter, Logger.MINOR);
		}

		protected void preload() {
			// All will be initted to null
		}

		public String getCheckpointName() {
			return "Rate limiting data save process";
		}

	}
	public static int maxConnDefault = 200;
	public static int maxFileDefault = 256;
	public static boolean isWin95;
	public static boolean isWin9X;
	public static boolean isWinCE;
	public static boolean isOSX;

	public static String sysName = System.getProperty("os.name");

	private static final NumberFormat nfp;
	private static final NumberFormat nf1;
	private static final NumberFormat nf03;
	private static final NumberFormat nf3;
	static float overloadHighDefault = 1.25f;
	private static final String ARG_BOOLEAN = "<true|false>";
	static {
		nfp = NumberFormat.getPercentInstance();
		nfp.setMinimumFractionDigits(0);
		nfp.setMaximumFractionDigits(1);
		nf1 = NumberFormat.getInstance();
		nf1.setMaximumFractionDigits(1);
		nf1.setMinimumFractionDigits(1);
		nf1.setGroupingUsed(false);
		nf03 = NumberFormat.getInstance();
		nf03.setMinimumFractionDigits(0);
		nf03.setMaximumFractionDigits(3);
		nf03.setGroupingUsed(false);
		nf3 = NumberFormat.getInstance();
		nf3.setMaximumFractionDigits(3);
		nf3.setMinimumFractionDigits(3);
		nf3.setGroupingUsed(false);

		// System.err.println("Node.java static initialization start.");
		Config config = getConfig();
		// internal defaults
		config.addOption("rtMaxRefs", 1, 50, 1300); // 50 refs/node

		// rtMaxNodes later down because of OS detection

		config.addOption("maxRoutingSteps", 1, 200, 1303); // to 10 refs

		config.addOption("messageStoreSize", 1, 10000, 1350);
		// 10000 live chains
		config.addOption("failureTableSize", 1, 20000, 1360);
		// 20000 failed keys - uses ~ 2.7MB
		config.addOption("failureTableItems", 1, 100000, 1361);
		config.addOption("failureTableTime", 1, 1800000l, 1362); // 30 min
		config.addOption("newNodePollInterval", 1, 30000, 1363); // 30 seconds

		// ARK stuff
		config.addOption("minCP", 1, 0.01F, 1370);
		config.addOption("failuresLookupARK", 1, 10, 1371);
		config.addOption("minARKDelay", 1, 900 * 1000, 1372);
		config.addOption("maxARKThreadsFraction", 1, 0.1F, 1373);

		// network defaults
		config.addOption("routeConnectTimeout", 1, 10000, 1400); // 10 sec
		config.addOption("maxHopsToLive", 1, 20, 1410);
		config.addOption("probIncHopsSinceReset", 1, 0.95F, 1411);
		config.addOption("cacheProbPerHop", 1, 0.8F, 1412);
		config.addOption("minStoreFullPCache", 1, 0.9F, 1413);
		config.addOption("minRTFullPRef", 1, 0.3F, 1414);
		config.addOption("minRTNodesPRef", 1, 0.8F, 1415);
		config.addOption("maxLog2DataSize", 1, 20, 1416);

		// network resource limiting options
		config.addOption("bandwidthLimit", 1, 0, 1200);
		config.addOption("inputBandwidthLimit", 1, 0, 1201); // disabled
		config.addOption("outputBandwidthLimit", 1, 12 * 1024, 1202);
		// 12kB/sec, so it doesn't ruin 128kbps uplink which is unfortunately
		// way too common
		config.addOption("averageBandwidthLimit", 1, 0, 1203); // disabled
		config.addOption("averageInputBandwidthLimit", 1, 0, 1204); // disabled
		config.addOption("averageOutputBandwidthLimit", 1, 0, 1205);
		// disabled

		sysName = sysName.toLowerCase();
		if (sysName.startsWith("windows")) {
			maxFileDefault = 1024;
			// http://msdn.microsoft.com/library/default.asp?url=/library/en-us/vccore98/html/_crt__setmaxstdio.asp
			if (sysName.startsWith("windows ce"))
				isWinCE = true;
			else if (sysName.startsWith("windows 95")) {
				isWin95 = true;
				isWin9X = true;
				maxConnDefault = 20;
			} else if (
				sysName.startsWith("windows 98")
					|| (sysName.startsWith("windows")
						&& (sysName.indexOf("millennium") != -1))
					|| (sysName.startsWith("windows me"))) {
				// Detected Windows 9X
				maxConnDefault = 60;
				isWin9X = true;
			} else {
				isWin9X = false;
			}
		}

		if (sysName.startsWith("mac os x")) {
			maxConnDefault = 128;
			maxFileDefault = 64;
			isOSX = true;
		} else
			isOSX = false;

		if (sysName.startsWith("netware")) {
			// supports unlimited FDs
			maxFileDefault = 0;
		}

		config.addOption("rtMaxNodes", 1, 100, 1301);
		config.addOption("doEstimatorSmoothing", 1, true, 1304);
		config.addOption("useFastEstimators", 1, true, 1305);

		config.addOption("maxNodeConnections", 1, maxConnDefault, 1224);
		config.addOption("maxOpenConnectionsNewbieFraction", 1, 0.2, 1225);
		config.addOption("maxNodeFilesOpen", 1, maxFileDefault, 1226);
		config.addOption("maxNegotiations", 1, 50, 1227);
		// I'm deprecating these in favor of
		config.addOption("maxConnectionsPerMinute", 1, 60, 1228);
		config.addOption("maxConnectionsMinute", 1, 60000, 1229);
		// these, and increasing the default quite a bit while at it.
		config.addOption("maxRequestsPerInterval", 1, -1, 1230);
		config.addOption("maxRequestsInterval", 1, 60000, 1231);
		// data store settings
		config.addOption("storeType", 1, "freenet", 999);
		// "freenet" or "native"
		config.addOption("nodeFile", 1, "", 1000); // node_<port>
		config.addOption("storeFile", 1, "", 1001); // store_<port>
		config.addOption("storeSize", 1, 256L*1024L*1024L, 1010, true);
		// 256MB is reasonable, strictish minimum would be 101MB ((1MB chunk +
		// header) * 100)
		config.addOption("storeBlockSize", 1, 4096, 1011);
		config.addOption("storeMaxTempFraction", 1, (1F / 3F), 1012);
		config.addOption("storeCipherName", 1, "Twofish", 1020);
		// Twofish cipher
		config.addOption("storeCipherWidth", 1, 128, 1021); // 128 bits
		config.addOption("routingDir", 1, "", 1022);
		config.addOption("useDSIndex", 1, true, 1023);

		// network settings
		config.addOption("ipAddress", 1, "", 100); // autodetected if not set
		config.addOption(new RandomPortOption("listenPort", 1, 101));
		// random choice
		config.addOption("clientPort", 1, 8481, 110);
		config.addOption("fcpHosts", 1, "", 112); // loopback only
		config.addOption("transient", 1, false, 300);
		config.setDeprecated("transient", true);
		config.addOption("seedFile", 1, "seednodes.ref", 320);
		config.addOption("routingTableImpl", 1, "ng", 330);

		// logging options
		config.addOption("logLevel", 1, "normal", 1250);
		config.addOption("logFile", 1, "freenet.log", 1251);
		config.addOption("logFormat", 1, "d (c, t, p): m", 1252);
		config.addOption("logDate", 1, "", 1253); // locale default
		config.addOption("logLevelDetail", 1, "", 1254);
		config.addOption("logMaxLinesCached", 1, 10000, 1255);
		config.addOption("logMaxBytesCached", 1, 10L*1024L*1024L, 1256);
		config.addOption("logRotate", 1, false, 1257);
		config.addOption("logRotateUseNativeGzip", 1, false, 1258);
		config.addOption("logRotateInterval", 1, "hour", 1259);
		config.addOption("logOverwrite", 1, true, 1260);

		// diagnostics options
		config.addOption("diagnosticsPath", 1, "stats", 501);

		// announcement options
		config.addOption("doAnnounce", 1, true, 310);
		config.addOption("announcementHTL", 1, 15, 1501);
		config.addOption("announcementAttempts", 1, 3, 1502);
		config.addOption("announcementPollInterval", 1, 900 * 1000, 1513);
		// Set to 0 - with bidi, new nodes should announce immediately
		config.addOption("announcementFirstDelay", 1, /*2 * 3600 * 1000*/0, 1514);
		config.addOption("announcementThreads", 1, 3, 1515);
		config.addOption("announcementUseRT", 1, true, 1516);
		config.addOption("initialRequests", 1, 10, 1520);
		config.addOption("initialRequestHTL", 1, 25, 1521);

		// Load balancing
		config.addOption("doLoadBalance", 1, true, 1550);

		// wierd stuff
		config.addOption("localIsOK", 1, false, 1551);
		config.addOption("dontLimitClients", 1, false, 1552);
		config.addOption("limitAll", 1, false, 1553);
		config.addOption("mainportURIOverride", 1, "", 1554);
		config.addOption("distributionURIOverride", 1, "", 1555);
		config.addOption("aggressiveGC", 1, 0, 1556);
		config.addOption("configUpdateInterval", 1, 5, 1557);
		config.addOption("seednodesUpdateInterval", 1, 5, 1558);
		config.addOption("defaultToSimpleUIMode", 1, true, 1559);
		config.addOption("defaultToOCMHTMLPeerHandlerMode", 1, false, 1560);
		config.addOption("ipDetectorInterval", 1, 10, 1561);

		// FCP admin options
		config.addOption("adminPassword", 1, String.class, 200);
		config.addOption("adminPeer", 1, String.class, 201);

		config.addOption("logOutputBytes", 1, true, 3540);
		config.addOption("logInputBytes", 1, true, 3541);

		// Logging, overload, triage
		config.addOption("logInboundContacts", 1, false, 3500);
		config.addOption("logOutboundContacts", 1, false, 3510);
		config.addOption("logInboundRequests", 1, false, 3520);
		config.addOption("logOutboundRequests", 1, false, 3530);
		config.addOption("logInboundInsertRequestDist", 1, false, 3541);
		config.addOption("logSuccessfulInsertRequestDist", 1, false, 3546);
		config.addOption("doRequestTriageByDelay", 1, true, 3250);
		config.addOption("doRequestTriageBySendTime", 1, true, 3251);
		config.addOption("overloadLow", 1, 1.0f, 3252);
		config.addOption("overloadHigh", 1, overloadHighDefault, 3253);
		config.addOption("threadConnectCutoff", 1, 1.5F, 3254);
		config.addOption("requestDelayCutoff", 1, 1000, 3255);
		config.addOption("successfulDelayCutoff", 1, 2000, 3256);
		config.addOption("requestSendTimeCutoff", 1, 500, 3257);
		config.addOption("successfulSendTimeCutoff", 1, 1000, 3258);
		config.addOption("doOutLimitCutoff", 1, false, 3259);
		config.addOption("outLimitCutoff", 1, 0.8F, 3260);
		config.addOption("doOutLimitConnectCutoff", 1, true, 3261);
		config.addOption("outLimitConnectCutoff", 1, 2.0F, 3262);
		config.addOption("lowLevelBWLimitFudgeFactor", 1, 3F / 4F, 3263);
		config.addOption("doReserveOutputBandwidthForSuccess", 1, false, 3264);
		// Give high-level a chance - if low level is too aggressive, high level 
		// won't be effective
		config.addOption("lowLevelBWLimitMultiplier", 1, 1.4F, 3265);
		config.addOption("doLowLevelOutputLimiting", 1, true, 3266);
		config.addOption("doLowLevelInputLimiting", 1, true, 3267);
		// Commented out because of limited use and potential political
		// problems i.e. not cross platform
		config.addOption("doCPULoad",1,false,3264);
		config.addOption("sendingQueueLength", 1, 256, 3266);
		config.addOption("sendingQueueBytes", 1, 1492 * 8, 3267);
		config.addOption("requestIntervalDefault", 1, 1000, 3268);
		config.addOption("requestIntervalQRFactor", 1, 1.05, 3269);

		// WatchMe options.
		config.addOption("watchme", 1, false, 3541);
		config.addOption("watchmeRetries", 1, 3, 3542);

		// LoadStats options.
		config.addOption("defaultResetProbability", 1, 0.05, 3557);
		config.addOption("lsMaxTableSize", 1, 100, 3558);
		config.addOption("lsAcceptRatioSamples", 1, 500, 3559);
		config.addOption("lsHalfLifeHours", 1, 1.2, 3560);

		// Forward Error Correction (FEC) options
		config.addOption("FECTempDir", 1, "", 3600);
		config.addOption("FECInstanceCacheSize", 1, 1, 3610);
		config.addOption("FECMaxConcurrentCodecs", 1, 1, 3612);

		// Default FEC encoder and decoder implementations.
		config.addOption("FEC.Encoders.0.class", 1, "OnionFECEncoder", 3620);
		config.addOption("FEC.Decoders.0.class", 1, "OnionFECDecoder", 3630);

		// Default temp dir for FEC and fproxy if theirs aren't specified
		config.addOption("tempDir", 1, "", 3640);
		config.addOption("tempInStore", 1, false, 3641);

		config.addOption("publicNode", 1, false, 3650);

		// Allow HTTP inserts?
		config.addOption("httpInserts", 1, true, 3651);

		// Allow FCP inserts?
		config.addOption("fcpInserts", 1, true, 3652);

		// UI template set
		config.addOption("UITemplateSet", 1, "aqua", 3653);

		// Bundled client stuff
		config.addOption(
			"filterPassThroughMimeTypes",
			1,
			"text/plain,image/jpeg,image/gif,image/png",
			4000);

		// Mainport
		config.addOption(
			"mainport.class",
			1,
			"freenet.interfaces.servlet.MultipleHttpServletContainer",
			4100);
		config.addOption("mainport.port", 1, 8888, 4101);
		config.addOption("mainport.allowedHosts", 1, "127.0.0.0/8", 4102);
		config.addOption("mainport.bindAddress", 1, "", 4103);

		config.addOption("mainport.params.servlet.1.uri", 1, "/", 4110);
		config.addOption("mainport.params.servlet.1.method", 1, "GET", 4111);
		config.addOption(
			"mainport.params.servlet.1.class",
			1,
			"freenet.client.http.FproxyServlet",
			4112);
		config.addOption(
			"mainport.params.servlet.1.name",
			1,
			"Freenet HTTP proxy (fproxy)",
			4113);
		config.addOption(
			"mainport.params.servlet.1.params.requestHtl",
			1,
			15,
			4114);
		config.addOption(
			"mainport.params.servlet.1.params.passThroughMimeTypes",
			1,
			"",
			4115);
		config.addOption(
			"mainport.params.servlet.1.params.filter",
			1,
			true,
			4116);
		config.addOption(
			"mainport.params.servlet.1.params.filterParanoidStringCheck",
			1,
			false,
			4117);
		config.addOption(
			"mainport.params.servlet.1.params.maxForceKeys",
			1,
			100,
			4118);
		config.addOption(
			"mainport.params.servlet.1.params.doSendRobots",
			1,
			true,
			4119);
		config.addOption(
			"mainport.params.servlet.1.params.noCache",
			1,
			false,
			4119);
		config.addOption(
			"mainport.params.servlet.1.params.dontWarnOperaUsers",
			1,
			false,
			4119);
		config.addOption(
			"mainport.params.servlet.2.uri",
			1,
			"/servlet/nodeinfo/",
			4120);
		config.addOption("mainport.params.servlet.2.method", 1, "GET", 4121);
		config.addOption(
			"mainport.params.servlet.2.class",
			1,
			"freenet.node.http.NodeInfoServlet",
			4122);
		config.addOption(
			"mainport.params.servlet.2.name",
			1,
			"Web Interface",
			4123);

		// default bookmarks. Bookmarks start at 6000 so they come last in the config file
		config.addOption("mainport.params.servlet.2.bookmarks.count", 1, -1, 6000);

		config.addOption("mainport.params.servlet.2.bookmarks.0.key", 1, "SSK@qe3ZRJg1Nv1XErADrz7ZYjhDidUPAgM/nubile/11//", 6100);
		config.addOption("mainport.params.servlet.2.bookmarks.0.title", 1, "Nubile", 6101);
		config.addOption("mainport.params.servlet.2.bookmarks.0.activelinkFile", 1, "nubile.png", 6102);
		config.addOption("mainport.params.servlet.2.bookmarks.0.description", 1,
			"Freesite aimed at beginners. Learn basic methods of retrieving and inserting data from and into freenet. " +
			"Unfortunately some of the specifics are slightly outdated.", 6103);

		config.addOption("mainport.params.servlet.2.bookmarks.1.key", 1, "SSK@a7SLJXxcl2eT967cHE5~mzQaYTkPAgM/newtofn/7//", 6130);
		config.addOption("mainport.params.servlet.2.bookmarks.1.title", 1, "New to Freenet?", 6131);
		config.addOption("mainport.params.servlet.2.bookmarks.1.activelinkFile", 1, "newtofn.jpg", 6132);
		config.addOption("mainport.params.servlet.2.bookmarks.1.description", 1,
			"Another freesite aimed at beginners, with emphasis on Windows  users connecting via modem.", 6133);

		config.addOption("mainport.params.servlet.2.bookmarks.2.key", 1, "SSK@y~-NCd~il6RMxOe9jjf~VR7mSYwPAgM,ds52dBUTmr8fSHePn1Sn4g/OneMore//", 6140);
		config.addOption("mainport.params.servlet.2.bookmarks.2.title", 1, "One More Time", 6141);
		config.addOption("mainport.params.servlet.2.bookmarks.2.activelinkFile", 1, "activelink.gif", 6142);
		config.addOption("mainport.params.servlet.2.bookmarks.2.description", 1,
			"A freesite indexing other freesites. The index is categorized, with one page per category.", 6143);

		config.addOption("mainport.params.servlet.2.bookmarks.3.key", 1, "SSK@pHWN3FglLQOoBleE3pQ3EX3PLFoPAgM,xgvJe~4roO7d3lT~4QPIzA/atwocentindex//", 6150);
		config.addOption("mainport.params.servlet.2.bookmarks.3.title", 1, "A Two Cent Index", 6151);
		config.addOption("mainport.params.servlet.2.bookmarks.3.activelinkFile", 1, "activelink.jpg", 6152);
		config.addOption("mainport.params.servlet.2.bookmarks.3.description", 1, "A freesite index with narrower inclusion criteria.", 6153);

		config.addOption("mainport.params.servlet.2.bookmarks.4.key", 1, "SSK@rgFrfo~dAesFgV5vylYVNvNGXO0PAgM,wo3T~oLnVbWq-vuD2Kr86Q/frost/19//", 6160);
		config.addOption("mainport.params.servlet.2.bookmarks.4.title", 1, "Frost", 6161);
		config.addOption("mainport.params.servlet.2.bookmarks.4.activelinkFile", 1, "activelink.png", 6162);
		config.addOption("mainport.params.servlet.2.bookmarks.4.description", 1, "Bulletin board and filesharing software.", 6163);

		config.addOption("mainport.params.servlet.2.bookmarks.5.key", 1, "SSK@TEx6TiaPeszpV4AFw3ToutDb49EPAgM/mytwocents/59//", 6200);
		config.addOption("mainport.params.servlet.2.bookmarks.5.title", 1, "My Two Cents Worth", 6201);
		config.addOption("mainport.params.servlet.2.bookmarks.5.activelinkFile", 1, "activelink.jpg", 6202);
		config.addOption("mainport.params.servlet.2.bookmarks.5.description", 1,
			"A flog (freenet blog) about \"my 2cents worth on just about any subject\", according to the author.", 6203);
		// end bookmarks

		
		
		config.addOption(
			"mainport.params.servlet.3.uri",
			1,
			"/servlet/images/",
			4130);
		config.addOption("mainport.params.servlet.3.method", 1, "GET", 4131);
		config.addOption(
			"mainport.params.servlet.3.class",
			1,
			"freenet.client.http.ImageServlet",
			4132);
		config.addOption(
			"mainport.params.servlet.3.name",
			1,
			"Server Images",
			4133);

		config.addOption(
			"mainport.params.servlet.4.uri",
			1,
			"/servlet/Insert",
			4140);
		config.addOption("mainport.params.servlet.4.method", 1, "BOTH", 4141);
		config.addOption(
			"mainport.params.servlet.4.class",
			1,
			"freenet.client.http.InsertServlet",
			4142);
		config.addOption(
			"mainport.params.servlet.4.name",
			1,
			"Insert Proxy Status",
			4143);
		config.addOption(
			"mainport.params.servlet.4.params.insertHtl",
			1,
			20,
			4144);
		config.addOption(
			"mainport.params.servlet.4.params.sfInsertThreads",
			1,
			30,
			4145);
		config.addOption(
			"mainport.params.servlet.4.params.sfInsertRetries",
			1,
			3,
			4146);
		config.addOption(
			"mainport.params.servlet.4.params.sfRefreshIntervalSecs",
			1,
			15,
			4147);

		config.addOption("mainport.params.servlet.6.uri", 1, "/", 4190);
		config.addOption("mainport.params.servlet.6.method", 1, "POST", 4191);
		config.addOption(
			"mainport.params.servlet.6.class",
			1,
			"freenet.client.http.InsertServlet",
			4192);
		config.addOption(
			"mainport.params.servlet.6.name",
			1,
			"Insert Proxy",
			4193);
		config.addOption(
			"mainport.params.servlet.6.params.insertHtl",
			1,
			20,
			4194);
		config.addOption(
			"mainport.params.servlet.6.params.sfInsertThreads",
			1,
			20,
			4195);
		config.addOption(
			"mainport.params.servlet.6.params.sfInsertRetries",
			1,
			3,
			4196);
		config.addOption(
			"mainport.params.servlet.6.params.sfRefreshIntervalSecs",
			1,
			15,
			4197);

		config.addOption(
			"mainport.params.servlet.5.uri",
			1,
			"/servlet/nodestatus/",
			4150);
		config.addOption("mainport.params.servlet.5.method", 1, "BOTH", 4151);
		config.addOption(
			"mainport.params.servlet.5.class",
			1,
			"freenet.client.http.NodeStatusServlet",
			4152);
		config.addOption(
			"mainport.params.servlet.5.name",
			1,
			"Node Status",
			4153);

		config.addOption(
			"mainport.params.servlet.7.uri",
			1,
			"/servlet/SFRequest/",
			4160);
		config.addOption("mainport.params.servlet.7.method", 1, "BOTH", 4161);
		config.addOption(
			"mainport.params.servlet.7.class",
			1,
			"freenet.client.http.SplitFileRequestServlet",
			4162);
		config.addOption(
			"mainport.params.servlet.7.name",
			1,
			"SplitFile Download Servlet (alpha!)",
			4163);
		config.addOption(
			"mainport.params.servlet.7.params.requestHtl",
			1,
			20,
			4164);
		config.addOption(
			"mainport.params.servlet.7.params.sfBlockRequestHtl",
			1,
			0,
			4165);
		config.addOption(
			"mainport.params.servlet.7.params.sfRequestRetries",
			1,
			4,
			4166);
		// Safer to go straight from 0 to 20
		config.addOption(
			"mainport.params.servlet.7.params.sfRetryHtlIncrement",
			1,
			20,
			4167);
		config.addOption(
			"mainport.params.servlet.7.params.sfRequestThreads",
			1,
			30,
			4168);
		config.addOption(
			"mainport.params.servlet.7.params.sfDoParanoidChecks",
			1,
			true,
			4169);
		config.addOption(
			"mainport.params.servlet.7.params.sfRefreshIntervalSecs",
			1,
			15,
			4170);
		config.addOption(
			"mainport.params.servlet.7.params.sfForceSave",
			1,
			false,
			4171);
		config.addOption(
			"mainport.params.servlet.7.params.sfSkipDS",
			1,
			false,
			4172);
		config.addOption(
			"mainport.params.servlet.7.params.sfUseUI",
			1,
			true,
			4173);
		config.addOption(
			"mainport.params.servlet.7.params.sfRunFilter",
			1,
			true,
			4174);
		config.addOption(
			"mainport.params.servlet.7.params.sfRandomSegs",
			1,
			true,
			4175);
		config.addOption(
			"mainport.params.servlet.7.params.sfFilterParanoidStringCheck",
			1,
			false,
			4176);
		config.addOption(
			"mainport.params.servlet.7.params.sfHealHtl",
			1,
			20,
			4177);
		config.addOption(
			"mainport.params.servlet.7.params.sfHealPercentage",
			1,
			100,
			4178);
		config.addOption(
			"mainport.params.servlet.7.params.sfForceSave",
			1,
			true,
			4179);
		config.addOption(
			"mainport.params.servlet.7.params.maxRetries",
			1,
			50,
			4179);

		String downloadDir;
		try {
			downloadDir =
				System.getProperty("user.home")
					+ File.separator
					+ "freenet-downloads";
		} catch (Throwable e) {
			downloadDir = "";
		}

		config.addOption(
			"mainport.params.servlet.7.params.sfDefaultSaveDir",
			1,
			downloadDir,
			4180);
		config.addOption(
			"mainport.params.servlet.7.params.sfDefaultWriteToDisk",
			1,
			downloadDir.length()!=0,
			4181);
		config.addOption(
			"mainport.params.servlet.7.params.sfDisableWriteToDisk",
			1,
			false,
			4182);
		config.addOption(
			"mainport.params.servlet.8.uri",
			1,
			"/servlet/stream/",
			4190);
		config.addOption("mainport.params.servlet.8.method", 1, "GET", 4191);
		config.addOption(
			"mainport.params.servlet.8.class",
			1,
			"freenet.client.http.StreamServlet",
			4192);
		config.addOption(
			"mainport.params.servlet.8.name",
			1,
			"Freenet Streaming Servlet",
			4193);

		config.addOption(
			"mainport.params.servlet.9.uri",
			1,
			"/servlet/streamInsert/",
			5101);
		config.addOption("mainport.params.servlet.9.method", 1, "GET", 5102);
		config.addOption(
			"mainport.params.servlet.9.class",
			1,
			"freenet.client.http.StreamInsertServlet",
			5103);
		config.addOption(
			"mainport.params.servlet.9.name",
			1,
			"Freenet Stream Insert Servlet",
			5104);

		config.setExpert("mainport.params.servlet.8.uri", true);
		config.setExpert("mainport.params.servlet.8.method", true);
		config.setExpert("mainport.params.servlet.8.class", true);
		config.setExpert("mainport.params.servlet.8.name", true);

		config.setExpert("mainport.params.servlet.9.uri", true);
		config.setExpert("mainport.params.servlet.9.method", true);
		config.setExpert("mainport.params.servlet.9.class", true);
		config.setExpert("mainport.params.servlet.9.name", true);

		config.addOption(
			"mainport.params.defaultServlet.uri",
			1,
			"/default",
			4190);
		config.addOption(
			"mainport.params.defaultServlet.method",
			1,
			"GET",
			4191);
		config.addOption(
			"mainport.params.defaultServlet.class",
			1,
			"freenet.client.http.RedirectServlet",
			4192);
		config.addOption(
			"mainport.params.defaultServlet.name",
			1,
			"Web Interface Redirect",
			4193);
		config.addOption(
			"mainport.params.defaultServlet.params.targetURL",
			1,
			"/servlet/nodeinfo/",
			4194);

		// RouteConnectTimeout
		config.setExpert("routeConnectTimeout", true);
		config.argDesc("routeConnectTimeout", "<millis>");
		config.shortDesc(
			"routeConnectTimeout",
			"wait on new connection when routing.");
		config.longDesc(
			"routeConnectTimeout",
			"The time to wait for connections to be established and ",
			"authenticated before passing by a node while routing out.",
			"Connections that are by passed are still finished and cached ",
			"for the time set by <connectionTimeout> (in milliseconds).");

		// maxHopsToLive
		config.setExpert("maxHopsToLive", true);
		config.argDesc("maxHopsToLive", "<integer>");
		config.shortDesc("maxHopsToLive", "max HTL allowed on routed requests");
		config.longDesc(
			"maxHopsToLive",
			"When forwarding a request, the node will reduce the HTL to this value",
			"if it is found to be in excess.");

		// maxLog2DataSize
		config.setExpert("maxLog2DataSize", true);
		config.argDesc("maxLog2DataSize", "<integer>");
		config.shortDesc(
			"maxLog2DataSize",
			"maximum file data size (log to base 2)");
		config.longDesc(
			"maxLog2DataSize",
			"The logarithm to the base 2 of the maximum file data+metadata size ",
			"that the node will accept. 20 means 1 megabyte, which is reasonable.");

		// probIncHopsSinceReset
		config.setExpert("probIncHopsSinceReset", true);
		config.argDesc("probIncHopsSinceReset", "<number between 0 and 1>");
		config.shortDesc(
			"probIncHopsSinceReset",
			"Probability of incrementing hopsSinceReset when forwarding a request. Leave this alone.");

		// cacheProbPerHop
		config.setExpert("cacheProbPerHop", true);
		config.argDesc("cacheProbPerHop", "<number between 0 and 1>");
		config.longDesc(
			"cacheProbPerHop",
			"Number which is raised to the power of the number of hops since a datasource reset to determine the cache probability. Set lower for better routing, higher for more caching/redundancy. The default is equivalent to approximately 5 nodes caching a file in a request.");

		// minStoreFullPCache
		config.setExpert("minStoreFullPCache", true);
		config.argDesc("minStoreFullPCache", "<number between 0 and 1>");
		config.longDesc(
			"minStoreFullPCache",
			"Minimum proportion of the datastore that must be filled before probabilistic caching kicks in.");

		// minRTFullPRef
		config.setExpert("minRTFullPRef", true);
		config.argDesc("minRTFullPRef", "<number between 0 and 1>");
		config.longDesc(
			"minRTFullPRef",
			"Minimium proportion of the routing table (classic mode) that must be filled before probabilistic ",
			"referencing kicks in.");

		// minRTNodesPRef
		config.setExpert("minRTNodesPRef", true);
		config.argDesc("minRTNodesPRef", "<number between 0 and 1>");
		config.longDesc(
			"minRTNodesPRef",
			"Minimum proportion of the routing table nodes that must be filled and not backed off before ",
			"probabilistic referencing kicks in");

		// nodeFile
		config.setExpert("nodeFile", true);
		config.argDesc("nodeFile", "<file>");
		config.shortDesc("nodeFile", "location of node's key file");
		config.longDesc(
			"nodeFile",
			"The path to the file containing the node's private key, DSA group,",
			"cipher key, etc.  Defaults to node in the current directory.");

		// storeFile
		config.setExpert("storeFile", true);
		config.argDesc("storeFile", "<file>[,..]");
		config.shortDesc(
			"storeFile",
			"location of data store directory - do not put anywhere with existing files");
		config.longDesc(
			"storeFile",
			"The path to the single directory containing the data "
				+ "store.  The total maximum size of the files in the "
				+ "directory is given by <storeSize>. It will create new "
				+ "files and directories in this dir, and DELETE OLD ONES. "
				+ "Defaults to store in the current directory.");

		// storeSize
		config.argDesc(
			"storeSize",
			"<bytes - can use kKmMgGtTpPeE multipliers>");
		config.shortDesc("storeSize", "size of the data store file(s)");
		config.longDesc(
			"storeSize",
			"The byte size of the data store directory.",
			"The maximum sized file that will be cached is 1/100th of",
			"this value.  We recommend the default 256MB, to cache the largest common",
			"file size on freenet, 1MB plus some headers, with plenty of elbowroom, but",
			"any size about 101MB should be adequate (a 1MB chunk is not exactly 1MB...).",
			"Note that if you increase settings such as maximumThreads, you may need to",
			"use a larger store.");

		// storeType
		config.setExpert("storeType", true);
		config.argDesc("storeType", "<string>");
		config.shortDesc(
			"storeType",
			"datastore implementation: \"native\" (new), \"monolithic\" (old, gets the DSB), \"freenet\" (autodetect, prefer native), or \"convert\" (convert old to new)");
		config.longDesc(
			"storeType",
			"Datastore implementation. Put \"native\" (without the quotes) if you want the new native filesystem datastore, which stores the files in a directory. Put \"convert\" to convert from an old monolithic store to a native store. Note that convert uses lots of disk space while doing the conversion (approximately twice the datastore size), and the resulting store may be (temporarily) slightly larger than the old one due to block size mismatch (this will be fixed as soon as the node tries to add a file to the store).");

		// storeBlockSize
		config.setExpert("storeBlockSize", true);
		config.argDesc("storeBlockSize", "<bytes>");
		config.shortDesc(
			"storeBlockSize",
			"Size of filesystem accounting blocks for storeType=native");
		config.longDesc(
			"storeBlockSize",
			"Size of blocks in the underlying filesystem for purposes of calculating space usage when storeType=native.");

		// storeMaxTempFraction
		config.setExpert("storeMaxTempFraction", true);
		config.argDesc("storeMaxTempFraction", "<number between 0 and 1>");
		config.shortDesc(
			"storeMaxTempFraction",
			"Maximum fraction of the datastore to use for temp files (assuming the temp dir is not overridden)");

		// storeCipherName
		config.setExpert("storeCipherName", true);
		config.argDesc("storeCipherName", "<string>");
		config.shortDesc(
			"storeCipherName",
			"name of symmetric cipher algorithm");
		config.longDesc("storeCipherName", "deprecated");

		// storeCipherWidth
		config.setExpert("storeCipherWidth", true);
		config.argDesc("storeCipherWidth", "<integer>");
		config.shortDesc("storeCipherWidth", "bit-width of cipher key");
		config.longDesc("storeCipherWidth", "deprecated");

		// routingDir
		config.setExpert("routingDir", true);
		config.argDesc("routingDir", "<directory>");
		config.shortDesc(
			"routingDir",
			"The directory in which to store the routing table files. Defaults to parent dir of storeDir");

		// useDSIndex
		config.setExpert("useDSIndex", true);
		config.argDesc("useDSIndex", "true|false");
		config.shortDesc("useDSIndex", "Use a datastore index file");
		config.longDesc(
			"useDSIndex",
			"Use a datastore index file. Shorter startup time, but we have to run checkpoints, which lock the datastore, causing a hiccup");

		// rtMaxRefs
		config.setExpert("rtMaxRefs", true);
		config.argDesc("rtMaxRefs", "<integer>");
		config.shortDesc("rtMaxRefs", "max no. of refs per node");
		config.longDesc(
			"rtMaxRefs",
			"The number of references allowed per node in the routing table.",
			"This should not be set too high.");

		// rtMaxNodes
		config.setExpert("rtMaxNodes", true);
		config.argDesc("rtMaxNodes", "<integer>");
		config.shortDesc("rtMaxNodes", "max no. unique nodes in routing table");
		config.longDesc(
			"rtMaxNodes",
			"The number of unique nodes that can be contained in the routing table. Note that the node will try to keep an idle connection open to each of these, so don't set it to more than half the value of maxNodeConnections. Too big or too small will result in inefficient or completely useless routing, or slow specialization; the default 50 is reasonable (if you see another default, it's because you have an OS with too few connections).");

		// doEstimatorSmoothing
		config.setExpert("doEstimatorSmoothing", true);
		config.argDesc("doEstimatorSmoothing", ARG_BOOLEAN);
		config.longDesc("doEstimatorSmoothing", 
				"Whether to use adjacent buckets to estimate the value of a given bucket in a KeyspaceEstimator when it has no reports. "+
				"If you don't understand what I just said you should probably leave it alone!");

		// useFastEstimators
		config.setExpert("useFastEstimators", true);
		config.argDesc("useFastEstimators", ARG_BOOLEAN);
		config.longDesc("useFastEstimators",
				"Whether to use doubles (floating point, 53 bit mantissa, implemented in hardware on most systems) instead of BigIntegers (full 160 bits, slow) in NGRouting estimators.");
		
		// minCP
		config.setExpert("minCP", true);
		config.argDesc("minCP", "<number between 0 and 1>");
		config.shortDesc(
			"minCP",
			"Lower bound on Contact Probability of nodes in the Routing Table");

		// failuresLookupARK
		config.setExpert("failuresLookupARK", true);
		config.argDesc("failuresLookupARK", "<integer>");
		config.shortDesc(
			"failuresLookupARK",
			"Number of consecutive failures required to trigger an ARK lookup");

		// minARKDelay
		config.setExpert("minARKDelay", true);
		config.argDesc("minARKDelay", "<milliseconds>");
		config.shortDesc(
			"minARKDelay",
			"Minimum time that a node in the routing table must have been uncontactable for before we can trigger an ARK lookup");

		// maxARKThreadsFraction
		config.setExpert("maxARKThreadsFraction", true);
		config.argDesc("maxARKThreadsFraction", "<number between 0 and 1>");
		config.shortDesc(
			"maxARKThreadsFraction",
			"Maximum fraction of maximumThreads to use for ARK lookups");

		// maxRoutingSteps
		config.setExpert("maxRoutingSteps", true);
		config.argDesc("maxRoutingSteps", "<integer>");
		config.shortDesc(
			"maxRoutingSteps",
			"max no. node refs used per routing attempt.");
		config.longDesc(
			"maxRoutingSteps",
			"The maximum number or node refs that will be used to route a request before RNFing. ",
			"-1 means 1/10th the routing table size.");

		// messageStoreSize
		config.setExpert("messageStoreSize", true);
		config.argDesc("messageStoreSize", "<integer>");
		config.shortDesc(
			"messageStoreSize",
			"max no. of simultaneous requests.");
		config.longDesc(
			"messageStoreSize",
			"The number of outstanding message replies the node will",
			"wait for before it starts to abandon them.");

		// failureTableSize
		config.setExpert("failureTableSize", true);
		config.argDesc("failureTableSize", "<integer>");
		config.shortDesc("failureTableSize", "max. no. cached failed keys.");
		config.longDesc(
			"failureTableSize",
			"The number keys that failed to be retrieved the node should key track of.");

		//failureTableItems
		config.setExpert("failureTableItems", true);

		// failureTableTime
		config.setExpert("failureTableTime", true);
		config.argDesc("failureTableTime", "<milliseconds>");
		config.shortDesc("failureTableTime", "max. time to fail keys.");
		config.longDesc(
			"failureTableTime",
			"The amount of time to keep keys cache keys that could not be found and",
			"automatically fail requests for them.");

		// newNodePollInterval
		config.setExpert("newNodePollInterval", true);
		config.argDesc("newNodePollInterval", "<milliseconds>");
		config.shortDesc(
			"newNodePollInterval",
			"interval between polling new nodes");
		config.longDesc(
			"newNodePollInterval",
			"The node will send a request for a random "
				+ "recently requested key to the node in the routing table with the fewest accesses, "
				+ "every N milliseconds. Please enter N.");

		// bandwidthLimit
		config.setExpert("bandwidthLimit", true); // because deprecated
		config.argDesc("bandwidthLimit", "<bytes/sec>");
		config.shortDesc("bandwidthLimit", "DEPRECATED");
		config.setDeprecated("bandwidthLimit", true);
		config.longDesc(
			"bandwidthLimit",
			"The maximum number of bytes per second to transmit, totaled between",
			"incoming and outgoing connections.  Ignored if either inputBandwidthLimit",
			"or outputBandwidthLimit is nonzero. DEPRECATED - please set inputBandwidthLimit and outputBandwidthLimit directly. Difficult to implement for NIO and not widely used.");

		// inputBandwidthLimit
		config.argDesc("inputBandwidthLimit", "<bytes/sec>");
		config.shortDesc("inputBandwidthLimit", "incoming bandwidth limit");
		config.longDesc(
			"inputBandwidthLimit",
			"If nonzero, specifies an independent limit for incoming data only, in bytes",
			"per second. A 512kbps broadband (DSL or cable) connection is 64kB/sec, but",
			"you may want to use other things than Freenet on it. However, Freenet's",
			"background usage should be close to the output limit most of the time. ",
			"You may want to set this and then set doLowLevelInputLimiting=false, in ",
			"order to have more accurate pending-transfers load. You SHOULD do this if ",
			"your connection has more outbound than inbound bandwidth.");

		// outputBandwidthLimit
		config.argDesc("outputBandwidthLimit", "<bytes/sec>");
		config.shortDesc(
			"outputBandwidthLimit",
			"if enabled, outgoing bandwidth limit");
		config.longDesc(
			"outputBandwidthLimit",
			"If nonzero, specifies an independent limit for outgoing data only, in bytes",
			"per second. Not entirely accurate. If you need exact limiting, do it at the",
			"OS level. A typical broadband connection has either a 128kbps or a 256kbps",
			"uplink, this equates to 16kB/sec and 32kB/sec respectively. You will need to",
			"keep some bandwidth back for other apps and for downloads (yes, downloading",
			"uses a small amount of upload bandwidth). We suggest therefore limits of",
			"12000 for a 128kbps upload connection, or 24000 for a 256kbps upload",
			"connection. Most broadband connections have far more download bandwidth than",
			"upload bandwidth... just because you have 1Mbps download, does not mean you",
			"have 1Mbps upload; if you do not know what your connection's upload speed is,",
			"use one of the above options.");

		// averageBandwidthLimit
		config.setExpert("averageBandwidthLimit", true);
		config.argDesc("averageBandwidthLimit", "<bytes/sec>");
		config.setDeprecated("averageBandwidthLimit", true);
		config.shortDesc("averageBandwidthLimit", "DEPRECATED");
		config.longDesc(
			"averageBandwidthLimit",
			"The maximum number of bytes per second to transmit (averaged over a week),",
			"totaled between incoming and outgoing connections.  Error to define it if",
			"any of (average)inputBandwidthLimit or (average)outputBandwidthLimit is",
			"nonzero. DEPRECATED - please set inputBandwidthLimit and outputBandwidthLimit directly. Difficult to implement for NIO and not widely used.");

		// averageInputBandwidthLimit
		config.argDesc("averageInputBandwidthLimit", "<bytes/sec>");
		config.shortDesc(
			"averageInputBandwidthLimit",
			"incoming bandwidth limit averaged over a week");
		config.longDesc(
			"averageInputBandwidthLimit",
			"If nonzero, specifies an independent limit for incoming data only (averaged",
			"over a week).  (overrides averageBandwidthLimit if nonzero)");

		// averageOutputBandwidthLimit
		config.argDesc("averageOutputBandwidthLimit", "<bytes/sec>");
		config.shortDesc(
			"averageOutputBandwidthLimit",
			"outgoing bandwidth limit averaged over a week");
		config.longDesc(
			"averageOutputBandwidthLimit",
			"If nonzero, specifies an independent limit for outgoing data only (averaged",
			"over a week).  (overrides bandwidthLimit if nonzero)");

		// maxConnectionsPerMinute
		config.setExpert("maxConnectionsPerMinute", true);
		config.argDesc("maxConnectionsPerMinute", "<int>");
		config.shortDesc(
			"maxConnectionsPerMinute",
			"Max no. of connections in one minute.");
		config.longDesc(
			"maxConnectionsPerMinute",
			"The maximum number of outgoing connections established in a one minute period. "
				+ "Deprecated and ignored.");

		// maxConnectionsMinute
		config.setExpert("maxConnectionsMinute", true);
		config.argDesc("maxConnectionsMinute", "<milliseconds>");
		config.shortDesc(
			"maxConnectionsMinute",
			"Length of a minute in milliseconds for purposes of maxConnectionsPerMinute");
		config.longDesc(
			"maxConnectionsMinute",
			"The length of the period over which there must be at most maxConnectionsPerMinute connections. Deprecated"
				+ " and ignored.");

		// maxRequestsPerInterval
		config.setExpert("maxRequestsPerInterval", true);
		config.argDesc("maxRequestsPerInterval", "<int>");
		config.shortDesc(
			"maxRequestsPerInterval",
			"Max no. of outgoing requests per maxRequestsInterval.");
		config.longDesc(
			"maxRequestsPerInterval",
			"The maximum number of outgoing requests per maxRequestsInterval. -1 = disable.");

		// maxRequestsInterval
		config.setExpert("maxRequestsInterval", true);
		config.argDesc("maxRequestsInterval", "<milliseconds>");
		config.shortDesc(
			"maxRequestsInterval",
			"Length of the period in milliseconds for purposes of maxRequestsPerInterval");
		config.longDesc(
			"maxRequestsInterval",
			"The length of the period over which there must be at most maxRequestsPerInterval connections.");

		// maxNodeConnections
		config.setExpert("maxNodeConnections", true);
		config.argDesc("maxNodeConnections", "<positive integer>");
		config.shortDesc(
			"maxNodeConnections",
			"Max. no. of connections to other "
				+ "nodes. Deprecated unless maximumThreads=0.");
		config.longDesc(
			"maxNodeConnections",
			"The maximum number of incoming and outgoing connections to "
				+ "allow at the same time. Forced to 0.4*maximumThreads unless"
				+ " maximumThreads = 0.");

		// maxOpenConnectionsNewbieFraction
		config.setExpert("maxOpenConnectionsNewbieFraction", true);
		config.argDesc  ("maxOpenConnectionsNewbieFraction", "<number between 0.0 and 1.0>");
		config.shortDesc("maxOpenConnectionsNewbieFraction", 
				"Proportion of open connections limit that may be newbie nodes before we start "+
				"rejecting new connections (unless there are free slots)");
		
		// maxNodeFilesOpen
		config.setExpert("maxNodeFilesOpen", true);
		config.argDesc("maxNodeFilesOpen", "<positive integer>");
		config.longDesc(
			"maxNodeFilesOpen",
			"Maximum number of file descriptors used by the node for files. Not including connections.");

		// maxNegotiations
		config.setExpert("maxNegotiations", true);
		config.argDesc  ("maxNegotiations", "<positive integer>");
		config.shortDesc("maxNegotiations", "maximum number of simultaneous connection opens initiated by the node");
		
		// ipAddress
		config.setExpert("ipAddress", true);
		config.argDesc("ipAddress", "xxx.xxx.xxx.xxx");
		config.shortDesc(
			"ipAddress",
			"your IP as seen by the public internet (normally this is autoconfigured)");
		config.longDesc(
			"ipAddress",
			"The IP address of this node as seen by the "
				+ "public Internet. You only need to override this "
				+ "if it cannot be autodetected, for example if you "
				+ "have a NAT (a.k.a. IP Masquerading) "
				+ "firewall/router, in which case you will need "
				+ "to set it to the IP address or DNS name of the "
				+ "internet-side interface of the router, which "
				+ "needs to be static (www.dyndns.org and similar "
				+ "services can help here if you have a dynamic IP).");

		// listenPort
		config.argDesc("listenPort", "<port no.>");
		config.shortDesc("listenPort", "incoming FNP port");
		config.longDesc(
			"listenPort",
			"The port to listen for incoming FNP (Freenet Node Protocol) connections on.");

		// clientPort
		config.setExpert("clientPort", true);
		config.argDesc("clientPort", "<port no.>");
		config.shortDesc("clientPort", "incoming FCP port");
		config.longDesc(
			"clientPort",
			"The port to listen for local FCP (Freenet Client Protocol) connections on.");

		// fcpHosts
		config.setExpert("fcpHosts", true);
		config.argDesc("fcpHosts", "<host list>");
		config.shortDesc("fcpHosts", "hosts allowed to connect with FCP");
		config.longDesc(
			"fcpHosts",
			"A comma-separated list of hosts that may connect to the FCP port",
			"(clientPort).  If left blank, only the localhost will be allowed."
				+ " If you set this, make sure localhost is included in the list or "
				+ " access won't be allowed from the local machine. ",
			"May be given as IP addresses or host names.");

		// logLevel
		config.argDesc("logLevel", "<word>");
		config.shortDesc("logLevel", "error, normal, minor, or debug");
		config.longDesc(
			"logLevel",
			"The error reporting threshold, one of:",
			"  Error:   Errors only",
			"  Normal:  Report significant events, and errors",
			"  Minor:   Report minor events, significant events, and errors",
			"  Debug:   Report everything that can be reported");

		// logLevelDetail
		config.setExpert("logLevelDetail", true);
		config.argDesc(
			"logLevelDetail",
			"<list of class name or package name = level e.g. freenet.node.rt:debug,freenet.support:minor>");
		config.shortDesc(
			"logLevelDetail",
			"Detailed list of parts of freenet we want different logging for");

		// logFile
		config.setExpert("logFile", true);
		config.argDesc("logFile", "<filename>|NO");
		config.shortDesc("logFile", "path to the log file, or NO for STDERR");
		config.longDesc(
			"logFile",
			"The name of the log file (`NO' to log to standard out)");

		// logFormat
		config.setExpert("logFormat", true);
		config.argDesc("logFormat", "<tmpl.>");
		config.shortDesc("logFormat", "template, like d:c:h:t:p:m");
		config.longDesc(
			"logFormat",
			"A template string for log messages.  All non-alphabet characters are",
			"reproduced verbatim.  Alphabet characters are substituted as follows:",
			"d = date (timestamp), c = class name of the source object,",
			"h = hashcode of the object, t = thread name, p = priority,",
			"m = the actual log message, u = name the local interface");

		// logDate
		config.setExpert("logDate", true);
		config.argDesc("logDate", "<tmpl.>");
		config.shortDesc("logDate", "java style date/time template");
		config.longDesc(
			"logDate",
			"A template for formatting the timestamp in log messages.  Defaults to",
			"the locale specific fully specified date format.  The template string",
			"is an ordinary Java date/time template - see:",
			"http://java.sun.com/products/jdk/1.1/docs/api/java.text.SimpleDateFormat.html");

		// logMaxLinesCached
		config.setExpert("logMaxLinesCached", true);
		config.argDesc("logMaxLinesCached", "<integer>");
		config.shortDesc(
			"logMaxLinesCached",
			"Maximum number of log lines to cache, anything above this will be discarded to prevent deadlocks and blocking");

		// logMaxBytes
		config.setExpert("logMaxBytesCached", true);
		config.argDesc(
			"logMaxBytesCached",
			"<bytes - can use kKmMgGg multipliers>");
		config.shortDesc(
			"logMaxBytesCached",
			"Maximum number of logged bytes to cache, anything above this will be discarded to prevent deadlocks and blocking");

		// logRotate
		config.setExpert("logRotate", true);
		config.argDesc("logRotate", "<true|false>");
		config.shortDesc(
			"logRotate",
			"Whether to rotate the log files. This is best done inside freenet because restarting Fred is slow and loses open connections.");

		// logRotateUseNativeGzip
		config.setExpert("logRotateUseNativeGzip", true);
		config.argDesc("logRotateUseNativeGzip", "<true|false>");
		config.shortDesc(
			"logRotateUseNativeGzip",
			"Whether to launch a native gzip to compress the old log files.");

		// logRotateInterval
		config.setExpert("logRotateInterval", true);
		config.argDesc("logRotateInterval", "<minute|hour|week|month|year>");
		config.shortDesc(
			"logRotateInterval",
			"Time interval for logfile rotation.");

		// logOverwrite
		config.setExpert("logOverwrite", true);
		config.argDesc("logOverwrite", "<yes|no>");
		config.shortDesc(
			"logOverwrite",
			"Whether to overwrite old log files; otherwise they are appended to.");

		// seedFile
		config.argDesc("seedFile", "<file>");
		config.shortDesc("seedFile", "initial node ref(s), for announcing");
		config.longDesc(
			"seedFile",
			"A file containing one or more node references which will be incorporated",
			"into the node's routing table on startup.  A reference is only added if",
			"there is no previously existing reference to that node.  When this node",
			"announces, it will announce to the nodes listed in this file.");

		// routingTableImpl
		config.setExpert("routingTableImpl", true);
		config.argDesc("routingTableImpl", "classic or ng");
		config.longDesc(
			"routingTableImpl",
			"Set to ng for the Next Generation Routing implementation, classic for the old Freenet routing algorithm.");

		// doAnnounce
		config.setExpert("doAnnounce", true);
		config.argDesc("doAnnounce", "yes|no");
		config.shortDesc("doAnnounce", "whether to automatically announce");
		config.longDesc(
			"doAnnounce",
			"If this is true, the node will automatically announce to all nodes in",
			"the <seedFile> file, as specified by <announcementDelay>, etc.");

		// announcementPeers
		config.setExpert("announcementHTL", true);
		config.argDesc("announcementHTL", "<integer>");
		config.shortDesc(
			"announcementHTL",
			"no. of nodes announcement goes to");
		config.longDesc(
			"announcementHTL",
			"The number of nodes that each announcement message should be"
				+ "sent to.");

		// announcementAttempts
		config.setExpert("announcementAttempts", true);
		config.argDesc("announcementAttempts", "<integer>");
		config.shortDesc(
			"announcementAttempts",
			"number of attempts to announce");
		config.longDesc(
			"announcementAttempts",
			"The number of attempts to make at announcing this node "
				+ "in any given attempt for any given node . Zero means "
				+ "the node will not announce itself.");

		// announcementPollInterval
		config.setExpert("announcementPollInterval", true);
		config.argDesc("announcementPollInterval", "<milliseconds>");
		config.shortDesc(
			"announcementPollInterval",
			"Interval between polling for inactivity to reannounce");
		config.longDesc(
			"announcementPollInterval",
			"The time between polling for 1 hours no incoming requests to ",
			"force reannouncement.");

		// announcementFirstDelay
		config.setExpert("announcementFirstDelay", true);
		config.argDesc("announcementFirstDelay", "<milliseconds>");
		config.shortDesc(
			"announcementFirstDelay",
			"Delay before announcing on first startup");

		// announcementThreads
		config.setExpert("announcementThreads", true);
		config.argDesc("announcementThreads", "<integer>");
		config.shortDesc(
			"announcementThreads",
			"Number of simultaneous announcement attempts");
		config.longDesc(
			"announcementThreads",
			"The number of simultaneous announcement attempts; when a"
				+ "permanent node sees no traffic for a while, or when it"
				+ "initially joins the network, it will try to announce to "
				+ "this many nodes.");

		// announcementUseRT
		config.setExpert("announcementUseRT", true);
		config.argDesc("announcementUseRT", "yes|no");
		config.shortDesc(
			"announcementUseRT",
			"announce to nodes from routing table?");
		config.longDesc(
			"announcementThreads",
			"If we run out of seed nodes, we can use other nodes from the"
				+ "routing table to announce to. However, since the trust level"
				+ "of these nodes is unknown, this is not recommended for the"
				+ "truly paranoid.");

		// initialRequests
		config.setExpert("initialRequests", true);
		config.argDesc("initialRequests", "<integer>");
		config.shortDesc("initialRequests", "number of initial requests");
		config.longDesc(
			"initialRequests",
			"The number of keys to request from the returned close values",
			"after an Announcement (this is per announcement made).");

		// initialRequestHTL
		config.setExpert("initialRequestHTL", true);
		config.argDesc("initialRequestHTL", "<integer>");
		config.shortDesc("initialRequestHTL", "HopsToLive on initial requests");
		config.longDesc(
			"initialRequestHTL",
			"The hops that initial requests should make.");

		//doLoadBalance
		config.setExpert("doLoadBalance", true);
		config.argDesc("doLoadBalance", "yes|no");
		config.shortDesc("doLoadBalance", "Use load balancing.");
		config.longDesc(
			"doLoadBalance",
			"Whether to emply load balancing algorithms against the ",
			"network.");

		//localIsOK
		config.setExpert("localIsOK", true);
		config.argDesc("localIsOK", "yes|no");
		config.shortDesc(
			"localIsOK",
			"set yes to allow permanent nodes with non-internet-resolvable addresses. Do not use this except in a local testing network.");

		//dontLimitClients
		config.setExpert("dontLimitClients", true);
		config.argDesc("dontLimitClients", "yes|no");
		config.shortDesc(
			"dontLimitClients",
			"set yes to not bandwidth throttle connections to LocalInterfaces i.e. FCP and mainport");

		//limitAll
		config.setExpert("limitAll", true);
		config.argDesc("limitAll", "yes|no");
		config.shortDesc(
			"limitAll",
			"set yes to run the bandwidth limiter over all connections, local, network or internet. Overridden by dontLimitClients.");

		//mainportURIOverride
		config.setExpert("mainportURIOverride", true);
		config.argDesc("mainportURIOverride", "URI");
		config.shortDesc(
			"mainportURIOverride",
			"URI to mainport servlet, e.g. for SSL tunneling");

		//distributionURIOverride
		config.setExpert("distributionURIOverride", true);
		config.argDesc("distributionURIOverride", "URI");
		config.shortDesc(
			"distributionURIOverride",
			"URI to distribution servlet, e.g. for SSL tunneling");

		//aggressiveGC
		config.setExpert("aggressiveGC", true);
		config.argDesc("aggressiveGC", "<seconds>");
		config.shortDesc(
			"aggressiveGC",
			"How often (in seconds) to do aggressive garbage collection. May impact performance but should reduce working set.  Set to 0 to disable.");

		//configUpdateInterval
		config.setExpert("configUpdateInterval", true);
		config.argDesc("configUpdateInterval", "<minutes>");
		config.shortDesc(
			"configUpdateInterval",
			"How often (in minutes) to check for config file changes.  Set to 0 to disable.");

		// seednodesUpdateInterval
		config.setExpert("seednodesUpdateInterval", true);
		config.argDesc("seednodesUpdateInterval", "<minutes>");
		config.shortDesc(
			"seednodesUpdateInterval",
			"How often (in minutes) to check for seednodes file changes.  Set to 0 to disable.");

		// adminPassword
		config.setExpert("adminPassword", true);
		config.argDesc("adminPassword", "<string>");
		config.shortDesc(
			"adminPassword",
			"allows remote admin using password.");
		config.longDesc(
			"adminPassword",
			"If this is set then users that can provide the password can",
			"can have administrative access. It is recommended that",
			"you do not use this without also using adminPeer below",
			"in which case both are required.");

		// adminPeer
		config.setExpert("adminPeer", true);
		config.argDesc("adminPeer", "<Identity FieldSet>");
		config.shortDesc("adminPeer", "allows remote admin using PKI");
		config.longDesc(
			"adminPeer",
			"If this is set, then users that are authenticated owners",
			"of the given PK identity can have administrative access.",
			"If adminPassword is also set both are required.");

		// ipDetectorInterval
		config.setExpert("ipDetectorInterval", true);
		config.argDesc("ipDetectorInterval", "time in seconds or 0");
		config.shortDesc(
			"ipDetectorInterval",
			"Frequency in seconds to run the IP detector. Set to 0 or less to disable.");

		//defaultToSimpleUIMode
		config.setExpert("defaultToSimpleUIMode", true);
		config.shortDesc(
			"defaultToSimpleUIMode",
			"Wheter the web interface should default to simple mode");
		config.longDesc(
			"defaultToSimpleUIMode",
			"The mainport and distribution servlets can display data in one out of two modes, Simple or Advanced. This parameter selects which of the two modes that should be the default mode.");

		//defaultToOCMHTMLPeerHandlerMode
		config.setExpert("defaultToOCMHTMLPeerHandlerMode", true);
		config.shortDesc(
			"defaultToOCMHTMLPeerHandlerMode",
			"Wheter the PeerHandler mode or the Connections mode should be the default mode int the OCM web interface");
		config.longDesc(
			"defaultToOCMHTMLPeerHandlerMode",
			"The OCM web page display data in one out of two modes, ConnectionsMode or PeerMode. This parameter selects which of the two modes that should be the default mode.");

		// diagnosticsPath
		config.setExpert("diagnosticsPath", true);
		config.argDesc("diagnosticsPath", "<dir>");
		config.shortDesc("diagnosticsPath", "directory to save statistics in");
		config.longDesc(
			"diagnosticsPath",
			"The directory in which to save diagnostics data.  Defaults to",
			"<storePath>/stats if left blank.");

		/*
		 * Class and port of the services are set as installation, because the
		 * bad way that these are read requires them to be in the config. That
		 * is dumb of course - a config file shouldn't even be necessary.
		 * Hopefully I will fix that some day... -oskar
		 */

		// NOTE: The default options for fproxy were moved into
		//       addDefaultHTTPPortParams. 20020922. --gj.
		//

		// Options for NodeStatusServlet.
		//config.addOption("nodestatus.class", 1,
		// "freenet.client.http.NodeStatusServlet", 2040, true);
		//config.addOption("nodestatus.port", 1, 8889, 2041, true);

		// Options for distribution servlet
		config.addOption(
			"distribution.class",
			1,
			"freenet.node.http.DistributionServlet",
			2100);
		config.addOption("distribution.port", 1, 8891, 2101);
		config.addOption("distribution.params.unpacked", 1, ".", 2102);
		config.addOption("distribution.params.distribDir", 1, "", 2105);
		config.addOption("distribution.params.generatorAllowedHosts", 1, "localhost", 2104);
		config.addOption("distribution.allowedHosts", 1, "*", 2103);

		// Turn on fproxy, nodeinfo and NodeStatusServlet by default.

		// mainport is a MultipleHttpServletContainer instance which
		// runs fproxy and nodeinfo on the same port.
		config.addOption("services", 1, "mainport, distribution", 2011);

		config.addOption(
			"mainport.params.servlet.10.uri",
			1,
			"/servlet/bookmarkmanager",
			7000);
		config.addOption("mainport.params.servlet.10.method", 1, "GET", 7001);
		config.addOption(
			"mainport.params.servlet.10.class",
			1,
			"freenet.node.http.BookmarkManagerServlet",
			7002);
		config.addOption(
			"mainport.params.servlet.10.name",
			1,
			"Bookmark Manager Servlet",
			7003);

		config.setExpert("mainport.params.servlet.10.uri", true);
		config.setExpert("mainport.params.servlet.10.method", true);
		config.setExpert("mainport.params.servlet.10.class", true);
		config.setExpert("mainport.params.servlet.10.name", true);

		config.addOption(
			"mainport.params.servlet.11.uri",
			1,
			"/servlet/coloredpixel",
			7000);
		config.addOption("mainport.params.servlet.11.method", 1, "GET", 7001);
		config.addOption(
			"mainport.params.servlet.11.class",
			1,
			"freenet.node.http.ColoredPixelServlet",
			7002);
		config.addOption(
			"mainport.params.servlet.11.name",
			1,
			"Colored Pixel Servlet",
			7003);

		config.setExpert("mainport.params.servlet.11.uri", true);
		config.setExpert("mainport.params.servlet.11.method", true);
		config.setExpert("mainport.params.servlet.11.class", true);
		config.setExpert("mainport.params.servlet.11.name", true);

		// NodeStatusServlet class
		/*
		 * config.setExpert ("nodestatus.class", true); config.argDesc
		 * ("nodestatus.class", " <class name> "); config.shortDesc
		 * ("nodestatus.class", "NodeStatusServlet class"); config.longDesc
		 * ("nodestatus.class", "The Java class for the NodeStatusServlet. You
		 * shouldn't need to change this."
		 */

		// NodeStatusServlet port
		/*
		 * config.setExpert ("nodestatus.port", true); config.argDesc
		 * ("nodestatus.port", " <port number> "); config.shortDesc
		 * ("nodestatus.port", "NodeStatusServlet listen port");
		 * config.longDesc ("nodestatus.port", "The port that the node status
		 * servlet listens for HTTP requests on."
		 */

		config.setExpert("services", true);
		config.argDesc("services", "service_0,service_1,...");
		config.shortDesc("services", "services run at start up");
		config.longDesc(
			"services",
			"A comma delimited list of services that are run when the node starts. "
				+ "If you don't know what this means, just accept the defaults.");

		// DistributionServlet class
		config.setExpert("distribution.class", true);
		config.argDesc("distribution.class", "<class name>");
		config.shortDesc("distribution.class", "Distribution servlet class");
		config.longDesc(
			"distribution.class",
			"The Java class of the distribution servlet. You shouldn't need to touch this.");

		config.setExpert("distribution.port", true);
		config.argDesc("distribution.port", "<port number>");
		config.shortDesc(
			"distribution.port",
			"DistributionServlet listen port");
		config.longDesc(
			"distribution.port",
			"The port that the distribution servlet listens for HTTP requests on.");

		// distribution.params
		config.setExpert("distribution.params.unpacked", true);
		config.argDesc("distribution.params.unpacked", "<directory>");
		config.shortDesc(
			"distribution.params.unpacked",
			"Dir where freenet was unpacked");
		config.longDesc(
			"distribution.params.unpacked",
			"A directory containing (some of) the files needed for the Distribution Servlet - for example, a CVS tree, or where the UNIX tarball was unpacked.");

		// distribution.params.distribDir
		config.setExpert("distribution.params.distribDir", true);
		config.argDesc("distribution.params.distribDir", "<directory>");
		config.shortDesc(
			"distribution.params.distribDir",
			"Dir to store redistributibles for the Distribution Servlet");
		config.longDesc(
			"distribution.params.distribDir",
			"Directory used by the node to store redistributibles for the Distribution Servlet - there is rarely any need to override this.");

		// distribution.allowedHosts - whole internet by default
		config.setExpert("distribution.allowedHosts", true);
		config.argDesc("distribution.allowedHosts", "<list of IP addresses>");
		config.shortDesc(
			"distribution.allowedHosts",
			"List of addresses that will be allowed to access distribution pages from the DistributionServlet");
		config.longDesc(
			"distribution.allowedHosts",
			"These IP addresses will be allowed to access the distribution pages generated by the DistributionServlet. Default * means everyone.");

		// distribution.generatorAllowedHosts - localhost only by default
		config.setExpert("distribution.params.generatorAllowedHosts", true);
		config.argDesc("distribution.params.generatorAllowedHosts", "<list of IP addresses>");
		config.shortDesc("distribution.params.generatorAllowedHosts", "Lists of addresses that will be allowed to generate new distribution pages.");
		config.longDesc("distribution.params.generatorAllowedHosts", "Lists of addresses that will be allowed to generate new distribution pages. Default is localhost only.");

		config.setExpert("logOutputBytes", true);
		config.argDesc("logOutputBytes", "true/false");
		config.shortDesc(
			"logOutputBytes",
			"Set true to count non-local TCP bytes sent for diagnostics."
				+ " Also used for medium range bandwidth limiting."
				+ " Since we for performance reasons don't limit messages"
				+ " and connection negotiation, we need to have some other"
				+ " way to keep bandwidth under control. This is it. We"
				+ " reject all queries if the previous minute's total was"
				+ " more than 50% over the limit.");

		config.setExpert("logInputBytes", true);
		config.argDesc  ("logInputBytes", "true/false");
		config.shortDesc("logInputBytes", "Set true to count non-local TCP bytes "
				+ "received for diagnostics. Also used for part of rate limiting.");
		
		config.setExpert("watchme", true);
		config.argDesc("watchme", "true/false");
		config.shortDesc(
			"watchme",
			"Debugging only, setting this to true will remove your anonymity!");

		config.setExpert("watchmeRetries", true);
		config.argDesc("watchmeRetries", "<integer>");
		config.shortDesc(
			"watchmeRetries",
			"Number of times watchMe will attempt to initialize");

		// FEC options
		config.setExpert("FECTempDir", true); // unnecessary
		config.argDesc("FECTempDir", "<directory>");
		config.shortDesc(
			"FECTempDir",
			"Dir. used for FEC temp files. You don't need to set this.");

		config.setExpert("FEC.Encoders.0.class", true);
		config.argDesc("FEC.Encoders.0.class", "<class name>");
		config.shortDesc(
			"FEC.Encoders.0.class",
			"Default FEC encoder implementation.");

		config.setExpert("FEC.Decoders.0.class", true);
		config.argDesc("FEC.Decoders.0.class", "<class name>");
		config.shortDesc(
			"FEC.Decoders.0.class",
			"Default FEC decoder implementation.");

		// tempDir
		config.setExpert("tempDir", true);
		config.argDesc("tempDir", "<directory>");
		config.shortDesc(
			"tempDir",
			"Dir used for temporary files, currently for fproxy");
		config.longDesc(
			"tempDir",
			"The directory used for temporary files. Used currently by fproxy and the FCP FEC mechanism, if their individual temp dirs are not set. If this is left unset, it will create a tempdir in the datastore (if the datastore is native).");

		// tempInStore
		config.setExpert("tempInStore", true);
		config.argDesc("tempInStore", "true/false");
		config.shortDesc(
			"tempInStore",
			"Does temp space count as part of the datastore?");
		config.longDesc(
			"tempInStore",
			"If true, temp space counts as part of the datastore for space accounting purposes. This means that freenet will never use significantly more disk space than the configured storeSize (ignoring space used for log files and routing table files), but it also means that if you have a small store you may not be able to download large files.");

		// doRequestTriageByDelay
		config.setExpert("doRequestTriageByDelay", true);
		config.argDesc("doRequestTriageByDelay", "true/false");
		config.shortDesc(
			"doRequestTriageByDelay",
			"triage requests if tickerDelay gets too high");
		config.longDesc(
			"doRequestTriageByDelay",
			"If true, above 2000ms "
				+ "tickerDelay (successfulDelayCutoff) all incoming requests "
				+ "will be rejected, and above 1000ms, nearly all incoming "
				+ "requests will be rejected. This is an attempt to prevent "
				+ "the node from totally overwhelming the hardware it runs "
				+ "on, and slowing down the network in the process.");

		config.setExpert("doRequestTriageBySendTime", true);
		config.argDesc("doRequestTriageBySendTime", "true/false");
		config.shortDesc(
			"doRequestTriageBySendTime",
			"Triage requests if messageSendTimeRequest > requestSendTimeCutoff");
		config.longDesc(
			"doRequestTriageBySendTime",
			"If true, when messageSendTimeRequest goes above requestSendTimeCutoff, nearly all incoming requests will be rejected. messageSendTime correlates with CPU and bandwidth usage, and if it is too high your node will not do any useful work anyway because the messages will time out.");

		// overloadLow
		config.setExpert("overloadLow", true);
		config.argDesc("overloadLow", "<float between 0 and 1>");
		config.shortDesc(
			"overloadLow",
			"start to selectively reject requests at this load");
		config.longDesc(
			"overloadLow",
			"The node will start to selectively reject requests above this load level.");

		// overloadHigh
		config.setExpert("overloadHigh", true);
		config.argDesc("overloadHigh", "<float between 0 and infinity>");
		config.shortDesc("overloadHigh", "reject all requests above this load");
		config.longDesc(
			"overloadHigh",
			"The node will reject all QueryRequests above this load level. "+
			"Should be over 1.0, because rate limiting will try to keep the load near 100%.");

		// requestDelayCutoff
		config.setExpert("requestDelayCutoff", true);
		config.argDesc("requestDelayCutoff", "<milliseconds>");
		config.shortDesc(
			"requestDelayCutoff",
			"reject queries above this routingTime");
		config.longDesc(
			"requestDelayCutoff",
			"The node will reject nearly all incoming queries when routingTime is over this value.");

		// successfulDelayCutoff
		config.setExpert("successfulDelayCutoff", true);
		config.argDesc("successfulDelayCutoff", "<milliseconds>");
		config.shortDesc(
			"successfulDelayCutoff",
			"if enabled, reject ALL connections when routingTime > cutoff");
		config.longDesc(
			"successfulDelayCutoff",
			"If doRequestTriageByDelay is true, the node will reject ALL connections when routingTime > successfulDelayCutoff.");

		// requestSendTimeCutoff
		config.setExpert("requestSendTimeCutoff", true);
		config.argDesc("requestSendTimeCutoff", "<milliseconds>");
		config.shortDesc(
			"requestSendTimeCutoff",
			"if enabled, reject queries when messageSendTimeRequest > cutoff");
		config.longDesc(
			"requestSendTimeCutoff",
			"If doRequestTriageBySendTime is true, the node will reject queries when messageSendTimeRequest > requestSendTimeCutoff.");

		// successfulSendTimeCutoff
		config.setExpert("successfulSendTimeCutoff", true);
		config.argDesc("successfulSendTimeCutoff", "<integer, per minute>");
		config.shortDesc(
			"successfulSendTimeCutoff",
			"if enabled, reject ALL connections if messageSendTimeRequest > cutoff");
		config.longDesc(
			"successfulSendTimeCutoff",
			"If doRequestTriageBySendTime is true, the node will reject ALL connections if messageSendTimeRequest > successfulSendTimeCutoff");

		// doOutLimitCutoff
		config.setExpert("doOutLimitCutoff", true);
		config.argDesc("doOutLimitCutoff", "<true|false>");
		config.shortDesc(
			"doOutLimitCutoff",
			"enable use of outLimitCutoff value");
		config.longDesc(
			"doOutLimitCutoff",
			"If true, the node will refuse incoming requests according to outLimitCutoff.");

		// outLimitCutoff
		config.setExpert("outLimitCutoff", true);
		config.argDesc("outLimitCutoff", "<floatint point number over 0>");
		config.shortDesc(
			"outLimitCutoff",
			"if enabled, reject queries when outbound bandwidth exceeds outLimitCutoff*outputBandwidthLimit");
		config.longDesc(
			"outLimitCutoff",
			"If the previous minute's outbound transfer limit was exceeded by this factor or more, the node will reject incoming requests.");

		// doOutLimitConnectCutoff
		config.setExpert("doOutLimitConnectCutoff", true);
		config.argDesc("doOutLimitConnectCutoff", "<true|false>");
		config.longDesc(
			"doOutLimitConnectCutoff",
			"Whether to refuse incoming conections when the node's outbound bandwidth limit is exceeded by the factor specified by outLimitConnectCutoff.");

		// outLimitConnectCutoff
		config.setExpert("outLimitConnectCutoff", true);
		config.argDesc(
			"outLimitConnectCutoff",
			"<floating point number over 0>");
		config.longDesc(
			"outLimitConnectCutoff",
			"If the previous minute's outbound transfer limit was exceeded by this factor or more, the node will reject incoming connections.");

		// threadConnectCutoff
		config.setExpert("threadConnectCutoff", true);
		config.argDesc  ("threadConnectCutoff",
				"<floating point number over 0>");
		config.shortDesc ("threadConnectCutoff",
				"Fraction of thread limit over which to reject all incoming connections");
		
		// lowLevelBWLimitFudgeFactor
		config.setExpert("lowLevelBWLimitFudgeFactor", true);
		config.argDesc(
			"lowLevelBWLimitFudgeFactor",
			"<floating point number over 0>");
		config.longDesc(
			"lowLevelBWLimitFudgeFactor",
			"The low level bandwidth limiter is not entirely accurate. It has however been found that it can be made more accurate by multiplying the limit by a fudge factor of around 70%. This may vary from system to system so is a config option. Probably not useful with the new outLimitCutoff option... Leave it alone unless you know what you are doing. Distinct from lowLevelBWLimitMultiplier so that we can change the default even if the latter has been overridden.");

		// doReserveOutputBandwidthForSuccess
		config.setExpert("doReserveOutputBandwidthForSuccess", true);
		config.argDesc("doReserveOutputBandwidthForSuccess", "<true|false>");
		config.longDesc("doReserveOutputBandwidthForSuccess",
				"If set to true, adjust the high level bandwidth limiting to "+
				"underuse our available bandwidth according to the ratio of "+
				"the actual probability of success to the best reasonable "+
				"probability of success (estimated from the nodes in the RT). "+
				"The idea here is that if you limit the number of requests "+
				"this way, then on average the network won't accept too much "+
				"work and end up backing off all over the place. This WILL "+
				"reduce your upstream bandwidth usage, so if you don't like "+
				"that, don't set it.");
		
		// lowLevelBWLimitMultiplier
		config.setExpert("lowLevelBWLimitMultiplier", true);
		config.argDesc(
			"lowLevelBWLimitMultiplier",
			"<floating point number over 0>");
		config.longDesc(
			"lowLevelBWLimitMultiplier",
			"Freenet has 2 separate bandwidth limiting mechanisms. The low level bandwidth limiter is more accurate, but can cause bad things to happeen and is quite expensive in terms of CPU time, and works by delaying messages, which may not be desirable. So by default limiting is done mainly by the high level limiter (see outLimitCutoff and outLimitConnectCutoff), and the low level limiter is set to a higher value to ensure that the node's usage does not get completely out of control when something unusual happens. Set the factor to multiply the bandwidth limits by before feeding them to the low level limiter here.");

		// doLowLevelOutputLimiting
		config.setExpert("doLowLevelOutputLimiting", true);

		// doLowLevelInputLimiting
		config.setExpert("doLowLevelInputLimiting", true);

		// doCPULoad
		config.setExpert ("doCPULoad", true);
		config.argDesc ("doCPULoad", "<true|false>");
		config.longDesc ("doCPULoad", "Whether to try to use the actual CPU"
			+"usage percentage in the load estimate calculation.");

		// sendingQueueLength
		config.setExpert("sendingQueueLength", true);
		config.argDesc("sendingQueueLength", "<positive integer>");
		config.longDesc(
			"sendingQueueLength",
			"Maximum number of messages queued on a connection before we force opening of a new connection. Note that a value too low will cause lots of connections to be opened; this takes time and bandwidth. Note also that messages are not necessarily sent one at a time; over 10 messages (approximately) can sometimes be packed into a single packet.");

		// sendingQueueBytes
		config.setExpert("sendingQueueBytes", true);
		config.argDesc("sendingQueueBytes", "<bytes>");
		config.longDesc(
			"sendingQueueBytes",
			"Maximum number of bytes queued on a connection before we force opening of a new connection. Note that a value too low will cause lots of connections to be opened; this takes time and bandwidth. Note also that messages are not necessarily sent one at a time; over 10 messages (approximately) can sometimes be packed into a single packet.");

		// requestIntervalDefault
		config.setDeprecated("requestIntervalDefault", true);

		// requestIntervalQRFactor
		config.setDeprecated("requestIntervalQRFactor", true);

		// defaultResetProbability
		config.setExpert("defaultResetProbability", true);
		config.argDesc("defaultResetProbability", "<probability>");
		config.shortDesc(
			"defaultResetProbability",
			"Default probability of resetting the DataSource");
		config.longDesc(
			"defaultResetProbability",
			"The node will have this probability, on average (it varies according to load unless you set doLoadBalance=no), of resetting the datasource. Increase this to get more load, reduce it to get less load.");

		// lsMaxTableSize
		config.setExpert("lsMaxTableSize", true);
		config.argDesc("lsMaxTableSize", "<positive integer>");
		config.shortDesc(
			"lsMaxTableSize",
			"Maximum number of peers to save queries/hour from");
		config.longDesc(
			"lsMaxTableSize",
			"LoadStats: Global queries/hour is calculated from at most this number of peers.");

		// lsAcceptRatioSamples
		config.setExpert("lsAcceptRatioSamples", true);
		config.argDesc("lsAcceptRatioSamples", "<positive integer>");
		config.shortDesc(
			"lsAcceptRatioSamples",
			"Number of query arrival time and accepted/rejected status entries.");
		config.longDesc(
			"lsAcceptRatioSamples",
			"LoadStats: Current proportion of requests being accepted is calculated from the fate of this number of recent requests.  The local queries/hour is calculated from the time it took for this number of queries to arrive, averaged at least every time this number of queries is received.");

		// lsHalfLifeHours
		config.setExpert("lsHalfLifeHours", true);
		config.argDesc("lsHalfLifeHours", "<positive real>");
		config.shortDesc(
			"lsHalfLifeHours",
			"Half life in hours of the queries per hour decaying average.");
		config.longDesc(
			"lsHalfLifeHours",
			"Half life in hours of the queries per hour decaying average.  Small values permit the value to change rapidly; large values force it to change slowly.  Use zero to get prior behavior.");

		// logOutboundContacts
		config.setExpert("logOutboundContacts", true);
		config.argDesc("logOutboundContacts", "true/false");
		config.shortDesc(
			"logOutboundContacts",
			"Set true to enable outbound contact monitoring.");

		// logInboundContacts
		config.setExpert("logInboundContacts", true);
		config.argDesc("logInboundContacts", "true/false");
		config.shortDesc(
			"logInboundContacts",
			"Set true to enable inbound contact monitoring.");

		// logInboundRequests
		config.setExpert("logInboundRequests", true);
		config.argDesc("logInboundRequests", "true/false");
		config.shortDesc(
			"logInboundRequests",
			"Set true to enable per host inbound request monitoring.");

		// logOutboundRequests
		config.setExpert("logOutboundRequests", true);
		config.argDesc("logOutboundRequests", "true/false");
		config.shortDesc(
			"logOutboundRequests",
			"Set true to enable per host outbound request monitoring.");

		// logInboundInsertRequestDist
		config.setExpert("logInboundInsertRequestDist", true);
		config.argDesc("logInboundInsertRequestDist", "true/false");
		config.shortDesc(
			"logInboundInsertRequestDist",
			"Set true to enable logging of inbound InsertRequest key distribution.");

		// logSuccessfulInsertRequestDist
		config.setExpert("logSuccessfulInsertRequestDist", true);
		config.argDesc("logSuccessfulInsertRequestDist", "true/false");
		config.shortDesc(
			"logSuccessfulInsertRequestDist",
			"Set true to enable logging of successful inbound InsertRequests' key distribution.");

		// FECInstanceCacheSize
		config.setExpert("FECInstanceCacheSize", true);
		config.argDesc("FECInstanceCacheSize", "<integer>");
		config.shortDesc(
			"FECInstanceCacheSize",
			"Number of FEC instances to cache. Set to 1 unless you expect more than one simultaneous FEC operation.");

		// FECMaxConcurrentCodecs
		config.setExpert("FECMaxConcurrentCodecs", true);
		config.argDesc("FECMaxConcurrentCodecs", "<integer>");
		config.shortDesc(
			"FECMaxConcurrentCodecs",
			"Number of concurrent FEC encodes/decodes allowed. "
				+ "Each codec can use up to 24Mb of memory.");

		// publicNode
		config.setExpert("publicNode", true);
		config.argDesc("publicNode", "true/false");
		config.shortDesc(
			"publicNode",
			"Disables anonymity threatening servlets and infolets on a multi-user machine");

		// httpInserts
		config.setExpert("httpInserts", true);
		config.argDesc("httpInserts", "true/false");
		config.shortDesc(
			"httpInserts",
			"Set false to disable inserts through FProxy.");

		// fcpInserts
		config.setExpert("fcpInserts", true);
		config.argDesc("fcpInserts", "true/false");
		config.shortDesc("fcpInserts", "Set false to disable FCP insertion.");

		// UITemplateSet
		config.setExpert("UITemplateSet", true);
		config.argDesc("UITemplateSet", "name of a template set");
		config.longDesc(
			"UITemplateSet",
			"Template set to use for user interface over HTTP. Default is aqua. Change it if you want to use another one - currently there is just one extra set (simple), but they can be added to src/freenet/node/http/templates/ easily enough");
		// filterPassThroughMimeTypes
		config.setExpert("filterPassThroughMimeTypes", true /* ? */
		);
		config.argDesc(
			"filterPassThroughMimeTypes",
			"comma delimited list of MIME types");
		config.shortDesc(
			"filterPassThroughMimeTypes",
			"safe MIME types that will be passed through to the browser without query or filtering");

		// mainport.class
		config.setExpert("mainport.class", true);
		config.argDesc("mainport.class", "interface class");
		config.shortDesc(
			"mainport.class",
			"Name of the interface class to run the mainport service. Leave it alone.");
		config.longDesc(
			"mainport.class",
			"Name of the interface class to run the mainport service. You do not need to change this.");

		// mainport.port
		config.setExpert("mainport.port", true);
		config.argDesc("mainport.port", "port number");
		config.shortDesc(
			"mainport.port",
			"Port to run the main Freenet HTTP interface on");
		config.longDesc(
			"mainport.port",
			"Port to run the main Freenet HTTP interface on... this is the port that is accessed by your web browser when you are browsing freenet via fproxy, or looking at the various status monitors. This is normally only accessible from localhost, and is different from the public FNP port that other freenet nodes talk to, the FCP port that client programs talk to, and the distribution port that you can run a freenet distribution website on.");

		// mainport.allowedHosts
		config.setExpert("mainport.allowedHosts", true);
		config.argDesc(
			"mainport.allowedHosts",
			"Comma delimited list of IP addresses, netmasks or hostnames");
		config.shortDesc(
			"mainport.allowedHosts",
			"hosts allowed to access main freenet web interface");
		config.longDesc(
			"mainport.allowedHosts",
			"List of IP addresses (for example \"192.168.1.7\"), DNS names (\"erica\" or \"www.nsa.gov\") or netmasks (\"192.168.1.0/24\") of hosts (computers) that should be allowed to access the main web interface of your freenet node. Defaults to localhost (127.0.0.0/8) only.");

		// mainport.bindAddress
		config.setExpert("mainport.bindAddress", true);
		config.argDesc("mainport.bindAddress", "IP address or \"*\"");
		config.shortDesc(
			"mainport.bindAddress",
			"IP address for main freenet web interface to listen on or \"*\"");
		config.longDesc(
			"mainport.bindAddress",
			"IP address of one interface for the main freenet web interface to listen on, or \"*\" to listen on all interfaces. Will be automatically determined from mainport.allowedHosts if not given; leave it alone.");

		// mainport.params.servlet.1.uri
		config.setExpert("mainport.params.servlet.1.uri", true);
		config.argDesc("mainport.params.servlet.1.uri", "path");
		config.shortDesc(
			"mainport.params.servlet.1.uri",
			"Path within mainport for fproxy. Leave this alone");

		// mainport.params.servlet.1.method
		config.setExpert("mainport.params.servlet.1.method", true);
		config.argDesc("mainport.params.servlet.1.method", "HTTP method");
		config.shortDesc(
			"mainport.params.servlet.1.method",
			"HTTP method for fproxy. Leave this alone.");

		// mainport.params.servlet.1.class
		config.setExpert("mainport.params.servlet.1.class", true);
		config.argDesc("mainport.params.servlet.1.class", "servlet class");
		config.shortDesc(
			"mainport.params.servlet.1.class",
			"servlet class to run fproxy. Leave this alone.");

		// mainport.params.servlet.1.name
		config.setExpert("mainport.params.servlet.1.name", true);
		config.argDesc("mainport.params.servlet.1.name", "string");
		config.shortDesc(
			"mainport.params.servlet.1.name",
			"name of first servlet on mainport (normally fproxy - \"Freenet HTTP proxy (fproxy)\"). Leave this alone.)");

		// mainport.params.servlet.1.params.requestHtl
		config.setExpert("mainport.params.servlet.1.params.requestHtl", true);
		config.argDesc(
			"mainport.params.servlet.1.params.requestHtl",
			"integer HTL value between 0 and maxHopsToLive");
		config.shortDesc(
			"mainport.params.servlet.1.params.requestHtl",
			"hops to live of fproxy requests");
		config.longDesc(
			"mainport.params.servlet.1.params.requestHtl",
			"hops to live (HTL) of requests made by fproxy");

		// mainport.params.servlet.1.params.passThroughMimeTypes
		config.setExpert(
			"mainport.params.servlet.1.params.passThroughMimeTypes",
			true);
		config.argDesc(
			"mainport.params.servlet.1.params.passThroughMimeTypes",
			"comma delimited list of MIME types");
		config.shortDesc(
			"mainport.params.servlet.1.params.passThroughMimeTypes",
			"safe MIME types, defaults to filterPassThroughMimeTypes");
		config.longDesc(
			"mainport.params.servlet.1.params.passThroughMimeTypes",
			"MIME types regarded as safe that are passed to the browser without filtering or warning in fproxy. The default is empty (\"\"), which means to use the node global default filterPassThroughMimeTypes");

		// mainport.params.servlet.1.params.filter
		config.setExpert("mainport.params.servlet.1.params.filter", true);
		config.argDesc("mainport.params.servlet.1.params.filter", "true|false");
		config.shortDesc(
			"mainport.params.servlet.1.params.filter",
			"run the anonymity filter on HTML/CSS");
		config.longDesc(
			"mainport.params.servlet.1.params.filter",
			"Whether to run the anonymity filter to remove HTML and CSS tags that might cause your browser to damage your anonymity");

		// mainport.params.servlet.1.params.filterParanoidStringCheck
		config.setExpert(
			"mainport.params.servlet.1.params.filterParanoidStringCheck",
			true);
		config.argDesc(
			"mainport.params.servlet.1.params.filterParanoidStringCheck",
			"true|false");
		config.shortDesc(
			"mainport.params.servlet.1.params.filterParanoidStringCheck",
			"whether to make the anonymity filter really paranoid");
		config.longDesc(
			"mainport.params.servlet.1.params.filterParanoidStringCheck",
			"whether to make the anonymity filter really paranoid; currently this causes strings in CSS to be removed if they contain colons (\":\")");

		// mainport.params.servlet.1.params.maxForceKeys
		config.setExpert("mainport.params.servlet.1.params.maxForceKeys", true);
		config.argDesc(
			"mainport.params.servlet.1.params.maxForceKeys",
			"integer");
		config.shortDesc(
			"mainport.params.servlet.1.params.maxForceKeys",
			"Number of ?force= keys for fproxy to track");
		config.longDesc(
			"mainport.params.servlet.1.params.maxForceKeys",
			"Number of key overrides Fproxy should track... these are the confirmation pages you get when you go to some file that fproxy doesn't know how to handle");

		// mainport.params.servlet.1.params.doSendRobots
		config.setExpert("mainport.params.servlet.1.params.doSendRobots", true);
		config.argDesc(
			"mainport.params.servlet.1.params.doSendRobots",
			"yes|no");
		config.shortDesc(
			"mainport.params.servlet.1.params.doSendRobots",
			"Whether to send /robots.txt to the browser");

		// mainport.params.servlet.1.params.noCache
		config.setExpert("mainport.params.servlet.1.params.noCache", true);
		config.argDesc("mainport.params.servlet.1.params.noCache", "yes|no");
		config.shortDesc(
			"mainport.params.servlet.1.params.noCache",
			"Whether to tell the browser not to cache Freenet content");

		// mainport.params.servlet.1.params.dontWarnOperaUsers
		config.setExpert("mainport.params.servlet.1.params.dontWarnOperaUsers", true);
		config.argDesc  ("mainport.params.servlet.1.params.dontWarnOperaUsers", "yes|no");
		config.shortDesc("mainport.params.servlet.1.params.dontWarnOperaUsers", 
				"Set true to not warn Opera users about MIME types. READ LONG DESCRIPTION.");
		config.longDesc ("mainport.params.servlet.1.params.dontWarnOperaUsers",
				"Opera and IE suffer from a design flaw that prevent FProxy from protecting ",
				"your anonymity properly. In Opera, it can be turned off. If you are sure you ",
				"have done so (read the warning page for instructions), enable this option to ",
				"eliminate the warning page.");
		
		// mainport.params.servlet.2.uri
		config.setExpert("mainport.params.servlet.2.uri", true);
		config.argDesc("mainport.params.servlet.2.uri", "path");
		config.shortDesc(
			"mainport.params.servlet.2.uri",
			"Path within mainport for the Node Info Servlet");
		config.longDesc(
			"mainport.params.servlet.2.uri",
			"Path within mainport for the Node Info Servlet - this contains infolets which present pages of information about the node as well as the default front page");

		// mainport.params.servlet.2.method
		config.setExpert("mainport.params.servlet.2.method", true);
		config.argDesc("mainport.params.servlet.2.method", "HTTP method");
		config.shortDesc(
			"mainport.params.servlet.2.method",
			"HTTP method for Node Info Servlet. Leave this alone.");

		// mainport.params.servlet.2.class
		config.setExpert("mainport.params.servlet.2.class", true);
		config.argDesc("mainport.params.servlet.2.class", "servlet class");
		config.shortDesc(
			"mainport.params.servlet.2.class",
			"servlet class to run Node Info Servlet. Leave this alone");

		// mainport.params.servlet.2.name
		config.setExpert("mainport.params.servlet.2.name", true);
		config.argDesc("mainport.params.servlet.2.name", "string");
		config.shortDesc(
			"mainport.params.servlet.2.name",
			"name of (usually) Node Info Servlet. Leave this alone.");

		// default bookmarks
		config.setExpert("mainport.params.servlet.2.bookmarks.count", true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.count",
			"Number of bookmarks on fproxy");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.count",
			"Number of bookmarks on fproxy, or -1 to include all specified ones");

		config.setExpert("mainport.params.servlet.2.bookmarks.0.key", true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.0.key",
			"freenet key");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.0.key",
			"The first bookmark for the web proxy");
		config.setExpert("mainport.params.servlet.2.bookmarks.0.title", true);
		config.argDesc("mainport.params.servlet.2.bookmarks.0.title", "string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.0.title",
			"The title for the first bookmark for the web proxy");
		config.setExpert(
			"mainport.params.servlet.2.bookmarks.0.activelinkFile",
			true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.0.activelinkFile",
			"string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.0.activelinkFile",
			"The name of the activelink image within the key for the first bookmark");
		config.setExpert(
			"mainport.params.servlet.2.bookmarks.0.description",
			true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.0.description",
			"string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.0.description",
			"The description of the first bookmark on the web proxy");

		config.setExpert("mainport.params.servlet.2.bookmarks.1.key", true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.1.key",
			"freenet key");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.1.key",
			"The second bookmark for the web proxy");
		config.setExpert("mainport.params.servlet.2.bookmarks.1.title", true);
		config.argDesc("mainport.params.servlet.2.bookmarks.1.title", "string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.1.title",
			"The title for the second bookmark for the web proxy");
		config.setExpert(
			"mainport.params.servlet.2.bookmarks.1.activelinkFile",
			true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.1.activelinkFile",
			"string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.1.activelinkFile",
			"The name of the activelink image within the key for the second bookmark");
		config.setExpert(
			"mainport.params.servlet.2.bookmarks.1.description",
			true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.1.description",
			"string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.1.description",
			"The description of the second bookmark on the web proxy");

		config.setExpert("mainport.params.servlet.2.bookmarks.2.key", true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.2.key",
			"freenet key");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.2.key",
			"The third bookmark for the web proxy");
		config.setExpert("mainport.params.servlet.2.bookmarks.2.title", true);
		config.argDesc("mainport.params.servlet.2.bookmarks.2.title", "string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.2.title",
			"The title for the third bookmark for the web proxy");
		config.setExpert(
			"mainport.params.servlet.2.bookmarks.2.activelinkFile",
			true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.2.activelinkFile",
			"string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.2.activelinkFile",
			"The name of the activelink image within the key for the third bookmark");
		config.setExpert(
			"mainport.params.servlet.2.bookmarks.2.description",
			true);
		config.argDesc(
			"mainport.params.servlet.2.bookmarks.2.description",
			"string");
		config.shortDesc(
			"mainport.params.servlet.2.bookmarks.2.description",
			"The description of the third bookmark on the web proxy");

//        config.setExpert("mainport.params.servlet.2.bookmarks.3.key", true);
//        config.argDesc(
//            "mainport.params.servlet.2.bookmarks.3.key",
//            "freenet key");
//        config.shortDesc(
//            "mainport.params.servlet.2.bookmarks.3.key",
//            "The fourth bookmark for the web proxy");
//        config.setExpert("mainport.params.servlet.2.bookmarks.3.title", true);
//        config.argDesc("mainport.params.servlet.2.bookmarks.3.title", "string");
//        config.shortDesc(
//            "mainport.params.servlet.2.bookmarks.3.title",
//            "The title for the fourth bookmark for the web proxy");
//        config.setExpert(
//            "mainport.params.servlet.2.bookmarks.3.activelinkFile",
//            true);
//        config.argDesc(
//            "mainport.params.servlet.2.bookmarks.3.activelinkFile",
//            "string");
//        config.shortDesc(
//            "mainport.params.servlet.2.bookmarks.3.activelinkFile",
//            "The name of the activelink image within the key for the fourth bookmark");
//        config.setExpert(
//            "mainport.params.servlet.2.bookmarks.3.description",
//            true);
//        config.argDesc(
//            "mainport.params.servlet.2.bookmarks.3.description",
//            "string");
//        config.shortDesc(
//            "mainport.params.servlet.2.bookmarks.3.description",
//            "The description of the fourth bookmark on the web proxy");
//
//        config.setExpert("mainport.params.servlet.2.bookmarks.4.key", true);
//        config.argDesc(
//            "mainport.params.servlet.2.bookmarks.4.key",
//            "freenet key");
//        config.shortDesc(
//            "mainport.params.servlet.2.bookmarks.4.key",
//            "The fifth bookmark for the web proxy");
//        config.setExpert("mainport.params.servlet.2.bookmarks.4.title", true);
//        config.argDesc("mainport.params.servlet.2.bookmarks.4.title", "string");
//        config.shortDesc(
//            "mainport.params.servlet.2.bookmarks.4.title",
//            "The title for the fifth bookmark for the web proxy");
//        config.setExpert(
//            "mainport.params.servlet.2.bookmarks.4.activelinkFile",
//            true);
//        config.argDesc(
//            "mainport.params.servlet.2.bookmarks.4.activelinkFile",
//            "string");
//        config.shortDesc(
//            "mainport.params.servlet.2.bookmarks.4.activelinkFile",
//            "The name of the activelink image within the key for the fifth bookmark");
//        config.setExpert(
//            "mainport.params.servlet.2.bookmarks.4.description",
//            true);
//        config.argDesc(
//            "mainport.params.servlet.2.bookmarks.4.description",
//            "string");
//        config.shortDesc(
//            "mainport.params.servlet.2.bookmarks.4.description",
//            "The description of the fifth bookmark on the web proxy");
//
		// mainport.params.servlet.3.uri
		config.setExpert("mainport.params.servlet.3.uri", true);
		config.argDesc("mainport.params.servlet.3.uri", "path");
		config.shortDesc(
			"mainport.params.servlet.3.uri",
			"Path within mainport for the Images Servlet");
		config.longDesc(
			"mainport.params.servlet.3.uri",
			"Path within mainport for the Images - this serves static images needed by fproxy and the Node Info Servlet");

		// mainport.params.servlet.3.method
		config.setExpert("mainport.params.servlet.3.method", true);
		config.argDesc("mainport.params.servlet.3.method", "HTTP method");
		config.shortDesc(
			"mainport.params.servlet.3.method",
			"HTTP method for Images Servlet. Leave this alone.");

		// mainport.params.servlet.3.class
		config.setExpert("mainport.params.servlet.3.class", true);
		config.argDesc("mainport.params.servlet.3.class", "servlet class");
		config.shortDesc(
			"mainport.params.servlet.3.class",
			"servlet class to run Images Servlet. Leave this alone");

		// mainport.params.servlet.3.name
		config.setExpert("mainport.params.servlet.3.name", true);
		config.argDesc("mainport.params.servlet.3.name", "string");
		config.shortDesc(
			"mainport.params.servlet.3.name",
			"name of (usually) Images Servlet. Leave this alone.");

		// mainport.params.servlet.4.uri
		config.setExpert("mainport.params.servlet.4.uri", true);
		config.argDesc("mainport.params.servlet.4.uri", "path");
		config.shortDesc(
			"mainport.params.servlet.4.uri",
			"Path within mainport for the Insert Servlet");
		config.longDesc(
			"mainport.params.servlet.4.uri",
			"Path within mainport for the Insert Servlet - used to insert files into freenet from the web interface");

		// mainport.params.servlet.4.method
		config.setExpert("mainport.params.servlet.4.method", true);
		config.argDesc("mainport.params.servlet.4.method", "HTTP method");
		config.shortDesc(
			"mainport.params.servlet.4.method",
			"HTTP method for Insert "
				+ "Servlet (needs BOTH GET for status and PUT for uploads). "
				+ "Leave this alone.");

		// mainport.params.servlet.4.class
		config.setExpert("mainport.params.servlet.4.class", true);
		config.argDesc("mainport.params.servlet.4.class", "servlet class");
		config.shortDesc(
			"mainport.params.servlet.4.class",
			"servlet class to run Insert Servlet. Leave this alone");

		// mainport.params.servlet.4.name
		config.setExpert("mainport.params.servlet.4.name", true);
		config.argDesc("mainport.params.servlet.4.name", "string");
		config.shortDesc(
			"mainport.params.servlet.4.name",
			"name of (usually) Insert Servlet. Leave this alone.");

		// mainport.params.servlet.4.params.insertHtl
		config.setExpert("mainport.params.servlet.4.params.insertHtl", true);
		config.argDesc(
			"mainport.params.servlet.4.params.insertHtl",
			"integer between 0 and maxHopsToLive");
		config.shortDesc(
			"mainport.params.servlet.4.params.insertHtl",
			"hops to live (HTL) of inserts");
		config.longDesc(
			"mainport.params.servlet.4.params.insertHtl",
			"Hops-to-Live value (HTL) of inserts through the web interface");

		// mainport.params.servlet.4.params.sfInsertThreads
		config.setExpert(
			"mainport.params.servlet.4.params.sfInsertThreads",
			true);
		config.argDesc(
			"mainport.params.servlet.4.params.sfInsertThreads",
			"integer");
		config.shortDesc(
			"mainport.params.servlet.4.params.sfInsertThreads",
			"threads to use to insert a splitfile");
		config.longDesc(
			"mainport.params.servlet.4.params.sfInsertThreads",
			"Number of threads to allocate to insert a splitfile through the web interface");

		// mainport.params.servlet.4.params.sfInsertRetries
		config.setExpert(
			"mainport.params.servlet.4.params.sfInsertRetries",
			true);
		config.argDesc(
			"mainport.params.servlet.4.params.sfInsertRetries",
			"integer");
		config.shortDesc(
			"mainport.params.servlet.4.params.sfInsertRetries",
			"Number of retries if a block insert fails");

		// mainport.params.servlet.4.params.sfRefreshIntervalSecs
		config.setExpert(
			"mainport.params.servlet.4.params.sfRefreshIntervalSecs",
			true);
		config.argDesc(
			"mainport.params.servlet.4.params.sfRefreshIntervalSecs",
			"<seconds>");
		config.shortDesc(
			"mainport.params.servlet.4.params.sfRefreshIntervalSecs",
			"How frequently to update insert status");

		// mainport.params.servlet.6.uri
		config.setExpert("mainport.params.servlet.6.uri", true);
		config.argDesc("mainport.params.servlet.6.uri", "path");
		config.shortDesc(
			"mainport.params.servlet.6.uri",
			"Path within mainport for the Insert Servlet");
		config.longDesc(
			"mainport.params.servlet.6.uri",
			"Path within mainport for the Insert Servlet - used to insert files into freenet from the web interface");

		// mainport.params.servlet.6.method
		config.setExpert("mainport.params.servlet.6.method", true);
		config.argDesc("mainport.params.servlet.6.method", "HTTP method");
		config.shortDesc(
			"mainport.params.servlet.6.method",
			"HTTP method for Insert Servlet (should be PUT). Leave this alone.");

		// mainport.params.servlet.6.class
		config.setExpert("mainport.params.servlet.6.class", true);
		config.argDesc("mainport.params.servlet.6.class", "servlet class");
		config.shortDesc(
			"mainport.params.servlet.6.class",
			"servlet class to run Insert Servlet. Leave this alone");

		// mainport.params.servlet.6.name
		config.setExpert("mainport.params.servlet.6.name", true);
		config.argDesc("mainport.params.servlet.6.name", "string");
		config.shortDesc(
			"mainport.params.servlet.6.name",
			"name of (usually) Insert Servlet. Leave this alone.");

		// mainport.params.servlet.6.params.insertHtl
		config.setExpert("mainport.params.servlet.6.params.insertHtl", true);
		config.argDesc(
			"mainport.params.servlet.6.params.insertHtl",
			"integer between 0 and maxHopsToLive");
		config.shortDesc(
			"mainport.params.servlet.6.params.insertHtl",
			"hops to live (HTL) of inserts");
		config.longDesc(
			"mainport.params.servlet.6.params.insertHtl",
			"Hops-to-Live value (HTL) of inserts through the web interface");

		// mainport.params.servlet.6.params.sfInsertThreads
		config.setExpert(
			"mainport.params.servlet.6.params.sfInsertThreads",
			true);
		config.argDesc(
			"mainport.params.servlet.6.params.sfInsertThreads",
			"integer");
		config.shortDesc(
			"mainport.params.servlet.6.params.sfInsertThreads",
			"threads to use to insert a splitfile");
		config.longDesc(
			"mainport.params.servlet.6.params.sfInsertThreads",
			"Number of threads to allocate to insert a splitfile through the web interface");

		// mainport.params.servlet.6.params.sfInsertRetries
		config.setExpert(
			"mainport.params.servlet.6.params.sfInsertRetries",
			true);
		config.argDesc(
			"mainport.params.servlet.6.params.sfInsertRetries",
			"integer");
		config.shortDesc(
			"mainport.params.servlet.6.params.sfInsertRetries",
			"Number of retries if a block insert fails");

		// mainport.params.servlet.6.params.sfRefreshIntervalSecs
		config.setExpert(
			"mainport.params.servlet.6.params.sfRefreshIntervalSecs",
			true);
		config.argDesc(
			"mainport.params.servlet.6.params.sfRefreshIntervalSecs",
			"<seconds>");
		config.shortDesc(
			"mainport.params.servlet.6.params.sfRefreshIntervalSecs",
			"How frequently to update insert status");

		// mainport.params.servlet.5.uri
		config.setExpert("mainport.params.servlet.5.uri", true);
		config.argDesc("mainport.params.servlet.5.uri", "path");
		config.shortDesc(
			"mainport.params.servlet.5.uri",
			"Path within mainport for the Node Status Servlet");
		config.longDesc(
			"mainport.params.servlet.5.uri",
			"Path within mainport for the Node Status Servlet - displays detailed information about node status. Disabled if publicNode is on.");

		// mainport.params.servlet.5.method
		config.setExpert("mainport.params.servlet.5.method", true);
		config.argDesc("mainport.params.servlet.5.method", "HTTP method");
		config.shortDesc(
			"mainport.params.servlet.5.method",
			"HTTP method for Node Status Servlet. Leave this alone.");

		// mainport.params.servlet.5.class
		config.setExpert("mainport.params.servlet.5.class", true);
		config.argDesc("mainport.params.servlet.5.class", "servlet class");
		config.shortDesc(
			"mainport.params.servlet.5.class",
			"servlet class to run Node Status Servlet. Leave this alone");

		// mainport.params.servlet.5.name
		config.setExpert("mainport.params.servlet.5.name", true);
		config.argDesc("mainport.params.servlet.5.name", "string");
		config.shortDesc(
			"mainport.params.servlet.5.name",
			"name of (usually) Node Status Servlet. Leave this alone.");

		// mainport.params.servlet.7.uri
		config.setExpert("mainport.params.servlet.7.uri", true);
		config.argDesc("mainport.params.servlet.7.uri", "path");
		config.shortDesc(
			"mainport.params.servlet.7.uri",
			"Path within mainport for the SplitFile Download Servlet");
		config.longDesc(
			"mainport.params.servlet.7.uri",
			"Path within mainport for the SplitFile Download Servlet - used to download large files through the web interface");

		// mainport.params.servlet.7.method
		config.setExpert("mainport.params.servlet.7.method", true);
		config.argDesc("mainport.params.servlet.7.method", "HTTP method");
		config.shortDesc(
			"mainport.params.servlet.7.method",
			"HTTP method for SplitFile Download Servlet. Leave this alone.");

		// mainport.params.servlet.7.class
		config.setExpert("mainport.params.servlet.7.class", true);
		config.argDesc("mainport.params.servlet.7.class", "servlet class");
		config.shortDesc(
			"mainport.params.servlet.7.class",
			"servlet class to run SplitFile Download Servlet. Leave this alone");

		// mainport.params.servlet.7.name
		config.setExpert("mainport.params.servlet.7.name", true);
		config.argDesc("mainport.params.servlet.7.name", "string");
		config.shortDesc(
			"mainport.params.servlet.7.name",
			"name of (usually) SplitFile Download Servlet. Leave this alone.");

		// mainport.params.servlet.7.params.requestHtl
		config.setExpert("mainport.params.servlet.7.params.requestHtl", true);
		config.argDesc(
			"mainport.params.servlet.7.params.requestHtl",
			"integer value between 0 and maxHopsToLive");
		config.shortDesc(
			"mainport.params.servlet.7.params.requestHtl",
			"request HTL getting metadata in splitfile servlet");
		config.longDesc(
			"mainport.params.servlet.7.params.requestHtl",
			"Hops-To-Live of requests for the metadata of the splitfiles downloading splitfiles from the web interface.");

		// mainport.params.servlet.7.params.sfBlockRequestHtl
		config.setExpert(
			"mainport.params.servlet.7.params.sfBlockRequestHtl",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfBlockRequestHtl",
			"integer value between 0 and maxHopsToLive");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfBlockRequestHtl",
			"initial request HTL for blocks in splitfile downloads");
		config.longDesc(
			"mainport.params.servlet.7.params.sfBlockRequestHtl",
			"initial Hops-To-Live (HTL) of requests for blocks downloading splitfiles");

		// mainport.params.servlet.7.params.sfRequestRetries
		config.setExpert(
			"mainport.params.servlet.7.params.sfRequestRetries",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfRequestRetries",
			"positive integer");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfRequestRetries",
			"Number of retries on each block in a splitfile download");

		// mainport.params.servlet.7.params.maxRetries
		config.setExpert(
			"mainport.params.servlet.7.params.maxRetries",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.maxRetries",
			"positive integer");
		config.shortDesc(
			"mainport.params.servlet.7.params.maxRetries",
			"Maximum number of retries per block for a splitfile");
		
		// mainport.params.servlet.7.params.sfRetryHtlIncrement
		config.setExpert(
			"mainport.params.servlet.7.params.sfRetryHtlIncrement",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfRetryHtlIncrement",
			"positive integer");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfRetryHtlIncrement",
			"Amount to increase the HTL by on each retry");

		// mainport.params.servlet.7.params.sfRequestThreads
		config.setExpert(
			"mainport.params.servlet.7.params.sfRequestThreads",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfRequestThreads",
			"positive integer");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfRequestThreads",
			"Number of threads to use to download a splitfile via the web interface");

		// mainport.params.servlet.7.params.sfDoParanoidChecks
		config.setExpert(
			"mainport.params.servlet.7.params.sfDoParanoidChecks",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfDoParanoidChecks",
			"true|false");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfDoParanoidChecks",
			"Whether to run paranoid checks on blocks downloaded as part of a splitfile");

		// mainport.params.servlet.7.params.sfRefreshIntervalSecs
		config.setExpert(
			"mainport.params.servlet.7.params.sfRefreshIntervalSecs",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfRefreshIntervalSecs",
			"<seconds>");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfRefreshIntervalSecs",
			"How frequently to update the splitfile user interface while downloading");

		// mainport.params.servlet.7.params.sfForceSave
		config.setExpert("mainport.params.servlet.7.params.sfForceSave", true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfForceSave",
			"true|false");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfForceSave",
			"Whether to (by default) force the browser to save splitfiles to disk");

		// mainport.params.servlet.7.params.sfSkipDS
		config.setExpert("mainport.params.servlet.7.params.sfSkipDS", true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfSkipDS",
			"true|false");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfSkipDS",
			"Skip local datastore when downloading splitfiles?");
		config.longDesc(
			"mainport.params.servlet.7.params.sfSkipDS",
			"Whether to "
				+ "skip the local datastore when downloading splitfiles. "
				+ "If you don't know what this means you don't need it.");

		// mainport.params.servlet.7.params.sfUseUI
		config.setExpert("mainport.params.servlet.7.params.sfUseUI", true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfUseUI",
			"true|false");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfUseUI",
			"Use downloader user interface when downloading files?");
		config.longDesc(
			"mainport.params.servlet.7.params.sfUseUI",
			"Whether to use the downloader user interface when downloading files. If set to no, files will be downloaded directly without showing any progress monitor, but this may take a very long time and most splitfiles do not send any data until the whole file has been downloaded");

		// mainport.params.servlet.7.params.sfRunFilter
		config.setExpert("mainport.params.servlet.7.params.sfRunFilter", true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfRunFilter",
			"true|false");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfRunFilter",
			"Run the anonymity filter on HTML splitfiles?");

		// mainport.params.servlet.7.params.sfRandomSegs
		config.setExpert("mainport.params.servlet.7.params.sfRandomSegs", true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfRandomSegs",
			"true|false");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfRandomSegs",
			"Randomize the order of segment downloads for splitfiles?");
		config.longDesc(
			"mainport.params.servlet.7.params.sfRandomSegs",
			"Whether to randomize the order of segment downloads for splitfiles. Normally this is a good thing.");

		// mainport.params.servlet.7.params.sfFilterParanoidStringCheck
		config.setExpert(
			"mainport.params.servlet.7.params.sfFilterParanoidStringCheck",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfFilterParanoidStringCheck",
			"true|false");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfFilterParanoidStringCheck",
			"Make the anonymity filter on HTML splitfiles really paranoid?");
		config.longDesc(
			"mainport.params.servlet.7.params.sfFilterParanoidStringCheck",
			"Make the anonymity filter on HTML splitfiles really paranoid? Currently this causes strings in CSS to be removed if they contain colons (\":\")");

		// mainport.params.servlet.7.params.sfHealHtl
		config.setExpert("mainport.params.servlet.7.params.sfHealHtl", true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfHealHtl",
			"integer between 0 and maxHopsToLive");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfHealHtl",
			"default HTL of splitfile healing insertions");
		config.longDesc(
			"mainport.params.servlet.7.params.sfHealHtl",
			"Default HTL of inserts caused by splitfile healing code");

		// mainport.params.servlet.7.params.sfHealPercentage
		config.setExpert(
			"mainport.params.servlet.7.params.sfHealPercentage",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfHealPercentage",
			"0...100");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfHealPercentage",
			"Percentage of missing blocks to reinsert after fetching a redundant splitfile. Reinsertion is done in the background, so the default of 100 is quite reasonable.");

		// mainport.params.servlet.7.params.sfForceSave
		config.setExpert("mainport.params.servlet.7.params.sfForceSave", true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfForceSave",
			"yes|no");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfForceSave",
			"force save splitfile downloads by default?");
		config.longDesc(
			"mainport.params.servlet.7.params.sfForceSave",
			"If true, large \"splitfiles\" will always be saved as application/octet-stream, to force the browser to save the file to disk rather than trying to open it in place.");

		// mainport.params.servlet.7.params.sfDefaultSaveDir
		config.setExpert(
			"mainport.params.servlet.7.params.sfDefaultSaveDir",
			false);
		config.argDesc(
			"mainport.params.servlet.7.params.sfDefaultSaveDir",
			"path to folder");
		config.shortDesc(
			"mainport.params.servlet.7.params.sfDefaultSaveDir",
			"Default folder to save large downloaded files to.");
		config.longDesc(
			"mainport.params.servlet.7.params.sfDefaultSaveDir",
			"Default folder to save large downloaded files to.  Defaults to a folder",
			"called \"freenet-downloads\" in your home directory.");

		// mainport.params.servlet.7.params.sfDefaultWriteToDisk
		config.setExpert(
			"mainport.params.servlet.7.params.sfDefaultWriteToDisk",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfDefaultWriteToDisk",
			"yes|no");
		config.longDesc(
			"mainport.params.servlet.7.params.sfDefaultWriteToDisk",
			"Whether to write splitfiles to disk by default, rather than sending them to the browser");

		// mainport.params.servlet.7.params.sfDisableWriteToDisk
		config.setExpert(
			"mainport.params.servlet.7.params.sfDisableWriteToDisk",
			true);
		config.argDesc(
			"mainport.params.servlet.7.params.sfDisableWriteToDisk",
			"yes|no");
		config.longDesc(
			"mainport.params.servlet.7.params.sfDisableWriteToDisk",
			"Set true to disable the option to write splitfile downloads direct to disk rather than streaming them to the browser. Automatically enabled if publicNode is set.");

		// mainport.params.defaultServlet.uri
		config.setExpert("mainport.params.defaultServlet.uri", true);
		config.argDesc("mainport.params.defaultServlet.uri", "path");
		config.shortDesc(
			"mainport.params.defaultServlet.uri",
			"Path within mainport for web interface redirect");
		config.longDesc(
			"mainport.params.defaultServlet.uri",
			"Path within mainport for web interface redirect");

		// mainport.params.defaultServlet.method
		config.setExpert("mainport.params.defaultServlet.method", true);
		config.argDesc("mainport.params.defaultServlet.method", "HTTP method");
		config.shortDesc(
			"mainport.params.defaultServlet.method",
			"HTTP method for web interface redirect. Leave this alone.");

		// mainport.params.defaultServlet.class
		config.setExpert("mainport.params.defaultServlet.class", true);
		config.argDesc("mainport.params.defaultServlet.class", "servlet class");
		config.shortDesc(
			"mainport.params.defaultServlet.class",
			"servlet class to run web interface redirect. Leave this alone");

		// mainport.params.defaultServlet.name
		config.setExpert("mainport.params.defaultServlet.name", true);
		config.argDesc("mainport.params.defaultServlet.name", "string");
		config.shortDesc(
			"mainport.params.defaultServlet.name",
			"name of (usually) web interface redirect. Leave this alone.");

		// mainport.params.defaultServlet.params.targetURL
		config.setExpert(
			"mainport.params.defaultServlet.params.targetURL",
			true);
		config.argDesc(
			"mainport.params.defaultServlet.params.targetURL",
			"path");
		config.shortDesc(
			"mainport.params.defaultServlet.params.targetURL",
			"path in the servlet to the default page");

		// System.err.println("Node.java static initialization end.");
	}

	//=== Static elements
	// ======================================================

	// node specific options
	static public int routeConnectTimeout;
	static public int maxHopsToLive;
	static public float probIncHopsSinceReset;
	static public float cacheProbPerHop;
	static public float minStoreFullPCache;
	static public float minRTFullPRef;
	static public float minRTNodesPRef;
	static public String routingTableImpl;
	static public RunningAverage myPQueryRejected;
	static public int newNodePollInterval = 30000; // 30 seconds

	// announcement options
	static public int announcementAttempts;
	static public int announcementThreads;

	static public int initialRequests;
	static public int initialRequestHTL;
	static public float overloadLow;
	static public float overloadHigh;
	static public boolean doRequestTriageByDelay;
	static public boolean doRequestTriageBySendTime;
	static public int requestDelayCutoff;
	static public int successfulDelayCutoff;
	static public int requestSendTimeCutoff;
	static public int successfulSendTimeCutoff;
	static public double defaultResetProbability;
	static public int lsMaxTableSize;
	static public int lsAcceptRatioSamples;
	static public double lsHalfLifeHours;
	static public boolean doOutLimitCutoff;
	static public boolean doOutLimitConnectCutoff;
	static public float outLimitCutoff;
	static public float outLimitConnectCutoff;
	static public float threadConnectCutoff;
	static public boolean doCPULoad;
	static public int sendingQueueLength;
	static public int sendingQueueBytes;
	static public int rateLimitingInterval = 0;
	// calculated from above 2
	public static final int muxTrailerBufferLength = 65535;

	static public boolean doLoadBalance;
	static public boolean localIsOK;
	static public boolean dontLimitClients;
	static public String mainportURIOverride;
	static public String distributionURIOverride;
	static public int aggressiveGC;
	static public int configUpdateInterval;
	static public int seednodesUpdateInterval;
	static public boolean badAddress = false;
	static public boolean logInputBytes;
	static public boolean logOutputBytes;
	public static int maxLog2DataSize = 20;

	//static public int maxForwardTries;

	static public boolean firstTime = false;

	// node data files
	static public String nodeFile;
	static public File storeFiles[];
	
	//The lock instance keeping the lock on the above file
	private static FileLock lockFileLock=null;

	// seednodes file
	static public String seedFile;

	// node limits
	static public long storeSize;
	static public int storeBlockSize;
	static public float storeMaxTempFraction;
	static public int rtMaxRefs, rtMaxNodes;
	static public int listenPort;
	static public String storeType;
	static public int maxNodeConnections;
	static public double maxOpenConnectionsNewbieFraction;
	static public int maxNodeFilesOpen;
	static public int bandwidthLimit, inputBandwidthLimit, outputBandwidthLimit;
	static public int averageBandwidthLimit,
		averageInputBandwidthLimit,
		averageOutputBandwidthLimit;
	static public double lowLevelBWLimitMultiplier;
	// the param value * the fudge factor
	static public double lowLevelBWLimitFudgeFactor;
	static public boolean doLowLevelOutputLimiting, doLowLevelInputLimiting;
	static public boolean doReserveOutputBandwidthForSuccess;
	static public int maxNegotiations;
	static public boolean doEstimatorSmoothing;
	static public boolean useFastEstimators;

	// The maximum number of node references that
	// will be returned by successive calls to
	// Routing.getNextRoute().
	static public int maxRoutingSteps;

	static public float minCP;
	static public int minARKDelay;
	static public int failuresLookupARK;
	static public float maxARKThreadsFraction;

	// datastore encryption
	static public String storeCipherName;
	static public int storeCipherWidth;

	static public File routingDir;
	static public boolean useDSIndex;

	// So we can keep track of how long the
	// node has been running. Approximate, but good enough.
	static final public long startupTimeMs = System.currentTimeMillis();

	// remote admin stuff
	static private String adminPassword;
	static private Identity adminPeer;

	// common service ports
	static public int distributionPort;
	static public int fproxyPort;

	// thread management. Parameters read in Main.java
	static public int maxThreads; // maximumThreads parameter.
	static public int targetMaxThreads;
	static public String threadFactoryName;
	static public int tfTolerableQueueDelay;
	static public int tfAbsoluteMaxThreads;
	static protected ThreadFactory threadFactory;

	// client stuff
	static public String filterPassThroughMimeTypes;
	static public boolean httpInserts;
	static public boolean fcpInserts;
	static public String uiTemplateSet = Node.uiTemplateSet + "";

	static public Bandwidth ibw, obw;
	static public boolean defaultToSimpleUIMode;
	static public boolean defaultToOCMHTMLPeerHandlerMode;

	static public RecentKeyList recentKeys;
	private static Object syncCountsByBackoffCount = new Object();
	private static long[] successesByBackoffCount;
	private static long[] failuresByBackoffCount;

	public static boolean isAuthorized(Identity id, String p) {
		if (adminPassword != null
			&& !adminPassword.equals("")
			&& adminPeer != null) {
			return adminPassword.equals(p) && adminPeer.equals(id);
		} else if (adminPassword != null && adminPassword.length()!=0) {
			return adminPassword.equals(p);
		} else if (adminPeer != null) {
			return adminPeer.equals(id);
		} else { // no remote admin
			return false;
		}
	}

	/**
	 * @throws CoreException
	 *             if initialization has already occurred
	 */
	public static void init(Params params) throws CoreException {
		// Init the core settings
		Core.init(params);

		// set keytypes
		try {
			Key.addKeyType(freenet.keys.SVK.keyNumber, freenet.keys.SVK.class);
			Key.addKeyType(freenet.keys.CHK.keyNumber, freenet.keys.CHK.class);
		} catch (KeyException e) {
			String s = "Failed initializing Key classes: " + e;
			logger.log(Node.class, s, e, Logger.ERROR);
			System.err.println(s);
			e.printStackTrace(System.err);
			CoreException ce = new CoreException(s);
			ce.initCause(e);
			throw ce;
		}
		String[] keyTypes = params.getList("keyTypes");
		for (int i = 1; keyTypes != null && i < keyTypes.length; i += 2) {
			try {
				Key.addKeyType(
					Integer.parseInt(keyTypes[i - 1]),
					Class.forName(keyTypes[i]));
			} catch (ClassNotFoundException e) {
				logger.log(
					Node.class,
					"No such class: "
						+ keyTypes[i]
						+ " for Key type "
						+ keyTypes[i
						- 1],
					Logger.ERROR);
			} catch (ClassCastException e) {
				logger.log(
					Node.class,
					"Class " + keyTypes[i] + " is not a Key",
					Logger.ERROR);
			} catch (KeyException e) {
				String s = "Failed initializing Key classes: " + e;
				logger.log(Node.class, s, e, Logger.ERROR);
				System.err.println(s);
				e.printStackTrace(System.err);
				CoreException ce = new CoreException(s);
				ce.initCause(e);
				throw ce;
			}
		}
		myPQueryRejected = new SimpleBinaryRunningAverage(1000, 1.0);
		// set network parameters
		newNodePollInterval = params.getInt("newNodePollInterval");
		recentKeys = new RecentKeyList(512, Core.getRandSource());
		routeConnectTimeout = params.getInt("routeConnectTimeout");
		maxHopsToLive = params.getInt("maxHopsToLive");
		maxLog2DataSize = params.getInt("maxLog2DataSize");
		probIncHopsSinceReset = params.getFloat("probIncHopsSinceReset");
		cacheProbPerHop = params.getFloat("cacheProbPerHop");
		minStoreFullPCache = params.getFloat("minStoreFullPCache");
		minRTFullPRef = params.getFloat("minRTFullPRef");
		minRTNodesPRef = params.getFloat("minRTNodesPRef");
		//routingTableImpl = params.getString("routingTableImpl");
		maxNegotiations = params.getInt("maxNegotiations");
		routingTableImpl = "ng";
		announcementAttempts = params.getInt("announcementAttempts");
		announcementThreads = params.getInt("announcementThreads");
		initialRequests = params.getInt("initialRequests");
		initialRequestHTL = params.getInt("initialRequestHTL");
		//maxForwardTries = Defaults.getInt("maxForwardTries", params);
		int maxRequestsPerInterval = params.getInt("maxRequestsPerInterval");
		if (maxRequestsPerInterval > 0)
			outboundRequestLimit =
				new LimitCounter(
					params.getInt("maxRequestsInterval"),
					maxRequestsPerInterval);

		lowLevelBWLimitFudgeFactor =
			params.getFloat("lowLevelBWLimitFudgeFactor");
		lowLevelBWLimitMultiplier =
			params.getFloat("lowLevelBWLimitMultiplier")
				* lowLevelBWLimitFudgeFactor;
//        doLowLevelOutputLimiting = false;
//        doLowLevelInputLimiting = false;
		doLowLevelOutputLimiting =
			params.getBoolean("doLowLevelOutputLimiting");
		doLowLevelInputLimiting = params.getBoolean("doLowLevelInputLimiting");
		doReserveOutputBandwidthForSuccess =
			params.getBoolean("doReserveOutputBandwidthForSuccess");
		bandwidthLimit = params.getInt("bandwidthLimit");
		inputBandwidthLimit = params.getInt("inputBandwidthLimit");
		outputBandwidthLimit = params.getInt("outputBandwidthLimit");
		averageBandwidthLimit = params.getInt("averageBandwidthLimit");
		averageInputBandwidthLimit =
			params.getInt("averageInputBandwidthLimit");
		averageOutputBandwidthLimit =
			params.getInt("averageOutputBandwidthLimit");

		if (inputBandwidthLimit == 0
			&& outputBandwidthLimit == 0
			&& averageInputBandwidthLimit == 0
			&& averageOutputBandwidthLimit == 0) {
			if (bandwidthLimit != 0 || averageBandwidthLimit != 0) {
				String err =
					"Overall bandwidth limit NO LONGER SUPPORTED! - approximating by setting each direction to half the specified limit";
				logger.log(Node.class, err, Logger.ERROR);
				System.err.println(err);
				System.out.println(err);
				outputBandwidthLimit = bandwidthLimit / 2;
				inputBandwidthLimit = bandwidthLimit / 2;
			}
		}
		if (doLowLevelInputLimiting) {
			ibw =
				inputBandwidthLimit == 0
					? null
					: new Bandwidth(
						(int) (inputBandwidthLimit * lowLevelBWLimitMultiplier),
						(int) (averageInputBandwidthLimit
							* lowLevelBWLimitMultiplier),
						Bandwidth.RECEIVED);
		} else
			ibw = null;
		if (doLowLevelOutputLimiting) {
			obw =
				outputBandwidthLimit == 0
					? null
					: new Bandwidth(
						(int) (outputBandwidthLimit
							* lowLevelBWLimitMultiplier),
						(int) (averageOutputBandwidthLimit
							* lowLevelBWLimitMultiplier),
						Bandwidth.SENT);
		} else
			obw = null;

		if (params.getBoolean("limitAll")) {
			logger.log(Node.class, "Limiting all connections", Logger.DEBUG);
			tcpAddress.throttleAll = true;
			tcpListener.throttleAll = true;
		}
		try {
			tcpConnection.setInputBandwidth(ibw);
			tcpConnection.setOutputBandwidth(obw);
		} catch (NoClassDefFoundError e) {
			if (e
				.getMessage()
				.indexOf("java/nio/channels/spi/AbstractInterruptibleChannel")
				!= -1) {
				String error =
					"Your Java installation is too old (insufficient NIO support). Please update it to 1.4.1 or later; you can do that at http://java.sun.com/ .";
				System.err.println(error);
				logger.log(Node.class, error, Logger.ERROR);
				System.exit(1);
			} else
				throw e;
		}
		//         ThrottledInputStream.setThrottle(ibw);
		//         ThrottledOutputStream.setThrottle(obw);

		// set storage parameters
		storeSize = params.getLong("storeSize");
		if (storeSize > 0 && storeSize < (101L * (1 << 20))) {
			String error =
				"Store size insufficient to store 1MB chunks! Your datastore is so small that it will not be able to store 1MB chunks, the maximum size of a single data key that most tools insert. You will still be able to fetch them but your node will not be very useful to the network, and consequentially will not perform well. To eliminate this error increase your storeSize to at least 101M. It is currently "
					+ storeSize
					+ ".";
			System.err.println(error);
			logger.log(Node.class, error, Logger.ERROR);
		}
		storeBlockSize = params.getInt("storeBlockSize");
		storeMaxTempFraction = params.getFloat("storeMaxTempFraction");
		//maxFileSize = cacheSize / params.getInt("storeCacheCount");

		maxNodeConnections = params.getInt("maxNodeConnections");
		maxNodeFilesOpen = params.getInt("maxNodeFilesOpen");
		maxOpenConnectionsNewbieFraction = 
			params.getDouble("maxOpenConnectionsNewbieFraction");

		rtMaxRefs = params.getInt("rtMaxRefs");
		if(maxNodeConnections > 0)
			rtMaxNodes = maxNodeConnections * 2;
		else
		rtMaxNodes = params.getInt("rtMaxNodes");
		doEstimatorSmoothing = params.getBoolean("doEstimatorSmoothing");
		useFastEstimators = params.getBoolean("useFastEstimators");
		successesByBackoffCount = new long[rtMaxNodes+1];
		failuresByBackoffCount = new long[rtMaxNodes+1];
		maxRoutingSteps = params.getInt("maxRoutingSteps");
		if(maxRoutingSteps < 0) maxRoutingSteps = rtMaxNodes/4;

		minCP = params.getFloat("minCP");
		failuresLookupARK = params.getInt("failuresLookupARK");
		minARKDelay = params.getInt("minARKDelay");
		maxARKThreadsFraction =
			params.getFloat("maxARKThreadsFraction");

		storeCipherName = params.getString("storeCipherName");
		if (storeCipherName.equals("none")
			|| storeCipherName.equals("null")
			|| storeCipherName.equals("void"))
			storeCipherName = null;

		storeCipherWidth = params.getInt("storeCipherWidth");

		routingDir = new File(params.getString("routingDir"));

		useDSIndex = params.getBoolean("useDSIndex");

		// get the listening port
		listenPort = params.getInt("listenPort");

		// seednodes file
		seedFile = params.getString("seedFile");

		String[] storeFile = params.getList("storeFile");
		if (storeFile.length == 0
			|| storeFile[0] == null
			|| storeFile[0].length()==0) {
			storeFile = new String[] { "store" };
			File f = new File("store_" + listenPort);
			if (f.exists()) {
				if (!f.renameTo(new File("store"))) {
					logger.log(
						Node.class,
						"Cannot rename "
							+ f
							+ " to \"store\". This would be useful"
							+ " because then you could change the port"
							+ " without causing the datastore to"
							+ " disappear, causing a major disk space leak"
							+ " and a significant loss of performance.",
						Logger.NORMAL);
					storeFile = new String[] { "store_" + listenPort };
				} else {
					File idx = new File("store", "index");
					if (idx.exists())
						idx.delete();
					idx = new File("store", "index.old");
					if (idx.exists())
						idx.delete();
				}
			}
		}

		storeFiles = new File[storeFile.length];
		for (int i = 0; i < storeFiles.length; ++i) {
			storeFiles[i] = new File(storeFile[i]);
			if (storeFiles[i].exists())
				firstTime = false;
		}

		if ((routingDir == null
				|| routingDir.toString().length() == 0)
				&& storeFiles[0] != null
				&& storeFiles[0].getPath().length() > 0) {
				routingDir =
					storeFiles[0].getAbsoluteFile().getParentFile();
			}

		//The file used to prevent additional instances of a node from starting 
		final File lockFile = new File(routingDir, "lock.lck");
		
		//See if 'we' are already running
		try {
			lockFile.createNewFile();
		} catch (IOException e) {
			//Dont care if we managed to create the file or not (wheter or not it already existed really).
			//What really matters is wheter or not we manage to lock it below 
		}
		if(!lockFile.exists()){
			//Hmmm.. this is bad.. better shut down I assume
			logger.log(Node.class, "Lockfile '"+lockFile.getAbsolutePath()+"' doesn't exist after we tried to create it. Permissions problem? Shutting down.",Logger.ERROR);
			System.exit(2);
		}
		try {
			lockFileLock = new FileOutputStream(lockFile).getChannel().tryLock();
		} catch (Exception e) {
			logger.log(Node.class, "Encountered an unexpected error when locking the lockfile '"+lockFile.getAbsolutePath()+"'. Shutting down.",e,Logger.ERROR);
			System.exit(3);
		}
		if(lockFileLock ==null){
			logger.log(Node.class, "Lockfile '"+lockFile.getAbsolutePath()+"' locked by another process. Maybe another node is running? Shutting down.",Logger.ERROR);
			System.exit(4);
		}
		//Unfortunately the below method doesn't work then the JVM is killed bu the user...
		//However, it looks better if we at least _try_ to clean up after us.
		lockFile.deleteOnExit();         

		// locate the data files
		nodeFile = params.getString("nodeFile");
		if (nodeFile == null || nodeFile.length()==0) {
			File f = new File("node_" + listenPort);
			nodeFile = "node";
			if (f.exists()) {
				if (!f.renameTo(new File(nodeFile))) {
					logger.log(
						Node.class,
						"Cannot rename "
							+ f
							+ " to \"node\". This would be useful "
							+ " because then you could change the port "
							+ " without causing the node identity to"
							+ " disappear, taking with it all references to"
							+ " your node from the rest of the network.",
						Logger.NORMAL);
					nodeFile = "node_" + listenPort;
				}
			}
		}

		// set admin permisions

		String pword = params.getString("adminPassword");
		if (!"".equals(pword))
			adminPassword = pword;

		FieldSet peer = params.getSet("adminPeer");
		if (peer != null) {
			adminPeer = new DSAIdentity(peer);
		}

		overloadLow = params.getFloat("overloadLow");
		overloadHigh = params.getFloat("overloadHigh");
		if(overloadHigh < 1.0) {
			Core.logger.log(Main.class, "overloadHigh set to "+nfp.format(overloadHigh)+
					" - this will NOT WORK with rate limiting", Logger.ERROR);
			overloadHigh = overloadHighDefault;
		}
		doRequestTriageByDelay = params.getBoolean("doRequestTriageByDelay");
		doRequestTriageBySendTime =
			params.getBoolean("doRequestTriageBySendTime");
		requestDelayCutoff = params.getInt("requestDelayCutoff");
		successfulDelayCutoff = params.getInt("successfulDelayCutoff");
		requestSendTimeCutoff = params.getInt("requestSendTimeCutoff");
		successfulSendTimeCutoff = params.getInt("successfulSendTimeCutoff");
		defaultResetProbability = params.getDouble("defaultResetProbability");
		lsMaxTableSize = params.getInt("lsMaxTableSize");
		lsAcceptRatioSamples = params.getInt("lsAcceptRatioSamples");
		lsHalfLifeHours = params.getDouble("lsHalfLifeHours");
		doOutLimitCutoff = params.getBoolean("doOutLimitCutoff");
		outLimitCutoff = params.getFloat("outLimitCutoff");
		doOutLimitConnectCutoff = params.getBoolean("doOutLimitConnectCutoff");
		outLimitConnectCutoff = params.getFloat("outLimitConnectCutoff");
		threadConnectCutoff = params.getFloat("threadConnectCutoff");
		doCPULoad = params.getBoolean("doCPULoad");
		sendingQueueLength = params.getInt("sendingQueueLength");
		sendingQueueBytes = params.getInt("sendingQueueBytes");

		doLoadBalance = params.getBoolean("doLoadBalance");
		localIsOK = params.getBoolean("localIsOK");
		dontLimitClients = params.getBoolean("dontLimitClients");
		mainportURIOverride = params.getString("mainportURIOverride");
		distributionURIOverride = params.getString("distributionURIOverride");
		aggressiveGC = params.getInt("aggressiveGC");
		configUpdateInterval = params.getInt("configUpdateInterval");
		seednodesUpdateInterval = params.getInt("seednodesUpdateInterval");

		distributionPort = params.getInt("distribution.port");
		fproxyPort = params.getInt("mainport.port");
		filterPassThroughMimeTypes =
			params.getString("filterPassThroughMimeTypes");
		httpInserts = params.getBoolean("httpInserts");
		fcpInserts = params.getBoolean("fcpInserts");
		defaultToSimpleUIMode = params.getBoolean("defaultToSimpleUIMode");
		defaultToOCMHTMLPeerHandlerMode =
			params.getBoolean("defaultToOCMHTMLPeerHandlerMode");
		uiTemplateSet = params.getString("UITemplateSet");
		logOutputBytes = params.getBoolean("logOutputBytes");
		logInputBytes = params.getBoolean("logInputBytes");
		freenet.support.servlet.HtmlTemplate.defaultTemplateSet = uiTemplateSet;
		// If we are in watchme mode, initialize watchme and change the
		// protocol
		// version to prevent spy nodes from polluting the real network
		if (params.getBoolean("watchme")) {
			watchme = new WatchMe();
			watchme.init(params.getInt("watchmeRetries"));
			Version.protocolVersion = Version.protocolVersion + "wm";
			freenet.session.FnpLink.AUTH_LAYER_VERSION = 0x05;
		}
		// Around 1000 seconds.
		// This is the time it would take for a request to start and finish
		// at 1kB/sec.
		// As of 29/7/04, this is relatively rare.
		// So we have a less-alchemical way of setting it, than just "5 minutes sounds good" :).
		rateLimitingInterval = 800*1000 + getRandSource().nextInt(400*1000);
	}

	/**
	 * Construct this node's NodeReference from the given private key.
	 */
	static NodeReference makeNodeRef(
		Authentity priv,
		Address[] addr,
		SessionHandler sh,
		PresentationHandler ph,
		long ARKversion,
		byte[] ARKcrypt) {
		long[] sessions, presentations;
		Enumeration e;

		sessions = new long[sh.size()];
		e = sh.getLinkManagers();
		for (int i = 0; i < sessions.length; ++i)
			sessions[i] = ((LinkManager) e.nextElement()).designatorNum();

		presentations = new long[ph.size()];
		e = ph.getPresentations();
		for (int i = 0; i < presentations.length; ++i)
			presentations[i] = ((Presentation) e.nextElement()).designatorNum();

		NodeReference nr =
			new NodeReference(
				priv.getIdentity(),
				addr,
				sessions,
				presentations,
				Version.getVersionString(),
				ARKversion,
				ARKcrypt);

		// FIXME - this method should accept an uncast Authentity
		nr.addSignature((DSAAuthentity) priv);

		return nr;
	}

	/**
	 * The Directory that stores all the node's data.
	 */
	public final Directory dir;

	/**
	 * A source of temp file buckets.
	 */
	public final BucketFactory bf;

	/**
	 * The nodes table of cached documents.
	 */
	public final DataStore ds;

	/**
	 * The routing table for routing requests on a key.
	 */
	public final NodeSortingRoutingTable rt;

	/**
	 * The table of recently failed keys.
	 */
	public final FailureTable ft;

	/**
	 * Queue manager
	 */
	public QueueManager queueManager;
	
	/**
	 * Load statistics module.
	 */
	public final LoadStats loadStats;

	/**
	 * Support for FEC encoding / FEC decoding.
	 */
	public static FECTools fecTools = null;

	// REDFLAG: initialize, better interface, i.e make final?

	/**
	 * Temp dir for servlets, FEC etc
	 */
	public static File tempDir = null;

	// REDFLAG: initialize, better interface, i.e make final?

	/**
	 * A node reference to this node. Basically this is who the node is to the
	 * network.
	 */
	protected NodeReference myRef;
	// FIXME: make protected, use an accessor
	// Only the update-ARK code needs to modify myRef

	public final NodeReference getNodeReference() {
		return myRef;
	}

	public final void setNodeReference(NodeReference newRef) {
		myRef=newRef;
	}

	//A couple of diagnostic variables used in load calculation
	private final RunningAverage routingTimeMinuteAverage;
	private final RunningAverage messageSendTimeRequestMinuteAverage;
	private final IntervalledSum outputBytesLastMinute;

	// Main rate limiting variables
	public ExtrapolatingTimeDecayingEventCounter receivedRequestCounter;
	public ExtrapolatingTimeDecayingEventCounter acceptedExternalRequestCounter;
	RunningAverage globalQuotaAverager;
	
	// Number of outbound requests per minute the node will make.
	static public LimitCounter outboundRequestLimit;
	public ExtrapolatingTimeDecayingEventCounter sentRequestCounter;
	

	/** Watchme (aka test network) functionality */
	public static WatchMe watchme = null;

	/**
	 * Internal client access.
	 */
	public final ClientFactory client;

	/**
	 * Creates a new Node.
	 * 
	 * @param privKey
	 *            The node's private key.
	 * @param dir
	 *            Directory of the node's storage repository.
	 * @param bf
	 *            The source for temp file buckets.
	 * @param ds
	 *            The store where cached data is kept.
	 * @param rt
	 *            The table for routing requests based on key values.
	 * @param ft
	 *            The table that keeps track recently failed keys.
	 * @param myRef
	 *            The NodeReference name to send to other nodes.
	 * @param th
	 *            A transporthandler on which the available transports are
	 *            registered.
	 * @param sh
	 *            The sessionhandler to use for making connections
	 * @param ph
	 *            The presentationHandler to use for making connections
	 * @param loadStats
	 *            The restored LoadStats object
	 */
	public Node(
		Authentity privKey,
		NodeReference myRef,
		Directory dir,
		BucketFactory bf,
		DataStore ds,
		NodeSortingRoutingTable rt,
		FailureTable ft,
		TransportHandler th,
		SessionHandler sh,
		PresentationHandler ph,
		LoadStats loadStats,
		File routingDir) {

		super(privKey, myRef.getIdentity(), th, sh, ph);
		this.myRef = myRef;
		this.dir = dir;
		this.bf = bf;
		this.ds = ds;
		this.ft = ft;
		this.rt = rt;
		this.client = new InternalClient(this);
		
		this.loadStats = loadStats;

		int ONE_MINUTE_MILLISECONDS_COUNT = 60000; //Just to make my intention somewhat clearer
		//Initialize the routingTime RunningAverage
		RunningAverage r1 = new SimpleIntervalledRunningAverage(ONE_MINUTE_MILLISECONDS_COUNT,100,false);
		routingTimeMinuteAverage = new SynchronizedRunningAverage(r1);
		diagnostics.getExternalContinuousVariable("routingTime").relayReportsTo(routingTimeMinuteAverage,Diagnostics.MEAN_VALUE);
		//Initialize the messageSendTimeRequest average
		messageSendTimeRequestMinuteAverage = new SynchronizedRunningAverage(new SimpleIntervalledRunningAverage(ONE_MINUTE_MILLISECONDS_COUNT,100,false));
		diagnostics.getExternalContinuousVariable("messageSendTimeRequest").relayReportsTo(messageSendTimeRequestMinuteAverage,Diagnostics.MEAN_VALUE);
		//Initialize the outputBytes average
		outputBytesLastMinute = new IntervalledSum(ONE_MINUTE_MILLISECONDS_COUNT);
		diagnostics.getExternalCountingVariable("outputBytes").relayReportsTo(outputBytesLastMinute,Diagnostics.COUNT_CHANGE);

		if(rateLimitingInterval == 0)
			throw new IllegalStateException("Must call init() first!");
		
		rlwc = new RateLimitingWriterCheckpointed(routingDir);
		
		rlwc.load();
	}

	final RateLimitingWriterCheckpointed rlwc;
	
	public void begin(Ticker t,
			OpenConnectionManager ocm, NIOInterface[] inter,
			boolean daemon) throws CoreException {
		connectionOpener = 
			new ConnectionOpenerManager(maxNegotiations, rt, ocm, this);
		queueManager = new QueueManager(ocm, (NGRoutingTable)(Main.origRT), t);
		super.begin(t, ocm, inter, daemon);
	}
	
	protected class NodeBackgroundInserter extends BackgroundInserter {
		public NodeBackgroundInserter(
			int nThreads,
			int maxSize,
			ClientFactory cf,
			BucketFactory bf) {
			super(nThreads, maxSize, cf, bf);
		}

		protected void onDone(
			boolean success,
			int htl,
			freenet.client.FreenetURI uri) {
			super.onDone(success, htl, uri);
			logger.log(
				this,
				"Background healing insert: "
					+ (success ? "Success: " : "Failure: ")
					+ ((uri == null) ? "(null)" : uri.toString()),
				Logger.DEBUG);
			if (success)
				diagnostics.occurrenceCounting("successBackgroundInsert", 1);
			else
				diagnostics.occurrenceCounting("failureBackgroundInsert", 1);
		}

		protected void logDebug(String s, boolean trace) {
			if (logger.shouldLog(Logger.DEBUG,this)) {
				if (trace)
					logger.log(this, s, Logger.DEBUG);
				else
					logger.log(
						this,
						s,
						new Exception("debug"),
						Logger.DEBUG);
			}
		}

		public void queue(
			Bucket block,
			BucketFactory owner,
			int htl,
			String cipher) {
			super.queue(block, owner, htl, cipher);
			if (logger.shouldLog(Logger.MINOR,this))
				logger.log(
					this,
					"Queued a background insert at HTL " + htl,
					Logger.MINOR);
			diagnostics.occurrenceCounting("queuedBackgroundInsert", 1);
		}

		protected void onRawEvent(freenet.client.ClientEvent e) {
			if (logger.shouldLog(Logger.DEBUG,this)) {
				logger.log(
					this,
					"BI: " + e.getDescription(),
					Logger.DEBUG);
			}
		}

		protected void onStart() {
			if (logger.shouldLog(Logger.MINOR,this)) {
				logger.log(
					this,
					"BackgroundInserter -- thread started.",
					Logger.MINOR);
			}
		}

		protected void onExit() {
			if (logger.shouldLog(Logger.MINOR,this)) {
				logger.log(
					this,
					"BackgroundInserter -- thread exited.",
					Logger.MINOR);
			}
		}
	}

	/** @return something for the logs.. */
	public final String toString() {
		return "Freenet Node: " + HexUtil.bytesToHex(identity.fingerprint());
	}

	/**
	 * Returns a peer object for which a connection with a node can be made.
	 * 
	 * @param nr
	 *            The node for which a Peer object is needed.
	 */
	public final Peer getPeer(NodeReference nr) {
		return nr.getPeer(transports, sessions, presentations);
	}

	/**
	 * @return Number of jobs running or queued for execution by the node's
	 *         ThreadManager. Can return -1 if no thread manager is being used.
	 */
	public final int activeJobs() {
		return threadFactory.activeThreads();
	}

	public final int availableThreads() {
		return threadFactory.availableThreads();
	}

	public ThreadFactory getThreadFactory() {
		return threadFactory;
	}

	/**
	 * @return true if the Node is Rejecting inbound connections, false
	 *         otherwise.
	 */
	public boolean rejectingConnections() {
		return rejectingConnections(null);
	}

	public boolean rejectingConnections(StringBuffer why) {
		int maximumThreads = threadFactory.maximumThreads();
		int activeThreads = threadFactory.activeThreads();
		if ((maximumThreads > 0) && threadConnectCutoff > 0 && 
				(activeThreads >= (maximumThreads*threadConnectCutoff))) {
			if (why != null) {
				why.append("activeThreads(");
				why.append(activeThreads);
				why.append(") >= maximumThreads (");
				why.append(maximumThreads);
				why.append(")");
			}
			return true;
		}
		if (diagnostics != null
			&& logOutputBytes
			&& doOutLimitConnectCutoff
			&& obw != null) {
			double sent =
				diagnostics.getCountingValue(
					"outputBytes",
					Diagnostics.MINUTE,
					Diagnostics.COUNT_CHANGE);
			if (Double.isNaN(sent))
				sent = 0.0;
			double limit = outputBandwidthLimit * 60 * outLimitConnectCutoff;
			if (sent > limit) {
				if (nextLoggedRejectingConns < System.currentTimeMillis()) {
					logger.log(
						this,
						"Rejecting connections because bwlimit exceeded by 200%!",
						Logger.NORMAL);
					nextLoggedRejectingConns =
						System.currentTimeMillis() + 60000;
				}
				if (why != null) {
					why.append("outputBytesPerMinute(");
					why.append(nf1.format(sent));
					why.append(") > outLimitConnectCutoff(");
					why.append(nf1.format(outLimitConnectCutoff));
					why.append(") * outputBandwidthLimit(");
					why.append(nf1.format(outputBandwidthLimit));
					why.append(") * 60 = ");
					why.append(nf1.format(limit));
				}
				return true; // Reject connections - emergency bwlimiting
			}
		}
		if (diagnostics != null && doRequestTriageByDelay) {
			double delay = routingTimeMinuteAverage.currentValue();
			if (Double.isNaN(delay))
				delay = 0.0;
			if (delay > successfulDelayCutoff) {
				if (why != null) {
					why.append("avgRoutingTime(");
					why.append(nf3.format(delay));
					why.append(") > successfulDelayCutoff(");
					why.append(nf3.format(successfulDelayCutoff) + ")");
				}
				return true;
			}
		}
		if (diagnostics != null && doRequestTriageBySendTime) {
			double delay = messageSendTimeRequestMinuteAverage.currentValue();
			if (Double.isNaN(delay))
				delay = 0.0;
			if (delay > successfulSendTimeCutoff) {
				if (why != null) {
					why.append("avgMessageSendTimeRequest(");
					why.append(nf3.format(delay));
					why.append(") > successfulSendTimeCutoff(");
					why.append(nf3.format(successfulSendTimeCutoff));
					why.append(")");
				}
				return true;
			}
		}
		return false;
	}

	long nextLoggedRejectingConns = 0;

	/**
	 * @param all
	 *            if true, only return true if the node is rejecting nearly all
	 *            incoming reqs
	 * @return true if the Node is QueryRejecting inbound requests, false
	 *         otherwise.
	 */
	public boolean rejectingRequests(boolean all) {
		return rejectingRequests(null, all);
	}

	public boolean rejectingRequests(StringBuffer why, boolean all) {
		if (outboundRequestLimit != null && outboundRequestLimit.exceeded()) {
			if (why != null) {
				why.append("outboundRequestLimit exceeded.");
			}
			return true;
		}
		float estimatedLoad = estimatedLoad(false);
		if (estimatedLoad > (all ? overloadHigh : overloadLow)) {
			if (why != null) {
				why.append("Estimated load (");
				why.append(nfp.format(estimatedLoad));
				why.append(") > overload" + (all ? "High" : "Low") + " (");
				why.append(nfp.format(all ? overloadHigh : overloadLow));
				why.append(")");
			}
			return true;
		}
		return false;
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
			logger.log(this, "Read first line: " + firstline, Logger.DEBUG);
			if(!firstline.startsWith("cpu")) return false;
			long[] data = new long[4];
			for(int i = 0; i < 4; i++) {
				firstline = firstline.substring("cpu".length()).trim();
				firstline = firstline + ' ';
				int x = firstline.indexOf(' ');
				if(x == -1)
					return false;
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
			logger.log(this, "Read from file: user " + user + " nice " + nice + " system " +system + " spare " + spare, Logger.DEBUG);
			return true;
		}

		int calculate(TickStat old) {
			long userdiff = user - old.user;
			long nicediff = nice - old.nice;
			long systemdiff = system - old.system;
			long sparediff = spare - old.spare;

			if (userdiff + nicediff + systemdiff + sparediff <= 0)
				return 0;
			logger.log(this, "User changed by " + userdiff + ", Nice: " + nicediff + ", System: " + systemdiff + ", Spare: " + sparediff, Logger.DEBUG);
			int usage = (int) ((100 * (userdiff + nicediff + systemdiff)) / (userdiff + nicediff + systemdiff + sparediff));
			logger.log(this, "CPU usage: " + usage, Logger.DEBUG);
			return usage;
		}

		void copyFrom(TickStat old) {
			user = old.user;
			nice = old.nice;
			system = old.system;
			spare = old.spare;
		}
	}

	int lastCPULoadEstimate = 0;

	long lastCPULoadEstimateTime = 0;

	File proc = File.separator.equals("/") ? new File("/proc/stat") : null;

	TickStat tsOld = new TickStat();

	TickStat tsNew = null;

	float lastEstimatedLoad = 0;
	float lastEstimatedRateLimitingLoad = 0;
	long lastCalculatedEstimatedLoad = -1;
	long lastCalculatedRateLimitingLoad = -1;
	long minLoadEstimationInterval = 1000; // 1 second
	Object loadSync = new Object();
	
	/**
	 * This is a rough estimate based on the number of running jobs. Hopefully
	 * we can come up with a better metric later.
	 * 
	 * @param forRateLimiting if true, return the rate limiting load, if false,
	 * return the QueryReject load
	 * @return a value between (0.0, 1.0), where 0.0 indicates no load and 1.0
	 *         indicates total overload.
	 *  
	 */
	public final float estimatedLoad(boolean forRateLimiting) {
		return estimatedLoad(null, forRateLimiting);
	}

	public final float estimatedLoad(StringBuffer why, boolean forRateLimiting) {
		double ret;

		long now = System.currentTimeMillis();
		if(why == null) {
			synchronized(loadSync) {
				long last = forRateLimiting ? lastCalculatedRateLimitingLoad :
					lastCalculatedEstimatedLoad;
					if(last + minLoadEstimationInterval > now) {
						return forRateLimiting ? lastEstimatedRateLimitingLoad :
							lastEstimatedLoad;
					}
			}
		}
		
		// FIXME: make it proportional?
		// FIXME: get rid?
		if (outboundRequestLimit != null && outboundRequestLimit.exceeded()) {
			if (why != null) {
				why.append(
					"Load = 100% because outboundRequestLimit is exceeded.");
			}
			return 1.0F;
		}

		// Thread load
		int maximumThreads = threadFactory.maximumThreads();

		if (maximumThreads <= 0) {
			ret = 0.0;
			if (why != null) {
				why.append(
					"Load due to thread limit = 0% because maximumThreads <= 0");
			}
		} else {
			ret = (double) activeJobs() / (double) maximumThreads;
			if (why != null) {
				why.append("Load due to thread limit = ");
				why.append(nfp.format(ret));
			}
		}

		if (doRequestTriageByDelay && forRateLimiting) {
			double delay = routingTimeMinuteAverage.currentValue();
			if (Double.isNaN(delay))
				delay = 0.0;
			double load;
			load = calculateLoadForUnit("routingTime", "ms", why, delay, 
					requestDelayCutoff, successfulDelayCutoff, forRateLimiting);
			
			if (ret < load)
				ret = load;
		}

		if (doRequestTriageBySendTime && forRateLimiting) {
			double delay = messageSendTimeRequestMinuteAverage.currentValue();
			if (Double.isNaN(delay))
				delay = 0.0;
			
			double load = calculateLoadForUnit("messageSendTimeRequest", "ms", why,
					delay, requestSendTimeCutoff, successfulSendTimeCutoff,
					forRateLimiting);
			
			if (load > overloadLow && ret < load)
				ret = load;
		}

		// by backedOffCount
		//if(Main.origRT instanceof NGRoutingTable && forRateLimiting) {
		//    int totnod=((NGRoutingTable)Main.origRT).countConnectedN
		//        odes();
		//    if(totnod>20) {
		//        int openod=((NGRoutingTable)Main.origRT).countUn
		//            backedOffNodes();
		//        double load=(double)(totnod-openod)/(double)totn
		//            od;
		//        // multiply by 1/x , where x is the desired percent
		//        // of backed off routes. As of 08/26, anything below
		//        // about 70% is foolhardy
		//        load *= 1.333 ; // target 75% closed (25% open)
//            if (load > overloadLow && ret < load)
//                ret = load;
		//        if (why != null) {
		//            why.append("<br />Load due to backoff = ");
		//            why.append(nfp.format(load));
		//            why.append(" (");
		//            why.append(openod);
		//            why.append(" of ");
		//            why.append(totnod);
		//            why.append(" backed off)");
//        }
		//    }
		//}

		if (logOutputBytes
			&& (doOutLimitCutoff || forRateLimiting) 
			&& outputBandwidthLimit > 0) {
			double sent = getBytesSentLastMinute();
			if (Double.isNaN(sent))
				sent = 0.0;
			// Because high level limiting is primarily to limit trailers,
			// We want outLimitCutoff => 100%, on both measures.
			double limit = outputBandwidthLimit * 60.0 * outLimitCutoff;
			
			if(doReserveOutputBandwidthForSuccess) {
				// Adjust limit to maximum attainable succcess ratio
				double maxRatio = rt.maxPSuccess();
				double actualRatio = Core.diagnostics.getBinomialValue("routingSuccessRatio", Diagnostics.HOUR, Diagnostics.SUCCESS_PROBABILITY);
				Core.logger.log(this, "maxRatio: "+maxRatio+", actualRatio: "+actualRatio,
						Logger.MINOR);
				if((!Double.isInfinite(actualRatio)) && (!Double.isNaN(actualRatio)) &&
						actualRatio > 0.0 && actualRatio <= 1.0 && (!Double.isNaN(maxRatio)) &&
						!Double.isInfinite(maxRatio) && maxRatio > 0.0 && maxRatio <= 1.0) {
					double oldLimit = limit;
					double adjustment = actualRatio / maxRatio;
					if(adjustment < 1.0) limit = limit * adjustment;
					Core.logger.log(this, "Adjusting limit: max ratio="+maxRatio+", actualRatio="+
							actualRatio+", adjustment: "+adjustment+", limit was: "+oldLimit+
							", limit now: "+limit, Logger.MINOR);
				}
			}
			
			double load = sent / limit;
			if (why != null) {
				why.append("<br />Load due to output bandwidth limiting = ");
				why.append(nfp.format(load));
				why.append(" because outputBytes(");
				why.append(nf03.format(sent));
				why.append(") " + (sent > limit ? ">" : "<=") + " limit (");
				why.append(nf03.format(limit));
				why.append(" ) = outLimitCutoff (");
				why.append(nf03.format(outLimitCutoff));
				why.append(") * outputBandwidthLimit (");
				why.append(nf03.format(outputBandwidthLimit));
				why.append(") * 60");
			}
			if (ret < load)
				ret = load;
		}

		if (doCPULoad && File.separator.equals("/") && forRateLimiting) {
			if (now - lastCPULoadEstimateTime > 1000) {
				try {
					lastCPULoadEstimateTime = now;
					if (tsNew == null) {
						tsOld.read(proc);
						tsNew = new TickStat();
					} else {
						if (!tsNew.read(proc)) {
							logger.log(this, "Failed to parse /proc",
									Logger.MINOR);
						}
						lastCPULoadEstimate = tsNew.calculate(tsOld);
						tsOld.copyFrom(tsNew);
					}
				} catch (Throwable t) {
					lastCPULoadEstimate = 0;
					logger.log(this, "Failed real-CPU-load estimation: "
							+ t, t, Logger.NORMAL);
				}
			}
			float f = (float)(((lastCPULoadEstimate) / 100.0F) / 0.75); 
			// target 75% cpu usage - FIXME
			if(why != null) {
				why.append("<br />\nLoad due to CPU usage = ");
				why.append(nfp.format(f));
				why.append(" = ");
				why.append(lastCPULoadEstimate);
				why.append("% / 0.75");
			}
			
			if (f > ret) ret = f;

		}
		// Predicted inbound bandwidth load
		if(Main.origRT instanceof NGRoutingTable && forRateLimiting) {
			double sentRequestsHour = sentRequestCounter.getExtrapolatedEventsPerHour();
			double pTransfer = ((NGRoutingTable)Main.origRT).pTransferGivenRequest();
			double stdFileSize = calculateStandardFileSize();
			double bytesExpected = sentRequestsHour * pTransfer *
				stdFileSize;
			double maxBytesPerMinute;
			String reason;
			if(inputBandwidthLimit > 0) {
				maxBytesPerMinute = inputBandwidthLimit * 60;
				reason = " (set input limit) ";
			} else {
				maxBytesPerMinute = tcpConnection.maxSeenIncomingBytesPerMinute();
				reason = " (max observed bytes per minute) ";
				// Assume output is at least as wide as input
				// If this is wrong, set inputBandwidthLimit
				double altMaxBytesPerMinute = outputBandwidthLimit * 60 * 4;
				if(altMaxBytesPerMinute > maxBytesPerMinute) {
					maxBytesPerMinute = altMaxBytesPerMinute;
					reason = " (output limit assumed smaller than input capacity) ";
				}
			}
			double maxBytes = maxBytesPerMinute * (60 * 1.1);
			double myLoad = maxBytes==0?0:bytesExpected / maxBytes;
			if(ret < myLoad) ret = myLoad;
			if(why != null) {
				why.append("<br />\nLoad due to expected inbound transfers: ");
				why.append(nfp.format(myLoad));
				why.append(" because: ");
				why.append(sentRequestsHour);
				why.append(" req/hr * ");
				why.append(pTransfer);
				why.append(" (pTransfer) * ");
				why.append(stdFileSize);
				why.append(" bytes = ");
				why.append((long)bytesExpected);
				why.append(" bytes/hr expected from current requests, but maxInputBytes/minute = ");
				why.append((long)maxBytesPerMinute);
				why.append(reason);
				why.append(" * 60 * 1.1 = ");
				why.append((long)maxBytes);
				why.append(" bytes/hr target");
			}
		}
		// Predicted outbound bandwidth load
		if(Main.origRT instanceof NGRoutingTable && forRateLimiting &&
				outputBandwidthLimit > 0 && acceptedExternalRequestCounter.countEvents() > 10) {
			double receivedRequestsHour = acceptedExternalRequestCounter.getExtrapolatedEventsPerHour();
			double pTransfer = ((NGRoutingTable)Main.origRT).pTransferGivenInboundRequest();
			double stdFileSize = calculateStandardFileSize();
			double bytesExpected = receivedRequestsHour * pTransfer *
				stdFileSize;
			double maxBytesPerMinute;
			maxBytesPerMinute = outputBandwidthLimit * 60 * 0.7; // assume significant overhead
			double maxBytes = maxBytesPerMinute * 60;
			double myLoad = maxBytes==0?0:bytesExpected / maxBytes;
			if(ret < myLoad) ret = myLoad;
			if(why != null) {
				why.append("<br />\nLoad due to expected outbound transfers: ");
				why.append(nfp.format(myLoad));
				why.append(" because: ");
				why.append(receivedRequestsHour);
				why.append(" req/hr * ");
				why.append(pTransfer);
				why.append("(");
				why.append(((NGRoutingTable)Main.origRT).whyPTransferGivenInboundRequest());
				why.append(") (pTransfer) * ");
				why.append(stdFileSize);
				why.append(" bytes = ");
				why.append((long)bytesExpected);
				why.append(" bytes/hr expected from current requests, but maxOutputBytes/minute = ");
				why.append((long)maxBytesPerMinute);
				why.append(" * 60 = ");
				why.append((long)maxBytes);
				why.append(" bytes/hr target");
			}
		}
//        if(forRateLimiting) {
//            // Propagation
//            double outgoingRequestRate = sentRequestCounter.getExtrapolatedEventsPerHour();
//            double incomingRequestRate = acceptedExternalRequestCounter.getExtrapolatedEventsPerHour();
//            /**
//             * Don't use it until we have received a certain number of requests.
//             * Otherwise fetches on startup will cause MAJOR problems.
//             */
//            if(acceptedExternalRequestCounter.countEvents() > 10) {
//                double myLoad = outgoingRequestRate / incomingRequestRate;
//                if(ret < myLoad) ret = myLoad;
//                if(why != null) {
//                    why.append("<br />Load due to propagation = ");
//                    why.append(nfp.format(myLoad));
//                    why.append(" = ");
//                    why.append(outgoingRequestRate);
//                    why.append(" / ");
//                    why.append(incomingRequestRate);
//                }
//            }
//        }
//        
//        // Backoff load
//        if (forRateLimiting) {
//            double meanLogBackoffLoad = Core.diagnostics.getContinuousValue("logBackoffLoad", Diagnostics.MINUTE, Diagnostics.MEAN_VALUE);
//            if(!(Double.isNaN(meanLogBackoffLoad))) {
//                double estBackoffLoad = Math.exp(meanLogBackoffLoad);
//                if(estBackoffLoad > ret) ret = estBackoffLoad;
//                if(why != null) {
//                  why.append("<br />\nLoad due to backoff: ");
//                    why.append(nfp.format(estBackoffLoad));
//                    why.append(" = e^");
//                    why.append(meanLogBackoffLoad);
//                    why.append(" (average logBackoffLoad)");
//                }
//            }
//        }
		
		// Allow >100% load either way
//        if ((!forRateLimiting) && ret > 1.0f) {
//            ret = 1.0f;
//        }

		synchronized(loadSync) {
			now = System.currentTimeMillis();
			if(forRateLimiting) {
				lastEstimatedRateLimitingLoad = (float) ret;
				lastCalculatedRateLimitingLoad = now;
			} else {
				lastEstimatedLoad = (float) ret;
				lastCalculatedEstimatedLoad = now;
			}
		}
		
		return (float) ret;
	}

	private double calculateLoadForUnit(String name, String units, StringBuffer why, 
			double delay, int delayCutoff, int successCutoff, 
			boolean forRateLimiting) {
		double load;
		double denom = successCutoff - delayCutoff;
		if(forRateLimiting) {
			load = delay / delayCutoff;
		} else {
			load = overloadLow
			+ (1 - overloadLow)
			* (delay - delayCutoff)
			/ (denom == 0.0 ? 1.0 : denom);
		}
		if (why != null) {
			why.append("<br />\nLoad due to "+name+" = ");
			why.append(nfp.format(load) + " = ");
			if(forRateLimiting) {
				why.append((int)delay + units + " / " +
						delayCutoff + units);
				why.append(load > overloadLow ? " > " : " <= ");
				why.append("overloadLow (");
				why.append(nfp.format(overloadLow));
			} else {
				why.append(
						nfp.format(overloadLow)
						+ " + "
						+ nfp.format(1 - overloadLow)
						+ " * ("
						+ nf3.format(delay)
						+ units
						+ " - "
						+ nf3.format(delayCutoff)
						+ units
						+ ") / "
						+ nf3.format(denom)
						+ units);
			}
			why.append(")");
		}

		return load;
	}

	double lastGlobalQuota;
	long lastGlobalQuotaTime;
	Object lastGlobalQuotaSync = new Object();
	
	/**
	 * @return the target total number of queries per hour that the
	 * node can handle, based on the current load and traffic levels.
	 * If load is zero returns positive infinity.
	 */
	public double getGlobalQuota() {
		/** Cache it for 2 reasons:
		 * 1. Save CPU usage.
		 * 2. Make the averages more or less averages over time, rather than
		 * being biased by message send rates.
		 */
		synchronized(lastGlobalQuotaSync) {
			if(System.currentTimeMillis() < lastGlobalQuotaTime + 5000)
				return lastGlobalQuota;
		}
		// First calculate the total number of queries received per hour
		// This is a kind of average, necessarily...
		double requestsPerHour = receivedRequestCounter.getExtrapolatedEventsPerHour();
		Core.diagnostics.occurrenceContinuous("estimatedIncomingRequestsPerHour",
				requestsPerHour);
		if(Double.isInfinite(requestsPerHour)) {
			return Double.POSITIVE_INFINITY;
		}
		double load = estimatedLoad(true);
		/** DO NOT average load.
		 * Why?
		 * If an external stimulus causes a slightly increased load for
		 * a while, and traffic starts to fall, and then the external stimulus
		 * is taken away, traffic will CONTINUE FALLING for as long as it 
		 * takes for the load average to come back down to below 1.0.
		 */
		Core.diagnostics.occurrenceContinuous("rateLimitingLoad", load);
//        try {
//        loadAverager.report(rawLoad);
//        } catch (IllegalArgumentException e) {
//            logger.log(this, "Caught exception reporting load to load averager",e,
//                    Logger.NORMAL);
//        }
//        double load = loadAverager.currentValue();
//        Core.diagnostics.occurrenceContinuous("rateLimitingLoad", load);
		if(load == 0.0) return Double.POSITIVE_INFINITY;
		double ret = requestsPerHour / load; 
		Core.diagnostics.occurrenceContinuous("globalQuotaPerHourRaw", ret);
		// Now clip it to the maximum the link can sustain
		double wasGlobalQuota = ret;
		double maxGlobalQuota = getMaxGlobalQuota();
		if(ret > maxGlobalQuota) ret = maxGlobalQuota;
		logger.log(this, "getGlobalQuota(): requests per hour: "+
				requestsPerHour+", load: "+load+
				", raw globalQuota="+wasGlobalQuota+", maxGlobalQuota="+
				maxGlobalQuota+", unaveraged globalQuota="+ret, Logger.MINOR);
		// Now we DEFINITELY need to average the output
		globalQuotaAverager.report(ret);
		ret = globalQuotaAverager.currentValue();
		Core.diagnostics.occurrenceContinuous("globalQuotaPerHour", ret);
		logger.log(this, "getGlobalQuota() returning "+ret, Logger.MINOR);
		synchronized(lastGlobalQuotaSync) {
			lastGlobalQuota = ret;
			lastGlobalQuotaTime = System.currentTimeMillis();
		}
		return ret;
	}
	
	/**
	 * @return the upper limit on the globalQuota, calculated from
	 * the output and input bandwidth limits.
	 */
	public double getMaxGlobalQuota() {
		int limit = 0;
		if(outputBandwidthLimit > 0) limit = outputBandwidthLimit;
		if(inputBandwidthLimit > 0 && inputBandwidthLimit < outputBandwidthLimit)
			limit = inputBandwidthLimit;
		if(limit > 0) {
			double pTransfer = ((NGRoutingTable)Main.origRT).pTransferGivenInboundRequest();
			double maxGlobalQuota = (limit * 60 * 60) /
				(calculateStandardFileSize() * pTransfer);
			return maxGlobalQuota;
		}
		return Double.MAX_VALUE;
	}

	private void accept(
		Key searchKey,
		int hopsToLive,
		String diagAddr,
		String verstr) {
		if (inboundRequests != null && diagAddr != null) {
			inboundRequests.incSuccesses(diagAddr);
		}
		acceptedExternalRequestCounter.logEvent();
	}

	private void reject(
		Key searchKey,
		int hopsToLive,
		String diagAddr,
		String verstr) {
		// Do nothing
	}

	private static final int DEFAULT_FILE_SIZE = 350000;
	// typical value 13/3/04

	public static long calculateStandardFileSize(Node node) {
		if (node == null)
			return DEFAULT_FILE_SIZE;
		else return node.calculateStandardFileSize();
	}
	
	long lastCalculatedStandardFileSizeTime = -1;
	private boolean isRecalculatingStandardFileSize = false;
	long lastStandardFileSize = 0;
	private final Object fileSizeTimestampSync = new Object();
	
	//Takes a shot on calculating a mean network file size
	//falls back to 128k if insufficent data is available or this NGRT is
	//unable to ask the store (== if this NGRT doesn't know what node it is
	// used in)
	public long calculateStandardFileSize() {
			long now = System.currentTimeMillis();
		//Avoid contention for the lastUpdatedNewNodeStatsLock lock if possible
		if (isRecalculatingStandardFileSize || now - lastCalculatedStandardFileSizeTime < 60*1000)
			return lastStandardFileSize;
		
		//Then test again, holding the proper lock, just to be _sure_ that we should run
		synchronized(fileSizeTimestampSync){
			if (isRecalculatingStandardFileSize || now - lastCalculatedStandardFileSizeTime < 60*1000)
				return lastStandardFileSize;
			isRecalculatingStandardFileSize = true;
			}
		try{
			long keys = dir.countKeys();
			if (keys > 16) {
				lastStandardFileSize = dir.used() / keys;  
				lastCalculatedStandardFileSizeTime = now;
				return lastStandardFileSize;
			} else
				return DEFAULT_FILE_SIZE;
		}finally{
			lastCalculatedStandardFileSizeTime = now;
			isRecalculatingStandardFileSize = false;
		}
	}

	public void logRequest(Key k) {
		receivedRequestCounter.logEvent();
	}
	
	/**
	 * Hook for rate limiting.
	 */
	public boolean acceptRequest(
		Key searchKey,
		int hopsToLive,
		Address source,
		String verstr) {

		String diagAddr = null;

		if (inboundRequests != null) {
			if (source != null) {
				diagAddr = source.toString();
				inboundRequests.incTotal(diagAddr);
				inboundRequests.setLastVersion(diagAddr, verstr);
			}
		}

		float load = estimatedLoad(false);

		if ((outboundRequestLimit == null
			|| (!outboundRequestLimit.exceeded()))
			&& load < overloadLow) {
			accept(searchKey, hopsToLive, diagAddr, verstr);
			return true;
		}

		// Node is loaded
		if (load < overloadHigh) {
			if (searchKey != null
				&& ds.contains(searchKey)) {
				accept(searchKey, hopsToLive, diagAddr, verstr);
				return true;
			} else {
				// Give the request another chance

				// 0.0 = (almost) always accept
				// 1.0 = (almost) never accept
				double rankFraction;
				if (Main.origRT instanceof NGRoutingTable &&
						searchKey != null) {
					/** Selective request accept/reject, based on estimate.
					 * a.k.a. unobtanium accept.
					 * 
					 * We want to accept the top x% of incoming requests, where
					 * x depends on load, by their estimates (lower is better).
					 * 
					 * So we route the request and see what the estimate is for
					 * the first node on the list.
					 */
					NGRouting routes = (NGRouting) (Main.origRT.route(searchKey, hopsToLive, calculateStandardFileSize(),
						/* searchKey.getExpectedTransmissionLength(), */ //Don't bias the estimate with the size of the key
						false, false, false, false, false));

					if (routes.getNextRoute() == null) {
						// Sh*t happens
						// No currently contactable nodes we could route to
						rankFraction = Math.random();
						logger.log(this, "Initial getNextRoute() call during load calculation returned no estimate, using random value "
							+ "for rankFraktion, routes=" + routes, Logger.MINOR);
					} else {
						double estimate = routes.lastEstimatedTime();
						routes.terminateNoDiagnostic();
						rankFraction = getRankFraction(estimate);
						if(logger.shouldLog(Logger.DEBUG, this))
							logger.log(this, "Unobtanium: key=" + searchKey + ", hopsToLive=" + hopsToLive + " -> estimate: " + estimate
								+ " -> rank: " + rankFraction, Logger.MINOR);
					}
				} else {
					rankFraction = Math.random();
				}
					// If loadThreshold is higher than load, reject
					// Therefore loadThreshold high = accept more requests 
					double loadThreshold =
						overloadLow + (1.0-rankFraction) * (overloadHigh - overloadLow);
			
				logger.log(
						this,
						"Unobtanium: "
							+ (load <= loadThreshold ? "ACCEPTING" : "REJECTING")
							+ " load threshold = "
							+ loadThreshold
							+ ", load="
							+ load
							+ ", rank="
						+ rankFraction,
						Logger.MINOR);
				
					if (load <= loadThreshold) {
						accept(searchKey, hopsToLive, diagAddr, verstr);
						return true;
					}
				
			}
			reject(searchKey, hopsToLive, diagAddr, verstr);
			return false;
		}

		// Over overloadHigh, we don't accept ANYTHING
		
		reject(searchKey, hopsToLive, diagAddr, verstr);
		// Node is overloaded
		return false;
	}

	protected Object selectiveAcceptRankingSync = new Object();
	protected final int SELECTIVE_ACCEPT_REQUESTS = 100;
	protected Comparable[] selectiveAcceptList =
		new Comparable[SELECTIVE_ACCEPT_REQUESTS];
	protected int selectiveAcceptListPosition = 0;

	/**
	 * Return the rank of the request by estimate in a list of recent requests
	 * 
	 * @param estimate
	 * @return rank between 0.0 and 1.0. 1.0=estimate is higher than any in
	 *         list. 0.0=estimate is lower than any in list.
	 */
	private double getRankFraction(double estimate) {
		return getRankFraction(new Double(estimate));
	}

	private double getRankFraction(Comparable estimate) {
		if (estimate == null) return 0.0;
		int requestsBelow = 0;
		int curLength = 0;
		synchronized (selectiveAcceptRankingSync) {
			// Iterate the list
			// We want to find:
			// A) the number of items in the list
			// B) the number of items with estimates lower than that of the
			// estimate

			for (curLength = 0;
				curLength < SELECTIVE_ACCEPT_REQUESTS;
				curLength++) {
				Comparable cur = selectiveAcceptList[curLength];
				if (cur == null)
					break;
				if (cur.compareTo(estimate) < 0)
					requestsBelow++;
			}

			selectiveAcceptList[selectiveAcceptListPosition] = estimate;
			selectiveAcceptListPosition++;
			selectiveAcceptListPosition %= SELECTIVE_ACCEPT_REQUESTS;
		}
		if (curLength == 0)
			return 0.0;
		return ((double) requestsBelow) / ((double) curLength);
	}

	//=== communications methods
	// ===============================================

	// /**
	//  * Returns a connection that messages can be sent on. This saves a little
	//  * time as getPeer is only called if a free connection isn't available.
	//  * 
	//  * @param nr
	//  *            The node to connect to.
	//  * @param timeout
	//  *            The time to allow in connecting.
	//  */
	//     public ConnectionHandler makeConnection(NodeReference nr, long timeout)
	//                                             throws CommunicationException {
	//         Peer p = getPeer(nr);
	//         if (p == null)
	//             throw new ConnectFailedException(new VoidAddress(), nr.getIdentity(),
	//                                              "Unusable node ref", true);
	//         return makeConnection(p, timeout);
	//    }

	//     public final ConnectionHandler makeConnection(NodeReference nr)
	//                                             throws CommunicationException {
	//         return makeConnection(nr, 0);
	//     }

	/**
	 * Sends a message accross an open or new connection. This saves a little
	 * time as getPeer is only called if a free connection isn't available.
	 * 
	 * @param nr
	 *            The node to connect to.
	 * @param m
	 *            The message to send.
	 * @param timeout
	 *            The time to allow in connecting.
	 * @return The trailing field stream (if there is one).
	 */
	public final TrailerWriter sendMessage(
		Message m,
		NodeReference nr,
		long timeout)
		throws SendFailedException {
		return connections.sendMessage(
			m,
			nr.getIdentity(),
			nr,
			timeout,
			PeerPacketMessage.NORMAL,
			presentations.getDefault());
	}

	/**
	 * Sends a message accross an open or new connection. This saves a little
	 * time as getPeer is only called if a free connection isn't available.
	 * 
	 * @param id
	 *            The node to connect to.
	 * @param m
	 *            The message to send.
	 * @param timeout
	 *            The time to allow in connecting.
	 * @return The trailing field stream (if there is one).
	 */
	public final TrailerWriter sendMessage(
		Message m,
			Identity id,
			long timeout)
		throws SendFailedException {
		return connections.sendMessage(
			m,
				id,
			null,
			timeout,
				PeerPacketMessage.NORMAL,
				presentations.getDefault());
	}

	
	
	/**
	 * Send a message, asynchronously
	 * 
	 * @param m
	 *            the message
	 * @param p
	 *            the peer
	 * @param msgPrio
	 *            the priority of the message, see PeerHandler
	 * @param timeout -
	 *            number of milliseconds after which the send will be dropped.
	 * @param cb
	 *            callback to be called when the message send has completed
	 *            (successfully or not) - null if no notification is needed.
	 */
	public final void sendMessageAsync(
		Message m,
		Peer p,
		int msgPrio,
		long timeout,
		MessageSendCallback cb) {
		connections.sendMessageAsync(
			m,
			p.getIdentity(),
			null,
			cb,
			timeout,
			msgPrio);
	}

	public final void sendMessageAsync(
		Message m,
		Peer p,
		long timeout,
		MessageSendCallback cb) {
		connections.sendMessageAsync(
			m,
			p.getIdentity(),
			null,
			cb,
			timeout,
			PeerPacketMessage.NORMAL);
	}

	public final void sendMessageAsync(
		Message m,
		NodeReference nr,
		long timeout,
		MessageSendCallback cb) {
		connections.sendMessageAsync(
			m,
			nr.getIdentity(),
			nr,
			cb,
			timeout,
			PeerPacketMessage.NORMAL);
	}

	public final void sendMessageAsync(
			Message m,
			Identity id,
			long timeout,
			MessageSendCallback cb) {
		connections.sendMessageAsync(
				m,
				id,
				null,
				cb,
				timeout,
				PeerPacketMessage.NORMAL);
	}

	public final void sendMessageAsync(
		Message m,
			Identity id,
			int msgPrio,
			long timeout,
			MessageSendCallback cb) {
		connections.sendMessageAsync(m, id, null, cb, timeout, msgPrio);
	}
	
	public final void sendMessageAsync(
		Message m,
		NodeReference nr,
		int prio,
		long timeout,
		MessageSendCallback cb) {
		connections.sendMessageAsync(
			m,
			nr.getIdentity(),
			nr,
			cb,
			timeout,
			prio);
	}

	public final void unsendMessage(Identity i, MessageSendCallback cb) {
		connections.unsendMessage(i, cb);
	}

	/**
	 * Change the HTL given slightly, randomly, if it is high enough.
	 * Use with care. Specifically, because of the 50% chance of not
	 * decrementing the HTL at maxHTL, should NOT be used on individual
	 * blocks. Otherwise you get a telltale distribution of HTLs which
	 * can distinguish between first hop and second hop (i.e. you get 
	 * equal numbers on 16,17,18,19,20 on the first hop, on the second
	 * hop you get half as many on 20 and 19 but equal numbers on 15-18,
	 * on the third hop... etc). So only use this for individual 
	 * requests.
	 * @param htl
	 * @return
	 */
	public static int perturbHTL(int htl) {
		float f = getRandSource().nextFloat();
		Core.logger.log(Node.class, "Perturbing HTL: htl="+htl, Logger.MINOR);
		if (maxHopsToLive == 0) return htl;
		if (htl > 3 && ((float)htl / (float)maxHopsToLive) > 0.5) {
			if(htl > (maxHopsToLive - 2)) htl = (maxHopsToLive - 2);
			Core.logger.log(Node.class, "HTL now "+htl, Logger.MINOR);
			f = getRandSource().nextFloat();
			if (f < 0.2)
				htl += 2;
			else if (f < 0.4)
				htl += 1;
			else if (f < 0.6)
				htl += 0;
			else if (f < 0.8)
				htl -= 1;
			else
				htl -= 2;
		}
		Core.logger.log(Node.class, "HTL now: "+htl, Logger.MINOR);
		if (htl > maxHopsToLive)
			htl = maxHopsToLive;
		return htl;
	}

	public boolean shouldCache(freenet.message.StoreData sd) {
		if (logger == null)
			throw new NullPointerException();
		if (sd == null) {
			logger.log(
				this,
				"shouldCache returning true because sd == null",
				Logger.DEBUG);
			return true;
		}
		logger.log(
			this,
			"shouldCache(" + (sd == null ? "(null)" : sd.toString()) + ")",
			Logger.DEBUG);
		if (storeSize == 0) {
			logger.log(
				this,
				"shouldCache returning true because " + "storeSize == 0",
				Logger.DEBUG);
			return true;
		}
		long used = dir.used();
		long target = (long) (storeSize * (double) minStoreFullPCache);
		if (used < target) {
			logger.log(
				this,
				"shouldCache returning true because "
					+ "used space ("
					+ used
					+ ") < target ("
					+ target
					+ ") "
					+ "out of maximum "
					+ storeSize,
				Logger.DEBUG);
			return true;
		}
		if (sd.shouldCache(getRandSource(), cacheProbPerHop)) {
			logger.log(
				this,
				"shouldCache returning true because "
					+ "sd.shouldCache says so for "
					+ sd,
				Logger.DEBUG);
			return true;
		} else {
			logger.log(
				this,
				"shouldCache returning false because "
					+ "sd.shouldCache says so for "
					+ sd,
				Logger.DEBUG);
			return false;
		}
	}

	public boolean shouldReference(
		NodeReference nr,
		freenet.message.StoreData sd) {
		return rt.shouldReference(nr, sd);
	}

	/**
	 * Add a reference for a node.
	 * 
	 * @param nr
	 *            the reference to be added to the routing table
	 * @param k
	 *            the key this resulted from, which may be ignored by the RT
	 * @param estimator
	 *            FieldSet of initial estimator, or null
	 */
	public void reference(Key k, Identity id, 
			NodeReference nr, FieldSet estimator) {
		boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);
		if(logDEBUG) logger.log(this, "referencing: "+k+", "+nr+", "+
					estimator, Logger.DEBUG);
		if(id == null) id = nr.getIdentity();
		if(id == null) {
			Core.logger.log(this, "Referencing: "+k+","+id+","+nr+","+estimator,
					new Exception("debug"), Logger.ERROR);
			return;
		}
		rt.reference(k, id, nr, estimator);
		if(logDEBUG) logger.log(this, "adding peer for: "+id+": "+ nr,
					Logger.DEBUG);
		connections.makePeerHandler(id, nr, presentations.getDefault());
		if(logDEBUG) logger.log(this, "scheduling connection open: "+id+
					": "+nr, Logger.DEBUG);
		connectionOpener.rescheduleNow();
		if(logDEBUG) logger.log(this, "Referenced "+k+": "+nr+": "+
					estimator, Logger.DEBUG);
	}

	/**
	 * Create ConnectionOpeners to open connections to all nodes in the RT,
	 * called on startup. Also creates PeerHandlers for each.
	 */
	public void scheduleOpenAllConnections() {
		logger.log(this, "Scheduling open on all connections", Logger.MINOR);
		RTDiagSnapshot snap =
			rt.getSnapshot(true);
		IdRefPair[] nodes = snap.getIdRefPairs();
		for(int i=0;i<nodes.length;i++)
			connections.makePeerHandler(nodes[i].id, nodes[i].ref, 
					presentations.getDefault());
		logger.log(this, "Scheduling open on all "+nodes.length+" connections",
				Logger.MINOR);
		rescheduleConnectionOpener();
		logger.log(this, "Scheduled open on all connections",
			Logger.MINOR);
	}

	public void rescheduleConnectionOpener() {
		if(connectionOpener != null)
			connectionOpener.reschedule();
	}

	public int getMaxPacketLength() {
		if (obw != null)
			return obw.maximumPacketLength();
		else
			return 1492; // fixme
	}

	/**
	 * @return
	 */
	public int getMaxTrailerChunkSize() {
		if (obw != null) {
			int i = obw.maximumPacketLength();
			i = i / 5;
			/** Impose a minimum:
			 * The chunk overhead is 10 bytes
			 * Lets say the maximum acceptable overhead is 5%
			 * That makes 200 bytes a reasonable minimum.
			 */
			if(i < 200) i = 200;
			return i;
		} else {
			return 500;
		}
	}

	long lastReportedRequestInterval = -1;
	
	public double getBytesSentLastMinute()
	{
		return outputBytesLastMinute.currentSum();
	}

	/**
	 * @param success whether the request succeeded
	 * @param backedOffCount the number of nodes that were tried and unavailable
	 * due to backoff or rate limiting, before we reached a node that eventually
	 * sent us a DNF or a transfer.
	 */
	public void routingRequestEndedWithBackedOffCount(boolean success, int backedOffCount) {
		if(backedOffCount > rtMaxNodes) backedOffCount = rtMaxNodes;
		if(backedOffCount < 0) backedOffCount = 0;
		synchronized(Node.syncCountsByBackoffCount) {
			if(success)
				Node.successesByBackoffCount[backedOffCount]++;
			else
				Node.failuresByBackoffCount[backedOffCount]++;
		}
	}
	
	public String routingResultsByBackoffCount() {
		StringBuffer sb = new StringBuffer();
		synchronized(Node.syncCountsByBackoffCount) {
			sb.append("Backoff count       Successes      Failures\n");
			for(int i=0;i<=rtMaxNodes;i++) {
				long successes = Node.successesByBackoffCount[i];
				long failures = Node.failuresByBackoffCount[i];
				String s1 = Integer.toString(i);
				sb.append(s1);
				for(int j=0;j<20-s1.length();j++) sb.append(' ');
				s1 = Long.toString(successes);
				sb.append(s1);
				for(int j=0;j<20-s1.length();j++) sb.append(' ');
				s1 = Long.toString(failures);
				sb.append(s1);
				for(int j=0;j<20-s1.length();j++) sb.append(' ');
				sb.append(Double.toString(successes/((double)failures+successes)));
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * @return
	 */
	public double getActualRequestsPerHour() {
		return receivedRequestCounter.getExtrapolatedEventsPerHour();
	}

	/**
	 * @return whether we want a single incoming connection, right now.
	 */
	public boolean wantIncomingConnection() {
		// Is the last node in the RT newbie?
		return connections.wantUnkeyedReference();
	}

	/**
	 * @return the highest seen build number for nodes of the same
	 * type as us. Unlike Version, we actually check that more than 
	 * one node has this :).
	 */
	public int getHighestSeenBuild() {
		return connections.getHighestSeenBuild(this);
	}

	public BackgroundInserter newBackgroundInserter(int i, int j, ClientFactory factory, BucketFactory bf2) {
		return new NodeBackgroundInserter(i,j,factory,bf2);
	}

	/**
	 * @return true if this particular request should be routed 
	 * to the newest node first.
	 */
	public static boolean shouldRouteByNewness() {
		return (getRandSource().nextInt(20) == 0);
	}

	/**
	 * Get a string-format address for a given identity
	 * @param origPeer
	 * @return
	 */
	public String getStringAddress(Identity origPeer) {
		NodeReference ref = rt.getNodeReference(origPeer);
		if(ref == null) return "(null)";
		return ref.firstPhysicalToString();
	}

	static final int PADDING_CHUNK_SIZE = 160;
	
	/**
	 * Pad a packet size up to a reasonable level that minimizes the
	 * amount of information given away.
	 * For now, lets round to the nearest PADDING_CHUNK_SIZE bytes.
	 */
	public static int padPacketSize(int totalLength) {
		if(totalLength % PADDING_CHUNK_SIZE == 0) return totalLength;
		return ((totalLength / PADDING_CHUNK_SIZE) + 1) * PADDING_CHUNK_SIZE;
	}

	public static int minPaddingChunkSize() {
		return PADDING_CHUNK_SIZE;
	}

	public Checkpointed getRateLimitingWriterCheckpoint() {
		return rlwc;
	}

	/**
	 * Log a sent request
	 */
	public void logOutgoingRequest() {
		sentRequestCounter.logEvent();
	}
}
