/*
 * JosmMCPPlugin - JOSM plugin to integrate JOSM with the Model Context Protocol
 * Copyright (C) 2025-2026 Pengunaria.dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.openstreetmap.josm.plugins.josmmcp.utils;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Polygon arithmetic on a local metric plane around a reference latitude, using {@link Area}.
 * Good for areas of a few kilometres: the projection error is far below mapping precision there.
 */
public final class PlanarGeometry {
	private final double refLat;
	private final double kLon;

	public PlanarGeometry(double refLat) {
		this.refLat = refLat;
		this.kLon = 111_320d * Math.cos(Math.toRadians(refLat));
	}

	public double refLat() {
		return refLat;
	}

	/** Coordinates in metres, rounded to a millimetre so that coincident edges of different ways stay coincident. */
	public double[] toXY(LatLon p) {
		return new double[] {Math.rint(p.lon() * kLon * 1000) / 1000, Math.rint(p.lat() * 110_574d * 1000) / 1000};
	}

	public LatLon toLatLon(double x, double y) {
		return new LatLon(LatLon.roundToOsmPrecision(y / 110_574d), LatLon.roundToOsmPrecision(x / kLon));
	}

	/** Polygon of a closed way's outline (holes of multipolygons are not considered). */
	public Area area(Way w) {
		Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
		boolean first = true;
		for (Node n : w.getNodes()) {
			if (n.getCoor() == null) {
				continue;
			}
			double[] p = toXY(n.getCoor());
			if (first) {
				path.moveTo(p[0], p[1]);
				first = false;
			} else {
				path.lineTo(p[0], p[1]);
			}
		}
		path.closePath();
		return new Area(path);
	}

	/**
	 * The polygon grown by {@code metres} on all sides (mitre joins, so building corners stay corners).
	 * Negative values shrink it.
	 */
	public static Area buffer(Area a, double metres) {
		if (metres == 0 || a.isEmpty()) {
			return new Area(a);
		}
		BasicStroke stroke = new BasicStroke((float) (2 * Math.abs(metres)), BasicStroke.CAP_SQUARE,
				BasicStroke.JOIN_MITER, 4f);
		Area band = new Area(stroke.createStrokedShape(a));
		Area out = new Area(a);
		if (metres > 0) {
			out.add(band);
		} else {
			out.subtract(band);
		}
		return out;
	}

	/** Absolute area in square metres. */
	public static double squareMetres(Area a) {
		double sum = 0;
		for (List<double[]> ring : rings(a)) {
			sum += Math.abs(signedArea(ring));
		}
		return sum;
	}

	/** Absolute area of one ring in square metres. */
	public static double ringArea(List<double[]> ring) {
		return Math.abs(signedArea(ring));
	}

	private static double signedArea(List<double[]> ring) {
		double s = 0;
		for (int i = 0, n = ring.size(); i < n; i++) {
			double[] p = ring.get(i);
			double[] q = ring.get((i + 1) % n);
			s += p[0] * q[1] - q[0] * p[1];
		}
		return s / 2;
	}

	/** The closed rings of the area (outer rings and holes alike), without the repeated closing point. */
	public static List<List<double[]>> rings(Area a) {
		List<List<double[]>> rings = new ArrayList<>();
		List<double[]> cur = null;
		double[] c = new double[6];
		for (PathIterator it = a.getPathIterator(null, 0.01); !it.isDone(); it.next()) {
			int type = it.currentSegment(c);
			switch (type) {
			case PathIterator.SEG_MOVETO:
				cur = new ArrayList<>();
				cur.add(new double[] {c[0], c[1]});
				rings.add(cur);
				break;
			case PathIterator.SEG_LINETO:
				if (cur != null) {
					cur.add(new double[] {c[0], c[1]});
				}
				break;
			case PathIterator.SEG_CLOSE:
				cur = null;
				break;
			default:
				// flattened iterator: no curves
				if (cur != null) {
					cur.add(new double[] {c[type == PathIterator.SEG_QUADTO ? 2 : 4], c[type == PathIterator.SEG_QUADTO ? 3 : 5]});
				}
			}
		}
		List<List<double[]>> out = new ArrayList<>();
		for (List<double[]> r : rings) {
			if (r.size() > 1 && dist(r.get(0), r.get(r.size() - 1)) < 1e-9) {
				r.remove(r.size() - 1);
			}
			if (r.size() >= 3) {
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * Removes consecutive near-duplicates and vertices that lie within {@code tolerance} of the line between
	 * their neighbours; the ring keeps at least 3 vertices.
	 */
	public static List<double[]> simplifyRing(List<double[]> ring, double tolerance) {
		List<double[]> out = new ArrayList<>();
		for (double[] p : ring) {
			if (out.isEmpty() || dist(out.get(out.size() - 1), p) > tolerance) {
				out.add(p);
			}
		}
		while (out.size() > 1 && dist(out.get(0), out.get(out.size() - 1)) <= tolerance) {
			out.remove(out.size() - 1);
		}
		boolean changed = true;
		while (changed && out.size() > 3) {
			changed = false;
			for (int i = 0; i < out.size() && out.size() > 3; i++) {
				double[] prev = out.get((i - 1 + out.size()) % out.size());
				double[] next = out.get((i + 1) % out.size());
				if (distToSegment(out.get(i), prev, next) <= tolerance) {
					out.remove(i);
					changed = true;
					i--;
				}
			}
		}
		return out;
	}

	public static double dist(double[] a, double[] b) {
		return Math.hypot(a[0] - b[0], a[1] - b[1]);
	}

	public static double distToSegment(double[] p, double[] a, double[] b) {
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double len2 = dx * dx + dy * dy;
		double t = len2 == 0 ? 0 : ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2;
		t = Math.max(0, Math.min(1, t));
		return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
	}

	/** Centroid of the (first ring of the) area, or null when empty. */
	public static double[] centroid(Area a) {
		List<List<double[]>> rs = rings(a);
		if (rs.isEmpty()) {
			return null;
		}
		List<double[]> r = rs.get(0);
		double cx = 0;
		double cy = 0;
		double s = 0;
		for (int i = 0, n = r.size(); i < n; i++) {
			double[] p = r.get(i);
			double[] q = r.get((i + 1) % n);
			double f = p[0] * q[1] - q[0] * p[1];
			cx += (p[0] + q[0]) * f;
			cy += (p[1] + q[1]) * f;
			s += f;
		}
		if (Math.abs(s) < 1e-9) {
			return r.get(0);
		}
		return new double[] {cx / (3 * s), cy / (3 * s)};
	}
}
