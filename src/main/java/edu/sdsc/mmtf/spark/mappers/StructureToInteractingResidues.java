package edu.sdsc.mmtf.spark.mappers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.rcsb.mmtf.api.StructureDataInterface;

import scala.Tuple2;

/**
 * Convert a full format of the file to a reduced format.
 * @author Peter Rose
 *
 */
public class StructureToInteractingResidues implements  FlatMapFunction<Tuple2<String,StructureDataInterface>, Row> {
	private static final long serialVersionUID = -3348372120358649240L;
    private String groupName;
    private double cutoffDistance;
    
    public StructureToInteractingResidues(String groupName, double cutoffDistance) {
		this.groupName = groupName;
		this.cutoffDistance = cutoffDistance;
	}
    
	@Override
	public Iterator<Row> call(Tuple2<String, StructureDataInterface> t) throws Exception {
		StructureDataInterface structure = t._2;
		
		List<Integer> groupIndices = new ArrayList<>();
		List<String> groupNames = new ArrayList<>();		
		getGroupIndices(structure, groupIndices, groupNames);
//		System.out.println("Group names:" + groupNames);
		

		List<Row> neighbors = new ArrayList<>();
		for (int i = 0; i < groupNames.size(); i++) {
			if (groupNames.get(i).equals(groupName)) {
				List<Integer> matches = new ArrayList<>();
				float[] boundingBox = calcBoundingBox(structure, groupIndices, i, cutoffDistance);
				// System.out.println("box: " + Arrays.toString(boundingBox));
				matches.addAll(findNeighbors(structure, i, boundingBox, groupIndices));
				// remove interactions with itself
//				matches.remove(i);
				neighbors.addAll(getDistanceProfile(matches, i, groupIndices, groupNames, structure));
			}
		}
		
		return neighbors.iterator();
	}
	
	private List<Row> getDistanceProfile(List<Integer> matches, int index, List<Integer> groupIndices, List<String> groupNames, StructureDataInterface structure) {
        double cutoffDistanceSq = cutoffDistance * cutoffDistance;
		
		float[] x = structure.getxCoords();
		float[] y = structure.getyCoords();
		float[] z = structure.getzCoords();
		
		int first = groupIndices.get(index);
		int last = groupIndices.get(index+1);
		
		List<Row> rows = new ArrayList<>();
		for (int i: matches) {
			if (i == index) {
				continue;
			}
			double minDSq = Double.MAX_VALUE;
			int minIndex = -1;
			for (int j = groupIndices.get(i); j < groupIndices.get(i+1); j++) {
				
				for (int k = first; k < last; k++) {
					double dx = (x[j] - x[k]);
					double dy = (y[j] - y[k]);
					double dz = (z[j] - z[k]);
					double dSq = dx*dx + dy*dy + dz*dz;
					if (dSq <= cutoffDistanceSq && dSq < minDSq) {
						minDSq = Math.min(minDSq, dSq);
						minIndex = i;
					}
				}
			}
			if (minIndex >= 0) {
				Row row = RowFactory.create(groupNames.get(index), groupNames.get(minIndex), Math.sqrt(minDSq));
//				System.out.println("adding row: " + row);
				rows.add(row);
			}
		}
		return rows;
	}
	
	private List<Integer> findNeighbors(StructureDataInterface structure, int index, float[] boundingBox, List<Integer>groupIndices) {
		float[] x = structure.getxCoords();
		float[] y = structure.getyCoords();
		float[] z = structure.getzCoords();
		
		List<Integer> matches = new ArrayList<>();
		
		for (int i = 0; i < groupIndices.size()-1; i++) {
//			System.out.println(i + ": " + groupIndices.get(i) + " -> " + groupIndices.get(i+1));
			for (int j = groupIndices.get(i); j < groupIndices.get(i+1); j++) {
				
				if (x[j] >= boundingBox[0] && x[j] <= boundingBox[1] &&
						y[j] >= boundingBox[2] && y[j] <= boundingBox[3] &&
						z[j] >= boundingBox[4] && z[j] <= boundingBox[5]) {
					matches.add(i);
//					System.out.println("match: " + i);
					break;
				}
			}
		}
		return matches;
	}

	private float[] calcBoundingBox(StructureDataInterface structure, List<Integer> groupIndices, int index, double cutoffDistance) {
		int first = groupIndices.get(index);	
		int last = groupIndices.get(index+1);

		return getBoundingBox(first, last, structure, cutoffDistance);	
	}

	private float[] getBoundingBox(int first, int last, StructureDataInterface structure, double cutoffDistance) {
		float[] boundingBox = new float[6];
		
		float[] x = structure.getxCoords();
		float[] y = structure.getyCoords();
		float[] z = structure.getzCoords();
		
		float xMin = Float.MAX_VALUE;
		float xMax = Float.MIN_VALUE;
		float yMin = Float.MAX_VALUE;
		float yMax = Float.MIN_VALUE;
		float zMin = Float.MAX_VALUE;
		float zMax = Float.MIN_VALUE;
		
		for (int i = first; i < last; i++) {
			xMin = Math.min(xMin, x[i]);
			xMax = Math.max(xMax, x[i]);
			yMin = Math.min(yMin, y[i]);
			yMax = Math.max(yMax, y[i]);
			zMin = Math.min(zMin, z[i]);
			zMax = Math.max(zMax, z[i]);
		}
		boundingBox[0] = (float) (xMin - cutoffDistance);
		boundingBox[1] = (float) (xMax + cutoffDistance);
		boundingBox[2] = (float) (yMin - cutoffDistance);
		boundingBox[3] = (float) (yMax + cutoffDistance);
		boundingBox[4] = (float) (zMin - cutoffDistance);
		boundingBox[5] = (float) (zMax + cutoffDistance);
		
		return boundingBox;

	}

	private void getGroupIndices(StructureDataInterface structure, List<Integer> groupIndices,
			List<String> groupNames) {
		int atomCounter= 0;
		int groupCounter= 0;
		int numChains = structure.getChainsPerModel()[0];
		
		// add start index for first group
		groupIndices.add(0);

		for (int i = 0; i < numChains; i++) {	
			
			for (int j = 0; j < structure.getGroupsPerChain()[i]; j++) {
				int groupIndex = structure.getGroupTypeIndices()[groupCounter];
				groupNames.add(structure.getGroupName(groupIndex));
				atomCounter+= structure.getNumAtomsInGroup(groupIndex);
				groupIndices.add(atomCounter);
				groupCounter++;	
			}
		}
	}
	
	public static StructType getSchema() {
		List<StructField> fields = new ArrayList<>();
	    fields.add(DataTypes.createStructField("Res1", DataTypes.StringType, true));
	    fields.add(DataTypes.createStructField("Res2", DataTypes.StringType, true));
	    fields.add(DataTypes.createStructField("Dist", DataTypes.DoubleType, true));
	    return DataTypes.createStructType(fields);
	}
}