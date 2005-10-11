package freenet.node;

import java.io.PrintWriter;
import java.util.Date;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.Random;

import freenet.Core;
import freenet.Key;
import freenet.support.Checkpointed;
import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.DoublyLinkedListImpl.Item;

/**
 * Keeps track of keys that have been failed recently, and automatically fails
 * requests for that key if the hops to live is lesser or equal.
 */

public class FailureTable implements Checkpointed {

    // Todo: make this a test.

    public static void main(String[] args) {
        // Determine memory usage for FT of a given size
        final int KEYS = 20000;
        FailureTable ft = new FailureTable(KEYS, 5 * KEYS, 1800000);
        Random r = new Random();
        Runtime rt = Runtime.getRuntime();
        rt.gc();
        rt.runFinalization();
        rt.gc();
        rt.runFinalization();
        long curUsed = rt.totalMemory() - rt.freeMemory();
        System.out.println("Using " + curUsed);
        for (int i = 0; i < KEYS; i++) {
            byte[] keyval = new byte[20];
            r.nextBytes(keyval);
            Key k = new Key(keyval);
            ft.failedToFind(k, 10);
        }
        rt.gc();
        rt.runFinalization();
        rt.gc();
        rt.runFinalization();
        long endUsed = rt.totalMemory() - rt.freeMemory();
        long diff = endUsed - curUsed;
        System.out.println("Now used: " + endUsed);
        System.out.println("FT of " + KEYS + " keys used: " + diff + " bytes");
        System.out.println("Per key: " + diff / KEYS);
        //          Key[] keys = new Key[16];
        //          for (int i = 0 ; i < 16 ; i++) {
        //              long time = System.currentTimeMillis() - r.nextInt(3600000);
        //              byte[] keyval = new byte[10];
        //              for (int j = 0 ; j < 10 ; j++) {
        //                  keyval[j] = (byte) i;
        //              }
        //              keys[i] = new Key(keyval);
        //              ft.failedToFind(keys[i], 10, time);
        //          }
        //        
        //          for (Enumeration e = ft.queue.elements() ; e.hasMoreElements() ;) {
        //              System.err.println(e.nextElement());
        //          }
        //
        //          System.err.println(ft.shouldFail(keys[0], 1));
        //          ft.checkpoint();
        //
        //          System.err.println("---");
        //
        //          while (ft.queue.size() > 0) {
        //              System.err.println(ft.queue.pop());
        //          }
    }

    protected int totalBlocks;

    protected int totalIgnores;

    protected int maxSize;

    protected int maxItemsSize;

    protected long maxMillis;

    protected long cpMillis;

    protected long lastCp;

    protected Hashtable failedKeys; // of FailureEntry's

    // In both cases, most recent is First(), LRU is Last()
    // Push()/Pop(): MRU
    // Unshift()/Shift(): LRU
    DoublyLinkedList entries; // of FailureEntry's

    DoublyLinkedList items; // of FailItem's

    /**
     * Creates a new.
     * 
     * @param size
     *            The number of entries to hold.
     * @param millis
     *            The max amount of time to keep an entry.
     */
    public FailureTable(int size, int itemSize, long millis) {
        maxSize = size;
        maxMillis = millis;
        cpMillis = maxMillis / 10;
        maxItemsSize = itemSize;
        failedKeys = new Hashtable();
        entries = new DoublyLinkedListImpl();
        items = new DoublyLinkedListImpl();
        lastCp = System.currentTimeMillis();
    }

    /**
     * Add a failure.
     * 
     * @param k
     *            The key that could not retrieved.
     * @param hopsToLive
     *            The hopsToLive when it could not be found.
     */
    public synchronized void failedToFind(Key k, int hopsToLive) {
        long time = System.currentTimeMillis();
        // Not an argument because it must be NOW - we don't want to mess with
        // ADTs

        FailureEntry fe = (FailureEntry) failedKeys.get(k);

        if (fe == null) {
            fe = new FailureEntry(k, time);
        }
        fe.failed(hopsToLive, time);
    }

    public synchronized long shouldFail(Key k, int hopsToLive) {
        return shouldFail(k, hopsToLive, false);
    }

    /**
     * Checks whether a query should fail.
     * 
     * @return The time at which the query that failed to find the key occured,
     *         or < 0 if no such query is known.
     */
    public synchronized long shouldFail(Key k, int hopsToLive, boolean noStats) {
        FailureEntry fe = (FailureEntry) failedKeys.get(k);
        long time = System.currentTimeMillis();

        if (fe == null) return -1;

        return fe.shouldFail(hopsToLive, time, noStats);
    }

    public synchronized void ignoredDNF(Key k) {
        FailureEntry fe = (FailureEntry) failedKeys.get(k);
        if (fe != null) fe.ignoredDNF();
        Core.diagnostics.occurrenceCounting("failureTableIgnoredDNFs", 1);
    }

    public synchronized boolean statsShouldIgnoreDNF(Key k, int hopsToLive) {
        FailureEntry fe = (FailureEntry) failedKeys.get(k);
        if (fe == null) return false;
        return fe.shouldIgnoreDNF(hopsToLive);
    }

    /**
     * @return true, if the Key is contained in the failuretable
     */
    public synchronized boolean contains(Key k, int hopsToLive) {
        return shouldFail(k, hopsToLive, true) > 0;
    }

    /**
     * Removes the entry for this key.
     */
    public synchronized void remove(Key k) {
        FailureEntry fe = (FailureEntry) failedKeys.get(k);
        if (fe != null) {
            fe.remove();
            Core.diagnostics.occurrenceContinuous("failureTableBlocks", fe.blocks);
        }
    }

    /**
     * Purges the queue.
     */
    public synchronized void checkpoint() {
        lastCp = System.currentTimeMillis();
        FailureEntry fe;
        while (entries.size() > maxSize) {
            fe = (FailureEntry) entries.shift();
            Core.diagnostics.occurrenceContinuous("failureTableBlocks", fe.blocks);
            fe.remove();
        }
        while (items.size() > maxItemsSize) {
            FailItem fi = (FailItem) items.shift();
            fi.getEntry().removeItem(fi);
        }
    }

    public String getCheckpointName() {
        return "Purge table of recently failed keys.";
    }

    public long nextCheckpoint() {
        return lastCp + cpMillis;
    }

    public synchronized void writeHtml(PrintWriter pw) {
        pw.println("<b>Maximum Keys:</b> " + maxSize + "<br />");
        pw.println("<b>Current Keys:</b> " + failedKeys.size() + "<br />");
        pw.println("<b>Maximum Key-HTL pairs:</b> " + maxItemsSize + "<br />");
        pw.println("<b>Current Key-HTL pairs:</b> " + items.size() + "<br />");
        pw.println("<b>Seconds Entries Prevent Routing:</b> " + maxMillis / 1000 + "<br />");
        pw.println("<b>Seconds Between Purges:</b> " + cpMillis / 1000 + "<br />");
        pw.println("<b>Number of Requests Blocked:</b> " + totalBlocks + "<br />");
        pw.println("<b>Number of DNFs Ignored:</b> " + totalIgnores + "<br />");
        pw.println("<b><small class=\"warning\">Blocking Requests</small></b><br />");
        pw.println("<b><small class=\"okay\" >Ignoring DNFs for Routing</small></b><br />");
        FailureEntry fe;
        long time = System.currentTimeMillis();
        int counter = 0;
        for (Enumeration e = entries.elements(); e.hasMoreElements(); counter++) {
            if (counter % 100 == 0) {
                pw.print(counter != 0 ? "</table>" : "");
                pw.println("<table border=\"1\">");
                pw.println("<tr><th>Key</th><th>Blocked HTLs</th><th>Age</th>"+
                        "<th># of Blocks</th><th># DNFs Ignored</th><th>Last Hit</th></tr>");
            }
            fe = (FailureEntry) e.nextElement();
            fe.toHtml(pw, time);
        }
        if (counter > 0) pw.println("</table>");
    }

    protected class FailItem extends Item {

        private FailureEntry fe;

        private int hopsToLive;

        private long time;

        public FailItem(FailureEntry fe, int hopsToLive) {
            this.fe = fe;
            this.hopsToLive = hopsToLive;
        }

        public FailureEntry getEntry() {
            return fe;
        }

        public long shouldFail(int hopsToLive, long time) {
            if (this.time + maxMillis > time && this.hopsToLive >= hopsToLive) { return time; }
            return -1;
        }

        public boolean failed(int hopsToLive, long time) {
            if (this.hopsToLive == hopsToLive) {
                this.time = time;
                items.remove(this);
                items.push(this); // MRU
                return true;
            }
            return false;
        }

        public boolean expired(long now) {
            return (time + maxMillis < now);
        }

        public void remove() {
            items.remove(this);
        }

        /**
         * @param pw =
         *            PrintWriter to put HTML on
         * @param time =
         *            current time, used to determine if it is in primary or
         *            secondary failure table
         */
        public void toHtml(PrintWriter pw, long time) {
            pw.println("<td>" + hopsToLive + "</td><td>" + (time - this.time) / 1000 + "</td>");
        }
    }

    protected class FailureEntry extends Item {

        private Key key;

        private int blocks;

        private long lastHit;

        private long lastFail;

        private int failures;

        private int ignores;

        private int highestHtl;

        LinkedList myItems;

        // java.util.LinkedList, NOT DoublyLinkedList, because we are already
        // in items DLList

        public FailureEntry(Key key, long time) {
            myItems = new LinkedList();
            this.key = key;
            this.blocks = 0;
            lastHit = time;
            highestHtl = 0;
            ignores = 0;
            failures = 0;
            failedKeys.put(key, this);
        }

        /**
         *  
         */
        public void remove() {
            for (Iterator i = myItems.iterator(); i.hasNext();) {
                ((FailItem) (i.next())).remove();
            }
            myItems = null;
            entries.remove(this);
            failedKeys.remove(key);
        }

        /**
         * removes an item from the internal list does NOT remove it from the
         * master list
         * 
         * @param fi
         *            the item to remove
         */
        public void removeItem(FailItem fi) {
            myItems.remove(fi);
            //FailureEntries need not have any FailureItems if they are in
            // 'pass-through' mode
            //			if ( myItems.size() == 0 ) {
            //				remove();
            //			}
        }

        /**
         * Add a FailItem, or update an existing one
         * 
         * @param hopsToLive
         *            the HTL of the request when it failed
         * @param time
         *            the time when it failed (must not be lower than the
         *            previous time - intended to be the time when the
         *            enclosing synchronized method was entered).
         */
        public void failed(int hopsToLive, long time) {
            failures++;
            if (highestHtl < hopsToLive) highestHtl = hopsToLive;
            lastFail = time;
            boolean found = false;
            for (Iterator i = myItems.iterator(); i.hasNext();) {
                FailItem item = (FailItem) i.next(); 
                found = item.failed(hopsToLive, time);
                if (found) break;
            }
            if (!found) {
                FailItem fi = new FailItem(this, hopsToLive);
                fi.failed(hopsToLive, time);
                myItems.addLast(fi);
            }
            entries.remove(this);
            entries.push(this);
        }

        /**
         * Checks whether a query should fail.
         * 
         * @return The time at which the query that failed to find the key
         *         occured, or < 0 if no such query is known.
         */
        public long shouldFail(int hopsToLive, long time, boolean noStats) {
            long failedTime = -1;
            for (Iterator i = myItems.iterator(); i.hasNext();) {
                FailItem fi = (FailItem) i.next();
                if (fi.expired(time)) {
                    fi.remove();
                    i.remove();
                    continue;
                }
                if ((failedTime = fi.shouldFail(hopsToLive, time)) >= 0) {
                    if (!noStats) {
                        blocks++;
                        totalBlocks++;
                        Core.diagnostics.occurrenceContinuous("timeBetweenFailedRequests", (time - lastHit));
                        lastHit = time;
                    }
                    break;
                }
            }
            return failedTime;
        }

        public void ignoredDNF() {
            ignores++;
            totalIgnores++;
        }

        public boolean shouldIgnoreDNF(int hopsToLive) {
            return (hopsToLive <= highestHtl && (failures + blocks) > 1);
        }

        public int compareTo(Object o) {
            //FailureEntry fe = (FailureEntry) o;
            //return (int) (fe.time - time);
            // older is "bigger"
            long ot = ((FailureEntry) o).lastFail;
            return lastFail == ot ? 0 : (lastFail > ot ? -1 : 1);
        }

        /**
         * @param pw
         */
        public void toHtml(PrintWriter pw, long time) {
            boolean active = (time - lastFail) < maxMillis;
            int span = Math.max(myItems.size(), 1);
            pw.println("<tr><td rowspan=" + span + "><font size=-2 color=\"" + (active ? "red" : "green") + "\">" + key + "</font></td>");
            Iterator i = myItems.iterator();
            if (i.hasNext()) {
                ((FailItem) (i.next())).toHtml(pw, time);
            } else {
                pw.println("<td>&nbsp;</td><td>&nbsp;</td>");
            }
            pw.println("<td rowspan=" + span + ">" + blocks + "</td><td rowspan=" + span + ">" + ignores + "</td><td rowspan=" + span + ">"
                    + new Date(lastHit) + "</td></tr>");
            for (; i.hasNext();) {
                pw.print("<tr>");
                ((FailItem) (i.next())).toHtml(pw, time);
                pw.print("</tr>\n");
            }
        }

        public String toString() {
            return key.toString();
        }
    }

}
