package freenet.node.rt;

public final class DiscardValue implements Comparable {
    final long time;
    DiscardValue(long time) {
	this.time = time;
    }
    public final int compareTo(Object o) {
	return compareTo((DiscardValue) o);
    }
    public final int compareTo(DiscardValue o) {
	return time == o.time ? 0 : (time < o.time ? 1 : -1);
    }
}
