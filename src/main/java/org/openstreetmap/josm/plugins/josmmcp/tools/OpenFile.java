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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.openstreetmap.josm.actions.OpenFileAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Opens a local file (.osm, .gpx, ...) as a new layer, like File &gt; Open.
 */
public class OpenFile extends BaseTool {

	@Override
	public String getName() {
		return "open_file";
	}

	@Override
	public String getDescription() {
		return "Open a local file (for example an .osm file saved earlier with save_layer) as a new layer in JOSM, "
				+ "like File > Open. Returns the layer list afterwards.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("path", Map.of("type", "string", "description", "Absolute path of the file to open"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("path"), null, null, null);
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		throw new UnsupportedOperationException("use execute()");
	}

	@Override
	protected List<Content> execute(Map<String, Object> args) throws Exception {
		File file = new File(requireArg(args, "path").toString());
		if (!file.isAbsolute()) {
			throw new Exception("path must be absolute");
		}
		if (!file.isFile()) {
			throw new Exception("file not found: " + file);
		}
		Future<?> future = runInEDT(() -> OpenFileAction.openFiles(Collections.singletonList(file)));
		if (future != null) {
			// Wait off the EDT so JOSM's import task can run.
			future.get(5, TimeUnit.MINUTES);
		}
		List<Map<String, Object>> layers = runInEDT(() -> {
			List<Map<String, Object>> out = new ArrayList<>();
			Layer active = MainApplication.getLayerManager().getActiveLayer();
			for (Layer l : MainApplication.getLayerManager().getLayers()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", l.getName());
				m.put("type", l.getClass().getSimpleName());
				m.put("active", l == active);
				out.add(m);
			}
			return out;
		});
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("opened", file.getAbsolutePath());
		result.put("layers", layers);
		return Arrays.asList(new TextContent(JosmUtils.toJson(result)));
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
