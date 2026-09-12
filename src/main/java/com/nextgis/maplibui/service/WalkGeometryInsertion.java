package com.nextgis.maplibui.service;

import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoPoint;

/** Keeps walk vertices contiguous directly after the vertex selected at walk start. */
final class WalkGeometryInsertion {
    private WalkGeometryInsertion() {
    }

    static int insert(GeoLineString geometry, int requestedIndex, GeoPoint point) {
        int index = Math.max(0, Math.min(requestedIndex, geometry.getPointCount()));
        geometry.getPoints().add(index, point);
        return index + 1;
    }

    /** Refine only a vertex previously inserted by this recording session. */
    static boolean correct(GeoLineString geometry, int ownedIndex, GeoPoint point) {
        if (ownedIndex < 0 || ownedIndex >= geometry.getPointCount()) return false;
        geometry.getPoint(ownedIndex).setCoordinates(point.getX(), point.getY());
        return true;
    }
}
