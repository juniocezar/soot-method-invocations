package sootparser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import soot.Body;
import soot.DoubleType;
import soot.FastHierarchy;
import soot.IntType;
import soot.Local;
import soot.LongType;
import soot.PatchingChain;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.baf.BafBody;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.toolkits.annotation.logic.Loop;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.callgraph.ReachableMethods;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.LoopNestTree;
import soot.toolkits.graph.UnitGraph;
import soot.util.Chain;
import soot.util.queue.QueueReader;
import sootparser.utils.ConsoleColors;
import sootparser.utils.Logger;

/**
 * Parses CallGraph, extracts method invocations and build
 * a map <SootMethod, Features>.
 * @author juniocezar
 */
public class StaticAnalyzer {
    private CallGraph cg;
    private Map<SootMethod, Features> featuresLibMap;
    private Map<SootMethod, Features> featuresMap;
    private Map<SootMethod, Features> propagatedFeaturesMap;
    private boolean debug = true;

    public StaticAnalyzer (CallGraph cg) {
        this.cg = cg;
        featuresMap = new HashMap<SootMethod, Features> ();
        featuresLibMap = new HashMap<SootMethod, Features> ();
        propagatedFeaturesMap = new HashMap<SootMethod, Features> ();
    }

    public void run () {
        //
        // 1 - Iterate over all application classes / methods calculate
        // how many methods they call
        generalFeatureExtraction();

        //
        // 2 - Traverse the Call Graph and propagate call stack counter
        propagateFeatures();
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

    /**
     * Iterates over all application classes and methods, and create an
     * Feature object for each method.
     */
    private void generalFeatureExtraction () {
        Logger.log("Counting how many calls each method does");
        Chain<SootClass> classes = Scene.v().getApplicationClasses();
        try {
            for (SootClass sclass : classes) {
                if (!isLibraryClass(sclass)) {
                    System.out.println(ConsoleColors.RED_UNDERLINED + "\n\n 🔍🔍 Checking invocations in " +
                    sclass.getName() + " 🔍🔍 " + ConsoleColors.RESET);
                    List<SootMethod> methods = sclass.getMethods();
                    for (SootMethod method : methods) {
                        featuresMap.put(method, new Features(method));
                    }
                }
            }
        } catch (Exception e) {            
        }
        System.out.println("\n");
    }

    /**
     * Traverses the CG and propagates the invocation count from each callee to its callers.
     */
    private void propagateFeatures () {
        Logger.log("Propagating invocations through the Call Graph - DFS");
        Set<SootMethod> calculated = new HashSet<SootMethod>();
        Chain<SootClass> classes = Scene.v().getApplicationClasses();
        SootClass c = null;
        SootMethod m = null;
        try {
            for (SootClass sclass : classes) {
                c = sclass;
                List<SootMethod> methods = sclass.getMethods();
                for (SootMethod method : methods) {
                    m = method;
                    if (!calculated.contains(method)) {
                        propagateFeatures(method, calculated);
                    }
                }
            }
        } catch (Exception e) {
            // handling
        }
    }

    private void propagateFeatures (SootMethod sm, Set<SootMethod> calculated) {
        if (sm.isPhantom()) {
            return;
        }
        Features propagated = getFeatures(sm);
        Chain<Unit> units = sm.retrieveActiveBody().getUnits();
        Map<Unit, Integer> unitDepth = calculateInstructionDepth(sm);
        for (Unit u : units) {
            if (u instanceof Stmt) {
                Stmt s = (Stmt) u;
                if (s.containsInvokeExpr()) {
                    Iterator<Edge> it = this.cg.edgesOutOf(u);
                    while (it.hasNext()) {
                        Edge e = it.next();
                        SootMethod tgt = e.tgt();

                        if (!calculated.contains(tgt) &&
                                !isLibraryClass(tgt.getDeclaringClass())) {
                            calculated.add(tgt);
                            propagateFeatures(tgt, calculated);
                        }

                        int depth = unitDepth.getOrDefault(u, 0);
                        Features features = getFeatures(tgt);
                        propagated.addFeaturesFrom(features, depth);
                        propagatedFeaturesMap.put(sm, propagated);

                        if (!tgt.getDeclaringClass().getPackageName().startsWith("java") &&
                               !tgt.getDeclaringClass().getPackageName().startsWith("jdk") &&
                               !tgt.getDeclaringClass().getPackageName().startsWith("sun")) {
                           System.out.println("    Propagated from: " + tgt.getSubSignature() + " to " +
                           sm.getSubSignature() + "[call depth = " + depth + "]");
                       }
                    }
                }
            }
        }

        calculated.add(sm);
    }

    /**
     * Calculates the depth of each instruction in the LoopNestTree obj.
     * @param sm Input method, from which will be calculated instruction depth.
     * @return A Map<Unit, Integer> containing the depth of each unit.
     */
    private Map<Unit, Integer> calculateInstructionDepth (SootMethod sm) {
        // First we analyze all Units (statements) within loops
        Body body = sm.retrieveActiveBody();
        LoopNestTree loopNestTree = new LoopNestTree(body);
        Map<Unit, Integer> idepth = new HashMap<Unit, Integer>();

        if (!loopNestTree.isEmpty()) {
            for (Loop initloop : loopNestTree) {
                int depth = 1;
                Loop loop = loopNestTree.higher(initloop);
                //
                // walk on the tree up to the root, so we can define our
                // nested level
                while (loop != null) {
                    depth++;
                    loop = loopNestTree.higher(loop);
                }
                List<Stmt> loopBody = initloop.getLoopStatements();
                for (Stmt s : loopBody) {
                    Integer currentDepth = idepth.getOrDefault(s, 1);
                    if (depth >= currentDepth) {
                        idepth.put(s, depth);
                    }
                }
            }
        }
        return idepth;
    }

    /**
     * Returns the Features object of a given method passes as parameter.
     * In case of no object found, a new one will be created and returned.
     * @param method Method which features are required.
     * @return Features object.
     */
    public Features getFeatures (SootMethod method) {
        if (propagatedFeaturesMap.containsKey(method)) {
            return propagatedFeaturesMap.get(method);
        } else if (featuresMap.containsKey(method)) {
            return featuresMap.get(method);
        } else if (featuresLibMap.containsKey(method)) {
            return featuresLibMap.get(method);
        } else {
            Features features = new Features(method);
            featuresLibMap.put(method, features);
            return features;
        }
    }

    public void dumpIR () {
        /* System.out.println("Application classes: " + Scene.v().getApplicationClasses().toString());
        System.out.println(""); */

        Chain<SootClass> classes = Scene.v().getApplicationClasses();
        for (SootClass sclass : classes) {
            File jFile = new File("tmp/" + sclass.getName() + ".J");
            File bFile = new File("tmp/" + sclass.getName() + ".B");
            try {
                FileWriter jfw = new FileWriter(jFile);
                FileWriter bfw = new FileWriter(bFile);
                BufferedWriter jbw = new BufferedWriter(jfw);
                BufferedWriter bbw = new BufferedWriter(bfw);
                for (SootMethod sm : sclass.getMethods()) {
                    jbw.write("\n" + sm.getSubSignature() + "   { \n");
                    bbw.write("\n" + sm.getSubSignature() + "   { \n");
                    JimpleBody jbody = (JimpleBody) sm.retrieveActiveBody();
                    PatchingChain<Unit> units = jbody.getUnits();
                    for (Unit u : units) {
                        jbw.write("\t" + u + " || " + u.getClass().getName() + "\n");
                        if ( u instanceof JAssignStmt) {
                            JAssignStmt as = (JAssignStmt) u;
                            String a = as.getLeftOp().getType().toString();
                        }
                    }
                    PatchingChain<Unit> bunits = (new BafBody(jbody, null)).getUnits();
                    for (Unit u : bunits) {
                        bbw.write("\t" + u + " || " + u.getClass().getName() + "\n");
                    }
                }
                jbw.close();
                bbw.close();
                jfw.close();
                bfw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Print the features Map of each method using the percentage notation
     * for each category of interest.
     */
    public void printFeaturesMap () {
        Logger.log("Printing Feature Map");
        for (Map.Entry<SootMethod, Features> entry :
                                            propagatedFeaturesMap.entrySet()) {
            SootMethod method = entry.getKey();
            Features features = entry.getValue();
            String m = method.getSignature().toString();
            if (m.contains(" benchmark(") || m.contains(" runIteration(")) {
                System.out.println(ConsoleColors.RED_BACKGROUND_BRIGHT + "Method: " + method.getSignature() + 
                " :: " + Long.toString(features.staticInvokations) + " | " + 
                Long.toString(features.approxDynamicInvokations) + ConsoleColors.RESET);
            } else {
                System.out.println("Method: " + method.getSignature() + " :: " + Long.toString(features.staticInvokations) +
                 " | " + Long.toString(features.approxDynamicInvokations));
            }            
        }

        System.out.println("\n");

        for (Map.Entry<SootMethod, Features> entry : featuresMap.entrySet()) {
            SootMethod method = entry.getKey();
            if (!propagatedFeaturesMap.containsKey(method)) {
                Features features = entry.getValue();
                System.out.println("Method not propagated: " + method.getSignature() + " :: "
                 + Long.toString(features.staticInvokations) + " | " + Long.toString(features.approxDynamicInvokations));
            }
        }
    }
}