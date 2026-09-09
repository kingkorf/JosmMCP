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
package org.openstreetmap.josm.plugins.josmmcp.server;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.openstreetmap.josm.plugins.josmmcp.tools.BaseTool;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.DispatcherType;
import reactor.core.publisher.Mono;

/**
 * Builds the Jetty server that hosts the MCP endpoint. Kept separate from the plugin class so
 * it can be started in tests without a JOSM plugin context.
 */
public final class McpServerRunner {
	public static final String ENDPOINT = "/mcp";

	private McpServerRunner() {
	}

	/**
	 * Creates (but does not start) the server.
	 *
	 * @param host bind address
	 * @param port TCP port, 0 for an ephemeral port
	 * @param tools tools to expose
	 * @param tokenSupplier returns the required bearer token, empty string for none
	 * @param stateSupplier produces the JSON exposed as the josm://state resource
	 * @param selectionSupplier produces the JSON exposed as the josm://selection resource
	 */
	public static Server create(String host, int port, List<BaseTool> tools, Supplier<String> tokenSupplier,
			Supplier<String> stateSupplier, Supplier<String> selectionSupplier) {
		Server server = new Server(new InetSocketAddress(host, port));
		server.setStopAtShutdown(true);
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		server.setHandler(context);

		context.addFilter(new FilterHolder(new SecurityFilter(host, tokenSupplier)), "/*",
				EnumSet.of(DispatcherType.REQUEST));

		List<McpStatelessServerFeatures.AsyncToolSpecification> specs = new ArrayList<>();
		for (BaseTool t : tools) {
			specs.add(t.getSpec());
		}
		HttpServletStatelessServerTransport servlet = HttpServletStatelessServerTransport.builder().build();
		McpServer.async(servlet).serverInfo("JOSM MCP Server", version()).tools(specs)
				.resources(resources(stateSupplier, selectionSupplier)).prompts(prompts()).build();
		context.addServlet(new ServletHolder(servlet), ENDPOINT);
		return server;
	}

	static String version() {
		Package p = McpServerRunner.class.getPackage();
		String v = p == null ? null : p.getImplementationVersion();
		return v == null ? "dev" : v;
	}

	private static List<McpStatelessServerFeatures.AsyncResourceSpecification> resources(Supplier<String> state,
			Supplier<String> selection) {
		return Arrays.asList(resource("josm://state", "JOSM state",
				"Layers and statistics of the active data layer, as JSON (same as get_josm_state)", state),
				resource("josm://selection", "JOSM selection",
						"The objects currently selected by the user, as JSON (same as get_user_selection)", selection));
	}

	private static McpStatelessServerFeatures.AsyncResourceSpecification resource(String uri, String name,
			String description, Supplier<String> supplier) {
		McpSchema.Resource res = McpSchema.Resource.builder().uri(uri).name(name).description(description)
				.mimeType("application/json").build();
		return new McpStatelessServerFeatures.AsyncResourceSpecification(res, (ctx, req) -> Mono.fromCallable(() -> {
			String json;
			try {
				json = supplier.get();
			} catch (RuntimeException e) {
				json = "{\"error\": \"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
			}
			return new McpSchema.ReadResourceResult(
					Collections.singletonList(new McpSchema.TextResourceContents(uri, "application/json", json)));
		}));
	}

	private static List<McpStatelessServerFeatures.AsyncPromptSpecification> prompts() {
		return Arrays.asList(prompt("review-area", "Analyse the loaded area and propose improvements",
				"You are helping an OpenStreetMap mapper in JOSM. Start with get_josm_state, then use search_elements "
						+ "to measure completeness (names, surface, maxspeed, sidewalks, opening hours, wheelchair, parking types, "
						+ "buildings without ref:bag or building:levels). Report counts in a table, then list improvements in "
						+ "order of value, separating what can be done from the desk (official open data, imagery) from what "
						+ "needs a survey. Do not change anything until the mapper asks."),
				prompt("bag-sync", "Synchronise buildings with the Dutch BAG register",
						"Compare the buildings in the active layer with the current BAG (Basisregistratie Adressen en Gebouwen): "
								+ "match on ref:bag, check start_date against the BAG bouwjaar, building=construction against the BAG "
								+ "status, find BAG panden without an OSM building and OSM refs the BAG no longer knows. Apply only "
								+ "unambiguous changes (start_date sync, finished constructions) and list everything else with the "
								+ "evidence. Never delete existing objects without the mapper's explicit approval. Run validate before "
								+ "reporting, and never upload."),
				prompt("bus-stops-chb", "Check bus stops against the Dutch CHB stop register",
						"Compare the bus stops in the loaded area with the Centraal Halte Bestand (CHB). Download the latest "
								+ "ExportCHB_<date>.xml.gz from https://data.ndovloket.nl/haltes/ (about 14 MB gzipped, 350 MB XML). "
								+ "The XML uses the namespace prefix ns1: on every element: stop places are <ns1:stopplace> with "
								+ "<ns1:stopplacecode> (NL:S:...), <ns1:publicname>, <ns1:town>, <ns1:street>; each contains <ns1:quay> "
								+ "elements with <ns1:quaycode> (NL:Q:..., the value of ref:IFOPT in OSM), <ns1:quaystatus>, "
								+ "<ns1:compassdirection>, <ns1:shelter>, <ns1:bench>, and coordinates <ns1:rd-x>/<ns1:rd-y> in the "
								+ "Dutch RD system (EPSG:28992), which must be converted to WGS84. Filter stop places by town, then match "
								+ "each quay to an OSM node by ref:IFOPT and check name, position (a few metres is normal), shelter and "
								+ "bench. Quays outside the loaded area may exist in OSM anyway: check with download_overpass "
								+ "(node[\"ref:IFOPT\"=\"NL:Q:...\"]; out meta;) before adding anything. Platform ways without a name "
								+ "and route relations are not stops and need no name or ref:IFOPT."),
				prompt("surface-from-bgt", "Derive surface tags from the BGT",
						"For highways without a surface tag, look up the BGT wegdeel polygons (fysiek voorkomen) under each way, "
								+ "sample the way every few metres, and only tag ways where at least 60% of the samples agree and the "
								+ "BGT function matches the highway type. Map open verharding to paving_stones, asfalt to asphalt, "
								+ "cementbeton to concrete, half verhard to compacted/gravel/shells. Add source:surface=BGT. Do a dry "
								+ "run first and show per-type counts before applying."));
	}

	private static McpStatelessServerFeatures.AsyncPromptSpecification prompt(String name, String description,
			String text) {
		McpSchema.Prompt p = new McpSchema.Prompt(name, description, Collections.emptyList());
		return new McpStatelessServerFeatures.AsyncPromptSpecification(p, (ctx, req) -> Mono.just(
				new McpSchema.GetPromptResult(description, Collections.singletonList(
						new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))))));
	}

	/** Convenience for tests and the plugin: the tool list in its canonical order. */
	public static List<BaseTool> defaultTools() {
		return ToolRegistry.all();
	}

	/** Unused parameter holder to keep the API stable for tests. */
	public static Map<String, Object> emptyArgs() {
		return Collections.emptyMap();
	}
}
