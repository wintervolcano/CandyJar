package application;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.controlsfx.control.SegmentedButton;

import data_holders.Candidate.CANDIDATE_TYPE;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.kurobako.gesturefx.GesturePane;

/**
 * Side-by-side viewer for cross-matched candidates from two searches.
 *
 * Reads a match CSV (e.g. the output of csv_matcher_v2) where every row is one
 * pair of candidates. Columns ending in "_a" / "_b" describe the two sides
 * (png_a and png_b are required), every other column describes the match itself.
 * Each pair can be classified, and the classification is written back as an
 * extra "classification" column so a saved file can be re-loaded to resume.
 */
public class MatchViewer {

	static final String CLASSIFICATION_COLUMN = "classification";
	static final String GROUP_COLUMN = "match_group";
	static final String TYPE_COLUMN = "match_type";
	static final String ALL_TYPES = "ALL match types";
	static final String SHOW_ALL = "All pairs";
	static final String SHOW_UNCLASSIFIED = "Unclassified";
	static final String SHOW_CLASSIFIED = "Classified";
	/* pairs are identified by their two candidates, so tags survive re-running the matcher or renaming plot directories */
	static final List<String> PAIR_KEY_COLUMNS = Arrays.asList("file_a", "row_a", "file_b", "row_b");
	static final int IMAGE_CACHE_SIZE = 40;
	static final int PREFETCH = 3;
	static final String CSV_ORDER = "CSV order";
	static final String DM_DIFF = "dm_diff";
	/* a matched pair shares (almost) the same spin frequency, so one sort option, taken from candidate A, is enough */
	static final String FREQ = "freq";
	static final String ABS_DM_DIFF = "abs_dm_diff";

	private final Stage stage;
	private final HostServices hostServices;

	/* data */
	private File matchFile = null;
	private File dirA = null;
	private File dirB = null;
	private final List<String> header = new ArrayList<>();
	private final List<Map<String, String>> rows = new ArrayList<>();
	private final List<Integer> visibleRows = new ArrayList<>();
	private final List<String> sidedColumns = new ArrayList<>();
	private final List<String> matchColumns = new ArrayList<>();
	private int cursor = 0;

	private final Map<String, Image> imageCache = new LinkedHashMap<String, Image>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
			return size() > IMAGE_CACHE_SIZE;
		}
	};

	/* top: inputs */
	final TextField matchFileField = new TextField();
	final TextField dirAField = new TextField();
	final TextField dirBField = new TextField();
	final Button matchFileSelect = new Button("...");
	final Button dirASelect = new Button("...");
	final Button dirBSelect = new Button("...");
	final Button load = new Button("Load");

	/* top: navigation */
	final Button previousPair = new Button("Previous (a)");
	final Button nextPair = new Button("Next (d)");
	final Label counterLabel = new Label();
	final TextField gotoGroupField = new TextField();
	final Button gotoGroup = new Button("Go");
	final ComboBox<String> typeFilter = new ComboBox<>();
	final ComboBox<String> sortBox = new ComboBox<>();
	final ToggleButton ascending = new ToggleButton("Asc");
	final ToggleButton descending = new ToggleButton("Desc");
	final SegmentedButton sortOrder = new SegmentedButton(ascending, descending);
	final ComboBox<String> classFilter = new ComboBox<>();
	final CheckBox linkZoom = new CheckBox("Link zoom");

	/* centre: the two plots */
	final ImageView imageViewA = new ImageView();
	final ImageView imageViewB = new ImageView();
	final GesturePane gestureA = Helpers.addGesture(imageViewA);
	final GesturePane gestureB = Helpers.addGesture(imageViewB);
	final Label missingA = new Label();
	final Label missingB = new Label();
	final Label titleA = new Label();
	final Label titleB = new Label();
	private boolean syncingZoom = false;
	private boolean pendingZoomSync = false;

	/* bottom: details and classification */
	final GridPane detailsGrid = new GridPane();
	final Label matchInfo = new Label();

	final ToggleButton rfi = new ToggleButton("RFI (y)");
	final ToggleButton noise = new ToggleButton("Noise (u)");
	final ToggleButton tier1 = new ToggleButton("Tier1 (i)");
	final ToggleButton tier2 = new ToggleButton("Tier2 (o)");
	final ToggleButton knownPulsar = new ToggleButton("Known PSR (p)");
	final ToggleButton nbPulsar = new ToggleButton("NB PSR (l)");
	final ToggleButton reset = new ToggleButton("Uncat (r)");
	final SegmentedButton categories = new SegmentedButton(FXCollections.observableArrayList(
			Arrays.asList(new ToggleButton[] { rfi, noise, tier1, tier2, knownPulsar, nbPulsar, reset })));
	final Button save = new Button("Save classification");
	final Button loadClassification = new Button("Load classification");
	final Label message = new Label("Load a match CSV to start.");

	public MatchViewer(Stage stage, HostServices hostServices, String matchCsv, String dirA, String dirB) {
		this.stage = stage;
		this.hostServices = hostServices;
		if (matchCsv != null) matchFileField.setText(new File(matchCsv).getAbsolutePath());
		if (dirA != null) dirAField.setText(new File(dirA).getAbsolutePath());
		if (dirB != null) dirBField.setText(new File(dirB).getAbsolutePath());
	}

	public void show() {

		stage.setTitle("CandyJar - match viewer");
		stage.setScene(buildScene());
		stage.setX(CandyJar.primaryScreenBounds.getMinX());
		stage.setY(CandyJar.primaryScreenBounds.getMinY());
		stage.show();

		if (!matchFileField.getText().isBlank()) load.fire();
	}

	/* ------------------------------------------------------------------ */
	/* data                                                                */
	/* ------------------------------------------------------------------ */

	void loadMatches() throws IOException {

		File file = new File(matchFileField.getText().trim());
		if (!file.exists()) throw new IOException("Match CSV not found: " + file.getAbsolutePath());

		File parent = file.getAbsoluteFile().getParentFile();
		File a = dirAField.getText().isBlank() ? parent : new File(dirAField.getText().trim());
		File b = dirBField.getText().isBlank() ? parent : new File(dirBField.getText().trim());

		List<String> newHeader = new ArrayList<>();
		List<Map<String, String>> newRows = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				List<String> values = splitCsvLine(line);
				if (newHeader.isEmpty()) {
					for (String v : values) newHeader.add(v.trim());
					continue;
				}
				Map<String, String> row = new LinkedHashMap<>();
				for (int i = 0; i < newHeader.size(); i++) row.put(newHeader.get(i), i < values.size() ? values.get(i).trim() : "");
				newRows.add(row);
			}
		}

		if (!newHeader.contains("png_a") || !newHeader.contains("png_b"))
			throw new IOException("Match CSV needs png_a and png_b columns. Found: " + newHeader);

		matchFile = file;
		dirA = a;
		dirB = b;
		dirAField.setText(a.getAbsolutePath());
		dirBField.setText(b.getAbsolutePath());

		header.clear();
		header.addAll(newHeader);
		if (!header.contains(CLASSIFICATION_COLUMN)) header.add(CLASSIFICATION_COLUMN);
		rows.clear();
		rows.addAll(newRows);
		for (Map<String, String> row : rows) {
			String value = row.getOrDefault(CLASSIFICATION_COLUMN, "");
			row.put(CLASSIFICATION_COLUMN, value.isBlank() ? CANDIDATE_TYPE.UNCAT.name() : value);
		}

		sidedColumns.clear();
		matchColumns.clear();
		for (String column : header) {
			if (column.equals(CLASSIFICATION_COLUMN)) continue;
			if (column.endsWith("_a") && header.contains(stripSide(column) + "_b")) sidedColumns.add(stripSide(column));
			else if (!(column.endsWith("_b") && header.contains(stripSide(column) + "_a"))) matchColumns.add(column);
		}

		addDerivedValues();

		/* every numeric per-side and per-match column in the CSV can be sorted on (e.g. snr_a / snr_b when present) */
		String previousSort = sortBox.getValue();
		sortBox.getItems().clear();
		sortBox.getItems().add(CSV_ORDER);
		for (String column : sidedColumns) {
			if (column.equals("row") || !isNumericColumn(column + "_a")) continue;
			sortBox.getItems().addAll(column + "_a", column + "_b");
			if (column.equals("period")) sortBox.getItems().add(FREQ);
		}
		sortBox.getItems().addAll(DM_DIFF, ABS_DM_DIFF);
		for (String column : matchColumns) if (!column.equals(GROUP_COLUMN) && isNumericColumn(column)) sortBox.getItems().add(column);
		sortBox.getSelectionModel().select(sortBox.getItems().contains(previousSort) ? previousSort : CSV_ORDER);

		Set<String> types = new LinkedHashSet<>();
		for (Map<String, String> row : rows) types.add(row.getOrDefault(TYPE_COLUMN, ""));
		types.remove("");
		typeFilter.getItems().clear();
		typeFilter.getItems().add(ALL_TYPES);
		typeFilter.getItems().addAll(types);
		typeFilter.getSelectionModel().select(ALL_TYPES);

		imageCache.clear();
		applyFilter();
		message.setText("Loaded " + rows.size() + " pairs in " + countGroups() + " match groups from " + file.getName());
	}

	/** Frequencies and DM difference, kept out of the header so they are not written back to the CSV. */
	void addDerivedValues() {
		for (Map<String, String> row : rows) {
			for (String side : Arrays.asList("a", "b")) {
				Double period = parse(row.get("period_" + side));
				if (period != null && period != 0) row.put("freq_" + side, Double.toString(1.0 / period));
			}
			if (row.containsKey("freq_a")) row.put(FREQ, row.get("freq_a"));
			Double dmA = parse(row.get("dm_a")), dmB = parse(row.get("dm_b"));
			if (dmA != null && dmB != null) {
				row.put(DM_DIFF, Double.toString(dmB - dmA));
				row.put(ABS_DM_DIFF, Double.toString(Math.abs(dmB - dmA)));
			}
		}
	}

	boolean isNumericColumn(String column) {
		return rows.stream().anyMatch(r -> parse(r.get(column)) != null);
	}

	static Double parse(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	void applyFilter() {
		String type = typeFilter.getValue();
		Integer currentRow = visibleRows.isEmpty() ? null : visibleRows.get(cursor);

		visibleRows.clear();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, String> row = rows.get(i);
			if (type != null && !type.equals(ALL_TYPES) && !type.equals(row.get(TYPE_COLUMN))) continue;
			if (!showClassification(row.get(CLASSIFICATION_COLUMN))) continue;
			visibleRows.add(i);
		}

		String sortKey = sortBox.getValue();
		if (sortKey != null && !sortKey.equals(CSV_ORDER)) {
			boolean descendingOrder = descending.isSelected();
			/* pairs without a value always go last, whichever the order */
			visibleRows.sort((x, y) -> {
				Double vx = parse(rows.get(x).get(sortKey)), vy = parse(rows.get(y).get(sortKey));
				if (vx == null || vy == null) return vx == null ? (vy == null ? 0 : 1) : -1;
				return descendingOrder ? Double.compare(vy, vx) : Double.compare(vx, vy);
			});
		}

		cursor = currentRow == null ? 0 : Math.max(0, visibleRows.indexOf(currentRow));
		message.setText("");
		showCurrent();
	}

	boolean showClassification(String classification) {
		String show = classFilter.getValue();
		boolean unclassified = CANDIDATE_TYPE.UNCAT.name().equals(classification);
		if (show == null || show.equals(SHOW_ALL)) return true;
		if (show.equals(SHOW_UNCLASSIFIED)) return unclassified;
		if (show.equals(SHOW_CLASSIFIED)) return !unclassified;
		return show.equals(classification);
	}

	/** Copy tags from a saved classification CSV onto the matching pairs; returns a summary for the message bar. */
	String loadClassificationFrom(File file) throws IOException {
		List<String> fileHeader = null;
		Map<String, String> loaded = new LinkedHashMap<>();
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				List<String> values = splitCsvLine(line);
				if (fileHeader == null) {
					fileHeader = new ArrayList<>();
					for (String v : values) fileHeader.add(v.trim());
					if (!fileHeader.contains(CLASSIFICATION_COLUMN))
						throw new IOException(file.getName() + " has no " + CLASSIFICATION_COLUMN + " column.");
					continue;
				}
				Map<String, String> row = new LinkedHashMap<>();
				for (int i = 0; i < fileHeader.size() && i < values.size(); i++) row.put(fileHeader.get(i), values.get(i).trim());
				String value = row.getOrDefault(CLASSIFICATION_COLUMN, "");
				/* only real tags are carried over, so loading an older file never wipes tags made since */
				if (isCategory(value) && !value.equals(CANDIDATE_TYPE.UNCAT.name())) loaded.put(pairKey(row), value);
			}
		}

		Map<String, Integer> counts = new LinkedHashMap<>();
		int applied = 0;
		for (Map<String, String> row : rows) {
			String value = loaded.remove(pairKey(row));
			if (value == null) continue;
			row.put(CLASSIFICATION_COLUMN, value);
			counts.merge(value, 1, Integer::sum);
			applied++;
		}
		if (applied > 0) writeToFile(autosaveFile());

		StringBuilder summary = new StringBuilder("Loaded " + applied + " classifications from " + file.getName());
		if (!counts.isEmpty()) summary.append(" (").append(counts.toString().replaceAll("[{}]", "").replace("=", ": ")).append(")");
		if (!loaded.isEmpty()) summary.append("; ").append(loaded.size()).append(" tagged pairs are not in this match CSV");
		return summary.toString();
	}

	static String pairKey(Map<String, String> row) {
		List<String> parts = new ArrayList<>();
		if (PAIR_KEY_COLUMNS.stream().allMatch(row::containsKey))
			for (String column : PAIR_KEY_COLUMNS) parts.add(row.get(column));
		else
			for (String column : Arrays.asList("png_a", "png_b")) parts.add(row.getOrDefault(column, ""));
		return String.join("|", parts);
	}

	static boolean isCategory(String value) {
		try {
			CANDIDATE_TYPE.valueOf(value);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** A new sort order starts from the top of the list, not from wherever the current pair ended up. */
	void sortFromStart() {
		visibleRows.clear();
		applyFilter();
	}

	long countGroups() {
		return rows.stream().map(r -> r.getOrDefault(GROUP_COLUMN, "")).distinct().count();
	}

	void writeToFile(File file) throws IOException {
		try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
			writer.println(String.join(",", header));
			for (Map<String, String> row : rows) {
				List<String> values = new ArrayList<>();
				for (String column : header) values.add(quoteCsv(row.getOrDefault(column, "")));
				writer.println(String.join(",", values));
			}
		}
	}

	File autosaveFile() {
		String name = matchFile.getName().replaceFirst("\\.csv$", "");
		if (!name.endsWith("_classified")) name += "_classified";
		return new File(matchFile.getAbsoluteFile().getParentFile(), name + ".csv");
	}

	static String stripSide(String column) {
		return column.substring(0, column.length() - 2);
	}

	static List<String> splitCsvLine(String line) {
		List<String> values = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
				else if (c == '"') quoted = false;
				else current.append(c);
			}
			else if (c == '"') quoted = true;
			else if (c == ',') { values.add(current.toString()); current.setLength(0); }
			else current.append(c);
		}
		values.add(current.toString());
		return values;
	}

	static String quoteCsv(String value) {
		if (value.contains(",") || value.contains("\"")) return "\"" + value.replace("\"", "\"\"") + "\"";
		return value;
	}

	/* ------------------------------------------------------------------ */
	/* display                                                             */
	/* ------------------------------------------------------------------ */

	Map<String, String> currentRow() {
		if (visibleRows.isEmpty()) return null;
		return rows.get(visibleRows.get(cursor));
	}

	File resolvePng(File dir, String png) {
		if (png == null || png.isBlank()) return null;
		File file = new File(png);
		if (file.isAbsolute()) return file;
		file = new File(dir, png);
		if (!file.exists() && matchFile != null) {
			File besideCsv = new File(matchFile.getAbsoluteFile().getParentFile(), png);
			if (besideCsv.exists()) return besideCsv;
		}
		return file;
	}

	Image getImage(File png) {
		if (png == null || !png.exists()) return null;
		return imageCache.computeIfAbsent(png.getAbsolutePath(), p -> new Image(png.toURI().toString(), true));
	}

	void showCurrent() {

		Map<String, String> row = currentRow();
		if (row == null) {
			imageViewA.setImage(null);
			imageViewB.setImage(null);
			titleA.setText("");
			titleB.setText("");
			counterLabel.setText("0/0");
			detailsGrid.getChildren().clear();
			matchInfo.setText("");
			message.setText(rows.isEmpty() ? "No pairs loaded." : "No pairs match the current filter.");
			return;
		}

		showSide(row, "a", dirA, imageViewA, missingA, titleA);
		showSide(row, "b", dirB, imageViewB, missingB, titleB);

		syncingZoom = true;
		gestureA.reset();
		gestureB.reset();
		syncingZoom = false;

		String group = row.getOrDefault(GROUP_COLUMN, "");
		int inGroup = 0, positionInGroup = 0;
		for (int i = 0; i < visibleRows.size(); i++) {
			if (!group.equals(rows.get(visibleRows.get(i)).get(GROUP_COLUMN))) continue;
			inGroup++;
			if (i == cursor) positionInGroup = inGroup;
		}
		counterLabel.setText("Pair " + (cursor + 1) + "/" + visibleRows.size()
				+ "  |  match_group " + group + ": pair " + positionInGroup + " of " + inGroup);

		updateDetails(row);
		selectCategory(row.get(CLASSIFICATION_COLUMN));

		/* warm the cache for the next few pairs so stepping through is smooth */
		for (int i = 1; i <= PREFETCH && cursor + i < visibleRows.size(); i++) {
			Map<String, String> next = rows.get(visibleRows.get(cursor + i));
			getImage(resolvePng(dirA, next.get("png_a")));
			getImage(resolvePng(dirB, next.get("png_b")));
		}
	}

	void showSide(Map<String, String> row, String side, File dir, ImageView imageView, Label missing, Label title) {
		String png = row.get("png_" + side);
		File file = resolvePng(dir, png);
		Image image = getImage(file);
		imageView.setImage(image);
		imageView.setUserData(file);
		missing.setVisible(image == null);
		missing.setText(image == null ? "PNG not found:\n" + (file == null ? "(no png in CSV)" : file.getAbsolutePath()) : "");

		String label = side.toUpperCase() + ": " + row.getOrDefault("file_" + side, "") + "  (row_" + side + " = " + row.getOrDefault("row_" + side, "") + ")";
		title.setText(label);
	}

	void updateDetails(Map<String, String> row) {
		detailsGrid.getChildren().clear();
		detailsGrid.add(boldLabel("Parameter"), 0, 0);
		detailsGrid.add(boldLabel("A"), 1, 0);
		detailsGrid.add(boldLabel("B"), 2, 0);

		int r = 1;
		for (String column : sidedColumns) {
			if (column.equals("png") || column.equals("file") || column.equals("row")) continue;
			String a = row.getOrDefault(column + "_a", "");
			String b = row.getOrDefault(column + "_b", "");
			if (column.equals("period")) {
				detailsGrid.add(new Label("period (ms)"), 0, r);
				detailsGrid.add(new CopyableLabel(toMilliseconds(a)), 1, r);
				detailsGrid.add(new CopyableLabel(toMilliseconds(b)), 2, r);
				r++;
			}
			detailsGrid.add(new Label(column), 0, r);
			detailsGrid.add(new CopyableLabel(a), 1, r);
			detailsGrid.add(new CopyableLabel(b), 2, r);
			r++;
			if (column.equals("period")) {
				detailsGrid.add(new Label("freq (Hz)"), 0, r);
				detailsGrid.add(new CopyableLabel(format(row.get("freq_a"), "%.9f")), 1, r);
				detailsGrid.add(new CopyableLabel(format(row.get("freq_b"), "%.9f")), 2, r);
				r++;
			}
			if (column.equals("dm")) {
				detailsGrid.add(boldLabel("DM diff (B - A)"), 0, r);
				detailsGrid.add(new CopyableLabel(format(row.get(DM_DIFF), "%+.6f")), 2, r);
				r++;
			}
		}
		detailsGrid.add(new Label("png"), 0, r);
		detailsGrid.add(new CopyableLabel(row.getOrDefault("png_a", "")), 1, r);
		detailsGrid.add(new CopyableLabel(row.getOrDefault("png_b", "")), 2, r);

		StringBuilder info = new StringBuilder();
		if (row.containsKey(DM_DIFF)) info.append("DM diff (B - A): ").append(format(row.get(DM_DIFF), "%+.6f"));
		for (String column : matchColumns) {
			if (info.length() > 0) info.append("     ");
			info.append(column).append(": ").append(row.getOrDefault(column, ""));
		}
		matchInfo.setText(info.toString());
	}

	static String format(String value, String pattern) {
		Double number = parse(value);
		return number == null ? "" : String.format(pattern, number);
	}

	static String toMilliseconds(String seconds) {
		try {
			return String.format("%.9f", Double.parseDouble(seconds) * 1000.0);
		} catch (NumberFormatException e) {
			return "";
		}
	}

	static Label boldLabel(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-font-weight: bold");
		return label;
	}

	void selectCategory(String value) {
		CANDIDATE_TYPE type;
		try {
			type = CANDIDATE_TYPE.valueOf(value);
		} catch (Exception e) {
			type = CANDIDATE_TYPE.UNCAT;
		}
		for (ToggleButton button : categories.getButtons()) button.setSelected(false);
		toggleFor(type).setSelected(true);
	}

	ToggleButton toggleFor(CANDIDATE_TYPE type) {
		switch (type) {
		case RFI: return rfi;
		case NOISE: return noise;
		case T1_CAND: return tier1;
		case T2_CAND: return tier2;
		case KNOWN_PSR: return knownPulsar;
		case NB_PSR: return nbPulsar;
		default: return reset;
		}
	}

	void classify(CANDIDATE_TYPE type) {
		Map<String, String> row = currentRow();
		if (row == null) return;
		row.put(CLASSIFICATION_COLUMN, type.name());
		selectCategory(type.name());
		try {
			writeToFile(autosaveFile());
		} catch (IOException e) {
			message.setText("Autosave failed: " + e.getMessage());
			return;
		}
		/* like the main viewer: tagging moves on to the next pair, resetting to Uncat stays */
		if (type != CANDIDATE_TYPE.UNCAT) move(1);
	}

	void move(int step) {
		if (visibleRows.isEmpty()) return;
		int target = cursor + step;
		if (target < 0 || target >= visibleRows.size()) {
			message.setText(target < 0 ? "Already at the first pair." : "Reached the last pair.");
			return;
		}
		cursor = target;
		message.setText("");
		showCurrent();
	}

	void goToGroup(String group) {
		for (int i = 0; i < visibleRows.size(); i++) {
			if (group.equals(rows.get(visibleRows.get(i)).get(GROUP_COLUMN))) {
				cursor = i;
				message.setText("");
				showCurrent();
				return;
			}
		}
		message.setText("No visible pairs in match group " + group);
	}

	/** Mirror zoom and pan from one pane onto the other, so the same region of both plots stays in view. */
	void syncZoom(GesturePane source, GesturePane target) {
		if (syncingZoom || pendingZoomSync || !linkZoom.isSelected()) return;
		/* a single zoom/pan updates scale and translation one after the other, so copy once it has settled */
		pendingZoomSync = true;
		Platform.runLater(() -> {
			pendingZoomSync = false;
			syncingZoom = true;
			try {
				target.zoomTo(source.getCurrentScaleX(), source.getCurrentScaleY(), target.targetPointAtViewportCentre());
				target.centreOn(source.targetPointAtViewportCentre());
			} finally {
				syncingZoom = false;
			}
		});
	}

	void openInViewer(ImageView imageView) {
		Object file = imageView.getUserData();
		if (file instanceof File && ((File) file).exists()) hostServices.showDocument(((File) file).toURI().toString());
	}

	/* ------------------------------------------------------------------ */
	/* layout                                                              */
	/* ------------------------------------------------------------------ */

	VBox buildPlotPane(Label title, GesturePane gesture, ImageView imageView, Label missing) {
		title.setStyle("-fx-font-weight: bold; -fx-font-size: 1.1em");
		missing.setTextFill(Paint.valueOf("darkred"));
		missing.setWrapText(true);
		missing.setVisible(false);
		missing.setMouseTransparent(true);

		StackPane holder = new StackPane(gesture, missing);
		holder.setMinSize(0, 0);
		gesture.setMinSize(0, 0);
		imageView.setPreserveRatio(true);
		imageView.setSmooth(true);
		imageView.fitWidthProperty().bind(holder.widthProperty());
		imageView.fitHeightProperty().bind(holder.heightProperty());

		VBox box = new VBox(5, title, holder);
		VBox.setVgrow(holder, Priority.ALWAYS);
		HBox.setHgrow(box, Priority.ALWAYS);
		box.setMinWidth(0);
		box.setPrefWidth(1);
		return box;
	}

	void wireControls() {

		FileChooser fileChooser = new FileChooser();
		fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
		DirectoryChooser directoryChooser = new DirectoryChooser();

		matchFileSelect.setOnAction(e -> {
			File file = fileChooser.showOpenDialog(stage);
			if (file != null) matchFileField.setText(file.getAbsolutePath());
		});
		dirASelect.setOnAction(e -> {
			File dir = directoryChooser.showDialog(stage);
			if (dir != null) dirAField.setText(dir.getAbsolutePath());
		});
		dirBSelect.setOnAction(e -> {
			File dir = directoryChooser.showDialog(stage);
			if (dir != null) dirBField.setText(dir.getAbsolutePath());
		});

		load.setOnAction(e -> {
			try {
				loadMatches();
			} catch (Exception ex) {
				message.setText(ex.getMessage());
				ex.printStackTrace();
			}
		});

		previousPair.setOnAction(e -> move(-1));
		nextPair.setOnAction(e -> move(1));
		gotoGroup.setOnAction(e -> goToGroup(gotoGroupField.getText().trim()));
		gotoGroupField.setOnAction(e -> gotoGroup.fire());
		typeFilter.setOnAction(e -> { if (!rows.isEmpty()) applyFilter(); });
		classFilter.getItems().addAll(SHOW_ALL, SHOW_UNCLASSIFIED, SHOW_CLASSIFIED);
		for (CANDIDATE_TYPE type : CANDIDATE_TYPE.values()) if (type != CANDIDATE_TYPE.UNCAT) classFilter.getItems().add(type.name());
		classFilter.getSelectionModel().select(SHOW_ALL);
		classFilter.setOnAction(e -> { if (!rows.isEmpty()) applyFilter(); });
		sortBox.getItems().add(CSV_ORDER);
		sortBox.getSelectionModel().select(CSV_ORDER);
		descending.setSelected(true);
		sortBox.setOnAction(e -> { if (!rows.isEmpty()) sortFromStart(); });
		sortOrder.getToggleGroup().selectedToggleProperty().addListener((o, previous, selected) -> {
			/* keep one order selected at all times */
			if (selected == null) previous.setSelected(true);
			else if (!rows.isEmpty()) sortFromStart();
		});

		rfi.setOnAction(e -> classify(CANDIDATE_TYPE.RFI));
		noise.setOnAction(e -> classify(CANDIDATE_TYPE.NOISE));
		tier1.setOnAction(e -> classify(CANDIDATE_TYPE.T1_CAND));
		tier2.setOnAction(e -> classify(CANDIDATE_TYPE.T2_CAND));
		knownPulsar.setOnAction(e -> classify(CANDIDATE_TYPE.KNOWN_PSR));
		nbPulsar.setOnAction(e -> classify(CANDIDATE_TYPE.NB_PSR));
		reset.setOnAction(e -> classify(CANDIDATE_TYPE.UNCAT));

		loadClassification.setOnAction(e -> {
			if (matchFile == null) {
				message.setText("Load a match CSV first.");
				return;
			}
			FileChooser loadChooser = new FileChooser();
			loadChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
			loadChooser.setInitialDirectory(matchFile.getAbsoluteFile().getParentFile());
			File file = loadChooser.showOpenDialog(stage);
			if (file == null) return;
			try {
				String summary = loadClassificationFrom(file);
				applyFilter();
				message.setText(summary);
			} catch (IOException ex) {
				message.setText("Could not load classification: " + ex.getMessage());
			}
		});

		save.setOnAction(e -> {
			if (matchFile == null) return;
			FileChooser saveChooser = new FileChooser();
			saveChooser.setInitialDirectory(matchFile.getAbsoluteFile().getParentFile());
			saveChooser.setInitialFileName(autosaveFile().getName());
			File file = saveChooser.showSaveDialog(stage);
			if (file == null) return;
			try {
				writeToFile(file);
				message.setText("Saved classification to " + file.getAbsolutePath());
			} catch (IOException ex) {
				message.setText("Save failed: " + ex.getMessage());
			}
		});

		linkZoom.setSelected(true);
		linkZoom.setOnAction(e -> syncZoom(gestureA, gestureB));
		gestureA.currentScaleProperty().addListener((o, ov, nv) -> syncZoom(gestureA, gestureB));
		gestureA.currentScaleYProperty().addListener((o, ov, nv) -> syncZoom(gestureA, gestureB));
		gestureA.currentXProperty().addListener((o, ov, nv) -> syncZoom(gestureA, gestureB));
		gestureA.currentYProperty().addListener((o, ov, nv) -> syncZoom(gestureA, gestureB));
		gestureB.currentScaleProperty().addListener((o, ov, nv) -> syncZoom(gestureB, gestureA));
		gestureB.currentScaleYProperty().addListener((o, ov, nv) -> syncZoom(gestureB, gestureA));
		gestureB.currentXProperty().addListener((o, ov, nv) -> syncZoom(gestureB, gestureA));
		gestureB.currentYProperty().addListener((o, ov, nv) -> syncZoom(gestureB, gestureA));
	}

	void handleKey(KeyEvent event) {
		if (event.getTarget() instanceof TextInputControl && ((TextInputControl) event.getTarget()).isEditable()) return;
		switch (event.getCode()) {
		case A: previousPair.fire(); break;
		case D: nextPair.fire(); break;
		case Y: rfi.fire(); break;
		case U: noise.fire(); break;
		case I: tier1.fire(); break;
		case O: tier2.fire(); break;
		case P: knownPulsar.fire(); break;
		case L: nbPulsar.fire(); break;
		case R: reset.fire(); break;
		case SPACE: openInViewer(imageViewA); openInViewer(imageViewB); break;
		default: return;
		}
		event.consume();
	}

	Scene buildScene() {

		wireControls();

		matchFileField.setPromptText("Match CSV (from csv_matcher_v2)");
		dirAField.setPromptText("Base directory for png_a (default: CSV directory)");
		dirBField.setPromptText("Base directory for png_b (default: CSV directory)");
		gotoGroupField.setPromptText("group");
		counterLabel.setTooltip(new Tooltip("Pair: position of this line in the match CSV (after filters).\n"
				+ "match_group: all pairs sharing one candidate, as numbered by the matcher."));
		gotoGroupField.setPrefColumnCount(6);
		HBox.setHgrow(matchFileField, Priority.ALWAYS);
		HBox.setHgrow(dirAField, Priority.ALWAYS);
		HBox.setHgrow(dirBField, Priority.ALWAYS);

		HBox inputs = new HBox(10, matchFileField, matchFileSelect, dirAField, dirASelect, dirBField, dirBSelect, load);
		inputs.setAlignment(Pos.CENTER_LEFT);
		HBox navigation = new HBox(10, previousPair, counterLabel, nextPair,
				new Label("Go to match_group:"), gotoGroupField, gotoGroup, linkZoom, new Label("Space: open both PNGs"));
		navigation.setAlignment(Pos.CENTER_LEFT);
		sortBox.setTooltip(new Tooltip("_a / _b: value of candidate A / B. freq = 1/period of candidate A. Any other numeric column in the CSV is listed too."));
		HBox sorting = new HBox(10, new Label("Sort by:"), sortBox, sortOrder, new Label("Show:"), classFilter, typeFilter);
		sorting.setAlignment(Pos.CENTER_LEFT);

		HBox plots = new HBox(10, buildPlotPane(titleA, gestureA, imageViewA, missingA),
				buildPlotPane(titleB, gestureB, imageViewB, missingB));

		detailsGrid.setHgap(10);
		detailsGrid.setVgap(2);
		ColumnConstraints nameColumn = new ColumnConstraints();
		ColumnConstraints valueColumn = new ColumnConstraints();
		valueColumn.setHgrow(Priority.ALWAYS);
		detailsGrid.getColumnConstraints().addAll(nameColumn, valueColumn, valueColumn);
		matchInfo.setStyle("-fx-font-weight: bold");
		message.setTextFill(Paint.valueOf("darkred"));
		categories.getStyleClass().add(SegmentedButton.STYLE_CLASS_DARK);

		VBox bottom = new VBox(8, matchInfo, detailsGrid,
				new HBox(10, new Label("Select Category:"), categories, save, loadClassification), message);

		BorderPane pane = new BorderPane();
		pane.setTop(new VBox(10, inputs, navigation, sorting));
		pane.setCenter(plots);
		pane.setBottom(bottom);
		Insets insets = new Insets(10, 20, 10, 20);
		BorderPane.setMargin(pane.getTop(), insets);
		BorderPane.setMargin(plots, new Insets(0, 20, 0, 20));
		BorderPane.setMargin(bottom, insets);

		/* text fields only take focus when clicked, so a/d etc. keep navigating after switching back to this window */
		for (TextField field : Arrays.asList(matchFileField, dirAField, dirBField, gotoGroupField)) field.setFocusTraversable(false);
		pane.setOnMouseClicked(e -> pane.requestFocus());
		Platform.runLater(pane::requestFocus);
		stage.focusedProperty().addListener((o, wasFocused, focused) -> {
			if (focused) Platform.runLater(pane::requestFocus);
		});

		Scene scene = new Scene(pane, CandyJar.primaryScreenBounds.getWidth(), CandyJar.primaryScreenBounds.getHeight());
		scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
		scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
		return scene;
	}
}
