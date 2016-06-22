/* --------------------------------------------------------------------
 * Copyright 2016 Gary W. Lucas.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---------------------------------------------------------------------
 */

/*
 * -----------------------------------------------------------------------
 *
 * Revision History:
 * Date     Name         Description
 * ------   ---------    -------------------------------------------------
 * 04/2016  G. Lucas     Created
 *
 * Notes:
 *
 * -----------------------------------------------------------------------
 */
package tinfour.test.viewer.backplane;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Formatter;
import java.util.List;
import javax.imageio.ImageIO;
import tinfour.common.IIncrementalTin;
import tinfour.common.INeighborEdgeLocator;
import tinfour.common.IQuadEdge;
import tinfour.common.NeighborEdgeVertex;
import tinfour.common.Vertex;
import tinfour.gwr.BandwidthSelectionMethod;
import tinfour.gwr.SurfaceGwr;
import tinfour.gwr.SurfaceModel;
import tinfour.interpolation.GwrTinInterpolator;
import tinfour.interpolation.NaturalNeighborInterpolator;
import tinfour.test.utils.TestPalette;
import tinfour.utils.AxisIntervals;

/**
 * Provides elements and method for managing the imagery associated with
 * a model and a set of view options.
 */
public class MvComposite {

  /**
   * An arbitrary setting for how much of the available application
   * memory the application is willing to allocate to the TIN.
   */
  static final double tinMemoryUseFraction = 0.1;

  final private int taskIndex;

  private final IModel model;
  private final ViewOptions view;

  /**
   * Width in pixels
   */
  private final int width;

  /**
   * Height in pixels for rendering
   */
  private final int height;

  AffineTransform m2c;

  AffineTransform c2m;

  IIncrementalTin wireframeTin;
  IIncrementalTin rasterTin;
  IIncrementalTin interpolatingTin;
  double interpolatingTinReductionFactor = Double.POSITIVE_INFINITY;
  GwrTinInterpolator interpolator;
  INeighborEdgeLocator edgeLocator;

  double vx0, vy0, vx1, vy1;

  double zVisMin = Double.POSITIVE_INFINITY;
  double zVisMax = Double.NEGATIVE_INFINITY;
  /**
   * A buffered image that will be returned to calling
   * application via a synchronized method. This image
   * s modified only in synchronized blocks and only using processes
   * that can be completed with sufficient speed to allow it to be
   * accessed from the event-dispatch thread.
   */
  BufferedImage rasterImage;

  private long timeForRenderWireframe0;
  private long timeForRenderWireframe1;
  private int nVerticesInWireframe;
  private int reductionForWireframe;

  private long timeForBuildRaster0;
  private long timeForBuildRaster1;
  private int reductionForRaster;

  boolean zGridComplete;
  boolean zGridIncludesHillshade;
  private float[] zGrid;

  private String modelAndRenderingReport;

  /**
   * A private constructor to deter other classes from instantiating
   * this class without a proper model.
   */
  private MvComposite() {
    taskIndex = 0;
    model = null;
    view = null;
    width = 0;
    height = 0;
    m2c = null;
  }

  /**
   * Construct an instance with the specified rendering elements.
   * Note that once instantiated, the model and view must never be changed.
   *
   * @param model a valid instance giving a data source
   * @param view a valid specification giving instructions on how the
   * data is to be rendered
   * @param width the width of the panel rendering surface
   * @param height the height of the panel rendering surface
   * @param m2c the model to composite transformation; specify a null
   * for first time initialization
   * @param c2m the composite to model transformation; specify a null for
   * first time initialization
   * @param taskIndex the index of the task used during the initialization
   * of this instance
   */
  public MvComposite(
    IModel model,
    ViewOptions view,
    int width, int height,
    AffineTransform m2c, AffineTransform c2m,
    int taskIndex) {
    if (model == null) {
      throw new IllegalArgumentException("Null model not allowed");
    }
    if (view == null) {
      throw new IllegalArgumentException("Null view not allowed");
    }

    this.taskIndex = taskIndex;
    this.width = width;
    this.height = height;
    this.m2c = m2c;
    this.c2m = c2m;
    this.model = model;
    this.view = view;

    // Get the coordinates of the corners of the
    // composite mapped to the model coordinate system.
    double[] c = new double[8];
    // lower-left corner
    c[0] = 0;
    c[1] = height;
    // upper-right corner
    c[2] = width;
    c[3] = 0;
    c2m.transform(c, 0, c, 4, 2);
    vx0 = c[4];
    vy0 = c[5];
    vx1 = c[6];
    vy1 = c[7];

    if (model.isLoaded()) {
      interpolatingTin = model.getReferenceTin();
      interpolatingTinReductionFactor = model.getReferenceReductionFactor();
      interpolator = new GwrTinInterpolator(interpolatingTin);
      edgeLocator = interpolatingTin.getNeighborEdgeLocator();
    }

    updateReport();
  }

  /**
   * Construct a new composite transferring data products from the older
   * composite so that they may be reused without additional processing.
   * These elements may include TINs and grids as well as transformations.
   *
   * @param mvComposite the older composite
   * @param view a valid set of view parameters
   * @param taskIndex the index for the task associated with the composite
   */
  public MvComposite(MvComposite mvComposite, ViewOptions view, int taskIndex) {
    this.taskIndex = taskIndex;
    this.width = mvComposite.width;
    this.height = mvComposite.height;
    this.m2c = mvComposite.m2c;
    this.c2m = mvComposite.c2m;
    this.model = mvComposite.model;
    this.wireframeTin = mvComposite.wireframeTin;
    this.rasterTin = mvComposite.rasterTin;
    this.view = view;
    this.modelAndRenderingReport = mvComposite.modelAndRenderingReport;

    synchronized (mvComposite) {
      interpolatingTin = mvComposite.interpolatingTin;
      interpolatingTinReductionFactor = mvComposite.interpolatingTinReductionFactor;
      interpolator = new GwrTinInterpolator(interpolatingTin);
      edgeLocator = interpolatingTin.getNeighborEdgeLocator();
      zVisMin = mvComposite.zVisMin;
      zVisMax = mvComposite.zVisMax;
    }
    // Get the coordinates of the corners of the
    // composite mapped to the model coordinate system.
    double[] c = new double[8];
    // lower-left corner
    c[0] = 0;
    c[1] = height;
    // upper-right corner
    c[2] = width;
    c[3] = 0;
    c2m.transform(c, 0, c, 4, 2);
    vx0 = c[4];
    vy0 = c[5];
    vx1 = c[6];
    vy1 = c[7];

    if (mvComposite.zGridComplete) {
      this.zGrid = mvComposite.zGrid;
      this.zGridComplete = true;
      this.zGridIncludesHillshade = mvComposite.zGridIncludesHillshade;
    }

    if (model.isLoaded()) {
      IIncrementalTin ref = model.getReferenceTin();
      ref.getNeighborEdgeLocator();
      interpolator = new GwrTinInterpolator(ref);
      edgeLocator = ref.getNeighborEdgeLocator();
    }
  }

  /**
   * Get the model associated with composite.
   *
   * @return a valid instance
   */
  public IModel getModel() {
    return model;
  }

  /**
   * Get the view options associated with the composite.
   * It is important that application code not alter the content
   * of the options object/
   *
   * @return a valid view object.
   */
  public ViewOptions getView() {
    return view;
  }

  /**
   * Get the index of the task currently associated with the composite.
   *
   * @return
   */
  public int getTaskIndex() {
    return taskIndex;
  }

  /**
   * Get the width of the composite
   *
   * @return a positive integer in pixels
   */
  public int getWidth() {
    return width;
  }

  /**
   * Get the height of the composite
   *
   * @return a positive integer value in pixels
   */
  public int getHeight() {
    return height;
  }

  /**
   * Get the model to display transform. Do not modify this element.
   *
   * @return a valid transform.
   */
  AffineTransform getModelToDisplayTransform() {
    return m2c;
  }

  /**
   * Sets the TIN to be used for wireframe rendering.
   * <strong>Important:</strong>
   * the TIN must not be modified after it is added to this composite.
   *
   * @param tin a valid TIN.
   */
  public void setWireframeTin(IIncrementalTin tin) {
    this.wireframeTin = tin;
  }

  /**
   * Sets the TIN for raster rendering.
   *
   * @param rasterTin a valid TIN
   */
  void setRasterTin(IIncrementalTin rasterTin) {
    this.rasterTin = rasterTin;
  }

  /**
   * Apply the specified range of values to the accumulated limits of
   * the range of values visible in the display
   *
   * @param zMin the minimum value
   * @param zMax the maximum value
   */
  void recordRangeOfVisibleSamples(double zMin, double zMax) {
    synchronized (this) {
      if (zMin < zVisMin) {
        zVisMin = zMin;
      }
      if (zMax > zVisMax) {
        zVisMax = zMax;
      }
    }
  }

  private double[] getRangeOfVisibleSamples() {
    synchronized (this) {
      if (zVisMin == Double.POSITIVE_INFINITY) {
        return new double[0];
      }
      double[] d = new double[2];
      d[0] = zVisMin;
      d[1] = zVisMax;
      return d;
    }
  }

  /**
   * Submit a TIN as a candidate for serving as the interpolating TIN.
   * The TIN will be selected if its reduction factor is less than that
   * of the current TIN.
   *
   * @param tin a valid candidate TIN
   * @param the reduction factor of the candidate TIN
   */
  void submitCandidateTinForInterpolation(IIncrementalTin tin, double reductionFactor) {
    synchronized (this) {
      if (reductionFactor < this.interpolatingTinReductionFactor) {
        interpolatingTin = tin;
        interpolatingTinReductionFactor = reductionFactor;
        interpolator = new GwrTinInterpolator(interpolatingTin);
        edgeLocator = interpolatingTin.getNeighborEdgeLocator();
      }
    }
  }

  //       1010     1000    1001
  //       0010     0000    0001
  //       0110     0100    0101
  private int cohenSutherlandCode(Vertex v) {
    double x = v.getX();
    double y = v.getY();
    int mask = 0;
    if (x < vx0) {
      mask |= 0b0010;
    } else if (x > vx1) {
      mask |= 0b0001;
    }
    if (y < vy0) {
      mask |= 0b0100;
    } else if (y > vy1) {
      mask |= 0b1000;
    }
    return mask;

  }

  /**
   * Render sample points only. Used when the user has deselected the
   * edge rendering. Since a TIN is not required, the rendering is
   * conducted strictly on the basis of the selected list.
   *
   * @param vList a list of vertices
   * @return a buffered image containing the rendering result.
   */
  BufferedImage renderWireframePointsOnly(List<Vertex> vList) {
    timeForRenderWireframe1 = 0;
    nVerticesInWireframe = 0;

    timeForRenderWireframe0 = System.currentTimeMillis();
    BufferedImage bImage
      = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
    Graphics2D g2d = bImage.createGraphics();
    g2d.setRenderingHint(
      RenderingHints.KEY_ANTIALIASING,
      RenderingHints.VALUE_ANTIALIAS_ON);
    g2d.setRenderingHint(
      RenderingHints.KEY_TEXT_ANTIALIASING,
      RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g2d.setColor(view.getForeground());
    double c[] = new double[8];

    double zMin = model.getMinZ();
    double zMax = model.getMaxZ();

    TestPalette palette = null;
    if (view.usePaletteForWireframe()) {
      String paletteName = view.getPaletteName();
      palette = TestPalette.getPaletteByName(paletteName);
    }
    g2d.setColor(view.getForeground());

    boolean labeling = view.isLabelRenderingSelected();
    boolean indexLabeling = "ID".equalsIgnoreCase(view.getFieldForLabel());
    g2d.setFont(new Font("Arial", Font.PLAIN, 10));
    Ellipse2D e2d = new Ellipse2D.Double();
    for (Vertex a : vList) {

      double x = a.getX();
      double y = a.getY();

      c[0] = x;
      c[1] = y;
      m2c.transform(c, 0, c, 2, 1);

      x = c[2];
      y = c[3];
      if (0 <= x && x <= width && 0 <= y && y <= height) {
        e2d.setFrame(c[2] - 2, c[3] - 2, 5, 5);
        if (palette != null) {
          double z = a.getZ();
          Color color = palette.getColor(z, zMin, zMax);
          g2d.setColor(color);
        }
        g2d.fill(e2d);
        if (labeling) {
          String s;
          if (indexLabeling) {
            s = Integer.toString(a.getIndex());
          } else {
            s = String.format("%5.3f", a.getZ());
          }
          g2d.drawString(s, (float) (c[2] + 3), (float) (c[3] - 3));
        }
      }

    }

    return bImage;
  }

  /**
   * Render using an existing wireframe tin.
   */
  @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
  BufferedImage renderWireframe() {
    timeForRenderWireframe1 = 0;
    nVerticesInWireframe = 0;
    if (!(view.isEdgeRenderingSelected() || view.isVertexRenderingSelected())) {
      return null;
    }

    timeForRenderWireframe0 = System.currentTimeMillis();
    BufferedImage bImage
      = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
    Graphics2D g2d = bImage.createGraphics();
    g2d.setRenderingHint(
      RenderingHints.KEY_ANTIALIASING,
      RenderingHints.VALUE_ANTIALIAS_ON);
    g2d.setRenderingHint(
      RenderingHints.KEY_TEXT_ANTIALIASING,
      RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g2d.setColor(view.getForeground());
    double c[] = new double[8];

    double zMin = model.getMinZ();
    double zMax = model.getMaxZ();

    TestPalette palette = null;
    if (view.usePaletteForWireframe()) {
      String paletteName = view.getPaletteName();
      palette = TestPalette.getPaletteByName(paletteName);
    }
    g2d.setColor(view.getForeground());

    // although the TIN classes do provide a method for getting vertices,
    // the edge fetching method is more efficient. So we attempt to streamline
    // things here by using just the edges. We supplement this with a
    // bitmap that will keep a record of two things:
    //    what points are within the drawing area
    //    what points have already been rendered.
    //
    //   Although we could have passed the vertex list that was
    // already available into this task, we take the list from
    // the TIN instead...  we do this because the TIN has logic for
    // removing duplicate vertices and we don't want to render the dupes.
    //   Finally, vertices with a negative index are a special case.
    // They are perimeter vertices. Because they are not thinned, they may
    // be too dense for labeling, so we do not label them.  Also, note that
    // because their indices are negative, they cannot be used in the
    // bitmap logic.
    int maxVertexIndex = 0;

    List<IQuadEdge> edgeList = wireframeTin.getEdges();
    for (IQuadEdge e : edgeList) {
      Vertex a = e.getA();
      if (a != null) {
        int index = a.getIndex();
        if (index > maxVertexIndex) {
          maxVertexIndex = index;
        }
      }
      Vertex b = e.getB();
      if (b != null) {
        int index = b.getIndex();
        if (index > maxVertexIndex) {
          maxVertexIndex = index;
        }
      }
    }

    int nVerticesIncluded = 0;
    final int[] bitmap = new int[1 + maxVertexIndex / 32];
    if (view.isEdgeRenderingSelected()) {
      Line2D l2d = new Line2D.Double();

      for (IQuadEdge e : edgeList) {
        Vertex a = e.getA();
        Vertex b = e.getB();
        if (a == null || b == null) {
          // a ghost edge
          continue;
        }
        int aMask = cohenSutherlandCode(a);
        int bMask = cohenSutherlandCode(b);
        if ((aMask & bMask) != 0) {
          // edge is unambiguously off the display
          continue;
        }

        if (aMask == 0) {
          nVerticesIncluded++;
          int aIndex = a.getIndex();
          if (aIndex >= 0) {
            bitmap[aIndex >> 5] |= (1 << (aIndex & 0x1f));
          }
        }
        if (bMask == 0) {
          nVerticesIncluded++;
          int bIndex = b.getIndex();
          if (bIndex >= 0) {
            bitmap[bIndex >> 5] |= (1 << (bIndex & 0x1f));
          }
        }
        if (palette != null) {
          double z0 = a.getZ();
          double z1 = a.getZ();
          Color c0 = palette.getColor(z0, zMin, zMax);
          Color c1 = palette.getColor(z1, zMin, zMax);
          GradientPaint paint = new GradientPaint( //NOPMD
            (float) a.getX(), (float) b.getY(), c0,
            (float) a.getX(), (float) b.getY(), c1);
          g2d.setPaint(paint);
        }
        c[0] = a.getX();
        c[1] = a.getY();
        c[2] = b.getX();
        c[3] = b.getY();
        m2c.transform(c, 0, c, 4, 2);
        l2d.setLine(c[4], c[5], c[6], c[7]);
        g2d.draw(l2d);
      }
    }

    // if the edge rendering wasn't turned on, we need to
    // process the data and make sure that the bitmap is
    // properly populated
    if (!view.isEdgeRenderingSelected()) {
      for (IQuadEdge e : edgeList) {
        Vertex a = e.getA();
        Vertex b = e.getB();
        if (a != null) {
          double x = a.getX();
          double y = a.getY();
          if (vx0 <= x && x <= vx1 && vy0 <= y && y <= vy1) {
            nVerticesIncluded++;
            int aIndex = a.getIndex();
            if (aIndex >= 0) {
              bitmap[aIndex >> 5] |= (1 << (aIndex & 0x1f));
            }
          }
        }
        if (b != null) {
          double x = b.getX();
          double y = b.getY();
          if (vx0 <= x && x <= vx1 && vy0 <= y && y <= vy1) {
            nVerticesIncluded++;
            int bIndex = b.getIndex();
            if (bIndex >= 0) {
              bitmap[bIndex >> 5] |= (1 << (bIndex & 0x1f));
            }
          }
        }
      }
    }

    if (view.isVertexRenderingSelected()) {
      boolean labeling = view.isLabelRenderingSelected();
      boolean indexLabeling = "ID".equalsIgnoreCase(view.getFieldForLabel());
      g2d.setFont(new Font("Arial", Font.PLAIN, 10));
      Ellipse2D e2d = new Ellipse2D.Double();

      for (IQuadEdge e : edgeList) {
        Vertex a = e.getA();
        Vertex b = e.getB();
        if (a != null) {
          int aIndex = a.getIndex();
          if (aIndex >= 0) {
            int mask = 1 << (aIndex & 0x1f);
            if ((bitmap[aIndex >> 5] & mask) != 0) {
              bitmap[aIndex >> 5] &= ~mask;

              double x = a.getX();
              double y = a.getY();

              c[0] = x;
              c[1] = y;
              m2c.transform(c, 0, c, 2, 1);
              e2d.setFrame(c[2] - 2, c[3] - 2, 5, 5);
              if (palette != null) {
                double z = a.getZ();
                Color color = palette.getColor(z, zMin, zMax);
                g2d.setColor(color);
              }
              g2d.fill(e2d);
              if (labeling) {
                String s;
                if (indexLabeling) {
                  s = Integer.toString(a.getIndex());
                } else {
                  s = String.format("%5.3f", a.getZ());
                }
                g2d.drawString(s, (float) (c[2] + 3), (float) (c[3] - 3));
              }
            }
          }
        }

        if (b != null) {
          int bIndex = b.getIndex();
          if (bIndex >= 0) {
            int mask = 1 << (bIndex & 0x1f);
            if ((bitmap[bIndex >> 5] & mask) != 0) {
              bitmap[bIndex >> 5] &= ~mask;

              double x = b.getX();
              double y = b.getY();

              c[0] = x;
              c[1] = y;
              m2c.transform(c, 0, c, 2, 1);
              e2d.setFrame(c[2] - 2, c[3] - 2, 5, 5);
              if (palette != null) {
                double z = b.getZ();
                Color color = palette.getColor(z, zMin, zMax);
                g2d.setColor(color);
              }
              g2d.fill(e2d);
              if (labeling) {
                String s;
                if (indexLabeling) {
                  s = Integer.toString(b.getIndex());
                } else {
                  s = String.format("%5.3f", b.getZ());
                }
                g2d.drawString(s, (float) (c[2] + 3), (float) (c[3] - 3));
              }
            }
          }
        }
      }
    }

    //  Draw a border around the overall composite
    //    g2d.setStroke(new BasicStroke(2.0f));
    //    g2d.setColor(new Color(64, 64, 255));
    //    Rectangle2D r2d = new Rectangle2D.Double(0, 0, width, height);
    //    g2d.draw(r2d);
    //    g2d.draw(new Line2D.Double(0, 0, width, height));
    //    g2d.draw(new Line2D.Double(width, 0, 0, height));
    g2d.dispose();
    timeForRenderWireframe1 = System.currentTimeMillis();
    nVerticesInWireframe = nVerticesIncluded;
    updateReport();
    return bImage;
  }

  /**
   * Get the transform that maps the composite to the model
   *
   * @return a valid instance
   */
  public AffineTransform getComposite2ModelTransform() {
    return new AffineTransform(c2m);
  }

  /**
   * Get a transform that maps the model to the composite
   *
   * @return a valid instance
   */
  public AffineTransform getModel2CompositeTransform() {
    return new AffineTransform(m2c);
  }

  /**
   * Map the specified composite coordinates to the model and get a string
   * indicating the data at that point. Typically called to support
   * mouse move events.
   *
   * @param x a coordinate in the composite coordinate system
   * @param y a coordinate in the composite coordinate system
   * @return a valid string
   */
  public String getModelDataStringAtCoordinates(double x, double y) {
    double[] c = new double[4];
    c[0] = x;
    c[1] = y;
    c2m.transform(c, 0, c, 2, 1);
    double mx = c[2];
    double my = c[3];

    String s = model.getFormattedCoordinates(c[2], c[3]);
    if (mx < vx0 || mx > vx1 || my < vy0 || my > vy1) {
      return s;
    } else if (interpolator != null) {
      // a slightly higher bandwidth parameter can serve as a low-pass filter
      // if the parameter is too small, the results will be more dramatic,
      // but will tend to reveal the triangular nature of the underlying TIN
      // in areas of particularly severe gradient.
      double z = interpolator.interpolate(
        SurfaceModel.QuadraticWithCrossTerms,
        BandwidthSelectionMethod.FixedProportionalBandwidth, 1.0,
        mx, my, null);
      if (Double.isNaN(z)) {
        s += " : N/A"; //NOPMD
      } else {
        s += String.format(" : %4.2f", z); //NOPMD
      }
    }
    return s;

  }

  /**
   * Maps the specified composite coordinates to the model,
   * performs a data query, and gets an HTML-formatted string
   * indicating the data at that point.
   *
   * @param x a coordinate in the composite coordinate system
   * @param y a coordinate in the composite coordinate system
   * @return a valid string
   */
  public MvQueryResult performQuery(double x, double y) {
    double[] c = new double[4];
    c[0] = x;
    c[1] = y;
    c2m.transform(c, 0, c, 2, 1);
    double mx = c[2];
    double my = c[3];

    Point2D compositePoint = new Point2D.Double(x, y);
    Point2D modelPoint = new Point2D.Double(mx, my);
    if (interpolator == null) {
      new MvQueryResult(
        compositePoint,
        modelPoint,
        "<html>Data not available. Model not loaded</html>");
    }

    NeighborEdgeVertex nev = edgeLocator.getEdgeWithNearestVertex(mx, my);
    boolean queryIsOutside = !nev.isInterior();
    Vertex vNear = nev.getNearestVertex();
    double dNear = nev.getDistance();

    // the following is a debugging aid when trying to deal with vertex
    // insertion versus TIN extension.
    // boolean isVertexInside = (searchEdge.getForward().getB() != null);
    StringBuilder sb = new StringBuilder(512);
    Formatter fmt = new Formatter(sb);
    fmt.format("<html><strong>Query/Regression Results</strong><br><pre><small>");
    double z = interpolator.interpolate(
      SurfaceModel.QuadraticWithCrossTerms,
      BandwidthSelectionMethod.AdaptiveBandwidth, 1.0,
      mx, my, null);
    fmt.format("X:     %s\n", model.getFormattedX(mx));
    fmt.format("Y:     %s\n", model.getFormattedY(my));
    if (queryIsOutside) {
      fmt.format("Query point is outside of TIN");
    } else if (Double.isNaN(z)) {
      fmt.format("Z:     Not available\n");
    } else {
      SurfaceGwr surface = interpolator.getCurrentSurfaceGWR();
      double beta[] = surface.getCoefficients();
      double descA = Math.toDegrees(Math.atan2(-beta[2], -beta[1]));
      double descB = 90 - descA;
      if (descB < 0) {
        descB += 360; // compass bearing is always 0 to 360
      }
      double zX = beta[1];
      double zY = beta[2];
      double zXX = 2 * beta[3];
      double zYY = 2 * beta[4];
      double zXY = beta[5];
      double kP = (zXX * zX * zX + 2 * zXY * zX * zY + zYY * zY * zY)
        / ((zX * zX + zY * zY) * Math.pow(zX * zX + zY * zY + 1.0, 1.5));

      double kS = (zX * zY * (zXX - zYY) + (zY * zY - zX * zX) * zXY)
        / Math.pow(zX * zX + zY * zY, 1.5);

      double h = surface.getPredictionIntervalHalfRange(0.05);
      fmt.format("Z:     %11.2f &plusmn; %4.2f\n", z, h);
      fmt.format("Slope: %11.2f %%\n", interpolator.getSlope() * 100);
      fmt.format("Curvature\n");
      fmt.format("  Profile:    %8.5f (radian/unit)\n", kP);
      fmt.format("  Streamline: %8.5f (radian/unit)\n", kS);
      fmt.format("Steepest Descent\n");
      fmt.format("  Azimuth:    %4d&deg;\n", (int) (descA));
      fmt.format("  Compass Brg: %03d&deg;\n", (int) (descB));
      fmt.format("Nearest Point\n");
      fmt.format("  Dist:  %11.2f units\n", dNear);
      fmt.format("  X:     %s\n", model.getFormattedX(vNear.getX()));
      fmt.format("  Y:     %s\n", model.getFormattedY(vNear.getY()));
      fmt.format("  Z:     %11.2f\n", vNear.getZ());
      fmt.format("  ID:    %8d\n", vNear.getIndex());
      if (model instanceof ModelFromLas) {
        ((ModelFromLas) model).formatLidarFields(fmt, vNear.getIndex());
      }
      fmt.format("\nRegression used %d samples\n", interpolator.getSampleCount());
    }
    fmt.format("</small></pre></html>");
    return new MvQueryResult(compositePoint, modelPoint, sb.toString());

  }

  /**
   * Gets the array for storing the interpolated z-value and derivative grids.
   * If the array is not allocated, initialize it. This method is synchronized
   * because it may be accessed by multiple tasks simultaneously.
   *
   * @return a valid array to be shared across classes
   */
  @SuppressWarnings("PMD.MethodReturnsInternalArray")
  float[] getArrayForZ() {
    synchronized (this) {
      if (zGrid == null) {
        zGrid = new float[width * height * 3];
        for (int i = 0; i < zGrid.length; i++) {
          zGrid[i] = Float.NaN;
        }
      }
      return zGrid;
    }
  }

  /**
   * Starts the timer for measuring grid building time.
   */
  void startGridBuildTimer() {
    timeForBuildRaster0 = System.currentTimeMillis();
  }

  /**
   * Build a set of grid points for use in raster rendering
   *
   * @param row0 the initial row
   * @param nRows the number of rows to process
   * @param hillshade indicates whether the operations should produce
   * first derivative information needed to compute surface normals for
   * hillshade operations.
   * @param task the task associated with the rendering.
   */
  void buildGrid(int row0, int nRows, boolean hillshade, IModelViewTask task) {
    // ensure grids are ready for writing results

    getArrayForZ();

    zGridIncludesHillshade = hillshade;

    double minX = model.getMinX();
    double maxX = model.getMaxX();
    double minY = model.getMinY();
    double maxY = model.getMaxY();
    int rowLimit = row0 + nRows;

    if (hillshade) {
      IIncrementalTin t = rasterTin;
      if (t == null) {
        // really, this should probably throw an IllegalStateException
        t = wireframeTin;
      }
      GwrTinInterpolator gwr = new GwrTinInterpolator(t);

      double[] c = new double[8];
      for (int iRow = row0; iRow < rowLimit; iRow++) {
        int index = iRow * width * 3;
        c[0] = 0;
        c[1] = iRow + 0.5;
        c[2] = width;
        c[3] = iRow + 0.5;
        c2m.transform(c, 0, c, 4, 2);
        double x0 = c[4];
        double y0 = c[5];
        double x1 = c[6];
        double y1 = c[7];

        double y = (y0 + y1) / 2; // diagnostic: y0 MUST equal y1
        if (y < minY || y > maxY) {
          continue;
        }
        double dx = (x1 - x0) / width;
        for (int iCol = 0; iCol < width; iCol++) {
          double x = (iCol + 0.5) * dx + x0;
          if (minX <= x && x <= maxX) {
            double z = gwr.interpolate(
              SurfaceModel.QuadraticWithCrossTerms,
              BandwidthSelectionMethod.FixedProportionalBandwidth, 1.0,
              x, y, null);

            if (gwr.wasTargetExteriorToTin()) {
              zGrid[index] = Float.NaN;
            } else if (Double.isNaN(z)) {
              zGrid[index] = Float.NaN;
            } else {
              zGrid[index] = (float) z;
              SurfaceGwr surface = gwr.getCurrentSurfaceGWR();
              double[] beta = surface.getCoefficients();
              zGrid[index + 1] = (float) beta[1]; // derivative Zx
              zGrid[index + 2] = (float) beta[2]; // derivative Zy
            }
          }
          index += 3;
        }
        if (task != null && task.isCancelled()) {
          return;
        }
      }
    } else {
      IIncrementalTin t = rasterTin;
      if (t == null) {
        t = wireframeTin;
      }
      NaturalNeighborInterpolator nni = new NaturalNeighborInterpolator(t);
      double[] c = new double[8];
      for (int iRow = row0; iRow < rowLimit; iRow++) {
        int index = iRow * width * 3;
        c[0] = 0;
        c[1] = iRow + 0.5;
        c[2] = width;
        c[3] = iRow + 0.5;
        c2m.transform(c, 0, c, 4, 2);
        double x0 = c[4];
        double y0 = c[5];
        double x1 = c[6];
        double y1 = c[7];

        double y = (y0 + y1) / 2; // diagnostic: y0 MUST equal y1
        if (y < minY || y > maxY) {
          continue;
        }
        double dx = (x1 - x0) / width;
        for (int iCol = 0; iCol < width; iCol++) {
          double x = (iCol + 0.5) * dx + x0;
          if (minX <= x && x <= maxX) {
            double z = nni.interpolate(x, y, null);

            if (Double.isNaN(z)) {
              zGrid[index] = Float.NaN;
            } else {
              zGrid[index] = (float) z;
            }
          }
          index += 3;
        }
        if (task != null && task.isCancelled()) {
          return;
        }
      }
    }
  }

  /**
   * Transfer the grid to a raster image using the view options
   * specified when this instance was constructed.
   */
  void transferGridToRasterImage() {
    double minZ = model.getMinZ();
    double maxZ = model.getMaxZ();
    if (view.useRangeOfValuesForPalette()) {
      double[] range = view.getRangeForPalette();
      if (range != null && range.length == 2) {
        minZ = range[0];
        maxZ = range[1];
      }
    }

    getArrayForZ();
    int[] argb = new int[width * height];
    int index = 0;
    TestPalette palette = TestPalette.getPaletteByName(view.getPaletteName());
    boolean hillshade = view.isHillshadeSelected();
    if (!hillshade) { //NOPMD
      for (int i = 0; i < argb.length; i++) {
        if (Float.isNaN(zGrid[index])) {
          argb[i] = 0xffffffff;
        } else {
          argb[i] = palette.getARGB(zGrid[index], minZ, maxZ);
        }
        index += 3;
      }
    } else {
      boolean rasterColor = view.isRasterSelected();
      //int background = view.getBackground().getRGB();
      double ambient = view.getHillshadeAmbient() / 100.0;
      double directLight = 1.0 - ambient;
      double sunAzimuth = Math.toRadians(view.getHillshadeAzimuth());
      double sunElevation = Math.toRadians(view.getHillshadeElevation());
      // create a unit vector pointing at illumination source
      double cosA = Math.cos(sunAzimuth);
      double sinA = Math.sin(sunAzimuth);
      double cosE = Math.cos(sunElevation);
      double sinE = Math.sin(sunElevation);
      double xSun = cosA * cosE;
      double ySun = sinA * cosE;
      double zSun = sinE;

      for (int i = 0; i < argb.length; i++) {
        if (Float.isNaN(zGrid[index])) {
          argb[i] = 0xffffffff;
        } else {
          final double fx = -zGrid[index + 1];
          final double fy = -zGrid[index + 2];
          final double s = Math.sqrt(fx * fx + fy * fy + 1);
          final double nx = fx / s;
          final double ny = fy / s;
          final double nz = 1 / s;
          final double cosine = nx * xSun + ny * ySun + nz * zSun;
          final double c = (cosine < 0 ? ambient : ambient + directLight * cosine);
          if (rasterColor) {
            final int rgb = palette.getARGB(zGrid[index], minZ, maxZ);
            final int r = (int) (((rgb >> 16) & 0xff) * c);
            final int g = (int) (((rgb >> 8) & 0xff) * c);
            final int b = (int) ((rgb & 0xff) * c);
            argb[i] = 0xff000000 | (r << 16) | (g << 8) | b;
          } else {
            int g = (int) (c * 255);
            argb[i] = (((0xff00 | g) << 8 | g) << 8) | g;
          }

        }
        index += 3;
      }
    }
    rasterImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    rasterImage.setRGB(0, 0, width, height, argb, 0, width);
    updateReport();
    //makeLegend( );
  }

  /**
   * Called when the last grid-building task completes so that the
   * timing data for grid-building can be recorded.
   */
  void stopGridBuildTimer() {
    double zMin = Double.POSITIVE_INFINITY;
    double zMax = Double.NEGATIVE_INFINITY;

    synchronized (this) {
      this.timeForBuildRaster1 = System.currentTimeMillis();
      zGridComplete = true;
      for (int i = 0; i < zGrid.length; i += 3) {
        double zTest = zGrid[i];
        if (zTest < zMin) {
          zMin = zTest;
        } else if (zTest > zMax) {
          zMax = zTest;
        }
      }

      updateReport();
    }

  }

  /**
   * Indicates whether a change in view options would require
   * reloading the existing model.
   *
   * @param v a set of view options for comparison
   * @return true if the comparison options are incompatible and
   * require a reloading of the model.
   */
  public boolean isModelReloadRequired(ViewOptions v) {
    if(model instanceof ModelFromLas){
      return view.getLidarPointSelection() != v.getLidarPointSelection();
    }
    return false;
  }

  public String getModelAndRenderingReport() {
    synchronized (this) {
      return modelAndRenderingReport;
    }
  }

  private String formatReduction(int reduction) {
    if (reduction == 0) {
      return "N/A";
    } else if (reduction == 1) {
      return "Full Resolution";
    } else {
      return String.format("%8d to 1", reduction);
    }
  }

  private synchronized void updateReport() {  //NOPMD (just to save an indent)
    StringBuilder sb = new StringBuilder(2048);
    Formatter fmt = new Formatter(sb);
    fmt.format("<html><strong>Model</strong><br><pre><small>");
    fmt.format("  Name: %s\n", model.getName());
    fmt.format("  Type: %s\n", model.getDescription());
    fmt.format("  Vertices:         %8d\n", model.getVertexCount());
    fmt.format("  Load time(ms):    %8d\n", model.getTimeToLoadInMillis());
    fmt.format("  Sort time(ms):    %8d\n", model.getTimeToSortInMillis());
    fmt.format("  Bounds\n");
    fmt.format("    Min X:          %s\n", model.getFormattedX(model.getMinX()));
    fmt.format("    Max X:          %s\n", model.getFormattedX(model.getMaxX()));
    fmt.format("    Min Y:          %s\n", model.getFormattedY(model.getMinY()));
    fmt.format("    Max Y:          %s\n", model.getFormattedY(model.getMaxY()));
    fmt.format("    Min Z:          %11.2f\n", model.getMinZ());
    fmt.format("    Max Z:          %11.2f\n", model.getMaxZ());
    fmt.format("  Area:             %11.2f\n", model.getArea());
    fmt.format("  Est. Avg. Spacing:%11.2f\n",
      model.getNominalPointSpacing());
    fmt.format("</small></pre><strong>Rendering</strong><br><pre><small>");
    fmt.format("  Wireframe\n");
    if (timeForRenderWireframe1 > 0) {
      long timex = timeForRenderWireframe1 - timeForRenderWireframe0;
      fmt.format("    Vertices:      %8d\n", nVerticesInWireframe);
      fmt.format("    Reduction:     %s\n", formatReduction(reductionForWireframe));
      fmt.format("    Time(ms):      %8d\n", timex);
    } else {
      fmt.format("    Not Available\n");
    }

    fmt.format("  Raster\n");
    if (timeForBuildRaster1 > 0) {
      long timex = timeForBuildRaster1 - timeForBuildRaster0;
      fmt.format("    Reduction:     %s\n", formatReduction(reductionForRaster));
      fmt.format("    Time(ms):      %8d\n", timex);
    } else {
      fmt.format("    Not Available\n");
    }

    double[] rng = getRangeOfVisibleSamples();
    if (rng.length > 0) {
      fmt.format("\nRange of visible samples\n");
      fmt.format("    Min:      %11.3f\n", rng[0]);
      fmt.format("    Max:      %11.3f\n", rng[1]);
    }

    fmt.format("</small></pre></html>");
    fmt.flush();
    modelAndRenderingReport = sb.toString();
  }

  void setReductionForWireframe(int reduction) {
    reductionForWireframe = reduction;
  }

  void setReductionForRaster(int reduction) {
    reductionForRaster = reduction;
  }

  IIncrementalTin getWireframeTin() {
    return wireframeTin;
  }

  int getReductionForWireframe() {
    return reductionForWireframe;
  }

  public boolean isReady() {
    return model.isLoaded() && interpolatingTin != null;
  }

  void makeLegend() {
    Font font = new Font("Arial", Font.BOLD, 10);
    BufferedImage bImage = renderLegend(view, model, 50, 100, 5, font, true);
    if (bImage != null) {
      try {

        File file = new File("legend.png");
        ImageIO.write(bImage, "PNG", file);
        System.out.println("wrote " + file);
      } catch (IOException ioex) {

      }
    }

  }

  /**
   * Render a legend for the current model and view. The width and height
   * are the dimensions of the color bar, but the actual legend will extend
   * larger to accommodate labels.
   *
   * @param vx a valid view
   * @param mx a valid model
   * @param width the width of the color bar
   * @param height the height of the color bar
   * @param margin the margin around the overall legend
   * @param frame indicates that a framing rectangle is to be drawn
   * around the legend
   * @return if successful, a valid buffered image; otherwise, a null
   */
  public BufferedImage renderLegend(
    ViewOptions vx, IModel mx, int width, int height, int margin, Font font, boolean frame) {

    int priSpace = 20;
    int secSpace = 5;
    int ticLength = 10;
    int ticLengthShort = 5;
    double v0 = mx.getMinZ();
    double v1 = mx.getMaxZ();
    if (vx.useRangeOfValuesForPalette()) {
      double[] d = vx.getRangeForPalette();
      v0 = d[0];
      v1 = d[1];
    }
    if (v0 == v1) {
      return null; // can't do anything
    }

    AxisIntervals lx = AxisIntervals.computeIntervals(
      v0, v1, priSpace, secSpace, height);
    if (lx == null) {
      return null;
    }

    // figure out how much space is needed for the labels
    double wLabel = 0; // width for labels

    FontRenderContext frc = new FontRenderContext(null, true, true);
    String[] labels = lx.getLabels();
    double[] wLab = new double[labels.length];
    for (int i = 0; i < labels.length; i++) {
      TextLayout layout = new TextLayout(labels[i], font, frc); // NOPMD
      Rectangle2D bounds = layout.getBounds();
      wLab[i] = bounds.getMaxX();
      if (wLab[i] > wLabel) {
        wLabel = wLab[i];
      }
    }

    TextLayout layout = new TextLayout("0", font, frc); // NOPMD
    Rectangle2D bounds = layout.getBounds();
    double wZero = bounds.getWidth();
    double yLabelCenter = bounds.getCenterY(); // for vertical alignment of labels

    int iWidth = (int) (width + 2 * margin + wLabel + ticLength + wZero / 2);
    int iHeight = height + 2 * margin;
    BufferedImage bImage = new BufferedImage(
      iWidth,
      iHeight,
      BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = bImage.createGraphics();
    g2d.setRenderingHint(
      RenderingHints.KEY_ANTIALIASING,
      RenderingHints.VALUE_ANTIALIAS_ON);
    g2d.setColor(vx.getBackground());
    g2d.fillRect(0, 0, iWidth + 1, iHeight + 1);

    g2d.setFont(font);

    int x0 = margin;
    int x1 = margin + width;   // start of tics
    int x2 = (int) (margin + width + ticLength + wZero / 2 + 1); // start of labels
    int x3 = (int) (x2 + wLabel); // end of labels
    int y0 = margin; // top of color bar
    int y1 = margin + height; // bottom of color bar

    String paletteName = vx.getPaletteName();
    TestPalette palette = TestPalette.getPaletteByName(paletteName);

    Rectangle2D r2d = new Rectangle2D.Double();
    for (int i = 0; i <= height; i++) {
      double v = i / (double) height;
      double y = y1 - i;
      Color c = palette.getColor(v, 0.0, 1.0);
      g2d.setColor(c);
      r2d.setRect(x0, y, width, 1);
      g2d.fill(r2d);
    }
    g2d.setColor(vx.getForeground());
    r2d.setRect(x0, y0, width, height);
    g2d.draw(r2d);

    double[][] cTics = lx.getTicCoordinates();
    double[] vT = cTics[0];
    Line2D l2d = new Line2D.Double();
    String fmt = lx.getLabelFormat();
    for (int i = 0; i < vT.length; i++) {
      double y = y1 - lx.mapValueToPixel(vT[i]);
      l2d.setLine(x1, y, x1 + ticLength, y);
      g2d.draw(l2d);
      String s = String.format(fmt, vT[i]);
      double x = x3 - wLab[i]; // right justified
      g2d.drawString(s, (float) x, (float) (y - yLabelCenter));
    }

    if (cTics.length == 2) {
      vT = cTics[1];
      for (int i = 0; i < vT.length; i++) {
        double y = y1 - lx.mapValueToPixel(vT[i]);
        l2d.setLine(x1, y, x1 + ticLengthShort, y);
        g2d.draw(l2d);
      }
    }

    if (frame) {
      g2d.setColor(vx.getForeground());
      g2d.drawRect(0, 0, iWidth - 1, iHeight - 1);
    }
    return bImage;

  }

}
