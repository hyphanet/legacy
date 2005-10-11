package freenet.diagnostics;
import java.io.PrintWriter;

import junit.framework.TestCase;
import freenet.FieldSet;
import freenet.support.LoggerHookChain;
import freenet.support.Fields;
import freenet.support.io.NullOutputStream;
/**
 * A unit test class containing some tests of the Freenet diagnostics
 * module.
 *
 * @author oskar
 */

public class DiagnosticsTest extends TestCase {

    public DiagnosticsTest(String name) {
        super(name);
    }

    private StandardDiagnostics diag;

    public void setUp() {
        Exception e = null;
        try {
            diag = new StandardDiagnostics(new LoggerHookChain(),"");
        } catch (Exception t) {
            e = t;
        }
        assertNotNull("Testing diagnostics construction:", diag);
        assertNull("Testing no exception was thrown:", e);
    }

    public void testGeneral() {
        assertEquals("Testing minute length",Diagnostics.getPeriod(Diagnostics.MINUTE),
                     60 * 1000);
        assertEquals("Testing hour length",Diagnostics.getPeriod(Diagnostics.HOUR),
                     60 * 60 * 1000);
        assertEquals("Testing day length",Diagnostics.getPeriod(Diagnostics.DAY),
                     24 * 60 * 60 * 1000);

        // I can't do much testing here. It runs on the system clock, so
        // there is no way to do a fastforward. I'll just do some things
        // to make sure they don't through errors.
        diag.registerBinomial("testBin",Diagnostics.MINUTE, "Test", null);
        diag.registerContinuous("testCont",Diagnostics.HOUR, "Test", null);
        diag.registerCounting("testCount",Diagnostics.HOUR, "Test", null);

        diag.occurrenceBinomial("testBin",10, 1);
        try {
            diag.occurrenceBinomial("testBin",1,10); 
            diag.occurrenceBinomial("testCount",1,10);
        } catch (Exception e) {
            fail("Exception not expected on faulty occurrence calls.");
        }

        diag.occurrenceCounting("testCount",-100);
        diag.occurrenceCounting("testCount",1000);
        
        diag.occurrenceContinuous("testCont",-0.0199);
        diag.occurrenceContinuous("testCont",.02);

        diag.aggregateVars();
        
        diag.writeVars(new PrintWriter(new NullOutputStream()),
                       new FieldSetFormat());
        
    }

    public void testRandomVar() {
        Binomial brv = new Binomial(diag,"test", Diagnostics.MINUTE,
                                    "test");
        brv.add(1000, 6,4);
        brv.add(4000, 10,1);
        brv.endOf(Diagnostics.MINUTE, 60001, 60000);
        FieldSet fs1 = FieldSetFormat.toFieldSet(brv);
        // System.out.println(fs1.toString());
        assertEquals("Testing output type",fs1.get("Type"),brv.getType());
        assertEquals("Testing period name",fs1.get("AggPeriod"),
                     StandardDiagnostics.getName(Diagnostics.MINUTE));
        FieldSet occ = fs1.getSet("occurrences");
        assertNotNull("Testing that occurrences are returned",occ);
        assertNotNull("Testing that occurrence at 1000 exists",
                      occ.get("1000"));
        assertNotNull("Testing that occurrence at 4000 exists;",
                      occ.get("4000")); 
        FieldSet minutes = 
            fs1.getSet(StandardDiagnostics.getName(Diagnostics.MINUTE));
        assertNotNull("Testing that minute aggregation exists",minutes);
        String agg = minutes.getString("60000");
        assertNotNull("Testing that minute at 60000 exists",
                      agg);
        brv.add(80000, 10, 1);
        FieldSet fs2 = FieldSetFormat.toFieldSet(brv);
        occ = fs2.getSet("occurrences");
        //System.err.println(occ);
        assertNotNull("Testing that occurrences are returned still",occ);
        assertNull("Testing that occurrence at 1000 is gone",
                   occ.get("1000"));
        assertNull("Testing that occurrence at 4000 is gone",
                   occ.get("4000"));
        assertNotNull("Testing that occurrence at 80000 exists",
                      occ.get("80000"));

    }

    public void testBinomial() {
        Binomial rv = new Binomial(diag,"test", Diagnostics.MINUTE,
                                   "test");
        rv.add(1000, 6,4);
        rv.add(4000, 10,1);
        rv.endOf(Diagnostics.MINUTE, 60001, 60000);
        FieldSet fs1 = FieldSetFormat.toFieldSet(rv);
        FieldSet minutes = 
            fs1.getSet(StandardDiagnostics.getName(Diagnostics.MINUTE));
        assertNotNull("Testing that minute aggregation exists",minutes);
        String agg = minutes.getString("60000");
        String[] fs = Fields.commaList(agg);
        assertEquals("Testing field format",fs.length,2);
        assertEquals("Testing n aggregation",Long.parseLong(fs[1]),6+10);
        assertEquals("Testing X aggregation",Long.parseLong(fs[0]),4+1);
    }

    public void testContinuous() {
        Continuous rv = new Continuous(diag,"test", Diagnostics.MINUTE,
                                       "test");
        rv.add(1000, 100.0);
        rv.add(4000, 200.0);
        rv.endOf(Diagnostics.MINUTE, 60001, 60000);
        FieldSet fs1 = FieldSetFormat.toFieldSet(rv);
        FieldSet minutes = 
            fs1.getSet(StandardDiagnostics.getName(Diagnostics.MINUTE));
        assertNotNull("Testing that minute aggregation exists",minutes);
        String agg = minutes.getString("60000");
        String[] fs = Fields.commaList(agg);
        assertEquals("Testing field format",fs.length,3);
        assertEquals("Testing X aggregation",
                     Double.valueOf(fs[0]).doubleValue(),
                     100.0 + 200.0, 0.001);
        assertEquals("Testing X? aggregation",
                     Double.valueOf(fs[1]).doubleValue(),
                     100.0*100.0 + 200.0*200.0, 0.001);
        assertEquals("Testing N aggregation",Long.parseLong(fs[2]),
                     2);
    }

    public void testCounting() {
        CountingProcess rv = new CountingProcess(diag,"test", 
                                                 Diagnostics.MINUTE, "test");
        rv.add(1000, 5);
        rv.add(4000, 10);
        rv.add(20000,-6);
        rv.endOf(Diagnostics.MINUTE, 60001, 60000);
        FieldSet fs1 = FieldSetFormat.toFieldSet(rv);
        FieldSet minutes = 
            fs1.getSet(StandardDiagnostics.getName(Diagnostics.MINUTE));
        assertNotNull("Testing that minute aggregation exists",minutes);
        String agg = minutes.getString("60000");
        String[] fs = Fields.commaList(agg);
        assertEquals("Testing field format",fs.length,1);
        assertEquals("Testing N aggregation",Long.parseLong(fs[0]),
                     5+10-6);
    }


    public void tearDown() {
        diag = null;
    }


}
