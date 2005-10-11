package freenet.support.io;
import freenet.support.Logger;
import freenet.Core;
import java.io.*;

/**
 * An OutputStream which limits (or "throttles") the bandwidth of bytes written to it.
 */

public class ThrottledOutputStream extends FilterOutputStream {
    
    private static Bandwidth bandwidth;
    private int reservedBandwidth=0;
    
    /**
     * Activate throttling on OutputStreams.
     *
     * @param bandwidth the available bandwidth for OutputStreams.
     */
    
    public static void setThrottle(Bandwidth bandwidth) {
        ThrottledOutputStream.bandwidth = bandwidth;
    }
    
    /**
     * Gets a ThrottledOutputStream based on the given OutputStream.
     * If throttling is turned off (by calling {@ref #setThrottle setThrottle}
     * with a zero or negative argument) then the given OutputStream is
     * simply returned.
     *
     * @param out the OutputStream to throttle.
     * @return an OutputStream which is either the original OutputStream if
     * throttling is turned off, or a new ThrottledOutputStream if not.
     */
    
    public static OutputStream throttledStream(OutputStream out) {
        if (bandwidth == null || bandwidth.bandwidthPerTick <= 0)
            return out;
        Core.logger.log(ThrottledOutputStream.class,
        "ThrottledOutput, creating new stream, bpt = " +
        bandwidth.bandwidthPerTick, Logger.DEBUG);
        return new ThrottledOutputStream(out);
    }
    
    // Force the use of a static method that checks if throttling
    // is on before creating a throttled stream.
    private ThrottledOutputStream(OutputStream out) {
        super(out);
        if(bandwidth!=null && bandwidth.bandwidthPerTick > 0)
            reservedBandwidth = bandwidth.getBandwidth(10000); //slow down the creation of new streams if bandwidth is low.
        
    }
    
    /**
     * Write a single byte to this OutputStream.
     *
     * @param b the byte to write.
     * @throws IOException if an I/O error occurs on the OutputStream.
     */
    
    public void write(final int b) throws IOException {
        bandwidth.getBandwidth(1);
        out.write(b);
        if(reservedBandwidth>0){
            bandwidth.putBandwidth(reservedBandwidth);
            reservedBandwidth = 0;
        }
    }
    
    /**
     * Write an array of bytes to this OutputStream.
     *
     * @param data the bytes to write.
     * @param offset the index in the array to start at.
     * @param totalLength the number of bytes to write.
     * @throws IOException if an I/O error occurs on the OutputStream.
     */
    
    public void write(byte[] data, int offset, int totalLength)
    throws IOException {
        while (totalLength > 0) {
            int length = bandwidth.getBandwidth(totalLength);
            out.write(data, offset, length);
            totalLength -= length;
            offset += length;
        }
        if(reservedBandwidth>0){
            bandwidth.putBandwidth(reservedBandwidth);
            reservedBandwidth = 0;
        }
    }
}





