/*
 * Download all the friend-list data from livejournal
 * via the most efficient interface available.
 */
package freenet.node.simulator.whackysim;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;

public class LiveJournalScraper {

    static HashSet fetchedNames = new HashSet();
    static HashSet pendingNames = new HashSet();
    static Socket mySocket;
    static OutputStream outStream;
    static InputStream inStream;
    
    public static void main(String[] args) throws UnknownHostException, FileNotFoundException {
        readInitialFetchedNames();
        readInitialPendingNames();
        long startTime = System.currentTimeMillis();
        int initialFetched = fetchedNames.size();
        if(pendingNames.size() == 0) {
            System.err.println("No pending names to search, aborting");
            return;
        }
        connect();
        while(pendingNames.size() > 0) {
            String name = (String) (pendingNames.iterator().next());
            String status = "Fetching "+name+", fetched "+fetchedNames.size()+", pending "+pendingNames.size();
            long now = System.currentTimeMillis();
            int fetched = fetchedNames.size() - initialFetched;
            if(fetched > 0) {
                int timePerFetch = (int)((now-startTime) / fetched);
                status += ", fetch time: "+timePerFetch+", ETA: "+formatTime(pendingNames.size()*timePerFetch);
            }
            System.err.println(status);
            long sleepTime = 1000;
            while(true) {
                try {
                    fetch(name);
                    break;
                } catch (IOException e) {
                    System.err.println("Caught "+e);
                    e.printStackTrace();
                    // Temporary condition? Exponential backoff
                    try {
                        Thread.sleep(sleepTime);
                        try {
                            mySocket.close();
                        } catch (IOException e2) {
                        }
                        connect();
                    } catch (InterruptedException e1) {
                    }
                    sleepTime += sleepTime;
                }
            }
        }
    }

    private static String formatTime(long x) {
        if(x < 10000) return Long.toString(x)+"ms";
        x /= 1000;
        if(x < 60) return Long.toString(x)+"s";
        if(x < 3600)
            return Long.toString(x/60) + ":" + Long.toString(x%60);
        if(x < 86400)
            return Long.toString(x/3600) + ":" + Long.toString((x/60) % 60) + ":" + Long.toString(x % 60);
        return Long.toString(x/86400) + "d "+Long.toString((x/3600) % 24)+
        	":"+Long.toString((x/60) % 60) + ":" + Long.toString(x % 60);
    }

    private static void connect() throws UnknownHostException {
        InetAddress addr;
        addr = InetAddress.getByName("www.livejournal.com");
        int x = 1000;
        while(true) {
            try {
                mySocket = new Socket(addr, 80);
                System.err.println("Connected to www.livejournal.com");
                outStream = mySocket.getOutputStream();
                inStream = mySocket.getInputStream();
                return;
            } catch (IOException e) {
                System.err.println("Failed to download: "+e);
                x *= 2;
            }
            try {
                Thread.sleep(x);
            } catch (InterruptedException e1) {
                // Ignore
            }
        }
        
    }

    static StringBuffer fullHeaders = new StringBuffer();
    
    /**
     * Fetch a friend datum
     * @param name
     */
    private static void fetch(String name) throws IOException {
        String request =
            "GET /misc/fdata.bml?user="+name+" HTTP/1.1\r\n"+
            "Host: www.livejournal.com\r\n"+
            "Connection: keep-alive\r\n"+
            "User-Agent: toadljfetch/0.1; toad@amphibian.dyndns.org\r\n\r\n";
        byte[] reqBytes = request.getBytes();
        fullHeaders.setLength(0);
//        try {
            outStream.write(reqBytes);
            // Catch the response
            int ctr = 0;
            byte[] lineBuf = new byte[4096];
            boolean firstLine = true;
            int contentLength = -1;
            while(true) {
                int x = inStream.read();
                if(x == -1) throw new IOException("EOF");
                lineBuf[ctr++] = (byte) x;
                if(x == '\n') {
                    // New line
                    String line = new String(lineBuf, 0, ctr);
                    while(line.length()>0 && (line.charAt(line.length()-1) == '\n' || line.charAt(line.length()-1) == '\r')) {
                        if(line.length() == 1) line = "";
                        else line = line.substring(0, line.length()-2);
                    }
                    fullHeaders.append(line).append('\n');
                    if(line.length() == 0) { // \r\n
                        // End of header
                        if(contentLength == -1) {
                            System.err.println(fullHeaders);
                            throw new Error("No content-length");
                        }
                        break;
                    }
                    //System.err.println("Got line: "+line);
                    if(firstLine) {
                        // Parse first line
                        if(line.startsWith("HTTP/1.0 200 OK")) {
                            //System.err.println("OK: "+line);
                        } else throw new IOException("Unexpected response: "+line);
                    }
                    line = line.toLowerCase();
                    if(line.startsWith("content-length: ")) {
                        line = line.substring("content-length: ".length());
                        contentLength = Integer.parseInt(line);
                        //System.err.println("content-length: "+contentLength);
                    }
                    firstLine = false;
                    ctr = 0;
                }
            }
            // Read the entire block into one byte array
            int ptr = 0;
            byte[] buf = new byte[contentLength];
            while(ptr < buf.length) {
                int x = inStream.read(buf, ptr, contentLength-ptr);
                if(x < 0)
                    throw new Error("Could not read full block: Read "+ptr+" of "+contentLength);
                ptr += x;
            }
            FileOutputStream fos = new FileOutputStream("fetched/"+name);
            fos.write(buf);
            fos.close();
            processData(name, buf);
    }

    /**
     * Process data fetched from fdata
     * Does not write it to disk, just processes it,
     * then removes it from pending and adds it to fetched.
     */
    private static void processData(String name, byte[] buf) {
        String data = new String(buf);
        String[] lines = data.split("\n");
        for(int i=0;i<lines.length;i++) {
            String line = lines[i];
            if(line.startsWith("#")) continue;
            if(line.startsWith("> ")) {
                String newName = line.substring(2);
                //System.err.println("Got name: "+newName);
                if(fetchedNames.contains(newName)) continue;
                if(pendingNames.contains(newName)) continue;
                if(newName.indexOf(File.separatorChar) >= 0) {
                    System.err.println("Illegal character in name: "+newName);
                    System.exit(2);
                }
                pendingNames.add(newName);
            }
        }
        fetchedNames.add(name);
        pendingNames.remove(name);
    }

    /**
     * Read initial names to search for
     */
    private static void readInitialPendingNames() throws FileNotFoundException {
        FileInputStream fis = new FileInputStream("seednames.txt");
        InputStreamReader r = new InputStreamReader(fis);
        BufferedReader br = new BufferedReader(r);
        String nameRead;
        try {
            while((nameRead = br.readLine()) != null) {
                pendingNames.add(nameRead);
            }
        } catch (IOException e) {
            return;
        }
        
    }

    /**
     * Read already saved data
     */
    private static void readInitialFetchedNames() {
        File f = new File("fetched");
        if(!f.exists()) {
            f.mkdir();
        }
        // Files are named by the username only
        String[] files = f.list();
        System.err.println("Reading files: "+files.length);
        for(int i=0;i<files.length;i++) {
            File f1 = new File("fetched/"+files[i]);
            if(f.length() > 0) {
                try {
                    readFriendData(files[i], f1);
                } catch (IOException e) {
                    f1.delete();
                }
            }
        }
    }

    private static void readFriendData(String string, File f1) throws IOException {
        FileInputStream f = new FileInputStream(f1);
        int length = (int) f1.length();
        int ptr = 0;
        byte[] buf = new byte[length];
        while(ptr < buf.length) {
            int x;
            x = f.read(buf, ptr, length-ptr);
            if(x < 0)
                throw new Error("Could not read full block: Read "+ptr+" of "+length);
            ptr += x;
        }
        f.close();
        processData(string, buf);
    }
}
