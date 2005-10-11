package freenet.client.cli;

import freenet.*;
import freenet.config.*;
import freenet.client.*;
import freenet.transport.*;
import freenet.support.Logger;

/** 
 * CLI extensions to FCP version of Client library
 * @author giannij
 */
public class CLIFCPClient extends FCPClient implements CLIClientFactory {
    private static final String  CLIENTDESCRIPTION = "FCPClient, "+
	freenet.Version.nodeName+" "+freenet.Version.nodeVersion;
    private static final TCP tcp = new TCP(100, false);

    /**
     * Creates a new new ClientFactory from a set of options.
     * @param params   The arguments given.
     * @param logger   The logger to log output to.
     */
    public CLIFCPClient(Params params, Logger logger) throws CLIException {
        super();
        params.addOptions(getOptions().getOptions());
        try {
            setTarget(tcp.getAddress(params.getString("FCPServerAddress")));
        }
        catch (BadAddressException bae) {
            String msg = "Bad FCP Server address: " + bae.toString();
            throw new CLIException(msg);
        }
        catch (IllegalArgumentException ia) {
            String msg = "Bad FCP Server address: " + ia.toString();
            throw new CLIException(msg);
        }
    }

    ////////////////////////////////////////////////////////////
    // CLIClientFactory interface implementation
    public String getDescription() { return CLIENTDESCRIPTION; }

    public Config getOptions() { 
        Config config = new Config();
        config.addOption("FCPServerAddress",           1, "127.0.0.1:8481",
                         20);
        config.argDesc("FCPServerAddress","<hostname>:<port>");
        config.shortDesc("FCPServerAddress","Set FCP server to connect to.");
        config.longDesc("FCPServerAddress",
                        "FCP servers usually only accept " +
                        "connections from localhost",
                        "Use this option to force a different host or port.");

        return config;
    }
        
    public void stop() { /* NOP */ }
}













