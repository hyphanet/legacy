package freenet.node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Iterator;

import net.i2p.util.NativeBigInteger;
import freenet.Address;
import freenet.BadAddressException;
import freenet.Core;
import freenet.DSAAuthentity;
import freenet.DSAIdentity;
import freenet.FieldSet;
import freenet.Identity;
import freenet.KeyException;
import freenet.Peer;
import freenet.Presentation;
import freenet.PresentationHandler;
import freenet.SessionHandler;
import freenet.Transport;
import freenet.TransportHandler;
import freenet.Version;
import freenet.client.ClientSSK;
import freenet.client.FreenetURI;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.Digest;
import freenet.crypt.SHA1;
import freenet.keys.SVK;
import freenet.session.LinkManager;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.io.WriteOutputStream;
import freenet.transport.VoidAddress;

/**
 * References contains names from which Address objects can be resolved.
 * How this is done is transport dependant.
 *
 * It is expected that at some later time References will be more complex
 * structures contain the names for several different transports and internal
 * lookup and authorization information.
 *
 * @author oskar
 */
public class NodeReference {

    private static final String[] stringSignature = {"signature"};

    public final String[] physical;
    private long[] sessions;
    private long[] presentations;

    private final Identity identity;    // node's public key
    private boolean hasARK = false;     // whether we have an ARK
    private long ARKrevision;           // ARK revision number
    private byte[] ARKcrypt;            // ARK data encryption key
    private DSASignature signature;
    // FIXME: get rid when nodes don't send it any more
    private String version;

    // kill me quickly
    //private boolean signatureHack = false;
    
    public boolean isSigned() {
	return signature != null;
    }
    
    /**
     * Makes a deep copy of a NodeReference.
     */
    public NodeReference(NodeReference nr) {
        
        physical = new String[nr.physical.length];
        System.arraycopy(nr.physical, 0, physical, 0, physical.length);
        sessions = new long[nr.sessions.length];
        System.arraycopy(nr.sessions, 0, sessions, 0, sessions.length);
        presentations = new long[nr.presentations.length];
        System.arraycopy(nr.presentations, 0, presentations, 0, presentations.length);
        
        identity = nr.identity;
        hasARK = nr.hasARK;
        ARKrevision = nr.ARKrevision;
        ARKcrypt = nr.ARKcrypt;
        signature = nr.signature;
        version = nr.version;
        noGoodVersion = nr.noGoodVersion;
    }

    /**
     * Creates a new nodereference from its FieldSet, verifying the
     * signature.
     * @param ref A FieldSet containing the full Node Reference information.
     * @exception BadReferenceException - if the data is malformed.
     */
    public NodeReference(FieldSet ref) throws BadReferenceException {
        this(ref, true, null);
    }

    /**
     * Creates a new nodereference from its FieldSet.
     * @param ref A FieldSet containing the full Node Reference information.
     * @param verify  true mandates the existance of valid signature.
     * @exception BadReferenceException - if the data is malformed.
     */
    public NodeReference(FieldSet ref, boolean verify) throws BadReferenceException {
        this(ref, true, null);

    }

    /**
     * Creates a new NodeReference from us, updating the ARKVersion and
     * the physical addresses and recalculating the signature
     */
    NodeReference newVersion(DSAAuthentity auth, String[] physical, 
			     long ARKversion) {
	NodeReference n = new NodeReference(identity, physical, 
					    sessions, presentations, version,
					    ARKversion, ARKcrypt);
	n.addSignature(auth);
	return n;
    }
    
    public NodeReference(FieldSet ref, boolean verify, Identity ident) 
        throws BadReferenceException {
	this(ref, verify, false, ident);
    }
    
    /**
     * Creates a new nodereference from its FieldSet, adding an externally
     * obtained identity.
     * @param ref A FieldSet containing the full Node Reference information.
     * @param verify  true mandates the existance of valid signature.
     * @param slack   true means if there is a signature it will be checked and must be valid
     * @param ident  The identity to assign the fieldset to. The method
     *               will look for a field called "identityFP" in the 
     *               and match that against the fingerprint of this identity.
     * @exception BadReferenceException - if the data is malformed.
     */
    public NodeReference(FieldSet ref, boolean verify, boolean slack, Identity ident) 
        throws BadReferenceException {

        if (ref.getSet("identity") == null && ident != null) {
            // It's sort of ugly, but we'll actually insert the identity
            // into the fieldset.
            String s = ref.getString("identityFP");
            if (s == null || !s.equals(ident.fingerprintToString()))
                throw new BadReferenceException("Provided identity did not " +
                                                "match fingerprint.");
            ref.remove("identityFP");
            ref.put("identity", ident.getFieldSet());
        }
        // Read physical addresses

        FieldSet phy = ref.getSet("physical");
        //if (phy == null || phy.isEmpty())
        //    throw new BadReferenceException("Malformed ref: " + 
        //                                       "no physical addresses.");

        if (phy != null && !phy.isEmpty()) {
            String trans, addr;
            int i = 0;
            physical = new String[phy.size() * 2];
            for (Iterator e = phy.keySet().iterator() ; e.hasNext() ; i += 2) {
                trans = (String) e.next();
                addr = phy.getString(trans);
                if (addr == null) { // safety, it could be a FieldSet, which is bad
                    throw new BadReferenceException("Malformed ref: " + 
                                                    "bad physical address.");
                }
                physical[i] = trans;
                physical[i + 1] = addr;
            }
        }
        else physical = new String[0];

        // read Contact info

        try {
            String ss = ref.getString("sessions");
            if (ss == null)
                throw new NumberFormatException("No session field");
            sessions = Fields.numberList(ss);


            String ps = ref.getString("presentations");

            if (ps == null)
                throw new NumberFormatException("No presentations field");

            presentations = Fields.numberList(ps);

            //FieldSet ident = ref.getSet("identity");
            // temporary compatibility measure
            //if (ident == null) {
            //    String pks = ref.get("identity");
            //    if (pks == null)
            //        throw new NumberFormatException("No node identity");
            //    identity = new DSAIdentity(ref.get("identityGroup"), pks);
            //}
            //else {
            //    identity = new DSAIdentity(ident);
            //}

            identity = new DSAIdentity(ref.getSet("identity"));
            
            this.version = ref.getString("version");
            if(version == null) {
                Core.logger.log(this, "Null version on "+this+" from "+ref,
                        Logger.ERROR);
                throw new BadReferenceException("Null version");
            }
            noGoodVersion = !Version.checkGoodVersion(getVersion());
            
        } catch (NumberFormatException e) {
            throw new BadReferenceException( "Malformed ref: " + e.getMessage() + 
					     "Fieldset: " + ref.toString());
        }
        
        // read ARK info

        FieldSet ARK = ref.getSet("ARK");
        if (ARK != null) {
            try {
            	String revision = ARK.getString("revision");
                if (revision == null)
                    throw new NumberFormatException();
                ARKrevision = Fields.hexToLong(revision);
                String crypts = ARK.getString("encryption");
                if (crypts != null ) {
                    ARKcrypt = new byte[crypts.length() / 2];
					HexUtil.hexToBytes(crypts, ARKcrypt, 0);
                }
                hasARK = true;
            } catch (NumberFormatException e) {
                Core.logger.log(this, 
					"Ignored malformed ARK entry:\n"+ARK.toString(),e,
					Logger.MINOR);
            }catch (IndexOutOfBoundsException e){
				Core.logger.log(this, 
					"Ignored malformed ARK entry:\n"+ARK.toString(),e,
					Logger.MINOR);
			}
        }

        if (verify || slack) {
            // last but not least, read and verify signature
            
            String signS = ref.getString("signature");
            if (signS == null) {
		if(verify) {
		    throw 
			new BadReferenceException("NodeReference must be signed");
		}
	    } else {
		try {
		    signature = new DSASignature(signS);
		    Digest d = SHA1.getInstance();
		    ref.hashUpdate(d, stringSignature);
		    //try {
		    if (!identity.verify(signature, 
					 new NativeBigInteger(1, d.digest()))) {
		        ByteArrayOutputStream sw = new ByteArrayOutputStream(256);
		        try {
		            ref.writeFields(new WriteOutputStream(sw));
		        } catch (IOException e) {
		            Core.logger.log(this, "WTF?! Writing ref", Logger.NORMAL);
		        }
		        Core.logger.log(this, "Bad reference: \n"+sw.toString(),
		                Logger.NORMAL);
		        throw new BadReferenceException("NodeReference self " +
							"signature check failed.");
		    }
		    //}
		    // oh what a brutal hack .. but it's temporary
		    //catch (BadReferenceException e) {
		    //    ref.put("identity", ((DSAPublicKey) identity).writeAsField());
		    //    // they were using group C anyway..
		    //    Digest d2 = new SHA1();
		    //    ref.hashUpdate(d2, stringSignature);
		    //    if (!identity.verify(signature, new BigInteger(1, d2.digest())))
		    //        throw e;
		    //    signatureHack = true;
		    //}
		} catch (NumberFormatException e) {
		    throw new BadReferenceException("Signature field not correct");
		}
	    }
	}
	
        if (version != null)
            Version.seenVersion(version);
    }

    /**
     * Constructs a new NodeReference to this address, with ARK as read
     * from the URI.
     * @param peer  A Peer object describing a physical address and
     *              identity of the node reference.
     * @param ARK   An ARK URI. The subspace value is ignored, it is assumed
     *              to be the fingerprint of the identity. May be null.
     */
    public NodeReference(Peer peer, String version, FreenetURI ARK) {
        this(
            peer.getIdentity(),
            new Address[] { peer.getAddress() },
            new long[]    { peer.getLinkManager().designatorNum() },
            new long[]    { peer.getPresentation().designatorNum() },
            version,
            ARK
        );

        if (version != null)
            Version.seenVersion(version);
    }

    public NodeReference( Identity identity, Address[] addr,
			  long[] sessions, long[] presentations,
			  String version, FreenetURI ARK ) {
	this(identity, parsePhysical(addr), sessions, 
		      presentations, version);
        if (ARK != null) {
            try {
                if (ARK.getGuessableKey() == null)
                    throw new NumberFormatException();
                ARKrevision = Fields.hexToLong(ARK.getGuessableKey());
                ARKcrypt = ARK.getCryptoKey(); // may be null	
                hasARK = true;
            }
            catch (NumberFormatException e) {
                Core.logger.log(this, 
                                "Malformed ARK entry in Reference ignored",
                                Logger.MINOR);
            }
        }

    }

    protected static String[] parsePhysical( Address[] addr ) {
	String[] physical = new String[2*addr.length];
        for (int i=0; i<addr.length; ++i) {
            physical[2*i]   = addr[i].getTransport().getName();
            physical[2*i+1] = addr[i].getValString();
        }
	return physical;
    }
    
    protected NodeReference( Identity identity, String[] physical,
			     long[] sessions, long[] presentations,
			     String version ) {

        this.identity      = identity;
        this.sessions      = sessions;
        this.presentations = presentations;
        this.version       = version;
        this.noGoodVersion = !Version.checkGoodVersion(getVersion());
		this.physical      = physical;
        
        if (version != null)
            Version.seenVersion(version);
    }
    
    protected NodeReference ( Identity identity, String[] addr,
			      long[] sessions, long[] presentations,
			      String version, long ARKrevision,
			      byte[] ARKcrypt ) {

	this(identity, addr, sessions, presentations, version);
	hasARK = true;
	this.ARKrevision = ARKrevision;
	this.ARKcrypt = ARKcrypt;
    }

    public NodeReference ( Identity identity, Address[] addr,
			   long[] sessions, long[] presentations,
			   String version, long ARKrevision,
			   byte[] ARKcrypt) {
	this(identity, parsePhysical(addr), sessions, presentations, version);
	hasARK = true;
	this.ARKrevision = ARKrevision;
	this.ARKcrypt = ARKcrypt;
	
    }
    
    /**
     * Returns true if this NodeReference and the given indicate that they can
     * talk to one another. That is they share at least of each of physical,
     * session, and presentation protocols.
     */
    public boolean intersects(NodeReference nr) {
        boolean found = false;
        // O(n^2) - but n is quite limited...
        for (int i = 0 ; i < physical.length && !found; i++) {
            for (int j = 0 ; j < nr.physical.length && !found; j++) {
                if (physical[i].equals(nr.physical[j]))
                    found = true;
            }
        }
        if (!found)
            return false;
        else
            found = false;

        for (int i = 0 ; i < sessions.length && !found; i++) {
            for (int j = 0 ; j < nr.sessions.length && !found; j++) {
                if (sessions[i] == nr.sessions[j])
                    found = true;
            }
        }
        if (!found)
            return false;
        else
            found = false;

        for (int i = 0 ; i < presentations.length && !found; i++) {
            for (int j = 0 ; j < nr.presentations.length && !found; j++) {
                if (presentations[i] == nr.presentations[j])
                    found = true;
            }
        }

        return found;
    }


    /**
     * Returns an address pointing at this reference.
     * @param   t The transport type of the address.
     * @return  The address if one with the correct transport
     *           is found, otherwise null.
     * @throws BadAddressException If the address stored for this transport is broken.
     */
    public Address getAddress(Transport t)
        throws BadAddressException {
        for (int i = 0 ; i < physical.length ; i += 2) {
            if (t.getName().equals(physical[i])) {
		long startTime = System.currentTimeMillis();
		Address addr = t.getAddress(physical[i+1]);
		long endTime = System.currentTimeMillis();
		long time = endTime - startTime;
		
		int logLevel = time>1000 ? Logger.NORMAL : Logger.DEBUG;
		if(Core.logger.shouldLog(logLevel,this))
			Core.logger.log(this, "t.getAddress("+physical[i+1]+") took "+time,logLevel);
		return addr;
            }
        }
        return null;
    }
    
    // Caveat: tcpAddress's are already-looked-up. Don't use this to update
    // to a new node _NAME_.
    static public String[] setPhysicalAddress(String[] physical, 
					      Address a) {
        Transport t = a.getTransport();
	for (int i = 0 ; i < physical.length ; i += 2) {
	    if (t.getName().equals(physical[i])) {
		physical[i+1] = a.toString();
		return physical;
	    }
	}
	String[] newPhys = new String[physical.length+2];
	System.arraycopy(physical, 0, newPhys, 0, physical.length);
	newPhys[physical.length] = t.getName();
	newPhys[physical.length+1] = a.toString();
	return newPhys;
    }
    
    /**
     * Create public URI for ARK
     * @param version the sequence number to use
     */
    public FreenetURI getARKURI(long version) throws KeyException {
	DSAPublicKey pk = (DSAPublicKey)getIdentity();
	if(pk == null) return null;
	byte[] ckey = cryptoKey();
	if(ckey == null) return null;
	SVK svk = new SVK(pk, Long.toHexString(version), SVK.SVK_LOG2_MAXSIZE);
	ClientSSK ssk = 
	    new ClientSSK(svk, ckey, Long.toHexString(version));
	
	return ssk.getURI();
    }
    
    /**
     * Checks that all addresses for transports that we support are 
     * correct.
     */
    public boolean checkAddresses(TransportHandler th) {
        for (int i = 0 ; i < physical.length ; i += 2) {
            if (!th.checkAddress(physical[i], physical[i+1]))
                return false;
        }
        return true;
    }

    /**
     * Returns true if the node supports the presentation protocol
     * designated by the object.
     */
    public boolean supports(Presentation p) {
        int n = p.designatorNum();

        for (int i = 0 ; i < presentations.length; i++) {
            if (presentations[i] == n) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if the node supports the session protocol
     * designated by the object.
     */
    public boolean supports(LinkManager s) {
        int n = s.designatorNum();

        for (int i = 0 ; i < sessions.length; i++) {
            if (sessions[i] == n) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the most prefered of the addresses suppported by both
     * the TransportHandler provided and this NodeReference.
     */
    public Peer getPeer(TransportHandler th, SessionHandler sh, 
                        PresentationHandler ph) {
        Presentation p = null;
        for (Enumeration e = ph.getPresentations() ; e.hasMoreElements() ;) {
            p = (Presentation) e.nextElement();
            if (supports(p)) {
                break;
            }
        }
        if (p == null) {
            Core.logger.log(this,
                "Failed to find supported presentation for peer.",
                Logger.DEBUG);
            return null;
        }
        LinkManager lm = null;
        for (Enumeration e = sh.getLinkManagers() ; e.hasMoreElements() ; ) {
            lm = (LinkManager) e.nextElement();
            if (supports(lm)) {
                break;
            }
        }
        if (lm == null) {
            Core.logger.log(this,
                "Failed to find supported session for peer.",
                Logger.DEBUG);
            return null;
        }

        Address r = null;
        for (Enumeration e = th.getTransports() ; 
             e.hasMoreElements() && r == null;) {
            try {
                r = getAddress((Transport) e.nextElement());
            } catch (BadAddressException bae) {
		Core.logger.log(this, "BadAddressException in getPeer",
				Logger.DEBUG);
            }
        }
	
	// Expanded out for easy augmentation
        if (r == null)
            r = new VoidAddress();
	
	Peer peer = new Peer(identity, r, lm, p);
        return peer;
    }
    
    /**
     * Returns the public key identity of the node reference.
     * @return The node referenced public key.
     */
    public final Identity getIdentity() {
        // maybe I should return a copy. If identity starts getting nuked
        // that will be the problem...
        return identity;
    }

    /**
     * Returns the version string of the NodeReference.
     * @return The version string of the node referenced.
     */
    public final String getVersion() {
        return version;
    }

    /**
     * Returns the ARK (Address resolution key) for this node reference. 
     * Null if ARK is missing.
     */
    public final FreenetURI getARK() {
        return !hasARK ? null : 
            new FreenetURI("SSK",
                           Long.toHexString(ARKrevision),
                           identity.fingerprint(), 
                           ARKcrypt);
    }
    public long getARKRevision(){
    	return ARKrevision;
    }
    public byte[] getARKEncryption(){
    	return (byte[])ARKcrypt.clone(); //Since the returned type is mutable
    }
    
    
    /**
     * Returns true if the other NodeReference is to the same node, but
     * with a new ARK revision value.
     */
    public final boolean supersedes(NodeReference nr) {
	if(nr == null) return true;
	if(!identity.equals(nr.identity)) return false;
	if(signature == null) return false;
	if(nr.signature == null) return true;
	if(ARKrevision < nr.ARKrevision) return false;
	if(noPhysical()) return false;
	if(ARKrevision > nr.ARKrevision) return true;
	if(nr.noPhysical()) return true;
	if(!version.equals(nr.version)) return true;
	return false;
    }
    
    /**
     * Returns true if we have no physical addresses
     */
    public final boolean noPhysical() {
	return physical == null || physical.length == 0;
    }
    
    /**
     * Returns the ARK revision value for this NodeReference
     */
    public final long revision() {
        return ARKrevision;
    }

    public final byte[] cryptoKey() {
	return ARKcrypt;
    }
    
    /**
     * Returns a FieldSet represenation of this NodeReference for
     * serialization.	
     */
    public FieldSet getFieldSet() {
        return getFieldSet(true);
    }

    /**
     * Returns a FieldSet representation of this NodeReference for
     * serialization, optionally omitting the identity.
     * @param addIdentity Whether to add the identity public key to the 
     *                    fieldset. If not, a field called "identityFP" will
     *                    contain the identity fingerprint instead. Note that
     *                    WE WILL KILL BOB IF YOU DO NOT GIVE US ONE MILLION
     *                    DOLLARS IN UNMARKED BILLS the signature field will
     *                    still contain the signature of the entire 
     *                    NodeReference - that is the fieldset that contains
     *                    the identity rather than the one generated.
     */
    public FieldSet getFieldSet(boolean addIdentity) {
        FieldSet fs = new FieldSet();

        if (addIdentity) {
            // add identity
            fs.put("identity", identity.getFieldSet());
        } else {
            fs.put("identityFP", identity.fingerprintToString());
        }

        // add physical addresses
        if (physical.length > 0) {
            FieldSet phys = new FieldSet();
            for (int i = 0 ; i < physical.length ; i += 2)
                phys.put(physical[i], physical[i+1]);
            fs.put("physical", phys);
        }

        fs.put("sessions", Fields.numberList(sessions));
        
        fs.put("presentations",Fields.numberList(presentations));
        
        if (version != null) fs.put("version", version);
        
        // add ARK info
        if (hasARK) {
            FieldSet ARK = new FieldSet();
            ARK.put("revision",Long.toHexString(ARKrevision));
            if (ARKcrypt != null) {
                ARK.put("encryption",HexUtil.bytesToHex(ARKcrypt));
            }
            fs.put("ARK",ARK);
        }

        // add signature if we have it
        if (signature != null) fs.put("signature", signature.toString());

        return fs;
    }

    
    
    private static final Digest ctx = SHA1.getInstance();

	private boolean noGoodVersion = true;
    
    /**
     * Signs the FieldSet with the provided authentity and adds the
     * signature value in the field "signature".
     * @param auth  The private key to sign with.
     */
    public void addSignature(DSAAuthentity auth) {

        byte[] b;
        FieldSet fs = getFieldSet();
        //fs.remove("signature");
        synchronized(ctx) {
            fs.hashUpdate(ctx, new String[] {"signature"});
            b = ctx.digest();
        }
        
        signature = (DSASignature) auth.sign(b);
    }        

    /**
     * Returns true if o is a NodeReference, has the same identity,
     * and the same revision as this.
     */
    public final boolean equals(Object o) {
        return o instanceof NodeReference && equals((NodeReference) o);
    }

    public final boolean equals(NodeReference nr) {
        return identity.equals(nr.identity) && ARKrevision == nr.ARKrevision;
    }

    public final int hashCode() {
        return identity.hashCode() ^ 
            (int) (ARKrevision * 1000000007); // large prime!
    }

    /**
     * @return  The first physical address as a string. They are often
     *          easier on the eye then the identity.
     */
    public String firstPhysicalToString() {
        if (physical.length < 2)
            return "void/void";
        else
            return physical[0] + "/" + physical[1];
    }
    
	public String[] getPhysical(){
		return (String[])physical.clone(); //Since the returned type is mutable
	}
	public long[] getSessions(){
		return (long[])sessions.clone(); //Since the returned type is mutable
	}
	public long[] getPresentations(){
		return (long[])presentations.clone(); //Since the returned type is mutable
	}
	public DSASignature getSignature(){
		return signature;
	}
    
    public String toString() {
        //return getFieldSet().toString();
        StringBuffer sb = new StringBuffer(256);
        for (int i=0; i<physical.length; i+=2) {
            sb.append(physical[i]+'/'+physical[i+1]+", ");
        }
        sb.append("sessions="+Fields.numberList(sessions)+", ");
        sb.append("presentations="+Fields.numberList(presentations)+", ");
        sb.append("ID="+identity+", ");
		sb.append("version="+version);
        return sb.toString();
    }

	/**
	 * @return true if either we have no contact details or we
	 * are of an invalid version.
	 */
	public boolean notContactable() {
		return noPhysical() || noGoodVersion;
	}

    /**
     * @return true if this node is too old a version to talk to.
     */
    public boolean badVersion() {
        return noGoodVersion;
    }

    /**
     * @param physical2
     * @param addr
     * @return
     */
    public static String[] setAllPhysicalAddresses(Address[] addr) {
        String[] phys = new String[0];
        for(int i=0;i<addr.length;i++)
            phys = setPhysicalAddress(phys, addr[i]);
        return phys;
    }

}




