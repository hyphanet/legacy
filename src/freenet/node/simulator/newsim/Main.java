/**
 * Yet another Freenet simulator, by Toad.
 * Lets try not to make the SAME mistakes as made in previous versions here.
 * Make different ones! :)
 * @author amphibian
 */
package freenet.node.simulator.newsim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

import freenet.Core;
import freenet.Key;
import freenet.config.Params;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;

/**
 * Static class to drive the simulator from the command line.
 */
public class Main {

    public Main() {
    }

    static Simulator sim;
    static Random prng;
    private static PrintStream resultsStream;
    static boolean logDEBUG;
    
    public static void main(String[] args) throws IOException {
        Params params = paramsFromArgs(args);
        SimConfig sc;
        try {
            sc = new SimConfig(params);
        } catch (OptionParseException e) {
            System.err.println("Could not parse options: "+e.getMessage());
            SimConfig.printUsage();
            return;
        }
        setupLogging(params, sc);
        long l = freenet.Core.getRandSource().nextLong();
        System.err.println("Seed: "+l);
        prng = new Random(l);
        sim = new Simulator(sc, prng, Core.logger); // will reload if needed, announce if needed, etc
        sim.logger.log(Main.class, "Started simulator", Logger.NORMAL);
        System.err.println("Started simulator");
        // Now run it
        Main.lastTotalAttemptedConnections = sim.totalAttemptedConnections;
        Main.lastTotalConnections = sim.totalConnections;
        runTests();
    }

    /**
     * Setup logging
     */
    private static void setupLogging(Params params, SimConfig sc) throws IOException {
        // log file is determined by what options are enabled etc.
        File logFile = makeLogFile(sc);
        // allow override though
        String s = params.getString("logFile");
        String resultsFile = logFile + ".results.log";
        Main.resultsStream = new PrintStream(new FileOutputStream(resultsFile));
        System.err.println("Logging results to "+resultsFile);
        if(!(s == null || s.equals(""))) {
            logFile = new File(s);
        }
        System.err.println("Log file: "+logFile);
        // logLevel and logLevelDetail from params
        String logLevel = params.getString("logLevel");
        String logLevelDetail = params.getString("logLevelDetail");
        String logFormat = params.getString("logFormat");
        String logDate = params.getString("logDate");
		int logMaxLinesCached = params.getInt("logMaxLinesCached");
		long logMaxBytesCached = params.getInt("logMaxBytesCached");
		FileLoggerHook flh;
		flh = new FileLoggerHook(false, logFile.getAbsolutePath(), logFormat, logDate, Logger.NORMAL, false, false);
		Core.setLogger(flh);
		flh.setThreshold(logLevel);
		flh.setDetailedThresholds(logLevelDetail);
		flh.setMaxListLength(logMaxLinesCached);
		flh.setMaxListBytes(logMaxBytesCached);
		flh.start();
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, Main.class);
    }

    /**
     * Return the filename of the log file.
     * Create any needed directories.
     */
    private static File makeLogFile(SimConfig sc) {
        String filename = "simulator-"+sc.getOptionsString()+"---"+getDateString();
        return new File(filename);
    }

    static final String DATE_FORMAT_STRING ="yyyy-MM-dd-HH-mm-ss";
    static final DateFormat df = new SimpleDateFormat(DATE_FORMAT_STRING);
    
    /**
     * @param l
     * @return
     */
    private static String getDateString() {
        return df.format(new Date());
    }
    
    /**
     * Run some tests
     */
    private static void runTests() {
        // Run 50 inserts, for each, run 100 requests
        // All of these are between random nodes
        // Then print the stats.
        // Then do it again
        Stats globalStats = new Stats();
        Stats localStats = new Stats();
        KeyCollector keys = new KeyCollector(sim.myConfig.testKeys, prng);
        while(true) {
            // 100,000 requests
            for(int q=0;q<20;q++) {
            localStats.clear();
            // 5000 requests
            for(int i=0;i<50;i++) {
                // Run an insert
                runInsert(globalStats, localStats, keys);
                // Now run 100 requests
                for(int j=0;j<100;j++) {
                    runRequest(globalStats, localStats, keys);
                }
            }
            dumpStats(globalStats, localStats);
            }
            sim.dumpStores();
        }
    }

    private static void runInsert(Stats globalStats, Stats localStats, KeyCollector keys) {
        // Run an insert
        Key k = freenet.keys.CHK.randomKey(prng);
        sim.logger.log(Main.class, "Running insert: "+k, Logger.MINOR);
        keys.add(k);
        Node n = sim.randomNode();
        InsertContext ic = new InsertContext(sim.myConfig.insertHTL);
        boolean success = n.runInsert(k, null, ic);
        globalStats.totalInserts++;
        localStats.totalInserts++;
        if(!success) {
            if(logDEBUG)
                sim.logger.log(Main.class, "Return code: "+ic.lastFailureCodeToString(), Logger.DEBUG);
        }
        if(success) {
            globalStats.successfulInserts++;
            localStats.successfulInserts++;
        }
    }

    public static void runRequest(Stats globalStats, Stats localStats, KeyCollector keys) {
        KeyWithCounter kc = keys.getRandomKey();
        boolean keyIsNew = kc.getCount() == 0;
        Key k = kc.getKeyIncCounter();
        sim.logger.log(Main.class, "Running request: "+k, Logger.MINOR);
        Node n = sim.randomNode();
        RequestContext rc = new RequestContext(sim.myConfig.requestHTL);
        boolean success = n.runRequest(k, null, rc);
        globalStats.totalRequests++;
        localStats.totalRequests++;
        if(keyIsNew) {
            globalStats.totalFirstTimeRequests++;
            localStats.totalFirstTimeRequests++;
        }
        if(!success) {
            if(logDEBUG)
                sim.logger.log(Main.class, "Return code: "+rc.lastFailureCodeToString(), Logger.DEBUG);
        }
        if(success) {
            globalStats.successfulRequests++;
            localStats.successfulRequests++;
            if(keyIsNew) {
                globalStats.totalFirstTimeSuccessfulRequests++;
                localStats.totalFirstTimeSuccessfulRequests++;
            }
        }
    }
    
    static long lastTotalConnections = 0;
    static long lastTotalAttemptedConnections = 0;
    
    /**
     * Write the statistics to stderr in a reasonably easy to parse and read format.
     */
    private static void dumpStats(Stats globalStats, Stats localStats) {
        /** Format:
         * INSERTS: 15000/15000=1.0 150/150=1.0
         * REQUESTS: 999500/100000=0.9995 100/100=1.0
         */
        String message = "INSERTS: "+mformat(globalStats.successfulInserts, globalStats.totalInserts) +
        	" "+mformat(localStats.successfulInserts,localStats.totalInserts)+"\n"+
        	"REQUESTS: "+mformat(globalStats.successfulRequests, globalStats.totalRequests)+
        	" "+mformat(localStats.successfulRequests,localStats.totalRequests)+"\n"+
        	"FIRST-TIME: "+mformat(globalStats.totalFirstTimeSuccessfulRequests, globalStats.totalFirstTimeRequests)+
        	" "+mformat(localStats.totalFirstTimeSuccessfulRequests, localStats.totalFirstTimeRequests)+"\n";
        System.err.print(message);
        resultsStream.print(message);
        // Now some more detailed stats
        sim.dumpStats();
        long d = sim.totalConnections - lastTotalConnections;
        System.err.println("Connections made: "+d);
        resultsStream.println("Connections made: "+d);
        lastTotalConnections = sim.totalConnections;
        d = sim.totalAttemptedConnections - lastTotalAttemptedConnections;
        System.err.println("Connections attempted: "+d);
        resultsStream.println("Connections attempted: "+d);
        lastTotalAttemptedConnections = sim.totalAttemptedConnections;
    }

    private static String mformat(int success, int total) {
        return ""+success+"/"+total+"="+((double)success/(double)total);
    }

    /**
     * @param args
     * @return
     */
    private static Params paramsFromArgs(String[] args) {
        return SimConfig.paramsFromArgs(args);
    }
}
