package freenet.support.graph;

import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Iterator;
import java.util.Hashtable;

import freenet.support.Logger;
import freenet.support.graph.GraphDataSet;
import freenet.support.graph.GraphDataSet.GraphPoint;
import freenet.Core;

/**
 * MinGraphDataSet merges GraphDataSets from each of several nodes and
 * composites them into a single graph showing the data with the smallest
 * values, and which nodes that data came from.
 * 
 * @author thelema
 */

public class MinGraphDataSet {

	private int count = 5;

	public MinGraphDataSet() {
	}
	public MinGraphDataSet(int c) {
		count = c;
	}

	private class GraphData {
		int c = 1; //count of how many elements in arrays below.
		double[] ys = new double[count];
		String[] sources = new String[count];
		public GraphData(double y1, String s1) {
			ys[0] = y1;
			sources[0] = s1;
			for (int i = 1; i<count; i++) {
				ys[i] = 0;
				sources[i] = null;
			}
		}
		public void add(double y1, String s1) {
			for (int i = 0; i < c; i++) {
				if (y1 < ys[i]) { //insert y1,s1 into position i
					for (int j = c - 1;
						//start at last element
					j > i;
						//stop before i
					j--) { //move the rest of the values one down
						ys[j] = ys[j - 1];
						sources[j] = sources[j - 1];
					}
					ys[i] = y1;
					sources[i] = s1;
					if (c < count)
						c++;
					return;
				}
			}
		}
	}

	protected TreeMap points = new TreeMap();
	// maps Integer x -> GraphData y's

	public void addPoint(int x, double y) {
		this.addPoint(new Integer(x), y, null);
	}

	public void addPoint(Integer x, double y, String id) {
		GraphData old = (GraphData) points.get(x);
		if (old == null) {
			points.put(x, new GraphData(y, id));
		} else {
			old.add(y, id);
		}
	}

	private GraphData toGPP(GraphPoint gp) {
		return new GraphData(gp.y, null);
	}

	public Object[] getSources() { // @returns String[]
		TreeSet ret = new TreeSet();
		for (Iterator i = points.keySet().iterator(); i.hasNext();) {
			GraphData gpp = (GraphData) (points.get(i.next()));
			for (int j = 0; j < gpp.c; j++) {
				String val = gpp.sources[j];
				Core.logger.log(this,"Adding " + val, Logger.DEBUG);
				if (val != null && val.length()!=0) {
			    	ret.add(val);
			    }
			}
		}
		return ret.toArray();
	}

	public void merge(GraphDataSet gds, String id) {
		for (Iterator i = gds.getPoints(); i.hasNext();) {
			GraphPoint p = (GraphPoint) i.next();
			addPoint(new Integer(p.x), p.y, id);
		}
	}

	public double getMin() { return getMin(0); }
	
	public double getMin(int pos) {
		double ret = Double.MAX_VALUE;
		for (Iterator i = points.keySet().iterator(); i.hasNext();) {
			GraphData gd = (GraphData) (points.get(i.next()));	
			double val = gd.ys[pos];
			if (ret > val && val > 0) ret = val;
		}
		return ret;
	}

	public double getMax() { return getMax(-1); }
	
	public double getMax(int pos) {
		double ret = 0;
		for (Iterator i = points.keySet().iterator(); i.hasNext();) {
			GraphData gd = (GraphData) (points.get(i.next()));
			double val = gd.ys[pos==-1 ? gd.c-1 : pos];
			if (ret < val) ret = val;
		}
		return ret;
	}

	public GraphDataSet[] flatten() {
		GraphDataSet[] ret = new GraphDataSet[count];
		for (Iterator i = points.keySet().iterator(); i.hasNext();) {
			Integer x = (Integer) i.next();
			GraphData gd = (GraphData) (points.get(x));
			for (int j = 0; j < gd.c; j++) {
				if (ret[j] == null)
					ret[j] = new GraphDataSet();
				ret[j].addPoint(x.intValue(), gd.ys[j], gd.sources[j]);
			}
		}
		return ret;
	}

	public void drawGraphOnImage(Bitmap bmp, Hashtable sourceColor) {
		bmp.scaleView(
			new Rectangle(
				0,
				(float) getMax(),
				(bmp.getWidth()),
				(float) (getMin() - 1)));
		drawGraphOnImageNoScaling(bmp, sourceColor);
	}

	public void drawGraphOnImageNoScaling(Bitmap bmp, Hashtable sourceColor) {
		GraphDataSet[] flat = flatten();
		for (int i = 0; i < count; i++) {
			if (flat[i] != null)
				flat[i].drawGraphOnImageNoScaling(bmp, sourceColor);
		}
	}

	public void drawCrossOnImage(Bitmap bmp, Hashtable sourceColor) {
		bmp.scaleView(
			new Rectangle(
				0,
				(float) getMax(),
				(bmp.getWidth()),
				(float) (getMin() - 1)));
		drawCrossOnImageNoScaling(bmp, sourceColor);
	}

	public void drawCrossOnImageNoScaling(Bitmap bmp, Hashtable sourceColor) {
		GraphDataSet[] flat = flatten();
		for (int i = 0; i < count; i++) {
			flat[i].drawCrossOnImageNoScaling(bmp, null, sourceColor);
		}
	}
}
