package freenet.node.simulator.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import freenet.node.rt.BootstrappingDecayingRunningAverageFactory;
import freenet.node.rt.RunningAverage;
import freenet.node.rt.RunningAverageFactory;

/**
 * Takes a stream from input in the form [number of requests] [probability]
 * Averages the probability over a specified interval, outputs with the original
 * number of requests in the same format. 
 */
public class Reaverage {

    public Reaverage() {
    }

    public static void main(String[] args) {
        InputStreamReader isr = new InputStreamReader(System.in);
        BufferedReader r = new BufferedReader(isr);
        int interval = 100;
        if(args.length > 0) {
            interval = Integer.parseInt(args[0]);
        }
        RunningAverageFactory raf = new BootstrappingDecayingRunningAverageFactory(0.0, 1.0, interval);
        RunningAverage ra = raf.create(0);
        while(true) {
            String s;
            try {
                s = r.readLine();
            } catch (IOException e) {
                return;
            }
            if(s == null) return;
            // Now parse
            int idx = s.indexOf(' ');
            String before = s.substring(0, idx);
            String after = s.substring(idx+1, s.length());
            int reports = Integer.parseInt(before);
            double value = Double.parseDouble(after);
            ra.report(value);
            //System.out.println("Reports: "+reports+" value: "+value+" average: "+ra.currentValue());
            System.out.println(""+reports+" "+ra.currentValue());
        }
    }
}
