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
package org.openstreetmap.josm.plugins.josmmcp;

import java.util.Collections;

import org.eclipse.jetty.server.Server;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.josmmcp.gui.JosmMCPPreferences;
import org.openstreetmap.josm.plugins.josmmcp.server.McpServerRunner;
import org.openstreetmap.josm.plugins.josmmcp.server.ToolRegistry;
import org.openstreetmap.josm.plugins.josmmcp.tools.GetUserSelection;
import org.openstreetmap.josm.plugins.josmmcp.tools.StateTool;
import org.openstreetmap.josm.tools.Logging;

public class JosmMCPPlugin extends Plugin {
	private Server jettyServer;

	public JosmMCPPlugin(PluginInformation info) {
		super(info);
		Logging.info("JosmMCPPlugin initialization");
		String host = Prefs.host();
		int port = Prefs.port();
		try {
			StateTool state = new StateTool();
			GetUserSelection selection = new GetUserSelection();
			jettyServer = McpServerRunner.create(host, port, ToolRegistry.all(), Prefs::token,
					() -> callQuietly(state), () -> callQuietly(selection));
			jettyServer.start();
			Logging.info(String.format("MCP HTTP server started on http://%s:%d%s%s", host, port,
					McpServerRunner.ENDPOINT, Prefs.token().isEmpty() ? " (no token configured)" : " (token required)"));
		} catch (Exception e) {
			Logging.error(String.format("Failed to start MCP server on %s:%d: %s", host, port, e.getMessage()), e);
		}
	}

	private static String callQuietly(org.openstreetmap.josm.plugins.josmmcp.tools.BaseTool tool) {
		try {
			return tool.callForResource(Collections.emptyMap());
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	@Override
	public PreferenceSetting getPreferenceSetting() {
		return new JosmMCPPreferences();
	}
}
