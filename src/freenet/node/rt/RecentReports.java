package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Enumeration;

import net.i2p.util.NativeBigInteger;

import freenet.Key;

/**
 * A class for keeping track of the recent reports that has come in to the
 * Estimator.
 */
class RecentReports implements Serializable {

	//	I need to see these from subclass --zab
	protected static int RECENT_LENGTH = 16;
	protected BigInteger recentKeys[] = new BigInteger[RECENT_LENGTH];
	protected double recentTimes[] = new double[RECENT_LENGTH];
	protected int recentPtr = 0;
	protected int recentCount = 0;
	protected static int SERIAL_MAGIC = 0x01725250;

	public Object clone() {
	    return new RecentReports(this);
	}
	
	//TOTHINK: this could grow fast, may be better off with long
	public RecentReports() {
		recentPtr = recentCount = 0;
	}
	public RecentReports(DataInputStream i) throws IOException {
		try {
		    int magic = i.readInt();
		    if(magic != SERIAL_MAGIC)
		        throw new IOException("Unrecognized magic");
		    int ver = i.readInt();
		    if(ver != 1)
		        throw new IOException("Unsupported version "+ver);
			recentPtr = i.readInt();
			if (recentPtr >= RECENT_LENGTH)
				throw new IOException("recentPtr too high");
			if (recentPtr < 0)
				throw new IOException("invalid negative recentPtr");
			recentCount = i.readInt();
			if (recentCount < 0)
				throw new IOException("invalid negative recentCount");
			int x = 0;

			for (x = 0; x < recentTimes.length; x++) {
				recentTimes[x] = i.readDouble();
				if (recentTimes[x] < 0)
					throw new IOException("negative value");
				byte[] b = new byte[Key.KEYBYTES];
				i.readFully(b);
				recentKeys[x] = new NativeBigInteger(1, b);
				if (recentKeys[x].signum() == -1)
					throw new IOException("negative key");
				if (recentKeys[x].compareTo(Key.KEYSPACE_SIZE) == 1)
					throw new IOException("exceeded keyspace");
			}
		} catch (IOException ioe) {
			//Sane initial values in case someone wants to use us
			//even though the reading above failed
			recentPtr = recentCount = 0;
			throw ioe;
		}
	}
	/**
     * @param reports
     */
    public RecentReports(RecentReports reports) {
        this.recentCount = reports.recentCount;
        this.recentKeys = (BigInteger[]) reports.recentKeys.clone();
        this.recentPtr = reports.recentPtr;
        this.recentTimes = (double[]) reports.recentTimes.clone();
    }

	synchronized void report(BigInteger n, double usec) {
		recentKeys[recentPtr] = n;
		recentTimes[recentPtr] = usec;
		recentCount++;
		recentPtr++;
		if (recentPtr >= RECENT_LENGTH)
			recentPtr = 0;
	}

	synchronized LowestHighestPair getLowestAndHighest() {
		LowestHighestPair l = new LowestHighestPair();
		int x = recentPtr;
		if (recentCount > RECENT_LENGTH)
			x = RECENT_LENGTH;
		for (int i = 0; i < x; i++) {
			if (recentTimes[i] < l.lowest)
				l.lowest = recentTimes[i];
			if (recentTimes[i] > l.highest)
				l.highest = recentTimes[i];
		}
		return l;
	}

	public synchronized void writeTo(DataOutputStream o)
		throws IOException {
	    o.writeInt(SERIAL_MAGIC);
	    o.writeInt(1);
		o.writeInt(recentPtr);
		o.writeInt(recentCount);
		for (int i = 0; i < recentTimes.length; i++) {
			o.writeDouble(recentTimes[i]);
			byte[] b;
			if (recentKeys[i] == null) {
				b = new byte[Key.KEYBYTES];
				for (int ii = 0; ii < Key.KEYBYTES; ii++)
					b[ii] = 0;
			} else {
				b = recentKeys[i].toByteArray();
				if (b.length > Key.KEYBYTES + 1)
					throw new IllegalStateException(
						"Key too long in serializing: " + b.length);
			}
			if (b.length < Key.KEYBYTES) {
				int skip = Key.KEYBYTES - b.length;
				o.write(Key.KEYBYTESZeroArray,0, skip);
				o.write(b);
			} else
				o.write(b, b.length - Key.KEYBYTES, Key.KEYBYTES);
			// in case of zero padding
		}
	}
	Enumeration enumeration() {
		return new RecentEnumeration();
	}
	class LowestHighestPair implements Serializable {
		double lowest = Integer.MAX_VALUE;
		double highest = 0;
	}
	class KeyTimePair implements Serializable {
		BigInteger key;
		double time;
		public KeyTimePair(BigInteger key, double time) {
			this.key = key;
			this.time = time;
		}
	}
	class RecentEnumeration implements Enumeration {
		int location = 0;

		/*
		 * @see java.util.Enumeration#hasMoreElements()
		 */
		public boolean hasMoreElements() {
			return (
				location < RECENT_LENGTH && recentKeys[location] != null);
			//Stop early if the history hasn't yet filled up
		}

		/*
		 * @see java.util.Enumeration#nextElement()
		 */
		public Object nextElement() {
			location++;
			return new KeyTimePair(
				recentKeys[location - 1],
				recentTimes[location - 1]);
		}

	}
}
