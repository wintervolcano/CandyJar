package readers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;

import data_holders.Angle;
import data_holders.Beam;
import data_holders.EllipseConfig;
import data_holders.MetaFile;
import jsky.coords.WCSTransform;



public class ApsuseMetaReader {
	
	/* APSUSE writes 2026/05/02 22:48:03; other pipelines write ISO or day-first (02/05/2026 22:48:03) */
	private static final List<DateTimeFormatter> META_UTC_FORMATS = Arrays.asList(
			DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss[.SSSSSS][.SSS]"),
			DateTimeFormatter.ISO_DATE_TIME,
			DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss[.SSSSSS][.SSS]"),
			DateTimeFormatter.ofPattern("dd-MM-yyyy'T'HH:mm:ss[.SSSSSS][.SSS]"));

	/** The metafile's utc_start is only informational (candidates carry their own UTC), so an unknown format is not fatal. */
	static LocalDateTime parseMetaUtc(String text, String fileName) {
		for (DateTimeFormatter format : META_UTC_FORMATS) {
			try {
				return LocalDateTime.parse(text.trim(), format);
			} catch (DateTimeParseException e) {
				/* try the next format */
			}
		}
		System.err.println("[WARN] " + fileName + ": cannot read utc_start '" + text + "'; ignoring it");
		return null;
	}

	public static MetaFile parseFile(String fileName) throws FileNotFoundException {
		
		MetaFile metaFile = new MetaFile();
		metaFile.setFileName(fileName);
		metaFile.setStale(true);

		if(!Files.exists(Paths.get(fileName))) {
			System.err.println("File does not exist: " + fileName);
			System.err.println("Using empty MetaFile");
			return metaFile;
		}

		try {
		
			Reader reader = new FileReader(fileName);
			JsonReader jsonReader = Json.createReader(reader);
			JsonObject jsonObject = jsonReader.readObject();

			
			metaFile.setBandwidth(jsonObject.getJsonNumber("bandwidth").doubleValue());
			metaFile.setCentreFreq(jsonObject.getJsonNumber("centre_frequency").doubleValue());
			
			
			metaFile.setUtc(parseMetaUtc(jsonObject.getJsonString("utc_start").getString(), fileName));
			metaFile.setProjectName(jsonObject.getJsonString("project_name").getString());
			metaFile.setScheduleBlockId(jsonObject.getJsonString("sb_id").getString());
			metaFile.setOutputDir(jsonObject.getJsonString("output_dir").getString());
			
			metaFile.setTsampCoherent(jsonObject.getJsonNumber("coherent_tsamp").doubleValue());
			metaFile.setNumChansCoherent(jsonObject.getJsonNumber("coherent_tsamp").intValue());
			
			metaFile.setTsampIncoherent(jsonObject.getJsonNumber("incoherent_tsamp").doubleValue());
			metaFile.setNumChansIncoherent(jsonObject.getJsonNumber("coherent_tsamp").intValue());
			
			String beamShapeJson = jsonObject
					.get("beamshape")
					.toString();
			
			beamShapeJson = beamShapeJson.substring(1, beamShapeJson.length() -1).replaceAll("\\\\", "");

			
			JsonReader beamShapeJsonReader = Json.createReader(new StringReader(beamShapeJson));			
			JsonObject beamShapeJsonObject = beamShapeJsonReader.readObject();
			
			
			
			Beam boresight = new Beam(jsonObject.getJsonString("boresight").getString());
			
			metaFile.setBoresight(boresight);
			
			
			double  step = 1/10000000000.;
			WCSTransform wcstrans = new WCSTransform(
					  boresight.getRa().getDegreeValue(), boresight.getDec().getDegreeValue(),
					  -step * 3600,
					  step * 3600,
					  0,0, 
					  200, 200,
					  0,
					  2000, 2000.0,
					  "-TAN");
			
			
			
			EllipseConfig ellipseConfig = new EllipseConfig(beamShapeJsonObject.getJsonNumber("x").doubleValue(),
					 beamShapeJsonObject.getJsonNumber("y").doubleValue(), 
					new Angle(beamShapeJsonObject.getJsonNumber("angle").doubleValue() + "", Angle.DEG)); 
			
			Point2D.Double pDist = new Point2D.Double(ellipseConfig.getBeamX().getDegreeValue(), 
					ellipseConfig.getBeamY().getDegreeValue());
			wcstrans.imageToWorldCoords(pDist, true);
			
			
			ellipseConfig.setBeamXEq(new Angle(pDist.x/step, Angle.DEG, Angle.DEG));
			ellipseConfig.setBeamYEq(new Angle(pDist.y/step, Angle.DEG, Angle.DEG));
			
			
			metaFile.setEllipseConfig(ellipseConfig);
			
			
			JsonObject beamJsonObject = (JsonObject) jsonObject.get("beams");
			
			
			
			Set<Entry<String, JsonValue>> entrySet = beamJsonObject.entrySet();
			
			for(Entry<String, JsonValue> e: entrySet) {
				String name = (String) e.getKey();
				JsonValue value =(JsonValue) e.getValue();
				Beam beam  = new Beam(name, value.toString().strip().replaceAll("\"", ""),ellipseConfig);
				
				if(beam.getBoresightName().equals("unset")) continue;
				
				
				
				Point2D.Double p = new Point2D.Double(beam.getRa().getDegreeValue(), beam.getDec().getDegreeValue());
				wcstrans.worldToImageCoords(p, false);
				beam.setRaPixel(new Angle((boresight.getRa().getDegreeValue() + p.x * step), Angle.DEG, Angle.HHMMSS));
				beam.setDecPixel(new Angle((boresight.getDec().getDegreeValue() + p.y * step), Angle.DEG, Angle.DDMMSS));

				
				metaFile.getBeams().put(name,beam);
				
			}
			
			double minRa = metaFile.getBeams()
					.values()
					.stream()
					.min(Comparator.comparing(Beam::getRa)).get().getRa().getRadianValue();
			
			double minDec = metaFile.getBeams()
					.values()
					.stream()
					.min(Comparator.comparing(Beam::getDec)).get().getDec().getRadianValue();
			
			double maxRa = metaFile.getBeams()
					.values()
					.stream()
					.max(Comparator.comparing(Beam::getRa)).get().getRa().getRadianValue();
			
			double maxDec = metaFile.getBeams()
					.values()
					.stream()
					.max(Comparator.comparing(Beam::getDec)).get().getDec().getRadianValue();
			
			double temp; 
			
			metaFile.setMinRa(new Angle(minRa, Angle.RAD, Angle.HHMMSS));
			metaFile.setMaxRa(new Angle(maxRa, Angle.RAD, Angle.HHMMSS));
			
			if (minDec < 0  && maxDec < 0) {
				temp = minDec;
				minDec = maxDec;
				maxDec = temp;
			}
			
			metaFile.setMaxDec(new Angle(maxDec, Angle.RAD, Angle.DDMMSS));
			metaFile.setMinDec(new Angle(minDec, Angle.RAD, Angle.DDMMSS));
			
			String pngFileName = fileName + ".png";
			
			if(new File(pngFileName).exists()) {
				metaFile.setPng(new File(pngFileName));
			}
			
			
			metaFile.setStale(false);

			return metaFile;
		}
		catch(FileNotFoundException e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
			
			throw e;
			
		} 
	}

}
