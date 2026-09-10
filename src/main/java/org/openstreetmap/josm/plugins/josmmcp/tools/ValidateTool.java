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

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.validation.OsmValidator;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;
import org.openstreetmap.josm.tools.Logging;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Runs JOSM's validator (the same tests as Validation &gt; Validate) over the changed
 * objects, the selection or the whole dataset and reports the results.
 */
public class ValidateTool extends BaseTool {

	@Override
	public String getName() {
		return "validate";
	}

	@Override
	public String getDescription() {
		return "Run JOSM's validator over the pending changes (default), the current selection or the whole "
				+ "data layer, using the tests enabled in JOSM's validator preferences. Returns errors and warnings "
				+ "with the affected elements. Does not modify anything.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		Map<String, Object> scope = new HashMap<>();
		scope.put("type", "string");
		scope.put("enum", new ArrayList<>(Arrays.asList("changes", "selection", "all", "bbox", "elements")));
		scope.put("description", "What to validate: 'changes' = modified or new elements (default), "
				+ "'selection' = the user's current selection, 'all' = every element in the layer, 'bbox' = everything "
				+ "intersecting the given bbox (implied when bbox is given), 'elements' = the given elements plus, for "
				+ "nodes, their parent ways (implied when elements is given)");
		props.put("scope", scope);
		props.put("bbox", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 4, "maxItems", 4,
				"description", "[min_lon, min_lat, max_lon, max_lat]: validate everything intersecting this box"));
		Map<String, Object> el = new HashMap<>();
		el.put("type", "object");
		el.put("properties", Map.of("type", Map.of("type", "string", "enum", Arrays.asList("node", "way", "relation")),
				"id", Map.of("type", "integer")));
		el.put("required", Arrays.asList("type", "id"));
		props.put("elements", Map.of("type", "array", "items", el, "minItems", 1, "maxItems", 5000,
				"description", "Validate exactly these elements (nodes bring their parent ways along)"));
		props.put("tests", Map.of("type", "array", "items", Map.of("type", "string"),
				"description", "Only run validator tests whose name contains one of these strings (case-insensitive), "
						+ "e.g. [\"Crossing\", \"Duplicate\"]; default all enabled tests"));
		props.put("max_findings", Map.of("type", "integer",
				"description", "Return at most this many findings (default 500); the per-message summary always covers all"));
		Map<String, Object> other = new HashMap<>();
		other.put("type", "boolean");
		other.put("description", "Also include informational findings of severity 'other' (default false)");
		props.put("include_other", other);
		Map<String, Object> upload = new HashMap<>();
		upload.put("type", "boolean");
		upload.put("description", "Run the test set JOSM uses when checking before upload (tests enabled 'on upload' "
				+ "in the validator preferences). Default false = the regular Validate action. Checks added by other "
				+ "plugins through their own upload hooks (e.g. PT_Assistant) are not included.");
		props.put("before_upload", upload);
		props.put("fix", Map.of("type", "boolean",
				"description", "Apply JOSM's automatic fix for every finding that offers one (default false). Each fix becomes an undo step."));
		return new McpSchema.JsonSchema("object", props, null, null, null, null);
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Object scopeObj = args == null ? null : args.get("scope");
		Object bboxObj = args == null ? null : args.get("bbox");
		Object elementsObj = args == null ? null : args.get("elements");
		String scope = scopeObj != null ? scopeObj.toString() : bboxObj != null ? "bbox" : elementsObj != null ? "elements" : "changes";
		int maxFindings = getInt(args, "max_findings", 500);
		List<String> testFilter = new ArrayList<>();
		if (args != null && args.get("tests") instanceof List) {
			for (Object t : (List<?>) args.get("tests")) {
				testFilter.add(String.valueOf(t).toLowerCase(java.util.Locale.ROOT));
			}
		}
		boolean includeOther = args != null && Boolean.TRUE.equals(args.get("include_other"));
		boolean beforeUpload = args != null && Boolean.TRUE.equals(args.get("before_upload"));
		boolean fix = args != null && Boolean.TRUE.equals(args.get("fix"));
		if (fix && org.openstreetmap.josm.plugins.josmmcp.Prefs.readOnly()) {
			throw new Exception("fix=true modifies data and is disabled in read-only mode");
		}

		Collection<OsmPrimitive> targets = new ArrayList<>();
		boolean partial = true;
		switch (scope) {
		case "changes":
			for (OsmPrimitive p : ds.allPrimitives()) {
				if (!p.isDeleted() && (p.isModified() || p.isNew())) {
					targets.add(p);
					// A moved node changes every way it belongs to: include those parents, as
					// JOSM's upload check does, so crossing/self-crossing tests can see them.
					if (p instanceof org.openstreetmap.josm.data.osm.Node) {
						for (OsmPrimitive parent : p.getReferrers()) {
							if (!parent.isDeleted() && !parent.isIncomplete()) {
								targets.add(parent);
							}
						}
					}
				}
			}
			targets = new java.util.LinkedHashSet<>(targets);
			break;
		case "selection":
			targets.addAll(ds.getAllSelected());
			break;
		case "all":
			targets.addAll(ds.allNonDeletedPrimitives());
			partial = false;
			break;
		case "bbox": {
			if (!(bboxObj instanceof List) || ((List<?>) bboxObj).size() != 4) {
				throw new Exception("scope bbox needs bbox [min_lon, min_lat, max_lon, max_lat]");
			}
			List<?> b = (List<?>) bboxObj;
			org.openstreetmap.josm.data.osm.BBox box = new org.openstreetmap.josm.data.osm.BBox(toDouble(b.get(0), "bbox"),
					toDouble(b.get(1), "bbox"), toDouble(b.get(2), "bbox"), toDouble(b.get(3), "bbox"));
			if (!box.isValid()) {
				throw new Exception("bbox is not valid");
			}
			java.util.LinkedHashSet<OsmPrimitive> set = new java.util.LinkedHashSet<>();
			for (OsmPrimitive p : ds.searchNodes(box)) {
				if (!p.isDeleted()) {
					set.add(p);
				}
			}
			for (OsmPrimitive p : ds.searchWays(box)) {
				if (!p.isDeleted() && !p.isIncomplete()) {
					set.add(p);
				}
			}
			for (OsmPrimitive p : ds.searchRelations(box)) {
				if (!p.isDeleted() && !p.isIncomplete()) {
					set.add(p);
				}
			}
			targets = set;
			break;
		}
		case "elements": {
			if (!(elementsObj instanceof List) || ((List<?>) elementsObj).isEmpty()) {
				throw new Exception("scope elements needs a non-empty elements array of {type, id}");
			}
			java.util.LinkedHashSet<OsmPrimitive> set = new java.util.LinkedHashSet<>();
			for (Object o : (List<?>) elementsObj) {
				if (!(o instanceof Map)) {
					throw new Exception("each element must be an object with type and id");
				}
				Map<?, ?> m = (Map<?, ?>) o;
				long id = toLong(m.get("id"), "id");
				OsmPrimitive p = ds.getPrimitiveById(new org.openstreetmap.josm.data.osm.SimplePrimitiveId(id,
						org.openstreetmap.josm.data.osm.OsmPrimitiveType.from(String.valueOf(m.get("type")))));
				if (p == null || p.isDeleted() || p.isIncomplete()) {
					throw new Exception(m.get("type") + " " + id + " not found");
				}
				set.add(p);
				if (p instanceof org.openstreetmap.josm.data.osm.Node) {
					for (OsmPrimitive parent : p.getReferrers()) {
						if (!parent.isDeleted() && !parent.isIncomplete()) {
							set.add(parent);
						}
					}
				}
			}
			targets = set;
			break;
		}
		default:
			throw new Exception("scope must be changes, selection, all, bbox or elements");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("scope", scope);
		result.put("mode", beforeUpload ? "before_upload" : "validate");
		result.put("validated_elements", targets.size());
		if (targets.isEmpty()) {
			result.put("errors", 0);
			result.put("warnings", 0);
			result.put("findings", new ArrayList<>());
			return JosmUtils.toJson(result);
		}

		OsmValidator.initializeTests();
		Collection<Test> tests = OsmValidator.getEnabledTests(beforeUpload);
		if (!testFilter.isEmpty()) {
			List<Test> kept = new ArrayList<>();
			for (Test t : tests) {
				String name = (t.getName() + " " + t.getClass().getSimpleName()).toLowerCase(java.util.Locale.ROOT);
				for (String f : testFilter) {
					if (name.contains(f)) {
						kept.add(t);
						break;
					}
				}
			}
			tests = kept;
			result.put("tests_filter", testFilter);
		}
		List<TestError> errors = new ArrayList<>();
		List<String> failedTests = new ArrayList<>();
		for (Test test : tests) {
			try {
				test.setBeforeUpload(beforeUpload);
				// Note: plugins such as PT_Assistant validate through their own UploadHook, not via
				// OsmValidator, so their findings cannot be reproduced here. Running without partial
				// selection only adds noise (e.g. unconnected-way warnings on untouched ways).
				test.setPartialSelection(partial);
				test.startTest(NullProgressMonitor.INSTANCE);
				test.visit(targets);
				test.endTest();
				errors.addAll(test.getErrors());
			} catch (RuntimeException e) {
				Logging.warn("Validator test " + test.getName() + " failed: " + e);
				failedTests.add(test.getName() + ": " + e);
			} finally {
				test.clear();
			}
		}

		int nFixed = 0;
		if (fix) {
			for (TestError err : errors) {
				if (!err.isIgnored() && err.isFixable()) {
					org.openstreetmap.josm.command.Command c = err.getFix();
					if (c != null) {
						addCommand(c, tr("Fix: {0}", err.getMessage()));
						nFixed++;
					}
				}
			}
		}
		int nErrors = 0;
		int nWarnings = 0;
		int nOther = 0;
		int nIgnored = 0;
		List<Map<String, Object>> findings = new ArrayList<>();
		Map<String, Integer> byMessage = new TreeMap<>();
		for (TestError err : errors) {
			if (err.isIgnored()) {
				nIgnored++;
				continue;
			}
			Severity sev = err.getSeverity();
			if (sev == Severity.ERROR) {
				nErrors++;
			} else if (sev == Severity.WARNING) {
				nWarnings++;
			} else {
				nOther++;
				if (!includeOther) {
					continue;
				}
			}
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("severity", sev.toString().toLowerCase(java.util.Locale.ROOT));
			f.put("test", err.getTester() == null ? null : err.getTester().getName());
			f.put("message", err.getMessage());
			if (err.getDescription() != null && !err.getDescription().isEmpty()) {
				f.put("description", err.getDescription());
			}
			List<Map<String, Object>> prims = new ArrayList<>();
			for (OsmPrimitive p : err.getPrimitives()) {
				Map<String, Object> pm = new LinkedHashMap<>();
				pm.put("type", p.getType().getAPIName());
				pm.put("id", p.getUniqueId());
				prims.add(pm);
			}
			f.put("elements", prims);
			f.put("fixable", err.isFixable());
			findings.add(f);
			String key = sev.toString().toLowerCase(java.util.Locale.ROOT) + ": " + err.getMessage();
			byMessage.merge(key, 1, Integer::sum);
		}

		result.put("tests_run", tests.size());
		if (fix) {
			result.put("fixed", nFixed);
		}
		result.put("errors", nErrors);
		result.put("warnings", nWarnings);
		result.put("other", nOther);
		result.put("ignored", nIgnored);
		result.put("summary", byMessage);
		if (findings.size() > maxFindings) {
			result.put("findings_omitted", findings.size() - maxFindings);
			findings = new ArrayList<>(findings.subList(0, maxFindings));
		}
		result.put("findings", findings);
		if (!failedTests.isEmpty()) {
			result.put("failed_tests", failedTests);
		}
		return JosmUtils.toJson(result);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	protected boolean supportsOutputPath() {
		return true;
	}
}
