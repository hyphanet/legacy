package freenet.support.io;
import freenet.support.Logger;
import freenet.Core;
import java.io.*;

/**
 * An InputStream which limits (or "throttles") the bandwidth of bytes written to it.
 */

public class ThrottledInputStream extends FilterInputStream {
    
    private static Bandwidth bandwidth;
    private int reservedBandwidth=0;
    protected boolean disabled = false;
    
    /**
     * Activate throttling on InputStreams.
     *
     * @param bandwidth the available bandwidth for InputStreams.
     */

    public static void setThrottle(Bandwidth bandwidth) {
        ThrottledInputStream.bandwidth = bandwidth;
    }

    /**
     * Gets a ThrottledInputStream based on the given InputStream.
     * If throttling is turned off (by calling {@ref #setThrottle setThrottle}
     * with a zero or negative argument) then the given InputStream is
     * simply returned.
     *
     * @param in the InputStream to throttle.
     * @return an InputStream which is either the original InputStream if
     * throttling is turned off, or a new ThrottledInputStream if not. 
     */
        
    public static InputStream throttledStream(InputStream in) {
        if (bandwidth == null || bandwidth.bandwidthPerTick <= 0)
            return in;
        Core.logger.log(ThrottledInputStream.class, 
                        "ThrottledInput, creating new stream, bpt = " +
                        bandwidth.bandwidthPerTick, Logger.DEBUG);
	
        return new ThrottledInputStream(in, false);
    }
    
    // Force the use of a static method that checks if throttling
    // is on before creating a throttled stream.
    private ThrottledInputStream(InputStream in, boolean disabled) {
        super(in);
	this.disabled = disabled;
        if(bandwidth!=null && bandwidth.bandwidthPerTick > 0)
	    reservedBandwidth = bandwidth.getBandwidth(10000); //slow down the creation of new streams if bandwidth is low.
    }
    
    public void setDisabled(boolean disabled) {
	this.disabled = disabled;
    }
    
    /**
     * Read a single byte from this InputStream.
     * @throws IOException if an I/O error occurs on the InputStream.
     */
    public int read() throws IOException {
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	if(logDEBUG) Core.logger.log(this, "read()", Logger.DEBUG);
        int rv = super.read();
	if(logDEBUG) Core.logger.log(this, "done super.read()", Logger.DEBUG);
	if(disabled) return rv;
        if (rv != -1)
            bandwidth.chargeBandwidth(1);
	if(logDEBUG) Core.logger.log(this, "Charged", Logger.DEBUG);
	if(reservedBandwidth>0){
            bandwidth.putBandwidth(reservedBandwidth);
            reservedBandwidth = 0;
        }
	if(logDEBUG) Core.logger.log(this, "Put", Logger.DEBUG);
        return rv;
    }
    
    /**
     * Read an array of bytes from this InputStream.
     *
     * @param data the bytes to read.
     * @param offset the index in the array to start at.
     * @param length the number of bytes to read.
     * @throws IOException if an I/O error occurs on the InputStream.
     */
    public int read(byte[] data, int offset, int length)
        throws IOException
    {
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	if(logDEBUG) Core.logger.log(this, "read(,,)", Logger.DEBUG);
        int rv = in.read(data, offset, length);
	if(logDEBUG) Core.logger.log(this, "super.read(,,) done", Logger.DEBUG);
	if(disabled) return rv;
        if (rv > 0) {
            bandwidth.chargeBandwidth(rv);
        }
        if(reservedBandwidth>0){
            bandwidth.putBandwidth(reservedBandwidth);
            reservedBandwidth = 0;
        }
        return rv;
    }
}





