package freenet.client.metadata;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Hashtable;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.ArrayBucket;
import freenet.support.Logger;
import freenet.support.io.ReadInputStream;
import freenet.support.io.WriteOutputStream;

/**
 * Parses a metadata stream and produces a series of parts.
 * 
 * @author oskar
 */
public class Metadata {

	public static final int VERSION = 1;

	private final Hashtable commands = new Hashtable();
	private VersionCommand version;
	private ArrayBucket trailing;
	private MetadataSettings settings;
	private static boolean logDebug=true;
	
	private Metadata() {
		logDebug=Core.logger.shouldLog(Logger.DEBUG,this);
	}

	/**
	 * Create a new Metadata object.
	 * 
	 * @param settings
	 *            Properties to use when handeling metadata.
	 */
	public Metadata(VersionCommand v, MetadataSettings settings) {
		this();
		this.settings = settings;
		this.version = v;
	}

	/**
	 * Create a new Metadata object.
	 */
	public Metadata(MetadataSettings settings) {
		this();
		this.settings = settings;
		this.version = new VersionCommand(this);
	}

	/**
	 * Create a new Metadata object from a wire.
	 * 
	 * @param settings
	 *            Properties to use when handling metadata.
	 * @param metadata
	 *            InputStream to read.
	 */
	public Metadata(InputStream metadata, MetadataSettings settings)
		throws InvalidPartException, IOException {
		this();
		//System.err.println("metadatasettings: " + settings);
		this.settings = settings;
		parse(metadata);
	}

	/**
	 * Returns the settings passed to the constructor.
	 */
	public MetadataSettings getSettings() {
		return settings;
	}

	/**
	 * Returns whatever is left on the trailing field after all the standard
	 * metadata is read.
	 */
	public InputStream getTrailing() {
		return trailing.getInputStream();
	}

	/**
	 * Add a DocumentCommand. Note that there can only be one that contains a
	 * trailing field - if this does, any previous such will be overwritten.
	 * (Which also mean, do NOT add a trailing later - Bad things will happen).
	 */
	public void addCommand(DocumentCommand dc) {
		commands.put(dc.getName(), dc);
	}

	public void addDocument(DocumentCommand dc) {
		addCommand(dc);
	}

	public static int revision() {
		return VERSION;
	}

	public VersionCommand getVersion() {
		return version;
	}

	protected void parse(InputStream metadata)
		throws InvalidPartException, IOException {

		logDebug = Core.logger.shouldLog(Logger.DEBUG,this);
		if (logDebug)
			Core.logger.log(this, "Parsing metadata", Logger.DEBUG);

		ReadInputStream in = new ReadInputStream(metadata);

		String name;
		try {
			name = in.readTo('\n', '\r');
		} catch (EOFException e) {
			if (logDebug)
				Core.logger.log(
					this,
					"Completely empty metadata",
					Logger.DEBUG);
			version = new VersionCommand(this);
			return; // I'm tolerating completely empty metadata
		}
		if (!name.equals("Version"))
			throw new InvalidPartException("Must start with version");
		FieldSet fs = new FieldSet();
		String end = fs.parseFields(in);
		if (logDebug)
			Core.logger.log(this, "end is " + end, Logger.DEBUG);
		version = new VersionCommand(this, fs);

		while (end.equals("EndPart")) {
			if (logDebug)
				Core.logger.log(this, "Got a Part", Logger.DEBUG);
			fs = new FieldSet();
			name = in.readTo('\n', '\r');

			if (!"Document".equals(name))
				throw new InvalidPartException(
					"Document command expected. " + " Got: " + name);
			end = fs.parseFields(in);
			if (logDebug)
				Core.logger.log(
					this,
					"Document end = " + end + ", fieldset: " + fs.toString(),
					Logger.DEBUG);
			DocumentCommand d = new DocumentCommand(fs, settings);
			if (logDebug)
				Core.logger.log(
					this,
					"Got DocumentCommand: " + d,
					Logger.DEBUG);
			commands.put(d.getName(), d);
		}
		if (!end.equals("End")) {
			throw new InvalidPartException("Malformed endstring: " + end);
		}
		if (logDebug)
			Core.logger.log(this, "Got End", Logger.DEBUG);
		ArrayBucket ab = new ArrayBucket();
		ab.read(in);
		if (ab.size() > 0)
			this.trailing = ab;
		if (logDebug)
			Core.logger.log(this, "Got Trailing", Logger.DEBUG);
	}

	public void writeTo(OutputStream rout) throws IOException {
		WriteOutputStream out = new WriteOutputStream(rout);
		if (logDebug)
			Core.logger.log(
				this,
				"Metadata.WriteOutputStream()",
				new Exception("debug"),
				Logger.DEBUG);
		out.writeUTF("Version", '\n');
		FieldSet fs = version.toFieldSet();
		Enumeration e = commands.elements();
		fs.writeFields(out, e.hasMoreElements() ? "EndPart" : "End");
		while (e.hasMoreElements()) {
			fs = ((DocumentCommand) e.nextElement()).toFieldSet();
			out.writeUTF("Document", '\n');
			fs.writeFields(out, e.hasMoreElements() ? "EndPart" : "End");
		}

		// Copy trailing
		if (trailing != null) {
			InputStream in = trailing.getInputStream();
			byte[] b = new byte[8 * 1024];
			int i;
			while ((i = in.read(b)) != -1) {
				out.write(b, 0, i);
			}
		}
		out.flush();
	}

	public String writeString() {
		OutputStream os = new ByteArrayOutputStream(250);
		try {
			writeTo(os);
			os.close();
		} catch (IOException e) {
			Core.logger.log(
				this,
				"IMPOSSIBLE - got IOException writing to a "
					+ "ByteArrayOutputStream",
				e,
				Logger.ERROR);
			return null;
		}
		return os.toString();
	}

	public DocumentCommand getDocument(String name) {
		return (DocumentCommand) commands.get(name);
	}

	public DocumentCommand getDefaultDocument() {
		return (DocumentCommand) commands.get("");
	}

	public Enumeration getDocumentNames() {
		return commands.keys();
	}

	public String toString() {
		StringBuffer sb = new StringBuffer();
		for (Enumeration e = commands.elements(); e.hasMoreElements();) {
			sb.append(e.nextElement());
			if (e.hasMoreElements())
				sb.append(", ");
		}
		return sb.toString();
	}

	public String getMimeType(String defaultMimeType) {
		for (Enumeration e = commands.elements(); e.hasMoreElements();) {
			DocumentCommand command = (DocumentCommand) (e.nextElement());
			// Ignore named sub-parts
			if(command.getName() != null && command.getName().length() > 1) continue;
			MetadataPart[] parts = command.getNonControlParts();
			int i;
			for (i = 0; i < parts.length; i++) {
				if (parts[i] instanceof InfoPart) {
					String format = ((InfoPart) parts[i]).format();
					if (format != null) {
						return format;
					}
				}
			}
		}

		return defaultMimeType;
	}

	public StreamPart getStreamPart() {
		for (Enumeration e = commands.elements(); e.hasMoreElements();) {
			DocumentCommand command = (DocumentCommand) (e.nextElement());
			MetadataPart[] parts = command.getNonControlParts();
			for (int i = 0; i < parts.length; i++) {
				if (parts[i] instanceof StreamPart) {
					return (StreamPart) parts[i];
				}
			}
		}

		return null;
	}

	// hmmmm... This may falsely return a SplitFile for
	// complex metadata. Good enough for now.
	public SplitFile getSplitFile() {
		for (Enumeration e = commands.elements(); e.hasMoreElements();) {
			DocumentCommand command = (DocumentCommand) (e.nextElement());
			MetadataPart part = command.getControlPart();
			if (part instanceof SplitFile) {
				return (SplitFile) part;
			}
		}
		return null;
	}

	// Read the checksum value out of the info part.
	public String getChecksum(String oldChecksum) {
		for (Enumeration e = commands.elements(); e.hasMoreElements();) {
			DocumentCommand command = (DocumentCommand) (e.nextElement());
			MetadataPart parts[] = command.getNonControlParts();
			for (int i = 0; i < parts.length; i++) {
				if (parts[i] instanceof InfoPart) {
					String candidate = ((InfoPart) parts[i]).checksum();
					// Take the first one we find. Hmmm...
					if (candidate != null) {
						return candidate;
					}
				}
			}
		}
		return oldChecksum;
	}

	// REDFLAG: Fix later.
	// This is a blunt tool used to implement checksum support for
	// SplitFiles.
	// Might not work for complex metadata. Good enough for now.

	// Sets the checksum of *all* info parts in the metadata.
	public void updateChecksum(String value) {
		for (Enumeration e = commands.elements(); e.hasMoreElements();) {
			DocumentCommand command = (DocumentCommand) (e.nextElement());
			MetadataPart parts[] = command.getNonControlParts();
			for (int i = 0; i < parts.length; i++) {
				if (parts[i] instanceof InfoPart) {
					System.err.println("UPDATED CHECKSUM: " + value);
					((InfoPart) parts[i]).setChecksum(value);
				}
			}

			// Hmmm... don't think this is really required.
			if (command.next != null) {
				command.next.updateChecksum(value);
			}

		}
	}

}
