package freenet.client.events;

import freenet.client.ClientEvent;
import freenet.client.metadata.MetadataPart;

/**
 * @author matburnham
 * @version
 */

public class RedirectFollowedEvent implements ClientEvent{
    MetadataPart metadataPart; 
   
    public RedirectFollowedEvent(MetadataPart mdp) {
        metadataPart = mdp;
    }
  
    public String getDescription() {
        return "RedirectFollowedEvent ";
    }

    public int getCode() {
        return 226; // no idea what i should put here!
    }
   
    public MetadataPart getMetadataPart() {
        return metadataPart;
    }


}
