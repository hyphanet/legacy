package freenet.client;

import freenet.*;
import freenet.crypt.DSAGroup;

import java.net.MalformedURLException;

/**
 * Freenet Insertion URI - this has the private key instead of the public key,
 * and optionally supports a DSAGroup (currently this is not part of the actual
 * text form of the URI - fix this if you like but it'll be biiig).
 *
 * Currently, at time of creation, this is just a hack to pass a DSAGroup into
 * the insertion process - this should be used more widely by all the insert
 * related stuff that uses a FreenetURI currently. FIXME.
 */
public class InsertURI extends FreenetURI {
    private DSAGroup dg = null;
    
    public final DSAGroup getGroup() {
	return dg;
    }

    public final void setGroup(DSAGroup dg) {
	this.dg = dg;
    }

    public InsertURI(String keyType, String docName) {
	super(keyType, docName);
    }

    public InsertURI(String keyType, String docName,
		     byte[] routingKey, byte[] cryptoKey) {
	super(keyType, docName, routingKey, cryptoKey);
    }
    
    public InsertURI(String keyType, String docName,
		     byte[] routingKey, byte[] cryptoKey, DSAGroup dg) {
	super(keyType, docName, routingKey, cryptoKey);
	this.dg = dg;
    }

    public InsertURI(String keyType, String docName, String metaStr,
		     FieldSet metaInfo, byte[] routingKey, byte[] cryptoKey) {
	super(keyType, docName, metaStr, metaInfo, routingKey, cryptoKey);
    }
    
    public InsertURI(String keyType, String docName, String metaStr,
		     FieldSet metaInfo, byte[] routingKey, byte[] cryptoKey,
		     DSAGroup dg) {
	super(keyType, docName, metaStr, metaInfo, routingKey, cryptoKey);
	this.dg = dg;
    }
    
    public InsertURI(String keyType, String docName, String[] metaStr,
		     FieldSet metaInfo, byte[] routingKey, byte[] cryptoKey) {
	super(keyType, docName, metaStr, metaInfo, routingKey, cryptoKey);
    }
    
    public InsertURI(String keyType, String docName, String[] metaStr,
		     FieldSet metaInfo, byte[] routingKey, byte[] cryptoKey,
		     DSAGroup dg) {
	super(keyType, docName, metaStr, metaInfo, routingKey, cryptoKey);
	this.dg = dg;
    }
    
    public InsertURI(String uri) throws MalformedURLException {
	super(uri);
    }
}
