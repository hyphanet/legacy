package freenet.support;

import java.io.Serializable;
import java.text.NumberFormat;

public interface Unit extends Serializable {
    public double toRaw(double value);
    public double ofRaw(double rawValue);
    public boolean withinRange(double value);
    public double getMax();
    public double getMin();
    public String rawToString(double rawValue);

    public class noConvert implements Unit {
        double min, max;
        Stringable stringer;
        
        public noConvert (double min, double max, Stringable stringer) {
            this.min = min; this.max = max; this.stringer = stringer;
        }
        public double toRaw(double value) {
            if (withinRange(value)) return value;
            throw new IllegalArgumentException("Out of range conversion to Raw");
        }
        public double ofRaw(double rawValue) {
            if (withinRange(rawValue)) return rawValue;
            throw new IllegalArgumentException("Out of Range converion from Raw");
        }
        public final boolean withinRange(double value) {
            return value >= min && value <= max;
        }
        /**
         * @return Returns the max.
         */
        public final double getMax() {
            return max;
        }
        /**
         * @return Returns the min.
         */
        public final double getMin() {
            return min;
        }
        public String toString(double value) {
            return stringer.toString(value);
        }
        
        public String rawToString(double rawValue) {
            return toString(ofRaw(rawValue));
        }
    }
    
    public class noConvertClip extends noConvert {
        double clipMin, clipMax;
        public noConvertClip(double min, double max, double clipMin, double clipMax, Stringable str) {
            super(min, max, str);
            this.clipMin = clipMin;
            this.clipMax = clipMax;
        }
        public double toRaw(double value) {
            if (withinRange(value)) return Math.max(clipMin, (Math.min(clipMax, value)));
            throw new IllegalArgumentException("Out of range conversion to Raw");
        }
        public double ofRaw(double rawValue) {
            if (withinRange(rawValue)) return Math.max(clipMin, (Math.min(clipMax, rawValue)));
            throw new IllegalArgumentException("Out of Range converion from Raw");
        }       
    }

    
    public interface Stringable extends Serializable {
        public String toString(double value);
    }
    
    public class milliRatePrint implements Stringable {
        static NumberFormat nf;
        static { nf = NumberFormat.getInstance(); nf.setMaximumFractionDigits(0); }
        public String toString(double value) {
            return nf.format(value * 1000) + " bytes/second";
        }
    }
    public class probPrint implements Stringable {
        static NumberFormat nf;
        static { nf = NumberFormat.getInstance(); nf.setMaximumFractionDigits(4); }
        public String toString(double value) {
            return nf.format(value);
        }        
    }
    public class milliPrint implements Stringable {
        public String toString(double value) {
            return Double.toString((long)value) + "ms";
        }
    }
}