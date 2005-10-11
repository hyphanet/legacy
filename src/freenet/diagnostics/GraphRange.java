package freenet.diagnostics;

import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

import freenet.support.graph.Rectangle;

public class GraphRange {
	private final float high;
	private final float low;

	private final long first;
	private final long last;

	private final int type;

	private final Rectangle r;

	/* reconstitute a GraphRange from a string. */
	public GraphRange(String s) throws IllegalArgumentException {
		if (s == null)
			throw new IllegalArgumentException();

		try {
			StringTokenizer st = new StringTokenizer(s, "_", false);

			r = new Rectangle(st.nextToken());

			high = Float.intBitsToFloat(Integer.parseInt(st.nextToken()));
			low = Float.intBitsToFloat(Integer.parseInt(st.nextToken()));
			first = Long.parseLong(st.nextToken());
			last = Long.parseLong(st.nextToken());
			type = Integer.parseInt(st.nextToken());
		} catch (NoSuchElementException e) {
			throw new IllegalArgumentException();
		}
	}

	/*
	 * convert a GraphRange to a url-safe string representation
	 */
	public String toString() {
		return r.toString()
			+ "_"
			+ Float.floatToIntBits(high)
			+ "_"
			+ Float.floatToIntBits(low)
			+ "_"
			+ first
			+ "_"
			+ last
			+ "_"
			+ type;
	}

	/**
	 * @param e
	 *            the Enumeration from an EventDequeue.elements() call. May be
	 *            null.
	 */
	public GraphRange(Enumeration e, int type) {
		float high = Float.NEGATIVE_INFINITY;
		float low = Float.POSITIVE_INFINITY;
		long first = Long.MAX_VALUE;
		long last = Long.MIN_VALUE;

		int count = 0;

		while (e != null && e.hasMoreElements()) {
			VarEvent ev = (VarEvent) e.nextElement();

			// consider doing this when ev.getValue(type) fails instead
			if (type == 0) {
				// figure out a default type
				if (ev instanceof ContinuousVarEvent)
					type = Diagnostics.MEAN_VALUE;
				else if (ev instanceof BinomialVarEvent)
					type = Diagnostics.SUCCESS_PROBABILITY;
				else if (ev instanceof CountingEvent)
					type = Diagnostics.NUMBER_OF_EVENTS;
				// is change a more useful default?
				else
					type = Diagnostics.NUMBER_OF_EVENTS;
				// should work for any type
			}

			float f = (float) ev.getValue(type);

			if (f == f) {
				++count;

				if (f > high)
					high = f;

				if (f < low)
					low = f;
			}

			long l = ev.time();

			if (l < first)
				first = l;

			if (l > last)
				last = l;
		}

		// no reasonable values, set an arbitrary range for the blank graph
		if (Float.isInfinite(high) || Float.isInfinite(low)) {
			low = 0;
			high = 1;
		}

		if (first == Long.MAX_VALUE || last == Long.MIN_VALUE) {
			first = 0;
			last = 1;
		}

		// make the graph go down to 0, even if values don't
		if (low > 0)
			low = 0;

		// if the graph contains only one value, force it into having range 1
		if (low == high) {
			high += 0.5;
			low -= 0.5;
		}

		if (first == last) {
			--first;
			++last;
		}

		r =
			new Rectangle(
				(float) ((last - first) * -0.05),
				(float) (high + (high - low) * 0.05),
				(float) ((last - first) * 1.05),
				(float) (low - (high - low) * 0.05));

		this.first = first;
		this.last = last;
		this.high = high;
		this.low = low;

		this.type = type;
	}

	/**
	 * The upper limit of graph values
	 */
	public float getHigh() {
		return high;
	}

	/**
	 * The lower limit of graph values
	 */
	public float getLow() {
		return low;
	}

	public long getFirst() {
		return first;
	}
	public long getLast() {
		return last;
	}

	public Rectangle getCoords() {
		return r;
	}

	public int getType() {
		return type;
	}
}