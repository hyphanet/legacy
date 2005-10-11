package freenet.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;

import freenet.node.Node;
import freenet.support.ArrayBucket;
import freenet.support.Bucket;
import freenet.support.io.ReadInputStream;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.QuickSorter;

/** Builds a configuration file from the options registered in Core.config
  */
public class Setup {

    private static Config options = new Config();

    // this has a side effect of making Java initialize the Node class
    // ... anyone know a less hackish way to do this?
    static { 
        Node.class.toString(); 
        options.addOption("expert",0,null, 1);
        options.addOption("silent",0,null, 2);
        options.addOption("update",0,null, 3);
        
        options.shortDesc("expert","Do configuration in expert mode.");
        options.shortDesc("silent","Do configuartion noninteractively.");
        options.shortDesc("update","Update file rather than create new.");
    }
    
    public static void main(String[] args) {

        Params switches = new Params(options.getOptions());
        Params params = new Params(Node.getConfig().getOptions());
        switches.readArgs(args);
        ReadInputStream in = new ReadInputStream(System.in);
        boolean expert = switches.getParam("expert") != null;
        boolean silent = switches.getParam("silent") != null;

        String filename = null;
        if (switches.getNumArgs() < 1) {
            System.out.println(
                 "Usage: freenet.scripts.Setup <filename> [options] [settings]"
            );
            System.out.println("Options:");
            options.printUsage(System.out);
            System.out.println("Settings: ");
            Node.getConfig().printUsage(System.out);
            System.exit(1);
        } else 
            filename = switches.getArg(0);
        
        //Read in existing prefs first
        try {
            params.readParams(filename);
        }
        catch (FileNotFoundException e) {}
        catch (IOException e) {
            System.out.println(e.toString());
            System.exit(1);
        }
        params.readArgs(args);

        Setup setup;
        if (silent)
            setup = new Setup(System.out, new File(filename), params,
                              Node.getConfig().getOptions());
        else
            setup = new Setup(in, System.out, new File(filename), expert, 
                              params, Node.getConfig().getOptions());

        try {
            if (switches.getParam("update") == null)
                setup.dumpConfig();
            else
                setup.updateConfig();
            System.out.println("Setup finished, exiting.");
            System.exit(0);
        } catch (IOException e) {
            System.out.println("Errors occured: " +e);
            System.exit(1);
        }
    }

    /** Output stream to the file */
    //   public  PrintStream out;
    DateFormat df = DateFormat.getDateTimeInstance();
    /** PrintStream to the user */
    private  PrintStream print;
    /** InputStream from the user */
    private  BufferedReader in;
    /** Whether we are in expert mode */
    private  boolean expert = false;
    /** Are we in silent creation mode? */
    private  boolean silent = false;
    /** Load the existing defaults to params */
    private  Params params;
    /** The config file to act on */
    private  File configFile;
    /** The options to use */
    private  Option[] opts;

    /**
     * Create a new interactive Setup object
     * @param  in   The inputstream on which to read user queries.
     * @param  print The printstream on which to write queries/errors.
     * @param configFile The configuration to act on.
     * @param expert  Whether we are in expert mode.
     * @param params  Load the existing defaults to params.
     */
    public Setup(InputStream in, PrintStream print, 
                 File configFile, boolean expert,
                 Params params, Option[] opts) {
        this.in = new BufferedReader(new InputStreamReader(in));
        this.print = print;
        this.configFile = configFile;
        this.expert = expert;
        this.silent = false;
        this.params = params;
        this.opts = opts;
    }

    public Setup(InputStream in, PrintStream print,
                 File configFile, boolean expert,
                 Params params) {
        this(in, print, configFile, expert, params, params.getOptions());
    }

   /**
     * Create a new silent Setup object.
     * @param  print The printstream on which to write errors. 
     * @param config The configuration to act on.
     * @param params  Load the existing defaults to params.
     */
    public Setup(PrintStream print, File config, Params params,
                 Option[] opts) {
        this.print = print;
        this.configFile = config;
        this.expert = false;
        this.silent = true;
        this.params = params;
        this.opts = opts;
    }

    public Setup(PrintStream print, File config, Params params) {
        this(print, config, params, params.getOptions());
    }

    /**
     * Generate a new configuration from the Params given and dump it
     * to the file, overwriting old data.
     */
    public void dumpConfig() throws IOException {
        
        // PrintWriter out = new PrintWriter(new FileOutputStream(configFile));
        Bucket bucket = new ArrayBucket();
        PrintWriter out = new PrintWriter(bucket.getOutputStream());

        println("Freenet Configuration");

        if (!expert) println("Running in simple mode. Some preferences will be skipped.");

        println("You can choose the default preferences by just hitting <ENTER>");
        out.println("[Freenet node]");
        out.println("# Freenet configuration file");
        out.println("# Note that all properties may be overridden from the command line,");
        out.println("# so for example, java freenet.node.Main --listenPort 10000 will cause");
        out.println("# the setting in this file to be ignored");
        out.println();
	out.println("\n\n\n\n\n# * * * * * * * * * * * * * * * * * * * * * * * * * * *\n# *\n");
	out.println("# * ------=== READ THIS!!!	READ THIS!!!	READ THIS!!! =====-------\n");
	out.println("# * \n");
	out.println("# *  	+++++++++       VERY IMPORTANT!!!!!!   +++++++++\n");
	out.println("# * \n");
	out.println("# *   \"#something\"   is a comment!\n");
	out.println("# *   \"%something\"  is ALSO a comment!\n");
	out.println("# * \n");
	out.println("# *\n# *       if you change any settings, REMOVE THE % IN THE BEGINNING OF THE LINE!!!!!\n");
	out.println("# *\n");
	out.println("# * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *\n\n\n\n\n");
        out.println("# Lines that start with \"%\" are settings that have been unchanged from");
        out.println("# default, and that are thus ignored by the node (so they don't linger");
        out.println("# when we want to change the default settings). If you change these");
        out.println("# settings you should remove the \"%\".");
        out.println();

        out.println( "# This file was automatically generated by Freenet.scripts.Setup (at " +
                     df.format(new Date()) + ")" );
        out.println();
        out.println();
        
        println();

        //        Option[] opts = config.getOptions();
        QuickSorter.quickSort(new ArraySorter(opts));

        for (int i=0; i<opts.length; ++i)
            setParam(opts[i], out);

        out.close();

        writeData(bucket);
    }

    /**
     * Reads a configuration from the file, and updates settings according
     * the values in this param object. This preserves comments and most
     * formatting.
     */
    public void updateConfig() throws IOException {

        Bucket bucket = new ArrayBucket();
        PrintWriter out = new PrintWriter(bucket.getOutputStream());
        
        BufferedReader br = new 
            BufferedReader(new 
                InputStreamReader(new 
                    FileInputStream(configFile)));

        String comment = "# changed by freenet.scripts.Setup (at " + 
            df.format(new Date()) + ".)";
        
        String line;
        while ((line = br.readLine()) != null) {
        
            String trimLine = line.trim();
            int j = line.indexOf('=');
            if (trimLine.startsWith("#") || trimLine.length()==0 ||
                trimLine.startsWith("[")) {
                if (!silent)
                    println(trimLine);
                out.println(line);
            } else {
		boolean wasDefault;
		wasDefault = false;
                String key = line.substring(0,j).trim();
                String old = line.substring(j + 1).trim();
                if (key.startsWith("%")) {
		    wasDefault = true;
                    key = key.substring(1).trim(); // remove unChanged comment
		}
                Option opt = params.getOption(key);
                if (opt != null) {
		    if (wasDefault) out.print('%');
                    if (updateParam(opt, old, out)) {
                        out.println(comment);
                        out.print("# was:");
                        out.println(line);
                    } else {
			if(wasDefault)
			    out.println(line.substring(1));
			else
			    out.println(line);
                    }
                } else {                
                    String val = params.getParam(key);
                    if (val != null && 
                        !old.equals(val)) {
			if(wasDefault) out.print('%');
                        out.print(key);
                        out.print('=');
                        out.println(val);
                        out.println(comment);
                        out.print("# was:");
                        out.println(line);
                    } else {
                        out.println(line);
                    }
                }
            }
        }

        out.close();

        writeData(bucket);
    }

    private void writeData(Bucket b) throws IOException {
        InputStream in = b.getInputStream(); 
        OutputStream out = new FileOutputStream(configFile);
        byte[] buffer = new byte[0xffff];
        int i;
        while ((i = in.read(buffer)) != -1) {
            out.write(buffer, 0, i);
        } 
        out.close();
    }

    private void print(String s) {
        if (!silent) print.print(s);
    }
    
    private void println() {
        if (!silent) print.println();
    }
    
    private void println(String s) {
        if (!silent) print.println(s);
    }


    /**
     * reads all buffered input from the user and throws it away
     */
    private  void clearInput() {
        //       while (in.read() > 0)
        //    in.read();
    }

    /**
     * Ends to program in the case of EOF on the user input
     */
    private  void qeof() {
        println("Input interrupted");
        System.exit(1);
    }

    /**
     * Prints a comment to the file, and possibly the user.
     * @param comment The comment to print.
     */
    private  void comment(String[] comment, boolean needsExpert,
                         PrintWriter out) {
        for (int i=0; i<comment.length; ++i) {
            out.println("# " + comment[i]);
            if (expert || !needsExpert) println(comment[i]);
        }
    }

    /**
     * Gets a boolean value from the user.
     * @param query   The text to print as the input query
     * @param dfault  The default value to display
     */
    private boolean getBoolean(String query, boolean dfault) throws UnchangedException {
        try {
            int i;
            print(query + " ");
            do {
                println(dfault ? "[Y/n]" : " [y/N]");
                if (silent) throw new UnchangedException();
                i = in.read();
                if (i < 0)
                    qeof();
            } while (i != 'y' && i != 'Y' && i != 'n' && i != 'N' 
                     && i != '\r' && i != '\n');
            clearInput();
            if (i == '\r' || i == '\n')
                throw new UnchangedException();
            else
                return (i == 'y' || i == 'Y'); 
        } catch (IOException e) {
            qeof();
            throw new UnchangedException();
        }
    }

    /**
     * Gets a String value from the user.
     * @param query The text to print as the input query
     * @param dfault  The default value to display
     */
    private String getString(String query, String dfault) throws UnchangedException {
        try {
            String s;
            println(query + " [" + dfault + ']');
            if (silent) throw new UnchangedException();
            s = in.readLine();
            clearInput();
            if ("".equals(s))
                throw new UnchangedException();
            else
                return s;
        } catch (IOException e) {
            qeof();
            throw new UnchangedException();
        }
    }

    /**
     * Gets an integer number value from the user.
     * @param query The text to print as the input query
     * @param dfault  The default value to display
     */
    private long getNumber(String query, long dfault) throws UnchangedException {
        boolean failed;
        long l = 0;
        do {
            failed = false;
            String s = getString(query, Long.toString(dfault));
            try {
                l = Long.parseLong(s);
            } catch (NumberFormatException e) {
                failed = true;
            }
        } while (failed);
        return l;
    }

    /**
     * Gets a floating point number value from the user.
     * @param query The text to print as the input query
     * @param dfault  The default value to display
     */
    private double getFloating(String query, double dfault) throws UnchangedException {
        boolean failed;
        double d = 0;
        do {
            failed = false;
            String s = getString(query, Double.toString(dfault));
            try {
                d = Double.valueOf(s).doubleValue();
            } catch (NumberFormatException e) {
                failed = true;
            }
        } while (failed);
        return d;
    }


    ///////////////////////////////////////////////////////////////////
    //                                                               //
    //                           Param Methods                       //
    //                                                               //
    ///////////////////////////////////////////////////////////////////
    
    private  void setParam(Option opt, PrintWriter out) {

        int numArgs = opt.numArgs();
        if (numArgs < 1) return;
        
        String name      = opt.name();
        String[] comment = opt.longDesc;
        if (comment == null) {
            String sh = opt.shortDesc;
            comment = new String[] { 
                sh == null ? (name + ": undocumented.") : sh
            };
        }
        
        if(opt.isDeprecated) return;
        if (expert || !opt.isExpert) println("Setting: " + name);
        comment(comment, opt.isExpert, out);
        
        //        Object type = opt.defaultValue();
        Class dc = opt.defaultClass();

        if (Boolean.class.isAssignableFrom(dc))
            setParam(opt, params.getBoolean(name), out);
        else if (Integer.class.isAssignableFrom(dc))
            setParam(opt, params.getInt(name), out);
        else if (Long.class.isAssignableFrom(dc))
            setParam(opt, params.getLong(name), out);
        else if (Float.class.isAssignableFrom(dc))
            setParam(opt, params.getFloat(name), out);
        else if (Double.class.isAssignableFrom(dc))
            setParam(opt, params.getDouble(name), out);
        else if (String.class.isAssignableFrom(dc))
            setParam(opt, params.getString(name), out);
        else if (expert)
            println("Unknown param type '" + dc.getName() 
                    + "', skipping: " + name);

        out.println();
        if (expert || !opt.isExpert) println();
    }

    private boolean updateParam(Option opt, String oldvalue, PrintWriter out) {
        int numArgs = opt.numArgs();
        if (numArgs < 1) return false;

        String name      = opt.name();
        println("Update: " + name);

        Class dc = opt.defaultClass();

        boolean r = false;
        try {
            if (Boolean.class.isAssignableFrom(dc)) {
                boolean def = params.getBoolean(name);
                boolean old = (def ? 
                               !oldvalue.equalsIgnoreCase("false") && 
                               !oldvalue.equalsIgnoreCase("no") : 
                               oldvalue.equalsIgnoreCase("true") || 
                               oldvalue.equalsIgnoreCase("yes"));
                r = updateParam(opt, def, old, 
                                out);
            } else if (Integer.class.isAssignableFrom(dc))
                r = updateParam(opt, params.getInt(name),
                                Integer.parseInt(oldvalue), out);
            else if (Long.class.isAssignableFrom(dc))
                r = updateParam(opt, params.getLong(name),
                                Long.parseLong(oldvalue), out);
            else if (Float.class.isAssignableFrom(dc))
                r = updateParam(opt, params.getFloat(name),
                                Float.valueOf(oldvalue).floatValue(), out);
            else if (Double.class.isAssignableFrom(dc))
                r = updateParam(opt, params.getDouble(name),
                                Double.valueOf(oldvalue).doubleValue(), out);
            else if (String.class.isAssignableFrom(dc)) {
                //  System.err.println(params.getString(name));
                r = updateParam(opt, params.getString(name), oldvalue, out);
            } else if (expert)
                println("Unknown param type '" + dc.getName()+ "', skipping: " 
                        + name);
        } catch (NumberFormatException e) {
        }
        println();
        return r;
    }

    private  void setParam(Option opt, boolean def, PrintWriter out) {
        boolean unSet = !opt.isInstallation();
        try {
            if (expert || !opt.isExpert) {
                def = getBoolean(opt.name(), def);
                unSet = false;
            }                
        } catch (UnchangedException e) {
        }
        out.println((unSet ? "%" : "") + opt.name() + "=" + def);
    }

    private  void setParam(Option opt, int def, PrintWriter out) {
        boolean unSet = !opt.isInstallation();
        try {
            if (expert || !opt.isExpert) {
                def = (int) getNumber(opt.name(), def);
                unSet = false;
            }                
        } catch (UnchangedException e) {
        }
        out.println((unSet ? "%" : "") + opt.name() + "=" + def);
    }

    private  void setParam(Option opt, long def, PrintWriter out) {
        boolean unSet = !opt.isInstallation();
        try {
            if (expert || !opt.isExpert) {
                def = getNumber(opt.name(), def);
                unSet = false;
            }
        } catch (UnchangedException e) {
        }
        out.println((unSet ? "%" : "") + opt.name() + "=" + def);
    }

    private  void setParam(Option opt, float def, PrintWriter out) {
         boolean unSet = !opt.isInstallation();
         try {
             if (expert || !opt.isExpert) {
                 def = (float) getFloating(opt.name(), def);
                 unSet = false;
             }                
         } catch (UnchangedException e) {
         }
         out.println((unSet ? "%" : "") + opt.name() + "=" + def);
    }

    private  void setParam(Option opt, double def, PrintWriter out) {
        boolean unSet = !opt.isInstallation();
        try {
             if (expert || !opt.isExpert) {
                 def = getFloating(opt.name(), def);
                 unSet = false;
             }                
         } catch (UnchangedException e) {
         }
         out.println((unSet ? "%" : "") + opt.name() + "=" + def);
    }

    private  void setParam(Option opt, String def, PrintWriter out) {
        boolean unSet = !opt.isInstallation();
        try {
             if (expert || !opt.isExpert) {
                 def = getString(opt.name(), def);
                 unSet = false;
             }                
         } catch (UnchangedException e) {
         }
        out.println((unSet ? "%" : "") + opt.name() + "=" + def);
    }

    private boolean updateParam(Option opt, boolean def, boolean old,
                              PrintWriter out) {
        if (expert || !opt.isExpert) 
            try {
                def = getBoolean(opt.name(), def);
            } catch (UnchangedException e) {}

        if (def != old) {
            out.println(opt.name() + "=" + def);
            return true;
        }
        return false;
    }

    private boolean updateParam(Option opt, int def, int old, 
                                PrintWriter out) {
        if (expert || !opt.isExpert)
            try {
                def = (int) getNumber(opt.name(), def);
            } catch (UnchangedException e) {}
        if (def != old) {
            //  System.err.println("LALAL" + def + " " + old);
            out.println(opt.name() + "=" + def);
            return true;
        }
        return false;
    }

    private boolean updateParam(Option opt, long def, long old, 
                              PrintWriter out) {
        if (expert || !opt.isExpert)
            try {
                def = getNumber(opt.name(), def);
            } catch (UnchangedException e) {}
        if (def != old) {
            out.println(opt.name() + "=" + def);
            return true;
        }
        return false;
    }

    private boolean updateParam(Option opt, float def, float old, 
                                PrintWriter out) {
        if (expert || !opt.isExpert)
            try {
                def = (float) getFloating(opt.name(), def);
            } catch (UnchangedException e) {}

        if (def != old) {
            out.println(opt.name() + "=" + def);
            return true;
        }
        return false;
    }

    private boolean updateParam(Option opt, double def, double old, 
                                PrintWriter out) {
        if (expert || !opt.isExpert)
            try {
                def = getFloating(opt.name(), def);
            } catch (UnchangedException e) {}
        if (def != old) {
            out.println(opt.name() + "=" + def);
            return true;
        }
        return false;
    }

    private boolean updateParam(Option opt, String def, String old,
                                PrintWriter out) {
        if (expert || !opt.isExpert)
            try {
                def = getString(opt.name(), def);
            } catch (UnchangedException e) {}
	if(def == null) def = "null";
        if (!old.equals(def)) {
            out.println(opt.name() + "=" + def);
            return true;
        }
        return false;
    }

    private class UnchangedException extends Exception {
    }

}


