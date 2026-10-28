package readers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import javafx.concurrent.Task;

import data_holders.Candidate;
import data_holders.Candidate.CANDIDATE_TYPE;

public class ClassificationReader extends Task<String> {

	File loadFile;
	List<Candidate> fullCandiatesList;

	public ClassificationReader(File loadFile, List<Candidate> fullCandiatesList) {
		this.loadFile = loadFile;
		this.fullCandiatesList = fullCandiatesList;
	}

    @Override
    protected String call() throws Exception {
        updateMessage("Loading classifications, please wait..");
		String returnStr = loadFromFile();
		updateMessage(returnStr);
        return returnStr;
    }

    /**
     * Reads a classification saved by CandyJar: the short file (beamid,utc,png,classification), the older
     * three-column one, or the _full file with every candidate column. The tag is the last column in all of them.
     */
    public String loadFromFile()  throws IOException{

		List<String> list = Files.readAllLines(this.loadFile.toPath());
		/* skip the header; keep only real tags, so loading never resets a candidate to UNCAT */
		List<String> classifiedList = list.stream().skip(1)
				.filter(line -> !line.isBlank() && !tagOf(line).equals(CANDIDATE_TYPE.UNCAT.name()))
				.collect(Collectors.toList());
		if (classifiedList.isEmpty()) {
			return loadFile.getName() + " has no classified candidates (every candidate is UNCAT)";
		}

		int loaded = 0;
		for (Candidate candidate : this.fullCandiatesList) {

			List<String> lines  = classifiedList.stream().filter(f -> f.contains(candidate.getPngFilePath())).collect(Collectors.toList());

			if(lines.isEmpty()) {
				continue;
			}
			if(lines.size() > 1) {
				System.err.println( lines.size() + " values for " + candidate.getPngFilePath());
				System.err.println("Using the last value: " + lines.get(lines.size()-1));
			}
			candidate.setCandidateType(CANDIDATE_TYPE.valueOf(tagOf(lines.get(lines.size()-1))));
			loaded++;

			classifiedList.removeAll(lines);

		}
		return "Loaded " + loaded + " classifications from " + loadFile.getName()
				+ (classifiedList.isEmpty() ? "" : "; " + classifiedList.size() + " tagged lines matched no candidate");
	}

	static String tagOf(String line) {
		return line.substring(line.lastIndexOf(',') + 1).trim();
	}

}
