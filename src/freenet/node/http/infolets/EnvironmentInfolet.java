package freenet.node.http.infolets;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.NumberFormat;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;

import freenet.Core;
import freenet.fs.dir.NativeFSDirectory;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.http.Infolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;
import freenet.support.Logger;
import freenet.support.servlet.HtmlTemplate;
import freenet.thread.ThreadStatusSnapshot;
import freenet.transport.tcpAddress;

public final class EnvironmentInfolet extends Infolet {

    private Node node;

    private final NumberFormat nf = NumberFormat.getInstance();

    private HtmlTemplate titleBoxTmp;

    public String longName() {
        return "Environment";
    }

    public String shortName() {
        return "env";
    }

    public boolean visibleFor(HttpServletRequest req) {
        return SimpleAdvanced_ModeUtils.isAdvancedMode(req);
    }

    public void init(Node n) {
        this.node = n;
        try {
            titleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");
        } catch (java.io.IOException e) {
        }
    }

    public void toHtml(PrintWriter pw) {
        HtmlTemplate titleBoxTmp = new HtmlTemplate(this.titleBoxTmp);
        StringWriter ssw = new StringWriter(200);
        PrintWriter sw = new PrintWriter(ssw);

        sw.println("<table width=\"100%\">");
        // architecture and operation system information
        sw.println("<tr><td>Architecture</td><td align=\"right\">" + System.getProperty("os.arch") + "</td></tr>");
        sw.println("<tr><td>Available processors</td><td align=right>" + Runtime.getRuntime().availableProcessors() + "</td></tr>");
        sw.println("<tr><td>Operating System</td><td align=right>" + System.getProperty("os.name") + "</td></tr>");
        sw.println("<tr><td>OS Version</td><td align=right>" + System.getProperty("os.version") + "</td></tr>");
        sw.println("</table>");
        titleBoxTmp.set("TITLE", "Architecture and Operating System");
        titleBoxTmp.set("CONTENT", ssw.toString());
        titleBoxTmp.toHtml(pw);

        // java virtual machine information
        ssw = new StringWriter(200);
        sw = new PrintWriter(ssw);
        sw.println("<table width=\"100%\">");
        sw.println("<tr><td>JVM Vendor</td><td align=right><a href=\"" + System.getProperty("java.vendor.url") + "\">"
                + System.getProperty("java.vm.vendor") + "</a></td></tr>");
        sw.println("<tr><td>JVM Name</td><td align=right>" + System.getProperty("java.vm.name") + "</td></tr>");
        sw.println("<tr><td>JVM Version</td><td align=right>" + System.getProperty("java.vm.version") + "</td></tr>");
        sw.println("</table>");
        titleBoxTmp.set("TITLE", "Java Virtual Machine");
        titleBoxTmp.set("CONTENT", ssw.toString());
        titleBoxTmp.toHtml(pw);

        // memory allocation
        ssw = new StringWriter(200);
        sw = new PrintWriter(ssw);
        sw.println("<table width=\"100%\">");
        long max = Runtime.getRuntime().maxMemory();
        sw.println("<tr><td>Maximum memory the JVM will allocate</td><td align=right>" + ((max == Long.MAX_VALUE) ? "Unlimited" : format(max))
                + "</td></tr>");
        sw.println("<tr><td>Memory currently allocated by the JVM</td><td align=right>" + format(Runtime.getRuntime().totalMemory()) + "</td></tr>");
        sw.println("<tr><td>Memory in use</td><td align=right>" + format(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                + "</td></tr>");
        sw.println("<tr><td>Estimated memory used by logger</td><td align=right>" + format(Core.listLogBytes()) + "</td></tr>");
        sw.println("<tr><td>Unused allocated memory</td><td align=right>" + format(Runtime.getRuntime().freeMemory()) + "</td></tr>");
        sw.println("</table>");
        titleBoxTmp.set("TITLE", "Memory Allocation");
        titleBoxTmp.set("CONTENT", ssw.toString());
        titleBoxTmp.toHtml(pw);

        // data store information
        ssw = new StringWriter(200);
        sw = new PrintWriter(ssw);
        sw.println("<table width=\"100%\">");
        long total = Node.storeSize;
        sw.println("<tr><td>Maximum size</td><td align=right>" + format(total) + "</td></tr>");
        long used = node.dir.used();
        sw.println("<tr><td>Used space</td><td align=right>" + format(used) + "</td></tr>");
        if (total > 0) {
            sw.println("<tr><td>Free space</td><td align=right>" + format(total - used) + "</td></tr>");
            sw.println("<tr><td>Percent used</td><td align=right>" + 100 * used / total + "</td></tr>");
        }
        long keys = node.dir.countKeys();
        sw.println("<tr><td>Total keys</td><td align=right>" + keys + "</td></tr>");
        if (node.dir instanceof NativeFSDirectory) {
            sw.println("<tr><td>Space used by temp files</td><td align=right>" + format(((NativeFSDirectory) (node.dir)).tempSpaceUsed())
                    + "</td></tr>");
            sw.println("<tr><td>Maximum space for temp files</td><td align=right>" + format(((NativeFSDirectory) (node.dir)).maxTempSpace())
                    + "</td></tr>");
            if (keys > 0) {
                sw.println("<tr><td>Most recent file access time</td><td align=right>"
                        + new Date(((NativeFSDirectory) node.dir).mostRecentlyUsedTime()).toString() + "</td></tr>");
                sw.println("<tr><td>Least recent file access time</td><td align=right>"
                        + new Date(((NativeFSDirectory) node.dir).leastRecentlyUsedTime()).toString() + "</td></tr>");
            }
        }
        sw.println("</table>");
        titleBoxTmp.set("TITLE", "Data Store");
        titleBoxTmp.set("CONTENT", ssw.toString());
        titleBoxTmp.toHtml(pw);

        if (!Main.publicNode) {
            ssw = new StringWriter(200);
            sw = new PrintWriter(ssw);
            sw.println("<table width=\"100%\">");
            tcpAddress tcp = Main.getTcpAddress();
            String s = "(NOT RESOLVABLE)";
            if (tcp != null) try {
                s = tcp.getHost().getHostAddress();
            } catch (java.net.UnknownHostException e) {
                // set above
                }

            sw.println("<tr><td>Current IPv4 address</td><td align=\"right\">" + ((tcp == null) ? "(NOT AVAILABLE)" : s) + "</td></tr>");
            sw.println("<tr><td>Current IPv4 port</td><td align=\"right\">" + ((tcp == null) ? "(NOT AVAILABLE)" : Integer.toString(tcp.getPort()))
                    + "</td></tr>");
            sw.println("<tr><td>ARK sequence number</td><td align=\"right\">" + node.getNodeReference().revision() + "</td></tr>");
            long x = Main.getInitialARKversion();
            try {
                sw.println("<tr><td>Last ARK sequence number inserted</td>" + "<td align=\"right\"><a href=\"/"
                        + node.getNodeReference().getARKURI(x).toString(false) + "\">" + x + "</a></td></tr>");
            } catch (freenet.KeyException e) {
                Core.logger.log(this, "Broken: " + e, e, Logger.ERROR);
                sw.println("<tr><td>ARKs broken</td></tr>");
            }
            sw.println("</table>");
            titleBoxTmp.set("TITLE", "Transports");
            titleBoxTmp.set("CONTENT", ssw.toString());
            titleBoxTmp.toHtml(pw);
            
            String detectedAddresses = node.connections.detectedAddressesToHTML();
            if(detectedAddresses.length() != 0) {
                titleBoxTmp.set("TITLE", "Addresses Detected by the Network");
                titleBoxTmp.set("CONTENT", detectedAddresses);
                titleBoxTmp.toHtml(pw);
            }
        }

        ThreadStatusSnapshot t = new ThreadStatusSnapshot();
        if (!t.getPoolConsumers().isEmpty()) {
            titleBoxTmp.set("TITLE", "Thread Pool");
            titleBoxTmp.set("CONTENT", t.threadStatusToHTML());
            titleBoxTmp.toHtml(pw);

            titleBoxTmp.set("TITLE", "Pooled Thread Consumers");
            titleBoxTmp.set("CONTENT", t.poolConsumersToHTML());
            titleBoxTmp.toHtml(pw);
        }

        pw.println("</td></tr></table>");
        StringWriter tsw = new StringWriter();
        PrintWriter tpw = new PrintWriter(tsw);
        tpw.print("<ul>");
        tpw.println(t.threadTreeToHTML());
        tpw.println("</ul>");
        titleBoxTmp.set("TITLE", "ThreadGroup/Thread Hierarchy");
        titleBoxTmp.set("CONTENT", tsw.toString());
        titleBoxTmp.toHtml(pw);
        pw.println("<table><tr><td>");
    }

    private String format(long bytes) {
        if (bytes == 0) return "None";
        if (bytes << 4 == 0) return nf.format(bytes >> 60) + " EiB";
        if (bytes << 14 == 0) return nf.format(bytes >> 50) + " PiB";
        if (bytes << 24 == 0) return nf.format(bytes >> 40) + " TiB";
        if (bytes << 34 == 0) return nf.format(bytes >> 30) + " GiB";
        if (bytes << 44 == 0) return nf.format(bytes >> 20) + " MiB";
        if (bytes << 54 == 0) return nf.format(bytes >> 10) + " KiB";
        return nf.format(bytes) + " Bytes";
    }
}
