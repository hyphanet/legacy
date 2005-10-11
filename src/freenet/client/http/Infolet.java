package freenet.client.http;
import java.io.*;
import freenet.node.*;
import freenet.support.servlet.*;

/**
 * An Infolet takes a Node object and a PrintWriter, and sends a fragment of
 * HTML to the printwriter describing some aspect of the node's status
 **/
public abstract class Infolet implements TemplateElement
{
  /** @return The long name for the Infolet (as appears in menu and page title **/
  public abstract String longName();
  /** @return Short alphanumeric name for Infolet for use in a URL **/
  public abstract String shortName();

  /** Initialise the Infolet with a Node object, called shortly after
      node startup **/
  public abstract void init(Node n);

  /** Send a fragment of HTML describing some aspect of the Node's status to
      the PrintWriter **/
  public abstract void toHtml(PrintWriter pw);
}
