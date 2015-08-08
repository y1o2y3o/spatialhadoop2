/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/
package edu.umn.cs.spatialHadoop.delaunay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.Point;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.SpatialAlgorithms;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.delaunay.DelaunayTriangulationOutputFormat.TriangulationRecordWriter;
import edu.umn.cs.spatialHadoop.mapreduce.RTreeRecordReader3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialInputFormat3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialRecordReader3;
import edu.umn.cs.spatialHadoop.nasa.HDFRecordReader;
import edu.umn.cs.spatialHadoop.util.Parallel;
import edu.umn.cs.spatialHadoop.util.Parallel.RunnableRange;

/**
 * Computes the Delaunay triangulation (DT) for a set of points.
 * @author Ahmed Eldawy
 * 
 */
public class DelaunayTriangulation {

  /**Logger to write log messages for this class*/
  static final Log LOG = LogFactory.getLog(DelaunayTriangulation.class);
  
  public static enum DelaunayCounters {
    MAP_FINAL_SITES,
    MAP_NONFINAL_SITES,
    REDUCE_FINAL_SITES,
    REDUCE_NONFINAL_SITES,
  }
  
  /**
   * The map function computes the DT for a partition and splits the
   * triangulation into safe and non-safe edges. Safe edges are final and are
   * written to the output, while non-safe edges can be modified and are sent to
   * the reduce function.
   * 
   * @param <S> - The type of shape for sites
   */
  public static class DelaunayMap<S extends Point>
    extends Mapper<Rectangle, Iterable<S>, IntWritable, Triangulation> {
    
    /**Whether the map function should remove duplicates in the input or not*/
    private boolean deduplicate;
    /**Threshold used in duplicate detection*/
    private float threshold;
    
    /**Boundaries of columns to split partitions*/
    private double[] columnBoundaries;
    /**A writer to write final parts*/
    private TriangulationRecordWriter writer;

    @Override
    protected void setup(Context context) throws IOException,
        InterruptedException {
      Configuration conf = context.getConfiguration();
      this.deduplicate = conf.getBoolean("dedup", true);
      if (deduplicate)
        this.threshold = conf.getFloat("threshold", 1E-5f);
      this.columnBoundaries = SpatialSite.getReduceSpace(context.getConfiguration());
      Path outputPath = DelaunayTriangulationOutputFormat.getOutputPath(context);
      Path finalPath = new Path(outputPath, String.format("m-%05d.final", context.getTaskAttemptID().getTaskID().getId()));
      FileSystem fs = finalPath.getFileSystem(context.getConfiguration());
      writer = new TriangulationRecordWriter(fs, null, finalPath, context);
    }
    
    @Override
    protected void map(Rectangle key, Iterable<S> values, Context context)
        throws IOException, InterruptedException {
      IntWritable column = new IntWritable();
      List<S> sites = new ArrayList<S>();
      for (S site : values)
        sites.add((S) site.clone());

      Point[] points = sites.toArray(new Point[sites.size()]);
      
      if (deduplicate) {
        points = SpatialAlgorithms.deduplicatePoints(points, threshold);
        LOG.info("Duplicates removed and only "+points.length+" points left");
      }

      context.setStatus("Computing DT");
      LOG.info("Computing DT for "+points.length+" sites");
      GSDTAlgorithm algo = new GSDTAlgorithm(
          points, context);
      if (key.isValid()) {
        int col = Arrays.binarySearch(this.columnBoundaries, key.x1);
        if (col < 0)
          col = -col - 1;
        column.set(col);
        LOG.info("Finding final and non-final edges");
        context.setStatus("Splitting DT");
        Triangulation finalPart = new Triangulation();
        Triangulation nonfinalPart = new Triangulation();
        algo.splitIntoFinalAndNonFinalParts(key, finalPart, nonfinalPart);
        // Write final part directly to the output
        context.getCounter(DelaunayCounters.MAP_FINAL_SITES).increment(finalPart.getNumSites());
        writer.write(Boolean.TRUE, finalPart);

        // Write nonFinalpart to the reduce phase
        context.write(column, nonfinalPart);
        context.getCounter(DelaunayCounters.MAP_NONFINAL_SITES).increment(nonfinalPart.getNumSites());
      } else {
        LOG.info("Writing the whole DT to the reduce phase");
        context.setStatus("Writing DT");
        context.write(column, algo.getFinalAnswer());
      }
    }
    
    @Override
    protected void cleanup(
        Mapper<Rectangle, Iterable<S>, IntWritable, Triangulation>.Context context)
            throws IOException, InterruptedException {
      super.cleanup(context);
      writer.close(context);
    }
  }

  /**
   * Reduce function for DT. Merges some intermediate DTs vertically into
   * columns and writes back the result as one trianguation to be merged at the
   * final step. 
   * @author Ahmed Eldawy
   *
   */
  public static class DelaunayReduce
  extends Reducer<IntWritable, Triangulation, Boolean, Triangulation> {
    
    @Override
    protected void reduce(IntWritable dummy, Iterable<Triangulation> values,
        Context context) throws IOException, InterruptedException {
      List<Triangulation> triangulations = new ArrayList<Triangulation>();
      Rectangle overallMBR = new Rectangle(Double.MAX_VALUE, Double.MAX_VALUE,
          -Double.MAX_VALUE, -Double.MAX_VALUE);
      for (Triangulation t : values) {
        overallMBR.expand(t.mbr);
        triangulations.add(t);
      }      
      
      GSDTAlgorithm algo = GSDTAlgorithm.mergeTriangulations(triangulations, context);
      
      Triangulation finalPart = new Triangulation();
      Triangulation nonfinalPart = new Triangulation();
      algo.splitIntoFinalAndNonFinalParts(overallMBR, finalPart, nonfinalPart);
      
      // Write final part directly to the output path
      context.getCounter(DelaunayCounters.REDUCE_FINAL_SITES).increment(finalPart.getNumSites());
      context.write(Boolean.TRUE, finalPart);
      
      // Write non final part to the final merge phase
      context.getCounter(DelaunayCounters.REDUCE_NONFINAL_SITES).increment(nonfinalPart.getNumSites());
      context.write(Boolean.FALSE, nonfinalPart);
    }
  }
  
  /**
   * Run the DT algorithm in MapReduce
   * @param inPaths
   * @param outPath
   * @param params
   * @return
   * @throws IOException 
   * @throws ClassNotFoundException 
   * @throws InterruptedException 
   */
  public static Job delaunayMapReduce(Path[] inPaths, Path outPath, OperationsParams params) throws IOException, InterruptedException, ClassNotFoundException {
    Job job = new Job(params, "Delaunay Triangulation");
    job.setJarByClass(DelaunayTriangulation.class);

    // Set map and reduce
    job.setMapperClass(DelaunayMap.class);
    job.setMapOutputKeyClass(IntWritable.class);
    job.setMapOutputValueClass(Triangulation.class);
    job.setReducerClass(DelaunayReduce.class);

    // Set input and output
    job.setInputFormatClass(SpatialInputFormat3.class);
    SpatialInputFormat3.setInputPaths(job, inPaths);
    job.setOutputFormatClass(DelaunayTriangulationOutputFormat.class);
    TextOutputFormat.setOutputPath(job, outPath);
    
    // Set column boundaries to define the boundaries of each reducer
    SpatialSite.splitReduceSpace(job, inPaths, params);

    // Submit the job
    if (!params.getBoolean("background", false)) {
      job.waitForCompletion(params.getBoolean("verbose", false));
      if (!job.isSuccessful())
        throw new RuntimeException("Job failed!");
    } else {
      job.submit();
    }
    return job;
  }

  /**
   * Compute the Deluanay triangulation in the local machine
   * @param inPaths
   * @param outPath
   * @param params
   * @throws IOException
   * @throws InterruptedException
   */
  public static void delaunayLocal(Path[] inPaths, Path outPath,
      final OperationsParams params) throws IOException, InterruptedException {
    boolean reportMem = params.getBoolean("mem", false);
    if (reportMem) {
      Thread memThread = new Thread(new Runnable() {
        private String humanReadable(double size) {
          final String[] units = {"", "KB", "MB", "GB", "TB", "PB"};
          int unit = 0;
          while (unit < units.length && size > 1024) {
            size /= 1024;
            unit++;
          }
          return String.format("%.2f %s", size, units[unit]);
        }
        
        @Override
        public void run() {
          Runtime runtime = Runtime.getRuntime();
          while (true) {
            LOG.info(String.format("Free memory %s / Total memory %s",
                humanReadable(runtime.freeMemory()),
                humanReadable(runtime.totalMemory())));
            try {
              Thread.sleep(1000*60);
            } catch (InterruptedException e) {
            }
          }
        }}, "reportMem");
      memThread.setDaemon(true);
      memThread.start();
    }
    // 1- Split the input path/file to get splits that can be processed
    // independently
    final SpatialInputFormat3<Rectangle, Point> inputFormat =
        new SpatialInputFormat3<Rectangle, Point>();
    Job job = Job.getInstance(params);
    SpatialInputFormat3.setInputPaths(job, inPaths);
    final List<InputSplit> splits = inputFormat.getSplits(job);
    final Point[][] allLists = new Point[splits.size()][];
    
    // 2- Read all input points in memory
    LOG.info("Reading points from "+splits.size()+" splits");
    Vector<Integer> numsPoints = Parallel.forEach(splits.size(), new RunnableRange<Integer>() {
      @Override
      public Integer run(int i1, int i2) {
        try {
          int numPoints = 0;
          for (int i = i1; i < i2; i++) {
            List<Point> points = new ArrayList<Point>();
            FileSplit fsplit = (FileSplit) splits.get(i);
            final RecordReader<Rectangle, Iterable<Point>> reader =
                inputFormat.createRecordReader(fsplit, null);
            if (reader instanceof SpatialRecordReader3) {
              ((SpatialRecordReader3)reader).initialize(fsplit, params);
            } else if (reader instanceof RTreeRecordReader3) {
              ((RTreeRecordReader3)reader).initialize(fsplit, params);
            } else if (reader instanceof HDFRecordReader) {
              ((HDFRecordReader)reader).initialize(fsplit, params);
            } else {
              throw new RuntimeException("Unknown record reader");
            }
            while (reader.nextKeyValue()) {
              Iterable<Point> pts = reader.getCurrentValue();
              for (Point p : pts) {
                points.add(p.clone());
              }
            }
            reader.close();
            numPoints += points.size();
            allLists[i - i1] = points.toArray(new Point[points.size()]);
          }
          return numPoints;
        } catch (IOException e) {
          e.printStackTrace();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        return null;
      }
    }, params.getInt("parallel", Runtime.getRuntime().availableProcessors()));
    
    int totalNumPoints = 0;
    for (int numPoints : numsPoints)
      totalNumPoints += numPoints;
    
    LOG.info("Read "+totalNumPoints+" points and merging into one list");
    Point[] allPoints = new Point[totalNumPoints];
    int pointer = 0;
    
    for (int iList = 0; iList < allLists.length; iList++) {
      System.arraycopy(allLists[iList], 0, allPoints, pointer, allLists[iList].length);
      pointer += allLists[iList].length;
      allLists[iList] = null; // To let the GC collect it
    }
    
    if (params.getBoolean("dedup", true)) {
      float threshold = params.getFloat("threshold", 1E-5f);
      allPoints = SpatialAlgorithms.deduplicatePoints(allPoints, threshold);
    }
    
    LOG.info("Computing DT for "+allPoints.length+" points");
    GSDTAlgorithm dtAlgorithm = new GSDTAlgorithm(allPoints, null);
    LOG.info("DT computed");
    //dtAlgorithm.getFinalAnswer().draw();
    //Triangulation finalPart = new Triangulation();
    //Triangulation nonfinalPart = new Triangulation();
    //dtAlgorithm.splitIntoFinalAndNonFinalParts(new Rectangle(-180, -90, 180, 90), finalPart, nonfinalPart);
  }

  /**
   * Compute the DT for an input file that contains points. If the params
   * contains an explicit execution option (local or MapReduce), it is used.
   * Otherwise, this method automatically uses a single-machine (local)
   * algorithm or a MapReduce algorithm depending on the input file size.
   * 
   * @param inFiles
   * @param outFile
   * @param params
   * @return
   * @throws IOException
   * @throws InterruptedException
   * @throws ClassNotFoundException
   */
  public static Job delaunay(Path[] inFiles, Path outFile,
      final OperationsParams params) throws IOException, InterruptedException,
      ClassNotFoundException {
    if (OperationsParams.isLocal(params, inFiles)) {
      delaunayLocal(inFiles, outFile, params);
      return null;
    } else {
      return delaunayMapReduce(inFiles, outFile, params);
    }
  }
  
  private static void printUsage() {
    System.out.println("Delaunay Triangulation");
    System.out.println("Computes the delaunay triangulation of a set of points.");
    System.out.println("Parameters: (* marks required parameters)");
    System.out.println("<input file>: (*) Path to file that contains all shapes");
    System.out.println("<output file>: (*) Path to output file");
    System.out.println("shape:<s> - Type of shapes stored in the input file");
    System.out.println("-dup - Automatically remove duplicates in the input");
    System.out.println("-local - Implement a local machine algorithm (no MapReduce)");
  }

  /**
   * @param args
   * @throws IOException 
   * @throws InterruptedException 
   * @throws ClassNotFoundException 
   */
  public static void main(String[] args) throws IOException,
      InterruptedException, ClassNotFoundException {
    GenericOptionsParser parser = new GenericOptionsParser(args);
    OperationsParams params = new OperationsParams(parser);
    
    if (!params.checkInputOutput()) {
      printUsage();
      System.exit(1);
    }

    Path[] inFiles = params.getInputPaths();
    Path outFile = params.getOutputPath();

    long t1 = System.currentTimeMillis();
    delaunay(inFiles, outFile, params);
    long t2 = System.currentTimeMillis();
    System.out.println("Total time: " + (t2 - t1) + " millis");
  }
}
