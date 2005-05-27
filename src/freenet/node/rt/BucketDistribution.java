package freenet.node.rt;

import java.io.PrintWriter;

import freenet.Key;

public class BucketDistribution {

    long[] buckets;
    double[] vals;
    String[] ras;
    Key[] center;
    public long maxBucketReports;
    public long minBucketReports;
    StringBuffer sb = new StringBuffer(400);

    public String toString() {
        sb.setLength(0);
        sb.append("min: ");
        sb.append(minBucketReports);
        sb.append(", max: ");
        sb.append(maxBucketReports);
        sb.append(" detail");
        for(int i=0;i<buckets.length;i++) {
            sb.append(" ");
            sb.append(buckets[i]);
            sb.append(" (");
            sb.append(vals[i]);
            sb.append(": ");
            sb.append(ras[i]);
            sb.append(") center ");
            sb.append(center[i]);
        }
        return sb.toString();
    }

    public void setAccuracy(int accuracy) {
        if(buckets == null || buckets.length != accuracy) {
            buckets = new long[accuracy];
            vals = new double[accuracy];
            ras = new String[accuracy];
            center = new Key[accuracy];
        }
    }

    public void dump_graphable(PrintWriter pw) {
        for(int i=0;i<vals.length;i++) {
            pw.println(""+center[i].toDouble()+", "+vals[i]);
        }
    }
}
