/*
 *  Webapplication - Java library that runs on OpenML servers
 *  Copyright (C) 2014 
 *  @author Jan N. van Rijn (j.n.van.rijn@liacs.leidenuniv.nl)
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 */
package org.openml.webapplication.features;

import java.io.FileReader;
import java.util.*;

import org.apache.commons.lang3.ArrayUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.openml.apiconnector.algorithms.Conversion;
import org.openml.apiconnector.algorithms.QueryUtils;
import org.openml.apiconnector.io.OpenmlConnector;
import org.openml.apiconnector.settings.Constants;
import org.openml.apiconnector.xml.DataQuality;
import org.openml.apiconnector.xml.DataQuality.Quality;
import org.openml.apiconnector.xml.DataQualityUpload;
import org.openml.apiconnector.xml.DataSetDescription;
import org.openml.apiconnector.xstream.XstreamXmlMapping;
import org.openml.webapplication.fantail.dc.Characterizer;
import org.openml.webapplication.fantail.dc.StreamCharacterizer;
import org.openml.webapplication.fantail.dc.landmarking.GenericLandmarker;
import org.openml.webapplication.fantail.dc.statistical.AttributeEntropy;
import org.openml.webapplication.fantail.dc.statistical.NominalAttDistinctValues;
import org.openml.webapplication.fantail.dc.statistical.SimpleMetaFeatures;
import org.openml.webapplication.fantail.dc.statistical.Statistical;
import org.openml.webapplication.fantail.dc.stream.ChangeDetectors;
import org.openml.webapplication.fantail.dc.stream.RunChangeDetectorTask;

import com.thoughtworks.xstream.XStream;

import weka.core.Instances;
import weka.core.OptionHandler;
import weka.core.Utils;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.StringToNominal;

public class FantailConnector {
    public class QualityResult{
        public QualityResult(Double value, Integer index){
            this.value = value;
            this.index = index;
        }

        Double value;
        Integer index;
    }

    public class CharacterizerWrapper {
        public CharacterizerWrapper(Characterizer characterizer){
            this.characterizer = characterizer;
            this.index = null;
        }

        public CharacterizerWrapper(Characterizer characterizer, int index){
            this.characterizer = characterizer;
            this.index = index;
        }

        public Characterizer characterizer;
        public Integer index;

        public String[] getIDs() {
            return characterizer.getIDs();
        }

        public int getNumMetaFeatures() {
            return getIDs().length;
        }

        public Map<String, QualityResult> characterize(Instances instances){
            Map<String, Double> values =characterizer.characterize(instances);
            Map <String, QualityResult> result = new HashMap<>();
            values.forEach((s,v) -> result.put(s, new QualityResult(v, index)));
            return result;
        }
    }

	private final Integer window_size;

	private final String preprocessingPrefix = "-E \"weka.attributeSelection.CfsSubsetEval -P 1 -E 1\" -S \"weka.attributeSelection.BestFirst -D 1 -N 5\" -W ";
	private final String cp1NN = "weka.classifiers.lazy.IBk";
	private final String cpNB = "weka.classifiers.bayes.NaiveBayes";
	private final String cpASC = "weka.classifiers.meta.AttributeSelectedClassifier";
	private final String cpDS = "weka.classifiers.trees.DecisionStump";
	
	private static final XStream xstream = XstreamXmlMapping.getInstance();
	private ArrayList<CharacterizerWrapper> batchCharacterizers = new ArrayList<>();
	
	private static StreamCharacterizer[] streamCharacterizers;
	private static OpenmlConnector apiconnector;
	
	public FantailConnector(OpenmlConnector ac, Integer dataset_id, boolean random, String priorityTag, Integer interval_size) throws Exception {
		int expectedQualities = 0; 
		apiconnector = ac;
		window_size = interval_size;

		Characterizer[] characterizers = {
                new SimpleMetaFeatures(), // done before, but necessary for streams
                new Statistical(),
                new NominalAttDistinctValues(),
                new AttributeEntropy(),
                new GenericLandmarker("kNN1N", cp1NN, 2, null),
                new GenericLandmarker("NaiveBayes", cpNB, 2, null),
                new GenericLandmarker("DecisionStump", cpDS, 2, null),
                new GenericLandmarker("CfsSubsetEval_kNN1N", cpASC, 2, Utils.splitOptions(preprocessingPrefix + cp1NN)),
                new GenericLandmarker("CfsSubsetEval_NaiveBayes", cpASC, 2, Utils.splitOptions(preprocessingPrefix + cpNB)),
                new GenericLandmarker("CfsSubsetEval_DecisionStump", cpASC, 2, Utils.splitOptions(preprocessingPrefix + cpDS))
        };
		// additional parameterized batch landmarkers
		String zeros = "0";
		for( int i = 1; i <= 3; ++i ) {
			zeros += "0";
			String[] j48Option = { "-C", "." + zeros + "1" };
			characterizers = ArrayUtils.add(characterizers, new GenericLandmarker("J48." + zeros + "1.", "weka.classifiers.trees.J48", 2, j48Option));
			
			String[] repOption = { "-L", "" + i };
            characterizers = ArrayUtils.add(characterizers, new GenericLandmarker("REPTreeDepth" + i, "weka.classifiers.trees.REPTree", 2, repOption));
			
			String[] randomtreeOption = { "-depth", "" + i };
            characterizers = ArrayUtils.add(characterizers, new GenericLandmarker("RandomTreeDepth" + i, "weka.classifiers.trees.RandomTree", 2, randomtreeOption));
		}
		for (Characterizer characterizer: characterizers){
		    batchCharacterizers.add(new CharacterizerWrapper(characterizer));
        }
		for(CharacterizerWrapper characterizer : batchCharacterizers) {
			expectedQualities += characterizer.getNumMetaFeatures();
		}
		// add expected qualities for stream landmarkers (initialized later)
		expectedQualities += RunChangeDetectorTask.getNumMetaFeatures();
		
		
		if(dataset_id != null) {
			Conversion.log("OK", "Process Dataset", "Processing dataset " + dataset_id + " on special request. ");
			extractFeatures(dataset_id, window_size);
		} else {
			dataset_id = getDatasetId(expectedQualities, window_size, random, priorityTag);
			while( dataset_id != null ) {
				Conversion.log("OK", "Process Dataset", "Processing dataset " + dataset_id + " as obtained from database. ");
				extractFeatures(dataset_id, window_size);
				dataset_id = getDatasetId(expectedQualities, window_size, random, priorityTag);
			}
			Conversion.log("OK", "Process Dataset", "No more datasets to process. ");
		}
	}
	
	public Integer getDatasetId(int expectedQualities, Integer window_size, boolean random, String priorityTag) throws JSONException, Exception {
		String tagJoin = "";
		String tagSelect = "";
		String tagSort = "";
		if (priorityTag != null) {
			tagSelect = ", t.tag ";
			tagSort = "t.tag DESC, "; // to avoid NULL values first
			tagJoin = "LEFT JOIN dataset_tag t ON q.data = t.id AND t.tag = '" + priorityTag + "' ";
		}
		
		String sql = 
			"SELECT `d`.`did`, `q`.`value` AS `numInstances`, `i`.`interval_end` - `i`.`interval_start` AS `interval_size`, " +
			"CEIL(`q`.`value` / " + window_size + ") AS `numIntervals`, " +
			"(COUNT(*) / CEIL(`q`.`value` / " + window_size + ")) AS `qualitiesPerInterval`, " +
			"COUNT(*) AS `qualities` " + tagSelect + 
			"FROM `data_quality` `q` " + tagJoin + 
			", `dataset` `d`" +
			"LEFT JOIN `data_quality_interval` `i` ON `d`.`did` = `i`.`data` AND `i`.`interval_end` - `i`.`interval_start` =  " + window_size + " " +
			"WHERE `q`.`quality` IS NOT NULL " +
			"AND `d`.`did` = `q`.`data` " +
			"AND `q`.`quality` = 'NumberOfInstances'  " +
			"AND `d`.`error` = 'false' AND `d`.`processed` IS NOT NULL " +
			"GROUP BY `d`.`did` " +
			"HAVING (COUNT(*) / CEIL(`q`.`value` / " + window_size + ")) < " + expectedQualities + " " +
			"ORDER BY " + tagSort + "`qualitiesPerInterval` ASC LIMIT 0,100; ";
		
		if(window_size == null) {
			sql = 
				"SELECT q.data, COUNT(*) AS `numQualities`" + tagSelect + 
				" FROM data_quality q " + tagJoin + 
				" GROUP BY q.data HAVING numQualities BETWEEN 0 AND " + (expectedQualities-1) + 
				" ORDER BY " + tagSort + " q.data LIMIT 0,100";
		}
			
		Conversion.log("OK", "FantailQuery", sql);
		JSONArray runJson = (JSONArray) apiconnector.freeQuery(sql).get("data");
		
		
		int randomint = 0;
		
		if (random) {
			Random randomgen = new Random(System.currentTimeMillis());
			randomint = Math.abs(randomgen.nextInt());
		}
		
		if(runJson.length() > 0) {
			int dataset_id = ((JSONArray) runJson.get(randomint % runJson.length())).getInt(0);
			return dataset_id;
		} else {
			return null;
		}
	}
	
	private boolean extractFeatures(Integer did, Integer interval_size) throws Exception {
		Conversion.log("OK", "Extract Features", "Start extracting features for dataset: " + did);
		
		List<String> qualitiesAvailable = Arrays.asList(apiconnector.dataQualities(did).getQualityNames());
		if (interval_size != null) {
			// alternative approach to knowing which features are already complete. 
			String sql = 
				"SELECT i.quality, CEIL(`q`.`value` / " + window_size + ") AS `numIntervals`, COUNT(*) AS `present` " + 
				"FROM `data_quality` `q`, `dataset` `d` LEFT JOIN `data_quality_interval` `i` ON `d`.`did` = `i`.`data` AND `i`.`interval_end` - `i`.`interval_start` =  " + window_size + " " +
				"WHERE `d`.`did` = `q`.`data` AND `q`.`quality` = 'NumberOfInstances'  AND `d`.`error` = 'false' AND `d`.`processed` IS NOT NULL AND d.did = " + did + " " +
				"GROUP BY `d`.`did`,`i`.`quality` HAVING `present` = `numIntervals`";
			Conversion.log("OK", "FantailQuery for interval queries", sql);
			qualitiesAvailable = Arrays.asList(QueryUtils.getStringsFromDatabase(apiconnector, sql));
		}
		
		
		// TODO: initialize this properly!!!!!!
		streamCharacterizers = new StreamCharacterizer[1]; 
		streamCharacterizers[0] = new ChangeDetectors(interval_size);
		
		DataSetDescription dsd = apiconnector.dataGet(did);
		
		Conversion.log("OK", "Extract Features", "Start downloading dataset: " + did);
		
		Instances dataset = new Instances(new FileReader(dsd.getDataset(apiconnector.getApiKey())));
		
		if (dsd.getDefault_target_attribute() == null) {
			throw new RuntimeException("Default target attribute is null. ");
		}
		
		dataset.setClass(dataset.attribute(dsd.getDefault_target_attribute()));
		if (dsd.getRow_id_attribute() != null) {
			if (dataset.attribute(dsd.getRow_id_attribute()) != null) {
				dataset.deleteAttributeAt(dataset.attribute(dsd.getRow_id_attribute()).index());
			}
		}
		if (dsd.getIgnore_attribute() != null) {
			for (String att : dsd.getIgnore_attribute()) {
				if (dataset.attribute(att) != null) {
					dataset.deleteAttributeAt(dataset.attribute(att).index());
				}
			}
		}

		// first run stream characterizers
		for(StreamCharacterizer sc : streamCharacterizers) {

			if (qualitiesAvailable.containsAll(Arrays.asList(sc.getIDs())) == false) {
				Conversion.log("OK", "Extract Features", "Running Stream Characterizers (full data)");
				sc.characterize(dataset);
			} else {
				Conversion.log("OK", "Extract Features", "Skipping Stream Characterizers (full data) - already in database");
			}
		}
		
		List<Quality> qualities = new ArrayList<DataQuality.Quality>();
		if (interval_size != null) {
			Conversion.log("OK", "Extract Features", "Running Batch Characterizers (partial data)");
			
			for (int i = 0; i < dataset.numInstances(); i += interval_size) {
				if (apiconnector.getVerboselevel() >= Constants.VERBOSE_LEVEL_ARFF) {
					Conversion.log("OK", "FantailConnector", "Starting window [" + i + "," + (i + interval_size) + "> (did = " + did + ",total size = " + dataset.numInstances() + ")");
				}
				qualities.addAll(datasetCharacteristics(dataset, i, interval_size, null));
				
				for(StreamCharacterizer sc : streamCharacterizers) {
					// preventing nullpointer exception (if stream characterizer was already run)
					if (qualitiesAvailable.containsAll(Arrays.asList(sc.getIDs())) == false) {
						qualities.addAll(hashMaptoList(sc.interval(i), i, interval_size));
					}
				}
			}
			
		} else {
			Conversion.log("OK", "Extract Features", "Running Batch Characterizers (full data, might take a while)");
			qualities.addAll(datasetCharacteristics(dataset, null, null, qualitiesAvailable));
			for (StreamCharacterizer sc : streamCharacterizers) {
				Map<String, Double> streamqualities = sc.global();
				if (streamqualities != null) {
					qualities.addAll(hashMaptoList(streamqualities, null, null));
				}
			}
		}
		Conversion.log("OK", "Extract Features", "Done generating features, start wrapping up");
		if(qualities.size()>0){
			DataQuality dq = new DataQuality(did, qualities.toArray(new Quality[qualities.size()]));
			String strQualities = xstream.toXML(dq);
			
			DataQualityUpload dqu = apiconnector.dataQualitiesUpload(Conversion.stringToTempFile(strQualities, "qualities_did_" + did, "xml"));
			Conversion.log("OK", "Extract Features", "DONE: " + dqu.getDid());
		}
		else{
			Conversion.log("OK", "Extract Features", "DONE: Nothing to upload");
		}
		
		return true;
	}

	private List<Quality> datasetCharacteristics(Instances fulldata, Integer start, Integer interval_size, List<String> qualitiesAvailable) throws Exception {
		List<Quality> result = new ArrayList<DataQuality.Quality>();
		Instances intervalData;
		
		// Be careful changing this!
		if (interval_size != null) {
			intervalData = new Instances(fulldata, start, Math.min(interval_size, fulldata.numInstances() - start));
			intervalData = applyFilter(intervalData, new StringToNominal(), "-R first-last");
			intervalData.setClassIndex(fulldata.classIndex());
		} else {
			intervalData = fulldata;
			// todo: use StringToNominal filter? might be too expensive
		}
		
		for(CharacterizerWrapper dc : batchCharacterizers) {
			if (qualitiesAvailable != null && qualitiesAvailable.containsAll(Arrays.asList(dc.getIDs())) == false) { 
				Conversion.log("OK","Extract Batch Features", dc.getClass().getName() + ": " + Arrays.toString(dc.getIDs()));
				Map<String,QualityResult> qualities = dc.characterize(intervalData);
				result.addAll(qualityResultToList(qualities, start, interval_size));
			} else {
				Conversion.log("OK","Extract Batch Features", dc.getClass().getName() + " - already in database");
			}
		}
		return result;
	}
	
	public static List<Quality> qualityResultToList(Map<String, QualityResult> map, Integer start, Integer size) {
		List<Quality> result = new ArrayList<DataQuality.Quality>();
		for(String quality : map.keySet()) {
			Integer end = start != null ? start + size : null;
			QualityResult qualityResult = map.get(quality);
			result.add(new Quality(quality, qualityResult.value + "", start, end, qualityResult.index));
		}
		return result;
	}

    public static List<Quality> hashMaptoList(Map<String, Double> map, Integer start, Integer size) {
        List<Quality> result = new ArrayList<DataQuality.Quality>();
        for(String quality : map.keySet()) {
            Integer end = start != null ? start + size : null;
            result.add(new Quality(quality, map.get(quality) + "", start, end, null));
        }
        return result;
    }
	
	private static Instances applyFilter(Instances dataset, Filter filter, String options) throws Exception {
		((OptionHandler) filter).setOptions(Utils.splitOptions(options));
		filter.setInputFormat(dataset);
		return Filter.useFilter(dataset, filter);
	}
}
