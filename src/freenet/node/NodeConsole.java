package freenet.node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.Enumeration;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.Version;
import freenet.interfaces.NIOInterface;
import freenet.support.io.WriteOutputStream;

/**
 * Control center.
 * @author tavin
 */
public class NodeConsole extends HttpServlet {

    Node node;
    
    public final void init() {
        this.node = (Node) getServletContext().getAttribute("freenet.node.Node");
    }
    
    
    public void doPost(HttpServletRequest req, HttpServletResponse resp) {
        
        Enumeration params = req.getParameterNames();
        while (params.hasMoreElements()) {
            String p = (String) params.nextElement();
            if (p.startsWith("interface_")) {
                int x = p.indexOf('_');
                int i = Integer.parseInt(p.substring(x+1));
                if (i>=0 && i<node.interfaces.length) {
                    if (req.getParameter(p).equals("stop"))
                        node.interfaces[i].listen(false);
                    else if (req.getParameter(p).equals("listen"))
                        node.interfaces[i].listen(true);
                }
            }
        }
        resp.setHeader("Location", req.getRequestURI());
        resp.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
    }
    
    public void doGet(HttpServletRequest req, HttpServletResponse resp)
                                                    throws IOException {
        resp.addHeader("Cache-control", "no-cache");
        resp.addHeader("Pragma", "no-cache");
        resp.addHeader("Expires", "Thu, 01 Jan 1970 00:00:00 GMT");

        try {
            int i = Integer.parseInt(req.getParameter("show_errors"));
            if (i >= 0 && i < node.interfaces.length) {
                showInterfaceErrors(req, resp, i);
                return;
            }
        }
        catch (Exception e) {}
        
        showMenu(req, resp);
    }


    private void showInterfaceErrors(HttpServletRequest req,
                                     HttpServletResponse resp,
                                     int i) throws IOException {
        resp.setContentType("text/plain");
        PrintWriter out = resp.getWriter();
        Enumeration errors = node.interfaces[i].getExceptions();
        while (errors.hasMoreElements()) {
            Throwable t = (Throwable) errors.nextElement();
            t.printStackTrace(out);
            out.println();
        }
    }

    private void showMenu(HttpServletRequest req, HttpServletResponse resp)
                                                        throws IOException {
        resp.setContentType("text/html");
        PrintWriter out = resp.getWriter();
        
        out.println("<html>");
        out.println("<head>");
        out.println("<title>Node Console: " + (new Date()) + "</title>");
        try {
            int refresh = Integer.parseInt(req.getParameter("refresh"));
            if (refresh > 0)
                out.println("<meta http-equiv=\"refresh\" content=\""+refresh+"\" />");
        }
        catch (Exception e) {}
        out.println("</head>");
        out.println("<body>");

        out.print("<h2>");
        out.print(Version.nodeName);
        out.print(" version ");
        out.print(Version.nodeVersion);
        out.print(", protocol version ");
        out.print(Version.protocolVersion);
        out.print(" (build ");
        out.print(Version.buildNumber);
        out.print(", last good build ");
        out.print(Version.lastGoodBuild);
        out.println(")</h2>");

        out.println("<table>");
        
        // uptime

        long uptime = (System.currentTimeMillis() - Node.startupTimeMs) / 1000;
        long days = uptime / 86400;
        long hours = (uptime % 86400) / 3600;
        long minutes = (uptime % 3600) / 60;
        
        out.println("<tr>");
        out.println("<td valign=\"top\" align=\"right\" nowrap=\"nowrap\"><b>uptime</b></td>");
        out.println("<td> &nbsp; </td>");
        out.println("<td>" + days + " days " + hours + " hours " + minutes + " minutes" + "</td>");
        out.println("</tr>");

        // connections

        out.println("<tr>");
        out.println("<td valign=\"top\" align=\"right\" nowrap=\"nowrap\"><b>connections</b></td>");
        out.println("<td> &nbsp; </td>");
        out.println("<td>" + node.connections.countConnections() + "</td>");
        out.println("</tr>");
        
        // active threads

        out.println("<tr>");
        out.println("<td valign=\"top\" align=\"right\" nowrap=\"nowrap\"><b>active threads</b></td>");
        out.println("<td> &nbsp; </td>");
        out.println("<td>" + Node.threadFactory.activeThreads() + " / "
                           + Node.threadFactory.maximumThreads() + "</td>");
        out.println("</tr>");

        // free space

        out.println("<tr>");
        out.println("<td valign=\"top\" align=\"right\" nowrap=\"nowrap\"><b>free space</b></td>");
        out.println("<td> &nbsp; </td>");
        out.println("<td>" + node.dir.available() + "</td>");
        out.println("</tr>");
        
        // interfaces
        
        out.println("<tr>");
        out.println("<td valign=\"top\" align=\"right\" nowrap=\"nowrap\"><b>interfaces</b></td>");
        out.println("<td> &nbsp; </td>");
        out.println("<td>");
        out.println("<form method=\"POST\">");
        out.println("<table>");
        out.println("<tr style=\"background-color: grey\">");
        out.println("<th>Interface</th>");
        out.println("<th>Status</th>");
        out.println("<th>Failures</th>");
        out.println("<th>Stop/Start</th>");
        out.println("</tr>");
        for (int i=0; i<node.interfaces.length; ++i) {
            out.println("<tr>");
            out.println("<td>"+node.interfaces[i]+"</td>");
            out.print("<td align=\"center\"><i>");
            out.print(getStatusFor(node.interfaces[i]));
            out.println("</i></td>");
            out.print("<td align=\"center\">");
            if (node.interfaces[i].getExceptionCount() > 0) {
                // keep link fresh
                String t = Long.toHexString(Core.getRandSource().nextLong());
                out.print("<a href=\"?show_errors="+i+"&t="+t+"\">");
                out.print(node.interfaces[i].getExceptionCount());
                out.print("</a>");
            }
            else {
                out.print(0);
            }
            out.println("</td>");
            out.print("<td align=\"center\">");
            if (node.interfaces[i].isTerminated()) {
                out.print("--");
            }
            else if (node.interfaces[i].isListening()) {
                out.print("<input type=\"submit\" name=\"interface_"+i+"\" value=\"stop\" />");
            }
            else {
                out.print("<input type=\"submit\" name=\"interface_"+i+"\" value=\"listen\" />");
            }
            out.println("</td>");
            out.println("</tr>");
        }
        out.println("</table>");
        out.println("</form>");
        out.println("</td>");
        out.println("</tr>");

        // node ref
        
        out.println("<tr>");
        out.println("<td valign=\"top\" align=\"right\" nowrap=\"nowrap\"><b>node ref</b></td>");
        out.println("<td> &nbsp; </td>");
        out.println("<td><pre>");
        ByteArrayOutputStream bout = new ByteArrayOutputStream(256);
        node.getNodeReference().getFieldSet().writeFields(new WriteOutputStream(bout));
        bout.close();
        out.print(new String(bout.toByteArray()));
        out.println("</pre></td>");
        out.println("</tr>");

        out.println("<table>");
        
        out.println("</body>");
        out.println("</html>");
    }

    private static final String getStatusFor(NIOInterface iface) {
        return iface.isTerminated()
               ? "terminated"
               : (iface.isListening() ? "listening" : "stopped");
    }
}












