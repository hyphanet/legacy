package freenet.node.states.FCP;

import freenet.*;
import freenet.node.*;
import freenet.message.client.*;
import freenet.support.Logger;
import freenet.client.*;

public class NewGenerateSVKPair extends NewClientRequest {

    public NewGenerateSVKPair(long id, PeerHandler source) {
        super(id, source);
    }
    
    public String getName() {
        return "New Client GenerateSVKPair";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof GenerateSVKPair))
            throw new BadStateException("expecting GenerateSVKPair");
	ClientSVK svk;
	try {
	    svk = new ClientSVK(Core.getRandSource());
	} catch (KeyException e) {
	    Core.logger.log(this, "KeyException creating ClientSVK: "+e, e, Logger.ERROR);
	    sendMessage(new Failed(id, "Internal Error: KeyException creating ClientSVK: "+e));
	    return null;
	}
        FieldSet fs = new FieldSet();
        fs.put("PrivateKey", Base64.encode(svk.getPrivateKey()));
        fs.put("PublicKey",  Base64.encode(svk.getPublicKeyFingerPrint()));
	byte[] cryptoKey = new byte[16]; // 64 bits should be plenty
	Core.getRandSource().nextBytes(cryptoKey);
	fs.put("CryptoKey",  Base64.encode(cryptoKey));
        sendMessage(new Success(id, fs));
        return null;
    }
}

