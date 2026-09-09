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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.josmmcp.tools.CaptureMapView;
import org.openstreetmap.josm.plugins.josmmcp.tools.CreateNode;
import org.openstreetmap.josm.plugins.josmmcp.tools.CreateWay;
import org.openstreetmap.josm.plugins.josmmcp.tools.DeleteNode;
import org.openstreetmap.josm.plugins.josmmcp.tools.DeleteRelation;
import org.openstreetmap.josm.plugins.josmmcp.tools.DeleteWay;
import org.openstreetmap.josm.plugins.josmmcp.tools.GetUserSelection;
import org.openstreetmap.josm.plugins.josmmcp.tools.ModifyTags;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReadNode;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReadRelation;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReadWay;
import org.openstreetmap.josm.plugins.josmmcp.tools.SearchTool;
import org.openstreetmap.josm.plugins.josmmcp.tools.SetLayerVisibility;
import org.openstreetmap.josm.plugins.josmmcp.tools.StateTool;
import org.openstreetmap.josm.plugins.josmmcp.tools.UpdateNode;
import org.openstreetmap.josm.plugins.josmmcp.tools.UpdateRelation;
import org.openstreetmap.josm.plugins.josmmcp.tools.ValidateTool;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;

public class JosmMCPPlugin extends Plugin {
	/** Preference key for the TCP port the MCP server listens on. */
	public static final String PREF_PORT = "josmmcp.port";
	/** Preference key for the address the MCP server binds to. */
	public static final String PREF_HOST = "josmmcp.host";
	public static final int DEFAULT_PORT = 3000;
	/** Loopback only: the server has no authentication, so never expose it on the network by default. */
	public static final String DEFAULT_HOST = "127.0.0.1";

	private Server jettyServer;

	public JosmMCPPlugin(PluginInformation info) {
		super(info);

		Logging.info("JosmMCPPlugin initialization");
		String host = Config.getPref().get(PREF_HOST, DEFAULT_HOST);
		int port = Config.getPref().getInt(PREF_PORT, DEFAULT_PORT);
		try {
			this.jettyServer = new Server(new InetSocketAddress(host, port));
			// JOSM offers no plugin unload hook; make sure the port is released when the JVM exits.
			jettyServer.setStopAtShutdown(true);
			ServletContextHandler context = new ServletContextHandler();
			context.setContextPath("/");
			jettyServer.setHandler(context);

			List<McpStatelessServerFeatures.AsyncToolSpecification> toolSpecs = new ArrayList<>();
			toolSpecs.add(new SearchTool().getSpec());
			toolSpecs.add(new StateTool().getSpec());
			toolSpecs.add(new GetUserSelection().getSpec());
			toolSpecs.add(new CaptureMapView().getSpec());
			toolSpecs.add(new ValidateTool().getSpec());
			toolSpecs.add(new SetLayerVisibility().getSpec());

			toolSpecs.add(new ModifyTags().getSpec());

			// CRUD Operations on Nodes
			toolSpecs.add(new CreateNode().getSpec());
			toolSpecs.add(new ReadNode().getSpec());
			toolSpecs.add(new UpdateNode().getSpec());
			toolSpecs.add(new DeleteNode().getSpec());

			// CRUD Operations on Ways
			toolSpecs.add(new CreateWay().getSpec());
			toolSpecs.add(new ReadWay().getSpec());
			//toolSpecs.add(new UpdateWay().getSpec());
			toolSpecs.add(new DeleteWay().getSpec());

			// CRUD Operations on Relations
			//toolSpecs.add(new CreateRelation().getSpec());
			toolSpecs.add(new ReadRelation().getSpec());
			toolSpecs.add(new UpdateRelation().getSpec());
			toolSpecs.add(new DeleteRelation().getSpec());

			HttpServletStatelessServerTransport servlet = HttpServletStatelessServerTransport.builder().build();
			McpStatelessAsyncServer server = McpServer.async(servlet).serverInfo("JOSM MCP Server", "1.0.0")
					.tools(toolSpecs).build();
			context.addServlet(new ServletHolder(servlet), "/mcp");
			jettyServer.start();
			Logging.info(String.format("MCP HTTP server started on http://%s:%d/mcp", host, port));
		} catch (Exception e) {
			Logging.error(String.format("Failed to start MCP server on %s:%d: %s", host, port, e.getMessage()), e);
		}
	}
}
