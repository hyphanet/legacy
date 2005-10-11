package freenet.support.graph;

import java.util.TreeSet;
import java.util.Iterator;
import java.util.Hashtable;

/** 
 * GraphDataSet holds x,y points to be drawn to a Bitmap x values should
 * correspond to the Bitmap's width; i.e. ranging from 0 to getWidth()
 *
 * @author thelema
 */

public class GraphDataSet {
    public class GraphPoint implements Comparable {
	int x; // should this be a BigInteger or a Key?
	double y;
		String id;
		public GraphPoint(int x1, double y1, String id1) {
			x = x1;
			y = y1;
			id = id1;
	}
	public int compareTo(GraphPoint gp) {
			if (gp.x > this.x) {
				return 1;
			} else if (gp.x < this.x) {
				return -1;
			} else
				return 0;
	}					
	public int compareTo(Object o) { 
	    return this.compareTo((GraphPoint) o);
	}
	public boolean equals(Object o) {
	    return ((GraphPoint) o).x == this.x;
	}
    }
    
	protected TreeSet points = new TreeSet();
	// contains the set of GraphPoints
    protected double lowest = Double.MAX_VALUE;
	protected int lowestPointX = -1;
    protected double highest = 0;
	protected int highestPointX = -1;
    
    public void addPoint(int x, double y) { 
		addPoint(x, y, null);
	}
	public void addPoint(int x, double y, String id) {
		points.add(new GraphPoint(x, y, id));
		if(!(Double.isInfinite(y) || Double.isNaN(y))) {
		if (y > highest) {
			highest = y;
		        highestPointX = x;
		}
		if (y < lowest) {
			lowest = y;
		        lowestPointX = x;
		    }
		}
    }
    
	public Iterator getPoints() {
		return points.iterator();
	}
    
	public double getLowest() {
		return lowest;
	}
	
	public int getLowestPointX() {
	    return lowestPointX;
	}
	
	public double getHighest() {
		return highest;
	}
    
	public int getHighestPointX() {
	    return highestPointX;
	}
	
    public void drawGraphOnImage(Bitmap bmp, Color c) {
		bmp.scaleView(
			new Rectangle(
				0,
	       (float) highest,
				(bmp.getWidth()),
	       (float) (lowest - 1)));
	drawGraphOnImageNoScaling(bmp, c);
    }
    
    public void drawGraphOnImageNoScaling(Bitmap bmp, Color c) {
		drawGraphOnImageNoScaling(bmp, c, null);
	}

	public void drawGraphOnImageNoScaling(Bitmap bmp, Hashtable h) {
		drawGraphOnImageNoScaling(bmp, null, h);
	}

	public void drawGraphOnImageNoScaling(Bitmap bmp, Color c, Hashtable h) {
		Color c1;
		if (c == null)
			c = new Color(255, 255, 255);
	Iterator i = getPoints();
	if (i.hasNext()) {
	    GraphPoint gp = (GraphPoint) i.next();
	    bmp.moveTo(gp.x, (float) gp.y);
	    while(i.hasNext()) {
				if (h != null && gp.id != null) {
					c1 = (Color) h.get(gp.id);
					if (c1 != null)		
					bmp.setPenColor(c1);
				else
					bmp.setPenColor(c);
				} else {
					bmp.setPenColor(c);
				}
		gp = (GraphPoint) i.next();
		bmp.drawTo(gp.x, (float) gp.y);
	    }
	}
    }

    public void drawCrossOnImage(Bitmap bmp, Color c) {
		bmp.scaleView(
			new Rectangle(
				0,
	       (float) highest,
				(bmp.getWidth()),
	       (float) (lowest - 1)));
	drawCrossOnImageNoScaling(bmp, c);
    }
    
    public void drawCrossOnImageNoScaling(Bitmap bmp, Color c) {
		drawCrossOnImageNoScaling(bmp, c, null);
	}
	
	public void drawCrossOnImageNoScaling(Bitmap bmp, Color c, Hashtable h) {
		Color c1;
	for(Iterator i = getPoints(); i.hasNext(); ) {
	    GraphPoint gp = (GraphPoint) i.next();
			if (h != null && (c1 = (Color) h.get(gp.id)) != null);
					c1 = c;
			if (c1 != null) {
			bmp.setPenColor(c1);
	    bmp.drawPlus(gp.x, (float) gp.y);
	}
    }	
}

}
