package randoop.jacoco;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.JavaNames;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import plume.Option;
import plume.Options;
import plume.Options.ArgException;

/**
* Program to compute coverage of tests generated by running Randoop over Pascali corpus.
*/
public class CoverageAnalyzer {

  @SuppressWarnings("WeakerAccess")
  @Option("the corpus directory")
  public static String corpusDirectoryPath;

  @SuppressWarnings("WeakerAccess")
  @Option("the path to the jacoco agent")
  public static String jacocoAgentPath;

  @SuppressWarnings("WeakerAccess")
  @Option("the junit library classpath")
  public static String junitPath;

  @SuppressWarnings("WeakerAccess")
  @Option("directory where output should be written")
  public static String outputPath;

  @SuppressWarnings("WeakerAccess")
  @Option("the path for the replacecall agent")
  public static String replacecallAgentPath;

  static Path projectPath;

  private static Options options = new Options(CoverageAnalyzer.class);

  public static void main(String[] args) {
    try {
      String[] nonargs = options.parse(args);
      if (nonargs.length > 0) {
        throw new ArgException("Unrecognized arguments: " + Arrays.toString(nonargs));
      }
    } catch (ArgException ae) {
      usage("while parsing command-line arguments: %s", ae.getMessage());
    }

    Path corpusDirectory = Paths.get(corpusDirectoryPath);
    System.out.println("Corpus directory:\t" + corpusDirectory);
    Path outputDirectory = Paths.get(outputPath);

    List<String> table = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(corpusDirectory)) {
      for (Path projectPath : stream) {
        Path testPath = projectPath.resolve("build.gradle");
        if (!testPath.toFile().exists()) continue;
        Path projectOutPath = outputDirectory.resolve(projectPath.getFileName());
        table.addAll(visitProject(projectPath, projectOutPath));
      }
    } catch (IOException e) {
      System.err.printf("Unable to read corpus directory: %s%n", e.getMessage());
      System.exit(1);
    }

    DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
    String reportFileName = "report-" + dateFormat.format(new Date()) + ".csv";
    File reportFile = outputDirectory.resolve(reportFileName).toFile();
    try (PrintStream out = new PrintStream(reportFile)) {
      out.println(reportFileName);
      out.println("project,case,covered lines, total lines, covered methods, total methods");
      for (String row : table) {
        out.println(row);
      }
    } catch (IOException e) {
      System.err.println("  Unable to write report file");
    }
  }

  private static List<String> visitProject(Path projectPath, Path projectOutPath) {
    System.out.printf("%nVisiting project: %s%n", projectPath.getFileName());
    List<String> table = new ArrayList<>();
    CoverageAnalyzer.projectPath = projectPath;
    Path scriptOutPath = projectPath.resolve("build/classes");
    try (DirectoryStream<Path> testStream = Files.newDirectoryStream(scriptOutPath, "test")) {
      for (Path testPath : testStream) {
        Path testOutPath = projectOutPath.resolve(testPath.getFileName());
        String result = visitTests(testPath, testOutPath);
        table.add(String.format("%s,%s", projectPath.getFileName(), result));
      }
    } catch (IOException e) {
      System.err.println(">>Unable to read directory " + scriptOutPath.getFileName());
    }

    return table;
  }

  private static String visitTests(Path testPath, Path testOutPath) {
    System.out.printf("[ %s ", testPath);

    Pattern testIDPattern = Pattern.compile("\\d+");
    Matcher testIDMatcher = testIDPattern.matcher(testPath.getFileName().toString());
    String testID = testPath.getFileName().toString();
    if (testIDMatcher.find()) {
       testID = testIDMatcher.group();
    }

    String format = "%s,%d,%d,%d,%d";
    String noResult = String.format(format, testID, 0, 0, 0, 0);

    List<Path> classFiles = getFiles(testPath, "*.class");
    if (classFiles.isEmpty()) {
      System.err.print(">>>>No class files found");
      System.out.println(" ]");
      return noResult;
    }

    String inputClasspath = getClasspath(testPath);
    if (inputClasspath == null) {
      System.err.println(">>>>Bad classpath in " + testPath);
      System.out.println(" ]");
      return noResult;
    }
    File execFile = testOutPath.resolve("jacoco.exec").toFile();
    Path workingDirectory = createWorkingDirectory();

    String testClasspath = inputClasspath + ":" + testPath.toString() + ":" + junitPath;
    List<String> command = new ArrayList<>();
    command.add("java");
    command.add("-Xbootclasspath/a:" + replacecallAgentPath);
    command.add("-javaagent:" + jacocoAgentPath + "=destfile=" + execFile + ",excludes=org.junit.*");
    command.add("-javaagent:" + replacecallAgentPath);
    command.add("-ea");
    command.add("-classpath");
    command.add(testClasspath);
    String DRIVER_NAME = "RegressionTestDriver";
    command.add(DRIVER_NAME);

    System.out.println(command);

    ProcessStatus status = ProcessStatus.runCommand(command, workingDirectory);
    deleteDirectory(workingDirectory.toFile());

    File logFile = testOutPath.resolve("log.txt").toFile();
    try (PrintStream log = new PrintStream(logFile)) {
      for (String line : status.outputLines) {
        log.println(line);
      }
    } catch (IOException e) {
      System.err.print(">>>>failed to write log file: " + e.getMessage());
    }

    if (status.exitStatus != 0) {
      if (status.exitStatus == 1) {
        System.err.println(">>>>Run terminated with exit status 1");
      } else {
        if (status.exitStatus == 143) {
          System.err.println(">>>>Run terminated");
        } else {
          System.err.println(">>>>Run failed with exit status " + status.exitStatus);
        }
        for (String line : status.outputLines) {
          System.err.println("      " + line);
        }
        System.out.println(" ]");
        return noResult;
      }
    }

    // load the exec file
    ExecFileLoader fileLoader = new ExecFileLoader();
    try (FileInputStream in = new FileInputStream(execFile)) {
      fileLoader.load(in);
      in.close();
    } catch (IOException e) {
      System.err.println(">>>>Failed to load exec file \"" + execFile + "\"");
      System.out.println(" ]");
      return noResult;
    }

    Path classDirPath = getInputClassDir(testPath);

    // analyze the exec file -- attaches names and code structure to results of run
    CoverageBuilder coverageBuilder = new CoverageBuilder();
    ExecutionDataStore dataStore = fileLoader.getExecutionDataStore();
    Analyzer analyzer = new Analyzer(dataStore, coverageBuilder);
    try {
      analyzer.analyzeAll(classDirPath.toFile());
    } catch (IOException e) {
      System.err.println(">>>>Failed to open class path \"" + classDirPath + "\": " + e.getMessage());
      System.out.println(" ]");
      return noResult;
    }

    Set<String> inputClasses = getInputClasses(classDirPath);
    System.out.print("    Number of classes: " + inputClasses.size());

    // dump report
    int coveredLineCount = 0;
    int totalLineCount = 0;
    int coveredMethodCount = 0;
    int totalMethodCount = 0;
    File reportFile = testOutPath.resolve("report.csv").toFile();
    try (PrintStream out = new PrintStream(reportFile)) {
      JavaNames names = new JavaNames();
      for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {
        String className = getClassName(names, classCoverage);
        if (inputClasses.contains(className)) {
          ICounter lineCounter = classCoverage.getLineCounter();
          coveredLineCount += lineCounter.getCoveredCount();
          totalLineCount += lineCounter.getTotalCount();
          ICounter methodCounter = classCoverage.getMethodCounter();
          coveredMethodCount += methodCounter.getCoveredCount();
          totalMethodCount += methodCounter.getTotalCount();
          out.println(String.format("%s,%d,%d,%d,%d", className, lineCounter.getCoveredCount(), lineCounter.getTotalCount(), methodCounter.getCoveredCount(), methodCounter.getTotalCount()));
        }
      }
    } catch (IOException e) {
      System.err.print(">>>>failed to generate report: " + e.getMessage());
    }
    System.out.println(" ]");
    return String.format("%s,%d,%d,%d,%d", testID, coveredLineCount, totalLineCount, coveredMethodCount, totalMethodCount);
  }

  private static Path createWorkingDirectory() {
    try {
      Path workingDirectory = Files.createTempDirectory("pascalicoverage");
      return workingDirectory;
    } catch (IOException e) {
      // not BugInRandoopException
      System.err.printf(
              "Unable to create temporary directory, exception: %s%n",
              e.getMessage());
      System.exit(1);
      throw new Error("unreachable statement");
    }
  }

  private static String getClasspath(Path testPath) {
    String testClasspath = projectPath.toString() + "/resources/classpath.txt";
    try (BufferedReader in = new BufferedReader(new FileReader(testClasspath))) {
      return in.readLine();
    } catch (IOException e ) {
      System.err.printf("Error reading classpath file " + e.getMessage());
    }
    return null;
  }

  private static Path getInputClassDir(Path testPath) {
    String rootPathString;
    String testClassdir = projectPath.toString() + "/resources/classdir.txt";
    try (BufferedReader in = new BufferedReader(new FileReader(testClassdir))) {
      rootPathString = in.readLine();
      return Paths.get(rootPathString);
    } catch (IOException e) {
      System.err.printf("Error reading class directory file: " + e.getMessage());
    }
    return null;
  }

  private static Set<String> getInputClasses(Path rootPath) {
    Set<String> classes = new HashSet<>();
    try {
      Files.walkFileTree(rootPath, new CollectClassFiles(rootPath, classes));
    } catch (IOException e) {
      System.err.println("Error collecting input classes from " + rootPath);
    }
    return classes;
  }

  /**
  * Constructs the {@code String} name of a class using JaCoCo classes.
  *
  * @param names  the {@code JavaNames} object
  * @param classCoverage  the {@code IClassCoverage} object
  * @return the class name
  */
  private static String getClassName(JavaNames names, IClassCoverage classCoverage) {
    String classname =
    names.getClassName(
    classCoverage.getName(),
    classCoverage.getSignature(),
    classCoverage.getSuperName(),
    classCoverage.getInterfaceNames());
    String packageName = names.getPackageName(classCoverage.getPackageName());
    if (packageName.equals("default")) {
      return classname;
    } else {
      return packageName + "." + classname;
    }
  }

  private static List<Path> getFiles(Path testPath, String glob) {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(testPath, glob)) {
      for (Path entry : stream) {
        files.add(entry);
      }
    } catch (IOException e) {
      System.err.printf("No file matching " + glob + " in " + testPath.getFileName());
    }
    return files;
  }

  /**
   * Print out usage error and stack trace and then exit.
   *
   * @param format the string format
   * @param args the arguments
   */
  private static void usage(String format, Object... args) {
    System.out.print("ERROR: ");
    System.out.printf(format, args);
    System.out.println();
    System.out.println(options.usage());
    System.exit(-1);
  }

  private static boolean deleteDirectory(File dir) {
    if (dir.isDirectory()) {
      File[] children = dir.listFiles();
      for (int i = 0; i < children.length; i++) {
        boolean success = deleteDirectory(children[i]);
        if (!success) {
          return false;
        }
      }
    }
    // either a file or an empty directory
    return dir.delete();
  }

}
