package freenet.node.simulator.whackysim;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import freenet.Key;
import freenet.node.rt.BucketDistribution;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;

/**
 * RouteEstimator using pSuccess only.
 */
public class SuccessDistanceOnlyRouteEstimator implements RouteEstimator {

    final KeyspaceEstimator tSuccess; // the lower the better!
    
    public SuccessDistanceOnlyRouteEstimator(KeyspaceEstimatorFactory kef) {
        tSuccess = kef.createTime(0, null);
    }

    public double estimate(Key k) {
        return tSuccess.guessTime(k);
    }

    public void succeeded(Key k, long time) {
        tSuccess.reportTime(k, time);
    }

    public void failed(Key k, long time) {
        // Do nothing
    }

    public void dump(PrintWriter pw, String dumpFilename) {
        pw.println(getClass().getName());
        BucketDistribution bd = new BucketDistribution();
        tSuccess.getBucketDistribution(bd);
        pw.println(bd.toString());
        pw.println(getClass().getName());
        dump_graphable(bd, pw, dumpFilename);
    }

    /**
     * @param bd
     * @param pw
     * @param filenameBase
     */
    private void dump_graphable(BucketDistribution bd, PrintWriter pw, String filenameBase) {
        try {
            File f = new File(filenameBase+"-"+tSuccess.countReports()+"hits");
            FileOutputStream fos = new FileOutputStream(f);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            PrintWriter fpw = new PrintWriter(bos);
            bd.dump_graphable(fpw);
            fpw.close();
            try {
                bos.close();
            } catch (IOException e) {}
        } catch (IOException e) {
            System.err.println("Couldn't dump: "+e);
            e.printStackTrace();
        }
        bd.dump_graphable(pw);
    }
}
