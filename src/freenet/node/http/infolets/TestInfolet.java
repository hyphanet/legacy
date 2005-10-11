package freenet.node.http.infolets;
import freenet.node.http.Infolet;
import freenet.node.Node;
import java.io.PrintWriter;

public class TestInfolet extends Infolet  {
    public String longName()  {
        return "Test Infolet";
    }
    
    public String shortName()  {
        return "test";
    }

    public void init(Node n)  { }
    
    public void toHtml(PrintWriter pw)  {
        pw.println("<b>Test Infolet</b>\n");
    }
}
