package freenet.diagnostics;
import java.io.DataOutputStream;
import java.io.IOException;
/**
 * Implementation of a the binomial random var.
 *
 * @author oskar
 */

class BinomialVarEvent extends VarEvent {

    public final long x, n;
    
    public BinomialVarEvent(long time, long n, long x) {
        super(time);
        this.x = x;
        this.n = n;
    }
    
    public String toString() {
        return "Binomial occurrence, n = " + n + ", x = " + x;
    }

    public String[] fields() {
        return new String[] { 
            Long.toString(n) , Long.toString(x), 
            (new Double(((double) x) / n)).toString()
        };
    }

    public void write(DataOutputStream out) throws IOException {
        super.write(Binomial.VERSION, out);
        out.writeLong(n);
        out.writeLong(x);
    }

    public double getValue(int type) {
        if (type == Diagnostics.SUCCESS_PROBABILITY)
            return ((double) x) / n;
        else if (type == Diagnostics.NUMBER_OF_EVENTS)
            return n;
        else
            throw new IllegalArgumentException("Unsupported value type.");
    }
    
}

