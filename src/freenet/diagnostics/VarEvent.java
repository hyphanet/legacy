package freenet.diagnostics;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.support.DoublyLinkedList.Item;
/**
 * Superclass of occurrence descriptions for random variables.
 */

abstract class VarEvent implements Item {

    private Item prev;
    private Item next;
    
    protected final long time;
    
    /**
     * @param time The time at which the event occured.
     */
    protected VarEvent(long time) {
        this.time = time;
    }
    
    public long time() {
        return time;
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


    public abstract void write(DataOutputStream out) throws IOException;
    /**
     * Writes the version and time as longs, after which implementation 
     * should add data.
     */
    protected void write(long version, DataOutputStream out) throws IOException {
        out.writeLong(version);
        out.writeLong(time);
    }

    public abstract double getValue(int type);

    public abstract String[] fields();

    public String toString() {
        return "VarEvent, type: " + getClass().getName();
    }

}
