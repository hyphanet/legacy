package freenet.node.simulator.newsim;

/**
 * Context object for a request
 */
public class RequestContext extends BaseContext {

    private Node dataSource = null;
    int hopsSinceReset = 0;
    
    public RequestContext(int htl) {
        super(htl);
    }

    /**
     * Set the data source
     */
    public void setDataSource(Node node) {
        dataSource = node;
    }

    public Node getDataSource() {
        return dataSource;
    }

    public void stepDataSource(Node node) {
        hopsSinceReset++;
        if(node.sim.r.nextInt(20) == 0) {
            // Reset
            dataSource = node;
        }
    }
    
}
