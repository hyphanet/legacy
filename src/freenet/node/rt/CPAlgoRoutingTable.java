package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Random;
import java.util.Vector;

import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.KeyException;
import freenet.Version;
import freenet.client.AutoBackoffNodeRequester;
import freenet.client.FreenetURI;
import freenet.client.InternalClient;
import freenet.message.StoreData;
import freenet.node.BadReferenceException;
import freenet.node.IdRefPair;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.states.maintenance.Checkpoint;
import freenet.support.ArrayBucket;
import freenet.support.Bucket;
import freenet.support.Comparator;
import freenet.support.DataObject;
import freenet.support.DataObjectPending;
import freenet.support.DataObjectUnloadedException;
import freenet.support.Logger;
import freenet.support.SimpleStringMap;
import freenet.support.io.ReadInputStream;
import freenet.support.PropertyArray;

/**
 * RoutingTable implementation which uses the tested CP algorithm that was in
 * place before Tavin ripped out the old routing code.
 * 
 * @author giannij
 */
public class CPAlgoRoutingTable extends TreeRoutingTable {

	protected final Random rand;

	public CPAlgoRoutingTable(
		RoutingStore routingStore,
		int maxNodes,
		int maxRefsPerNode,
		int consecutiveFailuresLookupARK,
		int minDowntimeARKLookup,
		float minCP,
		int maxLookupThreads,
		Random rand) {
		super(routingStore, maxNodes, maxRefsPerNode);
		this.rand = rand;
		this.node = null;
		this.consecutiveFailuresLookupARK = consecutiveFailuresLookupARK;
		this.minDowntimeARKLookup = minDowntimeARKLookup;
		this.minCP = minCP;
		this.maxLookupThreads = maxLookupThreads;
	}

	int consecutiveFailuresLookupARK;
	int minDowntimeARKLookup;
	float minCP;
	int maxLookupThreads;

	public boolean wantUnkeyedReference(NodeReference ref) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this); 
		if(logDEBUG) Core.logger.log(
			this,
			toString() + ".wantUnkeyedReference(" + ref + ")",
			Logger.DEBUG);
		Identity id = ref.getIdentity();
		if (ref.noPhysical()) {
			if(logDEBUG) Core.logger.log(
				this,
				"Returning false because " + ref + " has no physical addr",
				Logger.DEBUG);
			return false;
		}
		if (!Version.checkGoodVersion(ref.getVersion())) {
			if(logDEBUG) Core.logger.log(
				this,
				"Returning false because "
					+ ref
					+ " has wrong version "
					+ ref.getVersion(),
				Logger.DEBUG);
			return false;
		}
		if (routingStore.size() < maxNodes * 0.5)
			return true;
		if(logDEBUG) Core.logger.log(
			this,
			"Returning false because "
				+ routingStore.size()
				+ " < "
				+ maxNodes * 0.5,
			Logger.DEBUG);

		return false;
	}

	public void reference(Key k, Identity i, NodeReference nr, FieldSet e) {
	    if(i == null) {
	        Core.logger.log(this, "reference() with null identity not supported "+
	                "by CPAlgoRT; expect problems if using bidi connections!", 
	                Logger.ERROR);
	        return;
	    }
		if (k == null) {
			// New reference
			int r = Core.getRandSource().nextInt();
			byte[] keyBytes = new byte[Key.KEYBYTES];
			Core.getRandSource().nextBytes(keyBytes);
			reference(new Key(keyBytes), nr, e);
		} else
			super.reference(k, nr, e);
	}

	public RTDiagSnapshot getSnapshot(boolean starting) {
		long nowMs = System.currentTimeMillis();

		// Reset ref aggregate counters
		totalRefs = 0;
		contactedRefs = 0;
		requestingRefs = 0;
		backedOffRefs = 0;

		// ^^^ the fact that you have to do this suggests these
		//     should be local variables not instance variables -tc
		//
		// 6 of 1, 1/2 dozen of the other. makeRefInfo() updates
		// the counts as a side effect, if you were morally opposed
		// to side effects you could get rid of makeRefInfo
		// (decreasing readability). --gj

		Vector nodeRefs = new Vector();
		PropertyArray refInfos =
			new PropertyArray(
				REF_PROPERTIES,
				new REF_COMPARATOR());
		HashSet keys = new HashSet();

		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		synchronized (this) {
			if (logDEBUG)
				Core.logger.log(this, "Starting getSnapshot()", Logger.DEBUG);
			Enumeration memories = routingStore.elements();
			while (memories.hasMoreElements()) {
				if (logDEBUG)
					Core.logger.log(this, "Next memory element", Logger.DEBUG);
				RoutingMemory memory = (RoutingMemory) memories.nextElement();
				NodeReference nodeRef = memory.getNodeReference();
				Identity id = memory.getIdentity();
				IdRefPair pair = new IdRefPair(id, nodeRef);

				ReferenceSet refSet = ReferenceSet.getProperty(memory, "keys");
				Enumeration refs = refSet.references();
				while (refs.hasMoreElements()) {
					if (logDEBUG)
						Core.logger.log(this, "Next ref element", Logger.DEBUG);
					Reference ref = (Reference) refs.nextElement();
					if (!keys.contains(ref.key)) {
						if (freenet.Key.isKnownKeyType(ref.key.getVal())) {
							//FIXME: why is "freenet.Key" needed to make this
							// work?
							// Only add keys registered with freenet.Key (SVK,
							// CHK at the moment)
							keys.add(ref.key);
							if (logDEBUG)
								Core.logger.log(
									this,
									"Got new key " + ref.key.toString(),
									Logger.DEBUG);
						} else {
							if (logDEBUG)
								Core.logger.log(
									this,
									"Ignored nonstandard key ending",
									Logger.DEBUG);
						}
					} else {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Ignored duplicate key",
								Logger.DEBUG);
					}
				}
				if (!nodeRefs.contains(pair)) {
					makeAddRefInfo(pair, memory, nowMs, refInfos);
					nodeRefs.addElement(pair);
				}
			}
		}

		IdRefPair[] realRefs = new IdRefPair[nodeRefs.size()];
		nodeRefs.copyInto(realRefs);

		Object[] props = new Object[TABLE_PROPERTIES.length];
		props[0] = new Integer(totalRefs);
		props[1] = new Integer(contactedRefs);
		props[2] = new Integer(requestingRefs);
		props[3] = new Integer(backedOffRefs);
		props[4] = new Integer(totalAttempts);
		props[5] = new Integer(totalSuccesses);
		props[6] = new Integer(totalConnectAttempts);
		props[7] = new Integer(totalConnectSuccesses);
		props[8] = getClass().getName();

		Key[] keyList = (Key[]) keys.toArray(new Key[keys.size()]);
		if (logDEBUG)
			Core.logger.log(
				this,
				"got " + keyList.length + " keys in getSnapshot",
				Logger.DEBUG);

		int countedKeys = getKeyCount();
		if (keyList.length != countedKeys)
			if(logDEBUG) Core.logger.log(
				this,
				"Supposed to have "
					+ countedKeys
					+ " refs but actually "
					+ "have "
					+ keyList.length,
				Logger.DEBUG);

		return new SimpleRTDiagSnapshot(
			new SimpleStringMap(TABLE_PROPERTIES, props),
			refInfos,
			keyList,
			realRefs, null);
	}

	private final static String RMKEY = "cpdata";

	protected final Comparable getDiscardValue(RoutingMemory mem, DiscardContext ctx) {
		CPAlgoData cpd = getProperty(mem, RMKEY);
		return new DiscardValue(cpd.getLastSuccessfulContact());
	}

	private final void fail(RoutingMemory mem) {
		fail(mem, true);
	}

	private final void fail(RoutingMemory mem, boolean weak) {
		CPAlgoData cpd = getProperty(mem, RMKEY);
		if (cpd.decreaseContactProbability(weak)) {
			remove(mem, null);
			return;
		}
		//mem.setProperty(RMKEY, cpd); Not needed, we have mutated the property we extracted
	}

	/**
	 * Succeed and/or fail on weak or strong in a single step
	 * 
	 * @param weakSuccess
	 *            whether we weakly (query level) succeeded
	 */
	protected final void succeedFail(
		RoutingMemory mem,
		boolean weakSuccess) {
		synchronized(counterSyncObj){
			totalAttempts++;
			if(weakSuccess)
				totalSuccesses++;
		}
		CPAlgoData cpd = getProperty(mem, RMKEY);
		if (weakSuccess) {
			cpd.increaseContactProbability(true);
		} else {
			if (cpd.decreaseContactProbability(true)) {
				remove(mem, null);
				return;
			}
		}
		//mem.setProperty(RMKEY, cpd); Not needed, we have mutated the property we extracted
	}

	protected boolean isRoutable(
		RoutingMemory mem,
		boolean desperate) {
	    if(desperate) return true;
        CPAlgoData cpd = getProperty(mem, RMKEY, node,true); //Use faster/dont-create call to getProperty() because it is...faster 
        if(cpd == null)
        	return true; //In this case this *is* the case since the backoff times will always be set to 0 during  de-serialization/new-property-creation
        return !cpd.isBackedOff();
	}

	protected void routeAccepted(RoutingMemory mem) {
		// hmm.. what?
	}

	protected void routeSucceeded(RoutingMemory mem) {
		succeedFail(mem, true);
	}

	protected void timedOut(RoutingMemory mem) {
		fail(mem);
	}

	protected void transferFailed(RoutingMemory mem) {
	    // Reduce CP slightly
		fail(mem);
	}

	// this is not a tyop!
	protected void verityFailed(RoutingMemory mem) {
	    // Reduce CP slightly; they can't do any harm, it's probably accidental
	    fail(mem);
	}

	// Reduce the chances of routing to nodes that always
	// replies with QueryRejected.
	protected void queryRejected(
		RoutingMemory mem,
		long attenuation) {

		succeedFail(mem, false);
	}

	////////////////////////////////////////////////////////////
	// Helper functions used to implement diagnostic snapshots.

	private final static String[] REF_PROPERTIES =
		{
			"Address",
			"Contact Probability",
			"Bar Code",
			"Keys",
			"Consecutive Failures",
			"Connection Attempts",
			"Successful Connections",
			"Last Attempt",
			"NodeReference",
			"Node Version",
			"Key Count",
			"ARK Version",
			"Fetching ARK",
			"ARK URL",
			"Open Outbound Connections",
			"Open Inbound Connections",
			"Backoff Remaining" };

	private final static class REF_COMPARATOR implements Comparator {
		public int compare(Object o1, Object o2) {
			if (o1 instanceof Object[] && o2 instanceof Object[]) {
				final Float cpA = (Float) (((Object[]) o1)[1]);
				//Must be index of "Contact Probability"
				final Float cpB = (Float) (((Object[]) o2)[1]);
				//Must be index of "Contact Probability"

				return cpB.compareTo(cpA);
			} else
				return 0;
		}
	}

	private final static String[] TABLE_PROPERTIES =
		{
			"Number of node references",
			"Contacted node references",
			"Node references requesting ARKs",
			"Backed off node references", 
			"Total Trials",
			"Total Successes",
			"Total Connection Attempts",
			"Total Connection Successes",
			"Implementation" };

	// ^^^ i don't understand why you are doing this here
	//     instead of just passing the NodeReference back -tc
	//
	// The point is to present data that the client
	// (i.e. the caller of getSnapShot()) can immediately
	// display without doing any more work, or even having
	// to know the type of the value.
	// NodeReference.toString() doesn't give a value that
	// makes sense to an end user. --gj

	private static final long lastAttemptSecs(
		CPAlgoData cpd,
		long nowMs) {
		long ret = -1; // never
		if (cpd.trials > 0) {
			long x = cpd.getLastAttemptSecs();
			if (x > 0) {
				// trials++ does not immediately imply lastRetryMs
				// lastRetryMs is changed only when we have a result - success
				// or failure
				ret = (nowMs - x) / 1000;
				if (ret < 1) {
					ret = 1;
				}
			}
		}
		return ret;
	}


	// ARK utility method
	// REDFLAG: move to NodeReference?
	private Object counterSyncObj = new Object(); //Sync to update the counters below
	private int totalAttempts = 0;
	private int totalSuccesses = 0;
	private int totalConnectAttempts = 0;
	private int totalConnectSuccesses = 0;

	// Diagnostic stats set by makeRefInfo
	private int totalRefs = 0;
	private int requestingRefs = 0;
	private int contactedRefs = 0;
	private int backedOffRefs = 0;

	private final Long LONG_MINUS_ONE = new Long(-1);
	private final Integer INT_ZERO = new Integer(0);
	private final Long LONG_ZERO = new Long(0);

	////////////////////////////////////////////////////////////
	private final void makeAddRefInfo(
		IdRefPair pair,
		RoutingMemory mem,
		long nowMs,
		PropertyArray pa) {
		CPAlgoData cpd = getProperty(mem, RMKEY);

		NodeReference ref = pair.ref;
		pa.addToBuilder("Address", ref == null ? "(null)" : 
		        ref.firstPhysicalToString());
		// Don't phreak out cluebies with 0.0 cp during backoff.
		pa.addToBuilder(
			"Contact Probability",
			new Float(cpd.effectiveCP(nowMs)));
		ReferenceSet refSet = ReferenceSet.getProperty(mem, "keys");
		Enumeration enu = refSet.references();

		LinkedList refKeys = new LinkedList();
		while (enu.hasMoreElements()) {
			Reference r = (Reference) enu.nextElement();
			if (r != null) {
				refKeys.add(r.key);
			}
		}
		Key[] refKeys2 = new Key[refKeys.size()];
		Object[] keys = refKeys.toArray();
		System.arraycopy(keys, 0, refKeys2, 0, keys.length);
		pa.addToBuilder("Keys", refKeys2);
		pa.addToBuilder(
			"Consecutive Failures",
			new Long(cpd.consecutiveConnectFailures));
		///values[2] = new Integer(cpd.failureIntervals);
		pa.addToBuilder("Connection Attempts", new Long(cpd.connectTrials));
		pa.addToBuilder("Successful Connections", new Long(cpd.connectSuccesses));
		///values[5] = backedOffSecs(cpd, nowMs);
		pa.addToBuilder(
			"Last Attempt",
			new Long(lastAttemptSecs(cpd, nowMs)));
		pa.addToBuilder("NodeReference", ref); // refs are immutable
		pa.addToBuilder("Node Version", ref.getVersion());
		///values[9] = new Boolean(cpd.routingBackedOff());

		pa.addToBuilder("Key Count", new Long(refSet.size()));
		pa.addToBuilder("ARK Version", new Long(ref.revision()));
		pa.addToBuilder("Fetching ARK", new Boolean(cpd.fetchingARK()));
		try {
			FreenetURI uri = ref == null ? null : 
			    ref.getARKURI(ref.revision());
			if (uri != null)
				pa.addToBuilder("ARK URL", uri.toString(false));
		} catch (KeyException e) {
			Core.logger.log(
				this,
				"KeyException thrown getting ARK URI in makeRefInfo: " + e,
				e,
				Logger.ERROR);
		}
		freenet.OpenConnectionManager ocm = node.connections;
		int inConns;
		int outConns;
		synchronized (ocm) {
			outConns = ocm.countOutboundConnections(ref.getIdentity());
			inConns = ocm.countInboundConnections(ref.getIdentity());
		}
		pa.addToBuilder("Open Outbound Connections", new Integer(outConns));
		pa.addToBuilder("Open Inbound Connections", new Integer(inConns));
		pa.addToBuilder("Backoff Remaining", new Integer(cpd.backoffRemaining()));

		pa.commitBuilder(); //commit all the stats we've been building

		// Update aggregate stats
		totalRefs++;
		if (cpd.fetchingARK()) {
			requestingRefs++;
		}

		// 	if (cpd.successes > 0)
		if (inConns + outConns > 0) {
			contactedRefs++;
			if (cpd.isBackedOff()) {
			    backedOffRefs++;
			}
		}
	}
	final CPAlgoData getProperty(RoutingMemory mem, String name) {
		return getProperty(mem,name,node,false);
	}
	
	//Retrieve a property from the supplied RoutingMemory. If dontReloadOrCreate==false the property will be created
	//or deserialized as needed. If dontReloadOrCreate==true the method will return null if the property doesn't
	//exist or is currently unloaded (faster execution path due to somewhat less synchronization)
	final CPAlgoData getProperty(RoutingMemory mem, String name, Node node, boolean dontReloadOrCreate) {
		CPAlgoData ret = null;

		//Try a fast non-synchronized (on this) lookup first..
		try {
			ret = (CPAlgoData) mem.getProperty(name);
		} catch (DataObjectUnloadedException e) {
			//ignore
		}
		
		//If explicitly asked not to create or load anything if the property
		//wasn't found, return nothing.. Assume that the caller knows what
		//he is doing..
		if (ret != null || (ret == null && dontReloadOrCreate))
			return ret;

		//Hmm.. ok, we didn't find the property, then we (probably) will have
		//to load or create it. This requires that we try to look up the data
		//in the mem again, this time synchronized on 'this' (unfortunately to a performance penality).
		//If we didn't do the lookup again we would face a typical
		//'double checked locking' bug situation...
		synchronized (this) {
		long startTime = System.currentTimeMillis();
			boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
			if (logDEBUG)
				Core.logger.log(this, "getProperty(" + mem + "," + name + ",node) at " + startTime, Logger.DEBUG);
		try {
			ret = (CPAlgoData) mem.getProperty(name);
		} catch (DataObjectUnloadedException e) {
			long thrownTime = System.currentTimeMillis();
				if (logDEBUG || (thrownTime - startTime) > 500)
					Core.logger.log(this, "getProperty got DataObjectUnloadedException " + "after " + (thrownTime - startTime), ((thrownTime - startTime) > 500) ? Logger.MINOR : Logger.DEBUG);
			ret = new CPAlgoData(e, mem.getNodeReference());
			long constTime = System.currentTimeMillis();
			long constLength = constTime - thrownTime;
				if (logDEBUG || constLength > 500)
					Core.logger.log(this, "Constructed CPAlgoData from " + "DataObjectUnloadedException in " + constLength, constLength > 500 ? Logger.MINOR : Logger.DEBUG);
			mem.setProperty(name, ret);
			long setTime = System.currentTimeMillis();
			long setLength = setTime - constTime;
				if (logDEBUG)
					Core.logger.log(this, "Set property " + name + " to mem (" + mem + ") in " + setLength, Logger.DEBUG);
		}
		long gotTime = System.currentTimeMillis();
		long gotLength = gotTime - startTime;
			if (logDEBUG || gotLength > 500)
				Core.logger.log(this, "Got property from mem (" + mem + ") in " + gotLength, gotLength > 500 ? Logger.MINOR : Logger.DEBUG);
		if (ret == null) {
			ret = new CPAlgoData(mem.getNodeReference());
			mem.setProperty(name, ret);
			long setTime = System.currentTimeMillis();
			long setLength = setTime - gotTime;
				if (logDEBUG)
					Core.logger.log(this, "Set property " + name + " to mem (" + mem + ") in " + setLength, Logger.DEBUG);
		}
		return ret;
	}
	}

	int lookupThreads = 0;
	Object lookupThreadsLock = new Object();

	////////////////////////////////////////////////////////////
	/**
	 * Class to encapsulate the data used to implement the tested CP algorithm
	 * that was in place before Tavin ripped out the old routing code.
	 * <p>
	 * We keep count of connection attempts, count of connections, count of
	 * request attempts, count of successful or DNFed requests
	 */
	class CPAlgoData implements DataObject {

		// persisted.
		float contactProbability = 1.0f;

		long startBackoffTime = -1;
		int initBackoffLength = 1000;
		int backoffLength = -1;
		double successBackoffDivisor = 1.5;
		
		// Diagnostic info
		long successes = 0;
		long trials = 0;
		
		// Connections
		volatile long connectSuccesses = 0;
		volatile long connectTrials = 0;

		// Number of consecutive failures
		volatile long consecutiveConnectFailures = 0;

		// FIXME
		// 	static final long CONSECUTIVEFAILURESLOOKUPARK = 20;
		// 	static final long MINOLDMILLISLOOKUPARK = 900*1000; // 15 minutes
		// 	static final float MINCP = 0.01F;

		// Time of last successful contact
		long lastSuccessfulContact = -1;

		long lastRetryMs = -1;

		// ARK fetcher
		LookupARK lookup = null;
		Object lookupLock = new Object();
		volatile boolean justFetchedARK = false;

		NodeReference ref;

		////////////////////////////////////////////////////////////

		private CPAlgoData(NodeReference ref) {
			this.ref = ref;
		}

		/**
         * @return the number of milliseconds remaining on the current backoff period
         */
        public int backoffRemaining() {
            long now = System.currentTimeMillis();
			
            //Dont care about synchronization for the operation below
            //It is not *that* important that we calculate the backoff
            //period atomically
            long end = startBackoffTime + backoffLength;
            if(end > now) {
                return (int)(end - now); 
            } else return 0;
        }

        /**
         * @return true if we are backed off
         */
        public boolean isBackedOff() {
            return backoffRemaining() >0;
        }

        protected void finalize() {
			synchronized (lookupLock) {
				if (lookup != null) {
					Core.logger.log(
						this,
						"Killing ARK lookup in finalizer",
						Logger.DEBUG);
					lookup.kill();
					lookup = null;
				}
			}
		}

		////////////////////////////////////////////////////////////
		// DataObject implementation
		//
		// REDFLAG: double check
		private CPAlgoData(DataObjectPending dop, NodeReference ref) {
			this.ref = ref;
			if (dop.getDataLength() > 0) {
				try {
					DataInputStream din = dop.getDataInputStream();
					contactProbability = din.readFloat();
				} catch (IOException e) {
					Core.logger.log(
						this,
						"IOException reading CP!",
						e,
						Logger.ERROR);
				}
				//wtf? Tavin, If this is ok there should be a comment
				// explaining why.
				//
				// GJ, there were a few fine points I kind of glossed over..
				// but
				// it's not unreasonable to do this, the object just ends up
				// being
				// reset to initial values from the application layer's point
				// of view.
				//
				// Besides, if we get IOExceptions here there is something very
				// wrong
				// with the data store that will probably bring down the whole
				// node
				// anyway.
			}
			dop.resolve(this);
		}

		public final void writeDataTo(DataOutputStream out)
			throws IOException {
			out.writeFloat(contactProbability);
		}

		public final int getDataLength() {
			return FLOAT_SIZE;
		}

		////////////////////////////////////////////////////////////

		// Connection limits are inexact because of the time lag
		// between connection attempts and the CP update. One
		// could achieve more exact enforcement at the cost of
		// exposing connection management to the routing code.
		// It doesn't look like that's necessary. There is
		// already a hack in the OCM that keeps too many
		// connections to the same ref from blocking at
		// the socket layer.
		//

		// Weight the CP based on the time since the last connection
		// attempt and the number of failures.
		final float effectiveCP(long nowMs) {
		    return isBackedOff() ? 0.0F : 1.0F;
//			return contactProbability();
		}

		final float contactProbability() {
//			if (contactProbability < minCP) {
//				contactProbability = minCP;
//				return minCP;
//			}
//			return contactProbability;
		    return isBackedOff() ? 0.0F : 1.0F;
		}

		// returns true if the node should be dereferenced.
		final boolean decreaseContactProbability(boolean weak) {
			long now = System.currentTimeMillis();
			synchronized (this) { //Sync after we have retrieved the current time instead of sync:ing the method (optimization)
		    trials++;
//		    contactProbability =
//				(float) (weak ? ((9.0 * contactProbability) / 10.0) : (((
//			/* 1.0 * */
//			contactProbability) /* + 0.0 */
//			) / 2.0));
//			// Decrease by 10% for weak, 50% for strong
//			if (contactProbability < minCP)
//				contactProbability = minCP;

			lastRetryMs = now;
			if(startBackoffTime + backoffLength > now) {
			    // Backed off, ignore
			} else {
			    startBackoffTime = now;
			    if(backoffLength < initBackoffLength)
			        backoffLength = initBackoffLength;
					backoffLength = (int) (backoffLength + (Core.getRandSource().nextFloat() * initBackoffLength));
					if (backoffLength > 3600 * 1000)
						backoffLength = 3600 * 1000;
					if (Core.logger.shouldLog(Logger.DEBUG, this))
						Core.logger.log(this, "Backing off for " + backoffLength + " from " + startBackoffTime + " for " + ref, Logger.MINOR);
			}
			return false;
		}
		}

		final void increaseContactProbability(boolean weak) {
			long now = System.currentTimeMillis();
			synchronized (this) { //Sync after we have retrieved the current time instead of sync:ing the method (optimization)
		    trials++;
		    successes++;

			if (justFetchedARK) {
				Core.diagnostics.occurrenceCounting("successLookupARK", 1);
				justFetchedARK = false;
			}
			lastRetryMs = lastSuccessfulContact = now;
			if(startBackoffTime + backoffLength > now) {
			    // Backed off
			    // Ignore
			} else {
			    if(backoffLength < initBackoffLength)
			        backoffLength = initBackoffLength;
					backoffLength = (int) (backoffLength / (1.0 + Core.getRandSource().nextDouble()));
			    if (backoffLength < initBackoffLength)
			        backoffLength = initBackoffLength;
					if (Core.logger.shouldLog(Logger.MINOR, this))
						Core.logger.log(this, "Reducing backoff period to " + backoffLength + "ms for " + this +" (" + ref + ")", Logger.MINOR);
				}
			}
		}

		final boolean fetchingARK() {
			return lookup != null;
		}

		protected final FreenetURI getARKURI() throws KeyException {
			return ref.getARKURI(ref.revision() + 1);
		}

		protected final FreenetURI getARKURI(long version)
			throws KeyException {
			return ref.getARKURI(version);
		}

		protected class LookupARK extends AutoBackoffNodeRequester {
			long version;
			long firstVersion;
			long lastVersion;

			private boolean logDebug = true;

			public LookupARK(FreenetURI uri) {
				super(
					new InternalClient(node),
					uri,
					false,
					new ArrayBucket(),
					Node.maxHopsToLive);
				version = ref.revision() + 1;
				lastVersion = firstVersion = version;
				sleepTimeMultiplier = 1.0F; // deal with it manually
				// be a bit more aggressive than the others
				logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
				if (logDebug)
					Core.logger.log(
						this,
						"Scheduling ARK fetch for " + getURI(),
						Logger.DEBUG);
				new Checkpoint(this).schedule(node);
				Core.diagnostics.occurrenceCounting("startedLookupARK", 1);
				synchronized (lookupThreadsLock) {
					lookupThreads++;
					if (logDebug)
						Core.logger.log(
							this,
							"Added new ARK lookup - now up to " + lookupThreads,
							Logger.DEBUG);
				}
			}

			public String getCheckpointName() {
				return "Fetching ARK " + getURI();
			}

			public FreenetURI getURI() {
				FreenetURI uri;
				try {
					uri = getARKURI(version);
				} catch (KeyException e) {
					Core.logger.log(
						this,
						"KeyException getting URI: " + e,
						e,
						Logger.ERROR);
					finished = true;
					return null;
				}
				return uri;
			}

			protected Bucket getBucket() {
				return bucket;
			}

			protected void onFinish() {
				synchronized (lookupThreadsLock) {
					if (logDebug)
						Core.logger.log(
							this,
							"Terminated ARK lookup - now up to "
								+ lookupThreads,
							Logger.DEBUG);
					lookupThreads--;
				}
			}

			public boolean internalRun() {
				Core.diagnostics.occurrenceCounting("lookupARKattempts", 1);
				return super.internalRun();
			}

			protected boolean success() {
				boolean logMINOR = Core.logger.shouldLog(Logger.MINOR,this);
				if(logMINOR) Core.logger.log(
					this,
					"Fetched ARK " + getURI() + ": " + ((ArrayBucket) bucket).toString(),
					Logger.MINOR);
				// REDFLAG/FIXME: CHECK LOCKING HERE

				// hmmm... Dodgy. Need cast because
				// Bucket.getInputStream() throws IOException but
				// ArrayBucket.getInputStream() doesn't. -gj

				InputStream in = ((ArrayBucket) bucket).getInputStream();
				FieldSet fs;
				Core.diagnostics.occurrenceCounting("fetchedLookupARK", 1);
				try {
					fs = new FieldSet(new ReadInputStream(in));
				} catch (IOException e) {
					Core.logger.log(
						this,
						"IOException reading FieldSet from ARK! ("
							+ getURI()
							+ ")",
						e,
						Logger.ERROR);
					uri = getURI();
					version++;
					sleepTime = initialSleepTime; // probably not their fault
					if (firstVersion < version)
						firstVersion = version;
					if (lastVersion < version)
						lastVersion = version;
					return false; // ?
				}
				NodeReference newRef;
				try {
					newRef = new NodeReference(fs, true);
				} catch (BadReferenceException e) {
					// Might just be old-format; regardless, lets try the next
					// one
					version++;

					sleepTime = sleepTime / 4;
					// ^^^ What the hell? doesn't this negate the *=1.1?
					// --Thelema

					if (sleepTime <= initialSleepTime)
						sleepTime = initialSleepTime;
					sleepTime *= 1.1;
					// so a series of bad references will gradually slow down

					if (firstVersion < version)
						firstVersion = version;
					if (lastVersion < version)
						lastVersion = version;

					if(logMINOR) Core.logger.log(
						this,
						"Invalid ARK - fetching next version: " + getURI(),
						e,
						Logger.MINOR);
					uri = getURI();
					return false;
				}
				if (!newRef.getIdentity().equals(ref.getIdentity())) {
					version++;

					sleepTime = sleepTime / 4;
					// ^^^ What the hell? doesn't this negate the *=1.5?
					// --Thelema

					if (sleepTime <= initialSleepTime)
						sleepTime = initialSleepTime;
					sleepTime *= 1.5; // bad identity worse than bad reference
					// so a series of bad references will gradually slow down

					if (firstVersion < version)
						firstVersion = version;
					if (lastVersion < version)
						lastVersion = version;

					if(logMINOR) Core.logger.log(
						this,
						"Invalid ARK (tried to change identity) - fetching next version: "
							+ getURI(),
						Logger.MINOR);
					uri = getURI();
					return false;
				}
				synchronized (CPAlgoRoutingTable.this) {
					routingStore.putNode(null, newRef);
					// Don't touch the CP. Nodes should not persist just by
					// inserting lots of ARKs.
				}
				Core.diagnostics.occurrenceCounting("validLookupARK", 1);
				justFetchedARK = true;
				synchronized (lookupLock) {
					lookup = null; // we're done
				}
				finished = true;
				return true;
			}

			int seq = 0;

			protected boolean failedInvalidData() {
				if (logDebug) Core.logger.log(
					this,
					"failed: invalid data: " + this,
					Logger.DEBUG);
				version++;
				firstVersion = version;
				if (lastVersion < firstVersion)
					lastVersion = firstVersion;
				if (logDebug) Core.logger.log(
					this,
					"failed: invalid data: "
						+ this
						+ ": retrying with higher version number",
					Logger.DEBUG);
				return false; // retry
			}

			protected boolean failure() {
				if (logDebug)
					Core.logger.log(
						this,
						"LookupARK Failure: version="
							+ version
							+ ", firstVersion="
							+ firstVersion
							+ ", lastVersion="
							+ lastVersion
							+ " for "
							+ getURI(),
						Logger.DEBUG);
				if (dnf != null) {
					seq++; // used by both branches
					//Should it ever be reset to 0? (or is the whole class
					// recreated to start fetching ARKs again?) --Thelema
					if (version == lastVersion) {
						if (logDebug)
							Core.logger.log(
								this,
								"At last version",
								Logger.DEBUG);
						// exponential backoff, double up on every full pass
						sleepTime *= 2;

						if ((seq & 1) == 0) {
							lastVersion++;
							if (logDebug)
								Core.logger.log(
									this,
									"Incrementing lastVersion to "
										+ lastVersion
										+ " for "
										+ getURI(),
									Logger.DEBUG);
						}
						version = firstVersion;
					} else {
						if ((seq & 1) == 0) {
							version++;
							if (logDebug)
								Core.logger.log(
									this,
									"Incrementing version to "
										+ version
										+ " for "
										+ getURI(),
									Logger.DEBUG);
						}
						sleepTime *= 1.5;
					}
				} else {
					if (logDebug)
						Core.logger.log(
							this,
							"ARK failure not a DNF for " + getURI(),
							Logger.DEBUG);
					sleepTime *= 2;
					// Standard response to RNFs: double the sleepTime
				}
				if (logDebug)
					Core.logger.log(
						this,
						"Retrying ARK fetch with version "
							+ version
							+ " for "
							+ getURI()
							+ " (delay "
							+ sleepTime
							+ ")",
						Logger.MINOR);
				return false; // failed, retry exponentially
			}

            protected boolean doSplitFiles() {
                // ARKs are small
                return false;
            }

		}

        public void reportConnectionSuccess() {
        	synchronized(counterSyncObj){
            totalConnectAttempts++;
            totalConnectSuccesses++;
        	}
			synchronized (this) {
				connectTrials++;
				connectSuccesses++;
            consecutiveConnectFailures = 0;
            synchronized (lookupLock) {
                if (lookup != null) {
                    lookup.kill();
                    lookup = null;
                }
            }
        }
		}
        
        
        //Consider synchronizing this method if a larger-scale
        //atomicity is required (check instances of synchronization on 'this')
        public long getLastSuccessfulContact(){
        	return lastSuccessfulContact;
        }
		//Consider synchronizing this method if a larger-scale
		//atomicity is required (check instances of synchronization on 'this')
		private long getLastAttemptSecs(){
			return lastRetryMs;
		}

        public void reportConnectionFailure() {
			synchronized(counterSyncObj){
				totalConnectAttempts++;
			}
			synchronized (this) {
            connectTrials++;
            consecutiveConnectFailures++;
            // When consecutiveFailures reaches some value, start an ARK lookup
				if (consecutiveConnectFailures >= consecutiveFailuresLookupARK && getLastSuccessfulContact() + minDowntimeARKLookup < System.currentTimeMillis()) {
                synchronized (lookupLock) {
                    if (node == null)
                        throw new IllegalStateException("creating LookupARK with null node");
                    try {
                        if (lookup == null && getARKURI() != null) {
                            synchronized (lookupThreadsLock) {
                                if (lookupThreads < maxLookupThreads)
                                    lookup = new LookupARK(getARKURI());
                            }
                            // FIXME: Kill the LookupARK whose ref is least
                            // recently used
                        }
                    } catch (KeyException e) {
							Core.logger.log(this, "Cannot start ARK lookup: " + e, e, Logger.ERROR);
						}
                    }
                }
            }
        }
	}

	public void reportConnectionSuccess(Identity id, long time) {
	    RoutingMemory rm = (routingStore.getNode(id));
	    if (rm == null)
	        return;
	    CPAlgoData cpd = getProperty(rm, RMKEY);
	    cpd.reportConnectionSuccess();
	}

	public void reportConnectionFailure(Identity id, long time) {
		RoutingMemory rm = (routingStore.getNode(id));
		if (rm == null)
		    return;
		CPAlgoData cpd = getProperty(rm, RMKEY);
		cpd.reportConnectionFailure();
	}

	public FieldSet estimatorToFieldSet(Identity identity) {
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.rt.RoutingTable#shouldReference(freenet.node.NodeReference,
	 *      freenet.message.StoreData)
	 */
	public boolean shouldReference(NodeReference nr, StoreData sd) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if (nr == null) {
			if (logDEBUG) Core.logger.log(
				this,
				"shouldReference returning false because " + "null ref",
				Logger.DEBUG);
			return false;
		}
		int x = getKeyCount();
		if (logDEBUG) Core.logger.log(
			this,
			Integer.toString(x) + " elements in RoutingTable",
			Logger.DEBUG);
		if (x < (maxRefsPerNode * maxNodes * (double) Node.minRTFullPRef)) {
			if (logDEBUG) Core.logger.log(
				this,
				"shouldReference because RT less than required size",
				Logger.DEBUG);
			return true;
		} else {
			if (sd == null) {
				if (logDEBUG) Core.logger.log(
					this,
					"shouldReference because null StoreData",
					Logger.DEBUG);
				return true;
			} else {
				if (sd
					.shouldCache(Core.getRandSource(), Node.cacheProbPerHop)) {
						if (logDEBUG) Core.logger.log(
						this,
						"shouldReference returning true because "
							+ "sd.shouldCache says so for "
							+ sd,
						Logger.DEBUG);
					return true;
				} else {
					if (logDEBUG) Core.logger.log(
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

    protected void earlyTimeout(RoutingMemory mem) {
        queryRejected(mem, 0); // same as QR
    }

    protected void searchFailed(RoutingMemory mem) {
        queryRejected(mem, 0); // same as QR
    }

    public void updateMinRequestInterval(Identity id, double d) {
        // FIXME!!
        Core.logger.log(this, "updateMinRequestInterval called on "+this+
                ": unimplemented!", new Exception("debug"), Logger.ERROR);
    }

    public double estimatedOutgoingLoad() {
        Core.logger.log(this, "estimatedOutgoingLoad() not supported by classic routing!",
                Logger.ERROR);
        return 0.0;
    }

    protected DiscardContext getTypicalDiscardSortContext() {
        // Not used
        return null;
    }

    public int countNodes() {
        return this.routingStore.size();
    }
}
