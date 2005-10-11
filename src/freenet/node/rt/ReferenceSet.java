package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Enumeration;

import freenet.Core;
import freenet.Identity;
import freenet.Key;
import freenet.KeyException;
import freenet.support.DataObject;
import freenet.support.DataObjectPending;
import freenet.support.DataObjectUnloadedException;
import freenet.support.Logger;

/**
 * Contains the set of key references associated with a particular node.
 */
final class ReferenceSet implements DataObject, Comparable {

    /**
     * The only way to access a ReferenceSet is as a property of the
     * RoutingMemory for a node.
     */
    static ReferenceSet getProperty(RoutingMemory mem, String name) {
        ReferenceSet ret;
        try {
            ret = (ReferenceSet) mem.getProperty(name);
        } catch (DataObjectUnloadedException e) {
            ret = new ReferenceSet(mem.getIdentity(), e);
        }
        if (ret == null) {
            ret = new ReferenceSet(mem.getIdentity());
            mem.setProperty(name, ret);
        }
        return ret;
    }

    private final Identity ident;

    private ReferenceTuple rtHead = new ReferenceTuple(null);

    private ReferenceTuple rtTail = rtHead;

    private int refCount = 0;

    private int dataLength = 0;

    private ReferenceSet(Identity ident) {
        this.ident = ident;
    }

    private ReferenceSet(Identity ident, DataObjectPending dop) {
        this(ident);
        if (dop.getDataLength() > 0) {
            try {
                DataInputStream din = dop.getDataInputStream();
                int len;
                while ((len = din.readUnsignedShort()) > 0) {
                    byte[] key = new byte[len];
                    din.readFully(key);
                    long timestamp = din.readLong();
                    try {
                        append(new Reference(Key.readKey(key), ident, timestamp));
                    } catch (KeyException e) {
                        Core.logger.log(this, "Read invalid key, ignoring: "
                                + key + ": " + e, e, Logger.NORMAL);
                    }
                }
            } catch (IOException e) {
                Core.logger.log(this, "Reading ReferenceSet was truncated: "+e,
                        e, Logger.NORMAL);
            }
        }
        dop.resolve(this);
    }

    /**
     * Walks the list looking for a match and removes it.
     * 
     * @return true, if a ref was removed
     */
    boolean remove(Reference ref) {
        ReferenceTuple rt = rtHead;
        while (rt.next != null) {
            if (ref.key.equals(rt.next.ref.key)) {
                --refCount;
                dataLength -= 10 + ref.key.length();
                if (rtTail == rt.next) rtTail = rt;
                rt.next = rt.next.next;
                return true;
            }
            rt = rt.next;
        }
        return false;
    }

    /**
     * Adds a Reference at the end of the chronological list.
     */
    void append(Reference ref) {
        ReferenceTuple rt = new ReferenceTuple(ref);
        rtTail.next = rt;
        rtTail = rt;
        ++refCount;
        dataLength += 10 + ref.key.length();
    }

    /**
     * @return the Identity this reference set is associated with
     */
    final Identity identity() {
        return ident;
    }

    /**
     * @return the number of references stored in the set
     */
    final int size() {
        return refCount;
    }

    /**
     * Removes the oldest Reference and returns it.
     * 
     * @return the Reference removed, or null if the set was empty
     */
    final Reference pop() {
        if (rtHead.next == null) { return null; }
        try {
            return rtHead.next.ref;
        } finally {
            --refCount;
            dataLength -= 10 + rtHead.next.ref.key.length();
            if (rtTail == rtHead.next) rtTail = rtHead;
            rtHead.next = rtHead.next.next;
        }
    }

    /**
     * @return an enumeration of Reference objects in chronological order
     */
    final Enumeration references() {
        return new ReferenceTuple.ReferenceEnumeration(rtHead.next);
    }

    public void writeDataTo(DataOutputStream out) throws IOException {
        Enumeration refs = references();
        while (refs.hasMoreElements()) {
            Reference ref = (Reference) refs.nextElement();
            out.writeShort(ref.key.length());
            out.write(ref.key.getVal());
            out.writeLong(ref.timestamp);
        }
    }

    public final int getDataLength() {
        return dataLength;
    }

    public final int compareTo(Object o) {
        return compareTo((ReferenceSet) o);
    }

    public final int compareTo(ReferenceSet r) {
        // reverse sense, for the heap
        return refCount == r.refCount ? 0 : (refCount < r.refCount ? 1 : -1);
    }
}
