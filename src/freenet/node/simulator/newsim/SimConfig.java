package freenet.node.simulator.newsim;

import freenet.config.Config;
import freenet.config.Params;

/**
 * All configuration data for the simulator is kept here.
 */
public class SimConfig {

    /** If true, all nodes are connected to all other nodes, hence initialRTSize
     <= initialNodes-1 */
    final boolean isFullyConnected;
    /** Number of nodes at startup */
    final int initialNodes;
    /** Maximum size of each node's routing table */
    final int maxRTSize;
    /** Maximum size of each node's datastore */
    final int maxDSSize;
    /** Don't drop any node until at least this many nodes are "experienced" */
    final int minExperiencedNodes;
    /** Nodes are "experienced" when they have at least this many hits */
    final int experienceHits;
    /** If this is true, don't drop any node which is not "experienced".
     * Even if this is false, we drop experienced nodes first. */
    final boolean dontDropInexperiencedNodes;
    /* In combination, you can get new and old behaviours from the above:
     * For the old behaviour, set dontDropInexperiencedNodes to true, 
     * minExperiencedNodes to zero, and experienceHits to however many.
     * 
     * For the new behaviour, set minExperiencedNodes to maxRTSize, and set 
     * experienceHits.
     * 
     * For a hybrid a la KenMan, set minExperiencedNodes to some fraction of
     * maxRTSize, set dontDropInexperiencedNodes to true, and set experienceHits
     * appropriately.
     */
    /** Number of keys to keep "live" and fetching for testing */
    final int testKeys;
    /** Initial HTL for test inserts */
    final int insertHTL;
    /** Initial HTL for test requests */
    final int requestHTL;
    /** Type of estimator */
    final int estimatorClass;
    /** If true, do probabilistic caching */
    final boolean doPCaching;
    static final int CLASS_PROBABILITY_ONLY = 0;
    static final int CLASS_ALL_THREE = 1;
    static final int CLASS_TSUCCESS_FIXED_PENALTY = 2;
    private static Config config = new Config();
    /** Whether to create connections from successful requests */
    boolean doRequestConnections;
    
    static {
        config.addOption("fullyconnected", 1, true, 1);
        config.addOption("nodes", 1, 100, 2);
        config.addOption("rt", 1, 25, 3);
        config.addOption("dropInexperienced", 1, true, 6);
        config.addOption("experienceHits", 1, 200, 7);
        config.addOption("minRTFracExperienced", 1, 1.0, 8);
        config.addOption("fetchFrac", 1, 0.025, 9);
        config.addOption("htl", 1, 10, 10);
        config.addOption("insertHTL", 1, -1, 11);
        config.addOption("requestHTL", 1, -1, 12);
        config.addOption("estimatorClass", 1, "3est", 13);
        config.addOption("maxDSSize", 1, 100, 14);
        config.addOption("doConns", 1, true, 15);
        config.addOption("doPcaching", 1, true, 16);
        // Logging options - parsed directly by Main
        config.addOption("logFile", 1, "", 101);
        config.addOption("logLevel", 1, "normal", 102);
        config.addOption("logLevelDetail", 1, "", 103);
        config.addOption("logFormat", 1, "d (c, t, p): m", 104);
        config.addOption("logDate", 1, "", 105);
        config.addOption("logMaxLinesCached", 1, 10000, 106);
        config.addOption("logMaxBytesCached", 1, 1024*1024, 107);
        
        config.shortDesc("fullyconnected", "If true, each node is connected to each other node");
        config.argDesc("fullyconnected", "boolean");
        config.shortDesc("nodes", "Number of nodes to simulate");
        config.argDesc("nodes", "<positive integer>");
        config.shortDesc("rt", "Number of routes in each node's routing table");
        config.argDesc("rt", "<positive integer>");
        config.shortDesc("dropInexperienced", "Whether to allow dropping inexperienced nodes");
        config.argDesc("dropInexperienced", "yes or no");
        config.shortDesc("experienceHits", "Number of hits before a node is considered experienced");
        config.argDesc("experienceHits", "Number of hits");
        config.shortDesc("minRTFracExperienced", "Don't drop any nodes until at least this fraction of the routing table is experienced");
        config.argDesc("minRTFracExperienced", "Proportion between 0.0 and 1.0");
        config.shortDesc("fetchFrac", "Fraction of life datastore 'slots' to be considered live and fetched from");
        config.argDesc("fetchFrac", "Proportion between 0.0 and 1.0");
        config.shortDesc("htl", "Hops to live for requests and inserts");
        config.argDesc("htl", "<Number of hops>");
        config.shortDesc("insertHTL", "Override HTL for inserts");
        config.argDesc("insertHTL", "<Number of hops>");
        config.shortDesc("requestHTL", "Override HTL for requests");
        config.argDesc("requestHTL", "<Number of hops>");
        config.shortDesc("estimatorClass", "Estimator implementation class");
        config.argDesc("estimatorClass", "3est, psuccess or tsuccess-kludge");
        config.shortDesc("maxDSSize", "Number of keys to store in each node's datastore");
        config.argDesc("estimatorClass", "<Number of files>");
        config.shortDesc("doConns", "If false, don't connect to nodes when they reply successfully with data");
        config.argDesc("doConns", "<true|false>");
    }
    
    /** @param params
     * @throws OptionParseException
     */
    public SimConfig(Params params) throws OptionParseException {
        isFullyConnected = params.getBoolean("fullyconnected");
        initialNodes = params.getInt("nodes");

        if(isFullyConnected) {
            maxRTSize = initialNodes-1;
        } else {
            int rt = params.getInt("rt");
            maxRTSize = rt;
        }
        if(maxRTSize < 1)
            throw new OptionParseException("maxRTSize = "+maxRTSize+" - not good!");
        if(maxRTSize > initialNodes)
            throw new OptionParseException("Max RT size ("+maxRTSize+
                    ") > initial nodes("+initialNodes);
        dontDropInexperiencedNodes = ! params.getBoolean("dropInexperienced");
        experienceHits = params.getInt("experienceHits");
        double minRTFractionExperienced = params.getDouble("minRTFracExperienced");
        minExperiencedNodes = (int) (maxRTSize * minRTFractionExperienced);
        double fetchfrac = params.getDouble("fetchFrac");
        maxDSSize = params.getInt("maxDSSize");
        if(maxDSSize < 1) throw new OptionParseException("maxDSSize too small: "+maxDSSize);
        testKeys = (int) (fetchfrac * initialNodes * maxDSSize);
        System.err.println("Test keys: "+testKeys);
        int htl = params.getInt("htl");
        int iHTL = htl;
        int rHTL = htl;
        int i = params.getInt("insertHTL");
        if(i > 0) iHTL = i;
        i = params.getInt("requestHTL");
        if(i > 0) rHTL = i;
        insertHTL = iHTL;
        requestHTL = rHTL;
        String eclassString = params.getString("estimatorClass");
        if(eclassString.equalsIgnoreCase("psuccess")) {
            estimatorClass = CLASS_PROBABILITY_ONLY;
        } else if(eclassString.equalsIgnoreCase("3est")) {
            estimatorClass = CLASS_ALL_THREE;
        } else if(eclassString.equalsIgnoreCase("tsuccess_kludge")) {
            estimatorClass = CLASS_TSUCCESS_FIXED_PENALTY;
        } else {
            throw new OptionParseException("Unknown estimator class: "+eclassString);
        }
        doRequestConnections = params.getBoolean("doConns");
        this.doPCaching = params.getBoolean("doPcaching");
    }

    public static Params paramsFromArgs(String[] args) {
        Params params = new Params(config.getOptions());
        params.readArgs(args);
        System.err.println("Params: "+params.toString());
        return params;
    }

    /**
     * @return
     */
    public String getOptionsString() {
        StringBuffer sb = new StringBuffer();
        sb.append("fullyconnected=").append(isFullyConnected).append('-');
        sb.append("nodes=").append(initialNodes).append('-');
        sb.append("rt=").append(maxRTSize).append('-');
        if(!dontDropInexperiencedNodes)
            sb.append("dropInexperienced=true-");
        sb.append("experienceHits=").append(experienceHits).append('-');
        sb.append("minRTFracExperienced=").append((double)minExperiencedNodes/maxRTSize).append('-');
        sb.append("fetchFrac=").append((double)testKeys/(initialNodes*maxDSSize)).append('-');
        if(requestHTL == insertHTL) {
            sb.append("htl=").append(requestHTL).append("-");
        } else {
            sb.append("requestHTL=").append(requestHTL).append('-').
            	append("insertHTL=").append(insertHTL).append('-');
        }
        sb.append("estimatorClass=").append(estimatorClassName()).append('-');
        sb.append("doConns=").append(doRequestConnections).append('-');
        sb.append("doPcaching=").append(doPCaching).append('-');
        sb.append("maxDSSize=").append(maxDSSize);
        return sb.toString();
    }

    private String estimatorClassName() {
        switch(estimatorClass) {
        	case CLASS_PROBABILITY_ONLY:
        	    return "psuccess";
        	case CLASS_ALL_THREE:
        	    return "3est";
        	case CLASS_TSUCCESS_FIXED_PENALTY:
        	    return "tsuccess_kludge";
        }
        throw new IllegalArgumentException("Unknown estimator class "+estimatorClass);
    }

    /**
     * 
     */
    public static void printUsage() {
        config.printUsage(System.err);
    }
}
