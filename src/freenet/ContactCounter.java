package freenet;

import java.util.Enumeration;
import java.util.Hashtable;

public class ContactCounter {

	public static class Record implements Cloneable, Comparable {
		public String addr = "";
		
		//TODO: Create threadsafe inc'ers for these three props?
		//Idea.. wait for java 1.5:s no-synchronization atomic counters instead?!
		public volatile int totalContacts = 1;
		public volatile int successes = 0;
		public volatile int activeContacts = 0;
		public String version = "";

		protected Record(String addr) {
			this.addr = addr;
		}

		public Object clone() {
			try {
				return super.clone();
			} catch (CloneNotSupportedException cnse) {
				throw new InternalError("ContactCounter not cloneable");
			}
		}

		public final int compareTo(Object o) {
			if (!(o instanceof Record)) {
				throw new IllegalArgumentException("Not a ContactCounter.Record.");
			}

			final int delta = ((Record) o).totalContacts - totalContacts;

			if (delta == 0) {
				return 0;
			} else if (delta > 0) {
				return 1;
			}
			return -1;
		}
	}

	public final void setLastVersion(
		String addr,
		String lastVer) {
		addr = cannonicalAddr(addr);
		Record entry = (Record) table.get(addr);
		if (entry == null) {
			//no need to add an entry for this particular method!
			//table.put(addr, new Record(addr));
		} else {
			entry.version = lastVer;
		}
	}

	public final void incTotal(String addr) {
		addr = cannonicalAddr(addr);
		Record entry = (Record) table.get(addr);
		if (entry == null) {
			//System.err.println("ContactCounter.incTotal -- added: " + addr);
			table.put(addr, new Record(addr));
		} else {
			entry.totalContacts++; //Deliberately forget about threading issues for now
		}
	}

	// Must call incTotal first.
	public final void incSuccesses(String addr) {
		addr = cannonicalAddr(addr);
		Record entry = (Record) table.get(addr);
		if (entry != null) {
			entry.successes++; //Deliberately forget about threading issues for now
		} else {
			//System.err.println("ContactCounter.incSuccesses -- unknown addr:
			// " + addr);
		}
	}

	public final void incActive(String addr) {
		addr = cannonicalAddr(addr);
		Record entry = (Record) table.get(addr);
		if (entry != null) {
			entry.activeContacts++; //Deliberately forget about threading issues for now
		} else {
			//System.err.println("ContactCounter.incActive -- unknown addr: "
			// + addr);
		}
	}

	public final void decActive(String addr) {
		addr = cannonicalAddr(addr);
		Record entry = (Record) table.get(addr);
		if (entry != null) {
			entry.activeContacts--; //Deliberately forget about threading issues for now
		} else {
			//System.err.println("ContactCounter.decActive -- unknown addr: "
			// + addr);
		}
	}

	public final Record[] getSnapshot() {
		Record[] ret;
		synchronized (table) {
			ret = new Record[table.size()];
			int i = 0;
			Enumeration elements = table.elements();
			while (elements.hasMoreElements()) {
				// Deep copy because counts can be twiddled as
				// soon as we release the lock.
				ret[i++] = (Record) (((Record) elements.nextElement()).clone());
			}
		}
		return ret;
	}

	public final int getTotal(String addr) {
		addr = cannonicalAddr(addr);
		Record entry = (Record) table.get(addr);
		if (entry == null) {
			return 0;
		} else {
			return entry.totalContacts;
		}
	}

	public final int getSuccesses(String addr) {
		addr = cannonicalAddr(addr);
		Record entry = (Record) table.get(addr);
		if (entry == null) {
			return 0;
		} else {
			return entry.successes;
		}
	}

	public final int getActive(String addr) {
		addr = cannonicalAddr(addr);
		Record entry = (Record) table.get(addr);
		if (entry == null) {
			return 0;
		} else {
			return entry.activeContacts;
		}
	}

	private final static String cannonicalAddr(String addr) {
		int pos = addr.indexOf(':');
		if (pos > 0) {
			addr = addr.substring(0, pos);
		}

		return addr;
	}

	private Hashtable table = new Hashtable(1000);
}
