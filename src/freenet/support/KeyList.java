package freenet.support;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Vector;

import freenet.Core;
import freenet.Key;
import freenet.crypt.Digest;
import freenet.support.io.ParseIOException;
import freenet.support.io.ReadInputStream;
import freenet.support.sort.HeapSorter;
import freenet.support.sort.Sortable;

/**
 * Newline seperated list of binary key values, as used in the announcement
 * messages.
 * 
 * @author oskar
 */

public class KeyList implements Sortable {

	public static void main(String[] args) throws Throwable {

		KeyList kl =
			new KeyList(
				new Key[] {
					new Key("aaaaaaaaaaaa"),
					new Key("bbbbbbbbbbbb"),
					new Key("cccccccccccc"),
					new Key("dddddddddddd")});
		OutputStream out = new FileOutputStream("kltemp");
		System.err.println(kl.streamLength());
		kl.writeTo(out);
		out.close();
		InputStream in = new FileInputStream("kltemp");
		KeyList kl2 = new KeyList();
		readKeyList(in, kl2, 100);
		System.err.println(kl2.streamLength() + "..." + kl2.v.size());

	}

	private Vector v = new Vector();

	private Key compareBase = new Key(new byte[] { 0 });

	public KeyList() {
	}

	public KeyList(byte[][] list) {
		for (int i = 0; i < list.length; i++)
			v.addElement(new Key(list[i]));
	}

	public KeyList(Key[] list) {
		for (int i = 0; i < list.length; i++)
			v.addElement(list[i]);
	}

	/**
	 * Reads a list from the InputStream, to no more than entryLimit entries.
	 * 
	 * @param kl
	 *            a KeyList to read the entries into
	 */
	public static void readKeyList(InputStream in, KeyList kl, int entryLimit)
		throws IOException {
		ReadInputStream rin = new ReadInputStream(in);
		int i = 0;
		try {
			while (true) {
				String s = rin.readToEOF('\n', '\r');
				if (i++ > entryLimit)
					throw new ParseIOException("KeyList longer than allowed.");
				kl.addEntry(new Key(s));
			}
		} catch (EOFException e) {
			return;
		} catch (NumberFormatException e) {
			throw new ParseIOException("CorruptKeyList");
		}
	}

	public final void addEntry(Key k) {
		v.addElement(k);
	}

	public final void addEntry(byte[] b) {
		v.addElement(new Key(b));
	}

	public final Enumeration keys() {
		return v.elements();
	}

	public final Key[] toKeyArray() {
		synchronized (v) {
			Key[] ret = new Key[v.size()];
			v.copyInto(ret);
			return ret;
		}
	}

	/**
	 * For sortable. Don't do this while reading the KL as a stream.
	 */
	public final void swap(int a, int b) {
		Object o = v.elementAt(a);
		v.setElementAt(v.elementAt(b), a);
		v.setElementAt(o, b);
	}

	/**
	 * Sorts the list using freenet.support.sort.HeapSorter. Don't do this
	 * while reading kl as stream.
	 */
	public final void sort() {
		if (v.size() > 1)
			HeapSorter.heapSort(this);
	}

	/**
	 * Removes any duplicates from the list. The list must be sorted for this
	 * to work properly.
	 */
	public void prune() {
		if (v.size() > 1) {
			Key k = (Key) v.elementAt(0);
			int i = 1;
			while (i < v.size()) {
				Key next = (Key) v.elementAt(i);
				if (k.equals(next)) {
					v.removeElementAt(i);
				} else {
					++i;
					k = next;
				}
			}
			v.trimToSize();
		}
	}

	/**
	 * Removes any duplicates from the list. The list must be sorted for this
	 * to work properly. Afterwards, the list is reduced in size to the given
	 * limit.
	 */
	public final void prune(int limit) {
		prune();
		if (v.size() > limit)
			v.setSize(limit);
	}

	/**
	 * Compares the key values either to a null key or the key set by
	 * setCompareBase(). Closer to the key is consider lesser. If the keys are
	 * equal, it will return 0, but if the keys are unequal and equidistant
	 * from the compare base, the key that comes first lexicographically will
	 * be considered the lesser.
	 */
	public final int compare(int a, int b) {
		Key ka = (Key) v.elementAt(a);
		Key kb = (Key) v.elementAt(b);

		int cmp = compareBase.compareTo(ka, kb);
		return cmp == 0 ? ka.compareTo(kb) : cmp;

		//return ( ka.equals(kb) ? 0 :
		//         ( compareBase.isCloserTo(ka, kb) ?
		//           - 1 :
		//           ( 1)));
	}

	public final void setCompareBase(Key k) {
		this.compareBase = k;
	}

	/**
	 * Returns the number of entries in the list
	 */
	public final int size() {
		return v.size();
	}

	/**
	 * Returns the number of bytes in the list total
	 */
	public int byteLength() {
		int total = 0;
		Enumeration e = v.elements();
		while (e.hasMoreElements())
			total += ((Key) e.nextElement()).length();
		return total;
	}

	/**
	 * Returns the number of bytes in the \n delimited hex list produced by
	 * getStream.
	 * 
	 * @return byteLength() * 2 + size()
	 */
	public final int streamLength() {
		return byteLength() * 2 + v.size();
	}

	/**
	 * Returns a stream of newline seperated hex entries. The stream will be
	 * streamLength() bytes long (unless you add more entries while reading).
	 * The stream acts against the list, so using swap while reading is
	 * unsmart.
	 */
	public final InputStream getStream() {
		return new KeyListInputStream();
	}

	/**
	 * Writes the contens to the outputStream.
	 */
	public void writeTo(OutputStream out) throws IOException {
		InputStream in = new KeyListInputStream();
		int i;
		byte[] b = new byte[Core.blockSize];

		while ((i = in.read(b)) != -1) {
			out.write(b, 0, i);
		}

		out.flush();
	}

	/**
	 * Returns the cummulative hash of this list according to the formula:
	 * hashN = hash(N + hashN-1) (+ is concat, hash0 is "") using the provided
	 * digest. The hash is off the raw byte values, newlines are not included.
	 */
	public byte[] cumulativeHash(Digest d) {
		byte[] r = new byte[d.digestSize() >> 3];
		byte[] c;
		Enumeration e = v.elements();
		if (e.hasMoreElements()) {
			d.update(((Key) e.nextElement()).getVal());
			d.digest(true, r, 0);
			//            System.err.println(HexUtil.bytesToHex(r));
		}

		while (e.hasMoreElements()) {
			c = ((Key) e.nextElement()).getVal();
			//  System.err.println("C: " + HexUtil.bytesToHex(c));
			d.update(c);
			d.update(r);
			d.digest(true, r, 0);
			//   System.err.println(HexUtil.bytesToHex(r));
		}

		return r;
	}

	/**
	 * XORs all the values of the list.
	 * 
	 * @param b
	 *            The vector to XOR them to. If vectors in the list are longer
	 *            than b, only b.length bytes of them will be considered, if
	 *            they are shorter than b, they will be null padded.
	 */
	public void xorTotal(byte[] b) {
		byte[] el;
		for (Enumeration e = v.elements(); e.hasMoreElements();) {
			el = ((Key) e.nextElement()).getVal();
			for (int j = 0; j < Math.min(b.length, el.length); j++) {
				b[j] ^= el[j];
			}
		}
	}

	private class KeyListInputStream extends InputStream {

		private int num = 0;
		private byte[] current;
		private int pos = 0;

		private boolean next() {
			if (num == v.size())
				return false;
			String vstr = ((Key) v.elementAt(num)).toString();
			char[] c = new char[vstr.length() + 1];
			vstr.getChars(0, vstr.length(), c, 0);
			c[c.length - 1] = '\n';
			current = UTF8.encode(c);
			++num;
			pos = 0;
			return true;
		}

		public int read() {
			if ((current == null || pos == current.length) && !next())
				return -1;
			return current[pos++];
		}

		public final int read(byte[] b, int off, int length)
			throws IOException {
			return priv_read(b, off, length);
		}

		private int priv_read(byte[] b, int off, int length)
			throws IOException {

			if ((current == null || pos == current.length) && !next())
				return -1;

			int rlength = Math.min(current.length - pos, length);
			System.arraycopy(current, pos, b, off, rlength);
			pos += rlength;
			if (rlength == length) {
				//System.err.println(HexUtil.bytesToHex(b));
				return rlength;
			} else {
				int r = priv_read(b, off + rlength, length - rlength);
				return r < 0 ? rlength : rlength + r;
			}
		}

		public int available() {
			if ((current == null || pos == current.length) && !next()) {
				return 0;
			}
			return current.length - pos;
		}
	}
}
