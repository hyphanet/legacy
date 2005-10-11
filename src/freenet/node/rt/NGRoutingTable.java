/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;

import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.PeerHandler;
import freenet.Version;
import freenet.message.StoreData;
import freenet.node.IdRefPair;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.support.Checkpointed;
import freenet.support.LoadSaveCheckpointed;
import freenet.support.DataObjectUnloadedException;
import freenet.support.Logger;
import freenet.support.PropertyArray;
import freenet.support.SimpleStringMap;
import freenet.support.StringMap;
import freenet.support.graph.Bitmap;
import freenet.support.graph.Color;
import freenet.support.graph.DibEncoder;
import freenet.support.graph.GDSList;
import freenet.support.graph.MinGraphDataSet;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.QuickSorter;

/**
 * Routing table based on NodeEstimators
 * 
 * @author amphibian, but edt did lots of prior work, and sanity's concept
 */
public class NGRoutingTable
	extends StoredRoutingTable
	implements NodeSortingRoutingTable {
	
    NGRoutingTableCheckpointed myCheckpointed;
    
    static final String[] globalFileNames = { "ngrt_global_a", "ngrt_global_b" };
    
    private class NGRoutingTableCheckpointed extends LoadSaveCheckpointed {
    	
        public NGRoutingTableCheckpointed(File routingDir) {
        	super(routingDir, globalFileNames);
        }

    	public String getCheckpointName() {
    		return "Writing NGRoutingTable global estimator";
    	}

    	public long nextCheckpoint() {
    		return System.currentTimeMillis() + 20000; // FIXME
    	}

    	public void writeData(DataOutputStream dos) throws IOException {
   			globalSearchTimeEstimator.writeDataTo(dos);
   			globalTransferRateEstimator.writeDataTo(dos);
   			singleHopPDataNotFoundEstimator.writeDataTo(dos);
   			singleHopPTransferFailedGivenTransferEstimator.writeDataTo(dos);
   			singleHopTDataNotFoundEstimator.writeDataTo(dos);
   			singleHopRTransferFailedEstimator.writeDataTo(dos);
    		singleHopTQueryRejected.writeDataTo(dos);
    		singleHopPQueryRejected.writeDataTo(dos);
    		singleHopTEarlyTimeout.writeDataTo(dos);
    		singleHopPEarlyTimeout.writeDataTo(dos);
    		singleHopTSearchFailed.writeDataTo(dos);
    		singleHopPSearchFailed.writeDataTo(dos);
    		singleHopPDataNotFound.writeDataTo(dos);
    		singleHopPTransferFailedGivenTransfer.writeDataTo(dos);
    		fullRequestPTransfer.writeDataTo(dos);
    	}

        /**
         * Called before reading files. Nulls everything.
         */
        protected void preload() {
    		globalSearchTimeEstimator = null;
    		globalTransferRateEstimator = null;
        }

    	/**
         * Read data, throw an IOException if we can't read
         * from this file, in which case it will try the next one.
         * Anything we can't read remains null.
         */
        protected void readFrom(DataInputStream dis) throws IOException {
            globalSearchTimeEstimator = keFactory.createTime(dis, "globalSearchTimeEstimator");
            globalTransferRateEstimator = keFactory.createTransferRate(dis, "globalTransferRateEstimator");
            if (logDEBUG)
                    Core.logger.log(this, "gSTE: " + globalSearchTimeEstimator
                            + ", gTRE: " + globalTransferRateEstimator,
                            Logger.DEBUG);
            singleHopPDataNotFoundEstimator = keFactory.createProbability(
                    dis, "singleHopPDataNotFoundEstimator");
            singleHopPTransferFailedGivenTransferEstimator = keFactory
                    .createProbability(dis, "singleHopPTransferFailedGivenTransferEstimator");
            singleHopTDataNotFoundEstimator = keFactory.createTime(dis, 
                    "singleHopTDataNotFoundEstimator");
            singleHopRTransferFailedEstimator = keFactory.createTransferRate(
                    dis, "singleHopRTransferFailedEstimator");
            singleHopTQueryRejected = rafTimeFactory.create(dis);
            singleHopPQueryRejected = rafProbFactory.create(dis);
            singleHopTEarlyTimeout = rafTimeFactory.create(dis);
            singleHopPEarlyTimeout = rafProbFactory.create(dis);
            singleHopTSearchFailed = rafTimeFactory.create(dis);
            singleHopPSearchFailed = rafProbFactory.create(dis);
            singleHopPDataNotFound = rafProbFactory.create(dis);
            singleHopPTransferFailedGivenTransfer = rafProbFactory.create(dis);
            fullRequestPTransfer = rafProbFactory.create(dis);
        }

        /**
         * Create all the null variables that we couldn't serialize.
         */
        protected void fillInBlanks() {
    		if (globalSearchTimeEstimator == null)
    			globalSearchTimeEstimator = keFactory.createTime(5 * 60 * 1000, "globalSearchTimeEstimator");
    		if (globalTransferRateEstimator == null)
    			globalTransferRateEstimator = neFactory.createGlobalRateEstimator("globalTransferRateEstimator");
    		// FIXME: totally arbitrary defaults!
    		if (singleHopPDataNotFoundEstimator == null) {
    			singleHopPDataNotFoundEstimator =
    				keFactory.createProbability(0.95, "singleHopPDataNotFoundEstimator");
    			singleHopPTransferFailedGivenTransferEstimator =
    				keFactory.createProbability(0.95, "singleHopPTransferFailedGivenTransferEstimator");
    			singleHopTDataNotFoundEstimator =
    				keFactory.createTime(5 * 60 * 1000, "singleHopTDataNotFoundEstimator");
    			// should be sufficiently high
    			singleHopRTransferFailedEstimator =
    				keFactory.createInitTransfer(0.001, "singleHopRTransferFailedEstimator");
    			singleHopPQueryRejected = rafProbFactory.create(0.9);
    			singleHopTQueryRejected = rafTimeFactory.create(Core.hopTime(1, 0));
    			singleHopPEarlyTimeout = rafProbFactory.create(0.9);
    			singleHopTEarlyTimeout = rafTimeFactory.create(Core.hopTime(1, 0));
    			singleHopPSearchFailed = rafProbFactory.create(0.9);
    			singleHopTSearchFailed =
    				rafTimeFactory.create(Core.hopTime(Node.maxHopsToLive, Core.queueTimeout(1024*1024, false, false)) * 2);
    		}
    		if(singleHopPDataNotFound == null)
    		    singleHopPDataNotFound = rafProbFactory.create(0.9);
    		if(singleHopPTransferFailedGivenTransfer == null)
    		    singleHopPTransferFailedGivenTransfer = rafProbFactory.create(0.9);
    		if(fullRequestPTransfer == null) {
    		    fullRequestPTransfer = rafProbFactory.create(0.1);
    		}
    		// FIXME: is assuming createGlobalRateEstimator is pessimistic
        }

        protected int checkpointPeriod() {
            return 60000;
        }

    }
    
	private final NodeEstimatorStore estimators = new NodeEstimatorStore();
	
    private boolean logDEBUG;
	final NodeEstimatorFactory neFactory;
	final KeyspaceEstimatorFactory keFactory;
	final RunningAverageFactory rafProbFactory;
	final RunningAverageFactory rafTimeFactory;
	public final static String NGKEY = "ngr1data";

	public Checkpointed getCheckpointed() {
	    return myCheckpointed;
	}
	
	// This includes restarts etc, unlike searchSuccessTime on
	// StandardNodeEstimator
	KeyspaceEstimator globalSearchTimeEstimator;
	KeyspaceEstimator globalTransferRateEstimator;
	/**
	 * Probability of a DataNotFound given no searchFailed. If not, then we get
	 * a transfer, of one kind or another (or terminate for a non-routing
	 * reason, or run out of routes)
	 */
	KeyspaceEstimator singleHopPDataNotFoundEstimator;
	/**
	 * Overall probability of DataNotFound given no search failed.
	 * Does not depend on key requested.
	 */
	RunningAverage singleHopPDataNotFound;
	/**
	 * Probability of a transfer failure given that a transfer is started
	 */
	KeyspaceEstimator singleHopPTransferFailedGivenTransferEstimator;
	/** Overall probability of transfer failed given a transfer started.
	 * Does not depend on key requested.
	 */
	RunningAverage singleHopPTransferFailedGivenTransfer;
	/** Time taken to receive a DataNotFound */
	KeyspaceEstimator singleHopTDataNotFoundEstimator;
	/** Equivalent transfer rate for a transfer failure */
	KeyspaceEstimator singleHopRTransferFailedEstimator;
	/**
	 * Probability of a QueryRejected. This is kept globally; each node will
	 * use its own time but the global probability. Backoff should handle what
	 * would be handled by pQR per node. pQR per node is VERY hard to get
	 * right, so we are just using backoff and the pGlobalQR*tQR mechanism.
	 */
	RunningAverage singleHopPQueryRejected;
	RunningAverage singleHopTQueryRejected;
	protected RunningAverage singleHopPEarlyTimeout;
	RunningAverage singleHopTEarlyTimeout;
	RunningAverage singleHopPSearchFailed;
	RunningAverage singleHopTSearchFailed;
	RunningAverage fullRequestPTransfer;
	public double fastestTransferSeen = 0;
	public double defaultFastestTransferSeen;
	int earlyTimeoutReportsTotal;
	int earlyTimeoutReportsFailures;
	Object earlyTimeoutSync = new Object();

	private static boolean logDebug = true;
	
	
	/**
	 * @return the expected probability of a transfer given that a request
	 * is sent out.
	 */
	public double pTransferGivenRequest() {
	    return (1.0-this.singleHopPQueryRejected.currentValue()) *
	    	(1.0-this.singleHopPEarlyTimeout.currentValue()) *
	    	(1.0-this.singleHopPSearchFailed.currentValue()) *
	    	(1.0-this.singleHopPDataNotFound.currentValue());
	}

    /**
     * @return the expected probability of a transfer given that a request
     * is started.
     */
    public double pTransferGivenInboundRequest() {
        return fullRequestPTransfer.currentValue();
    }
	
	public void reportEarlyTimeout(boolean timeout, Object logOb) {
		if (Core.logger.shouldLog(Logger.MINOR, this))
			Core.logger.log(
				this,
				"Early timeout: "
					+ timeout
					+ " on "
					+ logOb
					+ ": result: "
					+ singleHopPEarlyTimeout,
				Logger.MINOR);
		synchronized (earlyTimeoutSync) {
			singleHopPEarlyTimeout.report(timeout ? 1.0 : 0.0);
			earlyTimeoutReportsTotal++;
			if (timeout)
				earlyTimeoutReportsFailures++;
		}
	}

	NodeStats newNodeStats = null;
	NodeStats newNodeStatsLegit;
	NodeStats newNodeStatsSemiLegit;
	NodeStats defaultNodeStats;
	// FIXME: should these be ints rather than longs? What about with alt
	// transports?
	/**
	 * Lowest max estimated search time of node estimators with nontrivial
	 * successes. defaultLowestSearchTime if there aren't any. We use the max
	 * rather than the minimum because that is probably more accurate.
	 */
	long lowestEstimatedSearchTime = 0;
	final long defaultLowestSearchTime = 1000;
	// 1 second - FIXME should be configurable
	/**
	 * Lowest max estimated DNF time of node estimators with nontrivial
	 * successes. defaultLowestEstimatedDNFTime if there aren't any.
	 */
	long lowestEstimatedDNFTime = 0;
	final long defaultLowestEstimatedDNFTime = 1000;
	// 1 second - FIXME should be configurable
	final int initialRefsCount;
	//Which file should we use next. Index into the array above.
	public NGRoutingTable(
		RoutingStore routingStore,
		int maxNodes,
		NodeEstimatorFactory factory,
		KeyspaceEstimatorFactory kef,
		RunningAverageFactory rafProbability,
		RunningAverageFactory rafTime,
		double defaultFastestTransferSeen,
		File routingDir) {
		super(routingStore, maxNodes);
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		myCheckpointed = new NGRoutingTableCheckpointed(routingDir);
		this.neFactory = factory;
		this.keFactory = kef;
		this.rafProbFactory = rafProbability;
		this.rafTimeFactory = rafTime;
		factory.setNGRT(this);
		this.defaultFastestTransferSeen = defaultFastestTransferSeen;
		newNodeStatsLegit = factory.createStats();
		newNodeStatsSemiLegit = factory.createStats();
		defaultNodeStats = factory.defaultStats();
		myCheckpointed.load();
		initialRefsCount = loadEstimators();
		
		updateNewNodeStats();
	}
	
    public int loadEstimators() {
		Enumeration memories = routingStore.elements();
		LinkedList listToRemove = null;
		while (memories.hasMoreElements()) {
			RoutingMemory mem = (RoutingMemory) memories.nextElement();
			NodeEstimator ne;
			try {
				ne = (NodeEstimator) mem.getProperty(NGKEY);
			} catch (DataObjectUnloadedException e) {
				try {
					NodeReference ref = mem.getNodeReference();
					Identity id = mem.getIdentity();
					if(ref != null) {
					    if (!Version.checkGoodVersion(ref.getVersion())) {
					        Core.logger.log(
					                this,
					                "Rejecting reference "
					                + ref
					                + " - too old ("
					                + ref.getVersion()
					                + ") in loadEstimators",
					                Logger.NORMAL);
					        if (listToRemove == null)
					            listToRemove = new LinkedList();
					        listToRemove.add(id);
					        continue;
					    }
					}
					if(logDebug)
					    Core.logger.log(this, "Loading "+id, Logger.DEBUG);
					ne = neFactory.create(mem, id, ref, e, false);
				} catch (IOException ex) {
					Core.logger.log(
						this,
						"Caught "
							+ ex
							+ " deserializing a NodeEstimator for "
							+ mem,
						ex,
						Logger.ERROR);
					if (listToRemove == null)
						listToRemove = new LinkedList();
					listToRemove.add(mem.getIdentity());
					ne = null;
				}
			}
			if (ne == null) {
				ne =
					neFactory.create(
						mem,
						mem.getIdentity(),
						mem.getNodeReference(),
						null,
						false,
						defaultNodeStats);
			}
			mem.setProperty(NGKEY, ne);
			estimators.put(ne);
		}
		if (listToRemove != null)
			for (Iterator i = listToRemove.iterator(); i.hasNext();) {
				routingStore.remove((Identity) i.next());
			}
		return estimators.size();
	}

	public void reportRate(double rate) {
		if (rate > fastestTransferSeen) {
			fastestTransferSeen = rate;
			if (Core.logger.shouldLog(Logger.NORMAL, this))
				Core.logger.log(
					this,
					"New fastest rate seen: " + rate + " bytes per second",
					Logger.NORMAL);
		}
	}

	/** Return the fastest transfer rate seen for files over 16k */
	public double getFastestRateSeen() {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (fastestTransferSeen == 0.0) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Returning default rate " + defaultFastestTransferSeen,
					Logger.DEBUG);
			return defaultFastestTransferSeen;
		}
		if (logDEBUG)
			Core.logger.log(
				this,
				"Returning fastest rate seen: " + fastestTransferSeen,
				Logger.DEBUG);
		return fastestTransferSeen;
	}

	public boolean wantUnkeyedReference(NodeReference ref) {
		Identity id = ref.getIdentity();
		if (id.equals(node.identity)) return false;
		if (ref.noPhysical())
		    return false;
		if (!Version.checkGoodVersion(ref.getVersion()))
		    return false;
		if (estimators.get(id) != null)
		    return false;
		// Totally abitrary: 15 seconds allowed for opening connections.
		// FIXME!
		// If less than 40 connected nodes, and we've had time to setup, we're
		// in trouble
		int contacted = countConnectedNodes(); 
		boolean notEnoughConnections =
		    ((System.currentTimeMillis() - Node.startupTimeMs + 500 > 15000) &&
		            (contacted < (maxNodes * 3 / 5)));
		// If can only talk to 10% of RT... we're probably in trouble
		boolean notEnoughAvailableNodes =
		    (countUnbackedOffNodes() < (contacted/10));
		return notEnoughConnections || notEnoughAvailableNodes;
	}

	public void updateReference(NodeReference nr) {
		Identity i = nr.getIdentity();
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(this, "updateReference(" + nr + ")", Logger.DEBUG);
		RoutingMemory mem;
		NodeEstimator e;
		synchronized (this) {
			mem = routingStore.getNode(i);
			e = estimators.get(i);
		}
		if (mem != null) {
			((DataObjectRoutingMemory) mem).updateReference(nr);
			if (e == null)
				Core.logger.log(
					this,
					"Mem exists but e doesn't! on updateReference("
						+ nr
						+ ": mem="
						+ mem,
					new Exception("debug"),
					Logger.ERROR);
			else
				e.updateReference(nr);
		} else {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Still referencing "
						+ nr
						+ ": estimators.size()="
						+ estimators.size()
						+ " of "
						+ maxNodes,
					Logger.DEBUG);
		}
	}
	
	public void reference(Key k, NodeReference nr, 
	        FieldSet estimator) {
			reference(k,nr.getIdentity(),nr,estimator);
	}

	public void reference(Key k, Identity i, NodeReference nr, 
	        FieldSet estimator) {
		if (i == null) i = nr.getIdentity();
		if (i.equals(Main.getIdentity())) {
			return;
		}
		synchronized (this) {
			// Not just in FilterRT: serialization and updateRef - FIXME
			RoutingMemory mem = routingStore.getNode(i);
			Core.logger.log(
				this,
				"reference(" + k + "," + i + "," + nr + ")",
				Logger.DEBUG);
			if (mem == null) {
				// New node, estimator centered on k
				mem = routingStore.putNode(i, nr);
				NodeEstimator e;
				updateNewNodeStats();
				if (k != null)
					e =
						neFactory.create(
							mem,
							i,
							mem.getNodeReference(),
							estimator,
							k,
							false,
							newNodeStats);
				else
					e =
						neFactory.create(
							mem,
							i,
							mem.getNodeReference(),
							estimator,
							false,
							newNodeStats);
				mem.setProperty(NGKEY, e);
				estimators.put(e);
				Core.diagnostics.occurrenceCounting("rtAddedNodes", 1);
			} else if (nr != null && nr.supersedes(mem.getNodeReference())) {
				((DataObjectRoutingMemory) mem).noderef = nr;
				NodeEstimator e = (estimators.get(i));
				if (e == null)
					Core.logger.log(
						this,
						"Got " + mem + " but not estimator!",
						new Exception("debug"),
						Logger.NORMAL);
				else
					e.setReference(nr);
			}

			// enforce max nodes limit
			if (countNodes() > maxNodes) {
			    int sz = estimators.size();
			    synchronized (discardSync) {
					if (sz > maxNodes) discard(sz / 20);
				}
			}
		}
	}

	public synchronized void remove(RoutingMemory mem, Identity id) {
		NodeEstimator ne;
		try {
			ne = (NodeEstimator) mem.getProperty(NGKEY);
		} catch (DataObjectUnloadedException e) {
			ne = null;
		}
		if(id == null && ne != null)
		    id = ne.id;
		if (id != null) {
		    estimators.remove(id);
		    routingStore.remove(id);
		}
	}

	public boolean references(Identity id) {
		return estimators.get(id) != null;
	}

	private long lastUpdatedNewNodeStats = -1;
	private final Object lastUpdatedNewNodeStatsLock = new Object();
	private boolean isUpdatingNewNodeStats = false;

    private final RecentRequestHistory history = new RecentRequestHistory(20, this);

	//Recalculates the different DNF probabilities
	private void updateNewNodeStats() {
		long now = System.currentTimeMillis();
		//Avoid contention for the lastUpdatedNewNodeStatsLock lock if possible
		if (isUpdatingNewNodeStats || now - lastUpdatedNewNodeStats < 1000)
			return;
		//Then test again, holding the proper lock, just to be _sure_ that we should run
		synchronized (lastUpdatedNewNodeStatsLock) {
			if (isUpdatingNewNodeStats || now - lastUpdatedNewNodeStats < 1000)
				return;
			isUpdatingNewNodeStats = true;
		}
		try {
			// FIXME!
			int nonWild = 0;
			int semiWild = 0;
			synchronized (this) {
				newNodeStatsLegit.reset();
				newNodeStatsSemiLegit.reset();
				NodeEstimator[] ea = estimators.toArray();
				for (int i = 0; i < ea.length; i++) {
					NodeEstimator ne = ea[i];
					if (!ne.isAvailable(false))
						continue;
					Identity id = ne.id;
					if (!isConnected(id))
						continue;
					if (ne.successes() >= 1) {
						semiWild++;
						newNodeStatsSemiLegit.register(ne);
					}
					if (ne.successes() > 5) {
						nonWild++;
						newNodeStatsLegit.register(ne);
					}
					// FIXME!
				}
				if (nonWild < maxNodes / 4) {
					if (semiWild > 0) {
						newNodeStats = newNodeStatsSemiLegit;
					} else
						newNodeStats = defaultNodeStats;
				} else
					newNodeStats = newNodeStatsLegit;
				if (Core.logger.shouldLog(Logger.DEBUG, this))
					Core.logger.log(this, "newNodeStats now: " + newNodeStats + "(nonWild=" + nonWild + ", semiWild="
							+ semiWild + ")", Logger.DEBUG);
				newNodeStats.reportStatsToDiagnostics();
			}
		} finally {
			lastUpdatedNewNodeStats = now;
			isUpdatingNewNodeStats = false;
		}
	}

	public Routing route(
		Key k,
		int htl,
		long size,
		boolean isInsert,
		boolean isAnnouncement,
		boolean orderByInexperience,
		boolean wasLocal, 
		boolean willSendRequests) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"Routing "
					+ k
					+ ", hopsToLive="
					+ htl
					+ ", size="
					+ size
					+ ", isInsert="
					+ isInsert
					+ ", orderByInexperience="
					+ orderByInexperience,
				new Exception("debug"),
				Logger.DEBUG);
		updateNewNodeStats();
		NodeEstimator[] ea = estimators.toArray();
		double pDataNonexistant = pDataNonexistant(k, ea);
		boolean willRecord = willSendRequests && (!isInsert) && (!isAnnouncement) &&
			(!orderByInexperience);
		Estimate[] estimates = new Estimate[ea.length];
		if(willRecord)
		    history.addTentative(k, htl, size, pDataNonexistant);
		int x = 0;
		try {
		//double pQueryRejected = singleHopPQueryRejected.currentValue();
		//int i = 0;
		for(int i=0;i<ea.length;i++){
		    NodeEstimator ne = ea[i];
		    // Must compute estimate even for the unconnected nodes
		    // because then the typical estimate will be up to date
		    // and the sort order will be reasonable.
//		    if(!Main.node.connections.isOpen(ne.id))
//		        continue;
			Estimate es;
			if (orderByInexperience) {
				es = new Estimate(ne, ne.routeAccesses(), ne.routeAccesses(), -1, -1);
			} else {
				es = ne.longEstimate(k, htl, size, 
				        calculateStandardFileSize(), pDataNonexistant,
				        !willRecord, history);
				if (es == null)
					throw new NullPointerException();
			}
			if (es == null)
				throw new NullPointerException();
			if (logDEBUG)
				Core.logger.log(
					this,
					"estimates[" + i + "] = " + es + " (" + k + ")",
					Logger.DEBUG);
			estimates[x] = es;
			x++;
		}
		} finally {
		if(willRecord)
		    history.confirm();
		}
		ArraySorter vs = new ArraySorter(estimates, 0, x);
		QuickSorter.quickSort(vs);
		if (logDEBUG) {
			for (int j = 0; j < x; j++)
				Core.logger.log(
					this,
					"Estimates[" + j + "] = " + estimates[j] + " (" + k + ")",
					Logger.DEBUG);
		}
		return new NGRouting(
			this,
			estimates,
			// Maximum number of steps, excluding disconnected nodes
			Math.min(x, freenet.node.Node.maxRoutingSteps),
			// Maximum number of estimates to read, including disconnected nodes
			x,
			k,
			node,
			isInsert,
			isAnnouncement,
			wasLocal,
			willSendRequests);
	}

    /**
	 * @param k
	 * @param v
	 * @return
	 */
	private double pDataNonexistant(Key k, NodeEstimator[] v) {
		double pDataNonexistant = 1.0;
		for(int i = 0;i<v.length;i++)
		{
			double pDNF = v[i].dnfProbability(k);
			if (pDNF < pDataNonexistant)
				pDataNonexistant = pDNF;
		}
		return pDataNonexistant;
	}

	public double pDataNonexistant(Key k) {
		return pDataNonexistant(k, estimators.toArray());
	}

	//Takes a shot on calculating a mean network file size falls back to 128k
	// if insufficent data is available or this NGRT is unable to ask the store
	// (== if this NGRT doesn't know what node it is used in)
	private long calculateStandardFileSize() {
		return Node.calculateStandardFileSize(node);
	}

	public RTDiagSnapshot getSnapshot(boolean starting) {
        Core.logger.log(this, "getSnapshot("+starting+")",
                Logger.MINOR);
		PropertyArray pa =
			new PropertyArray(
				StandardNodeEstimator.REF_PROPERTIES,
				new StandardNodeEstimator.REF_COMPARATOR());
		HashSet nodeRefs = new HashSet();
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		int connectedNodes = 0;
		int referencedNodes = 0;
		int newbies = 0;
		int connectedNewbies = 0;
		int uncontactableNodes = 0;
		int successNodes = 0;
		int triedNodes = 0;
		long totalConnectAttempts = 0;
		long totalConnectSuccesses = 0;
		int backedOffNodes = 0;
		long stdFileSize = calculateStandardFileSize();
		Key k = new Key(Key.HALFKEYSPACE_SIZE);
		NodeEstimator[] ea = estimators.toArray(); 
		double pDataNonexistant = pDataNonexistant(k, ea);
		//		double pQueryRejected = singleHopPQueryRejected.currentValue();
		updateNewNodeStats();
		for(int i=0;i<ea.length;i++){
			NodeEstimator ne = ea[i];
			Identity id = ne.id;
			if(!starting) {
				PeerHandler ph = node.connections.getPeerHandler(id);
				if(ph == null) {
					Core.logger.log(this, "Node id: "+id+" has no peerhandler!",
							new Exception("debug"), Logger.NORMAL);
					uncontactableNodes++;
				} else {
					if(ph.notContactable()) uncontactableNodes++;
				}
			}
			NodeReference ref = ne.getReference();
			if(ref != null) referencedNodes++;
			IdRefPair pair = new IdRefPair(id, ref);
			if (!nodeRefs.contains(pair)) {
				nodeRefs.add(pair);
			}
			long lastConnectTryTime = ne.lastConnectTryTime();
			boolean isConnected = countInboundConnections(id) > 0
				|| countOutboundConnections(id) > 0; 
			if (isConnected) {
				connectedNodes++;
				triedNodes++;
				if (!ne.isAvailable(false))
				    backedOffNodes++;
				// Successes only counts connected nodes
				if (ne.successes() > 0)
					successNodes++;
			} else if (lastConnectTryTime > 0)
			    triedNodes++;
			boolean newbie = isNewbie(ne, isConnected);
			if(newbie) newbies++;
			if(newbie && isConnected) connectedNewbies++;
			int connectTries = ne.connectTries();
			int connectSuccesses = ne.connectSuccesses();
			totalConnectAttempts += connectTries;
			totalConnectSuccesses += connectSuccesses;
			ne.getDiagProperties(pa, history, stdFileSize);
		}

		IdRefPair[] realRefs = new IdRefPair[nodeRefs.size()];
		realRefs = (IdRefPair[]) (nodeRefs.toArray(realRefs));
		Object[] props = new Object[TABLE_PROPERTIES.length];
		// Make diffs more readable!
		int i=0;
		props[i++] = new Integer(ea.length);
		props[i++] = new Integer(referencedNodes);
		props[i++] = new Integer(newbies);
		props[i++] = new Integer(uncontactableNodes);
		props[i++] = new Integer(triedNodes);
		props[i++] = new Integer(connectedNodes);
		props[i++] = new Integer(connectedNewbies);
		props[i++] = new Integer(successNodes);
		props[i++] = new Integer(backedOffNodes);
		props[i++] = new Long(totalConnectAttempts);
		props[i++] = new Long(totalConnectSuccesses);
		props[i++] = Long.toString(lowestEstimatedSearchTime) + "ms";
		props[i++] = Long.toString(lowestEstimatedDNFTime) + "ms";

		props[i++] =
			new String(
				globalSearchTimeEstimator.lowestString());
		props[i++] =
			new String(
				globalSearchTimeEstimator.highestString());
		props[i++] =
			new String(
				globalTransferRateEstimator
					.lowestString());
		props[i++] =
			new String(
				globalTransferRateEstimator
					.highestString());
		props[i++] =
			new String(
				singleHopPDataNotFoundEstimator
					.lowestString());
		props[i++] =
			new String(
				singleHopPDataNotFoundEstimator
					.highestString());
		props[i++] =
			new String(
				singleHopPTransferFailedGivenTransferEstimator
					.lowestString());
		props[i++] =
			new String(
				singleHopPTransferFailedGivenTransferEstimator
					.highestString());
		props[i++] = new Double(singleHopPQueryRejected.currentValue());
		props[i++] = new Double(singleHopTQueryRejected.currentValue());
		props[i++] = new Double(singleHopPEarlyTimeout.currentValue());
		props[i++] = new Double(singleHopTEarlyTimeout.currentValue());
		props[i++] = new Double(singleHopPSearchFailed.currentValue());
		props[i++] = new Double(singleHopTSearchFailed.currentValue());
		props[i++] = new Double(singleHopPDataNotFound.currentValue());
		props[i++] = new Double(singleHopPTransferFailedGivenTransfer.currentValue());
		props[i++] = new Double(fullRequestPTransfer.currentValue());
		props[i++] = new Integer(earlyTimeoutReportsTotal);
		props[i++] = new Integer(earlyTimeoutReportsFailures);
		props[i++] = getClass().getName();
		StringMap sm = new SimpleStringMap(TABLE_PROPERTIES, props);
		// FIXME!
		return new SimpleRTDiagSnapshot(sm, pa, null, realRefs,
		        history.snapshot());
	}

	private final static String[] TABLE_PROPERTIES =
		{
			"Number of known routing nodes",
			"Number of node references",
			"Number of newbie nodes",
			"Number of uncontactable nodes",
			"Contacted and attempted to contact node references",
			"Contacted node references",
			"Contacted newbie node references",
			"Connections with Successful Transfers",
			"Backed off nodes",
			"Connection Attempts",
			"Successful Connections",
			"Lowest max estimated search time",
			"Lowest max estimated DNF time",
			"Lowest global search time estimate",
			"Highest global search time estimate",
			"Lowest global transfer rate estimate",
			"Highest global transfer rate estimate",
			"Lowest one hop probability of DNF",
			"Highest one hop probability of DNF",
			"Lowest one hop probability of transfer failure",
			"Highest one hop probability of transfer failure",
			"Single hop probability of QueryRejected",
			"Single hop average time for QueryRejected",
			"Single hop probability of early timeout",
			"Single hop average time for early timeout",
			"Single hop probability of search timeout",
			"Single hop average time for search timeout",
			"Single hop overall probability of DNF given no timeout",
			"Single hop overall probability of transfer failure given transfer",
			"Probability of transfer given incoming request",
			"Total number of requests that didn't QR",
			"Total number of reqests that timed out before a QR or Accepted",
			"Implementation" };

	public long initialRefsCount() {
		return initialRefsCount;
	}

	public int getKeyCount() {
		return -1; // not applicable
	}

	public void toHtml(Identity i, PrintWriter pw, String imagePrefix)
		throws IOException {
		NodeEstimator e = estimators.get(i);
		if (e != null)
			e.getHTMLReportingTool().toHtml(pw, imagePrefix);
	}

	public KeyspaceEstimator getEstimator(Identity id, String type) {
		NodeEstimator e = estimators.get(id);
		if (e != null)
			return e.getHTMLReportingTool().getEstimator(type);
		else
			return null;
	}

	public NodeEstimator getEstimator(Identity id) {
		return estimators.get(id);
	}

	public FieldSet estimatorToFieldSet(Identity id) {
		NodeEstimator e = estimators.get(id);
		if (e == null)
			return null;
		else
			return e.toFieldSet();
	}

	public void reportConnectionSuccess(Identity id, long time) {
		NodeEstimator e = estimators.get(id);
		if (e == null)
			return;
		e.routeConnected(time);
	}

	public void reportConnectionFailure(Identity id, long time) {
		NodeEstimator e = estimators.get(id);
		if (e == null)
			return;
		e.connectFailed(time);
	}
	
	/**
	 * Class to sort by {whether node is newbie, # consecutive failed connections (0 if connected),
	 * decider value (typical estimate * current MRI).
	 * @author amphibian
	 */
	public static class NGRDiscardValue implements Comparable {

	    boolean oldNode;
	    boolean newbie;
	    int consecutiveFailedConnections;
	    double decider;
	    
        public NGRDiscardValue(boolean veryOldNode, boolean exemptNewbieNode, int x, double decider) {
            this.oldNode = veryOldNode;
            this.newbie = exemptNewbieNode;
            this.consecutiveFailedConnections = x;
            this.decider = decider;
        }

        public String toString() {
            return super.toString()+": oldNode="+oldNode+
            	": newbie="+newbie+", failedConns="+
            	consecutiveFailedConnections+", decider="+decider;
        }
        
        public int compareTo(Object o) {
            NGRDiscardValue v = (NGRDiscardValue)o;
            /** REDFLAG: this is needed because not all old nodes
             * support the GoAwayPacketMessage. Once they do, we
             * can get rid of it.
             */
            if(v.oldNode && !oldNode) return -1;
            if(oldNode && !v.oldNode) return 1;
                
            /* FIRST, consecutive failed conns
             * Comes first because if it didn't, new unconnectable nodes would 
             * stay in the RT forever
             */
            if(consecutiveFailedConnections > v.consecutiveFailedConnections)
                return 1;
            if(consecutiveFailedConnections < v.consecutiveFailedConnections)
                return -1;
            /* Now, whether the node is newbie?
             * More important than the general routing effectiveness guesstimate
             * because we don't know how effective a node is until
             */
            if(v.newbie && !newbie)
                return 1;
            if(newbie && !v.newbie)
                return -1;
            if(decider > v.decider)
                return 1;
            if(decider < v.decider)
                return -1;
            return 0;
        }

        public boolean equals(Object o) {
            if(o instanceof NGRDiscardValue) {
                NGRDiscardValue v = (NGRDiscardValue)o;
                return v.newbie == newbie &&
                	v.consecutiveFailedConnections == consecutiveFailedConnections &&
                	v.decider == decider;
            } else
                return false;
        }
	}
	
	protected final Comparable getDiscardValue(RoutingMemory mem, DiscardContext ctx) {
		NodeEstimator ne;
		NGRDiscardValue ret;
		Identity i = mem.getIdentity();
		ne = estimators.get(i);
		
		if (ne != null) {
		    boolean veryOldNode = ne.isVeryOld();
		    
		    // If we have a connection, 0.
		    // If we don't, 1 + # consecutive failed connections.
		    // Therefore open connections take precedence over untried conns.
		    // This disadvantages newbie nodes, but they are treated specially.
		    int x = 0;
			if (!isConnected(i)) {
			    x = ne.consecutiveFailedConnects() + 1;
				if(mem.getNodeReference() == null ||
						mem.getNodeReference().notContactable())
					// One failed attempt per 5 seconds age
					x = (int)Math.min(Integer.MAX_VALUE, ne.age() / 5000);
			}
			/**
			 * Somewhat arbitrary: node is newbie and will not be dropped if
			 * a) We have not yet connected, and we've been trying for less 
			 * than 15 minutes OR
			 * b) The node has less than 20 routing hits.
			 */
			boolean exemptNewbieNode =
			    isNewbie(ne, x == 0);
			/**
			 * Now the high resolution measurement
			 * Typical estimate * current minRequestInterval
			 */
			double typicalEstimate = ne.typicalEstimate(history,
			        calculateStandardFileSize());
			double minRequestInterval = ne.minRequestInterval();
			double decider = typicalEstimate * minRequestInterval;
			ret =
			    new NGRDiscardValue(veryOldNode, exemptNewbieNode, x, decider);
			if(Core.logger.shouldLog(Logger.DEBUG, this))
			    Core.logger.log(this, "minRequestInterval = "+minRequestInterval +
			            ", typicalEstimate = "+typicalEstimate+" for "+mem,
			            Logger.DEBUG);
		} else
		    ret = null;
		if(Core.logger.shouldLog(Logger.DEBUG,this)) 
		    Core.logger.log(this, "Returning " + ret+" for "+mem, Logger.DEBUG);
		return ret;
	}
	
	/**
	 * Is a node currently "newbie"?
     * @param ne the NodeEstimator for the node
     * @param isConnected is the node currently connected?
     * @return
     */
    private boolean isNewbie(NodeEstimator ne, boolean isConnected) {
        return (((!isConnected) && ne.age() < 15*60*1000) ||
                (isConnected && 
                        (ne.totalHits() < 100 || ne.age() < 5*60*1000)));
    }

    public KeyspaceEstimator getGlobalSearchTimeEstimator() {
		return globalSearchTimeEstimator;
	}

	public KeyspaceEstimator getGlobalTransferRateEstimator() {
		return globalTransferRateEstimator;
	}

	public void drawGraphBMP(
		int width,
		int height,
		HttpServletResponse resp,
		Identity highLightIdentity)
		throws IOException {

		Bitmap bmp = new Bitmap(width, height);

		GDSList gdsl = new GDSList();

		//Pregenerate these values to save us some later calculations
		int htl = 10;
		int size = 10000;
		Key half = new Key(Key.HALFKEYSPACE_SIZE);
		double pDataNonexistant = pDataNonexistant(half);

		NodeEstimator[] ea = estimators.toArray();
		for(int i = 0;i<ea.length;i++){
	        NodeEstimator est = ea[i];
			gdsl.add(
				est.createGDS(width, htl, size, pDataNonexistant),
				new Color(200, 200, 200));
		}

		if (highLightIdentity != null) {
			NodeEstimator est =
				estimators.get(highLightIdentity);
			gdsl.add(
				est.createGDS(width, htl, size, pDataNonexistant),
				new Color(0, 0, 0));
		}

		gdsl.drawGraphsOnImage(bmp);
		DibEncoder.drawBitmap(bmp, resp);
	}

	public MinGraphDataSet createMGDS(int samples, int depth) {
		MinGraphDataSet mgds = new MinGraphDataSet(depth);

		//Pregenerate these values to save us some later calculations
		int htl = 10;
		int size = 10000;
		Key half = new Key(Key.HALFKEYSPACE_SIZE);
		double pDataNonexistant = pDataNonexistant(half);

		
		int count = 0;
		NodeEstimator[] ea = estimators.toArray();
		for(int i = 0;i<ea.length;i++){
	        NodeEstimator est = ea[i];
			count++;
			NodeReference ref = est.getReference();
			String id = ref == null ? "" : 
				ref.firstPhysicalToString() +
				"(" + ref.getIdentity().fingerprintToString() + ")";
			mgds.merge(
				est.createGDS(samples, htl, size, pDataNonexistant),
				id);
		}
		return mgds;
	}

	public void drawRoutingBMP(
		MinGraphDataSet mgds,
		int width,
		int height,
		Color[] colors,
		HttpServletResponse resp)
		throws IOException {

		Bitmap bmp = new Bitmap(width, height);

		Hashtable sourceColor = new Hashtable();
		// String source -> Color color

		Object[] sources = mgds.getSources();
		for (int i = 0; i < sources.length && i < colors.length; i++) {
			sourceColor.put(sources[i], colors[i]);
		}

		mgds.drawGraphOnImage(bmp, sourceColor);
		DibEncoder.drawBitmap(bmp, resp);
	}

	public boolean shouldReference(NodeReference nr, StoreData sd) {
		if (nr == null) {
			Core.logger.log(
				this,
				"shouldReference returning false because " + "null ref",
				Logger.DEBUG);
			return false;
		}
		int x = countUnbackedOffNodes();
		Core.logger.log(
			this,
			Integer.toString(x) + " elements in RoutingTable",
			Logger.DEBUG);
		if (x < (maxNodes * (double) Node.minRTNodesPRef)) {
			Core.logger.log(
				this,
				"shouldReference because RT less than required size",
				Logger.DEBUG);
			return true;
		} else {
			if (sd == null) {
				Core.logger.log(
					this,
					"shouldReference because null StoreData",
					Logger.DEBUG);
				return true;
			} else {
				if (sd
					.shouldCache(Core.getRandSource(), Node.cacheProbPerHop)) {
					Core.logger.log(
						this,
						"shouldReference returning true because "
							+ "sd.shouldCache says so for "
							+ sd,
						Logger.DEBUG);
					return true;
				} else {
					Core.logger.log(
						this,
						"shouldReference returning false because "
							+ "sd.shouldCache says so for "
							+ sd,
						Logger.DEBUG);
					return false;
				}
			}
		}
	}

	/**
	 * @return the number of nodes in the routing table, excluding those
	 *         currently backed off
	 */
	public int countUnbackedOffNodes() {
		int count =0;
		NodeEstimator[] ea = estimators.toArray();
		for(int i = 0;i<ea.length;i++){
	        NodeEstimator ne = ea[i];
			if (ne.isAvailable(false) && isConnected(ne.id))
				count++;
		}
		return count;
	}

	/**
	 * @return the number of nodes in the routing table, excluding those
	 *         currently not connected
	 */
	public int countConnectedNodes() {
		int count =0;
		NodeEstimator[] ea = estimators.toArray();
		for(int i = 0;i<ea.length;i++){
	        NodeEstimator ne = ea[i];
	        if(isConnected(ne.id))
	            count++;
	    }
	    return count;
	}

	public KeyspaceEstimator getSingleHopDataNotFoundEstimator() {
		return singleHopPDataNotFoundEstimator;
	}

	public KeyspaceEstimator getSingleHopTransferFailedEstimator() {
		return singleHopPTransferFailedGivenTransferEstimator;
	}

	public void updateMinRequestInterval(Identity id, double d) {
		NodeEstimator e = estimators.get(id);
		if (e == null)
			return;
		e.updateMinRequestInterval(d);
	}

	private class SimpleNGRDiscardContext implements DiscardContext{
		final int htl;
		final long fileSize;
		final boolean ignoreNewbie;
		SimpleNGRDiscardContext(int htl,long fileSize, boolean ignoreNewbie){
			this.htl = htl;
			this.fileSize = fileSize;
			this.ignoreNewbie = ignoreNewbie;
		}
	}

	protected DiscardContext getTypicalDiscardSortContext() {
	    return this.getTypicalDiscardSortContext(false);
	}
	
    protected DiscardContext getTypicalDiscardSortContext(boolean ignoreNewbies) {
        return new SimpleNGRDiscardContext(Node.maxHopsToLive,Node.calculateStandardFileSize(node), ignoreNewbies);
    }

    //Support class for pairing an NGRDiscardValue and an PeerHandler.
    //Compares based on the wrapped discardValue
    private static class NGRDiscardValueAndPHPair implements Comparable{
    	final NGRDiscardValue d;
    	final PeerHandler attachment;
    	NGRDiscardValueAndPHPair(NGRDiscardValue d,PeerHandler attachment){
    		this.d = d;
    		this.attachment = attachment;
    	}
    	public String toString() {
    	    return d.toString() + " - " + attachment;
    	}
    	public int compareTo(Object o){
    		return d.compareTo(((NGRDiscardValueAndPHPair)o).d);
    	}
    }

    public void order(PeerHandler[] ph, boolean ignoreNewbies) {
        /**
         * Make an NGRDiscardValue for each one.
         * Sort the NGRDiscardValue's.
         * Reorder the NGRDiscardValue's.
         * Write the attachments into the array. 
         */
        NGRDiscardValueAndPHPair[] ng =
            new NGRDiscardValueAndPHPair[ph.length];
        DiscardContext ctx = getTypicalDiscardSortContext(ignoreNewbies);    
        for(int i=0; i<ph.length; i++) {
            if(ph[i] == null)
                ng[i] = null;
            else {
                Identity id = ph[i].getIdentity();
                NodeEstimator e = 
                    estimators.get(id);
                if(e == null)
                    ng[i] = null;
                else
                    ng[i] = new NGRDiscardValueAndPHPair((NGRDiscardValue) this.getDiscardValue(e.mem,ctx),ph[i]);
            }
        }
        
        //Eliminate sparseness in the 'ng' array
        //(Note that the last ng[j]...ng[ng.length] elements will afterwards all
        //contain null, use only ng[0]...ng[j-1] for whatever you need the array to)
        int j=0;
        for(int i=0;i<ng.length;i++) {
            NGRDiscardValueAndPHPair p = ng[i];
            if(p == null) {
                continue;
            }
            if(i != j) //No need to swap them in that case :)
            	ng[j] = ng[i];
            j++;
        }
        
        //Null out ng[j]...ng[ng.length] to prevent possible later mistakes
        for(int i=j;i<ng.length;i++) ng[i] = null;
        
        // Now sort them, only sort ng[0]...ng[j],
        //see comment above
        java.util.Arrays.sort(ng, 0, j);

        // Now write them back
        for(int i=0;i<ph.length;i++) {
            if(ng[i] == null) //Last part of the array might contain null's
                ph[i] = null;
            else {
                if(Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(this, "Rank "+i+": "+ng[i]+" - "+
                            ng[i].attachment.getIdentity(), Logger.DEBUG);
                ph[i] = ng[i].attachment;
            }
        }
    }

    //Support class for pairing an NGRDiscardValue and an Identity.
    //Compares based on the wrapped discardValue
    private static class NGRDiscardValueAndIDPair implements Comparable{
    	final NGRDiscardValue d;
    	final Identity attachment;
    	NGRDiscardValueAndIDPair(NGRDiscardValue d,Identity attachment){
    		this.d = d;
    		this.attachment = attachment;
    	}
    	public String toString() {
    	    return d.toString() + " - " + attachment;
    	}
    	public int compareTo(Object o){
    		return d.compareTo(((NGRDiscardValueAndIDPair)o).d);
    	}
    }
    
    public void order(Identity[] id) {
        /**
         * Make an NGRDiscardValue for each one.
         * Sort the NGRDiscardValue's.
         * Reorder the NGRDiscardValue's.
         * Write the attachments into the array. 
         */
        NGRDiscardValueAndIDPair[] ng =
            new NGRDiscardValueAndIDPair[id.length];
		DiscardContext ctx = getTypicalDiscardSortContext();
        for(int i=0; i<id.length; i++) {
            if(id[i] == null)
                ng[i] = null;
            else {
                NodeEstimator e = 
                    estimators.get(id[i]);
                if(e == null) 
                    ng[i] = null;
                else
                    ng[i] = new NGRDiscardValueAndIDPair((NGRDiscardValue) this.getDiscardValue(e.mem, ctx), id[i]);
            }
        }
        int j=0;
        for(int i=0;i<ng.length;i++) {
            NGRDiscardValueAndIDPair p = ng[i];
            if(p == null) {
                continue;
            }
            ng[i] = ng[j];
            j++;
        }
        // Now sort them
        java.util.Arrays.sort(ng, 0, j);
        // Now write them back
        int x = 0;
        for(int i=0;i<j;i++) {
            if(ng[i] == null)
                id[i] = null;
            else {
                if(Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(this, "Rank "+x+": "+ng[i]+" - "+
                            ng[i].attachment, Logger.DEBUG);
                x++;
                id[i] = ng[i].attachment;
            }
        }
        for(int i=j;i<id.length;i++) id[i] = null;
    }

    public boolean isNewbie(Identity identity, boolean isConnected) {
        NodeEstimator ne = estimators.get(identity);
        if(ne == null) return false;
        return isNewbie(ne, isConnected);
    }

    public int countNodes() {
        return estimators.size();
    }

	public double maxPSuccess() {
		NodeEstimator[] ea = estimators.toArray();
		NodeEstimator highest = null;
		double max = 0.0;
		for(int i=0;i<ea.length;i++) {
			NodeEstimator e = ea[i];
			boolean isConnected = isConnected(e.id);
			if((!isConnected) || isNewbie(e, isConnected)) {
				if(logDebug)
					Core.logger.log(this, "Excluding "+e+
							" (connected="+isConnected+
							") because newbie", Logger.DEBUG);
				continue;
			}
			double value = e.pSuccess();
			if(value > max) {
				highest = e;
				max = value;
			}
		}
		Core.logger.log(this, "Highest value: "+max+" on "+highest,
				Logger.DEBUG);
		return max;
	}

    public String whyPTransferGivenInboundRequest() {
        if(fullRequestPTransfer instanceof ExtraDetailRunningAverage)
            return ((ExtraDetailRunningAverage)fullRequestPTransfer).extraToString();
        else
            return fullRequestPTransfer.toString();
    }

    /**
     * @param identity
     * @return
     */
    public long timeTillCanSendRequest(Identity identity, long now) {
        NodeEstimator e = estimators.get(identity);
        if(e == null) return -1;
        return e.timeTillNextSendWindow(now);
    }
}
