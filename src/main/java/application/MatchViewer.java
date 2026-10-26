package application;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
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
import javafx.beans.InvalidationListener;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;
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
	static final String ALL_TYPES = "All match types";
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
	final ToggleButton groupOnly = new ToggleButton("Group only (g)");
	/* match_group shown on its own, or null for the normal filtered list */
	private String lockedGroup = null;
	final ComboBox<String> typeFilter = new ComboBox<>();
	final ComboBox<String> sortBox = new ComboBox<>();
	final ToggleButton ascending = new ToggleButton("Asc");
	final ToggleButton descending = new ToggleButton("Desc");
	final SegmentedButton sortOrder = new SegmentedButton(ascending, descending);
	final ComboBox<String> classFilter = new ComboBox<>();

	/* centre: the two plots */
	final ImageView imageViewA = new ImageView();
	final ImageView imageViewB = new ImageView();
	final GesturePane gestureA = Helpers.addGesture(imageViewA);
	final GesturePane gestureB = Helpers.addGesture(imageViewB);
	final Label missingA = new Label();
	final Label missingB = new Label();
	final Label titleA = new Label();
	final Label titleB = new Label();
	final CopyableLabel pngA = new CopyableLabel("");
	final CopyableLabel pngB = new CopyableLabel("");
	private boolean syncingZoom = false;
	private boolean pendingZoomSync = false;

	/* bottom: details and classification */
	/* comparison strip under the plots: one column per parameter, rows A / B / difference */
	final GridPane detailsGrid = new GridPane();
	final Label matchInfo = new Label();
	final Label tagBadge = new Label();
	final Label summary = new Label();
	final ScrollPane tableScroll = new ScrollPane();
	static final double TAG_COLUMN_WIDTH = 170;
	private boolean showDv = false;
	private boolean showSeparation = false;
	final TitledPane filesPane = new TitledPane();
	final Label keysHelp = new Label("Keys: a / d previous / next   y u i o p l tag (Shift: whole group)   r Uncat   g group only   Space open both PNGs");

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
	final CheckBox wholeGroup = new CheckBox("Whole group (or Shift+key)");
	/* set while a Shift+category key is being handled, so that key tags the whole group */
	private boolean shiftTag = false;
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

		if (matchInputs != null) runMatcher();
		else if (!matchFileField.getText().isBlank()) load.fire();
	}

	/* ------------------------------------------------------------------ */
	/* automatic matching (-m a.csv b.csv)                                 */
	/* ------------------------------------------------------------------ */

	static final List<String> DEFAULT_MATCHER_ARGS = Arrays.asList("--ptol-frac", "5e-4", "--dmtol", "2");
	private List<String> matchInputs = null;
	private List<String> matcherArgs = null;
	private String matcherPath = null;

	/** Match these candidate CSVs with csv_matcher_v2 when the viewer opens, then show the pairs. */
	public void matchCandidateFiles(List<String> inputs, List<String> args, String binary) {
		matchInputs = inputs;
		matcherArgs = args;
		matcherPath = binary;
	}

	/** -matcher, else the binary built along with CandyJar (passed in by gradle), else csv_matcher_v2 on the PATH. */
	String findMatcher() {
		List<String> candidates = new ArrayList<>();
		if (matcherPath != null) candidates.add(matcherPath);
		if (System.getProperty("candyjar.matcher") != null) candidates.add(System.getProperty("candyjar.matcher"));
		for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
			candidates.add(new File(dir, "csv_matcher_v2").getPath());
		return candidates.stream().filter(path -> new File(path).canExecute()).findFirst().orElse(null);
	}

	/** Short names for the inputs: the file name, or the directory name when the inputs share a file name (e.g. candidates.csv). */
	static List<String> inputLabels(List<File> inputs) {
		List<String> stems = new ArrayList<>();
		for (File input : inputs) stems.add(input.getName().replaceFirst("\\.csv$", ""));
		boolean sameNames = new LinkedHashSet<>(stems).size() < stems.size();
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < inputs.size(); i++)
			labels.add(sameNames ? inputs.get(i).getAbsoluteFile().getParentFile().getName() : stems.get(i));
		return labels;
	}

	/** matches_<a>__<b>.csv next to the first input. */
	static File pairsFile(List<File> inputs) {
		return new File(inputs.get(0).getAbsoluteFile().getParentFile(), "matches_" + String.join("__", inputLabels(inputs)) + ".csv");
	}

	void runMatcher() {
		List<File> inputs = new ArrayList<>();
		for (String input : matchInputs) {
			File file = new File(input).getAbsoluteFile();
			if (!file.exists()) {
				message.setText("Candidate CSV not found: " + file);
				return;
			}
			inputs.add(file);
		}
		String binary = findMatcher();
		if (binary == null) {
			message.setText("csv_matcher_v2 not found: build CandyJar with cargo installed (see README), or give -matcher <path>");
			return;
		}
		File output = pairsFile(inputs);
		List<String> args = matcherArgs == null ? DEFAULT_MATCHER_ARGS : matcherArgs;
		List<String> command = new ArrayList<>(Arrays.asList(binary, "-i"));
		inputs.forEach(input -> command.add(input.getPath()));
		command.addAll(args);
		if (!args.contains("--ptol-frac")) command.addAll(Arrays.asList("--ptol-frac", "5e-4"));
		command.addAll(Arrays.asList("--pairs-only", "--pairs-out", output.getPath()));

		message.setText("Matching " + String.join(" and ", inputLabels(inputs)) + " with csv_matcher_v2 ...");
		System.err.println("Running: " + String.join(" ", command));
		Thread matcher = new Thread(() -> {
			List<String> lines = new ArrayList<>();
			int exitCode;
			try {
				Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						System.err.println(line);
						lines.add(line);
					}
				}
				exitCode = process.waitFor();
			} catch (IOException | InterruptedException e) {
				lines.add(e.getMessage());
				exitCode = -1;
			}
			int result = exitCode;
			Platform.runLater(() -> {
				if (result != 0 || !output.exists()) {
					message.setText("Matching failed: " + (lines.isEmpty() ? "exit code " + result : lines.get(lines.size() - 1)));
					return;
				}
				matchFileField.setText(output.getPath());
				load.fire();
				long warnings = lines.stream().filter(line -> line.startsWith("[WARN]")).count();
				if (warnings > 0) message.setText(message.getText() + ". The matcher printed " + warnings + " warnings (see the terminal).");
			});
		}, "csv_matcher_v2");
		matcher.setDaemon(true);
		matcher.start();
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
		/* columns without a single value (e.g. RA/DEC when the candidate files had none) only clutter the table */
		sidedColumns.removeIf(column -> !Arrays.asList("png", "file", "path", "row").contains(column)
				&& !anyValue(column + "_a") && !anyValue(column + "_b"));
		showDv = anyValue("dv_kms");
		showSeparation = anyValue("ang_sep_arcsec");

		addDerivedValues();

		/* every numeric per-side and per-match column in the CSV can be sorted on (e.g. snr_a / snr_b when present) */
		String previousSort = sortBox.getValue();
		sortBox.getItems().clear();
		sortBox.getItems().add(CSV_ORDER);
		/* the matcher writes pairs in the order it finds them, so a group's pairs are not always together */
		if (header.contains(GROUP_COLUMN)) sortBox.getItems().add(GROUP_COLUMN);
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
		lockedGroup = null;
		groupOnly.setSelected(false);
		classFilter.setDisable(false);
		typeFilter.setDisable(false);
		visibleRows.clear();
		applyFilter();
		filesPane.setText("Files: " + file.getName() + "   ·   A plots: " + shortPath(a) + "   ·   B plots: " + shortPath(b));
		filesPane.setExpanded(false);
		message.setText("Loaded " + rows.size() + " pairs in " + countGroups() + " match groups from " + file.getName());

		/* tags saved for this match CSV earlier (e.g. before re-running the matcher) come back automatically */
		File saved = autosaveFile();
		if (!saved.getAbsoluteFile().equals(file.getAbsoluteFile()) && saved.exists()) {
			try {
				String summary = loadClassificationFrom(saved);
				applyFilter();
				message.setText("Loaded " + rows.size() + " pairs from " + file.getName() + ". " + summary);
			} catch (IOException e) {
				message.setText(message.getText() + ". Could not restore tags from " + saved.getName() + ": " + e.getMessage());
			}
		}
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
			/* a single group is shown complete, whatever the Show and match type filters say */
			if (lockedGroup != null) {
				if (lockedGroup.equals(row.get(GROUP_COLUMN))) visibleRows.add(i);
				continue;
			}
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

	/** Show only the pairs of one match group (null goes back to the filtered list), keeping the current pair if it is shown. */
	void setLockedGroup(String group) {
		Map<String, String> current = currentRow();
		if (group != null && current != null && !group.equals(current.get(GROUP_COLUMN))) visibleRows.clear();
		lockedGroup = group;
		groupOnly.setSelected(group != null);
		classFilter.setDisable(group != null);
		typeFilter.setDisable(group != null);
		applyFilter();
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

	boolean anyValue(String column) {
		return rows.stream().map(r -> r.get(column))
				.anyMatch(v -> v != null && !v.isBlank() && !v.equalsIgnoreCase("NA") && !v.equalsIgnoreCase("nan"));
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

	/** The last two parts of a path, enough to tell the directories apart in the collapsed Files bar. */
	static String shortPath(File dir) {
		File parent = dir.getAbsoluteFile().getParentFile();
		return (parent == null ? "" : "…/" + parent.getName() + "/") + dir.getName();
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

	/**
	 * A relative png path is looked up next to the candidate CSV it came from (path_a / path_b, written by the
	 * matcher), then in the -ma / -mb directory, then next to the match CSV.
	 */
	File resolvePng(Map<String, String> row, String side, File dir) {
		String png = row.get("png_" + side);
		if (png == null || png.isBlank() || png.equals("NA")) return null;
		File file = new File(png);
		if (file.isAbsolute()) return file;
		List<File> places = new ArrayList<>();
		String input = row.get("path_" + side);
		if (input != null && !input.isBlank() && !input.equals("NA")) places.add(new File(new File(input).getParentFile(), png));
		places.add(new File(dir, png));
		if (matchFile != null) places.add(new File(matchFile.getAbsoluteFile().getParentFile(), png));
		return places.stream().filter(File::exists).findFirst().orElse(places.get(0));
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
			fitTableHeight();
			matchInfo.setText("");
			pngA.setText("");
			pngB.setText("");
			summary.setText("");
			setTagBadge(null);
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
				+ "  ·  group " + group + " (" + positionInGroup + "/" + inGroup + ")");

		updateDetails(row);
		selectCategory(row.get(CLASSIFICATION_COLUMN));

		/* warm the cache for the next few pairs so stepping through is smooth */
		for (int i = 1; i <= PREFETCH && cursor + i < visibleRows.size(); i++) {
			Map<String, String> next = rows.get(visibleRows.get(cursor + i));
			getImage(resolvePng(next, "a", dirA));
			getImage(resolvePng(next, "b", dirB));
		}
	}

	void showSide(Map<String, String> row, String side, File dir, ImageView imageView, Label missing, Label title) {
		String png = row.get("png_" + side);
		File file = resolvePng(row, side, dir);
		Image image = getImage(file);
		imageView.setImage(image);
		imageView.setUserData(file);
		missing.setVisible(image == null);
		missing.setText(image == null ? "PNG not found:\n" + (file == null ? "(no png in CSV)" : file.getAbsolutePath()) : "");

		title.setText(side.toUpperCase() + "   " + row.getOrDefault("file_" + side, "") + "  ·  row " + row.getOrDefault("row_" + side, ""));
		CopyableLabel path = side.equals("a") ? pngA : pngB;
		path.setText(png == null ? "" : png);
		path.setTooltip(file == null ? null : new Tooltip(file.getAbsolutePath()));
	}

	void updateDetails(Map<String, String> row) {
		detailsGrid.getChildren().clear();
		detailsGrid.add(tableLabel("", 0, Pos.CENTER_LEFT), 0, 0);
		detailsGrid.add(tableLabel("A", 1, Pos.CENTER_LEFT), 0, 1);
		detailsGrid.add(tableLabel("B", 2, Pos.CENTER_LEFT), 0, 2);
		detailsGrid.add(tableLabel("Δ (B − A)", 3, Pos.CENTER_LEFT), 0, 3);

		boolean harmonic = !row.getOrDefault("harmonic_k", "1").equals("1") && !row.getOrDefault("harmonic_k", "").isBlank();
		boolean hasDemod = sidedColumns.contains("period_demod");
		int c = 1;
		for (String column : sidedColumns) {
			if (column.equals("png") || column.equals("file") || column.equals("path") || column.equals("row")) continue;
			String a = row.get(column + "_a"), b = row.get(column + "_b");
			if (column.equals("period") || column.equals("period_demod")) {
				boolean demod = column.equals("period_demod");
				String fracColumn = demod ? "period_demod_frac_diff" : "period_frac_diff";
				addComparisonColumn(c++, demod ? "P0 demod (ms)" : "P0 (ms)", format(a, 1000.0, "%.6f"), a, format(b, 1000.0, "%.6f"), b,
						periodDelta(a, b, row.get(fracColumn), harmonic),
						(demod ? "Barycentric (demodulated) period: Earth's motion removed, so a real pulsar keeps the same value.\n" : "")
								+ "dP/P = " + row.getOrDefault(fracColumn, "?") + " (harmonic-corrected)"
								+ (demod ? "" : "; raw |PA - PB| = " + row.getOrDefault("period_diff_s", "?") + " s"));
				/* frequency and velocity change follow the last period column */
				if (demod || !hasDemod) {
					if (showDv) {
						boolean topocentric = dvTopocentric(row);
						CopyableLabel dv = addComparisonColumn(c++, "ΔV (km/s)", "", null, "", null,
								format(row.get("dv_kms"), "%+.2f") + (topocentric ? " topo" : ""),
								"Line-of-sight velocity change implied by the period change, c (PB - PA) / PA, from the "
										+ (topocentric ? "TOPOCENTRIC periods: it also contains the change in Earth's velocity towards the source"
												: "barycentric (demodulated) periods"));
						if (topocentric) dv.setStyle(dv.getStyle() + "-fx-text-fill: #b35900; -fx-font-weight: bold;");
					}
					addComparisonColumn(c++, "F (Hz)", format(row.get("freq_a"), "%.5f"), row.get("freq_a"),
							format(row.get("freq_b"), "%.5f"), row.get("freq_b"), "", null);
				}
				continue;
			}
			String delta = "";
			if (column.equals("dm")) delta = format(row.get(DM_DIFF), "%+.2f");
			else if (column.equals("acc") && parse(a) != null && parse(b) != null) delta = formatAcceleration(parse(b) - parse(a), true);
			addComparisonColumn(c++, columnTitle(column), formatValue(column, a), a, formatValue(column, b), b, delta, null);
			if (column.equals("dec") && showSeparation)
				addComparisonColumn(c++, "Sep (arcsec)", "", null, "", null, format(row.get("ang_sep_arcsec"), "%.2f"),
						"Angular distance between the two candidates' positions");
		}
		updateSummary(row, harmonic);
		fitTableHeight();

		/* match information that is not already shown in the strip */
		List<String> info = new ArrayList<>();
		String type = row.getOrDefault(TYPE_COLUMN, "");
		String k = row.getOrDefault("harmonic_k", "");
		if (!type.isBlank()) info.add(type + (k.isBlank() ? "" : "   k = " + k));
		for (String column : matchColumns) {
			if (Arrays.asList(GROUP_COLUMN, TYPE_COLUMN, "harmonic_k", "period_frac_diff", "period_diff_s",
					"period_demod_frac_diff", "dv_kms", "dv_frame", "ang_sep_arcsec").contains(column)) continue;
			info.add(column + ": " + row.getOrDefault(column, ""));
		}
		matchInfo.setText(String.join("\n", info));
		matchInfo.setTextFill(Paint.valueOf(harmonic ? "darkorange" : "black"));
	}

	/**
	 * Make the scroll viewport exactly as tall as the table, so a horizontal scrollbar adds height rather than
	 * covering the last row. Done here, when the table changes, not with a binding to its height: a size that
	 * changes during layout is not picked up by the parents and leaves the bottom area too short.
	 */
	void fitTableHeight() {
		detailsGrid.applyCss();
		double height = detailsGrid.prefHeight(-1);
		tableScroll.setMinViewportHeight(height);
		tableScroll.setPrefViewportHeight(height);
	}

	/** B - A in ms for a direct match; for a harmonic match the ms difference means nothing, so dP/P instead. */
	static String periodDelta(String a, String b, String fracDiff, boolean harmonic) {
		Double pa = parse(a), pb = parse(b), frac = parse(fracDiff);
		if (harmonic) return frac == null ? "" : String.format("dP/P %.1e", frac);
		return pa == null || pb == null ? "" : String.format("%+.6f", (pb - pa) * 1000.0);
	}

	/** Small accelerations (|a| < 0.01 m/s²) in scientific notation, so they do not all show as 0.00. */
	static String formatAcceleration(double value, boolean signed) {
		if (value != 0 && Math.abs(value) < 0.01) return String.format(signed ? "%+.3e" : "%.3e", value);
		return String.format(signed ? "%+.2f" : "%.2f", value);
	}

	/** One line with the numbers that decide whether a pair is the same pulsar, like ΔDM | ΔP0 | ΔV | separation. */
	void updateSummary(Map<String, String> row, boolean harmonic) {
		List<String> parts = new ArrayList<>();
		if (row.containsKey(DM_DIFF)) parts.add("ΔDM = " + format(row.get(DM_DIFF), "%+.2f") + " pc/cm³");
		boolean demod = parse(row.get("period_demod_a")) != null && parse(row.get("period_demod_b")) != null;
		String period = periodDelta(row.get(demod ? "period_demod_a" : "period_a"), row.get(demod ? "period_demod_b" : "period_b"),
				row.get(demod ? "period_demod_frac_diff" : "period_frac_diff"), harmonic);
		if (!period.isEmpty()) parts.add((demod ? "ΔP0 (demodulated) = " : "ΔP0 = ") + period + (harmonic ? "" : " ms"));
		if (parse(row.get("dv_kms")) != null)
			parts.add("ΔV = " + format(row.get("dv_kms"), "%+.2f") + " km/s" + (dvTopocentric(row) ? " (topocentric, includes Earth's motion)" : ""));
		if (parse(row.get("ang_sep_arcsec")) != null) parts.add("Angular distance = " + format(row.get("ang_sep_arcsec"), "%.2f") + " arcsec");
		summary.setText(String.join("     |     ", parts));
	}

	/**
	 * Whether ΔV came from topocentric periods (so it still contains Earth's motion). Uses the matcher's dv_frame
	 * column, or for older match CSVs whether both barycentric periods are present.
	 */
	static boolean dvTopocentric(Map<String, String> row) {
		String frame = row.get("dv_frame");
		if (frame != null && !frame.isBlank()) return frame.equalsIgnoreCase("topocentric");
		return parse(row.get("period_demod_a")) == null || parse(row.get("period_demod_b")) == null;
	}

	/** Adds a column to the comparison strip and returns its difference cell. */
	CopyableLabel addComparisonColumn(int column, String title, String a, String rawA, String b, String rawB, String delta, String deltaTooltip) {
		detailsGrid.add(tableLabel(title, 0, Pos.CENTER_RIGHT), column, 0);
		detailsGrid.add(valueCell(a, rawA, 1), column, 1);
		detailsGrid.add(valueCell(b, rawB, 2), column, 2);
		CopyableLabel deltaCell = valueCell(delta, deltaTooltip, 3);
		detailsGrid.add(deltaCell, column, 3);
		return deltaCell;
	}

	/* table rows: header, A, B, difference; each gets its own background so a row is easy to follow across */
	static final String[] TABLE_ROW_COLOURS = { "#e3e9f0", "#ffffff", "#eef3f9", "#fff6dc" };
	static final String TABLE_CELL_STYLE = "-fx-padding: 4 12 4 12; -fx-background-radius: 0; -fx-background-insets: 0; -fx-background-color: ";
	/* a monospaced font so digits line up down a column */
	static final String MONOSPACED = Font.getFamilies().stream()
			.filter(f -> Arrays.asList("Menlo", "Consolas", "DejaVu Sans Mono", "Liberation Mono").contains(f))
			.findFirst().orElse("Monospaced");

	static CopyableLabel valueCell(String text, String tooltip, int tableRow) {
		CopyableLabel cell = new CopyableLabel(text == null ? "" : text);
		cell.setStyle(TABLE_CELL_STYLE + TABLE_ROW_COLOURS[tableRow] + "; -fx-font-family: '" + MONOSPACED + "'; -fx-font-size: 1.2em; -fx-text-fill: #111;");
		cell.setAlignment(Pos.CENTER_RIGHT);
		cell.setPrefColumnCount(Math.max(cell.getText().length(), 4));
		cell.setMaxWidth(Double.MAX_VALUE);
		cell.setFocusTraversable(false);
		if (tooltip != null && !tooltip.isBlank()) cell.setTooltip(new Tooltip(tooltip));
		return cell;
	}

	static Label tableLabel(String text, int tableRow, Pos alignment) {
		Label label = boldLabel(text);
		label.setStyle(TABLE_CELL_STYLE + TABLE_ROW_COLOURS[tableRow] + "; -fx-font-weight: bold; -fx-font-size: 1.1em; -fx-text-fill: #222;");
		label.setAlignment(alignment);
		label.setMinWidth(Label.USE_PREF_SIZE);
		label.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		return label;
	}

	static String columnTitle(String column) {
		switch (column) {
		case "dm": return "DM";
		case "acc": return "acc (m/s²)";
		case "snr": return "S/N";
		case "tobs": return "Tobs (s)";
		case "epoch_mjd": return "Epoch (MJD)";
		case "ra": return "RA";
		case "dec": return "DEC";
		default: return column;
		}
	}

	/** Round values to what is useful at a glance; the full value is in the tooltip and copied with the text. */
	static String formatValue(String column, String raw) {
		Double value = parse(raw);
		if (value == null) return raw == null ? "" : raw;
		if (column.equals("dm")) return String.format("%.2f", value);
		if (column.equals("acc")) return formatAcceleration(value, false);
		if (column.equals("tobs")) return String.format("%.0f", value);
		if (column.equals("epoch_mjd")) return String.format("%.5f", value);
		if (column.equals("snr") || column.startsWith("sn_")) return String.format("%.1f", value);
		if (column.startsWith("pics")) return String.format("%.3f", value);
		return new java.math.BigDecimal(value).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
	}

	static String format(String value, double scale, String pattern) {
		Double number = parse(value);
		return number == null ? "" : String.format(pattern, number * scale);
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
		setTagBadge(type);
	}

	/** Big coloured tag next to the comparison strip, readable while flipping through pairs. */
	void setTagBadge(CANDIDATE_TYPE type) {
		if (type == null) {
			tagBadge.setText("");
			tagBadge.setStyle("");
			return;
		}
		String text, colour;
		switch (type) {
		case RFI: text = "RFI"; colour = "#c62828"; break;
		case NOISE: text = "Noise"; colour = "#616161"; break;
		case T1_CAND: text = "Tier 1"; colour = "#2e7d32"; break;
		case T2_CAND: text = "Tier 2"; colour = "#9e9d24"; break;
		case KNOWN_PSR: text = "Known PSR"; colour = "#1565c0"; break;
		case NB_PSR: text = "NB PSR"; colour = "#6a1b9a"; break;
		default: text = "Untagged"; colour = "#e0e0e0"; break;
		}
		tagBadge.setText(text);
		String textColour = type == CANDIDATE_TYPE.UNCAT ? "#555" : "white";
		tagBadge.setStyle("-fx-background-color: " + colour + "; -fx-text-fill: " + textColour + "; -fx-font-weight: bold; -fx-font-size: 1.4em;"
				+ " -fx-padding: 6 16 6 16; -fx-background-radius: 6;");
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
		String group = row.get(GROUP_COLUMN);
		if ((wholeGroup.isSelected() || shiftTag) && group != null && !group.isBlank()) {
			classifyGroup(group, type);
			return;
		}
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

	/** Tag every pair of a match group, including pairs hidden by the filters, then go on to the next group. */
	void classifyGroup(String group, CANDIDATE_TYPE type) {
		int tagged = 0, changed = 0;
		for (Map<String, String> row : rows) {
			if (!group.equals(row.get(GROUP_COLUMN))) continue;
			tagged++;
			if (!type.name().equals(row.put(CLASSIFICATION_COLUMN, type.name()))) changed++;
		}
		selectCategory(type.name());
		try {
			writeToFile(autosaveFile());
		} catch (IOException e) {
			message.setText("Autosave failed: " + e.getMessage());
			return;
		}
		String summary = (tagged == 1 ? "Tagged the only pair" : "Tagged all " + tagged + " pairs") + " of match_group " + group + " as " + type.name()
				+ " (" + changed + " changed)";

		/* inside "This group only" there is nowhere else to go; resetting to Uncat stays, like a single pair */
		if (type == CANDIDATE_TYPE.UNCAT || lockedGroup != null) {
			message.setText(summary);
			return;
		}
		for (int i = cursor + 1; i < visibleRows.size(); i++) {
			if (!group.equals(rows.get(visibleRows.get(i)).get(GROUP_COLUMN))) {
				cursor = i;
				showCurrent();
				message.setText(summary);
				return;
			}
		}
		message.setText(summary + ". That was the last group.");
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
		if (lockedGroup != null) {
			if (rows.stream().noneMatch(r -> group.equals(r.get(GROUP_COLUMN)))) {
				message.setText("No match group " + group);
				return;
			}
			setLockedGroup(group);
			return;
		}
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
		if (syncingZoom || pendingZoomSync) return;
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

	/** A toolbar label with a little extra room in front of it, to separate it from the controls before. */
	static Label spaced(Label label) {
		label.setPadding(new Insets(0, 0, 0, 10));
		return label;
	}

	/* ------------------------------------------------------------------ */
	/* layout                                                              */
	/* ------------------------------------------------------------------ */

	/**
	 * One half of the plot area. The plot is sized to its image and pushed against the middle
	 * (A to the right edge of its half, B to the left), so the two plots sit next to each other.
	 */
	StackPane buildPlotPane(Label title, CopyableLabel png, GesturePane gesture, ImageView imageView, Label missing,
			boolean towardsMiddleOnRight, Runnable onImageLoaded) {
		title.setStyle("-fx-font-weight: bold; -fx-font-size: 1.1em");
		title.setMinWidth(Label.USE_PREF_SIZE);
		png.setStyle("-fx-font-size: 0.9em; -fx-text-fill: #777;");
		png.setFocusTraversable(false);
		HBox.setHgrow(png, Priority.ALWAYS);
		HBox header = new HBox(10, title, png);
		header.setAlignment(Pos.CENTER_LEFT);

		missing.setTextFill(Paint.valueOf("darkred"));
		missing.setWrapText(true);
		missing.setVisible(false);
		missing.setMouseTransparent(true);

		StackPane holder = new StackPane(gesture, missing);
		holder.setMinSize(0, 0);
		gesture.setMinSize(0, 0);
		imageView.setPreserveRatio(true);
		imageView.setSmooth(true);

		VBox box = new VBox(4, header, holder);
		box.setMinSize(0, 0);
		header.setMinWidth(0);
		StackPane half = new StackPane(box);
		StackPane.setAlignment(box, towardsMiddleOnRight ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
		half.setMinSize(0, 0);
		half.setPrefWidth(1);
		HBox.setHgrow(half, Priority.ALWAYS);

		Runnable resize = () -> {
			double headerHeight = Math.max(header.getHeight(), 24) + box.getSpacing();
			double width = half.getWidth(), height = half.getHeight() - headerHeight;
			if (width <= 0 || height <= 0) return;
			Image image = imageView.getImage();
			double ratio = image != null && image.getWidth() > 0 ? image.getWidth() / image.getHeight() : 1.0;
			double plotWidth = Math.min(width, height * ratio), plotHeight = plotWidth / ratio;
			imageView.setFitWidth(plotWidth);
			imageView.setFitHeight(plotHeight);
			holder.setPrefSize(plotWidth, plotHeight);
			holder.setMaxSize(plotWidth, plotHeight);
			box.setMaxSize(plotWidth, plotHeight + headerHeight);
		};
		half.widthProperty().addListener(o -> resize.run());
		half.heightProperty().addListener(o -> resize.run());
		/* images load in the background: once one is ready, size it and bring its zoom back in line with the other plot */
		InvalidationListener imageLoaded = o -> {
			resize.run();
			onImageLoaded.run();
		};
		imageView.imageProperty().addListener((o, oldImage, newImage) -> {
			if (oldImage != null) oldImage.widthProperty().removeListener(imageLoaded);
			if (newImage != null) newImage.widthProperty().addListener(imageLoaded);
			resize.run();
		});
		return half;
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
		wholeGroup.setFocusTraversable(false);
		wholeGroup.setTooltip(new Tooltip("Tag every pair of the current match_group at once, including pairs hidden by the filters"));
		groupOnly.setFocusTraversable(false);
		groupOnly.setTooltip(new Tooltip("Show every pair of the current match_group (ignores the Show and match type filters)"));
		groupOnly.setOnAction(e -> {
			Map<String, String> current = currentRow();
			setLockedGroup(groupOnly.isSelected() && current != null ? current.get(GROUP_COLUMN) : null);
		});
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
		shiftTag = event.isShiftDown();
		try {
			handleKeyCode(event);
		} finally {
			shiftTag = false;
		}
	}

	void handleKeyCode(KeyEvent event) {
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
		case G: groupOnly.fire(); break;
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
		gotoGroupField.setPrefColumnCount(5);
		counterLabel.setStyle("-fx-font-weight: bold");
		counterLabel.setTooltip(new Tooltip("Pair n/N: position of this pair in the list (after filters and sorting).\n"
				+ "group g (i/k): this is pair i of the k pairs shown for match_group g."));
		sortBox.setTooltip(new Tooltip("_a / _b: value of candidate A / B. freq = 1/period of candidate A. Any other numeric column in the CSV is listed too."));
		HBox.setHgrow(matchFileField, Priority.ALWAYS);
		HBox.setHgrow(dirAField, Priority.ALWAYS);
		HBox.setHgrow(dirBField, Priority.ALWAYS);

		/* file inputs are only needed to load, so they fold away once a CSV is loaded */
		HBox inputs = new HBox(10, matchFileField, matchFileSelect, dirAField, dirASelect, dirBField, dirBSelect, load);
		inputs.setAlignment(Pos.CENTER_LEFT);
		filesPane.setText("Files");
		filesPane.setContent(inputs);
		filesPane.setAnimated(false);
		filesPane.setFocusTraversable(false);
		filesPane.setMinWidth(0);

		sortBox.setPrefWidth(150);
		classFilter.setPrefWidth(125);
		typeFilter.setPrefWidth(165);
		FlowPane toolbar = new FlowPane(8, 6, previousPair, counterLabel, nextPair,
				spaced(new Label("Group:")), gotoGroupField, gotoGroup, groupOnly,
				spaced(new Label("Sort:")), sortBox, sortOrder, spaced(new Label("Show:")), classFilter, typeFilter);
		toolbar.setAlignment(Pos.CENTER_LEFT);

		HBox plots = new HBox(6,
				buildPlotPane(titleA, pngA, gestureA, imageViewA, missingA, true, () -> syncZoom(gestureB, gestureA)),
				buildPlotPane(titleB, pngB, gestureB, imageViewB, missingB, false, () -> syncZoom(gestureA, gestureB)));

		/* comparison strip: tag and match type on the left, A / B / difference table on the right */
		detailsGrid.setHgap(0);
		detailsGrid.setVgap(0);
		detailsGrid.setStyle("-fx-background-color: white; -fx-border-color: #c5ced8; -fx-border-width: 1;");
		/* the table sits in the middle of the scroll pane while it fits, and scrolls sideways when it does not */
		HBox tableHolder = new HBox(detailsGrid);
		tableHolder.setAlignment(Pos.TOP_CENTER);
		tableScroll.setContent(tableHolder);
		tableScroll.viewportBoundsProperty().addListener((o, old, bounds) -> tableHolder.setMinWidth(bounds.getWidth()));
		tableScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		tableScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		tableScroll.setFocusTraversable(false);
		tableScroll.setStyle("-fx-background-color: transparent; -fx-background: derive(-fx-base, 26.4%); -fx-padding: 0;");
		tableScroll.setMinWidth(0);
		HBox.setHgrow(tableScroll, Priority.ALWAYS);
		matchInfo.setStyle("-fx-font-weight: bold");
		tagBadge.setMinWidth(130);
		tagBadge.setAlignment(Pos.CENTER);
		matchInfo.setWrapText(true);
		VBox tagBox = new VBox(8, tagBadge, matchInfo);
		tagBox.setMinWidth(TAG_COLUMN_WIDTH);
		tagBox.setPrefWidth(TAG_COLUMN_WIDTH);
		tagBox.setMaxWidth(TAG_COLUMN_WIDTH);
		summary.setStyle("-fx-font-weight: bold; -fx-font-size: 1.3em");
		VBox comparison = new VBox(4, summary, tableScroll);
		comparison.setAlignment(Pos.TOP_CENTER);
		HBox.setHgrow(comparison, Priority.ALWAYS);
		/* mirror the tag column on the right (an invisible separator and a block as wide as the tag column),
		   so the table is centred under the plots rather than just right of the tag */
		Separator balanceSeparator = new Separator(Orientation.VERTICAL);
		balanceSeparator.setVisible(false);
		Region balance = new Region();
		balance.setMinWidth(TAG_COLUMN_WIDTH);
		balance.setPrefWidth(TAG_COLUMN_WIDTH);
		HBox strip = new HBox(16, tagBox, new Separator(Orientation.VERTICAL), comparison, balanceSeparator, balance);
		strip.setPadding(new Insets(6, 0, 0, 0));

		message.setTextFill(Paint.valueOf("darkred"));
		keysHelp.setTextFill(Paint.valueOf("#777"));
		categories.getStyleClass().add(SegmentedButton.STYLE_CLASS_DARK);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox actions = new HBox(10, categories, wholeGroup, spacer, save, loadClassification);
		actions.setAlignment(Pos.CENTER_LEFT);
		Region statusSpacer = new Region();
		HBox.setHgrow(statusSpacer, Priority.ALWAYS);
		HBox status = new HBox(10, message, statusSpacer, keysHelp);

		VBox top = new VBox(6, filesPane, toolbar);
		VBox bottom = new VBox(6, strip, actions, status);

		BorderPane pane = new BorderPane();
		pane.setTop(top);
		pane.setCenter(plots);
		pane.setBottom(bottom);
		BorderPane.setMargin(top, new Insets(8, 16, 6, 16));
		BorderPane.setMargin(plots, new Insets(0, 16, 0, 16));
		BorderPane.setMargin(bottom, new Insets(4, 16, 8, 16));

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
