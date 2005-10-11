package freenet.client;

import freenet.client.AutoRequester;
import freenet.client.ClientEventListener;
import freenet.client.ClientEvent;
import freenet.client.events.*;
import freenet.client.ClientFactory;
import freenet.Core;
import freenet.client.FreenetURI;
import freenet.support.Checkpointed;
import freenet.support.Bucket;
import freenet.support.Logger;
import freenet.node.Node;

public abstract class AutoBackoffNodeRequester
    implements Checkpointed, ClientEventListener {

    ClientFactory factory;
    protected volatile DataNotFoundEvent dnf = null;
    protected volatile RouteNotFoundEvent rnf = null;
    protected volatile CollisionEvent coll = null;
    protected volatile NoReplyEvent noreply = null;
    protected volatile boolean running;
    // sync on this when dealing with finished or running
    protected Object runSync = new Object(); 
    protected volatile boolean finished = false;
    protected long initialSleepTime = 5000;
    protected long sleepTime = initialSleepTime;
    AutoRequester r;
    protected FreenetURI uri;
    FreenetURI generatedURI;
    boolean inserting;
    protected Bucket bucket;
    protected int hopsToLive;
    protected float sleepTimeMultiplier = 2;
    protected volatile boolean notValid = false;
    protected boolean logDEBUG;

    public AutoBackoffNodeRequester(ClientFactory f, FreenetURI uri, boolean inserting, Bucket bucket, int hopsToLive) {
		factory = f;
		r = new AutoRequester(factory);
		r.addEventListener(this);
		r.doRedirect(doRedirect());
		r.setHandleSplitFiles(doSplitFiles());
		r.setTempDir(Node.tempDir.toString());
		this.bucket = bucket;
		this.hopsToLive = hopsToLive;
		this.uri = uri;
		this.inserting = inserting;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (Core.logger.shouldLog(Logger.MINOR, this)) Core.logger.log(this, "Starting internal request: " + uri, Logger.MINOR); }
    
    protected boolean doRedirect() {
	return false;
    }
    
    protected abstract boolean doSplitFiles();
    
    public String getCheckpointName() {
	String s = (inserting ? "Inserting " : "Fetching ");
	if(finished) s = "Terminating " + s;
	if(running) s += "(running) ";
	s += uri;
	return s;
    }
    
    public FreenetURI getURI() {
	return uri;
    }
    
    public long nextCheckpoint() {
	synchronized(runSync) {
	    if(finished || sleepTime < 0) {
		if(logDEBUG)
		    Core.logger.log(this, "Cancelling checkpoint: "+this,
				    Logger.DEBUG);
		return -1;
	    } else {
		long t = System.currentTimeMillis();
		if(logDEBUG)
		    Core.logger.log(this, "Next checkpoint at: "+(t+sleepTime)+
				    " at "+t+" for "+this, Logger.DEBUG);
		return t + sleepTime;
	    }
	}
    }

    public void checkpoint() {
	synchronized(runSync) {
	    if(finished) {
		finish();
		return;
	    }
	    running = true;
	}
	try {
	    if(logDEBUG)
		Core.logger.log(this, "Running checkpoint for internal request: "+
				getCheckpointName(), Logger.DEBUG);
	    if (internalRun()) {
		synchronized(runSync) {
		    running = false;
		    if(finished) finish();
		}
		return;
	    } else {
		synchronized(runSync) {
		    if(finished) return;
		}
		if (sleepTime == 0) sleepTime = initialSleepTime;
		else sleepTime *= sleepTimeMultiplier;
		if(logDEBUG)
		    Core.logger.log(this, "Retrying " + getCheckpointName() +
				    " in " +sleepTime + " millis", 
				    Logger.DEBUG);
		synchronized(runSync) {
		    running = false;
		    if(finished) finish();
		}
	    }
	} finally {
	    synchronized(runSync) {
		running = false;
		if(finished) finish();
	    }
	}
    }
    
    protected boolean success() {
    	synchronized(runSync) {
	    	finished = true; // not synchronized because will be acted on later
	    	if(finished) finish();
		}
		return true;
    }

    protected boolean failure() {
    	return false;
    }

    protected String contentType() {
    	return "text/plain";
    }

    protected Bucket getBucket() {
    	return bucket;
    }

    public void kill() {
    	synchronized(runSync) {
    		finished = true;
    		if(!running) finish();
    	}
    	sleepTime = -1;
    }
    
    public boolean overloaded() {
    	return factory.isOverloaded();
    }
    
    public boolean internalRun() {
		synchronized (runSync) {
			if (finished) {
				finish();
				return false;
			}
		}
		if (overloaded())
			return false;
		if (logDEBUG)
			Core.logger.log(this, "Running internal request: " + getCheckpointName(), Logger.DEBUG);
		dnf = null;
		rnf = null;
		coll = null;
		generatedURI = null;
		if (inserting ? r.doPut(getURI(), getBucket(), hopsToLive, contentType()) : r.doGet(getURI(), getBucket(), hopsToLive)) {
			if (logDEBUG)
				Core.logger.log(this, "Internal request apparently successful... " + getCheckpointName(), Logger.DEBUG);
			// Apparent success
			synchronized (runSync) {
				if (finished)
					finish();
			}
			return success();
		} else {
			if (logDEBUG)
				Core.logger.log(this, "Internal request apparently failed... " + getCheckpointName(), Logger.DEBUG);
			if (rnf != null) {
				Core.logger.log(this, "RouteNotFound " + getCheckpointName(), Logger.NORMAL);
			} else if (dnf != null) {
				Core.logger.log(this, "DataNotFound " + getCheckpointName(), Logger.MINOR);
			} else if (inserting && (coll != null)) {
				Core.logger.log(this, "Collision " + getCheckpointName(), Logger.NORMAL);
				return failedCollision(coll);
			} else if (noreply != null) {
				Core.logger.log(this, "Reply timeout " + getCheckpointName(), Logger.MINOR);
				return false; // retry
			} else if (notValid) {
				Core.logger.log(this, "Invalid data " + getCheckpointName(), Logger.MINOR);
				return failedInvalidData();
			} else {
				Core.logger.log(this, "Unexpected Failure " + getCheckpointName() + ": " + r.getError(), r.getThrowable(), Logger.ERROR);
				// Can probably retry
			}
			synchronized (runSync) {
				if (finished)
					finish();
			}
			return failure();
		}
	}
    
    // failed* - called after doPut returns
    protected boolean failedCollision(CollisionEvent e) {
	finished = true;
	return true; // collison => insert, it won't go away with a retry
    }
    
    protected boolean failedInvalidData() {
	finished = true;
	return true; // don't retry
    }
    
    // *Followed - called as soon as we see the Event
    protected void redirectFollowed(RedirectFollowedEvent e) {
        // Override in subclasses?
    }
    
    public void receive(ClientEvent ce) {
	if (ce instanceof RouteNotFoundEvent) {
	    rnf = (RouteNotFoundEvent) ce;
	} else if (!inserting && (ce instanceof DataNotFoundEvent)) {
	    dnf = (DataNotFoundEvent) ce;
	} else if (ce instanceof RedirectFollowedEvent) {
	    redirectFollowed((RedirectFollowedEvent)ce);
	} else if (ce instanceof CollisionEvent) {
	    coll = (CollisionEvent)ce;
	} else if (inserting && (ce instanceof GeneratedURIEvent)) {
	    generatedURI = ((GeneratedURIEvent)ce).getURI();
	    if(logDEBUG)
		Core.logger.log(this, "Generated URI: "+generatedURI+ " for "+
				getCheckpointName(), Logger.MINOR);
	} else if (ce instanceof DocumentNotValidEvent) {
	    Core.logger.log(this, "DocumentNotValid for "+this,
			    Logger.MINOR);
	    notValid = true;
	} else if (ce instanceof RestartedEvent) {
		if(Core.logger.shouldLog(Logger.MINOR,this)) Core.logger.log(this, "Restarted "+getCheckpointName(),
			    Logger.MINOR);
	} else if (ce instanceof NoReplyEvent) {
	    noreply = (NoReplyEvent)ce;
	} else if (ce instanceof StateReachedEvent) {
	    if(logDEBUG)
		Core.logger.log(this, ((StateReachedEvent)ce).getDescription() +
				": " + getCheckpointName(), Logger.DEBUG);
	} else if (ce instanceof PendingEvent) {
	    if(logDEBUG)
		Core.logger.log(this, "Pending "+getCheckpointName(),
				Logger.DEBUG);
	} else if (ce instanceof TransferStartedEvent) {
	    if(logDEBUG)
		Core.logger.log(this, "Transfer Started: "+getCheckpointName(),
				Logger.DEBUG);
	} else if (ce instanceof TransferCompletedEvent) {
	    if(logDEBUG)
		Core.logger.log(this, "Transfer Completed: "+getCheckpointName(),
				Logger.DEBUG);
	} else if (ce instanceof TransferFailedEvent) {
	    if(logDEBUG)
	        Core.logger.log(this, "Transfer Failed: "+getCheckpointName(),
	                Logger.DEBUG);
	}
    }
    
    boolean reallyFinished = false;
    protected void finish() {
	if(!reallyFinished) {
	    onFinish();
	    reallyFinished = true;
	}
    }
    
    protected void onFinish() {
        // Override in subclasses
    }
}
