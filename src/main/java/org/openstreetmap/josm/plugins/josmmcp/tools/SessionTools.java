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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openstreetmap.josm.actions.RestartAction;
import org.openstreetmap.josm.actions.SessionLoadAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.io.session.SessionLayerExporter;
import org.openstreetmap.josm.io.session.SessionWriter;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;
import org.openstreetmap.josm.tools.MultiMap;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * JOSM sessions (.joz) capture every layer, imagery included, so a restart costs nothing.
 * One class, three tool instances: save_session, open_session, restart_josm.
 */
public class SessionTools extends BaseTool {
	public enum Mode { SAVE, OPEN, RESTART }

	private final Mode mode;

	public SessionTools(Mode mode) {
		this.mode = mode;
	}

	@Override
	public String getName() {
		switch (mode) {
		case SAVE: return "save_session";
		case OPEN: return "open_session";
		default: return "restart_josm";
		}
	}

	@Override
	public String getDescription() {
		switch (mode) {
		case SAVE: return "Save all layers (data, imagery, GPX) as a JOSM session file (.joz, zipped with the data inside), so the "
				+ "whole working setup can be restored after a restart. Data layers with unsaved changes are included in the zip.";
		case OPEN: return "Open a JOSM session file (.jos or .joz), restoring its layers, like File > Open (session).";
		default: return "Restart JOSM (File > Restart), e.g. after installing a new plugin jar. Asks the mapper for confirmation. "
				+ "Save a session first: all layers are lost otherwise. The MCP connection drops until JOSM is back.";
		}
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		List<String> required = new ArrayList<>();
		if (mode != Mode.RESTART) {
			props.put("path", Map.of("type", "string", "description", mode == Mode.SAVE
					? "Absolute path of the .joz file to write" : "Absolute path of the .jos/.joz file to open"));
			required.add("path");
		}
		if (mode == Mode.SAVE) {
			props.put("overwrite", Map.of("type", "boolean", "description", "Replace an existing file (default false)"));
		}
		return new McpSchema.JsonSchema("object", props, required.isEmpty() ? null : required, null, null, null);
	}

	@Override
	public Category category() {
		return Category.FILES;
	}

	@Override
	public boolean isDestructive() {
		return mode == Mode.RESTART;
	}

	@Override
	public boolean returnsJson() {
		return mode == Mode.SAVE;
	}

	@Override
	protected String describeForConfirmation(Map<String, Object> args) {
		return "Restart JOSM now. Unsaved layers are lost; the assistant's connection drops until JOSM is running again.";
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		switch (mode) {
		case SAVE: return save(args);
		case OPEN: return open(args);
		default: return restart();
		}
	}

	private String save(Map<String, Object> args) throws Exception {
		File file = new File(requireArg(args, "path").toString());
		if (!file.isAbsolute() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".joz")) {
			throw new Exception("path must be an absolute .joz file");
		}
		if (file.exists() && !Boolean.TRUE.equals(args.get("overwrite"))) {
			throw new Exception(file + " exists; pass overwrite=true to replace it");
		}
		File parent = file.getAbsoluteFile().getParentFile();
		if (parent != null && !parent.isDirectory()) {
			throw new Exception("directory does not exist: " + parent);
		}
		List<Layer> layers = new ArrayList<>(MainApplication.getLayerManager().getLayers());
		Map<Layer, SessionLayerExporter> exporters = new HashMap<>();
		List<String> saved = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (Layer l : new ArrayList<>(layers)) {
			SessionLayerExporter ex = SessionWriter.getSessionLayerExporter(l);
			if (ex == null) {
				layers.remove(l);
				skipped.add(l.getName());
			} else {
				exporters.put(l, ex);
				saved.add(l.getName());
			}
		}
		if (layers.isEmpty()) {
			throw new Exception("no layers that can be saved in a session");
		}
		Layer active = MainApplication.getLayerManager().getActiveLayer();
		int activeIdx = Math.max(0, layers.indexOf(active));
		new SessionWriter(layers, activeIdx, exporters, new MultiMap<>(), true).write(file);
		for (Layer l : layers) {
			if (l instanceof OsmDataLayer) {
				// the data is inside the zip; JOSM does not consider that a save of the layer itself
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("path", file.getAbsolutePath());
		result.put("bytes", file.length());
		result.put("layers", saved);
		if (!skipped.isEmpty()) {
			result.put("skipped_layers", skipped);
		}
		return JosmUtils.toJson(result);
	}

	private String open(Map<String, Object> args) throws Exception {
		File file = new File(requireArg(args, "path").toString());
		String n = file.getName().toLowerCase(Locale.ROOT);
		if (!file.isAbsolute() || !(n.endsWith(".jos") || n.endsWith(".joz"))) {
			throw new Exception("path must be an absolute .jos or .joz file");
		}
		if (!file.isFile()) {
			throw new Exception("file not found: " + file);
		}
		MainApplication.worker.submit(new SessionLoadAction.Loader(file, n.endsWith(".joz")));
		return "Loading session " + file.getAbsolutePath() + " in the background; check get_josm_state in a moment";
	}

	private String restart() throws Exception {
		if (!RestartAction.isRestartSupported()) {
			throw new Exception("JOSM was not started with restart support (-Djosm.restart=true)");
		}
		// Give the HTTP response a moment to leave before the JVM goes down.
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(1500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			javax.swing.SwingUtilities.invokeLater(RestartAction::restartJOSM);
		}, "josmmcp-restart");
		t.setDaemon(true);
		t.start();
		return "JOSM restarts in a moment; reconnect when it is back";
	}
}
