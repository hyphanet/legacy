package freenet.transport;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Vector;

import freenet.Connection;
import freenet.SelectorLoop;
import freenet.diagnostics.ExternalContinuous;
import freenet.support.Logger;

/**
 * An abstract SelectorLoop. Subclasses will be the interface selector and the
 * polling selector. In this implementation, all the register/unregister
 * methods are to be called from a different thread.
 */
public abstract class AbstractSelectorLoop implements SelectorLoop{

	//protected LinkedList registerWaiters, unregisterWaiters, delayedWaiters;
	protected ChannelAttachmentPairQueue registerWaiters =
		new ChannelAttachmentPairQueue();
	protected ChannelAttachmentPairQueue unregisterWaiters =
		new ChannelAttachmentPairQueue();
	protected ChannelAttachmentPairQueue delayedWaiters =
		new ChannelAttachmentPairQueue();
	protected Selector sel;
	
	protected ByteBuffer []buffers;
	private int consecutiveWindowsBugEncounters = 0;
	//Keep track of how many consecutive windowsBugs we have encountered
	
	protected final Logger logger;
	
	private static CloseQueue closeQueue;
	private final static Object closeQueueLockObject = new Object();
	//necessary cause we change closeQueue
	
	private static boolean isWindows =
		(System.getProperty("os.name").toLowerCase().indexOf("windows") != -1);
	
	private static CloseThread closeThread= null;
	
	protected static boolean logDebug = true;
	
	public static final int closeUniquenessLength() {
		return closeQueue.closeUniquenessLength();
	}
	
	public static final int closeQueueLength() {
		return closeQueue.closeQueueLength();
	}
	
	/**
	 * Checks if the supplied NPE is the infamous windows 'select'-bug. Lets
	 * the NPE pass through if it isn't
	 */
	protected final void filterWindowsSelectBug(NullPointerException e)
		throws NullPointerException {
		if (!isWindows)
			throw e;
		StackTraceElement[] t = e.getStackTrace();
		if (t.length > 0
			&& (t[0].getMethodName().equals("processFdSet")
				|| t[0].getMethodName().equals("processFDSet"))
			&& t[0].getClassName().equals(
				"sun.nio.ch.WindowsSelectorImpl$SubSelector")) //This seems to
			// be the case
			// for the
			// particular
			// exception we
			// are hunting
			// for. Should
			// we maybe tie
			// this to
			// windows OS
			// only?
		{
			int iwaitTime = 50;
			if (consecutiveWindowsBugEncounters > 500
				&& isWindows) { //If it hasn't gone away after 5000*50ms Then
				// there is a chance that we are stuck in it
				logger.log(
					this,
					"Selector loop seems to be stuck in Sun windows JVM 'select'-bug (Sun BugId: 4729342), trying to fix. Consecutive encounters: "
						+ consecutiveWindowsBugEncounters
						+ ". Please report to devl@freenetproject.org if this situation resolves itself",
					Logger.ERROR);
				iwaitTime = 300;
				reset();

				//wait longer so that we dont spew log messages out...
				// No, if it's dead, it's dead.
				// We don't want to fill the disk up, sure
				// But there is no point running if we sleep(3000) - we won't
				// be able to even serve local web interface traffic
			}else{
				if (logger.shouldLog(Logger.MINOR, this))
					logger.log(
						this,
						"Worked around Sun windows JVM	'select'-bug (Sun BugId: 4729342), please update your JVM to a fixed version if possible. Consecutive encounters: "
							+ consecutiveWindowsBugEncounters,
						Logger.MINOR);
			}
			
			try {
				Thread.sleep(iwaitTime);
				//This problem might cause the selector to spin. Make that a
				// nicer experience to the user, both log-wise and CPU-wise
			} catch (InterruptedException e1) {
			}
			if (isWindows)
			consecutiveWindowsBugEncounters++;
			return;
		}else{
			throw e;
		}
	}
	
	//when this goes up, the loop stops
	protected volatile boolean stop;

	//it is a timeout for the selector loop in ms.
	//after it expires the select thread does maintenance
	//it should be fine-tuned after extensive testing.
	protected final static int TIMEOUT = 200;
	
	//the current timeout we use
	protected int timeout;

	//whether clearing of the bufferes is needed.
	//it will only be true in a subclass, but I want it here
	private boolean cleanupNeeded;
	
	public AbstractSelectorLoop(Logger logger, ExternalContinuous closePairLifetime) 
		throws IOException {
		synchronized(closeQueueLockObject){
			if(closeQueue == null)
				closeQueue = new CloseQueue(logger,closePairLifetime);
		}
		sel = Selector.open();
		this.logger = logger;
		logDebug = logger.shouldLog(Logger.DEBUG, this);
		stop = false;
		cleanupNeeded=false;
		timeout = TIMEOUT;

		if (closeThread == null) {
			closeThread = new CloseThread();
			closeThread.setDaemon(true);
			closeThread.start();
		}
		//currentSet=new LinkedList();//list is even better HashSet(1024);
		// //bigger is better

	}

	/**
	 * these pairs enter the register queue
	 */
	protected static class ChannelAttachmentPair {
		public final SelectableChannel channel;
		public final Object attachment;

		public ChannelAttachmentPair(
			SelectableChannel chan,
			Object attachment) {
			this.channel = chan;
			this.attachment = attachment;
		}
		
		public String toString() {
			return channel.toString()
				+ ":"
				+ ((attachment == null) ? "(null)" : attachment.toString());
		}
	}
	/**
	 * a synchronized queue that accepts single or bunched
	 * ChannelAttachmentPair:s and returns a list containing all items queued
	 */
	protected static class ChannelAttachmentPairQueue {
		private LinkedList queue = new LinkedList();

		//Adds the ChannelAttachmentPair to the queue
		synchronized void add(ChannelAttachmentPair cap) {
				queue.add(cap);
			}
		synchronized void addAll(LinkedList l) {
				queue.addAll(l);
			}
		//Returns and flushes the current queue
		//If no items is queued then null is returned
		LinkedList get() {
			//Avoid locking and allocation if we dont have any items queued
			//There shuldn't be any real drawback with keeping this check
			// unsynchronized
			if(queue.size()==0)
				return null;
			synchronized (this) {
				LinkedList retval = queue;
				queue = new LinkedList();
				return retval;
			}
		}
	}
	
	protected static final class DelayedChannelAttachmentPair
		extends ChannelAttachmentPair {
		public final long registerTime;
		
		public DelayedChannelAttachmentPair(
			SelectableChannel chan,
			NIOCallback attachment,
			long delay) {
			super(chan,attachment);
			registerTime = System.currentTimeMillis()+delay;
		}
	}
	
	public void register(SelectableChannel ch, Object attachment)
		throws IllegalBlockingModeException {
		if (ch == null) {
			logger.log(this, "Selectable channel is NULL", Logger.ERROR);
			return;
		}
		//minimize the number of exceptions in the select thread, check early
		if (ch.isBlocking())
			throw new IllegalBlockingModeException();
		if(logDebug)
		    logger.log(this, "Registering "+ch+" : "+attachment,
		            Logger.DEBUG);
		registerWaiters.add(new ChannelAttachmentPair(ch, attachment));
	}

	public final void register(Socket sock, Object attachment)
		throws IllegalBlockingModeException {
		//make sure this is a nio socket
		if (sock.getChannel() == null)
			throw new IllegalBlockingModeException();
		register(sock.getChannel(),attachment);
	}

	public final void unregister(SelectableChannel chan) {
		if(logDebug)
			logger.log(
				this,
				"Unregistering " + chan,
				new Exception("debug"),
				Logger.DEBUG);
		unregisterWaiters.add(new ChannelAttachmentPair(chan,null));
	}

	public final void unregister(Object attachment) {
		if(logDebug)
			logger.log(
				this,
				"Unregistering " + attachment,
				new Exception("debug"),
				Logger.DEBUG);
			unregisterWaiters.add(new ChannelAttachmentPair(null,attachment));
	}

	public final boolean isOpen() {
		return sel.isOpen();
	}
	/***************************************************************************
	 * these methods are to be called from within the selector loop thread.
	 * they must be as fast as possible and NEVER block.
	 **************************************************************************/
	
	public boolean shouldRegister(ChannelAttachmentPair chan) {		
		return true;
	}
	
	protected abstract int myKeyOps();
	public SelectionKey keyFor(SelectableChannel ch) {
		return ch.keyFor(sel);
	}
	
	/**
	 * register/unregister waiters in the queues to the selector another reason
	 * for this thread to have high priority - we don't want it to wait for
	 * processing the queues.
	 */
	protected final void processWaiters() {

	    if(logDebug)
	        logger.log(this, "processWaiters()", Logger.DEBUG);
		LinkedList waitersToProcess = delayedWaiters.get();
		while (waitersToProcess != null && waitersToProcess.size() > 0) {
			DelayedChannelAttachmentPair current = 
				(DelayedChannelAttachmentPair) waitersToProcess.removeFirst();
			if (System.currentTimeMillis() >= current.registerTime)
				registerWaiters.add(current);
			else
				delayedWaiters.add(current);
			//TODO: Would it be better to avoid a bunch of finegrained put():s
			// by using an putAll() instead
		}
		//TODO: find a way to get the try's out of the loop
		//first add
		LinkedList notRegistered = new LinkedList();
		waitersToProcess = registerWaiters.get();
		while (waitersToProcess != null && waitersToProcess.size() > 0) {
		    if(logDebug) logger.log(this, "waitersToProcess: "+waitersToProcess,
		            Logger.DEBUG);
			ChannelAttachmentPair current =
				(ChannelAttachmentPair) waitersToProcess.removeFirst();
			if(logDebug) logger.log(this, "current: "+current, Logger.DEBUG);

			if (myKeyOps() == SelectionKey.OP_ACCEPT) //this is a server
				// socket
				try {
					if(logDebug) logger.log(this, "Registering: "+current.channel+
				            " : "+current.attachment+" on "+sel, Logger.DEBUG);
					current.channel.register(
						sel,
						SelectionKey.OP_ACCEPT,
						current.attachment);
				} catch (ClosedChannelException e) {
					if(logDebug) logger.log(this, "Registering: "+current.channel+
				            " : "+current.attachment+" failed: "+e, 
				            Logger.DEBUG);
					continue;
				}
				else if (myKeyOps() == SelectionKey.OP_READ) {
				//a reader, writers get registered on the fly
				if (!current.channel.isOpen()) {
				    if(logDebug)
				        logger.log(this, "Channel not open: "+current.channel,
				                Logger.DEBUG);
					continue;
				}
				if (shouldRegister(current)) {
					try {
					    SelectionKey key = current.channel.keyFor(sel);
						if (key == null
							|| !(key.isValid())) {
							if(logDebug) logger.log(this, "Registering: "+current.channel+
						            " : "+current.attachment+" on "+sel, Logger.DEBUG);
						    current.channel.register(
								sel,
								SelectionKey.OP_READ,
								current.attachment);
							((NIOCallback) current.attachment).registered();
						} else {
						    // Just change the attachment
						    key.attach(current.attachment);
						    if(logDebug)
						        logger.log(this, "Not registering "+current.channel+
						                " : "+current.attachment+": already registered. now attached: "+
						                key.attachment()+" key: "+key, Logger.DEBUG);
						    
						}
					} catch (ClosedChannelException e) {
						if(logDebug) logger.log(this, "Registering: "+current.channel+
					            " : "+current.attachment+" failed: "+e, 
					            Logger.DEBUG);
						if (current.attachment instanceof NIOCallback)
							 ((NIOCallback) current.attachment).unregistered();
						try {
							queueClose(current);
						} catch (IllegalArgumentException x) {
							logger.log(
								this,
								"Could not queue close for "
									+ current
									+ ": "
									+ x,
								x,
								Logger.ERROR);
						}
						continue;
					} catch (ClassCastException e) {
						if(logDebug) logger.log(this, "Registering: "+current.channel+
					            " : "+current.attachment+" failed: "+e, 
					            Logger.DEBUG);
						// Not an NIOCallback
						continue;
					} catch (CancelledKeyException e) {
						if(logDebug) logger.log(this, "Registering: "+current.channel+
					            " : "+current.attachment+" failed: "+e, 
					            Logger.DEBUG);
						//do nothing?
						if (logDebug)
							logger.log(this, "caught " + e, Logger.DEBUG);
					}
				} else {
					notRegistered.add(current);
				}
			}
		}
		registerWaiters.addAll(notRegistered);
		
		//then remove
		
		waitersToProcess = unregisterWaiters.get();
		while (waitersToProcess != null && waitersToProcess.size() > 0) {
			ChannelAttachmentPair current;
			try {
				current =
					(ChannelAttachmentPair) waitersToProcess.removeFirst();
			} catch (NoSuchElementException e) {
				logger.log(
					this,
					"Parallel removal of elements in " + this +"?: " + e,
					e,
					Logger.ERROR);
				break;
			}
			if (current.channel != null) {
				//we have a channel
				SelectionKey k = current.channel.keyFor(sel);
				if (k != null)
					k.cancel();
			}
			// Not used by WSL, so we don't need to tell it
			else if (
				current.attachment != null) { //we have only the attachment
				Iterator i = sel.keys().iterator();
				while (i.hasNext()) {
					SelectionKey curKey = (SelectionKey) i.next();
					if (curKey == null)
						continue;
					if (!curKey.isValid())
						continue;
					Object attachment = curKey.attachment();
					if (attachment == null) {
						if (logDebug)
							logger.log(
								this,
								"Key "
									+ curKey
									+ " has null "
									+ "attachment unregistering "
									+ current,
								Logger.ERROR);
						curKey.cancel();
					} else if (attachment.equals(current.attachment)) {
						curKey.cancel();
						break;
					}
				}
			}
			if (current.attachment != null)
				try {
					((NIOCallback) current.attachment).unregistered();
				} catch (ClassCastException e) {
					continue;
				}
		}
	}
	
	/**
	 * returns true if none of the ready channels had any data, i.e. all of
	 * them are screwed. Might modify the contents of 'candidates'
	 */
	protected boolean inspectChannels(Set candidates){return false;}

	/**
	 * this fixes the keyset. We're maybe missing reads. Should return
	 * a new set of ready keys if deemed neccesary
	 */
	protected Set fixKeys(Set candidates){return candidates;}
	
	/**
	 * last-mile remedy if the selector is totaly screwed. this may very well
	 * have unforeseen consequences. lets hope we never get to use this...
	 */
	private final void reset() {
		Iterator i = sel.keys().iterator();
		Vector channels = new Vector(sel.keys().size());
		
		while (i.hasNext()) {
			SelectionKey current = (SelectionKey)i.next();
			if(current.channel().isOpen())
				channels.add(
					new ChannelAttachmentPair(
						current.channel(),
						current.attachment()));
			current.cancel();
		}
		
		try {
			if(sel.isOpen()) {
				try {
					sel.close();
				} catch (Throwable t) {
					logger.log(
						this,
						"Caught " + t + " closing selector in reset()",
						t,
						Logger.ERROR);
				}
				// Try open() anyway
			}
			
			sel = Selector.open();
			
			i = channels.iterator();
			while (i.hasNext()) {
				ChannelAttachmentPair current =
					(ChannelAttachmentPair) i.next();
				current.channel.register(sel,myKeyOps(),current.attachment);
			}
		} catch (IOException e) {
		} //at this moment we're already in deep trouble
		//catch this exception won't help much
		catch(NullPointerException e) {
			filterWindowsSelectBug(e);
			//TODO: Do something here?
		}
		
	}
	
	/**
	 * works the connections on the ready set. to be overrriden by subclasses.
	 * I want to throw as few exceptions as possible here, so its return value
	 * indicates whether it finished processing.
	 */
	protected abstract boolean processConnections(Set currentSet);

	/**
	 * Performs maintenace stuff on the loop. There really isn't anything
	 * necessary I can think of, so this method is empty. Subclasses are free
	 * to override it.
	 */
	protected void performMaintenance(){

	}

	/**
	 * do something before blocking. override if you need the reader for
	 * example would call clearBuffers here
	 */
	protected void beforeSelect() {
	}
	
	
	/**
	 * Do a selection operation
	 * 
	 * @return true if we selected, false if something failed, the usual
	 * response would be to try again
	 */
	protected final boolean mySelect(int x) throws IOException {
		boolean windowsBugHappened = false;
		try{
		    /**
		     * From the NIO book:
		     * 'Selections are cumulative. Once a selector adds a key to the 
		     * selected set, it never removes it. And once a key is in the 
		     * selected set, ready indications in the ready set of thay key
		     * are set but never cleared.'
		     * Thus you need to clear the selected set before selecting.
		     * Effects of not doing this: Missed notifications. 
		     */
		    sel.selectedKeys().clear();
			if(x == 0) {
				//logger.log(this, "selectNow()", Logger.DEBUG);
				currentlyActive = sel.selectNow();
			} else {
				long start = System.currentTimeMillis();
				currentlyActive = sel.select(x);
				long end = System.currentTimeMillis();
				if ((end - start) < 2)
					fastReturn = true;
			}
		} catch(ClosedChannelException e) {
			logger.log(this, "mySelect caught " + e, e, Logger.MINOR);
			return false;
		} catch (IOException e) {
			String msg = e.getMessage().toLowerCase();
			if(msg.indexOf("interrupted system call") != -1) {
				logger.log(
					this,
					"Caught interrupted system call: " + e,
					e,
					Logger.MINOR);
				return false;
			} else if (
				msg.indexOf("number of specified semaphore events") != -1) {
				logger.log(
					this,
					"Trying to workaround {" + msg + "}",
								Logger.NORMAL);
				try {
					reset();
				} catch (Throwable t) {
					String err =
						"Workaround for {"
							+ msg
							+ "} failed: "
							+ t
							+ " - report to "
							+ "support@freenetproject.org, with "
							+ "stack trace";
					logger.log(this, err, t, Logger.ERROR);
					System.err.println(err);
					e.printStackTrace(System.err);
					t.printStackTrace(System.err);
					System.exit(88);
					// let logger continue, and we may want some post mortem
				}
			} else if(msg.indexOf("the parameter is incorrect") == 0) {
				try {
					logger.log(
						this,
						"Error: " + e + " - trying to workaround",
									Logger.NORMAL);
					String jvmver = "";
					try {
						jvmver = System.getProperty("java.vm.version");
					} catch (Throwable t) {
					}
					if(jvmver.startsWith("1.4.0")) {
						logger.log(
							this,
							"JVM version: "
								+ jvmver
								+ " - java 1.4.0 has major problems with NIO, "
								+ "you should upgrade to 1.4.1 or later",
										Logger.ERROR);
					}
					reset();
				} catch (Throwable t) {
					String err = "Workaround for "+e+" failed: "+t;
					logger.log(this, err, t, Logger.ERROR);
					System.err.println(err);
					e.printStackTrace(System.err);
					t.printStackTrace(System.err);
					System.exit(88);
					// let logger continue, and we may want some post mortem
				}
			} else {
				throw e;
			}
		} catch(CancelledKeyException e) {
			logger.log(this, "mySelect caught " + e, e, Logger.MINOR);
			return false;
		} catch(NullPointerException e) {
			if (isWindows) {
				filterWindowsSelectBug(e);
				windowsBugHappened = true;
			} else {
				e.printStackTrace();
				logger.log(this, "NPE in rsl.mySelect" + e, Logger.ERROR);
			}
			//continue; //TODO: Is continue the right thing to do here or
			// should we do nothing instead?
			// we should die a horrible death and at least log something, damn
			// it! --zab
			
		} catch (Error e) {
			if(e.getMessage().indexOf("POLLNVAL") >= 0) {
				// GRRRRR!
				logger.log(
					this,
					"POLLNVAL detected in "
						+ this
						+ " ("
						+ e
						+ "), trying to workaround - I hate JVMs!",
								Logger.NORMAL);
				try {
					reset();
				} catch (Throwable t) {
					logger.log(
						this,
						"POLLNVAL workaround failed!: "
							+ t
							+ " - report to support@freenetproject."
							+ "org with stack trace",
						t,
						Logger.ERROR);
					System.err.println(
						"POLLNVAL workaround failed!: "
							+ t
							+ " - report to support@freenetproject"
							+ ".org with stack trace");
					e.printStackTrace();
					t.printStackTrace();
					System.exit(88);
					// let logger continue, and we may want some post mortem
				}
				return false;
			} else
				throw e;
		} catch (ClosedSelectorException e) {
			logger.log(this, "WTF?!: " + e, e, Logger.ERROR);
			try {
				reset();
			} catch (Throwable t) {
				logger.log(
					this,
					"Reopening selector FAILED!: " + t,
					t,
								Logger.ERROR);
				System.err.println(
					"Reopening selector FAILED! Tried to handle: " + e);
				e.printStackTrace(System.err);
				System.err.println("But then caught: "+t);
				t.printStackTrace(System.err);
			}
			return false;
		}
		if(!windowsBugHappened)
			this.consecutiveWindowsBugEncounters =0;
		return true;
	}
	
	long iter=0;
	int currentlyActive=0;
	boolean fastReturn = false;
	
	/**
	 * the actual selector loop itself. lets try to make this as smart as
	 * possible. I want to have this loop in a single location, so that
	 * children won't mess with it.
	 */
	protected final void loop() {
		int consecutiveDuds = 0;
		int consecutiveResets = 0;
		while(!stop) {
			fastReturn = false;
			logDebug = logger.shouldLog(Logger.DEBUG, this);
			try{ //a very wide and generic net
			
			//cancel() all keys before beforeSelect()
				closeQueue.handleCloseQueuePipe(); // move this down to rsl --zab
			
			beforeSelect();
			
			//tell the close thread it can start the real close
				closeQueue.notifyCloseThread();
				
			
			//moved this up
			//process any changes to the channels
			processWaiters();
			//sel.selectedKeys().clear();
			//select on the selector
			iter++;
				
				/** Don't let CPU usage go crazy if resetting isn't
				 * fixing it. I know sleeping won't *FIX* it, but it
				 * WILL prevent the node from killing the rest of the
				 * system and the user then shutting the node down.
				 */
				if (consecutiveResets > 0)
				    Thread.sleep(100);
			long now = System.currentTimeMillis();
				if (!mySelect(timeout))
					continue;
			long selected = System.currentTimeMillis();
				if (logDebug)
					logger.log(
						this,
						"Returned from selector in "
							+ (selected - now)
							+ " millis",
							Logger.DEBUG);
			
				//			logger.log(this, "Returned from selector,
				// "+currentlyActive+
//							" connections ("+iter+")", Logger.DEBUG);
			
			//currentSet.clear();
			//currentSet.addAll(sel.selectedKeys());
			
			if(logDebug)
					logger.log(
						this,
						"Keys ready before fixKeys: "
							+ currentlyActive
							+ "/"
							+ sel.keys().size(),
						Logger.DEBUG);
			
				Set readySet = fixKeys(sel.selectedKeys());
				//if (currentlyActive != currentSet.size())
				// logger.log(this, "read the freaking book! "+
				// currentlyActive +" != "+ currentSet.size() ,Logger.ERROR);
				currentlyActive = readySet.size();
			if(logDebug)
					logger.log(
						this,
						"Keys ready: "
							+ currentlyActive
							+ "/"
							+ sel.keys().size(),
						Logger.DEBUG);
			
			//if at this point no channels are active, it means
			//we were just woken up or timed out.
			if(currentlyActive == 0) {
					//logger.log(this, "Performing maintenance on
					// selector ("
				//iter+")", Logger.DEBUG);
				performMaintenance();
				//continue; - keys might be usable BEFORE selection
			}
			
				//if it is more than 0, we ewhile (readySockets.size() > 0)
				// {ither have data coming or a
			//screwed channel.

			//remove the closed channels
				if (inspectChannels(readySet)) {
				    // Returned with no data
					if (fastReturn) {
					    // Returned INSTANTLY with no data
					consecutiveDuds++;
						if (consecutiveDuds > 50) {
						    // Try to clear it
							//FIXME:lower this to MINOR when merging
							logger.log(this, "resetting ", Logger.NORMAL);
						reset();
						consecutiveDuds = 0;
						fastReturn=false;
							consecutiveResets++;
					}
				//continue; 
				//enter processConnections anyways
					} else {
					    consecutiveResets = 0;
					    consecutiveDuds = 0;
				}
			} 
			
			//and if not, process the rest
			
				if (!processConnections(readySet))
					throw new Exception();
					//can't think of anything smarter at this time
					//some quick-but-sophisticated recovery mechanism is needed
					//at least log it
			
		} catch(OutOfMemoryError t) {
			System.gc();
			System.runFinalization();
			System.gc();
			System.runFinalization();
			freenet.node.Main.dumpInterestingObjects();
			try {
					logger.log(
						this,
						"Ran emergency GC in " + getClass().getName(),
						Logger.ERROR);
				} catch (Throwable any) {
				}
		} catch(Throwable t){
			try {
					logger.log(
						this,
						"Caught throwable in AbstractSelectorLoop!: " + t,
						t,
						Logger.ERROR);
				t.printStackTrace();
				} catch (Throwable x) {
				}
		}
		}
	}
	
	/**
	 * moved down from the RSL
	 */
	
	// queueClose(CloseTriplet) overridden by subclasses, so always use it when closing
	protected void queueClose(CloseQueue.CloseTriplet cp) {
		if(logDebug)
			logger.log(this, "queueing close: " + cp, new Exception("debug"), Logger.DEBUG);
		/** Just because tcpConn.isClosed() doesn't mean
		 * the connection is fully closed at the tcp level.
		 * Firstly, tcpConn.close() will set the flag, and then
		 * call us! Secondly, if we timeout on a read, that does
		 * not automatically mean that the write side is closed.
		 * So one way or another, we need to actually close it. 
		 * 
		 * However.. it _does_ mean that getSocket() will unconditionally throw us an exception
		 * So.. until we know the correct cause of action just skip over the old getting-the-socket test   
		 */
		/*
		Socket s = null;
		try {
			s = ((tcpConnection) cp.conn).getSocket();
		} catch (IOException e) {
		    // Already closed. No big deal.
	        cp.closedAttachments();
			logger.log(this, "Got IOE while retrieving socket fom connection: cp="+cp, new Exception("debug"), Logger.MINOR);
			return; //TODO: Notify caller?
		}
		*/
		closeQueue.enqueue(cp);
		/*
		 * else synchronized(closeQueue) { boolean wasEmpty = closeQueue.isEmpty(); closeQueue.addLast(chan); if(wasEmpty) closeQueue.notify();
		 */
	}
	
	public final void queueClose(
		Connection conn,
		NIOCallback cb,
		SocketChannel sc) {
		if(conn == null){
			logger.log(this, "queueClosed null connection: cb="+cb+", sc="+sc, new Exception("debug"), Logger.ERROR);
			return;//TODO: Notify caller?
	}
	
		queueClose(new CloseQueue.CloseTriplet(conn, cb, sc));
	}
	

	public final void queueClose(SocketChannel chan, NIOCallback nc) {
		if (chan == null){
			logger.log(this, "queueClosed null channel: nc="+nc, new Exception("debug"), Logger.ERROR);
			return; //TODO: Notify caller about issue?
		}
		Socket sock = chan.socket();
		if (sock == null){
			logger.log(this, "queueClosed channel with null socket: nc="+nc+
			        ", chan="+chan, new Exception("debug"), Logger.ERROR);
			return; //TODO: Notify caller about issue?
		}
		Connection conn = tcpConnection.getConnectionForSocket(sock);
		if(conn == null){
		    if(chan.isOpen()) {
		        logger.log(this, "queueClosed called on socket with no matching connection: chan="+chan+
		                ", nc="+nc, new Exception("debug"), Logger.MINOR);
		    } else if(nc != null) {
		        try {
		            nc.closed();
		        } catch (Throwable t) {
		        	logger.log(this, "Caught throwable when executing callback",t, Logger.ERROR);
		        }
		}
			return;
	}
		queueClose(conn,nc,chan);
	}
	
	
	public final void queueClose(ChannelAttachmentPair pair) {
		NIOCallback cb = null;
		if(pair.attachment instanceof NIOCallback)
			cb = (NIOCallback)(pair.attachment);
		else
			logger.log(this, "Recieved queue-close callback of unknown type: pair.attachment="+pair.attachment, new Exception("debug"), Logger.ERROR);
		queueClose((SocketChannel)(pair.channel),cb);
	}
	
	protected class CloseThread extends Thread {
		CloseThread() {
			super("AbstractSelectorLoop background close() thread");
		}
		
		public void run() {
			closeQueue.processQueue();
		}
	}
	
	protected void closeCloseThread() {
		closeQueue.stopProcessQueue();
	}
 }
