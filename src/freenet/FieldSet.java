package freenet;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import freenet.crypt.Digest;
import freenet.presentation.FieldDefinitionsTable;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.UTF8;
import freenet.support.io.ReadInputStream;
import freenet.support.io.WriteOutputStream;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.QuickSorter;
import freenet.support.HexUtil;

/**
 * This is a wrapper for a Map containing strings and other FieldsSets that
 * also includes a parser/printer in the format used for fields in normal
 * Freenet messages. FieldSets are used for almost all data serializing
 * (DataProperties, DataStore to disk, client metadata, etc) in Freenet.
 * <p>
 * Multithreading: <code>FieldSet</code> is <strong>not thread safe
 * </strong>.
 * </p>
 * 
 * @author oskar
 * @author syoung (maintenance, optimization)
 */
public class FieldSet extends AbstractMap {
	// TODO FieldSet should be named FieldMap in Collections terminology.
	// FIXME FieldSet should properly implement the Map interface, including
	// making the get() method the proper Object get(Object) prototype.

	private static boolean logDEBUG;

	/**
	 * These are non-UTF8-legal bytes used in hashing the FieldSet.
	 */
	private static final byte HASH_SUBSET = (byte) 0xFD; // Typically .
	private static final byte HASH_EQUALS = (byte) 0xFE; // Typically =
	private static final byte HASH_NEWLINE = (byte) 0xFF; // Typically \n

	private final Map fields;

	/**
	 * Interface to filter field names and values when parsing.
	 */
	public static interface Filter {
		public String filter(String s);
	}

	private final static class VoidFilter implements Filter {
		public final String filter(String s) {
			return s;
		}
	}

	private static final Filter voidF = new VoidFilter();

	/**
	 * Construct an empty FieldSet
	 */
	public FieldSet() {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		this.fields = new HashMap();
	}

	/**
	 * Construct a FieldSet from the given stream using the default separators
	 */
	public FieldSet(ReadInputStream in) throws IOException {
		this();
		parseFields(in);
	}

	/**
	 * Copy constructor. Fields ARE copied.
	 */
	protected FieldSet(FieldSet fs) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		this.fields = new HashMap(fs.fields);
	}

	/**
	 * Get a string value
	 * <p>
	 * WARNING: Use getString(name) instead. This method does not conform to
	 * the interface for Map and will be changed to return an Object.
	 * </p>
	 * 
	 * @param name
	 *            the name of the field
	 * @return The field interpreted as a string
	 */
	public Object get(Object name) {
		return fields.get(name);
	}

	/**
	 * Get a string value.
	 * 
	 * @param name
	 *            the name of the field
	 * @return The field interpreted as a string
	 */
	public String getString(String name) {
		Object o = fields.get(name);
		if (o != null && o instanceof String) {
			return (String) o;
		}
		return null;
	}

	/**
	 * Get a field subset.
	 * 
	 * @param name
	 *            the name of the field
	 * @return The field interpreted as a subset of fields. Null if no such
	 *         field is found.
	 */
	public FieldSet getSet(String name) {
		Object o = fields.get(name);
		if (o == null || !(o instanceof FieldSet))
			return null;
		return (FieldSet) o;
	}

	public FieldSet makeSet(String name) {
		FieldSet fs = getSet(name);
		if (fs == null) {
			fs = newFieldSet();
			fields.put(name, fs);
		}
		return fs;
	}

	/**
	 * Remove a field
	 * 
	 * @param name
	 *            The name of the field to remove
	 */
	public final Object remove(String name) {
		return fields.remove(name);
	}

	/**
	 * Add a string value. This will overwrite any old values for this field.
	 * 
	 * @param name
	 *            The name of the field.
	 * @param value
	 *            The value to set to the field.
	 */
	public final void put(String name, String value) {
		fields.put(name, value);
	}

	/**
	 * Add a subset. This will overwrite any old values for this field.
	 * 
	 * @param name
	 *            The name of the field.
	 * @param fs
	 *            The value to set to the field.
	 */
	public final void put(String name, FieldSet fs) {
		fields.put(name, fs);
	}

	/**
	 * @return Whether this fieldset is empty.
	 */
	public final boolean isEmpty() {
		return fields.isEmpty();
	}

	/**
	 * @return An enumeration of the field names in this FieldSet.
	 */
	public final Set keysSet() {
		return fields.keySet();
	}

	/**
	 * @see java.util.AbstractMap#entrySet()
	 */
	public Set entrySet() {
		return fields.entrySet();
	}

	/**
	 * @return the String and FieldSet objects stored as values
	 */
	public final Collection values() {
		return fields.values();
	}

	/**
	 * @return The number of Fields in this FieldSet (not recursive)
	 */
	public final int size() {
		return fields.size();
	}

	/**
	 * @return true, if there is an entry with that name
	 */
	public boolean containsKey(String key) {
		return fields.containsKey(key);
	}

	/**
	 * Resets the FieldSet (removes all entries).
	 */
	public final void clear() {
		fields.clear();
	}

	/**
	 * Writes the fields to a stream with a standard syntax, and the given
	 * semantics, using the standard separators.
	 * 
	 * @param w
	 *            The stream to write to.
	 */
	public final void writeFields(WriteOutputStream w) throws IOException {
		writeFields(w, "End");
	}

	public final void writeFields(WriteOutputStream w, String end)
		throws IOException {
		writeFields(w, end, '\n', '=', '.');
	}

	/**
	 * Writes the fields to a stream with a standard syntax, and the given
	 * semantics.
	 * 
	 * @param w
	 *            The stream to write to.
	 * @param sep
	 *            The character used to delimit the end of a field name value
	 *            pair.
	 * @param equal
	 *            The character that delimits the field name from the field
	 *            value.
	 * @param subset
	 *            The character used to delimit subsets in the field name.
	 * @param terminate
	 *            The string to write at the end to terminate the fieldset this
	 *            must not include the character "equal" used to delimit the
	 *            name from the value in pairs.
	 */
	private final void writeFields(
		WriteOutputStream w,
		String terminate,
		char sep,
		char equal,
		char subset)
		throws IOException {
		inWriteFields(w, "", sep, equal, subset);
		w.writeUTF(terminate, sep);
	}

	// internal version, adds the preceding string necessary for recursion
	private void inWriteFields(
		WriteOutputStream w,
		String pre,
		char sep1,
		char equal,
		char subset)
		throws IOException {

		for (Iterator e = keySet().iterator(); e.hasNext();) {
			String name = (String) e.next();
			Object o = fields.get(name);
			if(o == this)
			    throw new IllegalStateException("Self registered under name "+name+" - "+super.toString()+" !!!!!");
			// Don't log toString() because will recurse
			
			if (o != null) {
				if (o instanceof String) {
					w.writeUTF(pre);
					w.writeUTF(name, equal);
						w.writeUTF((String) o, sep1);
				} else if (o instanceof FieldSet) {
					((FieldSet) o).inWriteFields(
						w,
						pre + name + subset,
						sep1,
						equal,
						subset);
				} else {
					Core.logger.log(this, "Could not interpret field '" + name + "' neither as String, nor as FieldSet, when trying to send.", Logger.MINOR);
				}
			} else {
				Core.logger.log(this, "Not writing null field: " + name, Logger.MINOR);
			}
		}
	}

	public void writeCompressed(WriteOutputStream w, Hashtable ctable)
		throws IOException {
		inWriteCompressed(w, ctable, "");
	}

	private void inWriteCompressed(
		WriteOutputStream w,
		Hashtable ctable,
		String pre)
		throws IOException {
	//Hashtable may be too heavyweight for the small number of elements
		for (Iterator e = keySet().iterator(); e.hasNext();) {
			String name = (String) e.next();

			FieldSet fs = getSet(name);
			if (fs != null) {
				fs.inWriteCompressed(w, ctable, pre + name + ".");
			} else { //(isString(name))
				String var = pre + name;
				FieldDefinitionsTable.FieldIDAndFieldType fieldInfo =
					(FieldDefinitionsTable.FieldIDAndFieldType) ctable.get(var);
				if (fieldInfo == null) {
					Core.logger.log(this, "FNC unknown field: " + var, Logger.MINOR);
					w.writeUTF(pre);
					w.writeUTF(name, '=');
					w.writeUTF(getString(name), '\n');
				} else {
					w.write((byte) (fieldInfo.fieldID | 128));
					String data = getString(name);
					if (fieldInfo.fieldType == Byte.class) {
						byte cdata = (byte) Fields.hexToInt(data);
						w.write(cdata);
					} else if (fieldInfo.fieldType == Integer.class) {
						int cdata = Fields.hexToInt(data);
						w.write((byte) (cdata >> 24));
						w.write((byte) (cdata >> 16));
						w.write((byte) (cdata >> 8));
						w.write((byte) cdata);
					} else if (fieldInfo.fieldType == Long.class) {
						long cdata = Fields.hexToLong(data);
						w.write((byte) (cdata >> 56));
						w.write((byte) (cdata >> 48));
						w.write((byte) (cdata >> 40));
						w.write((byte) (cdata >> 32));
						w.write((byte) (cdata >> 24));
						w.write((byte) (cdata >> 16));
						w.write((byte) (cdata >> 8));
						w.write((byte) cdata);
					} else if (fieldInfo.fieldType == BigInteger.class) {
						byte[] cdata = freenet.support.HexUtil.hexToBytes(data);
						w.write((byte) cdata.length);
						w.write(cdata);
					} else if (fieldInfo.fieldType == String.class) {
						w.writeUTF(data, '\n');
					} else {
						//couldn't compress the value
						w.writeUTF(data, '\n');
					}
				}

			}
		}
	}

	/**
	 * Parses fields from a stream using the standard separators.
	 * 
	 * @param in
	 *            The stream to read.
	 * @return The string encountered that lacks a field name/value delimiter
	 *         and that therefore is assumed to terminate the fieldset. (
	 *         <code>null</code> if terminated by EOF.)
	 * @exception IOException
	 *                if something goes wrong.
	 */
	public final String parseFields(ReadInputStream in) throws IOException {
		return parseFields(in, '\n', '\r', '=', '.');
	}

	/**
	 * Parses fields from a stream in a standard syntax with given semantics.
	 * The easiest way to see the syntax is probably to look at the output of
	 * writeFields() or look at the Freenet protocol specs.
	 * 
	 * @param in
	 *            The stream to read.
	 * @param sep
	 *            The character used to delimit the end of a field name value
	 *            pair.
	 * @param equal
	 *            The character that delimits the field name from the field
	 *            value.
	 * @param subset
	 *            The character used to delimit subsets in the field name.
	 * @return The string encountered that lacks a field name/value delimiter
	 *         and that therefore is assumed to terminate the fieldset. (
	 *         <code>null</code> if terminated by EOF.)
	 * @exception IOException
	 *                if something goes wrong.
	 */
	private final String parseFields(
		ReadInputStream in,
		char sep,
		char equal,
		char subset)
		throws IOException {
		return privParse(in, sep, (char) 0, equal, subset, false, voidF, voidF);
	}

	/**
	 * Parses fields from a stream in a standard syntax with given semantics.
	 * The easiest way to see the syntax is probably to look at the output of
	 * writeFields() or look at the Freenet protocol specs.
	 * 
	 * @param in
	 *            The stream to read.
	 * @param sep
	 *            The character used to delimit the end of a field name value
	 *            pair.
	 * @param ignore
	 *            The character that should be ignored if it directly precedes
	 *            the preceding seperator (used for \r)
	 * @param equal
	 *            The character that delimits the field name from the field
	 *            value
	 * @param subset
	 *            The character used to delimit subsets in the field name.
	 * @return The string encountered that lacks a field name/value delimiter
	 *         and that therefore is assumed to terminate the fieldset. (
	 *         <code>null</code> if terminated by EOF.)
	 * @exception IOException
	 *                if something goes wrong.
	 */
	private final String parseFields(
		ReadInputStream in,
		char sep,
		char ignore,
		char equal,
		char subset)
		throws IOException {
		return privParse(in, sep, ignore, equal, subset, true, voidF, voidF);
	}

	/**
	 * Parses fields from a stream in a standard syntax with given semantics.
	 * The easiest way to see the syntax is probably to look at the output of
	 * writeFields() or look at the Freenet protocol specs.
	 * 
	 * @param in
	 *            The stream to read.
	 * @param sep
	 *            The character used to delimit the end of a field name value
	 *            pair.
	 * @param ignore
	 *            The character that should be ignored if it directly precedes
	 *            the preceding seperator (used for \r)
	 * @param equal
	 *            The character that delimits the field name from the field
	 *            value
	 * @param subset
	 *            The character used to delimit subsets in the field name.
	 * @param nameFilter
	 *            A filter for field name strings
	 * @param valueFilter
	 *            A filter for field value strings
	 * @return The string encountered that lacks a field name/value delimiter
	 *         and that therefore is assumed to terminate the fieldset. (
	 *         <code>null</code> if terminated by EOF.)
	 * @exception IOException
	 *                if something goes wrong.
	 */
	private final String parseFields(
		ReadInputStream in,
		char sep,
		char ignore,
		char equal,
		char subset,
		Filter nameFilter,
		Filter valueFilter)
		throws IOException {

		return privParse(in, sep, ignore, equal, subset, true, nameFilter, valueFilter);
	}

	private final String parseFields(ReadInputStream in, char sep, char equal, char subset, Filter nameFilter, Filter valueFilter) throws IOException {
		return privParse(in, sep, (char) 0, equal, subset, false, nameFilter, valueFilter);
	}

	private String privParse(
		ReadInputStream in,
		char sep,
		char ignore,
		char equal,
		char subset,
		boolean useignore,
		Filter nameFilter,
		Filter valueFilter)
		throws IOException {

		if (logDEBUG)
			Core.logger.log(this, "privParse(" + in + ',' + sep + ',' + ignore + ',' + equal + ',' + subset + ',' + useignore + ',' + nameFilter + ',' + valueFilter + ')', Logger.DEBUG);

		// Now read field/data pairs
		String s;
		while (true) {
			try {
				if (useignore)
					s = in.readToEOF(sep, ignore);
				else
					s = in.readToEOF(sep);
			} catch (EOFException e) {
				Core.logger.log(this, "EOF before end of fieldset: readToEOF threw " + e, e, Logger.MINOR);
				throw e;
			}
			int n = s.indexOf(equal);
			if (n >= 0) { // field
				String name = s.substring(0, n);
				String data = valueFilter.filter(s.substring(n + 1));
				readField(name, data, subset, nameFilter);
			} else { // trailing
				if (logDEBUG)
					Core.logger.log(this, "Returning trailing field name " + s, Logger.DEBUG);
				return s;
			}
		}
	}

	public String parseCompressed(
		ReadInputStream in,
		freenet
			.presentation
			.FieldDefinitionsTable
			.FieldNameAndFieldType[] ctable)
		throws IOException {
		while (true) {
			int s;
			String name;
			String data;
			try {
				s = in.read();
			} catch (EOFException e) {
				Core.logger.log(this, "EOF before fieldset name: readToEOF threw " + e, e, Logger.MINOR);
				return null;
			}
			int code = s & 0x7f; //strip the high bit
			if (s == code) { // no high bit -> regular field format
				String s2 = in.readToEOF('\n', '\r');
				int n = s2.indexOf('=');
				if (n >= 0) {
					name = (char) s + s2.substring(0, n);
					data = s2.substring(n + 1);
				} else {
					return (char) s + s2; //return the trailing field name
				}
			} else {
				name = ctable[code].fieldName;

				try {
					if (ctable[code].fieldType == Byte.class) {
						int cdata = in.read();
						if (cdata == -1)
							throw new EOFException("expecting byte");
						data = Integer.toHexString(cdata);
					} else if (ctable[code].fieldType == Integer.class) {
						int cdata = 0;
						for (int i = 0; i < 4; i++) {
							cdata <<= 8;
							int b = in.read();
							if (b == -1)
								throw new EOFException(
									"expecting byte " + i + " of int");
							cdata |= b;
						}
						data = Integer.toHexString(cdata);
					} else if (ctable[code].fieldType == Long.class) {
						long cdata = 0;
						for (int i = 0; i < 8; i++) {
							cdata <<= 8;
							int b = in.read();
							if (b == -1)
								throw new EOFException(
									"expecting byte " + i + " of long");
							cdata |= b;
						}
						data = Long.toHexString(cdata);
					} else if (ctable[code].fieldType == BigInteger.class) {
						int len = in.read();
						byte[] cdata = new byte[len];
						for (int i = 0; i < len; i++) {
							int b = in.read();
							if (b == -1)
								throw new EOFException(
									"expecting byte " + i + " of bigint");
							cdata[i] = (byte) b;
						}
						data = HexUtil.bytesToHex(cdata);
					} else if (ctable[code].fieldType == String.class) {
						data = in.readToEOF('\n');
					} else {
						data = in.readToEOF('\n');
					}
				} catch (EOFException e) {
					Core.logger.log(
						this,
						"EOF before fieldset data: something threw " + e,
						e,
						Logger.MINOR);
					return null;
				}
			}
			readField(name, data, '.', voidF);
		} //while loop loops forever; normal exit path is the "return (char) s
		// + s2" line
	}

	/**
	 * Hashes the FieldSet without ignoring any strings.
	 * 
	 * @see #hashUpdate(freenet.crypt.Digest, java.lang.String[])
	 */
	public final void hashUpdate(Digest ctx) {
		hashUpdate(ctx, new String[0]);
	}

	/**
	 * Hashes the FieldSet into the digest algorithm provided. Hash is done
	 * with the UTF bytes of the key and value pairs, with the fields sorted
	 * alphabetically. Recurs on sub field-sets when encountered. Key/value and
	 * end-of-line separators are hashed in.
	 * 
	 * @param ctx
	 *            The Digest object
	 * @param ignore
	 *            A list of strings to ignore. These are ignored only in this
	 *            fieldset, not in any subsets.
	 */
	public final void hashUpdate(Digest ctx, String[] ignore) {
		hashUpdate(ctx, ignore, new byte[0]);
	}

	private void hashUpdate(Digest ctx, String[] ignore, byte[] prefix) {
		String[] fieldNames = new String[size()];
		int i = 0;
		Iterator keys = keySet().iterator();
		keys : while (keys.hasNext()) {
			String s = (String) keys.next();
			for (int j = 0; j < ignore.length; ++j)
				if (ignore[j].equals(s))
					continue keys;
			fieldNames[i++] = s;
		}
		QuickSorter.quickSort(
			new ArraySorter(fieldNames, new Fields.StringComparator(), 0, i));

		for (int j = 0; j < i; j++) {
			byte[] f = UTF8.encode(fieldNames[j]);
			Object data = fields.get(fieldNames[j]);
			if (data instanceof FieldSet) {
				byte[] newPrefix = new byte[prefix.length + f.length + 1];
				System.arraycopy(prefix, 0, newPrefix, 0, prefix.length);
				System.arraycopy(f, 0, newPrefix, prefix.length, f.length);
				newPrefix[newPrefix.length - 1] = HASH_SUBSET;
				((FieldSet) data).hashUpdate(ctx, new String[0], newPrefix);
			} else {
				ctx.update(prefix);
				ctx.update(f);
				ctx.update(HASH_EQUALS);
				ctx.update(UTF8.encode((String) data));
				ctx.update(HASH_NEWLINE);
			}
		}
	}

	/**
	 * @return A string representation of the FieldSet.
	 */
	public String toString() {
		return toString(null).toString();
	}

	/**
	 * @param buf
	 *            can be <code>null</code> to allocate a new buffer.
	 * @return StringBuffer appended to.
	 */
	public StringBuffer toString(StringBuffer buf) {

		String str;
		try {
			ByteArrayOutputStream sw = new ByteArrayOutputStream(512);
			WriteOutputStream pr = new WriteOutputStream(sw);
			inWriteFields(pr, "", ',', '=', '.');
			pr.flush();
			str = sw.toString("UTF8");
			pr.close();
		} catch (IOException e) {
			// Shouldn't ever happen
			e.printStackTrace();
			return null;
		}

		// Allocate or resize the buffer to the exact size.
		if (buf == null) {
			buf = new StringBuffer(str.length() + 2);
		} else {
			buf.ensureCapacity(buf.length() + str.length() + 2);
		}
		return buf.append('{').append(str).append('}');
	}

	/**
	 * Reads a field off a name and value
	 */
	protected void readField(String name, String value, char sep, Filter f) {
		int dot = name.indexOf(sep);
		if (dot < 0) { // value
			fields.put(f.filter(name), value);
		} else { // subset
			String fname = f.filter(name.substring(0, dot));
			FieldSet fs = getSet(fname);
			if (fs == null) {
				fs = newFieldSet();
				fields.put(fname, fs);
			}
			fs.readField(name.substring(dot + 1), value, sep, f);
		}
	}

	/**
	 * Override in descendants.
	 * 
	 * @return
	 */
	protected FieldSet newFieldSet() {
		return new FieldSet();
	}

	public boolean equals(Object other) {
		if (other == this) {
			return true;
		} else if (other == null || !(other instanceof FieldSet)) {
			return false;
		} else {
			FieldSet otherSet = (FieldSet) other;
			return fields.equals(otherSet.fields);
		}
	}

	public int hashCode() {
		return fields.hashCode();
	}

    /**
     * Like put(), but if set is null, do nothing.
     * @param string
     * @param set
     */
    public final void maybePut(String string, FieldSet set) {
        if(set != null) put(string, set);
    }
}
