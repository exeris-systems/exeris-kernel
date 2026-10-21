/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GraphHop and GraphPathSpec contract and construction rules")
class GraphPathSpecTest {

    private final GraphEdgeDescriptor edge =
            GraphEdgeDescriptor.create("User", "PURCHASED", "Product");

    @Test
    @DisplayName("GraphHop rejects a null edge descriptor")
    void hopRejectsNullEdge() {
        assertThatThrownBy(() -> new GraphHop(null, 1, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("GraphHop rejects minRepeat below 1")
    void hopRejectsMinRepeatBelowOne() {
        assertThatThrownBy(() -> new GraphHop(edge, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GraphHop rejects maxRepeat below minRepeat")
    void hopRejectsMaxRepeatBelowMinRepeat() {
        assertThatThrownBy(() -> new GraphHop(edge, 2, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GraphHop accepts equal minRepeat and maxRepeat")
    void hopAcceptsEqualBounds() {
        GraphHop hop = new GraphHop(edge, 2, 2);
        assertThat(hop.edge()).isEqualTo(edge);
        assertThat(hop.minRepeat()).isEqualTo(2);
        assertThat(hop.maxRepeat()).isEqualTo(2);
    }

    @Test
    @DisplayName("GraphHop.of(edge) equals a hop with bounds 1 and 1")
    void hopOfEqualsSingleRepetition() {
        GraphHop hop = GraphHop.of(edge);
        assertThat(hop).isEqualTo(new GraphHop(edge, 1, 1));
    }

    @Test
    @DisplayName("GraphPathSpec rejects a null start node")
    void pathSpecRejectsNullStartNode() {
        List<GraphHop> hops = List.of(GraphHop.of(edge));
        assertThatThrownBy(() -> new GraphPathSpec(null, hops))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("GraphPathSpec rejects a null hops list")
    void pathSpecRejectsNullHops() {
        UUID start = UUID.randomUUID();
        assertThatThrownBy(() -> new GraphPathSpec(start, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("GraphPathSpec rejects an empty hops list")
    void pathSpecRejectsEmptyHops() {
        UUID start = UUID.randomUUID();
        List<GraphHop> empty = List.of();
        assertThatThrownBy(() -> new GraphPathSpec(start, empty))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GraphPathSpec rejects a list containing a null hop")
    void pathSpecRejectsNullElementInHops() {
        UUID start = UUID.randomUUID();
        List<GraphHop> hopsWithNull = Collections.singletonList(null);
        assertThatThrownBy(() -> new GraphPathSpec(start, hopsWithNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("mutating the source list after construction does not change hops")
    void pathSpecDefensivelyCopiesHops() {
        UUID start = UUID.randomUUID();
        List<GraphHop> sourceList = new ArrayList<>();
        sourceList.add(GraphHop.of(edge));
        GraphPathSpec spec = new GraphPathSpec(start, sourceList);

        GraphHop secondHop = new GraphHop(edge, 1, 3);
        sourceList.add(secondHop);

        assertThat(spec.hops()).containsExactly(GraphHop.of(edge));
    }

    @Test
    @DisplayName("hops list returned by GraphPathSpec is unmodifiable")
    void pathSpecHopsIsUnmodifiable() {
        UUID start = UUID.randomUUID();
        GraphPathSpec spec = new GraphPathSpec(start, List.of(GraphHop.of(edge)));
        List<GraphHop> hops = spec.hops();
        GraphHop secondHop = new GraphHop(edge, 1, 3);

        assertThatThrownBy(() -> hops.add(secondHop))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
