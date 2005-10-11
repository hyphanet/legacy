package freenet.client.cli;
import freenet.client.*;
import freenet.node.Node;
import freenet.config.Config;
import freenet.Version;
/**
 * Internal CLI clientfactory. I'll make a terminal interface for the node.
 * (It isn't very useful, but sort of fun).
 *
 * @author oskar
 */

public class CLIInternalClient extends InternalClient 
    implements CLIClientFactory {

    public CLIInternalClient(Node n) {
        super(n);
    }

    public String getDescription() {
        return "Internal Client: " + Version.getVersionString();
    }

    public Config getOptions() {
        return new Config(); // don't really take any options...
    }

    public void stop() {
        // not happening
    }

}
