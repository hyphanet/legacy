package freenet.node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.StringTokenizer;

import freenet.Core;
import freenet.config.Option;
import freenet.config.Params;
import freenet.fs.dir.NativeFSDirectory;
import freenet.node.states.maintenance.Checkpoint;
import freenet.support.Checkpointed;
import freenet.support.Logger;
import freenet.support.io.Bandwidth;
import freenet.transport.tcpConnection;

/**
 * Checks the config file for updates, and if found, applies what it can.
 * 
 * @author Pascal
 */
public class NodeConfigUpdater implements Checkpointed {

	/** How often (in minutes) to check for config updates */
	private int updateInterval;

	/** Configuration we have already seen */
	private static Params oldParams;

	/** New configuration */
	private Params newParams;

	/** Last time the config file was modified */
	private long lastModified;

	/** ConfigUpdateListeners registered: map of path to HashSet of listeners */
	private static HashMap listeners = new HashMap();

	/**
	 * Gets the checkpointName attribute of the NodeConfigUpdater object
	 * 
	 * @return The checkpointName value
	 */
	public String getCheckpointName() {
		return "On-the-fly configuration updater";
	}

	/**
	 * Determines when the next NodeConfigUpdater checkpoint should run
	 * 
	 * @return Time the next NodeConfigUpdater checkpoint should be run
	 */
	public long nextCheckpoint() {
		if (updateInterval == 0)
			return -1;
		return System.currentTimeMillis() + updateInterval * 60000;
	}

	/**
	 * Initializes on-the-fly configuration updater functionality
	 * 
	 * @param updateInterval
	 *            How often to check for config updates
	 */
	public NodeConfigUpdater(int updateInterval) throws Throwable {
		this.updateInterval = updateInterval;
		oldParams = new Params(Node.getConfig().getOptions());
		lastModified = Main.paramFile.lastModified();
		oldParams.readParams(Main.paramFile);
		fireUpdates(oldParams);
	}

	private static Object syncOb = new Object();

	public final Object syncOb() {
		return syncOb;
	}

	/**
	 * Periodically check to see if the configuration file has been updated. If
	 * it has, read in the new config, compare to the old, and update as much
	 * of the active config as it knows how.
	 */
	public void checkpoint() {
		synchronized (syncOb()) {
			if (Main.paramFile.lastModified() == lastModified)
				return;
			lastModified = Main.paramFile.lastModified();
			newParams = new Params(Node.getConfig().getOptions());
			try {
				newParams.readParams(Main.paramFile);
			} catch (Throwable e) {
				newParams = null;
				Core.logger.log(
					this,
					"Config file changed but was not readable.",
					e,
					Logger.ERROR);
				return;
			}
		}
		fireUpdates(newParams);

		Option[] newOptions = newParams.getOptions();
		ConfigOptions options = new ConfigOptions();
		for (int i = 0; i < newOptions.length; i++) {
			String oldParam = oldParams.getParam(newOptions[i].name());
			String newParam = newParams.getParam(newOptions[i].name());
			if ((oldParam == null) && (newParam == null))
				continue;
			if (((oldParam == null) ^ (newParam == null))
				|| !oldParam.equalsIgnoreCase(newParam)) {
				try {
					options.getClass().getMethod(
						newOptions[i].name(),
						null).invoke(
						options,
						null);
				} catch (Throwable e) {
					Core.logger.log(
						NodeConfigUpdater.class,
						"Option "
							+ newOptions[i].name()
							+ " changed to "
							+ newParams.getParam(newOptions[i].name())
							+ " but no handler was available.",
						e,
						Logger.ERROR);
				}
			}
		}
		oldParams = newParams;
		newParams = null;
	}

	public static void addUpdateListener(
		String path,
		ConfigUpdateListener listener) {
		if (Core.logger.shouldLog(Logger.DEBUG, NodeConfigUpdater.class))
			Core.logger.log(
				NodeConfigUpdater.class,
				"registering path ["
					+ path
					+ "] listener ["
					+ listener.getClass().getName()
					+ "]",
				Logger.DEBUG);
		if (!listeners.containsKey(path))
			listeners.put(path, new HashSet());
		HashSet lsnrs = (HashSet) listeners.get(path);
		lsnrs.add(listener);
		fireInitialUpdate(path, listener);
	}
	public static void removeUpdateListener(ConfigUpdateListener listener) {
		for (Iterator iter = listeners.values().iterator(); iter.hasNext();) {
			HashSet listeners = (HashSet) iter.next();
			listeners.remove(listener);
		}
	}

	private static void fireInitialUpdate(
		String path,
		ConfigUpdateListener listener) {
		boolean logDEBUG =
			Core.logger.shouldLog(Logger.DEBUG, NodeConfigUpdater.class);
		if (logDEBUG)
			Core.logger.log(
				NodeConfigUpdater.class,
				"Firing initial update for " + path + ", " + listener,
				Logger.DEBUG);
		StringTokenizer tok = new StringTokenizer(path, ".");
		String val = null;
		Params fs = oldParams;
		if (fs == null && logDEBUG)
			Core.logger.log(
				NodeConfigUpdater.class,
				"fs == null !",
				new Exception("grrr"),
				Logger.DEBUG);
		while ((fs != null) && (tok.hasMoreTokens())) {
			String tpath = tok.nextToken();
			if (logDEBUG)
				Core.logger.log(
					NodeConfigUpdater.class,
					"In while(), tok = " + tpath,
					Logger.DEBUG);
			Params newFs = (Params)fs.getSet(tpath);
			if (newFs != null)
				fs = newFs;
			else {
				val = fs.getString(tpath);
				break;
			}
		}
		if (logDEBUG)
			Core.logger.log(
				NodeConfigUpdater.class,
				"Out of while()",
				Logger.DEBUG);
		if (fs != null) {
			if (logDEBUG)
				Core.logger.log(
					NodeConfigUpdater.class,
					"fs != null",
					Logger.DEBUG);
			if (val == null)
				listener.configPropertyUpdated(path, fs);
			else
				listener.configPropertyUpdated(path, val);
		}
	}

	private static void fireUpdates(Params params) {
		boolean logDEBUG =
			Core.logger.shouldLog(Logger.DEBUG, NodeConfigUpdater.class);
		if (logDEBUG)
			Core.logger.log(
				NodeConfigUpdater.class,
				"Firing configuration updates with params " + params.toString(),
				Logger.DEBUG);
		for (Iterator iter = listeners.keySet().iterator(); iter.hasNext();) {
			String path = (String) iter.next();
			HashSet lsnrs = (HashSet) listeners.get(path);
			if (logDEBUG)
				Core.logger.log(
					NodeConfigUpdater.class,
					"Firing configuration updates for path "
						+ path
						+ " w/ "
						+ lsnrs.size()
						+ " listeners",
					Logger.DEBUG);
			StringTokenizer tok = new StringTokenizer(path, ".");
			String val = null;
			Params fs = params;
			while ((fs != null) && (tok.hasMoreTokens())) {
				String tpath = tok.nextToken();
				Params newFs = (Params)fs.getSet(tpath);
				if (newFs != null)
					fs = newFs;
				else {
					val = fs.getString(tpath);
					break;
				}
			}
			if (fs != null) {
				if (val == null) {
					for (Iterator fireIter = lsnrs.iterator();
						fireIter.hasNext();
						) {
						ConfigUpdateListener lsnr =
							(ConfigUpdateListener) fireIter.next();
						lsnr.configPropertyUpdated(path, fs);
					}
				} else {
					for (Iterator fireIter = lsnrs.iterator();
						fireIter.hasNext();
						) {
						ConfigUpdateListener lsnr =
							(ConfigUpdateListener) fireIter.next();
						lsnr.configPropertyUpdated(path, val);
					}
				}
			}
		}
	}

	public class ConfigOptions {

		/**
		 * Each config option that can be updated on-the-fly needs its own
		 * public method.
		 */

		public void configUpdateInterval() {
			int interval = newParams.getInt("configUpdateInterval");
			if (updateInterval == interval)
				return;
			updateInterval = interval;
			if (interval == 0)
				Core.logger.log(
					NodeConfigUpdater.class,
					"Disabled on-the-fly config updater.",
					Logger.NORMAL);
			else
				Core.logger.log(
					NodeConfigUpdater.class,
					"Changed interval to check for configuration updates to "
						+ interval
						+ " minutes.",
					Logger.NORMAL);
		}

		public void seednodesUpdateInterval() {
			int interval = newParams.getInt("seednodesUpdateInterval");
		if (SeednodesUpdater.getUpdateInterval()==interval)
			return;

		SeednodesUpdater.setUpdateInterval(interval);
		
		if (interval == 0)
			Core.logger.log(
				NodeConfigUpdater.class,
				"Disabled seednodes updater.",
				Logger.NORMAL);
		else
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed interval to check for seednodes updates to "
					+ interval
					+ " minutes.",
				Logger.NORMAL);
		
		}

		public void logLevel() {
			String logLevel = newParams.getString("logLevel");
			Core.logger.setThreshold(logLevel);
			if (Main.node.dir instanceof NativeFSDirectory)
				((NativeFSDirectory) Main.node.dir).logDEBUG =
					Core.logger.shouldLog(Logger.DEBUG, this);
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed logging level to " + logLevel + ".",
				Logger.NORMAL);
		}

		public void logLevelDetail() {
			String detailedLevels = newParams.getString("logLevelDetail");
			Core.logger.setDetailedThresholds(detailedLevels);
			if (Main.node.dir instanceof NativeFSDirectory)
				((NativeFSDirectory) Main.node.dir).logDEBUG =
					Core.logger.shouldLog(Logger.DEBUG, Main.node.dir);
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed logging level details to " + detailedLevels + ".",
				Logger.NORMAL);
		}

		public void aggressiveGC() {
			int interval = newParams.getInt("aggressiveGC");
			if (Node.aggressiveGC == interval)
				return;
			if ((Node.aggressiveGC <= 0) && (interval != 0)) {
				Node.aggressiveGC = interval;
				new Checkpoint(
					new Main.GarbageCollectionCheckpointed()).schedule(
					Main.node);
				Core.logger.log(
					NodeConfigUpdater.class,
					"Enabled aggressive garbage collection with a "
						+ interval
						+ " second interval.",
					Logger.NORMAL);
			} else {
				Node.aggressiveGC = interval;
				if (interval == 0)
					Core.logger.log(
						NodeConfigUpdater.class,
						"Disabled aggressive garbage collection.",
						Logger.NORMAL);
				else
					Core.logger.log(
						NodeConfigUpdater.class,
						"Changed aggressive garbage collection interval to "
							+ interval
							+ " seconds.",
						Logger.NORMAL);
			}
		}

		public void requestDelayCutoff() {
			int n = newParams.getInt("requestDelayCutoff");
			if (Node.requestDelayCutoff == n)
				return;
			Node.requestDelayCutoff = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed requestDelayCutoff to " + n + ".",
				Logger.NORMAL);
		}

		public void successfulDelayCutoff() {
			int n = newParams.getInt("successfulDelayCutoff");
			if (Node.successfulDelayCutoff == n)
				return;
			Node.successfulDelayCutoff = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed successfulDelayCutoff to " + n + ".",
				Logger.NORMAL);
		}

		public void requestSendTimeCutoff() {
			int n = newParams.getInt("requestSendTimeCutoff");
			if (Node.requestSendTimeCutoff == n)
				return;
			Node.requestSendTimeCutoff = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed requestSendTimeCutoff to " + n + ".",
				Logger.NORMAL);
		}

		public void successfulSendTimeCutoff() {
			int n = newParams.getInt("successfulSendTimeCutoff");
			if (Node.successfulSendTimeCutoff == n)
				return;
			Node.successfulSendTimeCutoff = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed successfulSendTimeCutoff to " + n + ".",
				Logger.NORMAL);
		}

		public void inputBandwidthLimit() {
			int n = newParams.getInt("inputBandwidthLimit");
			if (Node.inputBandwidthLimit == n)
				return;
			Node.inputBandwidthLimit = n;
			if (Node.doLowLevelInputLimiting) {
				Node.ibw =
					n == 0
						? null
						: new Bandwidth(
							(int) (n * Node.lowLevelBWLimitMultiplier),
							(int) (Node.averageInputBandwidthLimit
								* Node.lowLevelBWLimitMultiplier),
							Bandwidth.RECEIVED);
			} else
				Node.ibw = null;
			try {
				tcpConnection.setInputBandwidth(Node.ibw);
			} catch (Exception e) {
			}
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed inputBandwidthLimit to " + n + ".",
				Logger.NORMAL);
		}

		public void outputBandwidthLimit() {
			int n = newParams.getInt("outputBandwidthLimit");
			if (Node.outputBandwidthLimit == n)
				return;
			Node.outputBandwidthLimit = n;
			if (Node.doLowLevelOutputLimiting) {
				Node.obw =
					n == 0
						? null
						: new Bandwidth(
							(int) (n
								* Node.lowLevelBWLimitMultiplier),
							(int) (Node.averageOutputBandwidthLimit
								* Node.lowLevelBWLimitMultiplier),
							Bandwidth.SENT);
			} else
				Node.obw = null;
			try {
				tcpConnection.setOutputBandwidth(Node.obw);
			} catch (Exception e) {
			}
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed outputBandwidthLimit to " + n + ".",
				Logger.NORMAL);
		}

		public void initialRequestHTL() {
			int n = newParams.getInt("initialRequestHTL");
			if (Node.initialRequestHTL == n)
				return;
			Node.initialRequestHTL = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed initialRequestHTL to " + n + ".",
				Logger.NORMAL);
		}

		public void initialRequests() {
			int n = newParams.getInt("initialRequests");
			if (Node.initialRequests == n)
				return;
			Node.initialRequests = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed initialRequests to " + n + ".",
				Logger.NORMAL);
		}

		public void announcementAttempts() {
			int n = newParams.getInt("announcementAttempts");
			if (Node.announcementAttempts == n)
				return;
			Node.announcementAttempts = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed announcementAttempts to " + n + ".",
				Logger.NORMAL);
		}

		public void announcementThreads() {
			int n = newParams.getInt("announcementThreads");
			if (Node.announcementThreads == n)
				return;
			Node.announcementThreads = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed announcementThreads to " + n + ".",
				Logger.NORMAL);
		}

		public void maxHopsToLive() {
			int n = newParams.getInt("maxHopsToLive");
			if (Node.maxHopsToLive == n)
				return;
			Node.maxHopsToLive = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed maxHopsToLive to " + n + ".",
				Logger.NORMAL);
		}

		public void newNodePollInterval() {
			int n = newParams.getInt("newNodePollInterval");
			if (Node.newNodePollInterval == n)
				return;
			Node.newNodePollInterval = n;
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed newNodePollInterval to " + n + ".",
				Logger.NORMAL);
		}

		public void targetMaxThreads() {
			int n = newParams.getInt("targetMaxThreads");
			if (n < 0) {
				n = Node.maxThreads;
			}
			if (Node.targetMaxThreads == n)
				return;
			Node.targetMaxThreads = n;
			Node.threadFactory.configUpdater(
				Node.targetMaxThreads,
				Node.tfTolerableQueueDelay,
				Node.tfAbsoluteMaxThreads);
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed targetMaxThreads to " + n + ".",
				Logger.NORMAL);
		}

		public void tfTolerableQueueDelay() {
			int n = newParams.getInt("tfTolerableQueueDelay");
			if (Node.tfTolerableQueueDelay == n)
				return;
			Node.tfTolerableQueueDelay = n;
			Node.threadFactory.configUpdater(
				Node.targetMaxThreads,
				Node.tfTolerableQueueDelay,
				Node.tfAbsoluteMaxThreads);
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed tfTolerableQueueDelay to " + n + ".",
				Logger.NORMAL);
		}

		public void tfAbsoluteMaxThreads() {
			int n = newParams.getInt("tfAbsoluteMaxThreads");
			if (n <= 0) {
				n = 1000000;
			}
			if (Node.tfAbsoluteMaxThreads == n)
				return;
			Node.tfAbsoluteMaxThreads = n;
			Node.threadFactory.configUpdater(
				Node.targetMaxThreads,
				Node.tfTolerableQueueDelay,
				Node.tfAbsoluteMaxThreads);
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed tfAbsoluteMaxThreads to " + n + ".",
				Logger.NORMAL);
		}
		
		public void maxOpenConnectionsNewbieFraction() {
			double n = newParams.getDouble("maxOpenConnectionsNewbieFraction");
			if (Node.maxOpenConnectionsNewbieFraction == n)
				return;
			Node.maxOpenConnectionsNewbieFraction = n;
			Main.node.connections.updateMaxNewbieFraction(n);
			Core.logger.log(
				NodeConfigUpdater.class,
				"Changed maxOpenConnectionsNewbieFraction to " + n + ".",
				Logger.NORMAL);
		}
	}
}
