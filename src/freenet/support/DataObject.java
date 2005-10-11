package freenet.support;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A generic Object that can be written to an output stream.
 * 
 * @see DataObjectPending
 * @see DataObjectUnloadedException
 */
public interface DataObject {

	/**
	 * Writes the serialized form of the object.
	 */
	void writeDataTo(DataOutputStream out) throws IOException;

	/**
	 * @return the byte-length of the serialized form
	 */
	int getDataLength();

	public static final int CHAR_SIZE = 1;
	public static final int DOUBLE_SIZE = 8;
	public static final int FLOAT_SIZE = 4;
	public static final int INT_SIZE = 4;
	public static final int LONG_SIZE = 8;
}