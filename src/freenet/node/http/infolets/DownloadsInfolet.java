package freenet.node.http.infolets;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Enumeration;

import freenet.Core;
import freenet.client.inFlightRequestTrackingAutoRequester;
import freenet.client.http.BaseContext;
import freenet.client.http.ContextManager;
import freenet.node.Node;
import freenet.node.http.Infolet;
import freenet.support.Logger;
import freenet.support.servlet.HtmlTemplate;

/**
 * Infolet that displays the list of background downloads
 */
public class DownloadsInfolet extends Infolet {
    private HtmlTemplate titleBoxTmp;

    public String longName() {
        return "Current Downloads";
    }

    public String shortName() {
        return "downloads";
    }

    public void init(Node n) {
        try {
            titleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");
        } catch (java.io.IOException e) {
            Core.logger.log(this,"Couldn't load titleBox.tpl",e,Logger.NORMAL);
        }
    }

    public void toHtml(PrintWriter pw) {
        ContextManager contextManager = new ContextManager();

        // splitfile downloads
        StringWriter tpw = new StringWriter();
        PrintWriter sw = new PrintWriter(tpw);

        for (Enumeration e = contextManager.ids(); e.hasMoreElements();) {
            String e_key = (String) e.nextElement();
            BaseContext ctx = (BaseContext) contextManager.lookup(e_key);
            ctx.writeHtml(sw);
        }
        pw.print(tpw.toString());

        StringWriter tpw2 = new StringWriter();
        PrintWriter sw2 = new PrintWriter(tpw2);
        boolean bFirstIteration = true;
        for (Enumeration e = inFlightRequestTrackingAutoRequester.requestsSnapshot(); e.hasMoreElements();) {
            if (!bFirstIteration) sw2.println("<br />");
            inFlightRequestTrackingAutoRequester r = (inFlightRequestTrackingAutoRequester) e.nextElement();
            r.toHtml(sw2);
            sw2.println("<br />");
            bFirstIteration = false;
        }

        pw.print(tpw2.toString());

        // splitfile uploads
        /*
         * tpw = new StringWriter(); sw = new PrintWriter(tpw);
         * 
         * sw.println(" <table width=\"100%\"> "); // TODO: iterate uploads
         * list here sw.println(" <tr><td> Not yet implemented </td></tr> ");
         * sw.println(" </table> "); titleBoxTmp.set("TITLE", "Splitfile
         * uploads"); titleBoxTmp.set("CONTENT", tpw.toString());
         */

    }

}
