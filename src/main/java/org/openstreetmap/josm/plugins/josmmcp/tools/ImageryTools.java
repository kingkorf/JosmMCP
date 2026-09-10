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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.imagery.ImageryLayerInfo;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Layer management: list and add imagery from JOSM's imagery catalogue, remove layers.
 * One class, three tool instances.
 */
public class ImageryTools extends BaseTool {
	public enum Mode { LIST, ADD, REMOVE }

	private final Mode mode;

	public ImageryTools(Mode mode) {
		this.mode = mode;
	}

	@Override
	public String getName() {
		switch (mode) {
		case LIST: return "list_imagery";
		case ADD: return "add_imagery_layer";
		default: return "remove_layer";
		}
	}

	@Override
	public String getDescription() {
		switch (mode) {
		case LIST: return "List imagery sources from JOSM's catalogue (the user's own entries plus the built-in list) matching 'query'. "
				+ "The query matches the entry's name and its id, and 'country' filters by ISO country code. "
				+ "Entry names are translated into JOSM's interface language, so a layer may not be findable under the name it has "
				+ "in its own country; the id is not translated, which makes a fragment such as 'NRW' or 'PDOK' the more reliable search. "
				+ "Use the exact name or the id with add_imagery_layer.";
		case ADD: return "Add an imagery layer (aerial photos, WMS/WMTS) from JOSM's catalogue by name or id; a unique substring of the name is enough. "
				+ "The user's own entries are searched first. Names are translated into JOSM's interface language, ids are not.";
		default: return "Remove a layer by name. Data layers with unsaved changes are refused unless force=true; the active data layer is never removed.";
		}
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		List<String> required = new ArrayList<>();
		if (mode == Mode.LIST) {
			props.put("query", Map.of("type", "string", "description",
					"Case-insensitive substring of the entry's name or its id, e.g. 'PDOK', 'Luchtfoto' or 'DE-NRW'. "
							+ "Ids are not translated, names are, so prefer an id fragment when looking for another country's layers"));
			props.put("country", Map.of("type", "string", "description",
					"Only entries for this ISO 3166-1 alpha-2 country code, e.g. 'DE'. Entries without a country (worldwide) never match"));
			props.put("limit", Map.of("type", "integer", "description", "Maximum number of entries (default 30)"));
		} else if (mode == Mode.ADD) {
			props.put("name", Map.of("type", "string", "description", "Exact name or unique substring of the imagery entry"));
			required.add("name");
		} else {
			props.put("layer", Map.of("type", "string", "description", "Exact layer name or unique substring"));
			props.put("force", Map.of("type", "boolean", "description", "Also remove a data layer with unsaved changes"));
			required.add("layer");
		}
		return new McpSchema.JsonSchema("object", props, required.isEmpty() ? null : required, null, null, null);
	}

	@Override
	public Category category() {
		return mode == Mode.LIST ? Category.READ : Category.DOWNLOAD;
	}

	@Override
	public boolean isDestructive() {
		return mode == Mode.REMOVE;
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		switch (mode) {
		case LIST: return list(args);
		case ADD: return add(args);
		default: return remove(args);
		}
	}

	private static List<ImageryInfo> catalogue() {
		List<ImageryInfo> all = new ArrayList<>(ImageryLayerInfo.instance.getLayers());
		for (ImageryInfo i : ImageryLayerInfo.instance.getDefaultLayers()) {
			if (!all.contains(i)) {
				all.add(i);
			}
		}
		return all;
	}

	private static Map<String, Object> describe(ImageryInfo i, boolean own) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", i.getName());
		m.put("type", i.getImageryType() == null ? null : i.getImageryType().getTypeString());
		m.put("id", i.getId());
		m.put("country", i.getCountryCode());
		m.put("own_entry", own);
		return m;
	}

	/**
	 * Whether an entry matches a search fragment. Names are translated into JOSM's interface
	 * language, so the untranslated id is searched as well: "NRW" finds "Noordrijn-Westfalen
	 * luchtfoto's" through its id DE-NRW-DOP.
	 */
	private static boolean matches(ImageryInfo i, String lowerQuery) {
		if (lowerQuery.isEmpty()) {
			return true;
		}
		if (i.getName() != null && i.getName().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
			return true;
		}
		return i.getId() != null && i.getId().toLowerCase(Locale.ROOT).contains(lowerQuery);
	}

	private String list(Map<String, Object> args) throws Exception {
		Object q = args == null ? null : args.get("query");
		String query = q == null ? "" : q.toString().toLowerCase(Locale.ROOT);
		Object c = args == null ? null : args.get("country");
		String country = c == null ? null : c.toString().trim().toUpperCase(Locale.ROOT);
		int limit = getInt(args, "limit", 30);
		List<Map<String, Object>> out = new ArrayList<>();
		List<ImageryInfo> own = ImageryLayerInfo.instance.getLayers();
		int total = 0;
		for (ImageryInfo i : catalogue()) {
			if (!matches(i, query)) {
				continue;
			}
			if (country != null && !country.equalsIgnoreCase(i.getCountryCode())) {
				continue;
			}
			total++;
			if (out.size() < limit) {
				out.add(describe(i, own.contains(i)));
			}
		}
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("query", query);
		if (country != null) {
			r.put("country", country);
		}
		r.put("total_matches", total);
		r.put("entries", out);
		return JosmUtils.toJson(r);
	}

	private String add(Map<String, Object> args) throws Exception {
		String wanted = requireArg(args, "name").toString();
		List<ImageryInfo> own = ImageryLayerInfo.instance.getLayers();
		ImageryInfo match = null;
		List<ImageryInfo> partial = new ArrayList<>();
		for (ImageryInfo i : catalogue()) {
			if (i.getName() == null && i.getId() == null) {
				continue;
			}
			if (i.getName() == null) {
				if (wanted.equals(i.getId())) {
					match = i;
					break;
				}
				continue;
			}
			if (i.getName().equals(wanted) || wanted.equals(i.getId())) {
				match = i;
				break;
			}
			if (matches(i, wanted.toLowerCase(Locale.ROOT))) {
				partial.add(i);
			}
		}
		if (match == null) {
			List<ImageryInfo> ownPartial = new ArrayList<>();
			for (ImageryInfo i : partial) {
				if (own.contains(i)) {
					ownPartial.add(i);
				}
			}
			List<ImageryInfo> pool = ownPartial.size() == 1 ? ownPartial : partial;
			if (pool.size() == 1) {
				match = pool.get(0);
			} else if (pool.isEmpty()) {
				throw new Exception("no imagery entry matches '" + wanted + "'; use list_imagery");
			} else {
				StringBuilder sb = new StringBuilder("'" + wanted + "' is ambiguous, candidates: ");
				for (int k = 0; k < Math.min(10, pool.size()); k++) {
					sb.append(k > 0 ? "; " : "").append(pool.get(k).getName());
				}
				throw new Exception(sb.toString());
			}
		}
		for (Layer l : MainApplication.getLayerManager().getLayers()) {
			if (l instanceof ImageryLayer && match.getName().equals(l.getName())) {
				l.setVisible(true);
				return JosmUtils.toJson(Map.of("layer", l.getName(), "already_present", true));
			}
		}
		ImageryLayer layer = ImageryLayer.create(match);
		MainApplication.getLayerManager().addLayer(layer);
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("layer", layer.getName());
		r.put("type", match.getImageryType() == null ? null : match.getImageryType().getTypeString());
		r.put("already_present", false);
		return JosmUtils.toJson(r);
	}

	private String remove(Map<String, Object> args) throws Exception {
		String wanted = requireArg(args, "layer").toString();
		List<Layer> layers = MainApplication.getLayerManager().getLayers();
		Layer target = null;
		List<Layer> partial = new ArrayList<>();
		for (Layer l : layers) {
			if (l.getName().equals(wanted)) {
				target = l;
				break;
			}
			if (l.getName().toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))) {
				partial.add(l);
			}
		}
		if (target == null) {
			if (partial.size() == 1) {
				target = partial.get(0);
			} else if (partial.isEmpty()) {
				throw new Exception("no layer matches '" + wanted + "'");
			} else {
				throw new Exception("layer name '" + wanted + "' is ambiguous: " + partial.size() + " layers match");
			}
		}
		if (target == MainApplication.getLayerManager().getEditLayer()) {
			throw new Exception("the active data layer is never removed by this tool");
		}
		if (target instanceof OsmDataLayer && ((OsmDataLayer) target).isModified() && !Boolean.TRUE.equals(args.get("force"))) {
			throw new Exception("data layer '" + target.getName() + "' has unsaved changes; pass force=true to remove it anyway");
		}
		MainApplication.getLayerManager().removeLayer(target);
		List<String> remaining = new ArrayList<>();
		for (Layer l : MainApplication.getLayerManager().getLayers()) {
			remaining.add(l.getName());
		}
		return JosmUtils.toJson(Map.of("removed", target.getName(), "layers", remaining));
	}

	@Override
	protected String describeForConfirmation(Map<String, Object> args) {
		return "Remove layer: " + (args == null ? "" : args.get("layer"));
	}
}
