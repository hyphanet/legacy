package freenet.node.simulator.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

/**
 * @author toad
 * 
 * Read a sequence of counter/value pairs from standard input.
 * Output the same sequence, taking out any restart gaps.
 */
public class CleanRestarts {

    static class MyItem {
        int sequence;
        double value;
        MyItem(int seq, double v) {
            sequence = seq;
            value = v;
        }
    }
    
    public static void main(String[] args) {
        Vector v = new Vector();
        InputStreamReader isr = new InputStreamReader(System.in);
        BufferedReader r = new BufferedReader(isr);
        while(true) {
            String s;
            try {
                s = r.readLine();
            } catch (IOException e) {
                break;
            }
            if(s == null) break;
            // Now parse
            int idx = s.indexOf(' ');
            String before = s.substring(0, idx);
            String after = s.substring(idx+1, s.length());
            int reports = Integer.parseInt(before);
            double value = Double.parseDouble(after);
            MyItem m = new MyItem(reports, value);
            v.add(m);
        }
        LinkedList output = new LinkedList();
        /**
         * Clean up the data
         * Read backwards from the end. The data should be decreasing
         * by exactly 1 on each item. If we see it increase, then
         * ignore that item, and continue until we find the expected
         * sequence number.
         */
        int expected = ((MyItem)(v.get(v.size()-1))).sequence;
        for(int i=v.size()-1;i>=0;i--) {
            MyItem item = (MyItem) (v.get(i));
            int actualSeqNo = item.sequence;
            if(actualSeqNo == expected) {
                output.addFirst(item);
                expected--;
            } else {
                // Not the expected sequence number; ignore
            }
        }
        for(Iterator it = output.iterator();it.hasNext();) {
            MyItem item = (MyItem) it.next();
            System.out.println(item.sequence+" "+item.value);
        }
    }
}
