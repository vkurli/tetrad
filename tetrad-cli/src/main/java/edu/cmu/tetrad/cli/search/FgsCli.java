/*
 * Copyright (C) 2015 University of Pittsburgh.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */
package edu.cmu.tetrad.cli.search;

import edu.cmu.tetrad.cli.ExtendedCommandLineParser;
import edu.cmu.tetrad.cli.data.IKnowledgeFactory;
import edu.cmu.tetrad.cli.util.Args;
import edu.cmu.tetrad.cli.util.FileIO;
import edu.cmu.tetrad.cli.util.GraphmlSerializer;
import edu.cmu.tetrad.data.BigDataSetUtility;
import edu.cmu.tetrad.data.CovarianceMatrixOnTheFly;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.search.Fgs;
import edu.cmu.tetrad.util.DataUtility;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * Fast Greedy Search (FGS) Command-line Interface.
 *
 * Nov 30, 2015 9:18:55 AM
 *
 * @author Kevin V. Bui (kvb2@pitt.edu)
 */
public class FgsCli {

    private static final String USAGE = "java -cp tetrad-cli.jar edu.cmu.tetrad.cli.search.FgsCli";

    private static final Options MAIN_OPTIONS = new Options();
    private static final Option HELP_OPTION = new Option(null, "help", false, "Show help.");

    static {
        // added required option
        Option requiredOption = new Option(null, "data", true, "Data file.");
        requiredOption.setRequired(true);
        MAIN_OPTIONS.addOption(requiredOption);

        MAIN_OPTIONS.addOption(HELP_OPTION);
        MAIN_OPTIONS.addOption(null, "knowledge", true, "A file containing prior knowledge.");
        MAIN_OPTIONS.addOption(null, "exclude-variables", true, "A file containing variables to exclude.");
        MAIN_OPTIONS.addOption(null, "delimiter", true, "Data delimiter.  Default is tab.");
        MAIN_OPTIONS.addOption(null, "penalty-discount", true, "Penalty discount.");
        MAIN_OPTIONS.addOption(null, "depth", true, "Search depth. Must be an integer >= -1 (-1 means unlimited).");
        MAIN_OPTIONS.addOption(null, "faithful", false, "Assume faithfulness.");
        MAIN_OPTIONS.addOption(null, "thread", true, "Number of threads.");
        MAIN_OPTIONS.addOption(null, "ignore-linear-dependence", false, "Ignore linear dependence.");
        MAIN_OPTIONS.addOption(null, "verbose", false, "Verbose message.");
        MAIN_OPTIONS.addOption(null, "graphml", false, "Create graphML output.");
        MAIN_OPTIONS.addOption(null, "dir-out", true, "Output directory.");
        MAIN_OPTIONS.addOption(null, "prefix-out", true, "Output prefix file name.");
    }

    private static Path dataFile;
    private static Path knowledgeFile;
    private static Path variableFile;
    private static char delimiter;
    private static double penaltyDiscount;
    private static int depth;
    private static boolean faithfulness;
    private static int numOfThreads;
    private static boolean verbose;
    private static boolean ignoreLinearDependence;
    private static boolean graphML;
    private static Path dirOut;
    private static String prefixOutput;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try {
            ExtendedCommandLineParser cmdParser = new ExtendedCommandLineParser(new DefaultParser());
            if (cmdParser.hasOption(HELP_OPTION, args)) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp(USAGE, MAIN_OPTIONS, true);
                return;
            }

            CommandLine cmd = cmdParser.parse(MAIN_OPTIONS, args);
            dataFile = Args.getPathFile(cmd.getOptionValue("data"), true);
            knowledgeFile = Args.getPathFile(cmd.getOptionValue("knowledge", null), false);
            variableFile = Args.getPathFile(cmd.getOptionValue("exclude-variables", null), false);
            delimiter = Args.getCharacter(cmd.getOptionValue("delimiter", "\t"));
            penaltyDiscount = Args.getDouble(cmd.getOptionValue("penalty-discount", "4.0"));
            depth = Args.getIntegerMin(cmd.getOptionValue("depth", "-1"), -1);
            faithfulness = cmd.hasOption("faithful");
            numOfThreads = Args.getInteger(cmd.getOptionValue("thread", Integer.toString(Runtime.getRuntime().availableProcessors())));
            verbose = cmd.hasOption("verbose");
            ignoreLinearDependence = cmd.hasOption("ignore-linear-dependence");
            graphML = cmd.hasOption("graphml");
            dirOut = Args.getPathDir(cmd.getOptionValue("dir-out", "."), false);
            prefixOutput = cmd.getOptionValue("prefix-out", String.format("fgs_%s_%d", dataFile.getFileName(), System.currentTimeMillis()));
        } catch (ParseException | FileNotFoundException | IllegalArgumentException exception) {
            System.err.println(exception.getLocalizedMessage());
            System.exit(127);
        }

        Graph graph;
        try {
            Path outputFile = Paths.get(dirOut.toString(), prefixOutput + "_output.txt");
            try (PrintStream writer = new PrintStream(new BufferedOutputStream(Files.newOutputStream(outputFile, StandardOpenOption.CREATE)))) {
                writer.println("Runtime Parameters:");
                writer.printf("number of threads = %,d\n", numOfThreads);
                writer.printf("verbose = %s\n", verbose);
                writer.println();

                writer.println("Algorithm Parameters:");
                writer.printf("penalty discount = %f\n", penaltyDiscount);
                writer.printf("depth = %s\n", depth);
                writer.printf("faithfulness = %s\n", faithfulness);
                writer.printf("ignore linear dependence = %s\n", ignoreLinearDependence);
                writer.println();

                File datasetFile = dataFile.toFile();
                int numOfCases = DataUtility.countLine(datasetFile) - 1;
                int numOfVars = DataUtility.countColumn(datasetFile, delimiter);
                writer.println("Data File:");
                writer.printf("file = %s\n", dataFile.getFileName());
                writer.printf("cases = %,d\n", numOfCases);
                writer.printf("variables = %,d\n", numOfVars);
                writer.println();

                if (knowledgeFile != null) {
                    writer.println("Knowledge:");
                    writer.printf("file = %s\n", knowledgeFile.toString());
                    writer.println();
                }

                Set<String> variables = FileIO.extractUniqueLine(variableFile);
                if (variableFile != null) {
                    writer.println("Variable Exclusion:");
                    writer.printf("file = %s\n", variableFile.toString());
                    writer.printf("variables to exclude = %,d\n", variables.size());
                    writer.println();
                }

                DataSet dataSet;
                if (variables.isEmpty()) {
                    dataSet = BigDataSetUtility.readInContinuousData(dataFile.toFile(), delimiter);
                } else {
                    dataSet = BigDataSetUtility.readInContinuousData(dataFile.toFile(), delimiter, variables);
                }
                writer.println("Dataset Read In:");
                writer.printf("cases = %,d\n", dataSet.getNumRows());
                writer.printf("variables = %,d\n", dataSet.getNumColumns());
                writer.printf("variable list size = %,d\n", dataSet.getVariableNames().size());
                writer.printf("node list size = %,d\n", dataSet.getVariables().size());
                writer.printf("variable counts should be = %,d\n", numOfVars - variables.size());
                writer.printf("unique variable list size = %,d\n", new HashSet<>(dataSet.getVariableNames()).size());

                if (verbose) {
                    writer.println();
                }

                Fgs fgs = new Fgs(new CovarianceMatrixOnTheFly(dataSet));
                fgs.setOut(writer);
                fgs.setDepth(depth);
                fgs.setIgnoreLinearDependent(ignoreLinearDependence);
                fgs.setPenaltyDiscount(penaltyDiscount);
                fgs.setNumPatternsToStore(0);  // always set to zero
                fgs.setFaithfulnessAssumed(faithfulness);
                fgs.setNumProcessors(numOfThreads);
                fgs.setVerbose(verbose);
                if (knowledgeFile != null) {
                    fgs.setKnowledge(IKnowledgeFactory.readInKnowledge(knowledgeFile));
                }
                writer.flush();

                graph = fgs.search();
                writer.println();
                writer.println(graph.toString().trim());
                writer.flush();
            }

            if (graphML) {
                Path graphOutputFile = Paths.get(dirOut.toString(), prefixOutput + "_graph.txt");
                try (PrintStream writer = new PrintStream(new BufferedOutputStream(Files.newOutputStream(graphOutputFile, StandardOpenOption.CREATE)))) {
                    writer.println(GraphmlSerializer.serialize(graph, prefixOutput));
                }
            }
        } catch (IOException exception) {
            exception.printStackTrace(System.err);
        }
    }

}
