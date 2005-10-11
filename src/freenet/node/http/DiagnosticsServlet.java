package freenet.node.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.NoSuchElementException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.diagnostics.Diagnostics;
import freenet.diagnostics.DiagnosticsFormat;
import freenet.diagnostics.FieldSetFormat;
import freenet.diagnostics.GraphDiagnosticsFormat;
import freenet.diagnostics.GraphHtmlDiagnosticsFormat;
import freenet.diagnostics.GraphRange;
import freenet.diagnostics.GraphRangeDiagnosticsFormat;
import freenet.diagnostics.HtmlDiagnosticsFormat;
import freenet.diagnostics.HtmlIndexFormat;
import freenet.diagnostics.RowDiagnosticsFormat;
import freenet.support.Logger;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.graph.BitmapEncoder;
import freenet.support.graph.DibEncoder;

public class DiagnosticsServlet extends HttpServlet {

    public void init() {
    }


    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
	throws IOException {
        
        String uri;
        try {
            String pi = req.getPathInfo();
            uri = pi == null ? "" : URLDecoder.decode(pi);
        } catch (URLEncodedFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Could not decode url.");
            return;
        }

        if (uri.startsWith("/"))
            uri = uri.substring(1);

        if (uri.equalsIgnoreCase("index.html") || uri.length()==0) {
            sendDiagnosticsIndex(req, resp);
        } else {
            int sep = uri.indexOf('/');
            if (sep == -1) {
                if (uri.equals("graphs")) {
                    sendGraphData(req, resp);
                } else {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unrecognized URL.");
                }
            } else {
                String varName = uri.substring(0, sep);
                String period = uri.substring(sep + 1);
                sendVarData(req, resp, 
                            varName, period);
            }
        }
    }

    public String getServletInfo() {
        return "Node diagnostics information.";
    }

    public String getServletName() {
        return "Diagnostics";
    }


    private final void sendDiagnosticsIndex(HttpServletRequest req, 
                                            HttpServletResponse resp)
        throws IOException {

        PrintWriter pw = resp.getWriter();
        resp.setContentType("text/html");

        pw.println("<html>");
        pw.println("<head>");
        pw.println("<title>");
        pw.println("Freenet Node Diagnostics Variables");
        pw.println("</title>");
        pw.println("</head>");
        pw.println("<body>");
        pw.println();
        pw.println();

        DiagnosticsFormat indexFormat = new HtmlIndexFormat();
        Core.diagnostics.writeVars(pw, indexFormat);

        pw.println("</body>");
        pw.println("</html>");
        resp.flushBuffer();
    }


    private final void sendVarData(HttpServletRequest req, 
                                   HttpServletResponse resp, 
                                   String varName, 
                                   String period) throws IOException {


        PrintWriter pw = resp.getWriter();

        DiagnosticsFormat format;
        boolean html = false;
        if (period.equalsIgnoreCase("occurrences")) {
            html = true;
            resp.setContentType("text/html");
            format =  new HtmlDiagnosticsFormat(-1);
        } else if (period.equalsIgnoreCase("raw")) {
            resp.setContentType("text/plain");
            format = new RowDiagnosticsFormat();
        } else if (period.startsWith("raw")) {
            resp.setContentType("text/plain");
            if (period.substring(3).equalsIgnoreCase("occurences"))
                format = new RowDiagnosticsFormat(-1);
            else
                format = 
                    new RowDiagnosticsFormat(Diagnostics.getPeriod(period.substring(3)));

        } else if (period.equalsIgnoreCase("fieldset")) {
            resp.setContentType("text/plain");
            format = new FieldSetFormat();
        } else {
            try {
                resp.setContentType("text/html");
                html = true;
                format = 
                    new HtmlDiagnosticsFormat(Diagnostics.getPeriod(period));
            } catch (IllegalArgumentException e) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, 
                               "Unknown period type given.");
                return;
            }
        }

        try {
            if (html)
                pw.println("<html><body>");
            pw.println(Core.diagnostics.writeVar(varName, format));
            if (html)
                pw.println("</body></html>");
        } catch (NoSuchElementException e) {
            resp.sendError(404, "No such diagnostics field");
            return;
        }

        resp.flushBuffer();
    }

    
    private final void sendGraphData(HttpServletRequest req,
                                     HttpServletResponse resp) {
        try {
            String varName = req.getParameter("var");
            
            if (varName == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, 
                               "Unknown diagnostics variable.");
                return;
            }
            
            GraphRange gr;
            
            String period = req.getParameter("period");
            
            if (period == null)
                period = "minute";
                
            final int p = (period.equalsIgnoreCase("occurrences") ? 
                           -1 : 
                           Diagnostics.getPeriod(period));
            
            int type;
            
            try {
                type = Integer.parseInt(req.getParameter("type"));
            } catch (NumberFormatException e) {
                type = 0; // 0 == guess
            }
                    
            try {
                // try getting the range out of the query string
                gr = new GraphRange(req.getParameter("range"));
            } catch (IllegalArgumentException e) {
                // failing that, parse it from the data we're formatting
                GraphRangeDiagnosticsFormat grdf = 
                    new GraphRangeDiagnosticsFormat(p, type);
                                            
                Core.diagnostics.writeVar(varName, grdf);
                
                gr = grdf.getRange();
            }
            
            type = gr.getType();
            
            String ctype = req.getParameter("content-type");
            
            if (ctype != null)
                try {
                    ctype = freenet.support.URLDecoder.decode(ctype);
                } catch (URLEncodedFormatException e) {
                    ctype = null;
                }
                
            BitmapEncoder be = BitmapEncoder.createByMimeType(ctype);
            
            if (be == null) {       
                // default to html
                PrintWriter pw = resp.getWriter();
                
                resp.setContentType("text/html");
                
                String itype = req.getParameter("image-type");
                // todo: get default from config
                if (itype == null) {itype = new DibEncoder().getMimeType(); }
               
                pw.println(Core.diagnostics.writeVar(varName, 
                                   new GraphHtmlDiagnosticsFormat(p, type, 
                                                                  gr, itype)));
            } else {
                // output the image
                OutputStream os = resp.getOutputStream();
                
                resp.setContentType(be.getMimeType());
                
                Core.diagnostics.writeVar(varName, 
                                          new GraphDiagnosticsFormat(p, be, os,
                                                                     type, 
                                                                     gr));
            }
            
            resp.flushBuffer();                           
        } catch (Throwable t) {
            Core.logger.log(this, "Grapher threw.", t, Logger.ERROR);
        }
    }

    

}





