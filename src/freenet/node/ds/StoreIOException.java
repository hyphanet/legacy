package freenet.node.ds;
import java.io.IOException;

public class StoreIOException extends IOException {
    public StoreIOException(IOException e) {
	initCause(e);
    }
    
    public String toString() {
	StringBuffer sb = new StringBuffer();
	sb.append(getClass().getName());
	if(getCause() != null) {
	    sb.append(": "+getCause().toString());
	}
	return new String(sb);
    }
}
