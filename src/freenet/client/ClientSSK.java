package freenet.client;

import java.util.Random;

import net.i2p.util.NativeBigInteger;
import freenet.KeyException;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.Util;
import freenet.keys.SVK;
import freenet.support.UTF8;

/**
 * ClientKey implementation for SSKs
 *
 * @author sgm
 */
public class ClientSSK extends ClientSVK {

    public static ClientKey createFromInsertURI(Random r, FreenetURI uri) throws KeyException {
        if (uri.getRoutingKey() == null)
            throw new KeyException("Unspecified private key");
        if (uri.getDocName() == null)
            throw new KeyException("Unspecified document name");
	
	DSAGroup dg = null;
	if(uri instanceof InsertURI) {
	    dg = ((InsertURI)uri).getGroup();
	}
	if(dg == null)
	    dg = getDefaultDSAGroup();
        return new ClientSSK( r, uri.getCryptoKey(), uri.getDocName(),
                              new DSAPrivateKey(new NativeBigInteger(1, uri.getRoutingKey())),
                              dg );
    }
    
    public static ClientKey createFromRequestURI(FreenetURI uri) throws KeyException {
        if (uri.getRoutingKey() == null)
            throw new KeyException("Unspecified routing key");
        if (uri.getDocName() == null)
            throw new KeyException("Unspecified document name");
        return new ClientSSK( uri.getRoutingKey(),
                              uri.getCryptoKey(),
                              uri.getDocName()       );
    }


    // store the optional SVK crypto key, and use it to generate
    // the final crypto key from the docname
    private byte[] parentEntropy;
    
    
    public ClientSSK(byte[] val, byte[] cryptoKey, String docName) 
	throws KeyException {
        super(val, null, docName);
        parentEntropy = cryptoKey;
    }

    public ClientSSK(SVK svk, byte[] cryptoKey, String docName) 
	throws KeyException {
        super(svk, null, docName);
        parentEntropy = cryptoKey;
    }

    public ClientSSK(Random r, String docName) throws KeyException {
        this(r, null, docName, null, null);
    }

    public ClientSSK(Random r, byte[] cryptoKey, String docName,
                     DSAPrivateKey sk, DSAGroup grp) throws KeyException {
        super(r, null, docName, sk, grp);
        parentEntropy = cryptoKey;
    }

    /** @return "SSK"
      */
    public String keyType() {
        return "SSK";
    }

    protected void makeCryptoKey() {
        byte[] docNameBytes = UTF8.encode(getDocumentName());
        byte[] entropy;
        if (parentEntropy == null) {
            entropy = docNameBytes;
        }
        else {
            if (parentEntropy.length == 0) {
                parentEntropy = new byte[cipher.getKeySize() >> 3];
                r.nextBytes(parentEntropy);
            }
            entropy = new byte[parentEntropy.length + docNameBytes.length];
            System.arraycopy(docNameBytes, 0, entropy, 0, docNameBytes.length);
            System.arraycopy(parentEntropy, 0, entropy, docNameBytes.length, parentEntropy.length);
        }
        cryptoKey = new byte[cipher.getKeySize() >> 3];
        Util.makeKey(entropy, cryptoKey, 0, cryptoKey.length);
    }

    public FreenetURI getURI() {
        SVK svk = (SVK) getKey();
        if (svk == null) {
            if (pk == null) return null;
            svk = new SVK(pk, getDocumentName(), SVK.SVK_LOG2_MAXSIZE);
        }
        return new FreenetURI(keyType(), getDocumentName(),
                              svk.getRootValue(), parentEntropy);
    }
}



        
        
