package freenet.diagnostics;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Implementation of a the continuous random var.
 *
 * @author oskar
 */


class ContinuousVarEvent extends VarEvent {

    public final double sum;
    public final double sqsum;
    public final double min, max;
    public final long n;
    
    public ContinuousVarEvent(long time, double sum, double sqsum, 
                              double min, double max, long n) {
        super(time);
        this.sum = sum;
        this.sqsum = sqsum;
        this.min = min;
        this.max = max;
        this.n = n;
    }
    
    public double mean() {
        return sum / n;
    }
    
    public double stdDeviation() {
        // bias corrected
        return Math.sqrt((sqsum/(n - 1.0) - sum*sum/(n*(n - 1.0))));
    }
        
    public String toString() {
        return "Continuous aggregation of " + n + " occurrences, mean = " 
            + mean() + ", stdDev = " + stdDeviation();
    }

    public String[] fields() {
        return new String[] { Double.toString(mean()) , 
                              Double.toString(stdDeviation()) , 
                              Double.toString(min),
                              Double.toString(max),
                              Long.toString(n) };
    }

    public void write(DataOutputStream out) throws IOException {
        super.write(Continuous.VERSION, out);
        out.writeDouble(sum);
        out.writeDouble(sqsum);
        out.writeDouble(min);
        out.writeDouble(max);
        out.writeLong(n);
    }

    public double getValue(int type) {
        switch(type) {
        case Diagnostics.MEAN_VALUE:
            return mean();
        case Diagnostics.STANDARD_DEVIATION:
            return stdDeviation();
        case Diagnostics.MIN_VALUE:
            return min;
        case Diagnostics.MAX_VALUE:
            return max;
        case Diagnostics.NUMBER_OF_EVENTS:
            return n;
        default:
            throw new IllegalArgumentException("Unsupported value type.");
        }
    }
}
