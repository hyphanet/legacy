package freenet.support.io;
import freenet.*;
import java.io.*;

public class ControlInputStream extends VerifyingInputStream {

    private static byte OK = Presentation.CB_OK;

    private long partLength;
    private long pbytesRead = 0;

    private boolean streamDead = false;

    private int endChar  = -1;
    private int endsWith = -1;

    /**
     * Creates a new ControlInputStream
     * @param in         The InputStream to filter.
     * @param partLength The length of data between each control character.
     *                   So If Partlength Is 20, A Control Character Is
     *                   expected at 21, the second at 42 and so on. 
     * @param docLength  The total length of the data to read.
     */
    public ControlInputStream(InputStream in, long partLength, long docLength){
	super(in, docLength);
	this.partLength = partLength;
	if (in instanceof PadOnErrorInputStream) 
	    ((PadOnErrorInputStream) in).setCIS(this);
    }

    protected void checkPart(int cb) throws IOException, 
    DataNotValidIOException {
	pbytesRead = 0;
	bytesRead--;
	if (endChar != -1)
	    throw new DataNotValidIOException(endChar);
	if (cb == -1)
	    throw new EOFException("Premature end-of-stream");
	else if (cb != OK)
	    throw new DataNotValidIOException(cb);	
    }
	
    public int read() throws IOException, DataNotValidIOException {
	if (streamDead)
	    throw new EOFException("Already closed");

	if (endsWith != -1) {
	    checkPart(endsWith);
	    return endsWith;
	}

	int rv=(partLength == 0 ? super.read() : priv_read());
	
	if (rv != -1 && finished) {
	    checkPart(rv);
	}
	return rv;
    }
    
    private int priv_read() throws IOException, DataNotValidIOException {
	
	int rv=super.read();
	
	if (rv != -1) {
	    pbytesRead++;
	    if (pbytesRead == partLength) 
		checkPart(super.read());
	}

	return rv;
    }

    public int read(byte[] b, int off, int length)     
	throws IOException, DataNotValidIOException {
	if (streamDead)
	    throw new EOFException("Already closed");

	if (endsWith != -1) {
	    checkPart(endsWith);
	    b[off] = (byte) endsWith;
	    return 1;
	}

	if (length == 1) {
	    b[off] = (byte) priv_read();
	    if (finished) {
		checkPart(b[off] & 0xff);
	    }
	    return 1;
	} else {
	    int rv=(partLength == 0 ? super.read(b, off, length) :
		    priv_read(b, off, length-1));

	    if (rv!=-1) {
		if (rv == 0) 
		    return (length == 0 ? 0 : read(b, off, length));
		if (finished) {
		    if (rv == 1)
		        checkPart(b[off + rv -1] & 0xff);
		    else {
			endsWith = b[off + rv -1] & 0xff;
			rv--;
		    }
		}
	    }
	    return rv;
	}
    }

    int priv_read(byte[] b, int off, int length) 
	throws IOException, DataNotValidIOException {

	if (length == 0)
	    return 0;
	int rc=-1;
	boolean checked = false;

	if (pbytesRead == partLength) {
	    int i = super.read();
	    if (i == -1)
		throw new EOFException("EOF where control byte expected");
	    checkPart(i);
	    b[off] = (byte) i;
	    off++;
	    length--;
	    checked = true;
	    pbytesRead = 0;
	}

	if ((length + pbytesRead) >= partLength)
	    length = (int)(partLength - pbytesRead);

	rc=super.read(b, off, length);
	
	if (rc != -1) {
	    pbytesRead += rc;
	} else {
	    throw new EOFException("Unexpected EOF");
	}
	
	if (checked)
	    rc++;

	return rc;
    }
    
    public void close() throws IOException {
	if (streamDead) {
	    throw new IOException();
	} else {
	    streamDead = true;
	    super.close();
	}
    }

    /**
     * Ends the stream at the next control character.
     * @param i The character to send as the next (and final) control
     *          character;
     **/
    public void endWithNextControl(int i) {
	if (i >= 0 && i <= 255)
	    endChar = i ;
	else
	    throw new IndexOutOfBoundsException("Control char must be between 0 and 255");
    }

    public static void main(String[] args) throws IOException {
	ControlInputStream cis=new ControlInputStream(System.in, 0, Integer.parseInt(args[0]));
	byte[] buffer=new byte[1024];
        //      int i=0;
	while(true) {
	    int rc=cis.read(buffer,0,1024);
	    //	    System.err.println(rc);
	    //	    if (i++==3) cis.endWithNextControl(1);
	    if (rc!=-1) {
		System.out.write(buffer,0,rc);
	    } else {
		System.out.flush();
		System.exit(0);
	    }
	}
    }

    class EndWithNextControlIOException extends IOException {
	protected byte cb;

	public EndWithNextControlIOException(byte cb) {
	    this.cb=cb;
	}

	public byte getCode() {
	    return cb;
	}
    }
}








