package freenet.node.http.infolets;
import freenet.node.http.Infolet;
import freenet.node.Node;
import freenet.node.Main;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class DistributionServletInfolet extends Infolet  {
    public String longName()  {
        return "Spread Freenet";
    }
    
    public String shortName()  {
        return "distribution";
    }

    public void init(Node n)  { }
    
    public void toHtml(PrintWriter pw)  {
        pw.println("If this appears somethings gone wrong.");
    }

    public String target(String base, String container) {
	if(Node.distributionURIOverride == null || 
	   (Node.distributionURIOverride.length()==0)) {
	    InetAddress i = Main.getDetectedAddress(0);
	    try {
		if(i == null) i = InetAddress.getByName("127.0.0.1");
	    } catch (UnknownHostException e) { /* impossible */ }
	    return "http://"+i.getHostAddress()+":"+Node.distributionPort+"/";
	} else return Node.mainportURIOverride;
    }
}
