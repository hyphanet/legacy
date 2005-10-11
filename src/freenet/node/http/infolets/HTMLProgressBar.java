/*
 * Created on Aug 30, 2004
 *
 */
package freenet.node.http.infolets;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import freenet.Core;
import freenet.support.Logger;
import freenet.support.servlet.HtmlTemplate;

/**
 * @author Iakin
 *
 */
public class HTMLProgressBar {
	private HtmlTemplate relBarTmp, barTmp;
	private final long total, current;
	private long width = -1;

	/**
	 * @param current The current value of the progress bar (usually less than 'total')
	 * @param total The total length of the progress bar
	 */
	public HTMLProgressBar(long current, long total) {
		super();
		this.current = current;
		this.total = total;
		try {
			relBarTmp = HtmlTemplate.createTemplate("relbar.tpl");
            barTmp = HtmlTemplate.createTemplate("bar.tpl");
		} catch (IOException e) {
			Core.logger.log(this, "Couldn't load templates", e, Logger.NORMAL);
		}
	}
	
	//Renders the progressbar,as defined, to a HTML string 
	public String render(){
        StringWriter ssw = new StringWriter(100);
        PrintWriter pw2 = new PrintWriter(ssw);
        float dRelWidth = width==-1?1:((float)width)/total;
        if (current == 0 || current == total) {
            barTmp.set("COLOR", getColorForValue(current));
            barTmp.set("WIDTH",String.valueOf(Math.round(dRelWidth*total)));
            barTmp.toHtml(pw2);
        } else {
            relBarTmp.set("LBAR", getColorForValue(current));
            relBarTmp.set("LBARWIDTH", String.valueOf(Math.round(dRelWidth*current)));
            relBarTmp.set("RBARWIDTH", "" + String.valueOf(Math.round(dRelWidth*(total - current))));
            relBarTmp.toHtml(pw2);
        }
        
		return ssw.toString();
	}
	
	//Sets the total width of the progressbar, if not specified
	//the maximum value of the progress bar will be used as width
	public void SetWidth(long l){
		width = l;
	}
	public static final int COLOR_TRANSPARENT = 0; //""
	public static final int COLOR_GREEN = 1; //"g"
	public static final int COLOR_YELLOW = 2; //"y"
	public static final int COLOR_RED = 3; //"l"
	
	private String sLowColor=null;
	private long lowColorThreshold = -1;
	private String sHighColor=null;
	private long highColorThreshold = -1;

	//Figures out the appropriate bar color for the supplied bar value
	private String getColorForValue(long value){
		if(value ==0)
			return "";
		if(highColorThreshold >0 && value >highColorThreshold )
			return sHighColor;
		if(lowColorThreshold >0 && value >lowColorThreshold )
			return sLowColor;
		return "g";
	}
	
	//Specifies an alternate color that the progressbar will use
	//when the its value becomes larger than the supplied threshold
	public void setLowColorThreshold(int lowThreshold, int color) {
		if(highColorThreshold != -1 && lowThreshold>highColorThreshold)
			throw new IllegalArgumentException("Low color threshold ("+lowThreshold+") may not be lower than high color threshold ("+highColorThreshold+")");
		lowColorThreshold = lowThreshold;
		switch(color){
		case COLOR_GREEN:
			sLowColor = "g";
			break;
		case COLOR_YELLOW:
			sLowColor = "y";
			break;
		case COLOR_RED:
			sLowColor = "l";
			break;
		}
	}
	//Specifies an alternate color that the progressbar will use
	//when the its value becomes larger than the supplied threshold
	public void setHighColorThreshold(int highThreshold, int color) {
		if(lowColorThreshold != -1 && highThreshold<lowColorThreshold)
			throw new IllegalArgumentException("High color threshold ("+highThreshold+") may not be lower than low color threshold ("+lowColorThreshold+")");
		highColorThreshold = highThreshold;
		switch(color){
		case COLOR_GREEN:
			sHighColor = "g";
			break;
		case COLOR_YELLOW:
			sHighColor = "y";
			break;
		case COLOR_RED:
			sHighColor = "l";
			break;
		}
	}

}
