package freenet.support;

import freenet.Core;
import freenet.node.rt.ValueConsumer;
import freenet.support.DoublyLinkedList.Item;

/**
 * Simple intervalled sum: sum of the last N millisconds of reports.
 * @author Iakin
 */
public class IntervalledSum implements ValueConsumer {
    private final DoublyLinkedList l;
	private volatile double total=0;
    private final long maxAgeMilliseconds;
    protected boolean logDEBUG;

    public IntervalledSum(long maxAgeMilliseconds) {
		this.maxAgeMilliseconds = maxAgeMilliseconds;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		l = new DoublyLinkedListImpl();
    }
    
    public IntervalledSum(IntervalledSum a) {
        this.l = (DoublyLinkedList) a.l.clone();
        this.maxAgeMilliseconds = a.maxAgeMilliseconds;
        this.total = a.total;
    }

    public double currentSum() {
		purge();
        return total;
    }
    
    public long currentReportCount(){
    	return l.size();
    }
    
    private synchronized void purge(){
    	long lt = System.currentTimeMillis();
    	double chopped = 0.0;
    	int count = 0;
    	while(l.size()>0){
    		Report r = (Report)l.head();
    		long itemAge = lt -r.timestamp;
    		if(itemAge > maxAgeMilliseconds){
    			l.shift();
    			total -= r.value;
    			count++;
    			chopped += r.value;
    		}else
    			break; //Assume the list is ordered by time of reports
    	}
    	// Will deadlock if we log toString here
    	if(count > 0 && logDEBUG)
    		Core.logger.log(this, "l.size() now: "+l.size()+", chopped "+
    				chopped+" in "+count, Logger.DEBUG);
    }

    public synchronized void report(double d) {
        l.push(new Report(d));
		total += d;
		purge();
    }

    public String toString() {
        return super.toString() + ": total="+
        	total+", size="+l.size();
    }
    
    public void report(long d) {
        report((double)d);
    }
    
    private static class Report implements DoublyLinkedList.Item {
		double value;
		long timestamp;
		private Item prev;
		private Item next;
		Report(double value) {
			this.value = value;
			timestamp = System.currentTimeMillis();
		}
		public Item getNext() {
			return next;
		}
		public Item setNext(Item i) {
			Item r = next;
			next = i;
			return r;
		}

		public Item getPrev() {
			return prev;
		}
		public Item setPrev(Item i) {
			Item r = prev;
			prev = i;
            return r;
		}
	}
}
