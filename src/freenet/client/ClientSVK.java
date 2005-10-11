package freenet.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Random;

import net.i2p.util.NativeBigInteger;
import freenet.Key;
import freenet.KeyException;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;
import freenet.crypt.SHA1;
import freenet.crypt.Util;
import freenet.keys.SVK;
import freenet.support.Bucket;

/**
 * ClientKey implemenation for SVKs
 *
 * @author sgm
 */
public class ClientSVK extends AbstractClientKey {

    public static ClientKey createFromInsertURI(Random r, FreenetURI uri) throws KeyException {
        DSAPrivateKey sk = ( uri.getKeyVal() == null
                             ? null
                             : new DSAPrivateKey(new NativeBigInteger(1, uri.getKeyVal())) );
	DSAGroup dg = null;
	if(uri instanceof InsertURI) {
	    dg = ((InsertURI)uri).getGroup();
	}
	if(dg == null)
	    dg = getDefaultDSAGroup();
	
        return new ClientSVK( r, uri.getCryptoKey(), null, sk, dg );
    }
    
    public static ClientKey createFromRequestURI(FreenetURI uri) throws KeyException {
        if (uri.getRoutingKey() == null)
            throw new KeyException("Unspecified routing key");
        if (uri.getCryptoKey() == null)
            throw new KeyException("Unspecified encryption key");
        return new ClientSVK( uri.getRoutingKey(),
                              uri.getCryptoKey(),
                              null                );
    }

    protected Random r;
    protected SVK svk;
    protected byte[] cryptoKey;
    protected String docName;
    protected DSAPrivateKey sk; //Should always be set to the private key for the public key below 
    protected DSAPublicKey pk;  //Should always be set to the public key for the private key above

    public ClientSVK(byte[] val, byte[] cryptoKey, String docName) 
	throws KeyException {
        this(new SVK(val, docName), cryptoKey, docName);
    }

    public ClientSVK(SVK svk, byte[] cryptoKey, String docName) 
	throws KeyException {
        this.svk       = svk;
        this.cryptoKey = cryptoKey;
        this.docName   = docName;
    }
    
    public ClientSVK(Random r) throws KeyException {
        this(r, null, null, null, null);
    }

    public ClientSVK(Random r, String docName) throws KeyException {
        this(r, null, docName, null, null);
    }
    
    /** Creates a new ClientSVK with the given private key and DSA group.
      * @param r        the Random source to create a new keypair,
      *                 or sign a set of Storables
      * @param docName  may be null, otherwise will be added to
      *                 the Storables when they are signed.
      * @param sk       if null, a new keypair will be created
      *                 with the given DSAGroup
      * @param grp      if null, sk will be ignored and a new
      *                 keypair will be created with getDefaultDSAGroup()
      */
    public ClientSVK(Random r, byte[] cryptoKey, String docName, 
                     DSAPrivateKey sk, DSAGroup grp) throws KeyException {
        super();
        this.r       = r;
        this.docName = docName;
        if (grp == null) {
            grp = getDefaultDSAGroup();
            sk = null;
        }
        if (sk == null) {
            sk = new DSAPrivateKey(grp, r);
        }        
        this.sk = sk;
        this.pk = new DSAPublicKey(grp, sk);
        this.cryptoKey = cryptoKey;
    }

    /** @return "SVK"
      */
    public String keyType() {
        return "SVK";
    }

    public String getDocumentName() {
        return docName;
    }

    public Key getKey() {
        return svk;
    }
    
    /** @return the encryption key
      */
    public byte[] getCryptoKey() throws KeyException {
        try {
            if (cryptoKey == null) makeCryptoKey();
        }
        catch (Exception e) {
	    e.printStackTrace();
            throw new KeyException(e.toString());
        }
        return cryptoKey;
    }

    protected void makeCryptoKey() throws Exception {
        byte[] keyBytes = new byte[cipher.getKeySize() >> 3];
        cryptoKey       = new byte[keyBytes.length];
        r.nextBytes(keyBytes);
        Util.makeKey(keyBytes, cryptoKey, 0, cryptoKey.length);
    }

    private InputStream priv_encode(InputStream in,
                                    long length, long metaLength,
                                    Bucket ctBucket) throws KeyException, IOException {
        if (length > SVK.SVK_MAXSIZE)
            throw new KeyException("length cannot exceed "+SVK.SVK_MAXSIZE+" bytes");
        InputStream ret = encode(in, length, metaLength, ctBucket);
        if (getDocumentName() != null) {
            storables.setDocumentName(Util.hashString(SHA1.getInstance(), 
						      getDocumentName()));
        }
        storables.sign(r, sk, pk);
        svk = new SVK(storables, SVK.SVK_LOG2_MAXSIZE);
        return ret;
    }
    
    public InputStream encode(Bucket plain, long metaLength,
                              Bucket ctBucket) throws KeyException, IOException {
        
        InputStream in = plain.getInputStream();
        try {
            return priv_encode(in, plain.size(), metaLength, ctBucket);
        }
        finally {
            try { in.close(); } catch (IOException e) {}
        }
    }

    public InputStream encode(Bucket plainMeta, Bucket plainData,
                              Bucket ctBucket) throws KeyException, IOException {

        InputStream meta = plainMeta.getInputStream();
        InputStream data = plainData.getInputStream();
        try {
            return priv_encode(new SequenceInputStream(meta, data),
                               plainMeta.size() + plainData.size(),
                               plainMeta.size(), ctBucket );
        }
        finally {
            try { meta.close(); } catch (IOException e) {}
            try { data.close(); } catch (IOException e) {}
        }
    }

    /** @return the public key for this SVK, or null if unknown
      *         it is a sequence of four MPI byte arrays in the order
      *         y, p, q, g
      */
    public byte[] getPublicKey() {
        return pk == null ? null : pk.asBytes();
    }

    /** @return the public key fingerprint for this SVK, or null if unknown
      *         it's just an SHA1 hash of getPublicKey()
      */
    public byte[] getPublicKeyFingerPrint() {
        return pk == null ? null : Util.hashBytes(SHA1.getInstance(), 
						  pk.asBytes());
    }
    
    /** @return the private key for this SVK, or null if unknown
      */
    public byte[] getPrivateKey() {
        return sk == null ? null : sk.getX().toByteArray();
    }

    /** ClientKSK, for example, should override this
      * to return Global.DSAgroupA.
      */
    public static DSAGroup getDefaultDSAGroup() {
        return Global.DSAgroupB;
    }
    
    public FreenetURI getURI() {
        SVK svk = (SVK) getKey();
        if (svk == null) {
            if (pk == null) return null;
            svk = new SVK(pk, getDocumentName(), SVK.SVK_LOG2_MAXSIZE);
        }
	try {
	    return new FreenetURI(keyType(), getDocumentName(),
				  svk.getVal(), getCryptoKey());
	} catch (KeyException e) {
	    return null;
	}
    }
}






