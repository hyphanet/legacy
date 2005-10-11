package freenet.node.simulator;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;

/**
 * A single simulated node.
 */
public class ThreadedNode implements Comparable {
	/** Whether to route newbie nodes requests purely by their announcement key */
	static boolean DO_NEWBIEROUTING = false;
	/** Whether to cache exclusively according to the global estimate of the key.
	* This is a really stupid idea, as you will find if you simulate it. 
	* Note: uses ONE estimator. */
	static boolean DO_CACHE_BY_ESTIMATE = false;
	/** Whether to do probabilistic caching based on hops since reset */
	static boolean DO_CLASSIC_PCACHING = false;
	/** Whether to do probabilistic reference based on hops since reset */
	static boolean DO_PREFERENCE = true;
	/** Whether to log excessively */
	static boolean DO_MORE_LOGGING = false;
	/** Whether to use two estimators. Otherwise we use one. */
	static boolean USE_THREE_ESTIMATORS = true;
	/** Whether to enable smoothing */
	static boolean DO_SMOOTHING = false;
	/** Whether to use pure random routing */
	static boolean DO_RANDOM = false;
	/** Whether to use fast estimators (double instead of BigInteger) */
	static boolean DO_FAST_ESTIMATORS = false;
	/** Whether to use announcements */
	static boolean DO_ANNOUNCEMENTS = false;
	/** Whether to use alternate announcements */
	static boolean DO_ALT_ANNOUNCEMENTS = false;
	/** Whether to probe by inexperience; if not, probes are random */
	static boolean PROBE_BY_INEXPERIENCE = false;
	/** Whether to randomize when several nodes have the same estimate, if
	* not ALL nodes have the same estimate. Probably a good idea, optional
	* to allow comparison with older plots.
	*/
	static boolean RANDOMIZE_WHEN_EQUAL = false;
	/** Whether to only do probe requests when we actually have some inexperienced
	* nodes (as opposed to newbie nodes)
	*/
	static boolean PROBE_ONLY_WHEN_NECESSARY = false;
	/** Whether to pass estimators around */
	static boolean DO_ESTIMATOR_PASSING = false;
	/** Movement factor for estimators */
	static double MOVEMENT_FACTOR = 0.05;
	/** Number of buckets to use for estimators */
	static int BUCKET_COUNT = 8;
	/** Routing table size */
	static int RT_MAX_NODES = 25;
	/** Minimum routing table fullness at which pref kicks in */
	static double PREF_MIN_RT_FULL = 1.0;

	static int NEWBIE_REQUEST_LIMIT = 200;


	int nodeId;
	int connections = 0;
	ThreadedLRUQueue storedKeys;
	KeyspaceEstimatorFactory myKeyspaceEstimatorFactory;
	KeyspaceEstimator myEstimator;

	public ThreadedNode(int id, KeyspaceEstimatorFactory inputKeyspaceEstimatorFactory, int maxKeys, int maxConnections)
	{
		nodeId = id;
		myKeyspaceEstimatorFactory = inputKeyspaceEstimatorFactory;

		//newbiePeers = new Hashtable();
		//maturePeers = new Hashtable();
		storedKeys = new ThreadedLRUQueue(maxKeys);
		myEstimator = newEstimator();
		//announcementKey = CHK.randomKey();
	}

	public boolean storeKey(double in)
	{
		Object key = new Double(in);
		storedKeys.put(key, key);
		return true;
	}

	public Double getKey (double in)
	{
		Object key = new Double(in);
		return (Double) storedKeys.get(key);
	}

	public boolean process(ThreadedRequest r)
	{
		Peer p;
		if(DO_ESTIMATOR_PASSING) {
			if(r.isRequest())
			{
				p = peerFor(r.sourceNode);
				if(p != null)
				{
					if(USE_THREE_ESTIMATORS) {
						if(r.sourceEpDNF == null && p.epDNF != null)
						{
							r.sourceEpDNF = (KeyspaceEstimator) p.epDNF.clone();
						}
						if(r.sourceEtSuccess == null && p.etFailure != null)
						{
							r.sourceEtSuccess = (KeyspaceEstimator) p.etFailure.clone();
						}
						if(r.sourceEtFailure == null && p.etSuccess != null)
						{
							r.sourceEtFailure = (KeyspaceEstimator) p.etSuccess.clone();
						}
					}
					else
					{
						if(r.sourceE == null && p.e != null)
						{
							r.sourceE = (KeyspaceEstimator) p.e.clone();
						}
					}
				}
			}
			else
			{
				p = peerFor(r.dataSourceNode);
				if(p != null)
				{
					if(USE_THREE_ESTIMATORS) {
						if(r.dataSourceEpDNF == null && p.epDNF != null)
						{
							r.dataSourceEpDNF = (KeyspaceEstimator) p.epDNF.clone();
						}
						if(r.dataSourceEtSuccess == null && p.etFailure != null)
						{
							r.dataSourceEtSuccess = (KeyspaceEstimator) p.etFailure.clone();
						}
						if(r.dataSourceEtFailure == null && p.etSuccess != null)
						{
							r.dataSourceEtFailure = (KeyspaceEstimator) p.etSuccess.clone();
						}
					}
					else
					{
						if(r.dataSourceE == null && p.e != null)
						{
							r.dataSourceE = (KeyspaceEstimator) p.e.clone();
						}
					}
				}
			}
		}
		return true;
	}

	public boolean connectTo(ThreadedConnectionRequest r)
	{
		if(r.isRequest())
		{
			Peer p = new Peer(r.sourceNode, r.announceKey);
			// is a request for a new connection
		}
		else if(r.isDisconnect())
		{
			
			// is a dissconnect notification
		}
		else
		{
			Peer p = new Peer(r.dataSourceNode, r.announceKey);
			// is a confirmation notification
		}
		//make a new connection to the node specified in the request
		return true;
	}

	public Peer peerFor(int n)
	{
		return new Peer(n, null);
	}

	private KeyspaceEstimator newEstimator() {
		return myKeyspaceEstimatorFactory.createTime(500, null);
	}

	private KeyspaceEstimator newTimeEstimator() {
		return myKeyspaceEstimatorFactory.createTime(10, null);
	}

	private KeyspaceEstimator newProbabilityEstimator() {
		return myKeyspaceEstimatorFactory.createProbability(1.0, null);
	}

	public int compareTo(Object o)
	{
		if(o instanceof ThreadedNode) {
			ThreadedNode n = (ThreadedNode)o;
			if(n == this) return 0;
			if(n.nodeId == nodeId) return 0;
			if(n.nodeId > nodeId) return 1;
			else return -1;
		} else throw new ClassCastException();
	}

	/* Inner Class Peer */
	class Peer {
		int n;
		KeyspaceEstimator e;
		KeyspaceEstimator epDNF;
		KeyspaceEstimator etSuccess;
		KeyspaceEstimator etFailure;
		Key announcementKey;
		boolean wasNewbie;
		int newbieCounter;

		public Peer(int n, Key announcementKey)
		{
			this.n = n;
			if(DO_NEWBIEROUTING)
				this.announcementKey = announcementKey;
			else
				this.announcementKey = null;
  			if(USE_THREE_ESTIMATORS)
			{
				epDNF = newProbabilityEstimator();
				etSuccess = newTimeEstimator();
				etFailure = newTimeEstimator();
			} else {
				e = newEstimator();
			}
			newbieCounter = NEWBIE_REQUEST_LIMIT;
			wasNewbie = isNewbie();
		}

		boolean isNewbie()
		{
			if(announcementKey != null)
			{ // either NEWBIEROUTING or announcement
				return isInexperienced();
			} else
			return false;
			//return false;
		}
		/**
		* @return Whether the node is sufficiently inexperienced to warrant
		* a probe request.
		*/
		public boolean isInexperienced()
		{
			if(USE_THREE_ESTIMATORS)
				return epDNF.countReports() < NEWBIE_REQUEST_LIMIT;
			else
				return e.countReports() < NEWBIE_REQUEST_LIMIT;
		}
		public void updateNewbieness() {
			if(!isNewbie()) {
				if(wasNewbie) {
					wasNewbie = false;
//					newbiePeers.remove(n);
//					maturePeers.put(n, this);
				}
			}
		}
		/**
		* @param announceKey
		*/
		public void setAnnouncementEstimators(Key announceKey) {
			if(USE_THREE_ESTIMATORS) {
				for(int i=0;i<10;i++) {
					epDNF.reportProbability(announceKey, 0.0);
					etSuccess.reportTime(announceKey, 0);
				}
			} else {
				for(int i=0;i<10;i++)
					e.reportTime(announceKey, 0);
			}
		}
	}
}
