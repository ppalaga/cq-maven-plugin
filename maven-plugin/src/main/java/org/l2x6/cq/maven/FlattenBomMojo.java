/**
 * Copyright (c) 2020 CQ Maven Plugin
 * project contributors as indicated by the @author tags.
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
package org.l2x6.cq.maven;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputLocation.StringFormatter;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.model.io.xpp3.MavenXpp3WriterEx;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.assertj.core.util.diff.Delta;
import org.assertj.core.util.diff.DiffUtils;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.repository.RemoteRepository;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.l2x6.cq.common.OnFailure;
import org.l2x6.pom.tuner.ExpressionEvaluator;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.NodeGavtcs;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.PomTunerUtils;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavPattern;
import org.l2x6.pom.tuner.model.GavSet;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Module;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static java.util.stream.Collectors.joining;

/**
 * Flattens the dependency management section of the current pom.xml file.
 *
 * @since 2.24.0
 */
@Mojo(name = "flatten-bom", threadSafe = true, requiresProject = true)
public class FlattenBomMojo extends AbstractMojo {

    public static final String DEFAULT_FLATTENED_REDUCED_VERBOSE_POM_FILE = "src/main/generated/flattened-reduced-verbose-pom.xml";
    public static final String DEFAULT_FLATTENED_REDUCED_POM_FILE = "src/main/generated/flattened-reduced-pom.xml";
    public static final String DEFAULT_FLATTENED_FULL_POM_FILE = "src/main/generated/flattened-full-pom.xml";
    public static final String ORG_APACHE_CAMEL_QUARKUS_GROUP_ID = "org.apache.camel.quarkus";

    /**
     * The Maven project.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${project.build.sourceEncoding}", required = true, property = "project.build.sourceEncoding")
    String encoding;
    Charset charset;

    /**
     * The root directory of the Camel Quarkus source tree.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    File multiModuleProjectDirectory;
    Path rootModuleDirectory;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.flatten-bom.skip", defaultValue = "false")
    boolean skip;

    /**
     * A list of {@link GavPattern}s to match against the GAVs of BOMs in which the given BOM entry is defined.
     * The entries satisfying this criterion will be excluded from the resulting flattened BOM.
     * <p>
     * We will typically want to exclude entries defined in {@code io.quarkus:quarkus-bom}. To do so, we would have to
     * set the following:
     *
     * <pre>
     * {@code
     * <originExcludes>
     *   <originExclude>io.quarkus:quarkus-bom</originExclude>
     * </originExcludes>
     * }
     * </pre>
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.originExcludes")
    List<String> originExcludes;

    /**
     * A list of GAV patterns to select a set of entries from the original non-flattened BOM. These initial artifacts
     * will be resolved, and their transitive dependencies will serve as a filter for keeping entries from the full
     * flattened BOM.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionEntryPointIncludes")
    List<String> resolutionEntryPointIncludes;

    /**
     * A list of GAV patterns whose matching entries will be removed from the initial GAV set selected by
     * {@link #resolutionEntryPointIncludes}.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionEntryPointExcludes")
    List<String> resolutionEntryPointExcludes;

    /**
     * As list of GAV patterns whose origin will be logged. Useful when searching on which BOM entry some specific
     * exclusion needs to be placed.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionSuspects")
    List<String> resolutionSuspects;

    /**
     * Where to store the non-reduced flattened BOM. An absolute path or a path relative to <code>${basedir}</code>.
     * Useful as a base for comparisons with {@link #flattenedReducedVerbosePomFile}.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = DEFAULT_FLATTENED_FULL_POM_FILE, property = "cq.flattenedFullPomFile")
    File flattenedFullPomFile;

    /**
     * Where to store the reduced flattened BOM. An absolute path or a path relative to <code>${basedir}</code>.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = DEFAULT_FLATTENED_REDUCED_POM_FILE, property = "cq.flattenedReducedPomFile")
    File flattenedReducedPomFile;

    /**
     * Where to store the reduced flattened BOM with comments about the origin of individual entries. An absolute path
     * or a path relative to <code>${basedir}</code>.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = DEFAULT_FLATTENED_REDUCED_VERBOSE_POM_FILE, property = "cq.flattenedReducedVerbosePomFile")
    File flattenedReducedVerbosePomFile;

    /**
     * What should happen when the checks performed by this plugin fail. Possible values: {@code WARN}, {@code FAIL},
     * {@code IGNORE}.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.onCheckFailure", defaultValue = "FAIL")
    OnFailure onCheckFailure;

    /**
     * Add exclusions to the specified artifacts coming from third party imported BOMs in the flattened BOM.
     * An example:
     *
     * <pre>
     * {@code
     * <addExclusion>
     *     <gavPattern>org.apache.kafka:connect-runtime</gavPattern>
     *     <exclusions>javax.activation:activation,javax.servlet:javax.servlet-api,log4j:log4j</exclusions>
     * </addExclusion>
     * }
     * </pre>
     *
     * @since      2.24.0
     * @deprecated use the more general {@link #bomEntryTransformations}
     */
    @Parameter
    List<BomEntryTransformation> addExclusions;

    /**
     * Add exclusions to the specified artifacts coming from third party imported BOMs in the flattened BOM,
     * or perform some version transformations.
     *
     * An example:
     *
     * <pre>
     * {@code
     * <bomEntryTransformations>
     * <bomEntryTransformation>
     *     <gavPattern>org.apache.kafka:connect-runtime</gavPattern>
     *     <addExclusions>javax.activation:activation,javax.servlet:javax.servlet-api,log4j:log4j</addExclusions>
     * </bomEntryTransformation>
     * <bomEntryTransformation>
     *     <gavPattern>org.apache.kafka:connect-runtime</gavPattern>
     *     <versionReplacement>(1.2).3/$1.4</versionReplacement>
     * </bomEntryTransformation>
     * </bomEntryTransformations>
     * }
     * </pre>
     *
     * @since 2.28.0
     */
    @Parameter
    List<BomEntryTransformation> bomEntryTransformations;

    /**
     * If {@code true}, assume there are no relevant changes in the source tree and just install the selected flattened
     * POM file flavor; otherwise recompute all the BOM filtering and install the potentially changed file.
     *
     * @since 2.25.0
     */
    @Parameter(defaultValue = "false", property = "quickly")
    boolean quickly;

    public static enum InstallFlavor {
        FULL, REDUCED, REDUCED_VERBOSE, ORIGINAL
    }

    /**
     * Which flavor of flattened BOM should be installed, useful for testing and debugging purposes. Possible values:
     * <ul>
     * <li>{@code FULL} - see {@link #flattenedFullPomFile}
     * <li>{@code REDUCED} (default) - see {@link #flattenedReducedPomFile}
     * <li>{@code REDUCED_VERBOSE} - see {@link #flattenedReducedVerbosePomFile}
     * <li>{@code ORIGINAL} - the original non-flattened BOM gets installed; {@link #flattenedFullPomFile},
     * {@link #flattenedReducedPomFile} and {@link #flattenedReducedVerbosePomFile} are still written but none of them
     * is installed,
     *
     * @since 2.25.0
     */
    @Parameter(defaultValue = "REDUCED", property = "cq.flatten-bom.installFlavor")
    InstallFlavor installFlavor;

    /**
     * If {@code true} performs any possible edits to fix issues found by consistency checks. The mojo will still fail
     * if any fix is performed.
     *
     * @since 2.25.0
     */
    @Parameter(property = "cq.flatten-bom.format", defaultValue = "false")
    boolean format;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.25.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    @Component
    RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    private static final GavSet toResolveDependencies = GavSet.builder()
            .excludes(ORG_APACHE_CAMEL_QUARKUS_GROUP_ID, "io.quarkus")
            .build();

    private static final Pattern LOCATION_COMMENT_PATTERN = Pattern.compile("\\s*\\Q<!--#}\\E");

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);
        rootModuleDirectory = multiModuleProjectDirectory.toPath().toAbsolutePath().normalize();
        final Path fullPomPath = basedir.toPath().resolve(flattenedFullPomFile.toPath());
        final Path reducedVerbosePamPath = basedir.toPath().resolve(flattenedReducedVerbosePomFile.toPath());
        final Path reducedPomPath = basedir.toPath().resolve(flattenedReducedPomFile.toPath());
        if (bomEntryTransformations == null) {
            bomEntryTransformations = new ArrayList<>();
        }
        if (addExclusions != null) {
            bomEntryTransformations.addAll(addExclusions);
        }

        new FlattenBomTask(
                resolutionEntryPointIncludes,
                resolutionEntryPointExcludes,
                resolutionSuspects,
                originExcludes,
                bomEntryTransformations,
                onCheckFailure,
                project,
                rootModuleDirectory,
                fullPomPath,
                reducedVerbosePamPath,
                reducedPomPath,
                charset,
                getLog(),
                repositories,
                repoSystem,
                repoSession,
                getProfiles(session),
                format,
                simpleElementWhitespace,
                installFlavor,
                quickly)
                        .execute();

    }

    static void write(List<Dependency> finalConstraints, Path flattenedPomPath, Model project, Charset charset,
            boolean verbose, StringFormatter formatter) {
        final Model model = new Model();
        model.setModelVersion(project.getModelVersion());
        model.setGroupId(project.getGroupId());
        model.setArtifactId(project.getArtifactId());
        model.setVersion(project.getVersion());
        model.setPackaging("pom");
        model.setName(project.getName());
        model.setDescription(project.getDescription());
        model.setUrl(project.getUrl());
        model.setLicenses(project.getLicenses());
        model.setDevelopers(project.getDevelopers());
        model.setScm(project.getScm());
        model.setIssueManagement(project.getIssueManagement());
        model.setDistributionManagement(project.getDistributionManagement());
        final DependencyManagement dm = new DependencyManagement();
        dm.setDependencies(finalConstraints);
        model.setDependencyManagement(dm);

        try {
            Files.createDirectories(flattenedPomPath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Could not create " + flattenedPomPath.getParent(), e);
        }

        final String newContent;
        final StringWriter sw = new StringWriter();
        try {
            if (verbose) {
                MavenXpp3WriterEx xpp3Writer = new MavenXpp3WriterEx();
                xpp3Writer.setStringFormatter(formatter);
                xpp3Writer.write(sw, model);
            } else {
                new MavenXpp3Writer().write(sw, model);
            }
        } catch (IOException e1) {
            throw new RuntimeException("Could not serialize pom.xml model: \n\n" + model);
        }
        newContent = reformat(sw.toString(), charset);

        final String originalContent;
        if (Files.exists(flattenedPomPath)) {
            try {
                originalContent = new String(Files.readAllBytes(flattenedPomPath), charset);
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + flattenedPomPath, e);
            }
        } else {
            originalContent = "";
        }

        if (!newContent.equals(originalContent)) {
            try (Writer out = Files.newBufferedWriter(flattenedPomPath, charset)) {
                out.write(newContent);
            } catch (IOException e) {
                throw new RuntimeException("Could not write " + flattenedPomPath, e);
            }
        }
    }

    static void addExclusion(ContainerElement exclusions, Ga newExclusion) {
        Node refNode = null;
        for (ContainerElement dep : exclusions.childElements()) {
            final Ga depGavtcs = dep.asGavtcs().toGa();
            int comparison = newExclusion.compareTo(depGavtcs);
            if (comparison == 0) {
                /* the given exclusion is available, no need to add it */
                return;
            }
            if (refNode == null && comparison < 0) {
                refNode = dep.previousSiblingInsertionRefNode();
            }
        }

        if (refNode == null) {
            refNode = exclusions.getOrAddLastIndent();
        }
        final ContainerElement dep = exclusions.addChildContainerElement("exclusion", refNode, false, false);
        dep.addChildTextElement("groupId", newExclusion.getGroupId());
        dep.addChildTextElement("artifactId", newExclusion.getArtifactId());
    }

    static boolean compare(String pomTunerValue, String mavenValue, String defaultValue) {
        if (mavenValue == null || mavenValue.isEmpty() || defaultValue.equals(mavenValue)) {
            return pomTunerValue == null || pomTunerValue.isEmpty() || defaultValue.equals(pomTunerValue);
        }
        return mavenValue.equals(pomTunerValue);
    }

    public static Predicate<Profile> getProfiles(MavenSession session) {
        final Predicate<Profile> profiles = ActiveProfiles.of(
                session.getCurrentProject().getActiveProfiles().stream()
                        .map(org.apache.maven.model.Profile::getId)
                        .toArray(String[]::new));
        return profiles;
    }

    static String reformat(String xml, Charset charset) {
        SAXBuilder builder = new SAXBuilder();
        builder.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        builder.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            Document effectiveDocument = builder.build(new StringReader(xml));

            StringWriter w = new StringWriter();
            Format format = Format.getPrettyFormat();
            format.setEncoding(charset.name());
            format.setLineSeparator("\n");
            format.setOmitDeclaration(false);
            XMLOutputter out = new XMLOutputter(format);
            out.output(effectiveDocument, w);

            return LOCATION_COMMENT_PATTERN.matcher(w.toString()).replaceAll("<!--");
        } catch (JDOMException | IOException e) {
            throw new RuntimeException("Could not reformat ", e);
        }
    }

    public static class FlattenBomTask {
        private final List<String> resolutionEntryPointIncludes;
        private final List<String> resolutionEntryPointExcludes;
        private final List<String> resolutionSuspects;
        private final List<String> originExcludes;
        private final List<BomEntryTransformation> bomEntryTransformations;
        private final OnFailure onCheckFailure;
        private final Model effectivePomModel;
        private final String version;
        private final Path basePath;
        private final Path rootModuleDirectory;
        private final Path fullPomPath;
        private final Path reducedVerbosePamPath;
        private final Path reducedPomPath;
        private final Charset charset;
        private final Log log;
        private final List<RemoteRepository> repositories;
        private final RepositorySystem repoSystem;
        private final RepositorySystemSession repoSession;
        private final Predicate<Profile> profiles;
        private final boolean format;
        private final SimpleElementWhitespace simpleElementWhitespace;
        private final MavenProject project;
        private final InstallFlavor installFlavor;
        private final boolean quickly;

        public FlattenBomTask(List<String> resolutionEntryPointIncludes, List<String> resolutionEntryPointExcludes,
                List<String> resolutionSuspects, List<String> originExcludes,
                List<BomEntryTransformation> bomEntryTransformations, OnFailure onCheckFailure,
                MavenProject project,
                Path rootModuleDirectory, Path fullPomPath, Path reducedVerbosePamPath,
                Path reducedPomPath, Charset charset, Log log, List<RemoteRepository> repositories, RepositorySystem repoSystem,
                RepositorySystemSession repoSession, Predicate<Profile> profiles, boolean format,
                SimpleElementWhitespace simpleElementWhitespace, InstallFlavor installFlavor, boolean quickly) {
            this.resolutionEntryPointIncludes = resolutionEntryPointIncludes;
            this.resolutionEntryPointExcludes = resolutionEntryPointExcludes;
            this.resolutionSuspects = resolutionSuspects;
            this.originExcludes = originExcludes;
            this.bomEntryTransformations = mergeTransformations(rootModuleDirectory, bomEntryTransformations, charset);
            this.onCheckFailure = onCheckFailure;
            this.project = project;
            this.effectivePomModel = project.getModel();
            this.version = project.getVersion();
            this.basePath = project.getBasedir().toPath();
            this.rootModuleDirectory = rootModuleDirectory;
            this.fullPomPath = resolve(this.basePath, fullPomPath, DEFAULT_FLATTENED_FULL_POM_FILE);
            this.reducedVerbosePamPath = resolve(this.basePath, reducedVerbosePamPath,
                    DEFAULT_FLATTENED_REDUCED_VERBOSE_POM_FILE);
            this.reducedPomPath = resolve(this.basePath, reducedPomPath, DEFAULT_FLATTENED_REDUCED_POM_FILE);
            this.charset = charset;
            this.log = log;
            this.repositories = repositories;
            this.repoSystem = repoSystem;
            this.repoSession = repoSession;
            this.profiles = profiles;
            this.format = format;
            this.simpleElementWhitespace = simpleElementWhitespace;
            this.installFlavor = installFlavor;
            this.quickly = quickly;
        }

        static List<BomEntryTransformation> mergeTransformations(Path rootModuleDirectory,
                List<BomEntryTransformation> bomEntryTransformations, Charset charset) {
            final List<BomEntryTransformation> result = new ArrayList<>();
            final Path prodArtifacts = rootModuleDirectory
                    .resolve("product/src/main/generated/transitive-dependencies-non-productized.txt");
            if (Files.isRegularFile(prodArtifacts)) {
                try {
                    Files.readAllLines(prodArtifacts, charset).stream()
                            .filter(line -> !line.isBlank())
                            .map(line -> new BomEntryTransformation(line, "[\\-\\.]redhat-\\d+$/", null, null))
                            .forEach(result::add);
                } catch (IOException e) {
                    throw new RuntimeException("Could not read " + prodArtifacts, e);
                }
            }
            result.addAll(bomEntryTransformations);
            return result;
        }

        static Path resolve(Path basePath, Path relPath, String defaultPath) {
            return basePath.resolve(relPath == null ? Paths.get(defaultPath) : relPath);
        }

        public Path execute() {
            if (!quickly) {
                final GavSet excludedByOrigin = GavSet.builder()
                        .includes(originExcludes == null ? Collections.emptyList() : originExcludes)
                        .build();
                final GavSet resolveSet = GavSet.builder()
                        .includes(resolutionEntryPointIncludes == null ? Collections.emptyList() : resolutionEntryPointIncludes)
                        .excludes(resolutionEntryPointExcludes == null ? Collections.emptyList() : resolutionEntryPointExcludes)
                        .build();

                /* Get the effective pom */
                final DependencyManagement effectiveDependencyManagement = effectivePomModel.getDependencyManagement();
                final List<Dependency> originalConstrains;
                if (effectiveDependencyManagement == null) {
                    originalConstrains = Collections.emptyList();
                } else {
                    final List<Dependency> deps = effectiveDependencyManagement.getDependencies();
                    originalConstrains = deps == null
                            ? Collections.emptyList()
                            : Collections.unmodifiableList(deps.stream()
                                    .map(Dependency::clone)
                                    .peek(dep -> {
                                        if (!bomEntryTransformations.isEmpty()) {
                                            bomEntryTransformations.stream()
                                                    .filter(transformation -> transformation.getGavPattern().matches(
                                                            dep.getGroupId(),
                                                            dep.getArtifactId(), dep.getVersion()))
                                                    .peek(transformation -> dep
                                                            .setVersion(transformation.replaceVersion(dep.getVersion())))
                                                    .forEach(transformation -> dep.getExclusions()
                                                            .addAll(transformation.getAddExclusions()));
                                        }
                                    })
                                    .collect(Collectors.toList()));
                }

                /* Collect the GAs required by our extensions */
                final Set<Ga> requiredGas = collectRequiredGas(originalConstrains, resolveSet);

                /* Filter out constraints managed in io.quarkus:quarkus-bom */
                final List<Dependency> filteredConstraints = Collections.unmodifiableList(originalConstrains.stream()
                        /* Filter by origin */
                        .filter(dep -> {
                            final Gav locationGav = Gav.of(dep.getLocation("artifactId").getSource().getModelId());
                            return !excludedByOrigin.contains(locationGav.getGroupId(), locationGav.getArtifactId(),
                                    locationGav.getVersion());
                        })
                        /* Exclude non-required constraints */
                        .filter(dep -> requiredGas.contains(new Ga(dep.getGroupId(), dep.getArtifactId())))
                        .collect(Collectors.toList()));

                StringFormatter formatter = new InputLocationStringFormatter(version);
                write(originalConstrains, fullPomPath, effectivePomModel, charset, true, formatter);
                write(filteredConstraints, reducedVerbosePamPath, effectivePomModel, charset, true, formatter);
                write(filteredConstraints, reducedPomPath, effectivePomModel, charset, false, formatter);
            }
            final Path result;
            switch (installFlavor) {
            case FULL:
                result = fullPomPath;
                break;
            case REDUCED:
                result = reducedPomPath;
                break;
            case REDUCED_VERBOSE:
                result = reducedVerbosePamPath;
                break;
            case ORIGINAL:
                /* nothing to do */
                result = project.getFile().toPath();
                break;
            default:
                throw new IllegalStateException(
                        "Unexpected " + InstallFlavor.class.getSimpleName() + ": " + installFlavor);
            }
            project.setPomFile(result.toFile());
            return result;

        }

        Set<Ga> collectRequiredGas(List<Dependency> originalConstrains, GavSet resolveSet) {

            final MavenSourceTree t = MavenSourceTree.of(rootModuleDirectory.resolve("pom.xml"), charset);
            final Set<Gavtcs> depsToResolve = collectDependenciesToResolve(originalConstrains, resolveSet, t);

            /* Assume that the current BOM's parent is both installed already and that it has no dependencies */
            final Parent parent = effectivePomModel.getParent();
            final DefaultArtifact emptyInstalledArtifact = new DefaultArtifact(
                    parent.getGroupId(),
                    parent.getArtifactId(),
                    null,
                    "pom",
                    parent.getVersion());
            final Set<Ga> ownGas = t.getModulesByGa().keySet();
            log.debug("Constraints");
            final List<org.eclipse.aether.graph.Dependency> aetherConstraints = originalConstrains.stream()
                    .filter(dep -> !ownGas.contains(new Ga(dep.getGroupId(), dep.getArtifactId())))
                    .map(dep -> new org.eclipse.aether.graph.Dependency(
                            new DefaultArtifact(
                                    dep.getGroupId(),
                                    dep.getArtifactId(),
                                    dep.getClassifier(),
                                    dep.getType(),
                                    dep.getVersion()),
                            null,
                            false,
                            dep.getExclusions().stream()
                                    .map(e -> new org.eclipse.aether.graph.Exclusion(e.getGroupId(), e.getArtifactId(), "*",
                                            "*"))
                                    .collect(Collectors.toList())))
                    .peek(dep -> log.debug(" - " + dep + " " + dep.getExclusions()))
                    .collect(Collectors.toList());

            final List<GavPattern> suspects = resolutionSuspects == null
                    ? Collections.emptyList()
                    : resolutionSuspects.stream().map(GavPattern::of).collect(Collectors.toList());

            final GavSet collectorExcludes = GavSet.builder().include(parent.getGroupId() + ":" + parent.getArtifactId())
                    .build();
            final Set<Ga> allTransitives = new TreeSet<>();
            final Map<Gavtcs, Set<Ga>> transitivesByBomEntry = new LinkedHashMap<>();
            for (Gavtcs entryPoint : depsToResolve) {

                final DependencyCollector collector = new DependencyCollector(collectorExcludes);
                final CollectRequest request = new CollectRequest()
                        .setRoot(new org.eclipse.aether.graph.Dependency(emptyInstalledArtifact, null))
                        .setRepositories(repositories)
                        .setManagedDependencies(aetherConstraints)
                        .setDependencies(
                                Collections.singletonList(
                                        new org.eclipse.aether.graph.Dependency(
                                                new DefaultArtifact(
                                                        entryPoint.getGroupId(),
                                                        entryPoint.getArtifactId(),
                                                        entryPoint.getType(),
                                                        entryPoint.getVersion()),
                                                null)));
                try {
                    final DependencyNode rootNode = repoSystem.collectDependencies(repoSession, request).getRoot();
                    rootNode.accept(collector);
                } catch (DependencyCollectionException | IllegalArgumentException e) {
                    throw new RuntimeException("Could not resolve dependencies of " + entryPoint, e);
                }

                if (!suspects.isEmpty()) {
                    for (Iterator<GavPattern> it = suspects.iterator(); it.hasNext();) {
                        final GavPattern pat = it.next();
                        if (collector.allTransitives.stream()
                                .anyMatch(ga -> pat.matches(ga.getGroupId(), ga.getArtifactId()))) {
                            log.warn("Suspect " + pat + " pulled via " + entryPoint);
                        }
                    }
                }
                transitivesByBomEntry.put(entryPoint, collector.allTransitives);
                allTransitives.addAll(collector.allTransitives);
            }

            checkManagedCamelArtifacts(allTransitives, originalConstrains);
            checkManagedCamelQuarkusArtifacts(t, originalConstrains);
            checkBannedDependencies(transitivesByBomEntry);

            allTransitives.addAll(t.getModulesByGa().keySet());
            return Collections.unmodifiableSet(allTransitives);
        }

        Set<Gavtcs> collectDependenciesToResolve(List<Dependency> originalConstrains, GavSet entryPoints, MavenSourceTree t) {
            final ExpressionEvaluator evaluator = t.getExpressionEvaluator(profiles);
            final Map<Ga, Module> modulesByGa = t.getModulesByGa();
            final Set<Gavtcs> result = new LinkedHashSet<>();
            final Set<String> wantedScopes = new HashSet<>(Arrays.asList("compile", "provided"));
            originalConstrains.stream()
                    .filter(dep -> entryPoints.contains(dep.getGroupId(), dep.getArtifactId(), dep.getVersion()))
                    .forEach(mvnDep -> {
                        final Ga ga = new Ga(mvnDep.getGroupId(), mvnDep.getArtifactId());
                        final Module module = modulesByGa.get(ga);
                        if (module == null) {
                            /*
                             * External artifact - if it was selected by resolutionEntryPointIncludes, then we
                             * want to have it in the entry points set
                             */
                            result.add(new Gavtcs(
                                    mvnDep.getGroupId(),
                                    mvnDep.getArtifactId(),
                                    mvnDep.getVersion(),
                                    mvnDep.getType(),
                                    mvnDep.getClassifier(),
                                    null));
                        } else {
                            /* Our own module */
                            t.collectOwnDependencies(ga, profiles).stream()
                                    .filter(dep -> wantedScopes.contains(dep.getScope()))
                                    .map(dep -> {

                                        final String groupId = evaluator.evaluate(dep.getGroupId());
                                        final String artifactId = evaluator.evaluate(dep.getArtifactId());
                                        final String type = dep.getType() == null ? "jar" : dep.getType();
                                        final String classifier = dep.getClassifier() == null ? null
                                                : evaluator.evaluate(dep.getClassifier());
                                        final String version = originalConstrains.stream()
                                                .filter(d -> groupId.equals(d.getGroupId())
                                                        && artifactId.equals(d.getArtifactId())
                                                        && compare(type, d.getType(), "jar")
                                                        && compare(classifier, d.getClassifier(), ""))
                                                .map(Dependency::getVersion)
                                                .findFirst()
                                                .orElseThrow();
                                        return new Gavtcs(
                                                groupId,
                                                artifactId,
                                                version,
                                                type,
                                                classifier, null);

                                    })
                                    .filter(dep -> toResolveDependencies.contains(dep.getGroupId(), dep.getArtifactId()))
                                    .forEach(result::add);
                        }
                    });
            return result;
        }

        void checkManagedCamelQuarkusArtifacts(MavenSourceTree t, List<Dependency> originalConstrains) {
            final Set<Ga> cqGas = t.getModulesByGa().keySet();
            final Set<Ga> managedCqGas = new TreeSet<Ga>();

            /* Check whether all org.apache.camel.quarkus:* entries managed in the BOM exist in the source tree */
            final String staleCqArtifacts = originalConstrains.stream()
                    .filter(dep -> dep.getGroupId().equals(ORG_APACHE_CAMEL_QUARKUS_GROUP_ID))
                    .peek(dep -> managedCqGas.add(new Ga(dep.getGroupId(), dep.getArtifactId())))
                    .filter(dep -> version.equals(dep.getVersion()))
                    .map(dep -> new Ga(dep.getGroupId(), dep.getArtifactId()))
                    .filter(ga -> !cqGas.contains(ga))
                    .map(Ga::toString)
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining("\n    "));
            if (!staleCqArtifacts.isEmpty()) {
                String msg = "Please remove these non-existent org.apache.camel.quarkus:* entries managed in camel-quarkus-bom:\n\n    "
                        + staleCqArtifacts
                        + "\n\n";
                reportFailure(msg);
            }

            /* Check whether all extensions have runtime artifacts managed */
            final String missingRuntimeEntries = managedCqGas.stream()
                    .filter(ga -> ga.getArtifactId().endsWith("-deployment"))
                    .map(ga -> new Ga(ga.getGroupId(), toRuntimeArtifactId(ga.getArtifactId())))
                    .filter(ga -> !managedCqGas.contains(ga))
                    .map(Ga::toString)
                    .collect(Collectors.joining("\n    "));
            if (!missingRuntimeEntries.isEmpty()) {
                String msg = "Please add these entries to camel-quarkus-bom:\n\n    "
                        + missingRuntimeEntries
                        + "\n\n";
                reportFailure(msg);
            }

            /* Check whether all extensions have deployment artifacts managed */
            final String missingDeploymentEntries = managedCqGas.stream()
                    .filter(ga -> !ga.getArtifactId().endsWith("-deployment"))
                    .map(ga -> new Ga(ga.getGroupId(), ga.getArtifactId() + "-deployment"))
                    .filter(ga -> !managedCqGas.contains(ga) && cqGas.contains(ga))
                    .map(Ga::toString)
                    .collect(Collectors.joining("\n    "));
            if (!missingDeploymentEntries.isEmpty()) {
                String msg = "Please add these entries to camel-quarkus-bom:\n\n    "
                        + missingDeploymentEntries
                        + "\n\n";
                reportFailure(msg);
            }

        }

        static String toRuntimeArtifactId(String deploymentArtifactId) {
            return deploymentArtifactId.substring(0, deploymentArtifactId.length() - "-deployment".length());
        }

        void checkManagedCamelArtifacts(Set<Ga> allTransitives, List<Dependency> originalConstrains) {
            final List<String> requiredCamelArtifacts = allTransitives.stream()
                    .filter(ga -> ga.getGroupId().equals("org.apache.camel"))
                    .map(Ga::toString)
                    .sorted()
                    .collect(Collectors.toList());

            final List<String> managedCamelArtifacts = originalConstrains.stream()
                    .filter(dep -> dep.getGroupId().equals("org.apache.camel"))
                    .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            final List<Delta<String>> diffs = DiffUtils.diff(requiredCamelArtifacts, managedCamelArtifacts).getDeltas();
            if (!diffs.isEmpty()) {
                String msg = "Too little or too much org.apache.camel:* entries in camel-quarkus-bom:\n\n    "
                        + diffs.stream().map(Delta::toString).collect(joining("\n    "))
                        + "\n\nConsider adding, removing or excluding them in the BOM\n\n";
                reportFailure(msg);
            }
        }

        public void reportFailure(String msg) {
            switch (onCheckFailure) {
            case FAIL:
                throw new RuntimeException(msg);
            case WARN:
                log.warn(msg);
                break;
            case IGNORE:
                break;
            default:
                throw new IllegalStateException("Unexpected " + OnFailure.class + " value " + onCheckFailure);
            }
        }

        void checkBannedDependencies(Map<Gavtcs, Set<Ga>> transitivesByBomEntry) {

            final org.w3c.dom.Document document;
            final Path path = rootModuleDirectory.resolve("pom.xml");
            try {
                final Transformer transformer = TransformerFactory.newInstance().newTransformer();
                final DOMResult result = new DOMResult();
                try (Reader r = Files.newBufferedReader(path, charset)) {
                    transformer.transform(new StreamSource(r), result);
                    document = (org.w3c.dom.Document) result.getNode();
                } catch (IOException e) {
                    throw new RuntimeException("Could not read " + path, e);
                } catch (TransformerException e) {
                    throw new RuntimeException("Could not parse " + path, e);
                }
            } catch (TransformerConfigurationException | TransformerFactoryConfigurationError e) {
                throw new RuntimeException(e);
            }

            final XPath xPath = XPathFactory.newInstance().newXPath();
            final String expr = "/" + PomTunerUtils.anyNs("bannedDependencies", "excludes", "exclude");
            final List<GavPattern> bannedPatterns = new ArrayList<>();
            try {
                final NodeList nodes = (NodeList) xPath.evaluate(expr, document, XPathConstants.NODESET);
                for (int i = 0; i < nodes.getLength(); i++) {
                    final Node n = nodes.item(i);
                    final String bannedPattern = n.getTextContent();
                    bannedPatterns.add(GavPattern.of(bannedPattern));
                }
            } catch (XPathExpressionException e) {
                throw new RuntimeException("Could not evaluate " + expr + " on " + path, e);
            }

            log.debug("Banned patterns " + bannedPatterns);

            final Map<Gavtcs, Set<Ga>> missingBannedDeps = new LinkedHashMap<>();
            for (Entry<Gavtcs, Set<Ga>> entry : transitivesByBomEntry.entrySet()) {
                final Set<Ga> transitives = entry.getValue();
                final Set<Ga> bannedEntryPoints = transitives.stream()
                        .flatMap(ga -> bannedPatterns.stream().filter(pat -> pat.matches(ga.getGroupId(), ga.getArtifactId())))
                        .map(gavPattern -> Ga.of(gavPattern.toString()))
                        .collect(Collectors.toCollection(TreeSet::new));
                if (!bannedEntryPoints.isEmpty()) {
                    missingBannedDeps.put(entry.getKey(), bannedEntryPoints);
                }
            }

            if (!missingBannedDeps.isEmpty()) {
                if (format) {
                    new PomTransformer(basePath.resolve("pom.xml"), charset, simpleElementWhitespace)
                            .transform((org.w3c.dom.Document doc, TransformationContext context) -> {
                                final Set<NodeGavtcs> deps = context.getManagedDependencies();
                                missingBannedDeps.forEach((gavtcs, missingExclusions) -> {
                                    deps.stream()
                                            .filter(dep -> dep.toGa().equals(gavtcs.toGa()))
                                            .forEach(dep -> {
                                                final ContainerElement exclusionsNode = dep.getNode()
                                                        .getOrAddChildContainerElement("exclusions");
                                                missingExclusions.forEach(
                                                        missingExclusion -> addExclusion(exclusionsNode, missingExclusion));
                                            });
                                });
                            });
                }

                final StringBuilder msg = new StringBuilder("Missing exclusions in ")
                        .append(effectivePomModel.getArtifactId())
                        .append(":\n");
                missingBannedDeps
                        .forEach((gavtcs, bannedEntryPoints) -> msg
                                .append("\n    ")
                                .append(gavtcs)
                                .append(" pulls banned dependencies ")
                                .append(bannedEntryPoints));
                throw new RuntimeException(msg.append(
                        "\n\nYou may want to consider running\n\n    mvn process-resources -Dcq.flatten-bom.format\n\nto fix the named issues in this BOM")
                        .toString());
            }

        }

    }

    private static class InputLocationStringFormatter
            extends InputLocation.StringFormatter {

        private final String versionSuffix;

        public InputLocationStringFormatter(String version) {
            this.versionSuffix = ":" + version;
        }

        private static final String GAV_PREFIX = ORG_APACHE_CAMEL_QUARKUS_GROUP_ID + ":";

        public String toString(InputLocation location) {
            InputSource source = location.getSource();

            String s = source.getModelId(); // by default, display modelId

            if (StringUtils.isBlank(s) || s.contains("[unknown-version]")) {
                // unless it is blank or does not provide version information
                s = source.toString();
            }

            if (s.startsWith(GAV_PREFIX)) {
                s = s.replace(versionSuffix, ":${project.version}");
            }

            return "#} " + s + " ";
        }

    }

    static class DependencyCollector implements DependencyVisitor {
        private final Set<Ga> allTransitives = new TreeSet<>();
        private final GavSet excludes;

        public DependencyCollector(GavSet excludes) {
            this.excludes = excludes;
        }

        @Override
        public boolean visitLeave(DependencyNode node) {
            return true;
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            final Artifact a = node.getArtifact();
            if (!excludes.contains(a.getGroupId(), a.getArtifactId())) {
                final Ga gav = new Ga(a.getGroupId(), a.getArtifactId());
                allTransitives.add(gav);
            }
            return true;
        }

    }

    public static class BomEntryTransformation {
        private GavPattern gavPattern;
        private List<Exclusion> addExclusions = new ArrayList<>();
        private Pattern versionPattern;
        private String versionReplace;

        public BomEntryTransformation() {
        }

        public BomEntryTransformation(String gavPattern, String versionReplacement, String exclusions, String addExclusions) {
            if (gavPattern != null) {
                setGavPattern(gavPattern);
            }
            if (versionReplacement != null) {
                setVersionReplacement(versionReplacement);
            }
            if (exclusions != null) {
                setExclusions(exclusions);
            }
            if (addExclusions != null) {
                setAddExclusions(addExclusions);
            }
        }

        public List<Exclusion> getAddExclusions() {
            return addExclusions;
        }

        public void setAddExclusions(String exclusions) {
            for (String rawExcl : exclusions.split("[,\\s]+")) {
                final String[] parts = rawExcl.split(":");
                final Exclusion excl = new Exclusion();
                excl.setGroupId(parts[0]);
                excl.setArtifactId(parts[1]);
                this.addExclusions.add(excl);
            }
        }

        /**
         * An alias for {@link #setAddExclusions(String)}
         *
         * @param      exclusions items to exclude
         * @deprecated            use {@link #setAddExclusions(String)}
         */
        public void setExclusions(String exclusions) {
            setAddExclusions(exclusions);
        }

        public GavPattern getGavPattern() {
            return gavPattern;
        }

        public void setGavPattern(String gavPattern) {
            this.gavPattern = GavPattern.of(gavPattern);
        }

        public String replaceVersion(String version) {
            return versionPattern == null ? version : versionPattern.matcher(version).replaceAll(versionReplace);
        }

        public void setVersionReplacement(String versionReplacement) {
            final int slashPos = versionReplacement.indexOf('/');
            if (slashPos < 1) {
                throw new IllegalStateException(
                        "versionReplacement is expected to contain exactly one slash (/); found " + versionReplacement);
            }
            this.versionPattern = Pattern.compile(versionReplacement.substring(0, slashPos));
            this.versionReplace = versionReplacement.substring(slashPos + 1);
        }
    }

}
