/*
 * Created on 10.1.2004
 */
package freenet.node;

import java.io.File;
import java.io.IOException;

import freenet.Core;
import freenet.node.rt.RoutingTable;
import freenet.support.Checkpointed;
import freenet.support.Logger;

/** periodically checks the seednodes file for updates and adds any new nodes to the routing table
 * @author hirvox
 */
public class SeednodesUpdater implements Checkpointed {

	/** how often the seednodes file is scanned for changes (in minutes) */
	private static int updateInterval;
	private RoutingTable rt;
	long lastModified;
	File seedFile;

	public SeednodesUpdater(int updateInterval, RoutingTable rt) {
		SeednodesUpdater.updateInterval = updateInterval;

		//Assume..the seednodes are up to date now..
		//If this wasn't done we would *always* update the seednodes
		//at least once....
		lastModified = System.currentTimeMillis(); 
		this.rt = rt;
	}

	public String getCheckpointName() {
		return "Seednodes updater";
	}

	public long nextCheckpoint() {
		if (updateInterval == 0)
			return -1;
		return System.currentTimeMillis() + updateInterval * 60000;
	}

	public void checkpoint() {
		seedFile = new File(Node.seedFile);
		if (seedFile.exists() && seedFile.lastModified() > lastModified) {
			lastModified = seedFile.lastModified();
			try {
			    Main.reseed(seedFile, false, true);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"Seednodes read failed from " + Node.seedFile,
					e,
					Logger.ERROR);
			}
		}

	}
	/**
	 * @return
	 */
	static public int getUpdateInterval() {
		return updateInterval;
	}

	/**
	 * @param i
	 */
	static public void setUpdateInterval(int i) {
		updateInterval = i;
	}

}