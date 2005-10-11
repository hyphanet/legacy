package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.Core;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.support.Logger;
/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
 */

/**
 * This is the DataReply message
 *
 * @see freenet.node.Node
 * @see freenet.Address
 * @author Brandon Wiley (blanu@uts.cc.utexas.edu)
 * @author Ian Clarke (I.Clarke@strs.co.uk)
 * @author oskar (rewritten from scratch... several times)
 **/

public class DataReply extends DataSend {

    public static final String messageName = "DataReply";
    public Exception initException; // DO NOT MERGE TO STABLE
    
    public DataReply(long idnum,  
		     FieldSet fs, long length) {
	
	super(idnum, fs, null, length);
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	   initException = new Exception("new DataReply (a)");
	else initException = null;
    }

    // if we're creating a new DataReply, it's to send it,
    // so we cannot give it an InputStream
    //public DataReply(long idnum, FieldSet fs, InputStream data, long length)
    //       throws IOException {
    //super(idnum, fs, data, length);
    //}
    
    public DataReply(BaseConnectionHandler source, RawMessage raw)
	throws InvalidMessageException {
	
	super(source, raw);
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	   initException = new Exception("new DataReply (b)");
	else initException = null;
    }

    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
        RawMessage raw=super.toRawMessage(t,ph);
        //raw.messageType="DataReply";
        return raw;
    }

    public String getMessageName() {
        return messageName;
    }

    public int getPriority() {
        return -5; // fairly important
    }

    // index immediately
    //public ReceiveData cacheData(Node n, Key searchKey) 
    //        throws IOException, DataNotValidIOException, KeyCollisionException {
    //    return super.cacheData(n, searchKey, true);
    //}
}








