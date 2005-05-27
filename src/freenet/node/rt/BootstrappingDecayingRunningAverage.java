package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.Logger;


/**
 * @author amphibian
 *
 * For the first N reports, this is equivalent to a simple running
 * average. After that it is a decaying running average with a
 * decayfactor of 1/N. We accomplish this by having decayFactor =
 * 1/(Math.min(#reports + 1, N)). We can therefore:
 * a) Specify N more easily than an arbitrary decay factor.
 * b) We don't get big problems with influence of the initial value,
 * which is usually not very reliable.
 */
public class BootstrappingDecayingRunningAverage implements
        RunningAverage {

    public Object clone() {
        return new BootstrappingDecayingRunningAverage(this);
    }
    
    final double min;
    final double max;
    double currentValue;
    long reports;
    final int maxReports;
    // FIXME: debugging!
    long zeros;
    long ones;
    
    public String toString() {
        return super.toString() + ": min="+min+", max="+max+", currentValue="+
        	currentValue+", reports="+reports+", maxReports="+maxReports
        	// FIXME
        	+", zeros: "+zeros+", ones: "+ones
        	;
    }
    
    public BootstrappingDecayingRunningAverage(double defaultValue, double min,
            double max, int maxReports) {
        this.min = min;
        this.max = max;
        reports = 0;
        currentValue = defaultValue;
        this.maxReports = maxReports;
    }
    
    public synchronized double currentValue() {
        return currentValue;
    }

    public synchronized void report(double d) {
        if(d < min) {
            Core.logger.log(this, "Too low: "+d, new Exception("debug"),
                    Logger.ERROR);
            d = min;
        }
        if(d > max) {
            Core.logger.log(this, "Too high: "+d, new Exception("debug"),
                    Logger.ERROR);
            d = max;
        }
        reports++;
        double decayFactor = 1.0 / (Math.min(reports, maxReports));
        currentValue = (d * decayFactor) + 
        	(currentValue * (1-decayFactor));
        if(d < 0.1 && d >= 0.0) zeros++;
        if(d > 0.9 && d <= 1.0) ones++;
    }

    public void report(long d) {
        report((double)d);
    }

    public FieldSet toFieldSet() {
        FieldSet fs = new FieldSet();
        fs.put("Implementation", "BootstrappingDecayingRunningAverage");
        fs.put("Version", "1");
        fs.put("Reports", Long.toHexString(reports));
        fs.put("MaxReports", Integer.toHexString(maxReports));
        fs.put("Value", Double.toString(currentValue));
        return fs;
    }

    public BootstrappingDecayingRunningAverage(FieldSet fs, double min,
            double max, int maxReports) throws EstimatorFormatException {
        this.min = min;
        this.max = max;
        if(fs == null) throw new EstimatorFormatException("Null fieldset");
        String impl = fs.getString("Implementation");
        if(impl == null || !impl.equals("BootstrappingDecayingRunningAverage"))
            throw new EstimatorFormatException("Invalid impl: "+impl, false);
        String ver = fs.getString("Version");
        if(ver == null || !ver.equals("1"))
            throw new EstimatorFormatException("Invalid ver: "+ver, false);
        String reportsString = fs.getString("Reports");
        if(reportsString == null)
            throw new EstimatorFormatException("No reports count");
        try {
            reports = Long.parseLong(reportsString, 16);
        } catch (NumberFormatException e) {
            throw new EstimatorFormatException("Invalid reports: "+e);
        }
        if(reports < 0)
            throw new EstimatorFormatException("Negative reports");
        String maxReportsString = fs.getString("MaxReports");
        this.maxReports = maxReports;
        if(!maxReportsString.equals(Integer.toHexString(maxReports)))
            Core.logger.log(this, "Different maxReports: should be "+maxReports+
                    " but is "+maxReportsString, Logger.NORMAL);
        String value = fs.getString("Value");
        if(value == null)
            throw new EstimatorFormatException("No value");
        try {
            currentValue = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new EstimatorFormatException("Invalid value: "+e);
        }
    }
    
    public synchronized double valueIfReported(double d) {
        if(d < min) {
            Core.logger.log(this, "Too low: "+d, new Exception("debug"),
                    Logger.ERROR);
            d = min;
        }
        if(d > max) {
            Core.logger.log(this, "Too high: "+d, new Exception("debug"),
                    Logger.ERROR);
            d = max;
        }
        double decayFactor = 1.0 / (Math.min(reports + 1, maxReports));
        return (d * decayFactor) + 
    		(currentValue * (1-decayFactor));
    }

    int SERIAL_MAGIC = 0xdd60ee7f;
    
    public void writeDataTo(DataOutputStream out) throws IOException {
        out.writeInt(SERIAL_MAGIC);
        out.writeInt(1);
        out.writeInt(maxReports);
        out.writeLong(reports);
        out.writeDouble(currentValue);
    }

    BootstrappingDecayingRunningAverage(DataInputStream dis, double min,
            double max, int maxReports) throws IOException {
        this.max = max;
        this.min = min;
        int magic = dis.readInt();
        if(magic != SERIAL_MAGIC)
            throw new IOException("Invalid magic");
        int ver = dis.readInt();
        if(ver != 1)
            throw new IOException("Invalid version "+ver);
        int mrep = dis.readInt();
        this.maxReports = maxReports;
        if(maxReports != mrep)
            Core.logger.log(this, "Changed maxReports: now "+maxReports+
                    ", was "+mrep, Logger.NORMAL);
        reports = dis.readLong();
        if(reports < 0)
            throw new IOException("Negative reports");
        currentValue = dis.readDouble();
        if(currentValue < min || currentValue > max)
            throw new IOException("Value out of range: "+currentValue);
    }
    
    public BootstrappingDecayingRunningAverage(BootstrappingDecayingRunningAverage a) {
        this.currentValue = a.currentValue;
        this.max = a.max;
        this.maxReports = a.maxReports;
        this.min = a.min;
        this.reports = a.reports;
    }

    public int getDataLength() {
        return 4 + 4 + 4 + 8 + 8;
    }

    public long countReports() {
        return reports;
    }
}
