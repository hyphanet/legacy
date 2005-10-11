package freenet.support.io;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import freenet.Core;
import freenet.support.Logger;

public class PadOnErrorInputStream extends FilterInputStream {
    protected ControlInputStream parent;
    protected byte padValue;

    /**
     * Creates a PadOnErrorInputStream that pads with zeros after the
     * filter ends or dies.
     * @param in  InputStream to filter.
     */
    public PadOnErrorInputStream(InputStream in) {
	this(in, (byte) 0);
    }

    /**
     * Creates a PadOnErrorInputStream.
     * @param in InputStream to filter.
     * @param padValue   Value to pad with after in ends or dies.
     */
    public PadOnErrorInputStream(InputStream in, byte padValue) {
	super(in);
        this.padValue = padValue;
    }

    public void setCIS(ControlInputStream cis) {
	parent = cis;
    }

    public int read() throws IOException {
	try {
	    int rv=in.read();
	    if (rv == -1) {
		goPadding(new EOFException());
		return read();
	    } else return rv;
	} catch (IOException e) {
	    goPadding(e);
	    return read();
	}
    }

    public int read(byte[] buff) throws IOException {
  	return read(buff, 0, buff.length);
    }
    
    public int read(byte[] buff, int off, int len) throws IOException {
  	try {
	    int rv=in.read(buff, off, len);
	    if (rv==-1) {
		goPadding(new EOFException());
		return read(buff, off, len);
	    } else return rv;
  	} catch (Exception e) {
	    goPadding(e);
	    return read(buff, off, len);
  	}	
    }
    
    protected void goPadding(Exception e) {
	Core.logger.log(this, "Exception caught: "+e+
			" padding...", Logger.MINOR);
	try {
	    in.close();
	} catch (IOException ie) {}
	in=new ZeroInputStream(padValue, Core.blockSize);
	if (parent != null)
	    parent.endWithNextControl(padValue);
    }
	
    public void close() throws IOException {
  	in.close();
    }
    
    public int available() throws IOException {
  	return in.available();
    }
    
    public long skip(long bytes) throws IOException {
  	return in.skip(bytes);
    }
}
