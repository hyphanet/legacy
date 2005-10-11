package freenet.client;

import freenet.Address;
import freenet.Authentity;
import freenet.Core;
import freenet.DSAAuthentity;
import freenet.OpenConnectionManager;
import freenet.Peer;
import freenet.Presentation;
import freenet.PresentationHandler;
import freenet.SessionHandler;
import freenet.Ticker;
import freenet.TransportHandler;
import freenet.crypt.Global;
import freenet.message.Accepted;
import freenet.message.DataInsert;
import freenet.message.DataNotFound;
import freenet.message.DataReply;
import freenet.message.InsertReply;
import freenet.message.QueryAborted;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.StoreData;
import freenet.message.VoidMessage;
import freenet.node.NodeReference;
import freenet.presentation.FNPRawMessage;
import freenet.session.LinkManager;
import freenet.thread.FastThreadFactory;
import freenet.thread.ThreadFactory;

/**
 * This is an implementation of Core the for use by the clients. It is 
 * runnable, and when started will listen for connections, feeding them
 * to a ClientMessageHandler.
 *
 * @author oskar
 **/

public class ClientCore extends Core {

    public ClientMessageHandler cmh;
    public Peer me;

    /* Clients are always static. */
    public boolean getTransience() {return true;}

    public NodeReference getNodeReference() {
        return new NodeReference(me, null, null);
    }
    
      /**
     * Create a new ClientCore.
     * @param myAddress  The address (port) that this client should listen for
     *                   messages on.
     * @param lm         The linkmanager with which to manage sessions.
     * @param p          The presentation protocol to use for this client.
     */
    public static ClientCore newInstance(Address myAddress, 
                                         LinkManager lm, Presentation p) {

        DSAAuthentity auth = new DSAAuthentity(Global.DSAgroupC, 
                                               Core.getRandSource());
        return newInstance(auth, myAddress, lm, p);
    }

   /**
     * Create a new ClientCore.
     * @param privKey    The private key to use for communication
     * @param myAddress  The address (port) that this client should listen for
     *                   messages on.
     * @param lm         The linkmanager with which to manage sessions.
     * @param p          The presentation protocol to use for this client.
     */
    public static ClientCore newInstance(Authentity privKey, 
                                         Address myAddress, 
                                         LinkManager lm, Presentation p) {
        
        TransportHandler th = new TransportHandler();
        th.register(myAddress.getTransport());
        SessionHandler sh = new SessionHandler();
        sh.register(lm, 100);
        PresentationHandler ph = new PresentationHandler();
        ph.register(p, 100);
        Peer me = new Peer(privKey.getIdentity(), myAddress, lm, ph.getDefault());
        return new ClientCore(privKey, th, sh, ph, me);
                              /* New plan - clients have no interfaces
                              new Interface[] {
                                  new StandardInterface(myAddress.listenPart(),
                                                        sh, ph, 0, 3)},
                              */
    }

    private ClientCore(Authentity privKey,
                       TransportHandler th, SessionHandler sh, 
                       PresentationHandler ph, Peer me) {
        
        super(privKey, me.getIdentity(), th, sh, ph); 
        this.me = me; 
        this.cmh = new ClientMessageHandler(this);

        cmh.addType( FNPRawMessage.class, 
                     VoidMessage.messageName, 
                     VoidMessage.class);
        cmh.addType( FNPRawMessage.class, 
                     DataReply.messageName,      
                     DataReply.class      );
        cmh.addType( FNPRawMessage.class, 
                     DataNotFound.messageName,   
                     DataNotFound.class   );
        cmh.addType( FNPRawMessage.class, 
                     QueryRejected.messageName,  
                     QueryRejected.class  );
        cmh.addType( FNPRawMessage.class, 
                     QueryAborted.messageName,   
                     QueryAborted.class   );
        cmh.addType( FNPRawMessage.class, 
                     QueryRestarted.messageName, 
                     QueryRestarted.class );
        cmh.addType( FNPRawMessage.class, 
                     StoreData.messageName,      
                     StoreData.class      );
        cmh.addType( FNPRawMessage.class, 
                     InsertReply.messageName,    
                     InsertReply.class    );
        cmh.addType( FNPRawMessage.class, 
                     Accepted.messageName,       
                     Accepted.class       );
        cmh.addType( FNPRawMessage.class, 
                     DataInsert.messageName,     
                     DataInsert.class     );

    }

    /**
     * Starts the client listening for Connections and Messages.
     */
    public void acceptConnections() {
        ThreadGroup tg = new ThreadGroup(toString());
        tg.setMaxPriority(Thread.NORM_PRIORITY);
        ThreadFactory tf = new FastThreadFactory(tg, 100);

        // REDFLAG: 20 ok? What value makes sense here?
        OpenConnectionManager ocm = new OpenConnectionManager(20, 1.0, null);
        
        begin(new Ticker(cmh, tf), ocm, null, false);
        join();
    }

    public String toString() {
        return "Client core serving: " + cmh;
    }
}


