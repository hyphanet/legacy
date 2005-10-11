package freenet.node.rt;

import freenet.Core;
import freenet.Identity;
import freenet.node.NodeReference;
import freenet.node.BadReferenceException;
import freenet.fs.dir.*;
import freenet.support.*;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.io.IOException;

/**
 * Uses a pair of DataObjectStores to hold node refs and node properties needed
 * to implement RoutingStore.
 * 
 * @author tavin
 */
public class DataObjectRoutingStore implements RoutingStore, Checkpointed {

	final DataObjectStore rtNodes, rtProps;

	private int count = 0; // number of nodes TODO: Expose the size from rtNodes insetad!
	
	//Used to speed up Identity-to-FileNumber building  
	CachingFileNumberBuilder fileNumberBuilder = new CachingFileNumberBuilder(10000,true,true); //To speed up the creation of FileNumber Objects

	public DataObjectRoutingStore(
		DataObjectStore rtNodes,
		DataObjectStore rtProps) {
		this.rtNodes = rtNodes;
		this.rtProps = rtProps;
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		removeOrphans();
		// initial count: number of node file entries
		Enumeration rme = rtNodes.keys(true);
		while (rme.hasMoreElements()) {
			++count;
			rme.nextElement();
		}
	}

	private static boolean logDebug = true;

	private final void removeOrphans() {
		// Must not have orphaned nodes or even worse orphaned props
		for (Enumeration e = rtNodes.keys(true); e.hasMoreElements();) {
			FileNumber fn = (FileNumber) (e.nextElement());
			Enumeration props = rtProps.keys(new PrefixFilePattern(fn, true));
			if (props == null || !props.hasMoreElements()) {
				Core.logger.log(
					this,
					"Removing orphaned node " + fn.toString(),
					Logger.NORMAL);
				rtNodes.remove(fn);
			}
		}
		for (Enumeration e = rtProps.keys(true); e.hasMoreElements();) {
			FileNumber fn = (FileNumber) (e.nextElement());
			boolean gotIt = false;
			Enumeration ee = rtNodes.keys(fn, false);
			if (ee == null || !ee.hasMoreElements()) {
				if (logDebug)
					Core.logger.log(
						this,
						"No nodes found looking for property...",
						Logger.DEBUG);
			} else {
				FileNumber f = (FileNumber) ee.nextElement();
				FilePattern p = new PrefixFilePattern(f, true);
				if (p.matches(fn)) {
					gotIt = true;
				} else {
					if (logDebug) {
						Core.logger.log(
							this,
							"Pattern does not match: "
								+ f.toString()
								+ " should be prefix of "
								+ fn.toString(),
							Logger.DEBUG);
					}
				}
			}
			if (!gotIt) {
				Core.logger.log(
					this,
					"Removing orphaned property " + fn.toString(),
					Logger.NORMAL);
				rtProps.remove(fn);
			}
		}
	}

	public final String getCheckpointName() {
		return "Saving routing table changes.";
	}

	public final long nextCheckpoint() {
		return System.currentTimeMillis() + 1000 * 300; // 5 minutes from now
	}

	public synchronized final void checkpoint() {
		// There might be a remove or something going on in parallel
		// We need to serialize out a consistent state
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDebug)
			Core.logger.log(
				this,
				"DataObjectRoutingStore checkpointing",
				new Exception("debug"),
				Logger.DEBUG);
		try {
			rtNodes.flush();
			rtProps.flush();
		} catch (IOException e) {
			// hmz..
			Core.logger.log(
				this,
				"I/O error flushing routing table data",
				e,
				Logger.ERROR);
		}
		if (logDebug)
			Core.logger.log(
				this,
				"DataObjectRoutingStore checkpointed",
				Logger.DEBUG);
	}

	// Don't need to synchronize
	public final int size() {
		return count;
	}

	public boolean remove(Identity ident) {
		FileNumber fn = fileNumberBuilder.build(ident);
		if (logDebug)
			Core.logger.log(
				this,
				"Removing identity " + ident.fingerprint(),
				Logger.DEBUG);
		boolean bResult =  remove(fn);
		if (bResult) {
			if (logDebug)
				Core.logger.log(this, "Removed identity " + ident.fingerprint().toString(), Logger.DEBUG);
		} else {
			if (logDebug)
				Core.logger.log(this, "Failed removing identity " + ident.fingerprint().toString(), Logger.DEBUG);
		}
		return bResult;
	}

	protected synchronized boolean remove(FileNumber fn) {
		if (rtNodes.remove(fn)) {
			if (logDebug)
				Core.logger.log(this, "Removed from rtNodes", Logger.DEBUG);
			--count;

			// clear out properties entries
			Enumeration pk = rtProps.keys(new PrefixFilePattern(fn, true));
			while (pk.hasMoreElements()) {
				fn = (FileNumber) pk.nextElement();
				Core.logger.log(
					this,
					"Removing " + fn + " from rtProps",
					Logger.DEBUG);
				rtProps.remove(fn);
				Core.logger.log(
					this,
					"Removed " + fn + " from rtProps",
					Logger.DEBUG);
			}

			
			return true;
		}
		
		return false;
	}
	public final boolean contains(Identity ident) {
		return rtNodes.contains(fileNumberBuilder.build(ident));
	}

	public synchronized final Enumeration elements() {
		return new RoutingMemoryEnumeration(rtNodes.keys(true));
	}

	private final class RoutingMemoryEnumeration implements Enumeration {

		private final Enumeration keys;
		private Object next;

		RoutingMemoryEnumeration(Enumeration keys) {
			this.keys = keys;
			next = step();
		}

		private Object step() {
			while (keys.hasMoreElements()) {
				FileNumber fn = (FileNumber) keys.nextElement();
				Object o = getNode(fn);
				if (o != null)
					return o;
			}
			return null;
		}

		public final boolean hasMoreElements() {
			return next != null;
		}

		public final Object nextElement() {
			if (next == null)
				throw new NoSuchElementException();
			try {
				return next;
			} finally {
				next = step();
			}
		}
	}

	private RoutingMemory getNode(FileNumber fn) {
		try {
				return (DataObjectRoutingMemory) rtNodes.get(fn);
		} catch (DataObjectUnloadedException dop) {
			try {
				return new DataObjectRoutingMemory(this, dop);
			} catch (BadReferenceException e) {
				Core.logger.log(this, "bad reference while resolving: " + fn, e, Logger.ERROR);
				boolean bResult = remove(fn);
				if (bResult) {
					if (logDebug)
						Core.logger.log(this, "Removed identity " + "FileNumber: " + fn.toString(), Logger.DEBUG);
				} else {
					if (logDebug)
						Core.logger.log(this, "Failed removing identity " + "FileNumber: " + fn.toString(), Logger.DEBUG);
				}
				return null;
			} catch (IOException e) {
				Core.logger.log(this, "I/O error while resolving: " + fn, e, Logger.ERROR);
				boolean bResult = remove(fn);
				if (bResult) {
					if (logDebug)
						Core.logger.log(this, "Removed identity " + "FileNumber: " + fn.toString(), Logger.DEBUG);
				} else {
					if (logDebug)
						Core.logger.log(this, "Failed removing identity " + "FileNumber: " + fn.toString(), Logger.DEBUG);
				}
				return null;
			}
		}
	}

	public final RoutingMemory getNode(Identity ident) {
		if (ident == null)
			throw new IllegalArgumentException("null");
		return getNode(fileNumberBuilder.build(ident));
	}

	public RoutingMemory putNode(Identity ident, NodeReference nr) {
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDebug)
			Core.logger.log(this, "Adding node to DataObjectRoutingStore", Logger.DEBUG);
		if(ident == null) ident = nr.getIdentity();
		FileNumber fn = fileNumberBuilder.build(ident);

		//TODO: This method performs 3 distinct lookups in the same rtNodes store. Optimize.
		boolean extant = rtNodes.contains(fn);

		DataObjectRoutingMemory mem;

		synchronized (this) {
			mem = (DataObjectRoutingMemory) getNode(fn);

			if (mem == null)
				mem = new DataObjectRoutingMemory(this, ident, nr);
			else if (mem.noderef == null || nr.supersedes(mem.noderef))
				mem.noderef = nr;

			if (logDebug)
				Core.logger.log(this, "About to add node to rtNodes: " + nr, Logger.DEBUG);
			rtNodes.set(fn, mem);
			if (logDebug)
				Core.logger.log(this, "Added node to rtNodes: " + nr, Logger.DEBUG);

			if (!extant)
				++count;
		}

		if (logDebug)
			Core.logger.log(this, "Added node to DataObjectRoutingStore: " + nr, Logger.DEBUG);

		return mem;
	}
}
