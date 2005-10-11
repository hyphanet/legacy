package freenet.diagnostics;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Enumeration;

/**
 * Implementation of a the binomial random var.
 *
 * @author oskar
 */

class Binomial extends RandomVar {

    public static long VERSION = 2;

    static {
        try {
            RandomVar.registerType("Binomial", RandomVar.class);
        } catch (NoSuchMethodException e) {
        }
    }
    
	public static abstract class BinomialEventCallback implements RandomVar.EventCallback {

		public void reportArrived(VarEvent e){
			if(e instanceof BinomialVarEvent)
				reportArrived((BinomialVarEvent)e);
			else
				throw new IllegalArgumentException("e not a BinomialVarEvent");
		}

		public abstract void reportArrived(BinomialVarEvent e);
	}
    
    public Binomial(StandardDiagnostics sd, String name, int period, String comment) {
        super(sd, name, period, comment);
    }

    public String getType() {
        return "Binomial";
    }
    
    public void add(long time, long n, long x) {
        super.add(new BinomialVarEvent(time, n, x));
    }
    
    public void add(BinomialVarEvent e) {
        super.add(e);
    }
    
    public VarEvent aggregate(EventDequeue.Tail t, long time, long after) {
        long tx = 0, tn = 0;

		synchronized (t) {
			for (Enumeration e = t.elements(); e.hasMoreElements();) {
            BinomialVarEvent bve = (BinomialVarEvent) e.nextElement(); 
            if (bve.time() > after) {
                    if (bve.time() > time) break;
                tn += bve.n;
                tx += bve.x;        
            }
        }
            // t.purgeOldEvents(time);
		}
        return new BinomialVarEvent(time, tn, tx);
    }

    public VarEvent readEvent(DataInputStream in) throws IOException {
        long version = in.readLong();
        long time = (version > StandardDiagnostics.y2k ? version : in.readLong());
        long n = in.readLong();
        long x = in.readLong();
        return new BinomialVarEvent(time, n, x);
    }

    public String[] headers() {
        return new String[] { "Tries" , "Successes", "Ratio" };
    }
}
