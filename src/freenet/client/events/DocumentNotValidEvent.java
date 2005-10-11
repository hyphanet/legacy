package freenet.client.events;

import freenet.client.*;
import freenet.support.io.DataNotValidIOException;

public class DocumentNotValidEvent implements ClientEvent {
    
    public static final int code = 0x90;
    private DataNotValidIOException dnv;
        
    public DocumentNotValidEvent(DataNotValidIOException dnv) {
        this.dnv = dnv;
    }

    public String getDescription() {
        return "The document was found to be invalid: "
               + Document.getTextForDNV(dnv.getCode());
    }

    public int getCode() {
        return code;
    }

    public void rethrow() throws DataNotValidIOException {
        throw dnv;
    }
    
    public DataNotValidIOException getDNV() {
        return dnv;
    }
}
