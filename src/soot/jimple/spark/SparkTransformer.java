/* Soot - a J*va Optimization Framework
 * Copyright (C) 2002, 2003, 2004 Ondrej Lhotak
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.jimple.spark;
import soot.*;
import soot.jimple.spark.pag.*;
import soot.jimple.spark.queue.*;
import soot.jimple.toolkits.callgraph.*;
import soot.jimple.*;
import java.util.*;
import soot.util.*;
import soot.options.SparkOptions;
import soot.options.Options;
import soot.tagkit.*;
import soot.jimple.toolkits.pointer.util.*;
import java.io.*;

/** Main entry point for Spark.
 * @author Ondrej Lhotak
 */
public class SparkTransformer extends SceneTransformer
{ 
    public SparkTransformer( Singletons.Global g ) {}
    public static SparkTransformer v() { return G.v().SparkTransformer(); }

    protected void internalTransform( String phaseName, Map options )
    {
        SparkOptions opts = new SparkOptions( options );

        if( opts.simulate_natives() ) {
            NativeHelper.register( new SparkNativeHelper() );
        }

        SparkScene.v().setup( opts );

        if( opts.pre_jimplify() ) preJimplify();

        Rctxt_method reachableMethods = SparkScene.v().rcout.reader();

        Date startSolve = new Date();

        SparkScene.v().solve();

        Date endSolve = new Date();
        
        if( opts.verbose() ) {
            reportTime( "Propagation", startSolve, endSolve );
        }

        if( opts.set_mass() ) findSetMass();

        /*
        for( Iterator tIt = reachableMethods.iterator(); tIt.hasNext(); ) {
            final Rctxt_method.Tuple t = (Rctxt_method.Tuple) tIt.next();
            System.out.println( t.method() );
        }
        */

        try {
            PrintStream out = new PrintStream(new FileOutputStream("/tmp/jedd_edges.sql"));
            out.println( "begin transaction;" );
            out.println( "drop table jedd_edges;" );
            out.println( "create table jedd_edges ( from string, to string, kind string ) ;" );
            for( Iterator tIt = SparkScene.v().cg.edges().iterator(); tIt.hasNext(); ) {
                final Rsrcc_srcm_stmt_kind_tgtc_tgtm.Tuple t = (Rsrcc_srcm_stmt_kind_tgtc_tgtm.Tuple) tIt.next();
                out.println( "insert into jedd_edges values ( '"+t.srcm()+"', '"+t.tgtm()+"', '"+t.kind()+"' ) ;" );
            }
            out.println( "end transaction;" );
            out.close();
        } catch( IOException e ) {
            throw new RuntimeException( e );
        }

        CallGraph cg = new CallGraph();
        for( Iterator tIt = SparkScene.v().cg.edges().iterator(); tIt.hasNext(); ) {
            final Rsrcc_srcm_stmt_kind_tgtc_tgtm.Tuple t = (Rsrcc_srcm_stmt_kind_tgtc_tgtm.Tuple) tIt.next();
            cg.addEdge( new Edge( MethodContext.v( t.srcm(), t.srcc() ),
                                  t.stmt(),
                                  MethodContext.v( t.tgtm(), t.tgtc() ),
                                  t.kind() ) );
        }
        Scene.v().setCallGraph( cg );

        /*
        SparkOptions opts = new SparkOptions( options );
        final String output_dir = SourceLocator.v().getOutputDir();

        // Build pointer assignment graph
        ContextInsensitiveBuilder b = new ContextInsensitiveBuilder();
        if( opts.pre_jimplify() ) b.preJimplify();
        if( opts.force_gc() ) doGC();
        Date startBuild = new Date();
        final PAG pag = (PAG) b.setup( opts );
        b.build();
        Date endBuild = new Date();
        reportTime( "Pointer Assignment Graph", startBuild, endBuild );
        if( opts.force_gc() ) doGC();

        // Build type masks
        Date startTM = new Date();
        SparkScene.v().tm.makeTypeMask();
        Date endTM = new Date();
        reportTime( "Type masks", startTM, endTM );
        if( opts.force_gc() ) doGC();

        if( opts.verbose() ) {
            G.v().out.println( "VarNodes: "+pag.getVarNodeNumberer().size() );
            G.v().out.println( "FieldRefNodes: "+pag.getFieldRefNodeNumberer().size() );
            G.v().out.println( "AllocNodes: "+pag.getAllocNodeNumberer().size() );
        }

        // Simplify pag
        Date startSimplify = new Date();

        // We only simplify if on_fly_cg is false. But, if vta is true, it
        // overrides on_fly_cg, so we can still simplify. Something to handle
        // these option interdependencies more cleanly would be nice...
        if( ( opts.simplify_sccs() && !opts.on_fly_cg() ) || opts.vta() ) {
                new SCCCollapser( pag, opts.ignore_types_for_sccs() ).collapse();
        }
        if( opts.simplify_offline() && !opts.on_fly_cg() ) {
            new EBBCollapser( pag ).collapse();
        }
        if( true || opts.simplify_sccs() || opts.vta() || opts.simplify_offline() ) {
            pag.cleanUpMerges();
        }
        Date endSimplify = new Date();
        reportTime( "Pointer Graph simplified", startSimplify, endSimplify );
        if( opts.force_gc() ) doGC();

        // Dump pag
        PAGDumper dumper = null;
        if( opts.dump_pag() || opts.dump_solution() ) {
            dumper = new PAGDumper( pag, output_dir );
        }
        if( opts.dump_pag() ) dumper.dump();

        // Propagate
        Date startProp = new Date();
        final Propagator[] propagator = new Propagator[1];
        switch( opts.propagator() ) {
            case SparkOptions.propagator_iter:
                propagator[0] = new PropIter( pag );
                break;
            case SparkOptions.propagator_worklist:
                propagator[0] = new PropWorklist( pag );
                break;
            case SparkOptions.propagator_cycle:
                propagator[0] = new PropCycle( pag );
                break;
            case SparkOptions.propagator_merge:
                propagator[0] = new PropMerge( pag );
                break;
            case SparkOptions.propagator_alias:
                propagator[0] = new PropAlias( pag );
                break;
            case SparkOptions.propagator_none:
                break;
            default:
                throw new RuntimeException();
        }
        if( propagator[0] != null ) propagator[0].propagate();
        Date endProp = new Date();
        reportTime( "Propagation", startProp, endProp );
        reportTime( "Solution found", startSimplify, endProp );
        if( opts.force_gc() ) doGC();
        
        if( !opts.on_fly_cg() || opts.vta() ) {
            CallGraphBuilder cgb = new CallGraphBuilder( pag );
            cgb.build();
        }

        if( opts.verbose() ) {
            G.v().out.println( "[Spark] Number of reachable methods: "
                    +Scene.v().getReachableMethods().size() );
        }

        if( opts.set_mass() ) findSetMass( pag );

        /*
        if( propagator[0] instanceof PropMerge ) {
            new MergeChecker( pag ).check();
        } else if( propagator[0] != null ) {
            new Checker( pag ).check();
        }
        * /

        if( opts.dump_answer() ) new ReachingTypeDumper( pag, output_dir ).dump();
        if( opts.dump_solution() ) dumper.dumpPointsToSets();
        if( opts.dump_html() ) new PAG2HTML( pag, output_dir ).dump();
        Scene.v().setPointsToAnalysis( pag );
        */
        if( opts.add_tags() ) {
            addTags();
        }

        //BDDCflow bddcflow = new BDDCflow( SparkScene.v().cg );
        //bddcflow.addEntryPoints( Scene.v().getEntryPoints() );
        //bddcflow.update();
    }
    private void addTag( Host h, Node n, Map nodeToTag ) {
        Tag t = (Tag) nodeToTag.get(n);
        if( t == null ) {
            t = new StringTag( n.toString() );
            nodeToTag.put(n, t);
        }
        h.addTag( t );
    }
    private void addTags() {
        final NodeManager nm = SparkScene.v().nodeManager();
        final AbsPAG pag = SparkScene.v().pag;

        final Map nodeToTag = new HashMap();

        for( Iterator cIt = Scene.v().getClasses().iterator(); cIt.hasNext(); ) {

            final SootClass c = (SootClass) cIt.next();
            for( Iterator mIt = c.methodIterator(); mIt.hasNext(); ) {
                SootMethod m = (SootMethod) mIt.next();
                if( !m.isConcrete() ) continue;
                if( !m.hasActiveBody() ) continue;
                for( Iterator sIt = m.getActiveBody().getUnits().iterator(); sIt.hasNext(); ) {
                    final Stmt s = (Stmt) sIt.next();
                    if( s instanceof DefinitionStmt ) {
                        Value lhs = ((DefinitionStmt) s).getLeftOp();
                        VarNode v = null;
                        if( lhs instanceof Local ) {
                            v = nm.findLocalVarNode( (Local) lhs );
                        } else if( lhs instanceof FieldRef ) {
                            v = nm.findGlobalVarNode( ((FieldRef) lhs).getField() );
                        }
                        if( v != null ) {
                            PointsToSetReadOnly p2set = 
                                SparkScene.v().p2sets.get(v);
                            p2set.forall( new P2SetVisitor() {
                            public final void visit( Node n ) {
                                addTag( s, n, nodeToTag );
                            }} );
                            Iterator it;
                            it = pag.simpleInvLookup(v);
                            while( it.hasNext() ) {
                                addTag( s, (Node) it.next(), nodeToTag );
                            }
                            it = pag.allocInvLookup(v);
                            while( it.hasNext() ) {
                                addTag( s, (Node) it.next(), nodeToTag );
                            }
                            it = pag.loadInvLookup(v);
                            while( it.hasNext() ) {
                                addTag( s, (Node) it.next(), nodeToTag );
                            }
                        }
                    }
                }
            }
        }
    }
    private void preJimplify() {
        boolean change = true;
        while( change ) {
            change = false;
            for( Iterator cIt = new ArrayList(Scene.v().getClasses()).iterator(); cIt.hasNext(); ) {
                final SootClass c = (SootClass) cIt.next();
                for( Iterator mIt = c.methodIterator(); mIt.hasNext(); ) {
                    final SootMethod m = (SootMethod) mIt.next();
                    if( !m.isConcrete() ) continue;
                    if( m.isNative() ) continue;
                    if( m.isPhantom() ) continue;
                    if( !m.hasActiveBody() ) {
                        change = true;
                        m.retrieveActiveBody();
                    }
                }
            }
        }
    }
    private static void reportTime( String desc, Date start, Date end ) {
        long time = end.getTime()-start.getTime();
        G.v().out.println( "[Spark] "+desc+" in "+time/1000+"."+(time/100)%10+" seconds." );
    }
    protected void findSetMass() {
        int mass = 0;
        int varMass = 0;
        int adfs = 0;
        int scalars = 0;

        for( Iterator vIt = SparkNumberers.v().varNodeNumberer().iterator(); vIt.hasNext(); ) {

            final VarNode v = (VarNode) vIt.next();
            scalars++;
            PointsToSetReadOnly set = SparkScene.v().p2sets.get(v);
            if( set != null ) mass += set.size();
            if( set != null ) varMass += set.size();
        }
        for( Iterator anIt = SparkScene.v().pag.allocSources(); anIt.hasNext(); ) {
            final AllocNode an = (AllocNode) anIt.next();
            for( Iterator adfIt = an.getFields().iterator(); adfIt.hasNext(); ) {
                final AllocDotField adf = (AllocDotField) adfIt.next();
                PointsToSetReadOnly set = SparkScene.v().p2sets.get(adf);
                if( set != null ) mass += set.size();
                if( set != null && set.size() > 0 ) {
                    adfs++;
                }
            }
        }
        G.v().out.println( "Set mass: " + mass );
        G.v().out.println( "Variable mass: " + varMass );
        G.v().out.println( "Scalars: "+scalars );
        G.v().out.println( "adfs: "+adfs );
    }
}


