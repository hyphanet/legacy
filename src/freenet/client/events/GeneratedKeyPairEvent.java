package freenet.client.events;
import freenet.client.ClientEvent;
import freenet.client.Base64;

public class GeneratedKeyPairEvent implements ClientEvent {

    public byte[] privKey;
    public byte[] pubKey;
    public byte[] cryptoKey;

    public GeneratedKeyPairEvent(byte[] privKey, byte[] pubKey, byte[] cryptoKey) {
        this.privKey = privKey;
        this.pubKey = pubKey;
	this.cryptoKey = cryptoKey;
    }

    public String getPrivKey() {
        return Base64.encode(privKey);
    }

    public String getPubKey() {
        return Base64.encode(privKey);
    }

    public String getCryptoKey() {
	if(cryptoKey == null) return null;
	else return Base64.encode(cryptoKey);
    }
    
    public int getCode() {
        return 668;
    }

    public String getDescription() {
	String privKeyEnc = Base64.encode(privKey);
	String pubKeyEnc = Base64.encode(pubKey);
	String cryptoKeyEnc = null;
	if(cryptoKey != null) 
	    cryptoKeyEnc = Base64.encode(cryptoKey);
        StringBuffer sb = new StringBuffer("Priv. key: ");
        sb.append(privKeyEnc);
        sb.append(" Pub. key: ");
        sb.append(pubKeyEnc);
	if(cryptoKeyEnc != null) {
	    sb.append(" Entropy: ");
	    sb.append(cryptoKeyEnc);
	}
	sb.append("\nInsert at SSK@");
	sb.append(privKeyEnc);
	if(cryptoKeyEnc != null) {
	    sb.append(",");
	    sb.append(cryptoKeyEnc);
	}
	sb.append("/\nRequest at SSK@");
	sb.append(pubKeyEnc);
	sb.append("PAgM"); // FIXME!
	if(cryptoKeyEnc != null) {
	    sb.append(",");
	    sb.append(cryptoKeyEnc);
	}
	sb.append("/");
        return sb.toString();
    }
    
}
