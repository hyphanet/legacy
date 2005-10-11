package freenet.node.http.infolets;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;

import freenet.Core;
import freenet.node.Node;
import freenet.node.http.Infolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;
import freenet.support.BufferLoggerHook;
import freenet.support.LoggerHookChain;
import freenet.support.Logger;
import freenet.support.LoggerHook;
import freenet.support.BufferLoggerHook.LogEntry;

public class LoggerInfolet extends Infolet {

    public static final int BUFFER_SIZE = 20;

    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

    private BufferLoggerHook buffer;

    public String longName() {
        return "Recent logs";
    }

    public String shortName() {
        return "logs";
    }

    public boolean visibleFor(HttpServletRequest req) {
        return SimpleAdvanced_ModeUtils.isAdvancedMode(req);
    }

    public void init(Node n) {
    	Logger l = Core.getLogger();
    	if(!(l instanceof LoggerHookChain))
    		throw new IllegalStateException("Core's logger not a LoggerHookChain, this infolet cannot be used");
    	LoggerHookChain chl = (LoggerHookChain)l;
        LoggerHook[] hooks = chl.getHooks();
        for (int i = 0; i < hooks.length; i++) {
            if (hooks[i] instanceof BufferLoggerHook && ((BufferLoggerHook) hooks[i]).size() >= BUFFER_SIZE) {
                buffer = (BufferLoggerHook) hooks[i];
                break;
            }
        }
        if (buffer == null) { // still
            buffer = new BufferLoggerHook(BUFFER_SIZE);
            chl.addHook(buffer);
            l.log(this, "No log buffer was found when starting log infolet.", Logger.MINOR);
        }
    }

    public synchronized void toHtml(PrintWriter pw) {
        pw.println("<h3>Recently logged messages:</h3>\n");
        pw.println("<b>Priority legend: </b> <span class=\"logerror\">" + "Error</span>, <span class=\"lognormal\">Normal</span>"
                + ", <span class=\"logminor\">Minor</span>, " + "<span class=\"logdebug\">Debug</span><p>\n");
        pw.println("<table width=\"100%\"><tr><th>Time</th>" + "<th>Message</th><th>Exception</th></tr>\n");
        LogEntry[] le = buffer.getBuffer();

        for (int i = 0; i < le.length && i < BUFFER_SIZE; i++) {
            String cssClass;
            switch (le[i].priority()) {
            case Logger.ERROR:
                cssClass = "logerror";
                break;
            case Logger.NORMAL:
                cssClass = "lognormal";
                break;
            case Logger.MINOR:
                cssClass = "logminor";
                break;
            default:
                cssClass = "logdebug";
            }

            Throwable exception = le[i].exception();

            StringBuffer sb = new StringBuffer(250);
            sb.append("<tr>").append("<td class=\"logentry ").append(cssClass).append("\">").append(sdf.format(new Date(le[i].time()))).append(
                    "</td><td class=\"logentry ").append(cssClass).append("\">").append(le[i].message()).append("</td><td class=\"logentry ").append(
                    cssClass).append("\">").append(exception == null ? "-" : exception.toString()).append("</td></tr>");

            pw.println(sb.toString());
        }
        pw.println("</table>\n");
    }

}
