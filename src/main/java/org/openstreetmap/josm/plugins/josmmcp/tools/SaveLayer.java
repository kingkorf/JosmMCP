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

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.io.importexport.OsmExporter;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Saves the active data layer to an .osm file, so pending edits survive a JOSM restart.
 * Writes files only; it never uploads.
 */
public class SaveLayer extends BaseTool {

	@Override
	public String getName() {
		return "save_layer";
	}

	@Override
	public String getDescription() {
		return "Save the active data layer (including its pending changes) to an .osm file. Without 'path' the layer's "
				+ "own file is used, if it has one. Existing files are only overwritten when they are the layer's own file "
				+ "or when overwrite=true. This never uploads to OpenStreetMap.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("path", Map.of("type", "string", "description", "Absolute path of the .osm file to write"));
		props.put("overwrite", Map.of("type", "boolean", "description", "Allow overwriting an existing file that is not the layer's own file"));
		return new McpSchema.JsonSchema("object", props, null, null, null, null);
	}


	@Override
	public String handle(Map<String, Object> args) throws Exception {
		OsmDataLayer layer = MainApplication.getLayerManager().getEditLayer();
		if (layer == null) {
			throw new Exception("no active data layer");
		}
		Object pathObj = args == null ? null : args.get("path");
		File file = pathObj != null ? new File(pathObj.toString()) : layer.getAssociatedFile();
		if (file == null) {
			throw new Exception("the layer has no associated file yet; pass 'path'");
		}
		if (!file.isAbsolute()) {
			throw new Exception("path must be absolute");
		}
		if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".osm")) {
			throw new Exception("only .osm files are supported");
		}
		boolean overwrite = args != null && Boolean.TRUE.equals(args.get("overwrite"));
		if (file.exists() && !file.equals(layer.getAssociatedFile()) && !overwrite) {
			throw new Exception(file + " exists and is not this layer's file; pass overwrite=true to replace it");
		}
		File parent = file.getAbsoluteFile().getParentFile();
		if (parent != null && !parent.isDirectory()) {
			throw new Exception("directory does not exist: " + parent);
		}
		new OsmExporter().exportData(file, layer);
		layer.setAssociatedFile(file);
		layer.onPostSaveToFile();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("layer", layer.getName());
		result.put("path", file.getAbsolutePath());
		result.put("bytes", file.length());
		return JosmUtils.toJson(result);
	}

	@Override
	public Category category() {
		return Category.FILES;
	}

	@Override
	public boolean returnsJson() {
		return true;
	}
}
