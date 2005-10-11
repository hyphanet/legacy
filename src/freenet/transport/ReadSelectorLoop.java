package freenet.transport;
//QUESTION: does this belong in this package?

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import freenet.diagnostics.ExternalContinuous;
import freenet.diagnostics.ExternalCounting;
import freenet.support.BooleanCallback;
import freenet.support.Logger;
import freenet.support.io.Bandwidth;

/**
 * A loop that reads and processes message headers. for now this is just the
 * code I had written before taken to its own class. Parsing of fieldsets is
 * yet to be implemented.
 */
public final class ReadSelectorLoop extends ThrottledSelectorLoop implements ThrottledAsyncTCPReadManager {

	protected Map bufferMap=new HashMap(MAX_CONC_CHANNELS);
	// TODO: contemplate the miniscule probability that a weak map would be
	// better here.

	private final LinkedList maintenanceQueue=new LinkedList();
	
	private final ExternalCounting logBytes;
	private final ExternalCounting readinessSelectionScrewed;
	private final ExternalCounting connectionResetByPeer;
	/** Whether we should check the time taken by a process() */
	private final BooleanCallback loadCheck;
	private final boolean logInputBytes;

	private volatile long totalReadThrottlableBytes = 0;
	private volatile long totalReadPseudoThrottlableBytes = 0;

	private class MaintenancePair {
		NIOReader attachment;
		SocketChannel chan;
		MaintenancePair(NIOReader reader, SocketChannel sc) {
			this.attachment = reader;
			this.chan = sc;
		}
	}

	/**
	 * this is the maximum number of channels that can be active at any single
	 * atom of time. How much is an atom? As small as it can be I guess. NOTE:
	 * since this code will be entered a lot, I'd rather allocate more memory
	 * needed on startup rather than do it on every entry. This and the above
	 * parameters need to be fine tuned, because they will practically
	 * MAX_CONC_CHANNELS memory, and in most cases less than 10% of that
	 */
	protected static final int MAX_CONC_CHANNELS = 20;

	/**
	 * Create a ReadSelectorLoop. This will create and start a thread for
	 * it to run on.
	 * @param logger a Logger to log any interesting events to.
	 * @param closePairLifetimeCallback A diagnostic variable to write the lifetime
	 * of close pairs to - meaning the time it takes between a request to close
	 * a channel being made and it being completed.
	 * @param logBytes Whether to log the number of bytes read to logBytes.
	 * @param readinessSelectionScrewed A diagnostic value for an unusual event:
	 * an event is logged when something breaks and the selector indicates that
	 * a channel is readable but we manage to read no bytes from it. 
	 * @param connectionResetByPeer A diagnostic variable that will be written 
	 * every time a read fails due to the error "Connection reset by peer". 
	 * @param loadCheck A BooleanCallback that tells us whether to check the 
	 * length of time taken by a read being processed. If this returns false,
	 * we don't check, because there is a known problem causing these delays.
	 * @param logInputBytes Diagnostic variable to log the number of bytes read
	 * to.
	 * @param bw Bandwidth limiter object to limit our incoming bytes.
	 * @param timerGranularity estimated minimum delta in System.currentTimeMillis().
	 * Used mainly by bandwidth limiting.
	 * @throws IOException
	 */
	public ReadSelectorLoop(Logger logger, ExternalContinuous closePairLifetimeCallback,
	        ExternalCounting logBytes, ExternalCounting readinessSelectionScrewed,
	        ExternalCounting connectionResetByPeer, BooleanCallback loadCheck,
	        boolean logInputBytes, Bandwidth bw, int timerGranularity)
		throws IOException {

		super(logger, closePairLifetimeCallback, bw, timerGranularity);
		this.logInputBytes = logInputBytes;
		this.logBytes = logBytes;
		this.readinessSelectionScrewed = readinessSelectionScrewed;
		this.connectionResetByPeer = connectionResetByPeer;
		this.loadCheck = loadCheck;
	}

	public ReadSelectorLoop(Logger logger, ExternalContinuous closePairLifetimeCallback,
	        ExternalCounting logBytes, ExternalCounting readinessSelectionScrewed,
	        ExternalCounting connectionResetByPeer, BooleanCallback loadCheck,
	        boolean logInputBytes) 
		throws IOException {

		super(logger, closePairLifetimeCallback);
		this.logBytes = logBytes;
		this.logInputBytes = logInputBytes;
		this.readinessSelectionScrewed = readinessSelectionScrewed;
		this.connectionResetByPeer = connectionResetByPeer;
		this.loadCheck = loadCheck;
	}

	/**
	 * resets the buffers to prepare for the next loop
	 */
	//     private final void resetBuffers() {
	//         Iterator i = bufferMap.entrySet().iterator();
	//         while(i.hasNext()) {
	//             Map.Entry e = (Map.Entry)(i.next());
	//             ByteBuffer current = (ByteBuffer)e.getValue();
	//             current.clear();
	//             i.remove();
	//         }
	//         //this should not be necessary, but lets do it just in case
	//         bufferMap.clear();
	//     }

	/**
	 * override this to clean up the buffers and do bandwith throttling
	 */
	protected final void beforeSelect() {

		//empty the buffers
		//    resetBuffers();
		bufferMap.clear();

		// Cancel keys etc, for throttleBeforeSelect
		boolean success = false;
		while (!success) {
			try {
				success = mySelect(0);
			} catch (IOException e) {
				logger.log(this, "selectNow() failed in RSL.beforeSelect(): " + e, e, Logger.ERROR);
			}
		}
		//make sure no message got stuck behind a trailing field

		if (logDebug)
			logger.log(this, "beforeSelect(), mq.size=" + maintenanceQueue.size(), Logger.DEBUG);

		while (maintenanceQueue.size() > 0) {
			MaintenancePair mp;
			synchronized (maintenanceQueue) {
				mp = (MaintenancePair) maintenanceQueue.removeFirst();
			}
			SocketChannel chan = mp.chan;
			NIOReader current = mp.attachment;
			SelectionKey k = chan.keyFor(sel);
			//process could take a while, so lock just this
			try {
				ByteBuffer buf = current.getBuf();
				int status = buf == null ? -1 : 
					current.process(buf);
				if (logDebug)
					logger.log(this, "Running maintenance on " + chan + ":" + current + ", returned " + status,
							Logger.DEBUG);
				if (status == -1) {
					if (logDebug)
						logger.log(this, "Closing connection " + chan + ":" + current + " (process returned -1)",
								Logger.DEBUG);
					if (k != null) {
						k.attach(null);
						k.cancel();
						current.unregistered();
					} else {
						if (logDebug)
							logger.log(this,
								"Maintenance process returned -1 but not registered on selector, queuing for unregistration",
								Logger.DEBUG);
						unregisterWaiters.add(
							new ChannelAttachmentPair(chan, current));
					}
					queueClose(chan, current);
				} else if (status == 0) {
					if (logDebug)
						logger.log(this, "Cancelling " + chan + ":" + current + "(returned 0)", Logger.DEBUG);
					if (k != null) {
						k.cancel();
					} else {
						if (logDebug)
							logger.log(this,
								"Maintenance process returned 0 but not registered on selector, queuing for unregistration",
								Logger.DEBUG);
						unregisterWaiters.add(
							new ChannelAttachmentPair(chan, current));
					}
					dontReregister.add(chan);
					if (logDebug)
						logger.log(this, "Cancelled " + chan + ": "
								+ (k == null ? "(null)" : Boolean.toString(k.isValid())) + ":" + k, Logger.DEBUG);
				}
			} catch (OutOfMemoryError e) {
				System.gc();
				System.runFinalization();
				System.gc();
				System.runFinalization();
				freenet.node.Main.dumpInterestingObjects();
				try {
					logger.log(this, "Ran emergency GC in " + getClass().getName(), Logger.ERROR);
				} catch (Throwable any) {
				}
			} catch (Throwable t) {
				System.err.println(
					"Caught " + t + " running maintenance queue");
				t.printStackTrace(System.err);
				logger.log(this, "Caught " + t + " running maintenance queue", t, Logger.ERROR);
			}
		}
		throttleBeforeSelect();
	}

	public final void scheduleMaintenance(
		SocketChannel cb,
		NIOReader attachment) {
		if (logDebug)
			logger.log(this,
				"Scheduling maintenance on " + cb + ":" + attachment,
				new Exception("debug"),Logger.DEBUG);
		synchronized (maintenanceQueue) {
			maintenanceQueue.addLast(new MaintenancePair(attachment, cb));
		}
	}

	/**
	 * a screwed channel is marked as available for read, but a read() call on
	 * it returns -1. Therefore...
	 */
	protected final boolean inspectChannels(Set candidates) {
		int screwedSelections = 0;
		//assume currentSet has been initialized

		boolean noneWorking = true;

		int throttledBytesRead = 0;
		int bytesRead = 0;
		int pseudoThrottledBytesRead = 0;
		int j = 0;
		boolean noThrottled =
			(System.currentTimeMillis() < reregisterThrottledTime);

		Iterator i = candidates.iterator();
		while (i.hasNext()) {
			SelectionKey current = (SelectionKey) i.next();
			if(logDebug)
			    logger.log(this, "checking " + current + " : " + current.channel(), Logger.DEBUG);
			//if (!(current.isValid() && current.isReadable() &&
			// current.channel().isOpen())) continue;
			NIOReader nc = (NIOReader) current.attachment();
			if (nc == null) {
			    if(logDebug)
			        logger.log(this, "attachment is null :( : " + current, Logger.DEBUG);
			    continue;
			}
			
			ByteBuffer bumper = nc.getBuf();
			
			SocketChannel sc = (SocketChannel) current.channel();
			if (logDebug) logger.log(this," checking channel"+
			        sc.toString(),Logger.DEBUG);
			int size = 0;
			boolean shouldThrottle = nc.shouldThrottle();
			try {
				synchronized (bumper) {
					if (!current.isValid()) {
						if (logDebug)
							logger.log(this, "Channel invalid: " + bumper + ":" + sc + ":" + nc + " (" + iteration
									+ ")", Logger.DEBUG);
						size = 0;
						continue;
					} else if (noThrottled && shouldThrottle) {
						if (logDebug)
							logger.log(this, "Ignored throttled " + current, Logger.DEBUG);
						// It will be put back onto the throttled queue later
						// No need to cancel
						size = 0;
						continue; //<-- if all that are readable are
						// throttled, we effectively cause the thread to sleep
						// --zab
					} else if (bumper.limit() == bumper.capacity()) {
						logger.log(this, "BUFFER FULL (" + current + ":" + current.isValid() + ") for " + bumper + ":"
								+ sc + ":" + nc + " (" + iteration + ")", Logger.ERROR);
						noneWorking = false;
						size = 0;
					} else if (!current.isReadable()) {
						if (logDebug)
							logger.log(this, "Not readable: " + current, Logger.DEBUG);
						size = 0;
					} else {
						if (logDebug)
							logBumper(bumper, sc, nc, 0);
						bumper.position(bumper.limit());
						if (logDebug)
							logBumper(bumper, sc, nc, 1);
						bumper.limit(bumper.capacity());
						if (logDebug)
							logBumper(bumper, sc, nc, 2);
						try {
							try{
								size = sc.read(bumper);
							}catch(OutOfMemoryError e){
								//TODO: Remove this when not needed anymore
								StackTraceElement[] t = e.getStackTrace();
								if (t.length > 0
									&& (t[0].getMethodName().equalsIgnoreCase("reserveMemory"))
									&& t[0].getClassName().equalsIgnoreCase("java.nio.Bits")){
									//Deduced by reading the sun JVM source
									String s = "Got OOM from sc.read(), probably tried to allocate "+bumper.remaining()+" bytes of temporary direct memory";
									logger.log(this,s,Logger.ERROR);
									System.err.println(s);
								}
								throw e;
							}
							if (size == 0) {
							    this.readinessSelectionScrewed.count(1);
								bumper.flip();
								screwedSelections++;
								i.remove();
								//noneWorking=false; //this isn't really a dud
								// either
								//and guess what happens - rsl starts sleeping
								// 10 to 20 times a second
								//which makes tons of system calls and the sys
								// cpu usage stays up steady
								//actually it happens even if this is set to
								// false
								// Yes it is. That's the whole point of duds!
								// If readiness selection is returning each
								// time,
								// we sleep a bit to prevent it causing 100%
								// CPU usage
								continue;
							}
						} catch (ClosedChannelException e) {
							if (logDebug)
								logger.log(this, "Channel closed: " + e + " for " + current, e, Logger.DEBUG);
							noneWorking = false;
							SelectionKey k = sc.keyFor(sel);
							k.attach(null);
							k.cancel();
							queueClose(sc, nc);
							nc.unregistered(); // see below re sequence
							continue;
						} catch (IOException e) {
							int prio = Logger.MINOR;
							if ((e.getMessage() != null)
								&& ((e.getMessage().indexOf("pipe") >= 0)
									|| (e.getMessage().indexOf("peer") >= 0)
									|| (e.getMessage().indexOf("timed out")
										>= 0))) {
								if (e
									.getMessage()
									.trim()
									.equalsIgnoreCase("Connection reset by peer"))
								    this.connectionResetByPeer.count(1);
								prio = Logger.DEBUG;
							}
							if (prio != Logger.DEBUG || logDebug)
								logger.log(this, "IOException processing " + nc + ": " + e + ", bytes read: " + size,
										e, prio);
							logBumper(bumper, sc, nc, 3);
							size = -1;
						}
						if (logDebug)
							logBumper(bumper, sc, nc, 4);
						bumper.flip();
						if (logDebug)
							logBumper(bumper, sc, nc, 5);
					}
				}
			} catch (Throwable e) {
				try {
					logger.log(this, "Unexpected throwable reading data for " + nc + ": " + e, e, Logger.NORMAL);
					e.printStackTrace();
				} catch (Throwable t) {
					logger.log(this, "Unexpected throwable reading data: " + e, e, Logger.NORMAL);
				}
				size = -1; // try unregistering it...
			}
			if (size == -1) {
				//TODO: log that this channel got closed.
				// and mark its Connection object as closed. (that'll be
				// tricky)
				if (logDebug)
					logger.log(this, "Closed (read -1): " + current, Logger.DEBUG);
				SelectionKey k = sc.keyFor(sel);
				k.attach(null);
				k.cancel();

				// queueClose BEFORE notifying unregistration.
				// So that if we wait on unregistration, and then check
				// closure,
				// we get the right sane answer.
				queueClose(sc, nc); 
				nc.unregistered();
				noneWorking = false;
			} else if (size > 0 || (bumper.limit() > 0)) {
				//do not call process() if nothing was read
				//this actually is an error in fixkeys
				if (size > 0) {
					bytesRead += (size + OVERHEAD);
					if (shouldThrottle) {
					    if(logBytes != null && logInputBytes)
					        logBytes.count(size+OVERHEAD);
					    throttledBytesRead += (size + OVERHEAD);
						totalReadThrottlableBytes += (size + OVERHEAD);
					} else if (nc.countAsThrottled()) {
						totalReadPseudoThrottlableBytes += (size + OVERHEAD);
						pseudoThrottledBytesRead += (size + OVERHEAD);
					}
				}
				if (logDebug)
					logger.log(this, "putting " + sc + ":" + bumper + " on bufferMap:" + size + ":" + (bumper.limit()),
							Logger.DEBUG);
				bufferMap.put(sc, bumper);
				noneWorking = false;
			}

			/*
			 * if(size == 0) { //this is happening waaay too often
			 * logger.log(this, "Readiness selection screwed - 0 byte "+
			 * "read: "+current+":"+sc+":"+nc, Logger.MINOR);
			 */
			/*
			 * else ok so fixkeys is screwed somehow...
			 */
			j++;
		} //they *should* throw exceptions, but they don't. just in case, lets
		// print.
		if (bw != null) {
			throttleConnections(
				bytesRead,
				throttledBytesRead,
				pseudoThrottledBytesRead);
		}
		if (logDebug)
			logger.log(this, " number of false positives " + screwedSelections, Logger.DEBUG);
		return noneWorking;
	}

	static Object staticIterationLock = new Object();
	static int staticIteration = 0;
	int iteration = 0;

	private final void logBumper(
		ByteBuffer bumper,
		SelectableChannel chan,
		NIOReader nc,
		int stage) {
		synchronized (staticIterationLock) {
			iteration++;
			staticIteration++;
			if (logDebug)
				logger.log(this, "Reading (" + stage + ") for " + nc + " : " + chan + ": " + bumper + ":("
						+ bumper.position() + "/" + bumper.limit() + "/" + bumper.capacity() + ") - iter " + iteration
						+ ":" + staticIteration, Logger.DEBUG);
		}
	}

	/**
	 * this is where we parse the fieldsets will finish it later
	 */
	protected final boolean processConnections(Set currentSet) {
		boolean success = true;
		try {
			Iterator i = bufferMap.keySet().iterator();
			while (i.hasNext()) {
				SocketChannel chan = (SocketChannel) i.next();
				if (logDebug)
					logger.log(this, "processConnections: " + chan, Logger.DEBUG);
				SelectionKey k = chan.keyFor(sel);
				if (k == null) {
					logger.log(this, "null key for " + chan, Logger.NORMAL);
					continue;
				}
				NIOReader nc = (NIOReader) (k.attachment());
				int status = 1;
				try {
				    long startTime = System.currentTimeMillis();
					status = nc.process(nc.getBuf());
					if(loadCheck.value()) {
					    long endTime = System.currentTimeMillis();
					    long length = endTime - startTime;
					    logger.log(this, "Processing "+nc+" took "+length,
					            length > 1000 ? Logger.NORMAL : Logger.MINOR);
					}
				} catch (Throwable t) {
					logger.log(this, "Caught throwable " + t + " processing " + nc, t, Logger.NORMAL);
					status = -1;
				}
				if (logDebug)
					logger.log(this, "" + chan + ":" + nc + " returned " + status, Logger.DEBUG);
				if (status == -1) {
					if (logDebug)
						logger.log(this, "Closing connection " + chan + ":" + nc + " (process returned -1)",
								Logger.DEBUG);
					k.attach(null);
					k.cancel();
					nc.unregistered();
					queueClose(chan, nc);
				} else if (status == 0) {
					if (logDebug)
						logger.log(this, "Cancelling " + nc + " (returned 0)", Logger.DEBUG);
					k.cancel();
					synchronized (dontReregister) {
						dontReregister.add(chan);
					}
					if (logDebug)
						logger.log(this, "Cancelled " + nc + ": " + k.isValid() + ": " + k, Logger.DEBUG);
				} else {
					// Good
				}
			}
		} catch (Throwable t) {
			t.printStackTrace();
			success = false;
			logger.log(this, "Something broke in RSL.processConnections(): " + t, t, Logger.ERROR);
		}

		return success;
	}

	protected final int myKeyOps() {
		return SelectionKey.OP_READ;
	}

	/**
	 * the run method. This is the place to add more init stuff NOTE: perhaps
	 * we want to catch exceptions here. But for now I like to print everything
	 * on stderr.
	 */
	public final void run() {
		loop();
	}

	//TODO: need to decide what happens when we close the selector this way.
	public final void close() {
		closeCloseThread();
	}
	public long getTotalTransferedThrottlableBytes() {
		return totalReadThrottlableBytes;
	}

	public long getTotalTransferedPseudoThrottlableBytes() {
		return totalReadPseudoThrottlableBytes;
	}
}
