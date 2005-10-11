package freenet.client.cli;

import freenet.Core;
import freenet.config.Params;

public class Main {

    public static void main(String[] args) {
        try {
            Params p = new Params();
            p.readArgs(args);
            CLI client = new CLI(p);
            boolean r = client.execute();
            client.stop();
            Core.closeFileLogger();
            waitForThreadsAndExit(r ? 0 : 1); 
        } catch (CLIException e) {
            System.err.println(e.getMessage());
        }
    }

    /**
     * Waits for all non-daemon threads to finish and then exits with the
     * exit state given in <b>exitState</b> (why doesn't java have this 
     * function??)
     */
    public static void waitForThreadsAndExit(int exitState) {
    	Thread[] ts = new Thread[Thread.activeCount()];
        int n = Thread.enumerate(ts);
        for (int i = 0; i < n ; i++) {
            if (!ts[i].isDaemon() && ts[i] != Thread.currentThread()) {
                try {
                    ts[i].join();
                } catch (InterruptedException e) {
                }
            }
        }
        System.exit(exitState);
    }

}
