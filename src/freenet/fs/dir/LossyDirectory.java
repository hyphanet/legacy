package freenet.fs.dir;

import java.io.IOException;

import freenet.Core;
import freenet.support.EnumerationWalk;
import freenet.support.Logger;
import freenet.support.Walk;

/**
 * The LossyDirectory attempts to automatically free space to
 * make room for flush and store operations.  It can do this
 * by deleting its own files, or the files of another shared
 * directory.
 * @author tavin
 */
public class LossyDirectory extends SharedDirectory {

    private final FilePattern lossyPattern;


    /**
     * A lone directory that deletes from itself when it fills.
     */
    public LossyDirectory(Directory dir) {
        this(0, dir);
    }

    /**
     * A shared directory that deletes from itself when it fills.
     */
    public LossyDirectory(int dirID, Directory dir) {
        this(dirID, dir, dirID);
    }

    /**
     * A directory that deletes from another when it fills.
     */
    public LossyDirectory(int dirID, Directory dir, int lossy) {
        this(dirID, dir, new int[] {lossy});
    }
    
    /**
     * A directory that deletes from several others when it fills.
     */
    public LossyDirectory(int dirID, Directory dir, int[] lossy) {
        super(dirID, dir);
        lossyPattern = new DirectoryIDFilePattern(lossy, true, true);
    }
    
    protected Object getSpaceLock = new Object();
    
    /**
     * Free up some space in the store.
     * Succeeds unless it exits with an exception.
     * @param size Number of bytes to attempt to free up
     * @throws IOException if not enough space can be freed
     * Note that the space will not necessarily be available by the time
     * you want to use it, so synchronize first or use a loop
     */
    public final void getSpace(long size) throws IOException {
	synchronized(getSpaceLock) {
	    boolean shouldLog = Core.logger.shouldLog(Logger.DEBUG,this);
	    while (available() < size) {
		if(shouldLog)
		    Core.logger.log(this, "Available now: "+available(),
				    Logger.DEBUG);
			Walk walk = FileNumber.filter(lossyPattern,
					  new EnumerationWalk(dir.lruKeys(true)));
		    int x = 0;
		    do {
			if(shouldLog)
			    Core.logger.log(this, "Trying to remove LRU ("+x+")",
					    Logger.DEBUG);
			FileNumber fn = (FileNumber) walk.getNext();
			if(shouldLog)
			    Core.logger.log(this, "LRU ("+x+") is "+
					    ((fn==null)?"(null)":fn.toString()),
					    Logger.DEBUG);
			x++;
			if (fn == null) {
			    IOException e = 
				new IOException("insufficient storage: tried to "+
						"delete "+x+" files out of "+
						countKeys()+", need "+size+
						" bytes, have "+available());
			    Core.logger.log(this, e.getMessage(), e, Logger.ERROR);
			    throw e;
			}
			dir.delete(fn, false);
			if(shouldLog)
			    Core.logger.log(this, "Deleted LRU ("+x+","+fn
					    +")", Logger.DEBUG);
		    }
		    while (available() < size);
	    }
	}
    }
    
    /**
     * Deletes files if necessary to allocate a buffer.
     * Succeeds unless it exits with an exception.
     * @throws IOException  if not enough space could be freed
     */
    public final Buffer forceStore(long size, FileNumber fnew) throws IOException {
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	    Core.logger.log(this, "Trying to forceStore "+fnew+" ("+size+")",
			    Logger.DEBUG);
	getSpace(size);
	Buffer buffer = null;
	synchronized(semaphore()) {
	    buffer = store(size, fnew);
	    if (buffer == null) {
		getSpace(size);
		buffer = store(size, fnew);
		if (buffer == null)
		    throw new IOException("insufficient storage");
	    }
	}
	return buffer;
    }
}



