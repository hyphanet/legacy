package freenet.client;

import java.util.Random;

import net.i2p.util.NativeBigInteger;
import freenet.KeyException;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.Global;
import freenet.crypt.SHA1;
import freenet.crypt.Util;
import freenet.keys.SVK;
import freenet.support.UTF8;

/**
 * ClientKey implementation for KSKs
 *
 * @author sgm
 */
public class ClientKSK extends ClientSVK {

    public static ClientKey createFromInsertURI(Random r, FreenetURI uri) throws KeyException {
        return new ClientKSK(r, uri.getDocName());
    }
    
    public static ClientKey createFromRequestURI(FreenetURI uri) throws KeyException {
        if (uri.getDocName() == null)
            throw new KeyException("Unspecified KSK name");
        return new ClientKSK(uri.getDocName());
    }

    
    // for KSK, all we need to do is set up all the internal
    // values immediately on construction, then act like an SVK

    private String keyword;

    public ClientKSK(String keyword) throws KeyException {
        this(null, keyword);
    }

    public ClientKSK(Random r, String keyword) throws KeyException {
        super( r, null, null,
               new DSAPrivateKey(new NativeBigInteger(1, Util.hashString(SHA1.getInstance(), keyword))),
               getDefaultDSAGroup() );
        this.keyword = keyword;
        this.svk = new SVK(pk, null, SVK.SVK_LOG2_MAXSIZE);
    }

    /** @return "KSK"
      */
    public String keyType() {
        return "KSK";
    }
    
    protected void makeCryptoKey() {
        byte[] entropy = UTF8.encode(keyword);
        cryptoKey = new byte[cipher.getKeySize() >> 3];
        Util.makeKey(entropy, cryptoKey, 0, cryptoKey.length);
    }
    
    public static DSAGroup getDefaultDSAGroup() {
        return Global.DSAgroupA;
    }

    public FreenetURI getURI() {
        return new FreenetURI(keyType(), keyword);
    }

    public String getKeyword() {
        return keyword;
    }

/*
    public static void main(String[] args) throws Exception {
        ClientKSK s=new ClientKSK(new Yarrow(), args[0]);
        s.getKey();
        s.getEncryptionKey(16);
        System.err.println(s.getURI());
        FreenetURI n=new FreenetURI(s.getURI().toString());
        n.decompose();
    }
*/
}



        
        
