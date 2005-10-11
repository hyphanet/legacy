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
import java.util.HashMap;
import java.util.Hashtable;

import freenet.Core;
import freenet.FieldSet;
import freenet.RawMessage;
import freenet.message.Accepted;
import freenet.message.AnnouncementComplete;
import freenet.message.AnnouncementExecute;
import freenet.message.AnnouncementFailed;
import freenet.message.AnnouncementReply;
import freenet.message.DataInsert;
import freenet.message.DataNotFound;
import freenet.message.DataReply;
import freenet.message.DataRequest;
import freenet.message.Identify;
import freenet.message.InsertReply;
import freenet.message.InsertRequest;
import freenet.message.NodeAnnouncement;
import freenet.message.QueryAborted;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.StoreData;
import freenet.message.VoidMessage;
import freenet.support.Logger;
import freenet.support.UTF8;
import freenet.support.io.DiscontinueInputStream;
import freenet.support.io.EOFingReadInputStream;
import freenet.support.io.ReadInputStream;
import freenet.support.io.WriteOutputStream;

/**
 * This class represents a compressed raw message in the Freenet Protocol
 * expected by Freenet. It can be created either from an InputStream or
 * manually created from scratch. It can then by piped out to an OutputStream.
 * Methods are provided for manipulation of normal fields, however the type of
 * the message and the trailing field should be set by direct manipulation of
 * fields.
 * 
 * @author Thelema
 * @author Iakin
 */
public final class FNCRawMessage extends RawMessage {

	private static FieldDefinitionsTable codeTableV1;
	//Compression code table v1 (more might come)
	private static HashMap knownCodeTables = new HashMap();
	//A map from code table digest to matching code table
	private static HashMap knownInverseCodeTables = new HashMap();
	//A map from code table digest to matching inverse code table

	//Define fields included in an ARK (FreenetURI)
	//private static final FieldDefinitionsTable.FieldDefinition[]
	// ARKFieldInfo = new FieldDefinitionsTable.FieldDefinition[]{
	//	new FieldDefinitionsTable.FieldDefinition("revision"), //No getter
	//	new FieldDefinitionsTable.FieldDefinition("encryption") //No correctly
	// named getter, is subset
	//	//TODO: Define 'encryption'
	//};
	//Define fields included in an DSAIdentity
	private static final FieldDefinitionsTable
		.FieldDefinition[] DSAIdentityInfo =
		new FieldDefinitionsTable.FieldDefinition[] {
			new FieldDefinitionsTable.FieldDefinition("y", "Y"),
			new FieldDefinitionsTable.FieldDefinition("p", "P"),
			new FieldDefinitionsTable.FieldDefinition("q", "Q"),
			new FieldDefinitionsTable.FieldDefinition("g", "G")};

	//Define fields included in a NodeReference
	private static final FieldDefinitionsTable
		.FieldDefinition[] nodeRefFieldInfo =
		new FieldDefinitionsTable
			.FieldDefinition[] {
		//No getter
		//new
		// FieldDefinitionsTable.FieldDefinition("identity","Identity",DSAIdentityInfo),
		// getter currently returns and 'Identity'. maybe an writeCompressed
		// method would be needed
		//new FieldDefinitionsTable.FieldDefinition("identityFP"), //No getter
		new FieldDefinitionsTable.FieldDefinition("physical", "Physical"),
			new FieldDefinitionsTable.FieldDefinition("sessions", "Sessions"),
			new FieldDefinitionsTable.FieldDefinition(
				"presentations",
				"Presentations"),
			new FieldDefinitionsTable.FieldDefinition(
				"ARK.revision",
				"ARKRevision"),
			new FieldDefinitionsTable.FieldDefinition(
				"ARK.encryption",
				"ARKEncryption")
		//new FieldDefinitionsTable.FieldDefinition("signature","Signature")
		//TODO: Define 'signature','identity' and, if used, 'identityFP'
	};

	private static FieldDefinitionsTable.FieldDefinition uniqueIDFieldInfo =
		new FieldDefinitionsTable.FieldDefinition("UniqueID", "ID");
	private static FieldDefinitionsTable.FieldDefinition hopsToLiveFieldInfo =
		new FieldDefinitionsTable.FieldDefinition("HopsToLive");

	static {
		//Define all node messages and the fields included in them
		//TODO: Define UniqueID getter somewhere
		try {
			codeTableV1 =
				new FieldDefinitionsTable(
					new FieldDefinitionsTable
					.MessageFieldsInfo[] {
						new FieldDefinitionsTable
						.MessageFieldsInfo(Accepted.class, //0x00
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					new FieldDefinitionsTable.FieldDefinition(
						"RequestInterval")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(AnnouncementComplete.class,
				//0x01
				new FieldDefinitionsTable.FieldDefinition[] {
					 uniqueIDFieldInfo }),
					new FieldDefinitionsTable
						.MessageFieldsInfo(AnnouncementExecute.class,
				//0x02
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					new FieldDefinitionsTable.FieldDefinition("RefSignature")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(AnnouncementFailed.class,
				//0x03
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					new FieldDefinitionsTable.FieldDefinition("Reason")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(AnnouncementReply.class,
				//0x04
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					new FieldDefinitionsTable.FieldDefinition("ReturnValue")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(DataInsert.class,
				//0x05
				new FieldDefinitionsTable.FieldDefinition[] {
					 uniqueIDFieldInfo }),
					new FieldDefinitionsTable
						.MessageFieldsInfo(DataNotFound.class,
				//0x06
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					new FieldDefinitionsTable.FieldDefinition(
						"TimeSinceQuery")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(DataReply.class,
				//0x07
				new FieldDefinitionsTable.FieldDefinition[] {
					 uniqueIDFieldInfo }),
					new FieldDefinitionsTable
						.MessageFieldsInfo(DataRequest.class,
				//0x08
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					new FieldDefinitionsTable.FieldDefinition("SearchKey"),
					hopsToLiveFieldInfo,
					new FieldDefinitionsTable.FieldDefinition("Source")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(InsertReply.class,
				//0x09
				new FieldDefinitionsTable.FieldDefinition[] {
					 uniqueIDFieldInfo }),
					new FieldDefinitionsTable
						.MessageFieldsInfo(InsertRequest.class,
				//0x0a
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					hopsToLiveFieldInfo,
					new FieldDefinitionsTable.FieldDefinition("SearchKey"),
					new FieldDefinitionsTable.FieldDefinition("Source")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(NodeAnnouncement.class,
				//0x0b
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					hopsToLiveFieldInfo,
					new FieldDefinitionsTable.FieldDefinition("Depth"),
					new FieldDefinitionsTable.FieldDefinition(
						"Announcee",
						nodeRefFieldInfo),
					new FieldDefinitionsTable.FieldDefinition(
						"Source",
						nodeRefFieldInfo),
					new FieldDefinitionsTable.FieldDefinition("CommitValue")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(QueryAborted.class,
				//0x0c
				new FieldDefinitionsTable.FieldDefinition[] {
					 uniqueIDFieldInfo }),
					new FieldDefinitionsTable
						.MessageFieldsInfo(QueryRejected.class,
				//0x0d
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					hopsToLiveFieldInfo,
					new FieldDefinitionsTable.FieldDefinition(
						"RequestInterval"),
					new FieldDefinitionsTable.FieldDefinition("Attenuation"),
					new FieldDefinitionsTable.FieldDefinition("Reason")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(QueryRestarted.class,
				//0x0e
				new FieldDefinitionsTable.FieldDefinition[] {
					 uniqueIDFieldInfo }),
					new FieldDefinitionsTable
						.MessageFieldsInfo(StoreData.class,
				//0x0f
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					new FieldDefinitionsTable.FieldDefinition(
						"DataSource",
						nodeRefFieldInfo),
					new FieldDefinitionsTable.FieldDefinition("RequestRate"),
					new FieldDefinitionsTable.FieldDefinition(
						"HopsSinceReset")}),
					new FieldDefinitionsTable
						.MessageFieldsInfo(VoidMessage.class,
				//0x10
				new FieldDefinitionsTable.FieldDefinition[] {
					 uniqueIDFieldInfo }),
					new FieldDefinitionsTable.MessageFieldsInfo(Identify.class,
				//0x11
				new FieldDefinitionsTable.FieldDefinition[] {
					uniqueIDFieldInfo,
					new FieldDefinitionsTable.FieldDefinition(
						"Source",
						nodeRefFieldInfo)}),
					});

			//Register v1 codetable in repository
			knownCodeTables.put(
				codeTableV1.getDigest(),
				codeTableV1.messageFieldsInfo);
			knownInverseCodeTables.put(
				codeTableV1.getDigest(),
				codeTableV1.inverseMessageFieldsInfo);
		} catch (Exception e) {
			//TODO: What to do
			Core.logger.log(
				FNCRawMessage.class,
				"Exception in FNCRawMessage class initialization",
				e,
				Logger.ERROR);
		}
	}

	private static String defaultDecoderTableDigest = codeTableV1.getDigest();

	// Constructors

	/**
	 * Constructs a new RawMessage off an FNP Stream
	 * 
	 * @param i
	 *            An InputStream of decrypted FNP data
	 */
	public FNCRawMessage(InputStream i)
		throws EOFException, UnknownEncodingException {

		//Core.logger.log(this,"Reading message",Logger.DEBUGGING);
		this(new EOFingReadInputStream(i));
	}

	public FNCRawMessage(ReadInputStream ris)
		throws EOFException, UnknownEncodingException {
		//        PushbackInputStream in = new PushbackInputStream(i);
		fs = new FieldSet();

		//TODO: The digest String used below really should be fetched from the
		// other node's NodeReference or similar...
		String codeTable = defaultDecoderTableDigest;
		FieldDefinitionsTable.MessageFieldsInfo[] ntable =
			getDecoderTable(codeTable);
		if (ntable == null)
			throw new UnknownEncodingException(
				"Has no code table for encoding table '" + codeTable + "'");

		//Dump codetable contents
		for (int i = 0; i < ntable.length; i++) {
			System.err.println(ntable[i].messageName + ":");
			for (int i2 = 0; i2 < ntable[i].ctable.length; i2++)
				System.err.println(
					"   "
						+ ntable[i].ctable[i2].fieldName
						+ ":"
						+ ntable[i].ctable[i2].fieldType.getName());
		}

		FieldDefinitionsTable.FieldNameAndFieldType[] ctable = null;

		try {
			// Read message type
			int first = ris.read();
			int code = first & 0x7F;
			if (first == code) {
					messageType =
						(char) first //assumes first char is a single-byte
		// UTF8
	+ris.readToEOF('\n', '\r');
			} else if (code > 0 && code < ntable.length) {
				messageType = ntable[code].messageName;
				ctable = ntable[code].ctable;
			} else {
				throw new EOFException("Unknown message type");
			}

			int byteTwo = ris.read();
			if (byteTwo == -1)
				throw new EOFException("Missing ByteTwo in FNCRawMessage constructor");

			close = (byteTwo & 1) == 1;
			sustain = (byteTwo & 2) == 1;
			int count = 0;
			if ((byteTwo & 12) == 1)
				count = 8;
			else if ((byteTwo & 4) == 1)
				count = 4;
			for (int i = 0; i < count; i++) {
				int r = ris.read();
				if (r == -1)
					throw new EOFException(
						"Missing DataLength byte #"
							+ i
							+ " in FNCRawMessage constructor");
				trailingFieldLength <<= 8;
				trailingFieldLength += r;
			}

			trailingFieldName = fs.parseCompressed(ris, ctable);

			if (trailingFieldName == null)
				throw new EOFException("incomplete FNC message - hopefully");

		} catch (IOException e) {
			throw new EOFException(
				"Could not parse message from stream : " + e);
		} catch (Exception e) {
			Core.logger.log(this, "Exception in RawMessage()", Logger.ERROR);
			e.printStackTrace();
		}
	}

	//Returns a code table that ought to be able to be used to
	//decode messages that has been encoded with the same table
	private static FieldDefinitionsTable.MessageFieldsInfo[] getDecoderTable(
		String digest) {
		return (FieldDefinitionsTable.MessageFieldsInfo[]) knownCodeTables.get(
			digest);
	}

	private static Hashtable getEncoderTable(String digest) {
		return (Hashtable) knownInverseCodeTables.get(digest);
	}

	protected FNCRawMessage(
		String messageType,
		boolean close,
		boolean sustain,
		FieldSet fs,
		long trailingLength,
		String trailingName,
		DiscontinueInputStream trailing) {
		super(
			messageType,
			close,
			sustain,
			fs == null ? new FieldSet() : fs,
			trailingLength,
			trailingName,
			trailing,
			0);
		// FIXME: FNC not muxed - for now...
	}

	// Public Methods

	public void writeMessage(OutputStream out) throws IOException {

		WriteOutputStream writer = new WriteOutputStream(out);

		//TODO: The digest String used below really should be fetched from the
		// other node's NodeReference or similar...
		String codeTable = defaultDecoderTableDigest;
		Hashtable ntable = getEncoderTable(codeTable);
		//Maps from messageType to
		// FieldDefinitionsTable.InverseMessageFieldsInfo
		if (ntable == null)
			throw new IOException(
				"Has no code table for decoding table '" + codeTable + "'");
		//Can unfortunately not use UnknownEncodingException due to
		// inheritance
		Hashtable ctable = null;
		//Maps from fieldName to FieldDefinitionsTable.FieldIDAndFieldType

		// Output message type
		FieldDefinitionsTable.InverseMessageFieldsInfo typeInfo =
			(FieldDefinitionsTable.InverseMessageFieldsInfo) ntable.get(
				messageType);
		if (typeInfo == null) { //didn't find messageType
			writer.write(UTF8.encode(messageType));
			writer.write((byte) '\n');
		} else {
			byte code = (byte) (typeInfo.messageTypeID | 128);
			//set the high bit on control bytes
			writer.write(code); //FIXME make a messagebyte field in messages
			ctable = typeInfo.fieldCodes;
		}

		// Output transport options
			int byteTwo = (close ? 1 : 0) + //1's bit -> close
		 (sustain ? 2 : 0) + //2's bit -> sustain
	 (trailingFieldLength != 0 ? 4 : 0) +
			//4's bit -> existence of trailingfield
	 (trailingFieldLength >= 1 << 24 ? 8 : 0);
		//8's bit: if trailingfield is > 5 bytes. (i.e. more than 16MB of data
		// trailing)
		//The point of this last one is to save bits when the trailing field
		// is normal sized.
		writer.write(byteTwo);

		if (trailingFieldLength != 0) {
			if (trailingFieldLength >= 1 << 24) {
				writer.write((byte) (trailingFieldLength >> 56));
				writer.write((byte) (trailingFieldLength >> 48));
				writer.write((byte) (trailingFieldLength >> 40));
				writer.write((byte) (trailingFieldLength >> 32));
			}
			writer.write((byte) (trailingFieldLength >> 24));
			writer.write((byte) (trailingFieldLength >> 16));
			writer.write((byte) (trailingFieldLength >> 8));
			writer.write((byte) (trailingFieldLength));
		}

		// Output message fields
		fs.writeCompressed(writer, ctable);

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
	
	public static class UnknownEncodingException extends Exception {
		public UnknownEncodingException(String s) {
			super(s);
		}
	}
}
