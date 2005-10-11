package freenet.support;

import java.io.*;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.Core;

/**
 * A pool of PooledRandomAccessFiles. These are similar to 
 * java.io.RandomAccessFiles, but we have a maximum number of file
 * descriptors. If there are more RAFs than that allocated, we keep only
 * the MRU fds open.
 *
 * Synchronized.
 */
public class RealRandomAccessFilePool implements RandomAccessFilePool {
    final int maxOpenFiles;
	volatile int pendingClosures = 0; //Sync on RealRandomAccessFilePool.this when modifying, on nothing while reading
    private volatile int totalOpenFiles = 0;
    protected final LRUQueue queue = new LRUQueue();

    public int totalOpenFiles() {
        return totalOpenFiles;
    }

    public int maxOpenFiles() {
        return maxOpenFiles;
    }

    public RealRandomAccessFilePool(int maxFDsOpen) {
        this.maxOpenFiles = maxFDsOpen;
        if(maxFDsOpen < 2) throw new IllegalArgumentException("Unreasonable maximum number of open files "+maxFDsOpen);
    }

    public PooledRandomAccessFile open(File f, String mode) throws IOException {
        return new MyPooledRandomAccessFile(f, mode);
    }

    class MyPooledRandomAccessFile implements PooledRandomAccessFile {
        final File filename;
        final String mode;
        RandomAccessFile raf;
        boolean closed = false;
        long position = 0; // Not guaranteed to be up to date unless raf closed

        public MyPooledRandomAccessFile(File f, String mode) throws IOException {
            this.filename = f;
            this.mode = mode;
            reopen();
        }

        void reopen() throws IOException {
			synchronized (this) {
				if (raf != null)
					return;
                if(closed) 
                    throw new IOException("Already closed or failed to save position");
				synchronized (RealRandomAccessFilePool.this) {
					queue.push(this); //We are now a candidate for closure..
					totalOpenFiles++;
                        }
				raf = new RandomAccessFile(filename, mode); //..or well.. at least now.
				if (position != 0)
					raf.seek(position);
                    }
			if ((totalOpenFiles-pendingClosures) > maxOpenFiles) { //Doublechecked locking bug situation.. but that doesn't really matter here..(it is ok if we overshoot the limit somewhat)
				LinkedList lClose = new LinkedList();
				synchronized (RealRandomAccessFilePool.this) {
					//Loop until we are below the maxOpenFiles limit again.
					//Include lClose.size() in the calculation since totalOpenFiles
					//won't be decremented until we call closeRAF() on the pop'ed files
					//and due to deadlock issues we wont do that until later on in this method
					//TODO: Possible problem with this is that we might undershoot the limit sometimes (see the error log message below)
					while ((totalOpenFiles - pendingClosures) >= maxOpenFiles) {
						//Pop LRU file for close..
						PooledRandomAccessFile fClose = (PooledRandomAccessFile) (queue.pop());
						if(fClose==null){ //Try to survive.. but *do* log a message about it
							Core.logger.log(this,"Ouch! LRU is empty but we still think that we should remove items from it. Is limit too low?!",Logger.ERROR);
							break;
						}else{
                    if(fClose == this)
                        throw new IllegalStateException("aaaaargh! Popped self!");
                }
						pendingClosures++;
						lClose.add(fClose);
					}
				}
				for (Iterator it = lClose.iterator(); it.hasNext();) {
					((PooledRandomAccessFile) it.next()).closeRAF();
				}
				synchronized (RealRandomAccessFilePool.this){
					pendingClosures = pendingClosures-lClose.size();
				}
            }
        }
    
        public long length() throws IOException {
			RandomAccessFile r = this.raf;
            if(r != null)
				return r.length();
			else
            return filename.length();
        }
    
        public synchronized void seek(long pos) throws IOException {
            reopen();
            raf.seek(pos);
            position = pos;
        }
    
        /**
         * Not in RandomAccessFile API, but because of synchronization
         * issues, it is better to put it here */
        public void sync() throws IOException {
			RandomAccessFile r = this.raf;
            // No need to reopen
            if(r != null)
				r.getFD().sync();
        }
    
        public synchronized int read() throws IOException {
            reopen();
            return raf.read();
        }
    
        public synchronized int read(byte[] buf, int off, int len) 
            throws IOException {
            reopen();
            return raf.read(buf, off, len);
        }
    
        public synchronized void write(int b) throws IOException {
            reopen();
            raf.write(b);
        }
    
        public synchronized void write(byte[] b, int offset, int len) 
            throws IOException {
            reopen();
            raf.write(b, offset, len);
        }
    
        public long getFilePointer() throws IOException {
			RandomAccessFile r = this.raf;
            if(r == null)
				return position;
            else
				return r.getFilePointer();
        }
    
        public synchronized void closeRAF() {
            if(raf == null) return;
            synchronized(RealRandomAccessFilePool.this) {
                if(totalOpenFiles < maxOpenFiles) return;
            }
            try {
                 position = raf.getFilePointer();
            } catch (IOException e) {
                closed = true;
                position = 0;
            } catch (Throwable t) {
                Core.logger.log(this, "Caught "+t+" saving file pointer for "+
                        raf, Logger.ERROR);
            }
            try {
                raf.close();
            } catch (IOException e) {
            } catch (Throwable t) {
                Core.logger.log(this, "Caught "+t+" closing "+raf, 
                        Logger.ERROR);
            // assume it is closed...
            }
            synchronized(RealRandomAccessFilePool.this) {
                totalOpenFiles--;
            }
            raf = null;
        }
    
        public synchronized void close() throws IOException {
            closed = true;
            if (raf != null) {
                raf.close();
                raf = null;
                synchronized(RealRandomAccessFilePool.this) {
                    totalOpenFiles--;
                }
            }
        }
    }
}
