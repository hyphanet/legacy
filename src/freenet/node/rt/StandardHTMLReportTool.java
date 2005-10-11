package freenet.node.rt;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Enumeration;

import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.Key;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.graph.Bitmap;
import freenet.support.graph.Color;
import freenet.support.graph.DibEncoder;
import freenet.support.graph.GDSList;
import freenet.support.graph.GraphDataSet;


abstract class StandardHTMLReportTool
	implements KeyspaceEstimator.HTMLReportTool {

	//public double convertFromRaw(long x, int type) {
	//	return DecayingKeyspaceEstimator.this.convertFromRaw(x, type);
	//}

	protected final NumericKeyKeyspaceEstimator estimator;
	protected boolean logDEBUG;

    
    StandardHTMLReportTool(NumericKeyKeyspaceEstimator estimator) {
        this.estimator = estimator;
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
    }

    abstract protected void dumpHtmlMiddle(java.io.PrintWriter pw);
    
    public void dumpHtml(java.io.PrintWriter pw) {
		pw.println("<table border=\"0\">");
		dumpHtmlMiddle(pw);
	
		pw.println(
			"<tr><td>Maximum</td><td colspan=\""+(columnCount()-1)+"\">"
				+ HexUtil.biToHex(Key.KEYSPACE_SIZE)
				+ "</td></tr>");
		pw.println("</table>");
	}

    abstract protected int columnCount();

    abstract protected void dumpLog();
    
	public GraphDataSet createRecentDS(int samples) {
		GraphDataSet g = new GraphDataSet();

		BigInteger keyspaceLastKey =
			Key.KEYSPACE_SIZE.subtract(BigInteger.ONE);
		BigInteger keyspaceStepLength =
			keyspaceLastKey.divide(BigInteger.valueOf(samples));

		Enumeration e = this.estimator.recentReports().enumeration();
		while (e.hasMoreElements()) {
			RecentReports.KeyTimePair kt =
				(RecentReports.KeyTimePair) e.nextElement();
			int i = kt.key.divide(keyspaceStepLength).intValue();
			double t = kt.time;
			g.addPoint(i, kt.time);
		}
		return g;
	}

	public void drawGraphBMP(
		int width,
		int height,
		boolean dontClipPoints,
		HttpServletResponse resp)
		throws IOException {
	    if(logDEBUG)
	        Core.logger.log(this, "drawGraphBMP("+width+","+height+","+
	                dontClipPoints+","+resp+" for "+this+" on "+ estimator,
	                Logger.DEBUG);
		Bitmap bmp = new Bitmap(width, height);
		GDSList gdsl =
			this.estimator.createGDSL(width, false, new Color(0, 0, 0));
		
		GraphDataSet recentDataSet = createRecentDS(width);
		if(gdsl != null && dontClipPoints)
		    gdsl.setHL(recentDataSet.getHighest(), 
		            recentDataSet.getLowest());
		if(gdsl != null)
		    gdsl.drawGraphsOnImage(bmp);
		recentDataSet.drawCrossOnImageNoScaling(bmp, new Color(255, 0, 0));
		DibEncoder.drawBitmap(bmp, resp);
	}

}