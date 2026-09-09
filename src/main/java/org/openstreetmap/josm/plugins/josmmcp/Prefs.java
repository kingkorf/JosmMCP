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

import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Preference keys and live accessors. Token, read-only mode and the output limit are read on
 * every request so changes in the preferences take effect without a restart; host and port
 * are only read when the server starts.
 */
public final class Prefs {
	public static final String HOST = "josmmcp.host";
	public static final String PORT = "josmmcp.port";
	public static final String TOKEN = "josmmcp.token";
	public static final String READ_ONLY = "josmmcp.readonly";
	public static final String MAX_OUTPUT_CHARS = "josmmcp.max_output_chars";
	public static final String CONFIRM_DESTRUCTIVE = "josmmcp.confirm_destructive";
	public static final String CONFIRM_TIMEOUT = "josmmcp.confirm_timeout_seconds";
	public static final String AUDIT = "josmmcp.audit";
	/** Prefix for per-category permissions, e.g. josmmcp.allow.delete */
	public static final String ALLOW_PREFIX = "josmmcp.allow.";

	public static final String DEFAULT_HOST = "127.0.0.1";
	public static final int DEFAULT_PORT = 3000;
	public static final int DEFAULT_MAX_OUTPUT_CHARS = 200_000;
	public static final int DEFAULT_CONFIRM_TIMEOUT = 60;

	private Prefs() {
	}

	public static String host() {
		return Config.getPref().get(HOST, DEFAULT_HOST);
	}

	public static int port() {
		return Config.getPref().getInt(PORT, DEFAULT_PORT);
	}

	/** The access token, or an empty string when no token is required. */
	public static String token() {
		return Config.getPref().get(TOKEN, "").trim();
	}

	public static boolean readOnly() {
		return Config.getPref().getBoolean(READ_ONLY, false);
	}

	public static boolean confirmDestructive() {
		return Config.getPref().getBoolean(CONFIRM_DESTRUCTIVE, true);
	}

	public static int confirmTimeoutSeconds() {
		int n = Config.getPref().getInt(CONFIRM_TIMEOUT, DEFAULT_CONFIRM_TIMEOUT);
		return n < 5 ? 5 : n;
	}

	public static boolean audit() {
		return Config.getPref().getBoolean(AUDIT, true);
	}

	/** Whether tools of the given category may run (all categories are allowed by default). */
	public static boolean allowed(org.openstreetmap.josm.plugins.josmmcp.tools.Tool.Category category) {
		return Config.getPref().getBoolean(ALLOW_PREFIX + category.prefKey, true);
	}

	public static int maxOutputChars() {
		int n = Config.getPref().getInt(MAX_OUTPUT_CHARS, DEFAULT_MAX_OUTPUT_CHARS);
		return n <= 0 ? Integer.MAX_VALUE : n;
	}
}
