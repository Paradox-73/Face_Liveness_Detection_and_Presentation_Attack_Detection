/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

/**
 * Size and position of the face liveness window on the operator's monitor.
 *
 * <p>Rule: the window takes the height its content needs, but never more than the visual bounds of the monitor it
 * is on (the screen without the taskbar). When the content is taller, the window is capped at that height and its
 * content scrolls; the window is then widened by the width of the vertical scroll bar so the content keeps its
 * width. The window is then moved, as little as possible, so that it lies fully inside the visual bounds; its top
 * and left edges stay visible when the window is larger than the monitor. So Retry and Close are always reachable,
 * also on a 1366x768 screen where the content needs about 760 px.
 *
 * <p>Pure arithmetic without JavaFX types, so the rule is unit-tested on its own; {@link LivenessDialog} applies it.
 */
final class LivenessDialogSizing {

	private LivenessDialogSizing() {
	}

	/** A rectangle in screen coordinates. */
	static final class Area {

		final double minX;
		final double minY;
		final double width;
		final double height;

		/**
		 * Creates an area.
		 *
		 * @param minX   left edge
		 * @param minY   top edge
		 * @param width  width, at least 0
		 * @param height height, at least 0
		 */
		Area(double minX, double minY, double width, double height) {
			if (width < 0 || height < 0 || Double.isNaN(width) || Double.isNaN(height)) {
				throw new IllegalArgumentException("negative or undefined size");
			}
			this.minX = minX;
			this.minY = minY;
			this.width = width;
			this.height = height;
		}

		double maxX() {
			return minX + width;
		}

		double maxY() {
			return minY + height;
		}
	}

	/** Where the window goes and whether its content scrolls. */
	static final class Placement {

		final double x;
		final double y;
		final double width;
		final double height;
		final boolean scrolls;

		Placement(double x, double y, double width, double height, boolean scrolls) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.scrolls = scrolls;
		}

		@Override
		public String toString() {
			return "Placement[x=" + x + ", y=" + y + ", width=" + width + ", height=" + height + ", scrolls="
					+ scrolls + "]";
		}
	}

	/**
	 * Fits the window to the visual bounds of its monitor.
	 *
	 * @param contentWidth    width the window content needs (preferred width of the panel)
	 * @param contentHeight   height the window content needs (preferred height of the panel)
	 * @param frameWidth      width of the window decorations (left and right borders), at least 0
	 * @param frameHeight     height of the window decorations (title bar and borders), at least 0
	 * @param scrollBarWidth  width of the vertical scroll bar, at least 0
	 * @param x               current left edge of the window
	 * @param y               current top edge of the window
	 * @param visualBounds    visual bounds of the monitor the window is on
	 * @return size and position of the window
	 */
	static Placement fit(double contentWidth, double contentHeight, double frameWidth, double frameHeight,
			double scrollBarWidth, double x, double y, Area visualBounds) {
		double frameW = Math.max(0, frameWidth);
		double frameH = Math.max(0, frameHeight);
		double neededHeight = Math.max(0, contentHeight) + frameH;
		boolean scrolls = neededHeight > visualBounds.height;
		double height = Math.min(neededHeight, visualBounds.height);
		double neededWidth = Math.max(0, contentWidth) + frameW + (scrolls ? Math.max(0, scrollBarWidth) : 0);
		double width = Math.min(neededWidth, visualBounds.width);
		return new Placement(clamp(x, visualBounds.minX, visualBounds.maxX() - width),
				clamp(y, visualBounds.minY, visualBounds.maxY() - height), width, height, scrolls);
	}

	/** Moves a start coordinate into [min, max]; {@code min} wins when the range is empty. */
	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(value, max));
	}
}
