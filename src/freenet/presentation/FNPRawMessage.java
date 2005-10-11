/*
 * This code is part of the Java Adaptive Network Client by Ian Clarke. It is
 * distributed under the GNU General Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

package freenet.presentation;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.Core;
import freenet.FieldSet;
import freenet.RawMessage;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.io.DiscontinueInputStream;
import freenet.support.io.EOFingReadInputStream;
import freenet.support.io.ReadInputStream;
import freenet.support.io.WriteOutputStream;

/**
 * This class represents a raw message in the Freenet Protocol expected by
 * Freenet. It can be created either from an InputStream or manually created
 * from scratch. It can then by piped out to an OutputStream. Methods are
 * provided for manipulation of normal fields, however the type of the message
 * and the trailing field should be set by direct manipulation of fields.
 * 
 * @author <A HREF="mailto:I.Clarke@strs.co.uk">Ian Clarke</A>
 * @author <A HREF="mailto:blanu@uts.cc.utexas.edu">Brandon Wiley</A>
 */
public final class FNPRawMessage extends RawMessage {

	/**
	 * Constructs a new RawMessage off an FNP Stream
	 * 
	 * @param i
	 *            An InputStream of decrypted FNP data
	 */
	public FNPRawMessage(InputStream i)
		throws EOFException {

		//Core.logger.log(this,"Reading message",Logger.DEBUGGING);
		this(new EOFingReadInputStream(i));
	}

	public FNPRawMessage(ReadInputStream ris)
		throws EOFException {
		fs = new FieldSet();

		try {
			// Read message type
			messageType = ris.readToEOF('\n', '\r');

			try {
				trailingFieldName = fs.parseFields(ris);
			} catch (EOFException e) {
				EOFException highLevelEOFException =
					new EOFException("incomplete FNP message - hopefully");
				highLevelEOFException.initCause(e);
				throw highLevelEOFException;
			}

			setFields(ris);
			/*
			 * } catch (EOFException e) { if (messageType != null) {
			 * Core.logger.log(this, "Stream died while reading message of
			 * type: " + messageType, Logger.ERROR); } else { // stream closed
			 * without getting a new message Core.logger.log(this, "Stream
			 * closed", Logger.DEBUGGING); }
			 */
		} catch (IOException e) {
			EOFException higherLevelEOFException =
				new EOFException("Could not parse message from stream");
			higherLevelEOFException.initCause(e);
			throw higherLevelEOFException;
		} catch (Exception e) {
			Core.logger.log(this, "Exception in RawMessage()", Logger.ERROR);
			e.printStackTrace();
		}
	}

	private void setFields(ReadInputStream in) {
		// Read and set the presentation related fields

		// setting KeepAlive
		String cvalue = fs.getString("Connection");
		close = cvalue != null && cvalue.equals("close");
		sustain = cvalue != null && cvalue.equals("sustain");

		// setting DataLength and trailing
		String dlvalue = fs.getString("DataLength");
		if (dlvalue == null) {
			trailingFieldLength = 0;
		} else {
			trailingFieldLength = Fields.hexToLong(dlvalue);
			fs.remove("DataLength");
		}

		String muxValue = fs.getString("TrailerMuxID");
		if (muxValue == null) {
			trailingFieldMuxID = 0;
		} else {
			trailingFieldMuxID = Fields.hexToInt(muxValue);
			fs.remove("TrailerMuxID");
		}
		/*
		 * trailingFieldLength = dlvalue == null ? 0 :
		 * Fields.hexToLong(dlvalue); if (dlvalue != null)
		 * fs.remove("DataLength"); if (trailingFieldLength != 0) {// we have a
		 * trailing trailingFieldStream = in; } else { trailingFieldName =
		 * null; // no trailing }
		 */
	}

	protected FNPRawMessage(
		String messageType,
		boolean close,
		boolean sustain,
		FieldSet fs,
		long trailingLength,
		String trailingName,
		DiscontinueInputStream trailing,
		int trailerMuxID) {
		super(
			messageType,
			close,
			sustain,
			fs == null ? new FieldSet() : fs,
			trailingLength,
			trailingName,
			trailing,
			trailerMuxID);
	}

	// Public Methods

	public void writeMessage(OutputStream out) throws IOException {

		WriteOutputStream writer = new WriteOutputStream(out);

		// Output message type
		writer.writeUTF(messageType, '\n');

		// Output tansport options

		if (close)
			fs.put("Connection", "close");
		else if (sustain)
			fs.put("Connection", "sustain");

		if (trailingFieldLength != 0) {
			fs.put("DataLength", Long.toHexString(trailingFieldLength));
			fs.put("TrailerMuxID", Long.toHexString(trailingFieldMuxID));
		}

		// Output message fields
		fs.writeFields(
			writer,
			(trailingFieldName == null ? "EndMessage" : trailingFieldName));
		// empty writer
		writer.flush();
	}

	public String toString() {
		StringBuffer sb = new StringBuffer(400);
		sb
			.append(messageType)
			.append("{Close=")
			.append(close)
			.append(",Sustain=")
			.append(sustain)
			.append(",DataLength=")
			.append(trailingFieldLength)
			.append(',');
		fs.toString(sb).append('}');
		return sb.toString();
	}
}
