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
package org.openstreetmap.josm.plugins.josmmcp.utils;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Image helpers for returning renderings of the map view to MCP clients.
 */
public final class ImageUtils {
	private ImageUtils() {
	}

	/**
	 * Scales the image down so that its width does not exceed {@code maxWidth}.
	 * Images that already fit are returned unchanged.
	 */
	public static BufferedImage limitWidth(BufferedImage src, int maxWidth) {
		if (maxWidth <= 0 || src.getWidth() <= maxWidth) {
			return src;
		}
		double factor = (double) maxWidth / src.getWidth();
		int h = Math.max(1, (int) Math.round(src.getHeight() * factor));
		BufferedImage dst = new BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = dst.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, maxWidth, h, null);
		} finally {
			g.dispose();
		}
		return dst;
	}

	/**
	 * Encodes the image as "png" or "jpeg". {@code quality} (0..1) only applies to JPEG.
	 */
	public static byte[] encode(BufferedImage img, String format, float quality) throws IOException {
		String fmt = format == null ? "jpeg" : format.toLowerCase(Locale.ROOT);
		if ("jpg".equals(fmt)) {
			fmt = "jpeg";
		}
		if (!"jpeg".equals(fmt) && !"png".equals(fmt)) {
			throw new IOException("unsupported image format: " + format);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if ("png".equals(fmt)) {
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		}
		// JPEG has no alpha channel; make sure we hand the writer an RGB image.
		BufferedImage rgb = img;
		if (img.getType() != BufferedImage.TYPE_INT_RGB) {
			rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = rgb.createGraphics();
			try {
				g.drawImage(img, 0, 0, null);
			} finally {
				g.dispose();
			}
		}
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IOException("no JPEG writer available");
		}
		ImageWriter writer = writers.next();
		try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
			writer.setOutput(ios);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
			writer.write(null, new IIOImage(rgb, null, null), param);
		} finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

	public static String mimeType(String format) {
		String fmt = format == null ? "jpeg" : format.toLowerCase(Locale.ROOT);
		return "png".equals(fmt) ? "image/png" : "image/jpeg";
	}
}
