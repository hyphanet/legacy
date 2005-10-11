package freenet.client;

import freenet.support.Bucket;

/** Represents a ComputeCHKRequest (GenerateCHK in FCP)
  * @author tavin
  */
public class ComputeCHKRequest extends Request {

    ClientCHK clientKey;
    Bucket meta, data;
    String cipherName;
    
    /**
      */
    public ComputeCHKRequest(String cipherName, Bucket meta, Bucket data) {
        super();
        this.cipherName = (cipherName == null ? "Twofish" : cipherName);
        this.meta       = meta;
        this.data       = data;
// 	System.err.println("ComputeCHKRequest: Metadata: ");
// 	if(meta == null) {
// 	    System.err.println("null");
// 	} else {
// 	    try {
// 		int x = (int)(meta.size());
// 		System.err.println("Length: "+x);
// 		if(x == 0)
// 		    new Exception ("debug").printStackTrace();
// 		byte[] b = new byte[x];
// 		java.io.InputStream s = meta.getInputStream();
// 		for(int y=0;y<x;) {
// 		    y += s.read(b, y, x-y);
// 		}
// 		System.err.write(b);
// 	    } catch (java.io.IOException e) {};
// 	}
    }

    /** @return the generated URI or null if request incomplete
      */
    public FreenetURI getURI() {
        return clientKey == null ? null : clientKey.getURI();
    }

}







