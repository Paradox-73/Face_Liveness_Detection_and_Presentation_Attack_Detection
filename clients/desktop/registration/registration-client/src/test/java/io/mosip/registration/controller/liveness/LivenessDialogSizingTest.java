package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.mosip.registration.controller.liveness.LivenessDialogSizing.Area;
import io.mosip.registration.controller.liveness.LivenessDialogSizing.Placement;

/** The liveness window never extends past the visual bounds of the operator's monitor. */
public class LivenessDialogSizingTest {

	private static final double DELTA = 0.001;
	/** Panel width of {@code LivenessPanel.fxml}. */
	private static final double PANEL_WIDTH = 520;
	private static final double FRAME_WIDTH = 16;
	private static final double FRAME_HEIGHT = 39;
	/**
	 * Content height after the last attempt with the override button (759 px in the e2e-07 run; the panel measures
	 * 757 px plus the 2 px scroll area border in that state).
	 */
	private static final double TALL_CONTENT = 759;
	private static final double SCROLL_BAR = 16;
	/** 1366x768 laptop screen with a 40 px taskbar at the bottom. */
	private static final Area LAPTOP = new Area(0, 0, 1366, 728);

	@Test
	public void tallContentOnA1366x768ScreenIsCappedAtTheVisualBoundsAndScrolls() {
		Placement p = LivenessDialogSizing.fit(PANEL_WIDTH, TALL_CONTENT, FRAME_WIDTH, FRAME_HEIGHT, SCROLL_BAR, 423,
				5, LAPTOP);

		assertEquals(728, p.height, DELTA);
		assertTrue(p.scrolls);
		assertEquals("bottom edge (with Retry and Close) is on screen", 728, p.y + p.height, DELTA);
		assertEquals(0, p.y, DELTA);
		assertEquals("widened by the scroll bar so the panel keeps its width",
				PANEL_WIDTH + FRAME_WIDTH + SCROLL_BAR, p.width, DELTA);
		assertEquals(423, p.x, DELTA);
	}

	@Test
	public void contentThatFitsKeepsItsNaturalSizeAndPosition() {
		Placement p = LivenessDialogSizing.fit(PANEL_WIDTH, 600, FRAME_WIDTH, FRAME_HEIGHT, SCROLL_BAR, 400, 50,
				new Area(0, 0, 1920, 1040));

		assertFalse(p.scrolls);
		assertEquals(639, p.height, DELTA);
		assertEquals(536, p.width, DELTA);
		assertEquals(400, p.x, DELTA);
		assertEquals(50, p.y, DELTA);
	}

	@Test
	public void windowThatGrewPastTheBottomIsMovedUpNotShrunk() {
		// fits in height, but the operator placed it low on the screen
		Placement p = LivenessDialogSizing.fit(PANEL_WIDTH, 600, FRAME_WIDTH, FRAME_HEIGHT, SCROLL_BAR, 400, 300,
				LAPTOP);

		assertFalse(p.scrolls);
		assertEquals(639, p.height, DELTA);
		assertEquals(728 - 639, p.y, DELTA);
	}

	@Test
	public void exactFitDoesNotScroll() {
		Placement p = LivenessDialogSizing.fit(PANEL_WIDTH, 728 - FRAME_HEIGHT, FRAME_WIDTH, FRAME_HEIGHT,
				SCROLL_BAR, 0, 0, LAPTOP);

		assertFalse(p.scrolls);
		assertEquals(728, p.height, DELTA);
		assertEquals(536, p.width, DELTA);
	}

	@Test
	public void secondMonitorWithOffsetAndTopTaskbarIsRespected() {
		// monitor right of the primary one, taskbar of 48 px at its top
		Area second = new Area(1920, 48, 1366, 720);

		Placement p = LivenessDialogSizing.fit(PANEL_WIDTH, TALL_CONTENT, FRAME_WIDTH, FRAME_HEIGHT, SCROLL_BAR,
				1900, 0, second);

		assertTrue(p.scrolls);
		assertEquals(720, p.height, DELTA);
		assertEquals("top edge below the taskbar", 48, p.y, DELTA);
		assertEquals("left edge moved onto the monitor", 1920, p.x, DELTA);
		assertTrue(p.x + p.width <= second.maxX());
	}

	@Test
	public void screenNarrowerThanTheWindowKeepsTheLeftEdgeVisible() {
		Placement p = LivenessDialogSizing.fit(PANEL_WIDTH, 400, FRAME_WIDTH, FRAME_HEIGHT, SCROLL_BAR, 100, 0,
				new Area(0, 0, 500, 600));

		assertEquals(500, p.width, DELTA);
		assertEquals(0, p.x, DELTA);
	}

	@Test
	public void unknownFrameSizesCountAsZero() {
		Placement p = LivenessDialogSizing.fit(PANEL_WIDTH, 600, -1, -1, SCROLL_BAR, 0, 0, LAPTOP);

		assertEquals(600, p.height, DELTA);
		assertEquals(PANEL_WIDTH, p.width, DELTA);
	}

	@Test(expected = IllegalArgumentException.class)
	public void negativeScreenSizeIsRefused() {
		new Area(0, 0, -1, 768);
	}
}
