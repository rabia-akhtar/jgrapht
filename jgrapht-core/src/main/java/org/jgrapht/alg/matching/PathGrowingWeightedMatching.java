/*
 * (C) Copyright 2016-2016, by Dimitrios Michail and Contributors.
 *
 * JGraphT : a free Java graph-theory library
 *
 * This program and the accompanying materials are dual-licensed under
 * either
 *
 * (a) the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation, or (at your option) any
 * later version.
 *
 * or (per the licensee's choosing)
 *
 * (b) the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation.
 */
package org.jgrapht.alg.matching;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.interfaces.MatchingAlgorithm;
import org.jgrapht.alg.util.Pair;
import org.jgrapht.alg.util.ToleranceDoubleComparator;

/**
 * A linear time 1/2-approximation algorithm for finding a maximum weight matching in an arbitrary
 * graph. Linear time here means O(m) where m is the cardinality of the edge set, even if the graph
 * contains isolated vertices. 1/2-approximation means that for any graph instance, the algorithm
 * computes a matching whose weight is at least half of the weight of a maximum weight matching. The
 * implementation accepts directed and undirected graphs which may contain self-loops and multiple
 * edges. There is no assumption on the edge weights, i.e. they can also be negative or zero.
 * 
 * <p>
 * The algorithm is due to Drake and Hougardy, described in detail in the following paper:
 * <ul>
 * <li>D.E. Drake, S. Hougardy, A Simple Approximation Algorithm for the Weighted Matching Problem,
 * Information Processing Letters 85, 211-213, 2003.</li>
 * </ul>
 * 
 * <p>
 * This particular implementation uses by default two additional heuristics discussed by the authors
 * which also take linear time but improve the quality of the matchings. These heuristics can be
 * disabled by calling the constructor {@link #PathGrowingWeightedMatching(Graph, boolean)}.
 * Disabling the heuristics has the effect of fewer passes over the edge set of the input graph,
 * probably at the expense of the total weight of the matching.
 * 
 * <p>
 * For a discussion on engineering approximate weighted matching algorithms see the following paper:
 * <ul>
 * <li>Jens Maue and Peter Sanders. Engineering algorithms for approximate weighted matching.
 * International Workshop on Experimental and Efficient Algorithms, Springer, 2007.</li>
 * </ul>
 *
 * @see GreedyWeightedMatching
 * 
 * @param <V> the graph vertex type
 * @param <E> the graph edge type
 *
 * @author Dimitrios Michail
 * @since September 2016
 */
public class PathGrowingWeightedMatching<V, E>
    implements MatchingAlgorithm<V, E>
{
    /**
     * Default value on whether to use extra heuristics to improve the result.
     */
    public static final boolean DEFAULT_USE_HEURISTICS = true;

    private final Graph<V, E> graph;
    private final Comparator<Double> comparator;
    private final boolean useHeuristics;

    /**
     * Construct a new instance of the path growing algorithm. Floating point values are compared
     * using {@link #DEFAULT_EPSILON} tolerance. By default two additional linear time heuristics
     * are used in order to improve the quality of the matchings.
     * 
     * @param graph the input graph
     */
    public PathGrowingWeightedMatching(Graph<V, E> graph)
    {
        this(graph, DEFAULT_USE_HEURISTICS, DEFAULT_EPSILON);
    }

    /**
     * Construct a new instance of the path growing algorithm. Floating point values are compared
     * using {@link #DEFAULT_EPSILON} tolerance.
     * 
     * @param graph the input graph
     * @param useHeuristics if true an improved version with additional heuristics is executed. The
     *        running time remains linear but performs a few more passes over the input. While the
     *        approximation factor remains 1/2, in most cases the heuristics produce matchings of
     *        higher quality.
     */
    public PathGrowingWeightedMatching(Graph<V, E> graph, boolean useHeuristics)
    {
        this(graph, useHeuristics, DEFAULT_EPSILON);
    }

    /**
     * Construct a new instance of the path growing algorithm.
     * 
     * @param graph the input graph
     * @param useHeuristics if true an improved version with additional heuristics is executed. The
     *        running time remains linear but performs a few more passes over the input. While the
     *        approximation factor remains 1/2, in most cases the heuristics produce matchings of
     *        higher quality.
     * @param epsilon tolerance when comparing floating point values
     */
    public PathGrowingWeightedMatching(Graph<V, E> graph, boolean useHeuristics, double epsilon)
    {
        if (graph == null) {
            throw new IllegalArgumentException("Input graph cannot be null");
        }
        this.graph = graph;
        this.comparator = new ToleranceDoubleComparator(epsilon);
        this.useHeuristics = useHeuristics;
    }

    /**
     * Get a matching that is a 1/2-approximation of the maximum weighted matching.
     * 
     * @return a matching
     */
    @Override
    public Matching<E> computeMatching()
    {
        if (useHeuristics) {
            return runWithHeuristics();
        } else {
            return run();
        }
    }

    /**
     * Compute all vertices that have positive degree by iterating over the edges on purpose. This
     * keeps the complexity to O(m) where m is the number of edges.
     * 
     * @return set of vertices with positive degree
     */
    private Set<V> initVisibleVertices()
    {
        Set<V> visibleVertex = new HashSet<>();
        for (E e : graph.edgeSet()) {
            V s = graph.getEdgeSource(e);
            V t = graph.getEdgeTarget(e);
            if (!s.equals(t)) {
                visibleVertex.add(s);
                visibleVertex.add(t);
            }
        }
        return visibleVertex;
    }

    // the algorithm (no heuristics)
    private Matching<E> run()
    {
        // lookup all relevant vertices
        Set<V> visibleVertex = initVisibleVertices();

        // run algorithm
        Set<E> m1 = new HashSet<>();
        Set<E> m2 = new HashSet<>();
        double m1Weight = 0d, m2Weight = 0d;
        int i = 1;
        while (!visibleVertex.isEmpty()) {
            // find vertex arbitrarily
            V x = visibleVertex.stream().findAny().get();

            // grow path from x
            while (x != null) {
                // first heaviest edge incident to vertex x (among visible neighbors)
                double maxWeight = 0d;
                E maxWeightedEdge = null;
                V maxWeightedNeighbor = null;
                for (E e : graph.edgesOf(x)) {
                    V other = Graphs.getOppositeVertex(graph, e, x);
                    if (visibleVertex.contains(other) && !other.equals(x)) {
                        double curWeight = graph.getEdgeWeight(e);
                        if (comparator.compare(curWeight, 0d) > 0 && (maxWeightedEdge == null
                            || comparator.compare(curWeight, maxWeight) > 0))
                        {
                            maxWeight = curWeight;
                            maxWeightedEdge = e;
                            maxWeightedNeighbor = other;
                        }
                    }
                }

                // add it to either m1 or m2, alternating between them
                if (maxWeightedEdge != null) {
                    switch (i) {
                    case 1:
                        m1.add(maxWeightedEdge);
                        m1Weight += maxWeight;
                        break;
                    case 2:
                        m2.add(maxWeightedEdge);
                        m2Weight += maxWeight;
                        break;
                    default:
                        throw new RuntimeException(
                            "Failed to figure out matching, seems to be a bug");
                    }
                    i = 3 - i;
                }

                // remove x and incident edges
                visibleVertex.remove(x);

                // go to next vertex
                x = maxWeightedNeighbor;
            }
        }

        // return best matching
        if (comparator.compare(m1Weight, m2Weight) > 0) {
            return new MatchingImpl<E>(m1, m1Weight);
        } else {
            return new MatchingImpl<E>(m2, m2Weight);
        }
    }

    // the algorithm (improved with additional heuristics)
    private Matching<E> runWithHeuristics()
    {
        // lookup all relevant vertices
        Set<V> visibleVertex = initVisibleVertices();

        // create solver for paths
        DynamicProgrammingPathSolver pathSolver = new DynamicProgrammingPathSolver();

        Set<E> matching = new HashSet<>();
        double matchingWeight = 0d;
        Set<V> matchedVertices = new HashSet<>();

        // run algorithm
        while (!visibleVertex.isEmpty()) {
            // find vertex arbitrarily
            V x = visibleVertex.stream().findAny().get();

            // grow path from x
            LinkedList<E> path = new LinkedList<>();
            while (x != null) {
                // first heaviest edge incident to vertex x (among visible neighbors)
                double maxWeight = 0d;
                E maxWeightedEdge = null;
                V maxWeightedNeighbor = null;
                for (E e : graph.edgesOf(x)) {
                    V other = Graphs.getOppositeVertex(graph, e, x);
                    if (visibleVertex.contains(other) && !other.equals(x)) {
                        double curWeight = graph.getEdgeWeight(e);
                        if (comparator.compare(curWeight, 0d) > 0 && (maxWeightedEdge == null
                            || comparator.compare(curWeight, maxWeight) > 0))
                        {
                            maxWeight = curWeight;
                            maxWeightedEdge = e;
                            maxWeightedNeighbor = other;
                        }
                    }
                }

                // add edge to path and remove x
                if (maxWeightedEdge != null) {
                    path.add(maxWeightedEdge);
                }
                visibleVertex.remove(x);

                // go to next vertex
                x = maxWeightedNeighbor;
            }

            // find maximum weight matching of path using dynamic programming
            Pair<Double, Set<E>> pathMatching = pathSolver.getMaximumWeightMatching(graph, path);

            // add it to result while keeping track of matched vertices
            matchingWeight += pathMatching.getFirst();
            for (E e : pathMatching.getSecond()) {
                V s = graph.getEdgeSource(e);
                V t = graph.getEdgeTarget(e);
                if (!matchedVertices.add(s)) {
                    throw new RuntimeException(
                        "Set is not a valid matching, please submit a bug report");
                }
                if (!matchedVertices.add(t)) {
                    throw new RuntimeException(
                        "Set is not a valid matching, please submit a bug report");
                }
                matching.add(e);
            }
        }

        // extend matching to maximal cardinality (out of edges with positive weight)
        for (E e : graph.edgeSet()) {
            double edgeWeight = graph.getEdgeWeight(e);
            if (comparator.compare(edgeWeight, 0d) <= 0) {
                // ignore zero or negative weight
                continue;
            }
            V s = graph.getEdgeSource(e);
            if (matchedVertices.contains(s)) {
                // matched vertex, ignore
                continue;
            }
            V t = graph.getEdgeTarget(e);
            if (matchedVertices.contains(t)) {
                // matched vertex, ignore
                continue;
            }
            // add edge to matching
            matching.add(e);
            matchingWeight += edgeWeight;
        }

        // return extended matching
        return new MatchingImpl<E>(matching, matchingWeight);
    }

    /**
     * Helper class for repeatedly solving the maximum weight matching on paths.
     * 
     * The work array used in the dynamic programming algorithm is reused between invocations. In
     * case its size is smaller than the path provided, its length is increased. This class is not
     * thread-safe.
     */
    class DynamicProgrammingPathSolver
    {
        private static final int WORK_ARRAY_INITIAL_SIZE = 256;

        // work array
        private double[] a = new double[WORK_ARRAY_INITIAL_SIZE];

        /**
         * Find the maximum weight matching of a path using dynamic programming.
         * 
         * @param path a list of edges. The code assumes that the list of edges is a valid simple
         *        path, and that is not a cycle.
         * @return a maximum weight matching of the path
         */
        public Pair<Double, Set<E>> getMaximumWeightMatching(Graph<V, E> g, LinkedList<E> path)
        {
            int pathLength = path.size();

            // special cases
            switch (pathLength) {
            case 0:
                // special case, empty path
                return Pair.of(Double.valueOf(0d), Collections.emptySet());
            case 1:
                // special case, one edge
                E e = path.getFirst();
                double eWeight = g.getEdgeWeight(e);
                if (comparator.compare(eWeight, 0d) > 0) {
                    return Pair.of(eWeight, Collections.singleton(e));
                } else {
                    return Pair.of(Double.valueOf(0d), Collections.emptySet());
                }
            }

            // make sure work array has enough space
            if (a.length < pathLength + 1) {
                a = new double[pathLength + 1];
            }

            // first pass to find solution
            Iterator<E> it = path.iterator();
            E e = it.next();
            double eWeight = g.getEdgeWeight(e);
            a[0] = 0d;
            a[1] = (comparator.compare(eWeight, 0d) > 0) ? eWeight : 0d;
            for (int i = 2; i <= pathLength; i++) {
                e = it.next();
                eWeight = g.getEdgeWeight(e);
                if (comparator.compare(a[i - 1], a[i - 2] + eWeight) > 0) {
                    a[i] = a[i - 1];
                } else {
                    a[i] = a[i - 2] + eWeight;
                }
            }

            // reverse second pass to build solution
            Set<E> matching = new HashSet<>();
            it = path.descendingIterator();
            int i = pathLength;
            while (i >= 1) {
                e = it.next();
                if (comparator.compare(a[i], a[i - 1]) > 0) {
                    matching.add(e);
                    // skip next edge
                    if (i > 1) {
                        e = it.next();
                    }
                    i--;
                }
                i--;
            }

            // return solution
            return Pair.of(a[pathLength], matching);
        }

    }

}

// End PathGrowingWeightedMatching.java
