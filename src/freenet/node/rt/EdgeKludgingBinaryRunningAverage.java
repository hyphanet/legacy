package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.Logger;


/**
 * SimpleBinaryRunningAverage plus kludge to keep probabilities within
 * ]0.0,1.0[, thus ensuring that:
 * * If one EKBRA has 500x 0.0's and no 1.0's, it's value will be higher
 *   than an EKBRA with 10x 0.0's and no 1.0's.
 * * ANY EKBRA with both 1.0's and 0.0's will have a lower value than
 *   an EKBRA with only 1.0's.
 * 
 * Thus, we postprocess the currentValue:
 * If it equals 1.0:
 * Return 1.0 - 1.0 / (totalOnes + maxReports + 1)
 * If it equals 0.0:
 * Return 1.0 / (totalZeros + maxReports + 1)
 * 
 * The above only happens if we have less than max reports. If we have
 * 1000 consecutive 1.0's, then it's pretty certain that it's 1.0!
 */
public final class EdgeKludgingBinaryRunningAverage extends
        SimpleBinaryRunningAverage {

    public Object clone() {
        return new EdgeKludgingBinaryRunningAverage(this);
    }
    
    public static RunningAverageFactory factory(int maxSize, int fsSize) {
        return new EdgeKludgingBinaryRunningAverageFactory(maxSize, fsSize);
    }
    
    public static class EdgeKludgingBinaryRunningAverageFactory implements
            RunningAverageFactory {

        int maxSize;
        int fsSize;
        
        public EdgeKludgingBinaryRunningAverageFactory(int maxSize, int fsSize) {
            this.maxSize = maxSize;
            this.fsSize = fsSize;
        }

        public RunningAverage create(double start) {
            return new EdgeKludgingBinaryRunningAverage(maxSize, start);
        }

        public RunningAverage create(DataInputStream dis) throws IOException {
            return new EdgeKludgingBinaryRunningAverage(maxSize, dis);
        }

        public RunningAverage create(FieldSet set)
                throws EstimatorFormatException {
            return new EdgeKludgingBinaryRunningAverage(maxSize, fsSize, set);
        }
    }
    
    public EdgeKludgingBinaryRunningAverage(int maxSize, double start) {
        super(maxSize, start);
        if(logDEBUG)
            Core.logger.log(this, "Created "+this+"("+maxSize+","+start+")", 
                    new Exception("debug"), Logger.DEBUG);
    }

    public EdgeKludgingBinaryRunningAverage(int maxSize, int fsSize,
            FieldSet set) throws EstimatorFormatException {
        super(maxSize, fsSize, set);
        Core.logger.log(this, "Created "+this+"("+maxSize+","+fsSize+","+
                set+")", new Exception("debug"), Logger.DEBUG);
    }

    public EdgeKludgingBinaryRunningAverage(int maxSize, DataInputStream dis)
            throws IOException {
        super(maxSize, dis);
        Core.logger.log(this, "Created "+this+"("+maxSize+","+dis+")", 
                new Exception("debug"), Logger.DEBUG);
    }

    public EdgeKludgingBinaryRunningAverage(EdgeKludgingBinaryRunningAverage a) {
        super(a);
    }

    public synchronized double kludgeValue(double d) {
        if(d > 1.0) {
            Core.logger.log(this, "Too high probability: "+d+
                    " while kludging "+this + checkOnesZeros(), new Exception("debug"), 
                    Logger.ERROR);
            d = 1.0;
        }
        if(d < 0.0) {
            Core.logger.log(this, "Too low probability: "+d+
                    " while kludging "+this + checkOnesZeros(), new Exception("debug"), 
                    Logger.ERROR);
        }
        if(totalReported > super.maximumSize) return d;
        if(d == 0.0)
            return 1.0 / (maximumSize + totalZeros + 1);
        if(d == 1.0)
            return 1.0 - 1.0 / (maximumSize + totalOnes + 1);
        return d;
    }
    
    public synchronized double currentValue() {
        return kludgeValue(super.currentValue());
    }
    
    public synchronized double valueIfReported(double d) {
        return kludgeValue(super.valueIfReported(d));
    }
    
    public synchronized double valueIfReported(boolean d) {
        return kludgeValue(super.valueIfReported(d));
    }
}
