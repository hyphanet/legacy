package freenet.client.metadata;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Vector;

import freenet.FieldSet;
import freenet.client.FreenetURI;
import freenet.client.RequestProcess;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

public class DocumentCommand {
	private static Hashtable partTypes = new Hashtable();

	static {
		registerPart(DateRedirect.name, DateRedirect.class);
		registerPart(InfoPart.name, InfoPart.class);
		registerPart(ExtInfo.name, ExtInfo.class);
		registerPart(Redirect.name, Redirect.class);
		registerPart(SplitFile.name, SplitFile.class);
		registerPart(StreamPart.name, StreamPart.class);
	}

	/**
	 * @param part
	 *            the name of the part
	 * @param c
	 *            the class (must extend freenet.client.Metadata)
	 */
	public static void registerPart(String part, Class c) {
		//System.err.println("LALA: Registering part: " + part + ".");
		if (!MetadataPart.class.isAssignableFrom(c))
			throw new IllegalArgumentException("not a subclass of Metadata");

		try {
			Constructor con =
				c.getConstructor(
					new Class[] { FieldSet.class, MetadataSettings.class });
			partTypes.put(part, con);
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException(
				"Constructor for class " + c + " not found.");
		}
	}

	private String documentName;

	//private Metadata next;
	Metadata next;

	private MetadataSettings settings;

	private Vector parts = new Vector();

	private boolean hasTrailing = false;

	/*
	 * public DocumentCommand() { documentName = ""; }
	 */

	public DocumentCommand(Metadata next) {
		this(next, "");
	}

	public DocumentCommand(Metadata next, String documentName) {
		this.next = next;
		this.documentName = documentName;
		if (next != null)
			this.settings = next.getSettings();
		if (this.settings == null)
			throw new NullPointerException("settings null");
	}

	/**
	 * DocumentCommands with no next metadata.
	 */
	public DocumentCommand(MetadataSettings settings) {
		this(settings, "");
	}

	public DocumentCommand(MetadataSettings settings, String documentName) {
		this.documentName = documentName;
		this.settings = settings;
		if (this.settings == null)
			throw new NullPointerException("settings null");
	}

	public DocumentCommand(FieldSet fs, MetadataSettings settings)
		throws InvalidPartException {

		this.documentName =
			(fs.containsKey("Name") ? fs.getString("Name") : "");
		//System.err.println(settings);
		this.settings = settings;
		if (this.settings == null)
			throw new NullPointerException("settings null");
		parseParts(fs);
	}

	public void addPart(MetadataPart mdp) throws InvalidPartException {

		if (mdp instanceof TrailingFieldPart
			&& ((TrailingFieldPart) mdp).hasTrailing()) {
			if (hasTrailing)
				throw new InvalidPartException("Can't have 2 trailing fields");
			hasTrailing = true;
		}
		parts.addElement(mdp);
	}

	/**
	 * @return Metadata object instance of the named, registered class
	 */
	private MetadataPart byName(
		String name,
		FieldSet rawFields,
		MetadataSettings settings)
		throws UnsupportedPartException, InvalidPartException {

		//        System.err.println("name: " + name);
		//for (Enumeration e = partTypes.keys() ; e.hasMoreElements();)
		//    System.err.println(e.nextElement());
		Constructor con = (Constructor) partTypes.get(name);
		if (con == null)
			throw new UnsupportedPartException(name);

		try {
			return (MetadataPart) con.newInstance(
				new Object[] { rawFields, settings });
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			if (e.getTargetException() instanceof InvalidPartException)
				throw (InvalidPartException) e.getTargetException();
			else
				e.getTargetException().printStackTrace();
		}

		throw new UnsupportedPartException(name);
	}

	private void parseParts(FieldSet fs) throws InvalidPartException {

		// since this is a reference implementation I am going to be
		// strict with unsupported options.

		for (Iterator e = fs.keySet().iterator(); e.hasNext();) {
			String s = (String) e.next();

			FieldSet part = fs.getSet(s);
			if (part != null) {
				try {
					addPart(byName(s, part, settings));
				} catch (UnsupportedPartException upe) {
					//System.err.println(upe);
					String imp = part.getString("Importance");
					if (imp == null || !imp.equals("Informational")) {
						throw new InvalidPartException(
							"Unsupported part: " + s + ".");
					}
				}
			} else if (!s.equals("Name")) {
				throw new InvalidPartException("Unsupported field : " + s);
			}
		}
	}

	public FieldSet toFieldSet() {
		FieldSet fs = new FieldSet();

		if (documentName.length()!=0)
			fs.put("Name", documentName);

		for (Enumeration e = parts.elements(); e.hasMoreElements();) {
			MetadataPart mdp = (MetadataPart) e.nextElement();

			mdp.addTo(fs);
		}
		return fs;
	}

	public String getName() {
		return documentName;
	}

	public void setName(String documentName) {
		this.documentName = documentName;
	}

	public boolean hasTrailing() {
		return hasTrailing;
	}

	public MetadataPart getControlPart() {
		for (Enumeration e = parts.elements(); e.hasMoreElements();) {
			MetadataPart mdp = (MetadataPart) e.nextElement();
			if (mdp.isControlPart()) {
				return mdp;
			}
		}
		return null;
	}

	public MetadataPart[] getNonControlParts() {
		Vector res = new Vector();
		for (Enumeration e = parts.elements(); e.hasMoreElements();) {
			MetadataPart mdp = (MetadataPart) e.nextElement();
			if (!mdp.isControlPart()) {
				res.addElement(mdp);
			}
		}
		MetadataPart[] result = new MetadataPart[res.size()];
		res.copyInto(result);
		return result;
	}

	/**
	 * Returns the get request process dictated by this Document.
	 */
	public RequestProcess getGetProcess(
		FreenetURI furi,
		int htl,
		Bucket data,
		BucketFactory ptBuckets,
		int recursionLevel,
		MetadataSettings ms) {
		for (Enumeration e = parts.elements(); e.hasMoreElements();) {
			MetadataPart mdp = (MetadataPart) e.nextElement();
			if (mdp.isControlPart()) {
				return mdp.getGetProcess(
					furi,
					htl,
					data,
					ptBuckets,
					recursionLevel,
					ms);
			}
		}
		return null;
	}

	/**
	 * Returns the put request process dictated by this Document
	 */
	public RequestProcess getPutProcess(
		FreenetURI furi,
		int htl,
		String cipher,
		Bucket data,
		BucketFactory ptBuckets,
		int recursionLevel,
		boolean descend) {
		if (settings == null)
			throw new NullPointerException("ms NULL!");
		for (Enumeration e = parts.elements(); e.hasMoreElements();) {
			MetadataPart mdp = (MetadataPart) e.nextElement();
			if (mdp.isControlPart()) {
				return mdp.getPutProcess(
					furi,
					htl,
					cipher,
					next,
					settings,
					data,
					ptBuckets,
					recursionLevel,
					descend);
			}
		}
		return null;
	}

	public String toString() {
		StringBuffer sb = new StringBuffer(getName()).append(" -> ");
		for (Enumeration e = parts.elements(); e.hasMoreElements();) {
			sb.append(e.nextElement());
		}
		return sb.toString();
	}

}
