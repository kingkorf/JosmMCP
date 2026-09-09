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
package org.openstreetmap.josm.plugins.josmmcp.tools;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.plugins.josmmcp.utils.ImageUtils;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Renders the current JOSM map view (all visible layers, including imagery) to an
 * image and returns it together with the geographic bounds of the rendered area.
 */
public class CaptureMapView extends BaseTool {

	private static final int DEFAULT_MAX_WIDTH = 1280;
	private static final int MAX_MAX_WIDTH = 4096;
	private static final int DEFAULT_WAIT_AFTER_ZOOM_MS = 1500;
	private static final int MAX_WAIT_MS = 15000;
	private static final float JPEG_QUALITY = 0.85f;

	@Override
	public String getName() {
		return "capture_map_view";
	}

	@Override
	public String getDescription() {
		return "Render the current JOSM map view (data layer plus any visible imagery/background layers) "
				+ "to an image. Optionally zoom to an element or a bounding box first; this changes the user's view. "
				+ "The text part of the result gives the lat/lon bounds of the image so pixel positions can be "
				+ "related to coordinates.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();

		Map<String, Object> maxWidth = new HashMap<>();
		maxWidth.put("type", "integer");
		maxWidth.put("description", "Downscale the image to at most this width in pixels (default "
				+ DEFAULT_MAX_WIDTH + ", max " + MAX_MAX_WIDTH + ")");
		props.put("max_width", maxWidth);

		Map<String, Object> format = new HashMap<>();
		format.put("type", "string");
		format.put("enum", new ArrayList<>(Arrays.asList("jpeg", "png")));
		format.put("description", "Image format; jpeg (default) is much smaller for aerial imagery, png is lossless");
		props.put("format", format);

		Map<String, Object> elType = new HashMap<>();
		elType.put("type", "string");
		elType.put("enum", new ArrayList<>(Arrays.asList("node", "way", "relation")));
		elType.put("description", "Together with zoom_to_element_id: zoom the view to this element before capturing");
		props.put("zoom_to_element_type", elType);

		Map<String, Object> elId = new HashMap<>();
		elId.put("type", "integer");
		props.put("zoom_to_element_id", elId);

		Map<String, Object> bbox = new HashMap<>();
		bbox.put("type", "array");
		bbox.put("items", Map.of("type", "number"));
		bbox.put("minItems", 4);
		bbox.put("maxItems", 4);
		bbox.put("description", "Zoom the view to [min_lon, min_lat, max_lon, max_lat] before capturing");
		props.put("bbox", bbox);

		Map<String, Object> wait = new HashMap<>();
		wait.put("type", "integer");
		wait.put("description", "Milliseconds to wait before capturing so imagery tiles can load "
				+ "(default 0, or " + DEFAULT_WAIT_AFTER_ZOOM_MS + " when zooming; max " + MAX_WAIT_MS + ")");
		props.put("wait_ms", wait);
		props.put("restore_view", Map.of("type", "boolean",
				"description", "After capturing, return the map view to where the user had it (default false; only relevant when zooming)"));

		return new McpSchema.JsonSchema("object", props, null, null, null, null);
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		throw new UnsupportedOperationException("use execute()");
	}

	@Override
	protected List<Content> execute(Map<String, Object> args) throws Exception {
		int maxWidth = Math.min(MAX_MAX_WIDTH, getInt(args, "max_width", DEFAULT_MAX_WIDTH));
		if (maxWidth <= 0) {
			throw new Exception("max_width must be positive");
		}
		Object formatObj = args == null ? null : args.get("format");
		String format = formatObj == null ? "jpeg" : formatObj.toString();
		if (!"jpeg".equals(format) && !"png".equals(format)) {
			throw new Exception("format must be jpeg or png");
		}

		boolean restore = args != null && Boolean.TRUE.equals(args.get("restore_view"));
		org.openstreetmap.josm.data.ViewportData previous = restore
				? runInEDT(() -> new org.openstreetmap.josm.data.ViewportData(requireMapView().getCenter(), requireMapView().getScale()))
				: null;
		boolean zoomed = runInEDT(() -> zoomIfRequested(args));

		int wait = getInt(args, "wait_ms", zoomed ? DEFAULT_WAIT_AFTER_ZOOM_MS : 0);
		if (wait < 0 || wait > MAX_WAIT_MS) {
			throw new Exception("wait_ms must be between 0 and " + MAX_WAIT_MS);
		}
		if (wait > 0) {
			// Off the EDT on purpose: give imagery layers time to fetch and paint their tiles.
			Thread.sleep(wait);
		}

		Capture capture = runInEDT(this::render);
		if (zoomed && previous != null) {
			runInEDT(() -> {
				requireMapView().zoomTo(previous);
				return null;
			});
		}

		BufferedImage scaled = ImageUtils.limitWidth(capture.image, maxWidth);
		byte[] bytes = ImageUtils.encode(scaled, format, JPEG_QUALITY);
		String base64 = Base64.getEncoder().encodeToString(bytes);

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("width", scaled.getWidth());
		meta.put("height", scaled.getHeight());
		meta.put("format", format);
		meta.put("bytes", bytes.length);
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("min_lat", capture.bounds.getMinLat());
		b.put("min_lon", capture.bounds.getMinLon());
		b.put("max_lat", capture.bounds.getMaxLat());
		b.put("max_lon", capture.bounds.getMaxLon());
		meta.put("bounds", b);
		meta.put("center", Map.of("lat", capture.bounds.getCenter().lat(), "lon", capture.bounds.getCenter().lon()));
		meta.put("meters_per_pixel", capture.metersPerPixel * capture.image.getWidth() / scaled.getWidth());
		meta.put("visible_layers", capture.visibleLayers);
		meta.put("zoomed", zoomed);
		meta.put("view_restored", zoomed && previous != null);

		List<Content> result = new ArrayList<>();
		result.add(new ImageContent(null, base64, ImageUtils.mimeType(format)));
		result.add(new TextContent(JosmUtils.toJson(meta)));
		return result;
	}

	private static MapView requireMapView() throws Exception {
		MapFrame map = MainApplication.getMap();
		if (map == null || map.mapView == null || !map.mapView.isShowing()) {
			throw new Exception("no map view is shown; open or download a data layer first");
		}
		return map.mapView;
	}

	/** Runs on the EDT. Returns true when the view was changed. */
	private static boolean zoomIfRequested(Map<String, Object> args) throws Exception {
		MapView mv = requireMapView();
		Object typeObj = args == null ? null : args.get("zoom_to_element_type");
		Object idObj = args == null ? null : args.get("zoom_to_element_id");
		Object bboxObj = args == null ? null : args.get("bbox");

		if (typeObj != null || idObj != null) {
			if (typeObj == null || idObj == null) {
				throw new Exception("zoom_to_element_type and zoom_to_element_id must be given together");
			}
			DataSet ds = MainApplication.getLayerManager().getEditDataSet();
			if (ds == null) {
				throw new Exception("no active dataset found");
			}
			long id = toLong(idObj, "zoom_to_element_id");
			OsmPrimitive el = ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.from(typeObj.toString())));
			if (el == null) {
				throw new Exception(typeObj + " with id " + id + " not found");
			}
			AutoScaleAction.zoomTo(Collections.singleton(el));
			return true;
		}

		if (bboxObj != null) {
			if (!(bboxObj instanceof List) || ((List<?>) bboxObj).size() != 4) {
				throw new Exception("bbox must be an array of 4 numbers: [min_lon, min_lat, max_lon, max_lat]");
			}
			List<?> l = (List<?>) bboxObj;
			double minLon = toDouble(l.get(0), "bbox");
			double minLat = toDouble(l.get(1), "bbox");
			double maxLon = toDouble(l.get(2), "bbox");
			double maxLat = toDouble(l.get(3), "bbox");
			if (minLat >= maxLat || minLon >= maxLon) {
				throw new Exception("bbox min values must be smaller than max values");
			}
			Bounds bounds = new Bounds(minLat, minLon, maxLat, maxLon);
			if (!bounds.isValid()) {
				throw new Exception("bbox is not valid");
			}
			mv.zoomTo(bounds);
			return true;
		}
		return false;
	}

	/** Runs on the EDT. */
	private Capture render() throws Exception {
		MapView mv = requireMapView();
		int w = mv.getWidth();
		int h = mv.getHeight();
		if (w <= 0 || h <= 0) {
			throw new Exception("map view has no size");
		}
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try {
			// MapView.paint() reads g.getClipBounds(), which is null on a fresh off-screen
			// Graphics; without an explicit clip it fails with a NullPointerException.
			g.setClip(0, 0, w, h);
			mv.paint(g);
		} catch (RuntimeException e) {
			throw new Exception("rendering the map view failed: " + e, e);
		} finally {
			g.dispose();
		}
		List<String> layers = new ArrayList<>();
		for (Layer layer : MainApplication.getLayerManager().getLayers()) {
			if (layer.isVisible()) {
				layers.add(layer.getName());
			}
		}
		// getDist100Pixel() is the real-world distance covered by 100 pixels at the view centre.
		return new Capture(img, mv.getRealBounds(), mv.getDist100Pixel() / 100.0, layers);
	}

	private static final class Capture {
		final BufferedImage image;
		final Bounds bounds;
		final double metersPerPixel;
		final List<String> visibleLayers;

		Capture(BufferedImage image, Bounds bounds, double metersPerPixel, List<String> visibleLayers) {
			this.image = image;
			this.bounds = bounds;
			this.metersPerPixel = metersPerPixel;
			this.visibleLayers = visibleLayers;
		}
	}

	@Override
	public Category category() {
		return Category.VIEW;
	}
}
