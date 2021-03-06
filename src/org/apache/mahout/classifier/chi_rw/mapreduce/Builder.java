package org.apache.mahout.classifier.chi_rw.mapreduce;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.mahout.classifier.chi_rw.builder.Fuzzy_ChiBuilder;
import org.apache.mahout.classifier.chi_rw.*;
import org.apache.mahout.classifier.chi_rw.data.Dataset;
import org.apache.mahout.common.HadoopUtil;
import org.apache.mahout.common.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;

public abstract class Builder {
  
  private static final Logger log = LoggerFactory.getLogger(Builder.class);
  
  private final Fuzzy_ChiBuilder fuzzy_ChiBuilder;
  private final Path dataPath;
  private final Path datasetPath;
  private final Configuration conf;
  private String outputDirName = "output";
  
  protected Builder(Fuzzy_ChiBuilder fuzzy_ChiBuilder, Path dataPath, Path datasetPath, Configuration conf) {
	this.fuzzy_ChiBuilder = fuzzy_ChiBuilder;  
    this.dataPath = dataPath;
    this.datasetPath = datasetPath;
    this.conf = new Configuration(conf);
  }
    
  protected Fuzzy_ChiBuilder getFuzzy_ChiBuilder(){
    return fuzzy_ChiBuilder;  
  }
  
  protected Path getDataPath() {
    return dataPath;
  }

  /**
   * Returns the random seed
   * 
   * @param conf
   *          configuration
   * @return null if no seed is available
   */
  public static Long getRandomSeed(Configuration conf) {
    String seed = conf.get("mahout.rf.random.seed");
    if (seed == null) {
      return null;
    }
    
    return Long.valueOf(seed);
  }
  
  /**
   * Return the value of "mapred.map.tasks".
   * 
   * @param conf
   *          configuration
   * @return number of map tasks
   */
  public static int getNumMaps(Configuration conf) {
    return conf.getInt("mapred.map.tasks", -1);
  }

  /**
   * Used only for DEBUG purposes. if false, the mappers doesn't output anything, so the builder has nothing
   * to process
   * 
   * @param conf
   *          configuration
   * @return true if the builder has to return output. false otherwise
   */
  protected static boolean isOutput(Configuration conf) {
    return conf.getBoolean("debug.mahout.fc.output", true);
  }
  
  public static Fuzzy_ChiBuilder getFuzzy_ChiBuilder(Configuration conf) {
    String string = conf.get("mahout.fc.fuzzy_ChiBuilder");
    if (string == null) {
      return null;
    }
    
    return StringUtils.fromString(string);
  }
  
  private static void setFuzzy_ChiBuilder(Configuration conf, Fuzzy_ChiBuilder fuzzy_ChiBuilder) {
    conf.set("mahout.fc.fuzzy_ChiBuilder", StringUtils.toString(fuzzy_ChiBuilder));
  }
 
  /**
   * Sets the Output directory name, will be creating in the working directory
   * 
   * @param name
   *          output dir. name
   */
  public void setOutputDirName(String name) {
    outputDirName = name;
  }
  
  /**
   * Output Directory name
   * 
   * @param conf
   *          configuration
   * @return output dir. path (%WORKING_DIRECTORY%/OUTPUT_DIR_NAME%)
   * @throws IOException
   *           if we cannot get the default FileSystem
   */
  protected Path getOutputPath(Configuration conf) throws IOException {
    // the output directory is accessed only by this class, so use the default
    // file system
    FileSystem fs = FileSystem.get(conf);
    return new Path(fs.getWorkingDirectory(), outputDirName);
  }
  
  /**
   * Helper method. Get a path from the DistributedCache
   * 
   * @param conf
   *          configuration
   * @param index
   *          index of the path in the DistributedCache files
   * @return path from the DistributedCache
   * @throws IOException
   *           if no path is found
   */
  public static Path getDistributedCacheFile(Configuration conf, int index) throws IOException {
    URI[] files = DistributedCache.getCacheFiles(conf);
    
    if (files == null || files.length <= index) {
      throw new IOException("path not found in the DistributedCache");
    }
    
    return new Path(files[index].getPath());
  }
  
  /**
   * Helper method. Load a Dataset stored in the DistributedCache
   * 
   * @param conf
   *          configuration
   * @return loaded Dataset
   * @throws IOException
   *           if we cannot retrieve the Dataset path from the DistributedCache, or the Dataset could not be
   *           loaded
   */
  public static Dataset loadDataset(Configuration conf) throws IOException {
    Path datasetPath = getDistributedCacheFile(conf, 0);
    
    return Dataset.load(conf, datasetPath);
  }
  
  /**
   * Used by the inheriting classes to configure the job
   * 
   *
   * @param job
   *          Hadoop's Job
   * @throws IOException
   *           if anything goes wrong while configuring the job
   */
  protected abstract void configureJob(Job job) throws IOException;
  
  /**
   * Sequential implementation should override this method to simulate the job execution
   * 
   * @param job
   *          Hadoop's job
   * @return true is the job succeeded
   */
  protected boolean runJob(Job job) throws ClassNotFoundException, IOException, InterruptedException {
    return job.waitForCompletion(true);
  }
  
  /**
   * Parse the output files to extract the model and pass the predictions to the callback
   * 
   * @param job
   *          Hadoop's job
   * @return Built DecisionForest
   * @throws IOException
   *           if anything goes wrong while parsing the output
   */
  protected abstract RuleBase parseOutput(Job job) throws IOException;
  
  public RuleBase build() throws IOException, ClassNotFoundException, InterruptedException {
    
    Path outputPath = getOutputPath(conf);
    FileSystem fs = outputPath.getFileSystem(conf);
    
    // check the output
    if (fs.exists(outputPath)) {
      throw new IOException("Chi: Output path already exists : " + outputPath);
    }

    setFuzzy_ChiBuilder(conf, fuzzy_ChiBuilder);
    
    // put the dataset into the DistributedCache
    DistributedCache.addCacheFile(datasetPath.toUri(), conf);
    
    Job job = new Job(conf, "fuzzy_Chi builder");
    
    log.debug("Chi: Configuring the job...");
    configureJob(job);
    
    log.debug("Chi: Running the job...");
    if (!runJob(job)) {
      log.error("Chi: Job failed!");
      return null;
    }
    
    if (isOutput(conf)) {
      log.debug("Parsing the output...");
      RuleBase ruleBase = parseOutput(job);
      HadoopUtil.delete(conf, outputPath);
      return ruleBase;
    }
    
    return null;
  }
  
  /**
   * sort the splits into order based on size, so that the biggest go first.<br>
   * This is the same code used by Hadoop's JobClient.
   * 
   * @param splits
   *          input splits
   */
  public static void sortSplits(InputSplit[] splits) {
    Arrays.sort(splits, new Comparator<InputSplit>() {
      @Override
      public int compare(InputSplit a, InputSplit b) {
        try {
          long left = a.getLength();
          long right = b.getLength();
          if (left == right) {
            return 0;
          } else if (left < right) {
            return 1;
          } else {
            return -1;
          }
        } catch (IOException ie) {
          throw new IllegalStateException("Problem getting input split size", ie);
        } catch (InterruptedException ie) {
          throw new IllegalStateException("Problem getting input split size", ie);
        }
      }
    });
  }
}

