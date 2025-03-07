/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.tools.spi;

import org.wso2.carbon.tools.CarbonTool;
import org.wso2.carbon.tools.converter.utils.BundleGeneratorUtils;
import org.wso2.carbon.tools.exception.CarbonToolException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.wso2.carbon.tools.Constants.BUNDLE_ACTIVATOR;
import static org.wso2.carbon.tools.Constants.JAR_MANIFEST_FOLDER;
import static org.wso2.carbon.tools.Constants.MANIFEST_FILE_NAME;

/**
 * This class allow to register spi services as OSGi services
 */
public class SPIProviderTool implements CarbonTool {
    private static final Logger logger = Logger.getLogger(SPIProviderTool.class.getName());
    private static final String ACTIVATOR_CLASS_FILE = "CustomBundleActivator.class";
    private static final String ACTIVATOR_JAVA_FILE = "CustomBundleActivator.java";
    private static final String INTERNAL_PKG_NAME = "internal";
    private static final String ACTIVATOR_FULL_QUALIFIED_NAME = "internal.CustomBundleActivator";

    private static final String CLASS_TEMPLATE =
            "package internal;%n" +
                    "import org.osgi.framework.BundleActivator;%n" +
                    "import org.osgi.framework.BundleContext;%n" +
                    "import %s;%n" +
                    "%n" +
                    "import %s;%n" +
                    "%n" +
                    "public class CustomBundleActivator implements BundleActivator {%n" +
                    "%n" + "    " +
                    "@Override%n" +
                    "    public void start(BundleContext bundleContext) throws Exception {%n" +
                    "        bundleContext.registerService(new String[] { \"%s\", \"%s\" }, new %s(), null);%n" +
                    "    }%n" +
                    "%n" +
                    "    @Override%n" +
                    "    public void stop(BundleContext bundleContext) throws Exception {%n" +
                    "%n" +
                    "    " +
                    "}%n" +
                    "}%n";

    @Override
    public void execute(String... toolArgs) {

        if (toolArgs.length < 4 || toolArgs.length > 5) {
            String message = "Improper usage detected. " +
                    "Usage: spi-provider.sh|bat [ICF Impl class] [Interface class name] [jar file] [destination]" +
                    " [OSGi jar path]" +
                    "First 4 arguments are compulsory.";
            logger.log(Level.INFO, message);
            return;
        }

        String spiImpl = toolArgs[0];
        String interfaceJar = toolArgs[1];
        Path jarFile = Paths.get(toolArgs[2]);
        Path destination = Paths.get(toolArgs[3]);
        String osgiJar = (toolArgs.length == 5 && !toolArgs[4].isEmpty()) ? toolArgs[4] :
                Paths.get(System.getProperty("carbon.home"), "wso2", "lib", "plugins",
                        "org.eclipse.osgi_3.14.0.jar").toString();

        Path fileName = jarFile.getFileName();
        if (fileName == null) {
            return;
        }
        String jarFileName = fileName.toString();
        Path tmpDir = destination.resolve(jarFileName.substring(0, jarFileName.lastIndexOf(".")));

        Process process = null;
        if (Files.exists(jarFile) && Files.exists(destination) && Files.isWritable(destination) &&
                !Files.exists(tmpDir)) {
            FileOutputStream fileOutputStream = null;
            try {
                Files.createDirectory(tmpDir);

                // Prepare source.
                String source = String.format(CLASS_TEMPLATE, spiImpl, interfaceJar, interfaceJar, spiImpl, spiImpl);

                // Save source in .java file.
                Path internal = Files.createDirectory(tmpDir.resolve(INTERNAL_PKG_NAME));
                File sourceFile = new File(internal.toFile(), ACTIVATOR_JAVA_FILE);
                Files.write(internal.resolve(ACTIVATOR_JAVA_FILE), source.getBytes(StandardCharsets.UTF_8));

                // Compile source file.
                if (System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")) {
                    System.setProperty("java.home", System.getenv("JAVA_HOME"));
                }
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

                List<String> compilerArgs = new ArrayList<>();
                compilerArgs.add("-cp");
                String separator = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows") ?
                        ";" : ":";
                compilerArgs.add(jarFile.toString() + separator + osgiJar);
                compilerArgs.add(sourceFile.getPath());
                fileOutputStream = new FileOutputStream(internal.resolve(ACTIVATOR_CLASS_FILE).toFile());
                compiler.run(null, fileOutputStream, null, compilerArgs.toArray(new String[0]));

                //Copy the original jar file to tmp dir
                Path finalJarPath = tmpDir.resolve(jarFileName);
                Files.copy(jarFile, finalJarPath);

                // Add the .class file to jar
                StringBuilder command = new StringBuilder();
                command.append("jar uf ")
                        .append(finalJarPath.toString())
                        .append(" -C ")
                        .append(tmpDir.toString())
                        .append(" ")
                        .append(internal.resolve(ACTIVATOR_CLASS_FILE).toString().replace(tmpDir.toString(),
                                ""));
                logger.log(Level.INFO, "Executing '" + command.toString() + "'");
                process = Runtime.getRuntime().exec(command.toString());
                process.waitFor(5, TimeUnit.SECONDS);
                addBundleActivatorHeader(finalJarPath, tmpDir);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error while running SPI Creator", e);
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, "Error while adding SPI", e);
            } catch (CarbonToolException e) {
                logger.log(Level.SEVERE, "Error while converting to bundle", e);
            } finally {
                if (process != null) {
                    process.destroy();
                }
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Error while closing OutputStream", e);
                    }
                }
            }
        } else {
            String message = "The destination location '" + tmpDir.toString() +
                    "' already exist/does not have write permissions or jar file doesn't exist";
            logger.log(Level.WARNING, message);
        }
    }

    /**
     * Add 'Bundle-Activator: CustomBundleActivator' to MANIFEST.MF file.
     *
     * @param finalJarPath Path of the jar file
     * @param destination  Destination path where jar get created.
     * @throws IOException         if an error occur while reading/writing jar file
     * @throws CarbonToolException if an error occur when converting to bundle
     */
    private void addBundleActivatorHeader(Path finalJarPath, Path destination) throws IOException, CarbonToolException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue(BUNDLE_ACTIVATOR, ACTIVATOR_FULL_QUALIFIED_NAME);
        if (BundleGeneratorUtils.isOSGiBundle(finalJarPath)) {
            logger.log(Level.INFO, "Adding '" + BUNDLE_ACTIVATOR + ": CustomBundleActivator' to " + MANIFEST_FILE_NAME);
            Path manifestmfFile = destination.resolve(MANIFEST_FILE_NAME);
            try (JarFile jar = new JarFile(finalJarPath.toString()); PrintWriter printWriter = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(manifestmfFile.toFile()),
                            "UTF-8")); InputStream inputStream = jar
                    .getInputStream(jar.getEntry(JAR_MANIFEST_FOLDER + "/" + MANIFEST_FILE_NAME));) {
                Files.copy(inputStream, destination.resolve(MANIFEST_FILE_NAME + ".tmp"));
                List<String> existingManifest = Files.readAllLines(destination.resolve(MANIFEST_FILE_NAME + ".tmp"));
                existingManifest.add(BUNDLE_ACTIVATOR + ": " + ACTIVATOR_FULL_QUALIFIED_NAME);
                existingManifest.forEach(printWriter::println);
                printWriter.flush();
            }

            Map<String, String> env = new HashMap<>();
            env.put("create", "true");
            URI uri = URI.create("jar:file:" + finalJarPath.toString());
            try (FileSystem zipfs = FileSystems.newFileSystem(uri, env)) {
                Path pathInZipfile = zipfs.getPath(JAR_MANIFEST_FOLDER, MANIFEST_FILE_NAME);
                Files.copy(manifestmfFile, pathInZipfile, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.log(Level.INFO, "Created bundle file: '" + finalJarPath.toString());
        } else {
            logger.log(Level.INFO, "Running jar to bundle conversion");
            BundleGeneratorUtils.convertFromJarToBundle(finalJarPath, destination, manifest, "");
        }
    }
}
