
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
import org.tinfour.common.PolygonConstraint;
import org.tinfour.common.SimpleTriangle;
import org.tinfour.common.Vertex;
import org.tinfour.standard.IncrementalTin;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

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
            exportTinAsWKT(new File("target", "tinfour_wkt_triangles.csv").getPath(), tin);
        }
    }
}
