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
import freenet.node.rt.RunningAverage;

/**
 * RouteEstimator using 3 KeyspaceEstimators, plus an external object to 
 * indicate what the average full-path tSuccess and tFailure are. We add 
 * these to our estimates in order to route on the basis of the whole 
 * path rather than just our bit of it. I theorize that this will improve
 * load distribution and success ratios by:
 * - Giving more importance to psuccess in a non-alchemical way
 * - Preventing all requests from going (first) to fast nodes with dubious 
 *   psuccess
 * - Making it harder to build ubernodes
 */
public class PathCountingStandardRouteEstimator implements RouteEstimator {

    final KeyspaceEstimator epDNF;
    final KeyspaceEstimator etDNF;
    final KeyspaceEstimator etSuccess;
    final SuccessFailureStats sfStats;
    final boolean halfValues;
    final boolean probRA;
    final RunningAverage rapDNF;
    final boolean noTDNF;
    final boolean productOfTSuccessAndPFailure;
    final boolean doNoPathCounting;
    final boolean productTSuccessIncludeBoth;
    
    public PathCountingStandardRouteEstimator(KeyspaceEstimatorFactory kef, int initTimeVal, SuccessFailureStats st, boolean halfValues, boolean probRA, boolean noTDNF, boolean tSuccessTimesPFailure, boolean doNoPathCounting, boolean productTSuccessIncludeBoth) {
        productOfTSuccessAndPFailure = tSuccessTimesPFailure;
        this.doNoPathCounting = doNoPathCounting;
        this.productTSuccessIncludeBoth = productTSuccessIncludeBoth;
        if(probRA) epDNF = null;
        else epDNF = kef.createProbability(0.0, null);
        if(productOfTSuccessAndPFailure) etDNF = null;
        else etDNF = kef.createTime(null, initTimeVal, initTimeVal, null);
        etSuccess = kef.createTime(null, initTimeVal, initTimeVal, null);
        sfStats = st;
        this.halfValues = halfValues;
        this.probRA = probRA;
        rapDNF = Main.rafb.create(1.0);
        this.noTDNF = noTDNF;
    }

    public double estimate(Key k) {
        double pDNF;
        if(probRA)
            pDNF = rapDNF.currentValue();
        else
            pDNF = epDNF.guessProbability(k);
        double tDNF = 0;
        if(!productOfTSuccessAndPFailure) {
            if(!noTDNF)
                tDNF = etDNF.guessTime(k);
            if(halfValues) tDNF += (sfStats.fullRequestTDNF()/2);
            else if(!doNoPathCounting) tDNF += sfStats.fullRequestTDNF();
        }
        double tSuccess = etSuccess.guessTime(k);
        if(halfValues) tSuccess += (sfStats.fullRequestTSuccess()/2);
        else if(!doNoPathCounting) tSuccess += sfStats.fullRequestTSuccess();
        /**
         * Algorithm:
         * pSuccess * tSuccess + tDNF / pDNF
         */
        if(productOfTSuccessAndPFailure) {
            if(productTSuccessIncludeBoth)
                return (tSuccess + sfStats.fullRequestTDNF() + sfStats.fullRequestTSuccess()) * pDNF;
            return tSuccess * pDNF;
        } else {
            double d = ((1.0-pDNF) * tSuccess) + tDNF / (1.0-pDNF);
            //System.out.println("pDNF: "+pDNF+", tDNF: "+tDNF+", tSuccess: "+tSuccess+" -> "+d);
            return d;
        }
    }

    public void succeeded(Key k, long time) {
        if(!probRA)
            epDNF.reportProbability(k, 0.0);
        etSuccess.reportTime(k, time);
        rapDNF.report(0.0);
    }

    public void failed(Key k, long time) {
        if(!probRA)
            epDNF.reportProbability(k, 1.0);
        if(!productOfTSuccessAndPFailure)
            etDNF.reportTime(k, time);
        rapDNF.report(1.0);
    }

    public void dump(PrintWriter pw, String filenameBase) {
        pw.println(getClass().getName());
        BucketDistribution bd = new BucketDistribution();
        if(epDNF != null) {
            pw.println("epDNF:");
            epDNF.getBucketDistribution(bd);
            pw.println(bd.toString());
            dump_graphable(bd, pw, filenameBase+"-epDNF-"+epDNF.countReports()+"hits");
        }
        if(etDNF != null) {
            pw.println("etDNF:");
            etDNF.getBucketDistribution(bd);
            pw.println(bd.toString());
            dump_graphable(bd, pw, filenameBase+"-etDNF-"+etDNF.countReports()+"hits");
        }
        if(etSuccess != null) {
            pw.println("etSuccess:");
            etSuccess.getBucketDistribution(bd);
            pw.println(bd.toString());
            dump_graphable(bd, pw, filenameBase+"-etSuccess"+etSuccess.countReports()+"hits");
        }
        if(probRA) {
            pw.println("rapDNF:");
            pw.println(rapDNF.toString());
        }
    }

    /**
     * @param bd
     * @param pw
     * @param filenameBase
     */
    private void dump_graphable(BucketDistribution bd, PrintWriter pw, String filenameBase) {
        try {
            File f = new File(filenameBase);
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

    public long hits() {
        return epDNF.countReports();
    }
}
