/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;

import freenet.Core;
import freenet.fs.dir.FileNumber;
import freenet.fs.dir.FilePattern;

/**
 * A simple DataObjectStore implementation backed by a skiplist that
 * serializes to a single file.
 * 
 * Flush() is slow, everything else is pretty fast.
 *
 * @author oskar
 */

public class SimpleDataObjectStore implements DataObjectStore {

    private static final int FILE_VERSION = 1;
    private static final long FILE_CHECKSTRING = 0x0300010014260592l;

    private final Skiplist contents = new Skiplist(64);
	private final Hashtable hash = new Hashtable(); //For faster random access to the items 
    private File[] targets;
    private int saveFile = 0;

    public boolean truncated = false;
    
    private static boolean logDebug=true;

    /**
     * Make a new DataObjectsStore. If there is more than one file,
     * then they will be used in turn at the checkpoints, and if one
     * is corrupt on load we can fall back on the previous.
     * @param  targets   Files to use
     * @param  loadfrom  The index of the file to attempt to load data from
     */
    public SimpleDataObjectStore(File[] targets,
                                 int loadfrom) throws IOException {
		logDebug=Core.logger.shouldLog(Logger.DEBUG,this);
        this.targets = targets;
        //QuickSorter.quickSort(new ArraySorter(targets, FDC));
        //for (int i = 0 ; i < targets.length ; i++) {
        //    System.err.println("LM: " + targets[i].lastModified()
        //                       + ", " + targets[i]);
        if (targets[loadfrom].exists()) {
            //System.err.println("LOADFROM: " + targets[loadfrom]);
            preload(targets[loadfrom]);
            // start in a different one then where we loaded from
            saveFile = (loadfrom + 1) % targets.length;
        } 
    }


    // loads the bytes into memory so they can be resolved. The file
    // is never reread after this.
	private void preload(File target) throws IOException {
		DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(target)));

		try {
			if (in.readLong() != FILE_CHECKSTRING)
				throw new IOException("File corrupt.");
			if (in.readInt() != FILE_VERSION)
				throw new IOException("Version mismatch.");

			int isize = in.readInt();
			Core.logger.log(this, "Preloading " + isize + " entries.", Logger.DEBUG);

			for (int i = 0; i < isize; i++) {
				try {
					DOWrapper dow = newDOWrapper(in);
					//System.err.println(i + " : " + dow);
					if (contents.treeInsert(dow, true) != null) {
						// fucked up file on disk
						throw new IOException("Duplicates found reading in file!");
					}
					if (hash.put(dow.getObject(), dow) != null) {
						// fucked up file on disk
						throw new IOException("Duplicates found reading in file!");
					}
				} catch (EOFException e) {
					Core.logger.log(this, "Size was wrong reading in SimpleDataObjectStore (Got EOF after "+i+"/"+isize+" items read). Truncating to "+i+" items. Datafile name: '"+target.getAbsolutePath()+"', datafile size: "+target.length(),new Exception("debug"), Logger.ERROR);
					truncated = true;
					break;
				}
			}
			in.close();
		} finally { //Ensure that we close the file before leaving this scope (works around windows filelocking nastyness)
			in.close();
		}
		//checkSize();
	}
    
    /**
     * Copy all the data from another DataObjectStore into this one.
     */
    public void copyAll(DataObjectStore source) throws IOException {
        for (Enumeration e = source.keys(true) ; e.hasMoreElements();) {
        	
        	// Clone the FileNumber without preserving the directory ID.
            FileNumber fn = 
				new FileNumber(((FileNumber) e.nextElement()).getByteArray());
            
            try {
                DataObject obj = source.get(fn);
                set(new DOWrapper(fn, obj));
            } catch (DataObjectUnloadedException dop) {
                // that's OK, we can copy the data representation.
                int l = dop.getDataLength();
                DataInputStream in = dop.getDataInputStream();
                byte[] data = new byte[l];
                in.readFully(data);
                //System.err.println("COPYING: " + fn);
                set(new DOWrapper(fn, data));
            }
        }
    }


    /**
     * Write changes to the backing storage.
     */
    public void flush() throws IOException {
		Core.logger.log(this, "Trying to write to " + targets[saveFile].getAbsolutePath(), Logger.DEBUG);
		if(targets[saveFile].exists()){ //Separate out removal of old file from creation of new one..
			if(!targets[saveFile].delete())
				throw new IOException("Failed to delete old storage file '"+targets[saveFile].getAbsolutePath()+"'");
		}
		DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(targets[saveFile])));
		try {
			out.writeLong(FILE_CHECKSTRING);
			out.writeInt(FILE_VERSION); // a version indicator
			int size = hash.size();
			out.writeInt(size);

			// writing objects to a buffer first, because the current most
			// common DataObjects have lousy implementations of getDataLength.
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
			DataOutputStream objout = new DataOutputStream(buffer);

			//TODO: What if the number of items in the walk doesn't match the size
			//param above? Can that ever happen? For now, log a message..
			//TODO: Will the treewalk still work if items are added to or
			//remved from the underlying tree?
			Walk w = contents.treeWalk(true);  
			DOWrapper o;
			int written =0;
			while ((o = (DOWrapper) w.getNext()) != null) {
				o.writeTo(out, buffer, objout);
				buffer.reset();
				written++;
			}
			out.close();
			
			if(written > size){
				Core.logger.log(this,"written larger than size ("+written+">"+size+")",Logger.ERROR);
				return; //Make sure that we write to the same file next time
			}
			if(written < size){
				Core.logger.log(this,"written smaller than size ("+written+"<"+size+")",Logger.ERROR);
				return; //Make sure that we write to the same file next time
			}
			

			// go to next file only if succeeded (will leave us with at least one working file hopefully...).
			saveFile = (saveFile + 1) % targets.length;
		} finally {
			//Close file manually, dont wait for the GC to do it for us
			//This is fairly important on windows since windows generally 
			//doesn't allow operations on open files.
			out.close();
		}
	}

    /**
     * @return  the DataObject for the given key,
     *          or null if not found
     * 
     * @throws DataObjectUnloadedException
     *         whenever an instance is found in serial form only
     */
    public DataObject get(FileNumber key)
		throws DataObjectUnloadedException {
	
        DOWrapper res = (DOWrapper) hash.get(key);

		if (res == null)
            return null;
	
        return res.getDataObject();
    }

    /**
     * Although accessing the object through get() and modifying it
     * will cause other callers to see the modified object, it is
     * still necessary to call this method to trigger reserialization
     * of the object in the backing store.
     */
    public void set(FileNumber key, DataObject o) {
        set(new DOWrapper(key, o));
    }
     
    private synchronized void set(DOWrapper data) {
        contents.treeInsert(data,true);
		hash.put(data.getObject(),data);
    }

    /**
     * @return  true, if there is a data object stored under the key
     */
    public boolean contains(FileNumber key) {
        return hash.containsKey(key);
    }

    /**
     * Erases an object.
     * @return  true, if something was removed
     */
    public synchronized boolean remove(FileNumber key) {
		Object r = hash.remove(key);
		if(r != null) //Then 'contents' doesn't have it either, save some processing time
			contents.treeRemove(key);
        return r != null;
    }

    /*  protected void checkSize() {
		int x = 0;
		Walk w = contents.treeWalk(true);
		while(w.getNext() != null) x++;
		if(x != size) throw new IllegalStateException("checkSize failed!");
		} */
    
    /**
     * @return  an enumeration of FileNumber
     */
    public Enumeration keys(boolean ascending) {
        return new WalkEnumeration(new FNWalk(contents.treeWalk(ascending)));
    }

    public Enumeration keys(FileNumber start, boolean ascending) {
		return new WalkEnumeration(new FNWalk(contents.treeWalk(start, true, 
																ascending)));
    }
    
    /**
     * @return  the subset of keys matching the pattern
     */
    public Enumeration keys(FilePattern pat) {
        return new WalkEnumeration(FileNumber.filter(pat, 
                                                     new FNWalk(contents.treeWalk(pat.key(), true, true))));
    }


    // These belong in DOWrapper below, but some JVMs have had issues with
    // static methods in internal classes.
    private static DOWrapper newDOWrapper(DataInputStream in) 
        throws IOException {

        int fields = in.readInt();
        byte[] fnbs = readField(in);
        byte[] data = readField(in);
        
        for (int i = fields - 2 ; i > 0 ; i--) {
            readField(in); // forward compatibility
        }

        return new DOWrapper(new FileNumber(fnbs), data);
    }

    private static byte[] readField(DataInputStream in) throws IOException {
        int s = in.readInt();
		if(s < 0 || s > 2<<25 /*32MB*/) {
			// Ridiculous! (FIXME?)
			// The file is corrupt
			throw new IOException("The file is corrupt");
		}
		byte[] bs = new byte[s];
        in.readFully(bs);
        return bs;
    }

    private static class DOWrapper extends Skiplist.SkipNodeImpl {

        public DataObject resolved;
        public byte[] data;

        public DOWrapper(FileNumber key, DataObject resolved) {
            super(key);
            this.resolved = resolved;
        }

        public DOWrapper(FileNumber key, byte[] data) {
            super(key);
            this.data = data;
        }


        /*
         * out: actual outputstream
         * buffer: a bytearray OS reset before each call
         * objout: feeds into the buffer
         */
        private final void writeTo(DataOutputStream out,
                                   ByteArrayOutputStream buffer,
                                   DataOutputStream objout) throws IOException{
            out.writeInt(2);
            
            byte[] fnbs = ((FileNumber) comp).getByteArray();
            out.writeInt(fnbs.length);
            out.write(fnbs);

            if (resolved != null) {
                resolved.writeDataTo(objout); // goes right into buffer
                out.writeInt(buffer.size());
                buffer.writeTo(out);
            } else {
                out.writeInt(data.length);
                out.write(data);
            }
        }

        public DataObject getDataObject() throws DataObjectUnloadedException {
            if (resolved != null)
                return resolved;
            else {
                throw new MyException();
            }
        }


        private class MyException extends DataObjectUnloadedException {
            
            public MyException() {}

            public int getDataLength() {
                return data.length;
            }

            public DataInputStream getDataInputStream() {
                return new DataInputStream(new ByteArrayInputStream(data));
            }

            public void resolve(DataObject o) {
                resolved = o;
                data = null;
            }
        }
        
    }

    private static class FNWalk implements Walk {

        public Walk source;

        public FNWalk(Walk source) {
            this.source = source;
        }

        public Object getNext() {
            DOWrapper o = (DOWrapper) source.getNext();
            return o == null ? null : o.comp;
        }

    }

    private static class FileDateComparator implements Comparator {
        public int compare(Object o1, Object o2) {
            long f1 = ((File) o1).lastModified();
            long f2 = ((File) o2).lastModified();
            return f1 > f2 ? -1 : f1 == f2 ? 0 : 1;
        }
    }

}
