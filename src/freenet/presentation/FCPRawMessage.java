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

public final class FCPRawMessage extends RawMessage {

	/**
	 * Constructs a new RawMessage off an FCP Stream
	 * 
	 * @param i
	 *            An InputStream of decrypted FNP data
	 */
	public FCPRawMessage(InputStream i)
		throws EOFException {

		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);

		if (logDEBUG)
			Core.logger.log(this, "Reading FCP message", Logger.DEBUG);
		EOFingReadInputStream ris = new EOFingReadInputStream(i);
		fs = new FieldSet();

		try {
			// Read message type
			messageType = ris.readToEOF('\n', '\r');

			if (logDEBUG)
				Core.logger.log(
					this,
					"Message type: " + messageType,
					Logger.DEBUG);

			//            System.out.println(messageType);
			try {
				trailingFieldName = fs.parseFields(ris);
				if (logDEBUG)
					Core.logger.log(
						this,
						"Read whole message: trailing field name "
							+ trailingFieldName,
						Logger.DEBUG);
			} catch (EOFException e) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Incomplete message: " + e,
						e,
						Logger.DEBUG);
				EOFException highLevelEOFException =
					new EOFException("incomplete fcp message - hopefully");
				highLevelEOFException.initCause(e);
				throw highLevelEOFException;
			}
			setFields(ris);
			/*
			 * } catch (EOFException e) { if (messageType != null) {
			 * Core.logger.log(this, "Stream died while reading message of
			 * type: " + messageType, Logger.ERROR); } else { // stream closed
			 * without getting a new message Core.logger.log(this, "Stream
			 * closed", Logger.DEBUG); }
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
		close = true;
		sustain = true;
		// setting DataLength and trailing
		trailingFieldLength = 0;
		String dlvalue = fs.getString("DataLength");
		if (dlvalue != null) {
			trailingFieldLength = Fields.hexToLong(dlvalue);
			//fs.remove("DataLength");
		}
	}

	protected FCPRawMessage(
		String messageType,
		boolean close,
		FieldSet fs,
		long trailingLength,
		String trailingName,
		DiscontinueInputStream trailing) {
		super(
			messageType,
			close,
			true,
			fs == null ? new FieldSet() : fs,
			trailingLength,
			trailingName,
			trailing,
			0);
		// FCP is not muxed
	}

	// Public Methods

	public void writeMessage(OutputStream out) throws IOException {

		WriteOutputStream writer = new WriteOutputStream(out);

		// Output message type
		writer.writeUTF(messageType, '\n');

		// Output tansport options
		if (trailingFieldLength != 0)
			fs.put("Length", Long.toHexString(trailingFieldLength));

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
			.append(',');
		fs.toString(sb).append('}');
		return sb.toString();
	}

}
