package sootparser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import soot.Body;
import soot.FastHierarchy;
import soot.Hierarchy;
import soot.PatchingChain;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.toolkits.graph.LoopNestTree;
import soot.Type;

import soot.jimple.toolkits.annotation.logic.Loop;
import soot.jimple.toolkits.thread.synchronization.CriticalSectionInterferenceGraph;
import soot.jimple.toolkits.thread.synchronization.LockAllocator;
import soot.jimple.toolkits.thread.synchronization.SynchronizedRegion;
import soot.toolkits.graph.BlockGraph;
import soot.toolkits.graph.BriefBlockGraph;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.ClassicCompleteBlockGraph;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.Pair;
import soot.util.Chain;
import soot.util.Switchable;


public class Features {
    //
    // All invocations are related to the associated method
    public SootMethod method;
    // total number of invocations
    public long staticInvokations;
    public long approxDynamicInvokations;    
    // CFG Block representation of the method
    private BlockGraph cfg;

    /**
     * Constructor.
     * @param target Method to be analyzed.
     */
    public Features(SootMethod target) {
        method = target;
        staticInvokations = 0;
        try {
            if (!isLibraryClass(target.getDeclaringClass())) {
                System.out.printf("Collecting invocations for for Method " + method.toString());
                buildCFG();
                extractFeatures();
            }            
        } catch (RuntimeException e) {
                 System.err.println("Invocations for method: " + target.getSignature() +
                    " were ignored, due to internal fail!");
                //e.printStackTrace();
        }
    }

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
     * Builds unit CFG
     */
    private void buildCFG () {
        this.cfg = new ClassicCompleteBlockGraph(this.method.retrieveActiveBody());
        //this.cfg = new BriefBlockGraph(this.method.retrieveActiveBody());
    }

    /**
     * Merges two Feature objects;
     * @param other Feature object to be merged into this object.
     */
    public void addFeaturesFrom (Features other) {
        addFeaturesFrom(other, 0);
    }

    /**
     * Merges two Feature objects taking into account the call depth;
     * @param other Feature object to be merged into this object.
     * @param callDepth Call depth.
     */
    public void addFeaturesFrom (Features other, Integer callDepth) {
        int base = 10;
        long m = (long) Math.pow(base, callDepth);
        this.approxDynamicInvokations += other.approxDynamicInvokations * m;
        this.staticInvokations += other.staticInvokations;
    }

    public String serialize () {
        return "" + Long.toString(this.staticInvokations) + " << ";
    }

    private void extractFeatures () {
        // JVM info instructons
        // https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-2.html
        // Baf Reference: Page 13 of
        // https://courses.cs.washington.edu/courses/cse501/01wi/project/sable-thesis.pdf
        Body body = method.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();

        Map<Unit, Integer> unitDepth = calculateInstructionDepth(method);
        Set<String> uniqueInv = new HashSet<String>();
        int base = 10;

        for (Unit u : units) {        
            //
            // Using the unit depth to 'estimate' the amount of times it will be
            // be executed.            
            SootMethod m = isMethodCall(u);
            if (m != null) {
                long inc = (long) Math.pow(base, unitDepth.getOrDefault(u, 0));
                System.out.println("    ➡️   Found " + m.getSignature() + " at depth " + 
                    Integer.toString(unitDepth.getOrDefault(u, 0)));
                uniqueInv.add(m.getSignature());
                this.approxDynamicInvokations += inc;
                this.staticInvokations += 1;
            }
        }

        //this.staticInvokations = uniqueInv.size();
        System.out.println(" || Static: " + Long.toString(this.staticInvokations));
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
     * Checks if input Unit is an explicity barrier in java code.
     * @param u Input unit.
     * @return True if input unit is a barrier
     */
    private SootMethod isMethodCall (Unit u) {
        // We look for explicit barriers in java code
        JInvokeStmt invoke = null;
        if (u instanceof JAssignStmt) {
            JAssignStmt assign = (JAssignStmt) u;
            Value op = assign.getRightOp();
            if (op instanceof JInvokeStmt) {
                invoke = (JInvokeStmt) op;
                InvokeExpr expr = invoke.getInvokeExpr();                    
                return expr.getMethod();
            }

            if (assign.containsInvokeExpr()) {
                InvokeExpr expr = assign.getInvokeExpr();            
                if (expr != null) {
                    return expr.getMethod();
                }
            }
        } else if (u instanceof JInvokeStmt) {
            invoke  = (JInvokeStmt) u;
        } 

        if (invoke != null) {
            InvokeExpr expr = invoke.getInvokeExpr();            
            return expr.getMethod();
        }

        return null;
    }
}