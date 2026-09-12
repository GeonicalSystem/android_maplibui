package com.nextgis.maplibui.service;

import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoPoint;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WalkGeometryInsertionTest {
    @Test
    public void refiningAWalkStopPreservesItsNeighboursAndVertexCount() {
        GeoLineString line = new GeoLineString();
        line.add(new GeoPoint(0, 0));
        line.add(new GeoPoint(100, 0));
        int next = WalkGeometryInsertion.insert(line, 1, new GeoPoint(40, 0));
        assertTrue(WalkGeometryInsertion.correct(line, next - 1, new GeoPoint(20, 5)));
        assertEquals(3, line.getPointCount());
        assertEquals(0, line.getPoint(0).getX(), 0);
        assertEquals(20, line.getPoint(1).getX(), 0);
        assertEquals(5, line.getPoint(1).getY(), 0);
        assertEquals(100, line.getPoint(2).getX(), 0);
        assertFalse(WalkGeometryInsertion.correct(line, -1, new GeoPoint(9, 9)));
        assertFalse(WalkGeometryInsertion.correct(line, 3, new GeoPoint(9, 9)));
        assertEquals(3, line.getPointCount());
    }

    @Test
    public void consecutiveWalkPointsStayBetweenSelectedVertexAndItsFormerNextVertex() {
        GeoPoint selected = new GeoPoint(0, 0);
        GeoPoint formerNext = new GeoPoint(10, 0);
        GeoPoint walkA = new GeoPoint(2, 0);
        GeoPoint walkB = new GeoPoint(4, 0);
        GeoLineString line = new GeoLineString();
        line.add(selected);
        line.add(formerNext);

        int next = WalkGeometryInsertion.insert(line, 1, walkA);
        next = WalkGeometryInsertion.insert(line, next, walkB);

        assertEquals(3, next);
        assertEquals(4, line.getPointCount());
        assertSame(selected, line.getPoint(0));
        assertSame(walkA, line.getPoint(1));
        assertSame(walkB, line.getPoint(2));
        assertSame(formerNext, line.getPoint(3));
    }
}
