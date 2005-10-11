/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
//TODO: override register and unregister so that they can't be used.


package freenet.transport;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.Vector;

import freenet.diagnostics.ExternalContinuous;
import freenet.diagnostics.ExternalCounting;
import freenet.support.BlockingQueue;
import freenet.support.Logger;
import freenet.support.io.Bandwidth;
import freenet.support.sort.QuickSorter;
import freenet.support.sort.SortAlgorithm;
import freenet.support.sort.VectorSorter;

/**
 * a loop that writes data to the network. 
 * its operation is basically the opposite of 
 * the read loop.
 */

public final class WriteSelectorLoop extends ThrottledSelectorLoop implements ThrottledAsyncTCPWriteManager {
  	
	/**
	 * queue where the sendjobs are placed
	 * this is different from the queues in ASL
	 */
	private final BlockingQueue jobs=new BlockingQueue();
	
	/**
	 * hashtable which filters all requests to add something
	 * to the queue.
	 */
	private final Hashtable uniqueness = new Hashtable(TABLE_SIZE,TABLE_FACTOR);
	
	private final boolean logOutputBytes;
	private final ExternalCounting logBytes;
	private final ExternalCounting logBytesVeryHigh;
	private final ExternalCounting logBytesHigh;
	private final ExternalCounting logBytesNormal;
	private final ExternalCounting logBytesLow;
	
	//TreeSet ts = new TreeSet();
	private final SortAlgorithm sorter = new QuickSorter();
	
	public final int uniquenessLength() {
		return uniqueness.size();
	}

	/**
	 * parameters of the uniquenss hashtable.  This depends entirely on 
	 * the machine power.  Its better to have it bigger than have 
	 * to refactor in runtime.
	 */
	private static final int TABLE_SIZE=512;
	private static final float TABLE_FACTOR=(float)0.6;
	
	int minSendBytesPerThrottleCycle;
	private static final int GRANULES_PER_THROTTLE_CYCLE = 20;
	
	/**
	 * nothing special about this constructor
	 */
	public WriteSelectorLoop(Logger logger, ExternalContinuous closePairLifetimeCallback, 
	        ExternalCounting logBytes, ExternalCounting logBytesVeryHigh,
	        ExternalCounting logBytesHigh, ExternalCounting logBytesNormal, 
	        ExternalCounting logBytesLow, boolean logOutputBytes, Bandwidth bw, int timerGranularity) 
		throws IOException {
		
		super(logger, closePairLifetimeCallback, bw, timerGranularity);
		this.logOutputBytes = logOutputBytes;
		this.logBytes = logBytes;
		this.logBytesVeryHigh = logBytesVeryHigh;
		this.logBytesHigh = logBytesHigh;
		this.logBytesNormal = logBytesNormal;
		this.logBytesLow = logBytesLow;
		minSendBytesPerThrottleCycle = 
			(bw.currentBandwidthPerSecondAllowed() * 
			 GRANULES_PER_THROTTLE_CYCLE *
			 timerGranularity) / 1000;
		logger.log(this, "Minimum send size: "+
						minSendBytesPerThrottleCycle+" per "+
						(GRANULES_PER_THROTTLE_CYCLE * timerGranularity)+
						"ms", Logger.MINOR);
	}
	
	/**
	 * nothing special about this constructor
	 */
	public WriteSelectorLoop(Logger logger, ExternalContinuous closePairLifetimeCallback,
	        ExternalCounting logBytes, ExternalCounting logBytesVeryHigh,
	        ExternalCounting logBytesHigh, ExternalCounting logBytesNormal, 
	        ExternalCounting logBytesLow, boolean logOutputBytes) 
		throws IOException {
		
		super(logger, closePairLifetimeCallback);
		this.logBytes = logBytes;
		this.logOutputBytes = logOutputBytes;
		this.logBytesVeryHigh = logBytesVeryHigh;
		this.logBytesHigh = logBytesHigh;
		this.logBytesNormal = logBytesNormal;
		this.logBytesLow = logBytesLow;
		
		minSendBytesPerThrottleCycle = Integer.MAX_VALUE;
	}
	
	protected Object idSync = new Object();
	protected long idCount = 0;
	public final static int maxDelay = 2000;
	/**
	 * these triplets enter the queue for sending.
	 */
	private final class SendJob implements NIOCallback, 
										   Comparable {
		public final ByteBuffer data;
		public final SelectableChannel destination;
		public final NIOWriter client;
		public final int position;
		public long id;
		public final long writtenThrottledBytesAtStart;
		public final long writtenPseudoThrottledBytesAtStart;
		public final int priority;
		public final long switchAfter;
		
		boolean shouldFragment() {
			return priority > 0;
		}
		
		public void update() {
// 			if(switchAfter != -1) {
// 				long now = System.currentTimeMillis();
// 				if(switchAfter != -1 &&
// 				   switchAfter < now) {
// 					priority = 0;
// 					switchAfter = -1;
// 					synchronized(idSync) {
// 						id = idCount++;
// 					}
// 				}
// 			}
		}
		
		public int compareTo(Object o) {
			if(o instanceof SendJob) {
				SendJob j = (SendJob)o;
//				if(j.priority != priority) {
//					if(j.priority != priority) {
//						if(j.priority > priority) return -1;
//						if(j.priority < priority) return 1;
//					}
//				}
				if(j.id>id) return -1;
				if(j.id<id) return 1;
				return 0;
			} else return -1;
		}
		
		public SendJob(byte [] _data, SelectableChannel destination, NIOWriter client,
					   int priority) {
			this(_data,0,_data.length,destination,client,priority);
			/*
			this.data=ByteBuffer.wrap(_data);
			this.position = 0;
			this.destination = destination;
			this.client=client;
			synchronized(idSync) {
				id = idCount++;
			}
			writtenThrottledBytesAtStart = totalWrittenThrottlableBytes;
			writtenPseudoThrottledBytesAtStart =totalWrittenPseudoThrottlableBytes;
			this.priority = priority;
// 			if(priority > 0)
// 				switchAfter = System.currentTimeMillis() + maxDelay;
// 			else 
			switchAfter = -1;
			*/
		}
		
		public long delta() {
			return totalWrittenThrottlableBytes - 
				writtenThrottledBytesAtStart;
		}
		
		public long odelta() {
			return totalWrittenPseudoThrottlableBytes -
				writtenPseudoThrottledBytesAtStart;
		}
		
		public SendJob(byte [] _data, int offset, int length, SelectableChannel destination, 
					   NIOWriter client, int priority) {
			this.data=ByteBuffer.wrap(_data,offset,length);
			this.position = offset;
			this.destination = destination;
			if(destination == null){
				logger.log(this,"SendJob with destination == null created",new Exception("debug"),Logger.ERROR);
				throw new NullPointerException();
			}
			this.client=client;
			writtenThrottledBytesAtStart = totalWrittenThrottlableBytes;
			writtenPseudoThrottledBytesAtStart =totalWrittenPseudoThrottlableBytes;
			synchronized(idSync) {
				id = idCount++;
			}
			this.priority = priority;
// 			if(priority > 0)
// 				switchAfter = System.currentTimeMillis() + maxDelay;
// 			else
			switchAfter = -1;
		}
		
		public final String toString() {
			return SendJob.this.getClass().getName()+": "+data+","+destination+
				","+client+","+position+","+id+",prio="+priority;
		}
		
		public final void closed() {
			client.closed();
			if(uniqueness.containsKey(destination)) {
				logger.log(this, "Removing "+this+
								" from uniqueness in SendJob.closed()!!",
								Logger.NORMAL);
				uniqueness.remove(destination);
			}
		}
		
		public final void queuedClose() {
			client.queuedClose();
			if(uniqueness.containsKey(destination)) {
				logger.log(this, "Removing "+this+
								" from uniqueness in SendJob.queuedClose()!",
								Logger.NORMAL);
				uniqueness.remove(destination);
			}
		}
		
		public final void registered() {
			client.registered();
		}
		
		public final void unregistered() {
			client.unregistered();
		}
		
		public final boolean shouldThrottle() {
			return client.shouldThrottle();
		}
		
		public final boolean countAsThrottled() {
			return client.shouldThrottle();
		}
	}
	
	public final void onClosed(SelectableChannel sc) {
		if(sc == null) throw new NullPointerException();
		if(uniqueness.containsKey(sc)) {
			if (logDebug)logger.log(this, "Removing "+sc+":"+uniqueness.get(sc)+
							" from uniqueness in onClosed()",
							Logger.DEBUG);
			uniqueness.remove(sc);
		}
		super.onClosed(sc);
	}
	
	/**
	 * this method adds a byte[] to be sent to a Channel. 
	 * it should be called from a different thread 
	 * return value: false if there's a job already on this channel.
	 */
	 public final boolean send(byte [] data, SelectableChannel destination, NIOWriter client, 
							   int priority) throws IOException{
	 
	 	logDebug = logger.shouldLog(Logger.DEBUG, this);
	 	
		if (data.length==0 || destination ==null) throw new IOException ("no data to send?");
		
	 	if (!checkValid(destination, client))
			return false;
		
		SendJob job = new SendJob(data,destination,client, priority);
		
		//it doesn't really matter what the actual mapping is
		uniqueness.put(destination,job);
		if (logDebug)logger.log(this, "Added "+destination+","+job+" to uniqueness, now "+uniqueness.size(), Logger.DEBUG);
		
		//because the objects are removed from this queue
		jobs.enqueue(job);
		if (logDebug)logger.log(this, "Queued "+destination+","+job,
						Logger.DEBUG);
		return true;
	 }
	 
	 public final boolean send(byte [] data, int offset, int len, SelectableChannel destination, 
							   NIOWriter client, int priority) throws IOException{
	 
	 	logDebug = logger.shouldLog(Logger.DEBUG, this);
		if (len==0 || destination==null) throw new IOException ("no data to send?");
		
	 	if (!checkValid(destination, client))
			return false;
		
		SendJob job = new SendJob(data,offset,len,destination,client,priority);
		
		//it doesn't really matter what the actual mapping is
		uniqueness.put(destination,job);
		if (logDebug)logger.log(this, "Added "+destination+","+job+" to uniqueness, now "+uniqueness.size(), Logger.DEBUG);
		
		//because the objects are removed from this queue
		jobs.enqueue(job);
		if (logDebug)logger.log(this, "Queued "+destination+","+job,
						Logger.DEBUG);
		return true;
	 }
	 
	 private final boolean checkValid(SelectableChannel destination,
									  NIOWriter client) {
		//if we are already registered, return false
		SelectionKey key = destination.keyFor(sel);
		if (key != null && key.isValid()) {
			logger.log(this, "Send failed due to valid key for "+
							destination+":"+client, 
							new Exception("debug"), Logger.ERROR);
			return false;
		}
		
		//check if we have this channel in the hashtable
		if (uniqueness.containsKey(destination)) {
			if(logDebug)
				logger.log(this, "Send failed due to key in hash",
								Logger.DEBUG);
			return false;
		}
		
		return true;
	 }
	 
	 //
	 // recent long waits in CHOS.write prompted me to do this here as well
	 //
	 protected final Set fixKeys(Set candidates) {
	 	//this never adds anything
//	 	Set retval=null;
//	 	Iterator i = sel.keys().iterator();
//		while (i.hasNext()) {
//			SelectionKey current = (SelectionKey) i.next();
//			if (current.isValid() && current.isWritable() 
//				&& (!candidates.contains(current))){
//				logger.log(this, "fixKeys added "+current,
//								Logger.NORMAL);
//				 if(retval == null)
//				 	retval = new HashSet();
//				retval.add(current);
//			}
//		}
//		//If we found something bad we will have to 
//		//return a new:d set since 'candidates' is
//		//read-only
//		if(retval != null){
//			retval.addAll(candidates); //Dont forget all those channels that _was_ ok
//			return retval;
//		}else
//			return candidates;
		return candidates;
	 }
	 
	 /**
	  * overriden so that it will block when there is nothing
	  * to send.  Otherwise the selector enters an endless loop
	  */
	 protected final void beforeSelect() {
		 boolean success = false;
		 while(!success) {
			 try {
				 success = mySelect(0);
			 } catch (IOException e) {
				 logger.log(this, "selectNow() failed in WSL.beforeSelect(): "+e,
								 e, Logger.ERROR);
			 }
		 }
		 throttleBeforeSelect(); // to avoid CancelledKeyExceptions
		 // Also to deregister anything that needs deregistering
		 
		 //this queue is local to the thread.
		 LinkedList waitingJobs = new LinkedList();
		 if(logDebug)
			 logger.log(this, "beforeSelect()", Logger.DEBUG);
		 // first check if any channel is registered in the 
		 // selector.
		 try{
			 // We want to run the first iteration anyway in case there are
			 // some jobs to copy; but we do not want to block unless there
			 // is nothing else to do.
			 boolean firstIteration = true;
			 while((waitingJobs.isEmpty() && sel.keys().isEmpty())
				   || (!jobs.isEmpty())) {
				 // If we are flooded with jobs, we will stall until we can add them all
				 // If there is something left on the selector from last time i.e. a partial write, or if we have queued stuff in waitingJobs, then we may be done
				 // If we have work to register, we should get it
				 // And we always run the first time
				 firstIteration = false;
				 long now = System.currentTimeMillis();
				 if(throttling && now > reregisterThrottledTime)
					 throttleBeforeSelect();
				 if (sel.keys().isEmpty() && jobs.isEmpty()) {
					 //check if the jobs queue is empty,
					 //and if it is, block on it.
					 if(logDebug)
						 logger.log(this, "Waiting for job to add to "+
										 "queue", Logger.DEBUG);
					 long delay = 0;
					 synchronized(throttleLock) {
						 if(throttling) {
							 if(logDebug)
								 logger.log(this, "Throttling at "+now+
												 " until "+
												 reregisterThrottledTime, 
												 Logger.DEBUG);
							 delay = reregisterThrottledTime - now;
							 if(delay < 0) delay = 0;
						 } else {
							 delay = -1; // wait forever!
						 }
						 if (logDebug)logger.log(this, "Delay: "+delay, 
													  Logger.DEBUG);
					 }
					 int x = (delay > Integer.MAX_VALUE) ? Integer.MAX_VALUE
						 : ((int)delay);
					 if(logDebug) 
						 logger.log(this, "Delay will be "+x+" ms: ",
										 Logger.DEBUG);
					 Object o = jobs.dequeue(x);
					 logger.log(this, "Dequeued", Logger.DEBUG);
					 if(o != null) {
						 SendJob current = (SendJob)o;
						 synchronized(throttleLock) {
							 if(throttling && current.shouldThrottle()) {
								 ChannelAttachmentPair pair = 
									 new ChannelAttachmentPair(current.destination, current);
								 throttleDisabledQueue.addLast(pair);
								 if(logDebug)
									 logger.log(this, "Moving new job "+
													 pair+" onto delay queue", 
													 Logger.DEBUG);
								 continue;
							 }
						 }
						 waitingJobs.add(o);
						 if(logDebug)
							 logger.log(this, "Dequeued job",
											 Logger.DEBUG);
						 continue;
					 } else {
						 throttleBeforeSelect();
						 continue;
					 }
				 } else {
					 //don't block, just copy the elements to
					 //the local queue
					 long startTime = System.currentTimeMillis();
					 while(jobs.size() >0) {
						 SendJob current = (SendJob)(jobs.dequeue());
						 synchronized(throttleLock) {
							 if(throttling && current.shouldThrottle()) {
								 ChannelAttachmentPair pair = 
									 new ChannelAttachmentPair(current.destination, current);
								 throttleDisabledQueue.addLast(pair);
								 if(logDebug)
									 logger.log(this, "Moving new job "+
													 pair+" onto delay queue", 
													 Logger.DEBUG);
								 continue;
							 }
						 }
						 if(logDebug)
							 logger.log(this, "Copying job "+current+
											 " to queue", Logger.DEBUG);
						 waitingJobs.add(current);
					 }
					 long endTime = System.currentTimeMillis();
					 if(logDebug)
						 logger.log(this, "Took "+(endTime-startTime)+
										 " millis copying queue ("+iter+")", 
										 Logger.DEBUG);
				 }
			 }
		 } catch(InterruptedException e) {
			 logger.log(this, "Interrupted: "+e, e, Logger.NORMAL);
			 e.printStackTrace();
		 }
		 
		 
		//register the channel  with the selector.
		//do not remove the channel from the uniqueness table
		Iterator i = waitingJobs.iterator();
		while (i.hasNext()) {
			SendJob currentJob = (SendJob) i.next();
			try {
				if(logDebug)
					logger.log(this, "Registering channel "+currentJob+
									" with selector", Logger.DEBUG);
				currentJob.destination.register(sel, SelectionKey.OP_WRITE, currentJob);
				if(logDebug)
					logger.log(this, "Registered channel "+currentJob+
									" with selector", Logger.DEBUG);
			}catch (ClosedChannelException e) {
				if(logDebug)
					logger.log(this, "Channel closed: "+currentJob+": "+e,
									e, Logger.DEBUG);
				queueClose((SocketChannel)currentJob.destination,
						   currentJob.client);
				uniqueness.remove(currentJob.destination);
				if (logDebug)logger.log(this, "Removed "+currentJob+" from uniqueness due to ClosedChannelException, now "+uniqueness.size(), Logger.DEBUG);
				//TOTHINK: perhaps move all callbacks outside of WSL thread
				try {
					currentJob.client.jobDone(0,false);
				} catch (Throwable t) {
					logger.log(this, "Caught "+t+" notifying "+
									currentJob.client+" for "+
									currentJob.destination+" in beforeSelect",
									t, Logger.ERROR);
				}
			} catch (IllegalBlockingModeException e) {
				logger.log(this, "Could not register channel "+currentJob+
								" with selector: "+currentJob+": "+e, e, 
								Logger.ERROR);
				e.printStackTrace();
			}
		}
	 }
	 
	private long totalWrittenThrottlableBytes = 0;
	private long totalWrittenPseudoThrottlableBytes = 0;
	
	//at this stage the selected set should contain only channels
	//that are ready to be written to and have something to be sent.
	protected final boolean processConnections(Set currentSet) {
		if(logDebug)
			logger.log(this, "processConnections()", Logger.DEBUG);
		boolean success = true;
		int throttledBytes = 0;
		int pseudoThrottledBytes = 0;
		int bytesSent = 0;
		Iterator i = currentSet.iterator();
		Vector ts =new Vector(currentSet.size());
		while(i.hasNext()) {
			SelectionKey curKey = (SelectionKey)i.next();
			//if (!(curKey.isValid() && curKey.isWritable() && curKey.channel().isOpen())) continue;
			SendJob currentJob = (SendJob)(curKey.attachment());
			currentJob.update();
			ts.add(currentJob);
		}
		sorter.sort(new VectorSorter(ts));
		if (logDebug)logger.log(this, "Sorted jobs, "+ts.size(), Logger.DEBUG);
		i = ts.iterator();
		try{
			boolean noThrottled = (System.currentTimeMillis() < 
								   reregisterThrottledTime);
			// Some of them may have been enableThrottle()d off thread
		while (i.hasNext()) {
			boolean localSuccess = true;
			SendJob currentJob = (SendJob)(i.next());
			SelectionKey curKey = currentJob.destination.keyFor(sel);
			if(currentJob == null || currentJob.data.remaining() <= 0) {
				curKey.cancel(); // leave running, but cancel
				if(curKey.channel() != null) {
					uniqueness.remove(curKey.channel());
					if (logDebug)logger.log(this, "Removed "+curKey.channel()+
									" from uniqueness, now "+uniqueness.size(),
									Logger.DEBUG);
				}
				logger.log(this, "Cancelled "+currentJob+
								" - already done?", Logger.ERROR);
			}
			
			//do the write.. 
			int sent = 0;
			try {
				if(!currentJob.destination.isOpen())
					throw new IOException("closed");
				if(currentJob.destination instanceof SocketChannel &&
				   (!((SocketChannel)(currentJob.destination)).
					isConnected())) throw new IOException("not connected");
				if(bw != null && currentJob.client.shouldThrottle()) {
					if(throttledBytes > minSendBytesPerThrottleCycle) {
						// Will get cancelled by throttleConnections
						if(logDebug)
							logger.log(this, "Skipping (A) throttled "+
											currentJob, Logger.DEBUG);
						shortTimeout = true; // just in case the bytes are absorbed by the throttle
						continue;
					} else if(noThrottled) {
						// May not get cancelled by throttleConnections
						// shouldThrottle overrides countAsThrottled
						logger.log(this, "Job apparently became throttled"+
										" after registration: "+currentJob,
										Logger.MINOR);
						curKey.cancel();
						ChannelAttachmentPair pair = 
							new ChannelAttachmentPair(currentJob.destination,
													  currentJob);
						synchronized(throttleLock) {
							throttleDisabledQueue.addLast(pair);
						}
						continue;
					}
				}
				int oldLimit = currentJob.data.limit();
				if(bw != null && currentJob.client.shouldThrottle() &&
				   currentJob.shouldFragment()) {
					int lim = bw.maximumPacketLength();
					int av = bw.availableBandwidth();
					if(av >= 1 && lim > av) lim = av;
					// it is not refilled in bytes - one tick should be a fair chunk
					if(currentJob.data.remaining() > lim) {
						currentJob.data.limit(currentJob.data.position() +
											  lim);
						if(logDebug)
							logger.log(this, "Limited: "+currentJob.data+
											" for "+currentJob.client+
											", limit was "+oldLimit, Logger.DEBUG);
					}
				} else {
					if(logDebug)
						logger.log(this, "Did not limit, "+
										currentJob.data.remaining()+"/"+
										(bw==null?"(null)": Integer.
										 toString(bw.maximumPacketLength()))+
										" for "+currentJob.client, 
										Logger.DEBUG);
				}
				try {
					sent = ((SocketChannel)curKey.channel()).
						write(currentJob.data);
					//this should be here, not in finally
					//if (sent==0) currentSet.remove(curKey);
				} finally {
					if(currentJob.data.limit() != oldLimit)
						currentJob.data.limit(oldLimit);
				}
				//if this was an incomplete write, leave the buffer as it is
			} catch (IOException e) {
				localSuccess=false;
				if (logDebug)logger.log(this, "IOException: "+e+" writing to channel "+
								currentJob, e, Logger.DEBUG);
				queueClose((SocketChannel)currentJob.destination,
						   currentJob.client);
			} finally {
				if(sent > 0) {
					bytesSent+=(sent+OVERHEAD);
					if(currentJob.client.shouldThrottle()) {
						throttledBytes += (sent+OVERHEAD);
						totalWrittenThrottlableBytes += (sent+OVERHEAD);
						if(logDebug) {
							logger.log(this, "Should throttle "+
											currentJob, Logger.DEBUG);
							logger.log(this, "Written "+sent+
											" throttled bytes for "+
											currentJob+": delta="+
											currentJob.delta()+", odelta="+
											currentJob.odelta(),
											Logger.DEBUG);
						}
						if(logOutputBytes) {
						if(currentJob.priority < NEGOTIATION)
						        if(logBytesVeryHigh != null) {
						        logBytesVeryHigh.count(sent);
						        }
						else if(currentJob.priority == NEGOTIATION)
						        if(logBytesHigh != null) {
						        logBytesHigh.count(sent);
						        }
						else if(currentJob.priority == MESSAGE)
						        if(logBytesNormal != null) {
						        logBytesNormal.count(sent);
						        }
						else
						        if(logBytesLow != null) {
						        logBytesLow.count(sent);
						}
						}
					} else {
						if(currentJob.client.countAsThrottled()) {
							pseudoThrottledBytes += (sent+OVERHEAD);
							totalWrittenPseudoThrottlableBytes += (sent+OVERHEAD);
							if(logDebug)
								logger.log(this, "Pseudo-throttle "+
												currentJob, Logger.DEBUG);
						} else {
							if(logDebug)
								logger.log(this, "Should not throttle "+
												currentJob, Logger.DEBUG);
						}
					}
					if (logDebug)logger.log(this, "Sent "+sent+" bytes on "+
												 currentJob+", bytesSent="+bytesSent+
												 ", throttledBytes="+ throttledBytes+
												 ", psuedoThrottledBytes="+
												 pseudoThrottledBytes, Logger.DEBUG);
					// In any case, demote it
					if(currentJob.data.remaining() > 0)
						synchronized(idSync) {
							currentJob.id = idCount++;
						}
				}
				//cancel the key and remove it from the uniqueness
				//and notify Callback 
				//also notify if we get an exception
				if ((!localSuccess) || currentJob.data.remaining() == 0) {
					if (logDebug)logger.log(this, "Finishing "+currentJob,
									Logger.DEBUG);
					curKey.cancel();
					if (logDebug)logger.log(this, "Removing (B) "+currentJob+" ("+
									curKey+") from uniqueness, now "+
									uniqueness.size(), Logger.DEBUG);
					uniqueness.remove(curKey.channel());
					if (logDebug)logger.log(this, "Removed (B) "+currentJob+" ("+
									curKey+") from uniqueness, now "+
									uniqueness.size(), Logger.DEBUG);
					try {
						if(currentJob.data == null ||
						   currentJob.client == null)
							logger.log(this, "currentJob.data="+currentJob.data+
											", currentJob.client="+currentJob.client,
											Logger.ERROR);
						else {
							int delta = currentJob.data.position() -
								currentJob.position;
							currentJob.client.jobDone(delta, localSuccess);
						}
					} catch (Throwable t) {
						logger.log(this, "Caught "+t+" notifying "+
										currentJob.client+" for "+
										currentJob.destination+
										" in processConnections", t, 
										Logger.ERROR);
					}
					if (logDebug)logger.log(this, "Finished "+currentJob,
									Logger.DEBUG);
				} else {
					if (logDebug)logger.log(this, "Not finished "+currentJob,
									Logger.DEBUG);
					if (sent > 0 && localSuccess)
						currentJob.client.jobPartDone(currentJob.data.position()-currentJob.position);
				}
			}
		}
		if(logDebug)
			logger.log(this, "Written "+bytesSent, Logger.DEBUG);
		if(throttledBytes != 0 || pseudoThrottledBytes != 0) {
			if(logDebug)
				logger.log(this, "Written "+throttledBytes+
								" bytes that should be throttled and "+
								pseudoThrottledBytes+" pseudo-throttled bytes", 
								Logger.DEBUG);
			int bytes = throttledBytes + pseudoThrottledBytes;
			if(logOutputBytes && logBytes != null)
			    logBytes.count(bytes);
			throttleConnections(bytesSent, throttledBytes,
								pseudoThrottledBytes);
		}
		} catch(Throwable e) {
			logger.log(this, "Exception in processConnections(): "+
							e, e, Logger.ERROR);
			e.printStackTrace();
			success=false;
		} /*finally {
			currentSet.clear();  //not needed anymore
		}*/
		return success;
	}
	
	protected final boolean shouldThrottle(Object o) {
		SendJob sj = (SendJob)o;
		NIOCallback cb = sj.client;
		return cb.shouldThrottle();
	}
	
	//again, eventually the catches will come up here
	public final void run() {
		loop();
	}
	
	protected final void queueClose(CloseQueue.CloseTriplet chan) {
		if (logDebug)logger.log(this, "queueClose("+chan+"), uniqueness "+
						uniqueness.size(), Logger.DEBUG);
		if(chan.sc != null) {
			uniqueness.remove(chan.sc);
			if (logDebug)logger.log(this, "Removed(C) "+chan+" from uniqueness, now "+
							uniqueness.size(), Logger.DEBUG);
		}
		super.queueClose(chan);
	}
	
	protected final void onInvalidKey(SelectionKey key) {
		SocketChannel chan = (SocketChannel)(key.channel());
		if(chan != null) {
			if(uniqueness.containsKey(chan)) {
				uniqueness.remove(chan);
				if (logDebug)logger.log(this, "Removed "+key+" from uniqueness due to channel being invalid, uniqueness now "+uniqueness.size(), Logger.DEBUG);
			}
		}
	}
	
	public final String analyzeUniqueness() {
		StringBuffer out = new StringBuffer();
		SocketChannel c[] = new SocketChannel[uniqueness.size()];
		c = (SocketChannel[])(uniqueness.keySet().toArray(c));
		for(int x=0;x<c.length;x++) {
			SocketChannel chan = c[x];
			if(chan == null) continue; // race condition
			if(!chan.isOpen()) {
				out.append("NOT OPEN: ").append(chan);
				Object o = uniqueness.get(chan);
				if(o != null) out.append(":").append(o);
				out.append('\n');
			} else if(!chan.isConnected()) {
				out.append("NOT CONNECTED: ").append(chan);
				Object o = uniqueness.get(chan);
				if(o != null) out.append(":").append(o);
				out.append('\n');
			} else {
				out.append("Running: ").append(chan);
				Object o = uniqueness.get(chan);
				if(o != null) out.append(":").append(o);
				out.append('\n');
			}
		}
		return out.toString();
	}
	
	protected final int myKeyOps() {
		return SelectionKey.OP_WRITE;
	}
	
	//TODO: close, log, etc.
	public final void close() {}

	public long getTotalTransferedThrottlableBytes() {
		return totalWrittenThrottlableBytes;
	}

	public long getTotalTransferedPseudoThrottlableBytes() {
		return totalWrittenPseudoThrottlableBytes;
	}
  }
