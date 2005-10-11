package freenet.node.rt;

import java.math.BigInteger;

import freenet.Core;
import freenet.Key;
import freenet.support.Unit;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.graph.GraphDataSet;


/**
 * @author amphibian
 * 
 * A base class for KeyspaceEstimators based on converting the key to a
 * BigInteger. Contains utility methods extracted from 
 * DecayingKeyspaceEstimator
 */
public abstract class NumericKeyKeyspaceEstimator implements KeyspaceEstimator {
    
    protected boolean logDEBUG = true;
    protected final Unit type;
    protected final String name;
    
    public abstract Object clone();
    
    NumericKeyKeyspaceEstimator(Unit type, String name) {
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        this.type = type;
        this.name = name;
    }
    
    public NumericKeyKeyspaceEstimator(NumericKeyKeyspaceEstimator e) {
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        this.name = e.name;
        this.type = e.type;
    }

    protected void logDEBUG(String s) {
		if (logDEBUG) {
			Core.logger.log(this, s + " (" + this +")", Logger.DEBUG);
		}
	}

	public String toString() {
	    return super.toString() + ": "+name+" of "+type();
	}
	
	protected final String type() {
	    return type(type);
	}
	
    protected final String type(Unit t) {
        if(t == KeyspaceEstimator.PROBABILITY) return "probability";
        if(t == KeyspaceEstimator.TIME) return "time";
        if(t == KeyspaceEstimator.TRANSFER_RATE) return "transfer rate";
        return "unknown type: "+t;
    }

    protected static void log(int level, String s) {
		if (Core.logger.shouldLog(level, DecayingKeyspaceEstimator.class)) {
			Core.logger.log(DecayingKeyspaceEstimator.class, s, level);
		}
	}

	public GraphDataSet createGDS(int samples, int age) {
	    Core.logger.log(this, "Creating GDS for "+this, Logger.DEBUG);
		GraphDataSet g = new GraphDataSet();

		BigInteger at = BigInteger.ZERO;
		BigInteger keyspaceLastKey = Key.KEYSPACE_SIZE.subtract(BigInteger.ONE);
		BigInteger keyspaceStepLength =
			keyspaceLastKey.divide(BigInteger.valueOf(samples));

		synchronized (getGDSSync()) {
			for (int i = 0; i < samples; i++) {
			    if(logDEBUG)
			        Core.logger.log(this, "Guessing sample "+i+": "+HexUtil.biToHex(at),
			                Logger.DEBUG);
				g.addPoint(i, guessRaw(at, age));
				at = at.add(keyspaceStepLength);
			}
		}
		return g;
	}

	abstract protected Object getGDSSync();

	abstract protected double guessRaw(BigInteger b, int age);
	
    /**
     * @return a RecentReports structure recording all recent
     * reports to this estimator.
     */
    abstract public RecentReports recentReports();
}
