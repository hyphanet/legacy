package freenet.client.cli;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import freenet.BadAddressException;
import freenet.Core;
import freenet.FieldSet;
import freenet.Transport;
import freenet.Version;
import freenet.client.ClientCore;
import freenet.client.FNPClient;
import freenet.config.Config;
import freenet.config.Params;
import freenet.node.BadReferenceException;
import freenet.node.NodeReference;
import freenet.support.Logger;
import freenet.support.io.ReadInputStream;
import freenet.transport.TCP;
/** 
 * CLI extensions to FNP version of Client library
 * @author oskar
 */
public class CLIFNPClient extends FNPClient implements CLIClientFactory {


    private static Transport tcp = new TCP(100, false);

    /**
     * Create a ClientCore for a TCP Freenet protocol. Most Internet Freenet
     * clients can retrieve a Core this way, and shouldn't have to bother with
     * its actual constructor.
     *
     * @param options  A config.Params set of Core parameters. See seperate 
     *                 documentation.
     * @param log      A Logger object to get log calls from the Core and 
     *                 Clients.
     **/
    private static ClientCore getTCPCore(Params options, Logger log) 
        throws CLIException {
        
        options.addOptions(Core.getConfig().getOptions());
        String address = options.getString("myAddress");
        try {
            Core.setLogger(log);
            Core.init(options);

            ClientCore core = 
                ClientCore.newInstance(tcp.getAddress(address),
                                       new freenet.session.FnpLinkManager(),
                                   new freenet.presentation.FreenetProtocol());
                    
            core.acceptConnections();
            return core;
        } catch (BadAddressException e) {
            throw new CLIException(address + " malformed.");
        }
    }


    static {
        try {
            Core.getConfig().addOption("myAddress",           1, InetAddress.getLocalHost().getHostName() + ":1177", 150); // FIXME
        } catch (UnknownHostException e) {
            Core.getConfig().addOption("myAddress",           1, "localhost", 155);
        }

        Core.getConfig().argDesc("myAddress","<hostname>");
        Core.getConfig().shortDesc("myAddress","Set the clients own address.");
        Core.getConfig().longDesc("myAddress",
                             "FNP requires that nodes be able to connect back to you. Setting",
                             "the right address can sometimes be a problem, this causes it to be forced.");

        Core.getConfig().addOption("serverRef",      'r', 1, "target.ref", 160);
        Core.getConfig().argDesc("serverRef","<filename>");
        Core.getConfig().shortDesc("serverRef","A .ref file for the node to talk to.");
        Core.getConfig().longDesc("serverRef",
                              "The name of file containing the address to the freenet node",
                             "to connect to when making requests. The file should be in Freenet .ref",
                             "format.");
        
    }



    /**
     * Creates a new new ClientFactory from a set of options.
     * @param params   The arguments given.
     * @param logger   The logger to log output to.
     */
    public CLIFNPClient(Params params, Logger logger) throws CLIException {
        super(getTCPCore(params, logger));
        try {
            FileInputStream in = 
                new FileInputStream(params.getString("serverRef"));
            FieldSet fs = new FieldSet();
            fs.parseFields(new ReadInputStream(in));
            NodeReference nr = new NodeReference(fs);
            target = nr.getPeer(core.transports, core.sessions,
                                core.presentations);
            if (target == null) {
                String s = "Target unreachable.";
                throw new CLIException(s);
            }
          
        } catch (IOException e) {
            String s = "Failed to load necessary server reference: " +
                e.toString();
            core.stop();
            throw new CLIException(s);
        } catch (BadReferenceException e) {
            String s = "Failed to load necessary server reference: " +
                e.toString();
            core.stop();
            throw new CLIException(s);
        }
        
    }

    public String getDescription() {
        return "FNPClient, " + Version.nodeName + ' ' + Version.nodeVersion
               +", protocol " + Version.protocolVersion;
    }

    public Config getOptions() {
        return Core.getConfig();
        
    }
}







