package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;

import freenet.Core;
import freenet.Key;
import freenet.support.Logger;

/**
 * @author amphibian
 *
 * A class to keep track of the last N requests made of this node.
 * Keeps {key, htl, size} for each one.
 * Also can cache pDataNonexistant.
 */
public class RecentRequestHistory {

    final int maxLength;
    int counter;
    int index;
    int unconfirmedCount;
    
    final RequestHistoryItem[] items;
    final NGRoutingTable ngrt;

    final static int SERIAL_MAGIC = 0x438a40d1;
    
    /**
     * @param keep_requests
     * @param object
     * @param dis
     */
    public RecentRequestHistory(int maxLength, NGRoutingTable ngrt, DataInputStream dis, int maxHTL, long maxSize) throws IOException {
        this.maxLength = maxLength;
        items = new RequestHistoryItem[maxLength];
        this.ngrt = ngrt;
        int magic = dis.readInt();
        if(magic != SERIAL_MAGIC) throw new IOException("Invalid magic "+magic+" should be "+SERIAL_MAGIC);
        int ver = dis.readInt();
        if(ver != 0) throw new IOException("Unrecognized version "+ver);
        int maxLen = dis.readInt();
        if(maxLen != maxLength) throw new IOException("Incorrect maxLength: "+maxLen+" should be "+maxLength);
        counter = dis.readInt();
        if(counter < 0) throw new IOException("Invalid counter "+counter);
        index = dis.readInt();
        if(index < 0 || index > maxLength) throw new IOException("Invalid index "+index+" should be between 0 and maxLength ("+maxLength+")");
        unconfirmedCount = dis.readInt();
        if(unconfirmedCount < 0 || unconfirmedCount > counter) throw new IOException("Invalid unconfirmedCount: "+unconfirmedCount+" should be between 0 and "+counter);
        int len = Math.min(maxLength, counter);
        for(int i=0;i<len;i++) {
            items[i] = new RequestHistoryItem(dis, maxHTL, maxSize);
        }
    }

    /**
     * Serialize our data out.
     * DO NOT include the NGRT or we loop.
     */
    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(SERIAL_MAGIC);
        dos.writeInt(0); // version
        dos.writeInt(maxLength);
        dos.writeInt(counter);
        dos.writeInt(index);
        dos.writeInt(unconfirmedCount);
        int len = Math.min(maxLength, counter);
        for(int i=0;i<len;i++) {
            items[i].writeTo(dos);
        }
    }
    
    public String toString() {
        return super.toString()+": counter="+counter+", index="+index+
        	", unconfirmed: "+unconfirmedCount+" for "+ngrt;
    }
    
    public static class RequestHistoryItem{
    	public final Key key;
    	public final int HTL;
    	public final long size;
    	final double pDNE;
    	RequestHistoryItem(Key key, int HTL,long size, double pDNE){
    		this.key = key;
    		this.HTL = HTL;
    		this.size = size;
    		this.pDNE = pDNE;
    	}
    	
        public RequestHistoryItem(DataInputStream dis, int maxHTL, long maxSize) throws IOException {
            BigInteger bi = freenet.support.HexUtil.readBigInteger(dis);
            key = new Key(bi);
            HTL = dis.readInt();
            if(HTL < 0 || HTL > maxHTL) throw new IOException("Invalid HTL: "+HTL+" should be between 0 and "+maxHTL);
            size = dis.readLong();
            if(size < 0 || size > maxSize) throw new IOException("Size invalid: "+size);
            pDNE = dis.readDouble();
            if(pDNE < 0.0 || pDNE > 1.0) throw new IOException("Invalid pDNE: "+pDNE);
        }

        /**
         * Serialize item to a DataOutputStream
         */
        public void writeTo(DataOutputStream dos) throws IOException {
            key.writeTo(dos);
            dos.writeInt(HTL);
            dos.writeLong(size);
            dos.writeDouble(pDNE);
        }
    }
    
    public RecentRequestHistory(int maxLength, NGRoutingTable ngrt) {
        this.maxLength = maxLength;
        items = new RequestHistoryItem[maxLength];
        this.ngrt = ngrt;
        counter = 0;
        index = 0;
    }
    
    public synchronized void add(Key k, int htl, long size, double pDNE) {
        items[index] = new RequestHistoryItem(k,htl,size,pDNE);
        index++;
        if(index == maxLength) index = 0;
        counter++;
        if(Core.logger.shouldLog(Logger.DEBUG,this)) Core.logger.log(this, "Added "+k+":"+htl+":"+size+":"+pDNE+" on "+this, 
                new Exception("debug"), Logger.DEBUG);
    }

    /**
     * Recalculate the last N estimates, and record them on the
     * SimpleRunningAverage's supplied.
     * @param averageNormalizedEstimate RunningAverage for normalized estimates. 
     * @param averageEstimate RunningAverage for raw estimates
     * @param estimator the NodeEstimator to use for calculations.
     */
    public void regenerateAverages(ClearableRunningAverage averageNormalizedEstimate, 
            ClearableRunningAverage averageEstimate, NodeEstimator estimator,
            long typicalSize) {
        averageNormalizedEstimate.clear();
        averageEstimate.clear();
        for(int i=0;i<Math.min(counter, maxLength); i++) {
            Estimate e = estimator.longEstimate(items[i].key, items[i].HTL, items[i].size, typicalSize, items[i].pDNE, true, this);
            averageNormalizedEstimate.report(e.normalizedValue);
            averageEstimate.report(e.value);
        }
    }

    /**
     * @return the last however many requests
     */
    public RequestHistoryItem[] snapshot() {
        int len = Math.min(maxLength, counter);
        RequestHistoryItem[] rrh = new RequestHistoryItem[len];
        for(int i=0;i<len;i++) {
            rrh[i] = items[i];
        }
        return rrh;
    }

    /**
     * @param k
     * @param htl
     * @param size
     * @param dataNonexistant
     */
    public synchronized void addTentative(Key k, int htl, long size, double dataNonexistant) {
        add(k, htl, size, dataNonexistant);
        unconfirmedCount++;
    }
    
    public synchronized void confirm() {
        unconfirmedCount--;
    }
    
    public synchronized int getUnconfirmedCount() {
        return unconfirmedCount;
    }

    /**
     * Dump contents to stream as text, one per line
     */
    public void dumpSimple(PrintStream stream) {
        if(counter > maxLength) {
            for(int i=index;i<maxLength;i++)
                dumpLine(stream, i, i-index);
            for(int i=0;i<index;i++)
                dumpLine(stream, i, i+index);
        } else
            for(int i=0;i<counter;i++)
                dumpLine(stream, i, i);
    }

    private void dumpLine(PrintStream s, int i, int count) {
        s.println(Integer.toString(count)+": "+items[i].key+"@"+items[i].HTL);
    }
}
