package freenet.client;

import freenet.client.events.*;
import freenet.support.io.DiscontinueInputStream;
import java.io.*;

/**
 * An inputstream that generates status events.
 *
 * @author oskar
 **/

public class EventInputStream extends DiscontinueInputStream {

    private ClientEventProducer cep;
    private boolean ee;  // produce events on exceptions?
    private long interval, total, meta, data, read = 0;
    private boolean closed = false;


    public EventInputStream(InputStream in, ClientEventProducer cep,
                            long interval, long meta, long data, long total) {
        this(in, cep, interval, meta, data, total, false);
    }
    
    /**
     * Wrap an EventInputStream around an InputStream.
     * @param in        the Inputstream to read.
     * @param cep       the event producer to use when events are raised
     * @param interval  Generate an event every time <b>interval</b> 
     *                  bytes have been moved. 
     * @param total     total number of bytes to read on this stream
     * @param ee        true to produce events on exceptions
     * @param meta      metadata length for making the TransferStartedEvent
     * @param data      data length for making the TransferStartedEvent
     */
    public EventInputStream(InputStream in, ClientEventProducer cep,
                            long interval, long meta, long data, 
			    long total, boolean ee) {
        super(in);
        this.cep = cep;
        this.ee  = ee && (cep != null);
        this.interval = interval;
        this.total = total;
	this.meta = meta;
	this.data = data;
	cep.produceEvent(new TransferStartedEvent(new long[] { meta, data, total }));
    }
    
    private void doEvents(long inc) {
        if (cep != null) {
            if (read + inc == total)
                cep.produceEvent(new TransferCompletedEvent(total));
            else if (interval > 0 && inc >= (interval - read % interval))
                cep.produceEvent(new TransferEvent(read + inc));
        }
    }

    public int read() throws IOException {
        if (read == total) return -1;
        try {
            int i = super.read();
            if (i != -1) {
                doEvents(1);
                if (++read == total) priv_close();
            }
            else priv_close();
            
            return i;
        }
        catch (IOException e) {
            if (ee) cep.produceEvent(new ExceptionEvent(e));
            throw (IOException) e.fillInStackTrace();
        }
    }

    public int read(byte[] buf, int off, int len) throws IOException {
        if (read == total) return -1;
        try {
            int i = super.read(buf, off, len); 
            if (i != -1) {
                doEvents(i);
                if ((read += i) == total) priv_close();
            }
            else priv_close();
            
            return i;
        }
        catch (IOException e) {
            if (ee) cep.produceEvent(new ExceptionEvent(e));
            throw (IOException) e.fillInStackTrace();
        }
    }

    private final void priv_close() throws IOException {
        closed = true;
        in.close();
    }

    public void close() throws IOException {
        try {
            if (!closed) priv_close();
        }
        catch (IOException e) {
            if (ee) cep.produceEvent(new ExceptionEvent(e));
            throw (IOException) e.fillInStackTrace();
        }
    }

    public final void discontinue() throws IOException {
        close();
    }
}



