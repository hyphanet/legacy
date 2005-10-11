package freenet.support.graph;

import java.util.LinkedList;
import java.util.Iterator;

/** 
 * GraphDataSetList takes multiple GraphDataSets and make sure that they get
 * drawn on the same Bitmap with the correct scale
 *
 * @author thelema
 */

public class GDSList {

    private class GDSColor {
	GraphDataSet gds;
	Color c;
		String id;
		public GDSColor(GraphDataSet gds1, Color c1, String id1) {
			gds = gds1;
			c = c1;
			id = id1;
	}
    }
    
    private LinkedList gdses = new LinkedList();
    public double lowest = Double.MAX_VALUE;
	public int lowestPointX = -1;
    public double highest = 0;
	public int highestPointX = -1;
    
    public void add (GraphDataSet gds, Color c1) {
		add(gds, c1, null);
	}

	public void add(GraphDataSet gds, Color c1, String id1) {
		gdses.addFirst(new GDSColor(gds, c1, id1));
		if (lowest > gds.getLowest()) {
			lowest = gds.getLowest();
			lowestPointX = gds.lowestPointX;
		}
		if (highest < gds.getHighest()) {
			highest = gds.getHighest();
			highestPointX = gds.highestPointX;
		}
    }

    public void setHL (double highin, double lowin) {
		if (lowest > lowin) {
			lowest = lowin;
		}
		if (highest < highin) {
			highest = highin;
		}
    }
    
    public void drawGraphsOnImage(Bitmap bmp) {
		bmp.scaleView(
			new Rectangle(
				0.0F,
	       (float) highest,
				bmp.getWidth(),
				(float)lowest));
	
	for (Iterator i = gdses.iterator(); i.hasNext();) {
	    GDSColor gdsc = (GDSColor) i.next();
	    gdsc.gds.drawGraphOnImageNoScaling(bmp, gdsc.c);
	}
    }	
    
}
