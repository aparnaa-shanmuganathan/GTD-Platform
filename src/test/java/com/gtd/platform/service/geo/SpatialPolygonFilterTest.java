package com.gtd.platform.service.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-traced coverage of {@link SpatialPolygonFilter}'s ray-casting
 * algorithm: a point clearly inside, a point clearly outside, and two
 * boundary cases (an edge midpoint and a vertex) whose results were traced
 * by hand against this exact implementation - see the class Javadoc for why
 * a boundary result isn't claimed to be universally "correct," only
 * deterministic and consistent with the algorithm.
 */
class SpatialPolygonFilterTest {

    /** A simple axis-aligned square: lat 0-10, lng 0-10. */
    private static final List<double[]> SQUARE = List.of(
            new double[] { 0, 0 },
            new double[] { 0, 10 },
            new double[] { 10, 10 },
            new double[] { 10, 0 });

    @Test
    void pointClearlyInsideIsContained() {
        assertThat(SpatialPolygonFilter.contains(5, 5, SQUARE)).isTrue();
    }

    @Test
    void pointClearlyOutsideIsNotContained() {
        assertThat(SpatialPolygonFilter.contains(20, 20, SQUARE)).isFalse();
    }

    @Test
    void pointFarOutsideInBothAxesIsNotContained() {
        assertThat(SpatialPolygonFilter.contains(-50, -50, SQUARE)).isFalse();
    }

    @Test
    void pointOnBottomEdgeMidpointIsContained() {
        // (lat=0, lng=5) sits exactly on the (0,0)-(10,0) edge; hand-traced against
        // this implementation's strict '<' comparison to resolve as inside.
        assertThat(SpatialPolygonFilter.contains(0, 5, SQUARE)).isTrue();
    }

    @Test
    void pointOnVertexIsContained() {
        // (0,0) is itself a vertex of SQUARE; hand-traced against this
        // implementation to resolve as inside.
        assertThat(SpatialPolygonFilter.contains(0, 0, SQUARE)).isTrue();
    }

    @Test
    void nullPolygonIsNeverContained() {
        assertThat(SpatialPolygonFilter.contains(5, 5, null)).isFalse();
    }

    @Test
    void fewerThanThreeVerticesIsNeverContained() {
        List<double[]> degenerate = List.of(new double[] { 0, 0 }, new double[] { 10, 10 });
        assertThat(SpatialPolygonFilter.contains(5, 5, degenerate)).isFalse();
    }

    @Test
    void triangleContainsItsCentroid() {
        List<double[]> triangle = List.of(
                new double[] { 0, 0 },
                new double[] { 0, 12 },
                new double[] { 12, 0 });
        assertThat(SpatialPolygonFilter.contains(4, 4, triangle)).isTrue();
        assertThat(SpatialPolygonFilter.contains(10, 10, triangle)).isFalse();
    }
}
