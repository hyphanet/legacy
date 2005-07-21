/*
 * Read a file of FOAF triplets, and find the largest (optionally 
 * bidirectionally) connected subsets.
 */
package freenet.node.simulator.whackysim;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;

public class FOAFProcessor {

    static Random r = new Random();
    
    public static class Actor {

        public Actor(String name2) {
            this.name = name2;
            links = new LinkedList();
        }
        String name;
        LinkedList links;

        public void connect(Actor a2) {
            links.add(a2);
        }
        
    }
    
    static Hashtable actors = new Hashtable();
    
    public static void main(String[] args) throws IOException {
        readData();
        doStats();
        while(actors.size() > 0) {
            removeConnectedSet();
        }
    }

    /**
     * Find a connected set of Actor's, (or just one
     * unconnected actor), and remove it.
     * Note that the algorithm requires they be bidirectionally
     * connected. It cannot be directly adapted to support one way
     * links - you'd have to preprocess actors to turn unidi links
     * into bidi links.
     */
    private static void removeConnectedSet() {
        Actor a = randomActor();
        System.out.println("Starting at "+a);
        HashSet connectedSet = new HashSet();
        LinkedList toCheck = new LinkedList();
        toCheck.add(a);
        connectedSet.add(a);
        while(!toCheck.isEmpty()) {
            Actor checking = (Actor) (toCheck.removeFirst());
            System.out.println("Checking "+checking);
            Iterator i = checking.links.iterator();
            while(i.hasNext()) {
                Actor connected = (Actor) (i.next());
                System.out.println("Connected: "+connected);
                if(!connected.links.contains(checking)) continue;
                System.out.println("Bidi connected: "+connected);
                if(!connectedSet.contains(connected)) {
                    connectedSet.add(connected);
                    toCheck.add(connected);
                    System.out.println("Added "+connected+" now connectedSet: "+connectedSet.size()+", toCheck: "+toCheck.size());
                }
            }
        }
        for(Iterator i=connectedSet.iterator();i.hasNext();) {
            Actor toDelete = (Actor) i.next();
            actors.remove(toDelete);
            System.err.println("Deleted "+toDelete+", set now "+actors.size());
        }
        System.err.println("Connected set size "+connectedSet.size()+", remaining: "+actors.size());
    }

    private static Actor randomActor() {
        Object[] actorArray = actors.values().toArray();
        Actor a = (Actor) actorArray[r.nextInt(actorArray.length)];
        return a;
    }

    private static void readData() throws IOException {
        FileInputStream fis = new FileInputStream("foaf.20040303.n3");
        InputStreamReader ris = new InputStreamReader(fis);
        BufferedReader br = new BufferedReader(ris);
        String line = null;
        while((line = br.readLine()) != null) {
            if(line.charAt(line.length()-1) == '.')
                line = line.substring(0, line.length()-1);
            String[] separated = line.split(" ");
            if(separated[1].equals("<http://xmlns.com/foaf/0.1/knows>")) {
                String actor1 = zapQuotes(separated[0]);
                String actor2 = zapQuotes(separated[2]);
                Actor a1 = makeActor(actor1);
                Actor a2 = makeActor(actor2);
                a1.connect(a2);
                System.out.println("Connecting "+a1+" to "+a2);
            }
        }
    }

    /**
     * @param string
     * @return
     */
    private static String zapQuotes(String string) {
        if(string.length() > 2 && string.charAt(0) == '"' &&
                string.charAt(string.length()-1) == '"')
            return string.substring(1, string.length()-2);
        return string;
    }

    private static void doStats() {
        System.err.println("Number of actors: "+actors.size());
        Iterator i = actors.values().iterator();
        long totalLinks = 0;
        int maxLinks = 0;
        int minLinks = Integer.MAX_VALUE;
        while(i.hasNext()) {
            Actor a = (Actor) i.next();
            int linkCount = a.links.size();
            totalLinks += linkCount;
            if(maxLinks < linkCount) maxLinks = linkCount;
            if(minLinks > linkCount) minLinks = linkCount;
        }
        System.err.println("Min links: "+minLinks);
        System.err.println("Max links: "+maxLinks);
        System.err.println("Average links: "+((double)totalLinks)/actors.size());
    }

    private static Actor makeActor(String name) {
        Actor a = (Actor) (actors.get(name));
        if(a != null) return a;
        System.err.println("Adding actor "+actors.size()+": " +name);
        a = new Actor(name);
        actors.put(name, a);
        return a;
    }
}
