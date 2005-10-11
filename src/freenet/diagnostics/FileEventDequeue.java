package freenet.diagnostics;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Enumeration;

import freenet.Core;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.Logger;

/**
 * Like LinkedEventDequeue, but saves the data to a file between sessions.
 */
public class FileEventDequeue extends LinkedEventDequeue {

	private File f;
	private int openLevel = 0;

	public static boolean isCached(String dir, String varName, long type) {
		File varDir = new File(dir, varName);
		return varDir.exists()
			&& new File(varDir, Long.toString(type)).exists();
	}

	public FileEventDequeue(String dir, String varName, long type)
		throws IOException {
		super();
		File p = new File(dir, varName);
		if (!p.exists() && !p.mkdirs()) {
			throw new IOException("Cannot create directory: " + p.getPath());
		}
		f = new File(p, Long.toString(type));
		if (!f.exists()) {
			//If no old data was present we have fiddle around a little bit
			//to ensure that coming open() and close() method calls
			//gets a proper initial context.
			//An unfortunate side effect with this is that we
			//will litter the FS with empty stats files but I cannot really think of 
			//a nice way to get around that issue.
			
			openLevel =1; //Pretend that we have opened a file
			ll = new DoublyLinkedListImpl(); //But it contained no reports
			close(); //And then let close() flush that data to disk
		}else
			ll = null; //Old data available but not yet read
	}

	/**
	 * Contructor for when restoring.
	 */
	public FileEventDequeue(String path) {
		this(new File(path));
	}

	public FileEventDequeue(File path) {
		super();
		f = path;
		ll = null; 
	}

	public String restoreString() {
		return f.getName();
	}

	public synchronized void open(RandomVar rv) {
		openLevel++;
		if(openLevel >1) //If we already where open..
			return;
		if (ll != null)
			throw new RuntimeException("About to open but already opened?");
		Head h = super.getHead();
		synchronized(h){
			ll = new DoublyLinkedListImpl();
			if(f.exists()) //No need reading it if it doesn't exist..
			read(h,f, rv);
			else //Ought to mean that we haven't yet used this file..
				Core.logger.log(this,"Diagnostics file '"+f.getAbsolutePath()+"' doesn't exist, starting out with empty event list",Logger.DEBUG);
				
		}
		
	}

	public synchronized void close() {
		openLevel--;
		if(openLevel>0) //Still not time to finalize the closure
			return;
		if(openLevel <0) //Should never happen
			throw new RuntimeException("Cannot close already closed list");
		Tail t = super.getTail();
		Head h = super.getHead();
		synchronized (t) {
			synchronized (h) {
				write(t, f);
				ll = null;
				tailList = null;
			}
		}
	}

	private boolean write(Tail tail, File f) {
		DataOutputStream out = null;
		try {
			out =
				new DataOutputStream(
					new BufferedOutputStream(new FileOutputStream(f)));

			for (Enumeration e = tail.elements(); e.hasMoreElements();) {
				((VarEvent) e.nextElement()).write(out);
				out.writeBoolean(e.hasMoreElements());
			}
			return true;
		} catch (IOException e) {
			Core.logger.log(
				this,
				"Failed to write diagnostics data to disk (File: "+f.getAbsolutePath()+")",
				e,
				Logger.ERROR);
			return false;
		} finally {
			if (out != null) {
				try {
					out.close();
				} catch (Throwable t) {}
			}
		}
	}

	private void read(Head h,File f, RandomVar to) {
		DataInputStream in = null;
		try {
			in =
				new DataInputStream(
					new BufferedInputStream(new FileInputStream(f)));
			do {
				h.add(to.readEvent(in));
			} while (in.readBoolean());
		} catch (IOException e) {
			Core.logger.log(
				this,
				"Failed to read diagnostics data for variable '"+to.name+"' from disk (File: "+f.getAbsolutePath()+"), using empty file.",
				e,
				Logger.ERROR);
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Throwable t) {}
			}
		}
	}
	
	public Head getHead() {
		if (ll == null)
			throw new RuntimeException("Open not called.");
		return super.getHead();
	}

	public Tail getTail() {
		if (ll == null)
			throw new RuntimeException("Open not called.");
		return super.getTail();
	}

}