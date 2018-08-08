/*-----------------------------------------------------------------------
 *
 * Copyright (C) 2018 Sonalysts Inc. All Rights Reserved.
 *
 * Revision History:
 * Date     Name         Description
 * ------   ---------    -------------------------------------------------
 * 08/2018  G. Lucas     Created
 *
 * Notes:
 *
 *--------------------------------------------------------------------------
 */
package tinfour.voronoi;

import java.util.Arrays;
import java.util.List;
import tinfour.common.IQuadEdge;
import tinfour.common.Vertex;

/**
 * Provides elements and methods for representing a Thiessen Polygon created by
 * the LimitedVoronoi class.
 */
public class ThiessenPolygon {

  final boolean open;
  final Vertex vertex;
  final IQuadEdge[] edges;
  final double area;

  /**
   * Constructs a Thiessen Polygon representation. The open flag is used to
   * indicate polygons of an infinite area that extend beyomd the bounds of the
   * Delaunay Triangulation associated with the Voronoi Diagram
   *
   * @param vertex The vertex at the center of the polygon
   * @param edgeList a list of the edges comprising the polygon
   * @param open indcates whether the polygon is infinite (open) finite
   * (closed).
   */
  public ThiessenPolygon(Vertex vertex, List<IQuadEdge> edgeList, boolean open) {
    this.vertex = vertex;
    this.edges = edgeList.toArray(new IQuadEdge[edgeList.size()]);
    this.open = open;
    if (open) {
      area = Double.POSITIVE_INFINITY;
    } else {
      double s = 0;
      for (IQuadEdge e : edgeList) {
        Vertex A = e.getA();
        Vertex B = e.getB();
        s += A.getX() * B.getY() - A.getY() * B.getX();
      }
      area = s / 2;
    }
  }

  /**
   * Gets the edges that comprise the polygon
   *
   * @return a valid array of edges
   */
  public List<IQuadEdge> getEdges() {
    //List<IQuadEdge>list = new ArrayList<>(edges.length);
    return Arrays.asList(edges);
    //return list;
  }

  /**
   * Gets the area of the Voronoi polygon. If the polygon is an open polygon,
   * its area will be infinite.
   *
   * @return if this instance is a closed polygon, a valid floating point value;
   * otherwise, positive infinity.
   */
  public double getArea() {
    return area;
  }
}
