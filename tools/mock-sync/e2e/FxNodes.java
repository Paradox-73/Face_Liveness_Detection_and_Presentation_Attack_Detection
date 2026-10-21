/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Toggle;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import javafx.stage.Window;

/**
 * Everything the driver does to the client's JavaFX scenes: find nodes, click, fill, select, wait for texts,
 * close alerts, take snapshots. Calls that touch nodes run on the JavaFX thread through {@link #onFx}.
 */
final class FxNodes {

	private static final double MASK_MIN_SIDE = 60;

	private final FlowContext c;

	FxNodes(FlowContext c) {
		this.c = c;
	}

	// ---------------------------------------------------------------------------------------------- thread

	interface Check {
		boolean test() throws Exception;
	}

	interface FxCall<T> {
		T call() throws Exception;
	}

	boolean fxStarted() {
		try {
			Platform.runLater(() -> {
			});
			return true;
		} catch (IllegalStateException notStarted) {
			return false;
		}
	}

	void onFxQuiet(FxCall<?> call) {
		try {
			if (fxStarted()) {
				onFx(call);
			}
		} catch (Exception ignored) {
			// best effort
		}
	}

	<T> T onFx(FxCall<T> call) throws Exception {
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Exception> error = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				result.set(call.call());
			} catch (Exception e) {
				error.set(e);
			} finally {
				done.countDown();
			}
		});
		if (!done.await(20, TimeUnit.SECONDS)) {
			return null;
		}
		if (error.get() != null) {
			throw error.get();
		}
		return result.get();
	}

	// ---------------------------------------------------------------------------------------------- waiting

	boolean waitFor(Check check, long seconds) throws Exception {
		long end = System.currentTimeMillis() + seconds * 1000;
		while (System.currentTimeMillis() < end) {
			if (fxStarted() && Boolean.TRUE.equals(onFx(check::test))) {
				return true;
			}
			Thread.sleep(250);
		}
		return false;
	}

	boolean waitText(Pattern pattern, long seconds, boolean present) throws Exception {
		return waitFor(() -> pattern.matcher(allText()).find() == present, seconds);
	}

	boolean waitAlert(Pattern pattern, long seconds) throws Exception {
		long end = System.currentTimeMillis() + seconds * 1000;
		while (System.currentTimeMillis() < end) {
			for (int i = c.alertMark; i < c.alerts.size(); i++) {
				if (pattern.matcher(c.alerts.get(i)).find()) {
					return true;
				}
			}
			if (fxStarted()) {
				String open = onFx(this::openAlertText);
				if (open != null && pattern.matcher(open).find()) {
					return true;
				}
			}
			Thread.sleep(250);
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------- actions

	/**
	 * Clicks a node. The node is found on the JavaFX thread, and the click itself is posted without waiting for it:
	 * a handler that opens a modal dialog ({@code showAndWait}) keeps running while the next commands work on that
	 * dialog.
	 */
	void click(String selector, long seconds) throws Exception {
		c.require(waitFor(() -> clickable(selector) != null, seconds), "not clickable: " + selector);
		AtomicReference<Node> target = new AtomicReference<>();
		onFx(() -> {
			target.set(clickable(selector));
			return null;
		});
		Node node = target.get();
		c.require(node != null, "click failed: " + selector);
		c.logLine("  " + (node instanceof ButtonBase ? "fired " : "clicked ") + onFx(() -> describeNode(node)));
		Platform.runLater(() -> {
			if (node instanceof ButtonBase) {
				((ButtonBase) node).fire();
			} else {
				dispatchClick(node);
			}
		});
		Thread.sleep(150);
	}

	private void dispatchClick(Node node) {
		Bounds b = node.getLayoutBounds();
		double x = b.getMinX() + b.getWidth() / 2;
		double y = b.getMinY() + b.getHeight() / 2;
		javafx.geometry.Point2D s = node.localToScreen(x, y);
		double sx = s == null ? 0 : s.getX();
		double sy = s == null ? 0 : s.getY();
		for (javafx.event.EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED,
				MouseEvent.MOUSE_CLICKED)) {
			Event.fireEvent(node, new MouseEvent(type, x, y, sx, sy, MouseButton.PRIMARY, 1, false, false, false, false,
					type == MouseEvent.MOUSE_PRESSED, false, false, true, false, true, null));
		}
	}

	void fill(String selector, String text) throws Exception {
		c.require(waitFor(() -> field(selector) != null, FlowContext.DEFAULT_WAIT_SECONDS), "no text field " + selector);
		onFx(() -> {
			TextInputControl field = field(selector);
			field.requestFocus();
			field.setText(text);
			return null;
		});
	}

	/** The text field for a selector; {@code dialog} is the first text field of the newest open dialog window. */
	private TextInputControl field(String selector) {
		if ("dialog".equals(selector)) {
			for (Window w : windows()) {
				if (w.getScene().getRoot() instanceof DialogPane) {
					List<Node> all = new ArrayList<>();
					collect(w.getScene().getRoot(), all);
					for (Node n : all) {
						if (n instanceof TextInputControl && shown(n)) {
							return (TextInputControl) n;
						}
					}
				}
			}
			return null;
		}
		Node n = shownNode(selector);
		return n instanceof TextInputControl ? (TextInputControl) n : null;
	}

	@SuppressWarnings("unchecked")
	void select(String selector, String value) throws Exception {
		c.require(waitFor(() -> {
			Node n = shownNode(selector);
			return n instanceof ComboBox && !((ComboBox<?>) n).getItems().isEmpty()
					|| n instanceof ListView && !((ListView<?>) n).getItems().isEmpty();
		}, FlowContext.DEFAULT_WAIT_SECONDS), "no items in " + selector);
		String chosen = onFx(() -> {
			Node n = shownNode(selector);
			List<?> items = n instanceof ComboBox ? ((ComboBox<?>) n).getItems() : ((ListView<?>) n).getItems();
			int index = -1;
			if (value.matches("\\d+")) {
				index = Integer.parseInt(value);
			} else {
				for (int i = 0; i < items.size(); i++) {
					if (String.valueOf(items.get(i)).contains(value)) {
						index = i;
						break;
					}
				}
			}
			if (index < 0 || index >= items.size()) {
				return null;
			}
			if (n instanceof ComboBox) {
				((ComboBox<Object>) n).getSelectionModel().select(index);
			} else {
				((ListView<Object>) n).getSelectionModel().select(index);
			}
			return String.valueOf(items.get(index));
		});
		c.require(chosen != null, "no item " + value + " in " + selector);
		c.logLine("  selected " + chosen);
	}

	void check(String selector, boolean value) throws Exception {
		c.require(waitFor(() -> shownNode(selector) != null, FlowContext.DEFAULT_WAIT_SECONDS), "no check box " + selector);
		onFx(() -> {
			Node n = shownNode(selector);
			if (n instanceof CheckBox) {
				CheckBox box = (CheckBox) n;
				if (box.isSelected() != value) {
					box.fire();
				}
			} else if (n instanceof Toggle) {
				((Toggle) n).setSelected(value);
			}
			return null;
		});
	}

	/** Fires the first menu item (in any menu bar of a showing window) whose text contains the given text. */
	Boolean fireMenuItem(String text) {
		for (Window w : windows()) {
			List<Node> all = new ArrayList<>();
			collect(w.getScene().getRoot(), all);
			for (Node n : all) {
				if (!(n instanceof MenuBar)) {
					continue;
				}
				for (Menu menu : ((MenuBar) n).getMenus()) {
					for (MenuItem item : menu.getItems()) {
						if (item.getText() != null && item.getText().contains(text) && !item.isDisable()) {
							c.logLine("  menu item " + item.getText());
							item.fire();
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	Boolean pressDialog(String button) {
		ButtonType type;
		switch (button.toUpperCase(Locale.ROOT)) {
		case "CANCEL":
			type = ButtonType.CANCEL;
			break;
		case "YES":
			type = ButtonType.YES;
			break;
		case "NO":
			type = ButtonType.NO;
			break;
		default:
			type = ButtonType.OK;
		}
		for (Window window : windows()) {
			if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane) {
				DialogPane pane = (DialogPane) window.getScene().getRoot();
				Node b = pane.lookupButton(type);
				if (b instanceof ButtonBase) {
					c.logLine("  dialog: " + pane.getContentText());
					((ButtonBase) b).fire();
					return true;
				}
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------- scene lookup

	private List<Window> windows() {
		List<Window> list = new ArrayList<>();
		for (Window w : Window.getWindows()) {
			if (w.isShowing() && w.getScene() != null) {
				list.add(w);
			}
		}
		Collections.reverse(list);
		return list;
	}

	/** Finds the first node for a selector in the showing windows, newest window first. */
	Node find(String selector) {
		for (Window w : windows()) {
			Node n = findIn(w.getScene().getRoot(), selector);
			if (n != null) {
				return n;
			}
		}
		return null;
	}

	Node shownNode(String selector) {
		for (Window w : windows()) {
			List<Node> all = new ArrayList<>();
			collect(w.getScene().getRoot(), all);
			for (Node n : all) {
				if (matches(n, selector) && shown(n)) {
					return n;
				}
			}
		}
		return null;
	}

	private Node findIn(Node root, String selector) {
		if (selector.startsWith("#")) {
			List<Node> all = new ArrayList<>();
			collect(root, all);
			Node fallback = null;
			for (Node n : all) {
				if (matches(n, selector)) {
					if (shown(n)) {
						return n;
					}
					fallback = fallback == null ? n : fallback;
				}
			}
			return fallback;
		}
		List<Node> all = new ArrayList<>();
		collect(root, all);
		for (Node n : all) {
			if (matches(n, selector) && shown(n)) {
				return n;
			}
		}
		return null;
	}

	private boolean matches(Node n, String selector) {
		if (selector.startsWith("#")) {
			return selector.substring(1).equals(n.getId());
		}
		String text = textOf(n);
		if (text == null) {
			return false;
		}
		text = text.trim();
		if (selector.startsWith("text=")) {
			return text.equals(selector.substring(5));
		}
		if (selector.startsWith("text~")) {
			return Pattern.compile(selector.substring(5)).matcher(text).find();
		}
		return false;
	}

	/** The node to click: a shown, enabled button, or the node itself when it or an ancestor handles mouse clicks. */
	Node clickable(String selector) {
		Node n = shownNode(selector);
		if (n == null) {
			return null;
		}
		for (Node c = n; c != null; c = c.getParent()) {
			if (c instanceof ButtonBase) {
				return c.isDisabled() ? null : c;
			}
			if (c.getOnMouseClicked() != null || c.getOnMousePressed() != null) {
				return c.isDisabled() ? null : n;
			}
		}
		// A container whose clickable part is a child (for example an HBox around an icon): click that child.
		List<Node> inside = new ArrayList<>();
		collect(n, inside);
		for (Node c : inside) {
			if (shown(c) && (c instanceof ButtonBase || c.getOnMouseClicked() != null)) {
				return c.isDisabled() ? null : c;
			}
		}
		// A caption next to its clickable tile (for example the settings tiles): click the tile in the same group.
		Node group = n.getParent() == null ? null : n.getParent();
		for (int level = 0; group != null && level < 2; level++, group = group.getParent()) {
			List<Node> near = new ArrayList<>();
			collect(group, near);
			for (Node c : near) {
				if (c != n && shown(c) && c.getOnMouseClicked() != null) {
					return c.isDisabled() ? null : c;
				}
			}
		}
		return n.isDisabled() ? null : n;
	}

	private void collect(Node node, List<Node> all) {
		all.add(node);
		if (node instanceof Parent) {
			for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
				collect(child, all);
			}
		}
	}

	private boolean shown(Node node) {
		for (Node n = node; n != null; n = n.getParent()) {
			if (!n.isVisible()) {
				return false;
			}
		}
		return true;
	}

	private String textOf(Node n) {
		if (n instanceof Labeled) {
			return ((Labeled) n).getText();
		}
		if (n instanceof Text) {
			return ((Text) n).getText();
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------- texts

	String allText() {
		StringBuilder b = new StringBuilder();
		for (Window w : windows()) {
			List<String> texts = new ArrayList<>();
			collectTexts(w.getScene().getRoot(), texts);
			b.append(String.join(" | ", texts)).append(" || ");
		}
		return b.toString();
	}

	String describeWindows() {
		StringBuilder builder = new StringBuilder();
		for (Window window : windows()) {
			List<String> texts = new ArrayList<>();
			collectTexts(window.getScene().getRoot(), texts);
			builder.append("[").append(String.join(" | ", texts.subList(0, Math.min(texts.size(), 40)))).append("] ");
		}
		return builder.toString();
	}

	/** The text of one node, or null when it has none (a label's prompt, for example). */
	String nodeText(String selector) {
		return textOf(shownNode(selector));
	}

	private void collectTexts(Node node, List<String> texts) {
		if (!node.isVisible()) {
			return;
		}
		// A Labeled draws its text with an internal LabeledText node; count that text once.
		boolean skinText = "LabeledText".equals(node.getClass().getSimpleName());
		String text = node instanceof TextInputControl || skinText ? null : textOf(node);
		if (text != null && !text.isBlank()) {
			texts.add(text.trim().replaceAll("\\s+", " "));
		}
		if (node instanceof Parent) {
			for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
				collectTexts(child, texts);
			}
		}
	}

	private String describeNode(Node n) {
		String text = textOf(n);
		return n.getClass().getSimpleName() + (n.getId() == null ? "" : "#" + n.getId())
				+ (text == null ? "" : " '" + text.trim() + "'");
	}

	void dump(String name) throws Exception {
		String text = onFx(() -> {
			StringBuilder b = new StringBuilder();
			for (Window w : windows()) {
				b.append("WINDOW ").append(w.getClass().getSimpleName()).append('\n');
				dumpNode(w.getScene().getRoot(), 0, b);
			}
			return b.toString();
		});
		Files.writeString(c.out.resolve(name + ".txt"), text == null ? "" : text);
		c.logLine("  DUMP " + name + ".txt");
	}

	private void dumpNode(Node n, int depth, StringBuilder b) {
		if (!n.isVisible()) {
			return;
		}
		b.append("  ".repeat(depth)).append(n.getClass().getSimpleName());
		if (n.getId() != null) {
			b.append(" #").append(n.getId());
		}
		String text = n instanceof TextInputControl ? ((TextInputControl) n).getText() : textOf(n);
		if (text != null && !text.isBlank()) {
			b.append(" '").append(text.trim().replaceAll("\\s+", " ")).append('\'');
		}
		if (n.isDisabled()) {
			b.append(" [disabled]");
		}
		if (n.getOnMouseClicked() != null) {
			b.append(" [onClick]");
		}
		if (n instanceof ComboBox) {
			b.append(" items=").append(((ComboBox<?>) n).getItems().size());
		}
		b.append('\n');
		if (n instanceof Parent) {
			for (Node child : ((Parent) n).getChildrenUnmodifiable()) {
				dumpNode(child, depth + 1, b);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------- alerts

	/** Closes one client alert ({@code Alert.fxml}: {@code #alertGridPane} with the {@code #exit} button). */
	Boolean closeAlert() {
		for (Window window : windows()) {
			Scene scene = window.getScene();
			if (scene.lookup("#alertGridPane") == null) {
				continue;
			}
			Node exit = scene.lookup("#exit");
			if (exit instanceof ButtonBase) {
				List<String> texts = new ArrayList<>();
				collectTexts(scene.getRoot(), texts);
				String text = String.join(" | ", texts);
				c.alerts.add(text);
				c.logLine("ALERT " + text);
				snapshot(scene, "alert");
				((ButtonBase) exit).fire();
				return true;
			}
		}
		return false;
	}

	private String openAlertText() {
		for (Window window : windows()) {
			Scene scene = window.getScene();
			if (scene.lookup("#alertGridPane") != null || scene.getRoot() instanceof DialogPane) {
				List<String> texts = new ArrayList<>();
				collectTexts(scene.getRoot(), texts);
				return String.join(" | ", texts);
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------- snapshots

	Void snapshotAll(String label) {
		for (Window window : windows()) {
			snapshot(window.getScene(), label);
		}
		return null;
	}

	private void snapshot(Scene scene, String label) {
		List<Node> hidden = c.maskFaces ? hideFaceImages(scene) : List.of();
		try {
			WritableImage image = scene.snapshot(null);
			File file = c.out.resolve(String.format("%03d-%s.png", ++c.snapshotCount, label)).toFile();
			ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
			c.logLine("SNAPSHOT " + file.getName());
			// A window capped at the screen height scrolls; the "-full" picture shows its whole content as well.
			List<Node> all = new ArrayList<>();
			collect(scene.getRoot(), all);
			for (Node n : all) {
				if (n instanceof ScrollPane && shown(n)) {
					Node content = ((ScrollPane) n).getContent();
					if (content != null && content.getBoundsInLocal().getHeight()
							> ((ScrollPane) n).getViewportBounds().getHeight() + 1) {
						File full = c.out.resolve(String.format("%03d-%s-full.png", c.snapshotCount, label)).toFile();
						ImageIO.write(SwingFXUtils.fromFXImage(content.snapshot(null, null), null), "png", full);
						c.logLine("SNAPSHOT " + full.getName());
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			c.logLine("snapshot failed " + e);
		} finally {
			hidden.forEach(n -> n.setOpacity(1));
		}
	}

	/**
	 * Makes every large image view (camera preview, captured face and document thumbnails; icons are smaller than
	 * {@link #MASK_MIN_SIDE}) and every HTML page view of the scene transparent and returns them, so that snapshots
	 * meant for publication show no face. The scene is drawn again at once; nothing else changes.
	 */
	private List<Node> hideFaceImages(Scene scene) {
		List<Node> all = new ArrayList<>();
		collect(scene.getRoot(), all);
		List<Node> hidden = new ArrayList<>();
		for (Node n : all) {
			// Preview, acknowledgement and EOD detail pages are HTML (WebView) with the resident's photo inside.
			if ("WebView".equals(n.getClass().getSimpleName()) && shown(n) && n.getOpacity() > 0) {
				n.setOpacity(0);
				hidden.add(n);
				continue;
			}
			if (n instanceof ImageView && shown(n) && n.getOpacity() > 0) {
				Bounds b = n.getBoundsInLocal();
				if (b.getWidth() >= MASK_MIN_SIDE && b.getHeight() >= MASK_MIN_SIDE) {
					n.setOpacity(0);
					hidden.add(n);
				}
			}
		}
		return hidden;
	}
}
