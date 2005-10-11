/*
   This code is part of the Java Adaptive Network Client by Ian Clarke. 
   It is distributed under the GNU Public Licence (GPL) version 2.  See
   http://www.gnu.org/ for further details of the GPL.
*/


/**
 * This is the DataInsert message
 *
 * @see Node
 * @see Address
 * @author Brandon Wiley (blanu@uts.cc.utexas.edu)
 * @author Ian Clarke (I.Clarke@strs.co.uk)
 * @author oskar
 **/

package freenet.message;

import freenet.BaseConnectionHandler;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.support.io.DiscontinueInputStream;

public class DataInsert extends DataSend {

    public static final String messageName = "DataInsert";

    public DataInsert(long idnum, FieldSet otherfields, long length) {
	super(idnum, otherfields, null, length);
    }

    public DataInsert(long idnum, FieldSet otherfields, 
		      DiscontinueInputStream data, long length) {
	super(idnum, otherfields, data, length);
    }


    public DataInsert(BaseConnectionHandler source, RawMessage raw)
	throws InvalidMessageException {
	super(source, raw);
    }
    
    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
	RawMessage raw=super.toRawMessage(t,ph);
	//raw.messageType="DataInsert";
	//	System.out.println(raw.toString());
	return raw;
    }

    public String getMessageName() {
        return messageName;
    }

    public int getPriority() {
        return -5; // same as DataReply
    }
    
    // index later
    //public ReceiveData cacheData(Node n, Key searchKey) 
    //        throws IOException, DataNotValidIOException, KeyCollisionException {
    //    return super.cacheData(n, searchKey, false);
    //}
}


