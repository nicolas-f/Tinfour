
/* --------------------------------------------------------------------
 *
 * The MIT License
 *
 * Copyright (C) 2021  Gary W. Lucas.

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ---------------------------------------------------------------------
 */

/*
 * -----------------------------------------------------------------------
 *
 * Revision History:
 * Date     Name         Description
 * ------   ---------    -------------------------------------------------
 * 11/2021  N. Fortin    Created
 *
 * -----------------------------------------------------------------------
 */

package org.tinfour.utils;

import org.junit.Test;
import org.tinfour.common.IConstraint;
import org.tinfour.common.LinearConstraint;
import org.tinfour.common.PolygonConstraint;
import org.tinfour.common.SimpleTriangle;
import org.tinfour.common.Vertex;
import org.tinfour.standard.IncrementalTin;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TriangleRegressionConstraintTest {

    public void exportTinAsWKT(String filePath, IncrementalTin tin) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("the_geom, constraint_index\n");
            for(SimpleTriangle triangle : tin.triangles()) {
                Vertex a = triangle.getVertexA();
                Vertex b = triangle.getVertexB();
                Vertex c = triangle.getVertexC();
                int region = -1;
                IConstraint containingRegion = triangle.getContainingRegion();
                if(containingRegion != null) {
                    region = containingRegion.getConstraintIndex();
                }
                writer.write(String.format(Locale.ROOT, "\"POLYGON ((%f %f %f, %f %f %f, %f %f %f, %f %f %f))\", %d\n",
                        a.x, a.y, a.getZ(), b.x, b.y, b.getZ(), c.x, c.y, c.getZ(), a.x, a.y, a.getZ(),
                        region));
            }
        }
    }

    public void dumpDataClass() {
        List<Vertex> points = new ArrayList<>();
        List<IConstraint> constraints = new ArrayList<>();
        String dumpFolder = "";
        try {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dumpFolder, "tinfour_data.dump")))) {
                writer.write("Vertex " + points.size() + "\n");
                int index = 0;
                for(Vertex vObj : points) {
                    final Vertex v = (Vertex)vObj;
                    v.setIndex(index++);
                    writer.write(String.format(Locale.ROOT, "%d %d %d\n", Double.doubleToLongBits(v.getX()),
                            Double.doubleToLongBits(v.getY()),
                            Double.doubleToLongBits(v.getZ())));
                }
                writer.write("Constraints " + constraints.size() + " \n");
                for (IConstraint constraint : constraints) {
                    if (constraint instanceof LinearConstraint) {
                        writer.write("LinearConstraint");
                        List<Vertex> vertices = constraint.getVertices();
                        for (final Vertex v : vertices) {
                            writer.write(" " + v.getIndex());
                        }
                        writer.write("\n");
                    } else if (constraint instanceof PolygonConstraint) {
                        List<Vertex> vertices = constraint.getVertices();
                        if(vertices != null && vertices.size() >= 3) {
                            writer.write("PolygonConstraint " + constraint.getConstraintIndex());
                            for (final Vertex v : vertices) {
                                writer.write(" " + v.getIndex());
                            }
                            writer.write("\n");
                        } else {
                            System.out.println("Weird null polygon " + constraint);
                        }
                    }
                }
            }
        }  catch (IOException ioEx) {
            // ignore
        }
    }

    @Test
    public void testUnlimitedLoop() throws IOException {
        String line = "";
        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(Objects.requireNonNull(
                        TriangleRegressionConstraintTest.class.getResourceAsStream("unlimitedloop.dump"))))) {

            // Triangulate
            IncrementalTin tin = new IncrementalTin();

            List<Vertex> meshPoints;

            // read number of expected points
            while(!line.startsWith("Vertex ")) {
                line = reader.readLine();
            }
            int numberOfVertex = Integer.parseInt(line.substring(line.indexOf(" ") + 1).trim());
            meshPoints = new ArrayList<>(numberOfVertex);
            // Read points
            for(int idVertex = 0; idVertex < numberOfVertex; idVertex++) {
                line = reader.readLine();
                StringTokenizer t = new StringTokenizer(line, " ");
                double v0 = Double.longBitsToDouble(Long.parseLong(t.nextToken()));
                double v1 = Double.longBitsToDouble(Long.parseLong(t.nextToken()));
                double v2 = Double.longBitsToDouble(Long.parseLong(t.nextToken()));
                Vertex vertex = new Vertex(v0, v1, v2);
                meshPoints.add(vertex);
            }
            tin.add(meshPoints, null);
            // Read constraints
            while(!line.startsWith("Constraints ")) {
                line = reader.readLine();
            }
            int numberOfConstraints = Integer.parseInt(line.substring(line.indexOf(" ") + 1).trim());
            List<IConstraint> constraints = new ArrayList<>();
            for(int idConstraint = 0; idConstraint < numberOfConstraints; idConstraint++) {
                line = reader.readLine();
                if(line != null && line.contains(" ")) {
                    StringTokenizer t = new StringTokenizer(line, " ");
                    String constraintClass = t.nextToken();
                    int constraintIndex = Integer.parseInt(t.nextToken().trim());
                    if (constraintClass.equals("PolygonConstraint")) {
                        List<Vertex> vertices = new ArrayList<>();
                        while (t.hasMoreElements()) {
                            vertices.add(meshPoints.get(Integer.parseInt(t.nextToken())));
                        }
                        PolygonConstraint p = new PolygonConstraint(vertices);
                        constraints.add(p);
                        p.setConstraintIndex(tin, constraintIndex);
                    } else  if (constraintClass.equals("LinearConstraint")) {
                        List<Vertex> vertices = new ArrayList<>();
                        while (t.hasMoreElements()) {
                            vertices.add(meshPoints.get(Integer.parseInt(t.nextToken())));
                        }
                        LinearConstraint p = new LinearConstraint(vertices);
                        constraints.add(p);
                        p.setConstraintIndex(tin, constraintIndex);
                    }
                }
            }
            tin.addConstraints(constraints, false);
            Map<Integer, Integer> constraintIndexToTriangleCount = new HashMap<>();

            int total = 0;
            for(SimpleTriangle t : tin.triangles()) {
                IConstraint c = t.getContainingRegion();
                if(c != null) {
                    constraintIndexToTriangleCount.merge(c.getConstraintIndex(), 1, Integer::sum);
                } else {
                    constraintIndexToTriangleCount.merge(-1, 1, Integer::sum);
                }
                total+=1;
            }
            exportTinAsWKT(new File("target", "tinfour_wkt_triangles.csv").getPath(), tin);
        }
    }
    @Test
    public void testIllegalPolygon() throws IOException {
        String line = "";
        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(Objects.requireNonNull(
                        TriangleRegressionConstraintTest.class.getResourceAsStream("illegal_poly.dump"))))) {

            // Triangulate
            IncrementalTin tin = new IncrementalTin();

            List<Vertex> meshPoints;

            // read number of expected points
            while(!line.startsWith("Vertex ")) {
                line = reader.readLine();
            }
            int numberOfVertex = Integer.parseInt(line.substring(line.indexOf(" ") + 1).trim());
            meshPoints = new ArrayList<>(numberOfVertex);
            // Read points
            for(int idVertex = 0; idVertex < numberOfVertex; idVertex++) {
                line = reader.readLine();
                StringTokenizer t = new StringTokenizer(line, " ");
                Vertex vertex = new Vertex(Double.parseDouble(t.nextToken()),Double.parseDouble(t.nextToken()),
                        Double.parseDouble(t.nextToken()));
                meshPoints.add(vertex);
            }
            tin.add(meshPoints, null);
            // Read constraints
            while(!line.startsWith("Constraints ")) {
                line = reader.readLine();
            }
            int numberOfConstraints = Integer.parseInt(line.substring(line.indexOf(" ") + 1).trim());
            List<IConstraint> constraints = new ArrayList<>();
            for(int idConstraint = 0; idConstraint < numberOfConstraints; idConstraint++) {
                line = reader.readLine();
                if(line != null && line.contains(" ")) {
                    StringTokenizer t = new StringTokenizer(line, " ");
                    String constraintClass = t.nextToken();
                    int constraintIndex = Integer.parseInt(t.nextToken().trim());
                    if (constraintClass.equals("PolygonConstraint")) {
                        List<Vertex> vertices = new ArrayList<>();
                        while (t.hasMoreElements()) {
                            vertices.add(meshPoints.get(Integer.parseInt(t.nextToken())));
                        }
                        PolygonConstraint p = new PolygonConstraint(vertices);
                        constraints.add(p);
                        p.setConstraintIndex(tin, constraintIndex);
                    }
                }
            }
            tin.addConstraints(constraints, false);
            Map<Integer, Integer> constraintIndexToTriangleCount = new HashMap<>();

            int total = 0;
            for(SimpleTriangle t : tin.triangles()) {
                IConstraint c = t.getContainingRegion();
                if(c != null) {
                    constraintIndexToTriangleCount.merge(c.getConstraintIndex(), 1, Integer::sum);
                } else {
                    constraintIndexToTriangleCount.merge(-1, 1, Integer::sum);
                }
                total+=1;
            }
            exportTinAsWKT(new File("target", "tinfour_wkt_triangles.csv").getPath(), tin);

            assertEquals(53, constraintIndexToTriangleCount.get(-1));
            assertEquals(3, constraintIndexToTriangleCount.get(0));
            assertEquals(18, constraintIndexToTriangleCount.get(1));
            assertEquals(2, constraintIndexToTriangleCount.get(2));
            assertEquals(14, constraintIndexToTriangleCount.get(3));
        }
    }
}
