package sootparser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import soot.*;
import soot.Scene;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.callgraph.ReachableMethods;
import soot.options.Options;
import soot.util.Chain;
import soot.util.queue.QueueReader;
import sootparser.utils.Logger;
import soot.jimple.internal.JDynamicInvokeExpr;

public class SootDriver {

    public static void main (String[] args) {
        Logger.log("Instrummenting Java/Scala file");
        // specifies soot options for handling JAR/class file
        setSootOptions(extractInput(args));
        // adding our analysis to soot's pipeline
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.phases", new SceneTransformer() {
                protected void internalTransform(String phaseName,
                  Map options) {

                    Logger.log("Building Call Graph");
                    // getting call graph from soot scene
                    CallGraph cg = Scene.v().getCallGraph();
                    Set<SootClass> ths = findUnThreads();
                    extendCallGraph(cg, ths);

                    // initiating our analysis and instrumentation
                    StaticAnalyzer analyzer = new StaticAnalyzer(cg);
                    // running analysis (feature extraction)
                    analyzer.run();
                    analyzer.printFeaturesMap();
                    //analyzer.dumpIR();
                }
            }));

            Logger.log("Running Soot ...");
            Scene.v().addBasicClass("java.io.PrintStream", SootClass.BODIES);
            soot.Main.main(args);
    }

    /**
     * Checks if input class is member of a library package.
     * @param sclass Input class.
     * @return true if class is member of a library package, false otherwise.
     */
    private static boolean isLibraryClass (SootClass sclass) {
        String pack = sclass.getPackageName();
        String[] libs = {"java.", "jdk.", "soot.","sun.", "oracle.", "scala."};

        for (String lib : libs) {
            if (pack.startsWith(lib)) {
                return true;
            }
        }

        return false;
    }

    private static Set<SootClass> findUnThreads () {
        FastHierarchy h = Scene.v().getOrMakeFastHierarchy();
        Set<SootClass> scs = new HashSet<SootClass>();
        for (SootClass sclass : Scene.v().getApplicationClasses()) {
            if (!isLibraryClass(sclass)) {
                if (sclass.implementsInterface("java.lang.Runnable") ||
                        h.isSubclass(sclass, Scene.v().getSootClass("java.lang.Thread"))) {
                    scs.add(sclass);
                }
            }
        }
        return scs;
    }

    private static void extendCallGraph (CallGraph cg, Set<SootClass> set) {
        for (SootClass sclass : Scene.v().getApplicationClasses()) {
            if (!isLibraryClass(sclass)) {
                for (SootMethod sm : sclass.getMethods()) {
                    simpleCallGraphExtension(cg, sm, set);
                }
            }
        }
    }

    /**
     * Extends the Call Graph with missing edges
     * @param cg Original Call Graph
     * @param entryPoint Method to be analyzed
     */
    private static void simpleCallGraphExtension (CallGraph cg, SootMethod entryPoint, Set<SootClass> set) {
        if (entryPoint == null)
            entryPoint = Scene.v().getMainMethod();

        Chain<Unit> units;
        try {
             units = entryPoint.retrieveActiveBody().getUnits();        

        List<SootMethod> nexts = new ArrayList<SootMethod>();

        for (Unit u : units) {
            if (u instanceof Stmt) {
                Stmt s = (Stmt) u;
                if (s.containsInvokeExpr()) {
                    InvokeExpr in = s.getInvokeExpr();
                    //System.out.println("Hey > " + in.getMethod().getSignature());
                    SootClass sclass = entryPoint.getDeclaringClass();
                    //
                    // Special Handler for Executor submit
                    if (in.getMethod().getSignature().equals(
                    "<java.util.concurrent.ExecutorService: java.util.concurrent.Future submit(java.lang.Runnable)>"))
                    {
                        Value arg0 = in.getArg(0);
                        Type t = arg0.getType();
                        if (t instanceof RefType) {
                            RefType rt = (RefType) t;
                            SootClass sc = rt.getSootClass();
                            SootMethod sm = sc.getMethod("void run()");
                            Edge e = new Edge(entryPoint, s, sm);
                            cg.addEdge(e);
                            nexts.add(sm);
                        }
                    } else if (in.getMethod().getSignature().equals(
                            "<java.lang.Thread: void start()>")) {
                        for (SootClass tclass : set) {
                            SootMethod sm = tclass.getMethod("void run()");
                            if (!sm.equals(entryPoint)){
                                Edge e = new Edge(entryPoint, s, sm);
                                cg.addEdge(e);
                                nexts.add(sm);
                                System.out.println("added edge from " + entryPoint.getSignature() + " to "
                                   + sm.getSignature());
                            }
                        }

                    } else {
                        Iterator it = cg.edgesOutOf(u);
                        //
                        // avoid adding extra edges to units with outgoing edges
                        if (!it.hasNext()) {
                            try {
                                Edge e = new Edge(entryPoint, s, in.getMethod());
                                cg.addEdge(e);
                                nexts.add(in.getMethod());
                            } catch (RuntimeException e) {
                                System.out.println(" Ignoring edge for  :: " + s.getInvokeExpr().getClass().toString());
                            }
                        }
                    }
                }
            }
        }

        for (SootMethod s : nexts) {
            if (!s.getDeclaringClass().getPackageName().startsWith("java."))
                simpleCallGraphExtension(cg, s, set);
            
        }

        } catch (Exception e) {
            return;
        }
    }

    private static String[] extractInput (String[] args) {
        return args;
    }

    /**
     * Sets soot parameters for handling java class/jar files.
     * @param input Path for input directory, class or jar file.
     */
    private static void setSootOptions (String[] input) {
        Options.v().set_whole_program(true);
        Options.v().set_verbose(false);
        Options.v().set_src_prec(Options.src_prec_class);
        Options.v().set_output_format(Options.output_format_jimple);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_xml_attributes(false);
        Options.v().set_force_overwrite(true);
        Options.v().setPhaseOption("jb","use-original-names:true");
        Options.v().setPhaseOption("jb","preserve-source-annotations:true");
        // results in faster call graphs, exclude libraries
        Options.v().set_no_bodies_for_excluded(true);
        //Options.v().set_process_dir(Collections.singletonList(input));
        //Options.v().set_java_version(6);
        Options.v().setPhaseOption("cg.spark", "on");
        Options.v().set_no_writeout_body_releasing(true);
    }
}