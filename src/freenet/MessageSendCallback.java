package freenet;

public interface MessageSendCallback {
        
	/** Must be called BEFORE the terminal success() or thrown() */
	void setTrailerWriter(TrailerWriter tw);
    
	void thrown(Exception e);
	void succeeded();
}
