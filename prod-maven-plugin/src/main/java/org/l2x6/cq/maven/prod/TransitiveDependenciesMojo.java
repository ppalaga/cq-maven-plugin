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
package org.l2x6.cq.maven.prod;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.maven.prod.ProdExcludesMojo.CamelEdition;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavSet;
import org.w3c.dom.Document;

/**
 * List the transitive dependencies of all, of supported extensions and the rest that neither needs to get productized
 * nor aligned by PNC.
 *
 * @since 2.17.0
 */
public class TransitiveDependenciesMojo {

    /**
     * The version of the current source tree
     *
     * @since 2.17.0
     */
    private final String version;

    /**
     * Camel Quarkus community version
     *
     * @since 2.17.0
     */
    private final String camelQuarkusCommunityVersion;

    /**
     * The basedir
     *
     * @since 2.17.0
     */
    private final Path basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.17.0
     */
    private final Charset charset;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    private final Path productizedDependenciesFile;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    private final Path allDependenciesFile;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    private final Path nonProductizedDependenciesFile;

    /**
     * A map from Camel Quarkus artifactIds to comma separated list of {@code groupId:artifactId} patterns.
     * Used for assigning shaded dependencies to a Camel Quarkus artifact when deciding whether the given transitive
     * needs
     * to get productized.
     *
     * @since 2.18.0
     */
    private final Map<String, GavSet> additionalExtensionDependencies;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.23.0
     */
    private final SimpleElementWhitespace simpleElementWhitespace;

    private final List<RemoteRepository> repositories;

    private final RepositorySystem repoSystem;

    private final RepositorySystemSession repoSession;

    private final Log log;

    private final Runnable bomInstaller;

    private final Path jakartaReportFile;

    public TransitiveDependenciesMojo(
            String version, String camelQuarkusCommunityVersion,
            Path basedir, Charset charset,
            Path productizedDependenciesFile, Path allDependenciesFile, Path nonProductizedDependenciesFile,
            Map<String, GavSet> additionalExtensionDependencies, SimpleElementWhitespace simpleElementWhitespace,
            List<RemoteRepository> repositories, RepositorySystem repoSystem,
            RepositorySystemSession repoSession,
            Log log,
            Runnable bomInstaller, Path jakartaReportFile) {
        this.version = version;
        this.camelQuarkusCommunityVersion = camelQuarkusCommunityVersion;
        this.basedir = basedir;
        this.charset = charset;
        this.productizedDependenciesFile = basedir.resolve(productizedDependenciesFile);
        this.allDependenciesFile = basedir.resolve(allDependenciesFile);
        this.nonProductizedDependenciesFile = basedir.resolve(nonProductizedDependenciesFile);
        this.additionalExtensionDependencies = additionalExtensionDependencies;
        this.simpleElementWhitespace = simpleElementWhitespace;
        this.repositories = repositories;
        this.repoSystem = repoSystem;
        this.repoSession = repoSession;
        this.log = log;
        this.bomInstaller = bomInstaller;
        this.jakartaReportFile = jakartaReportFile;
    }

    public void execute() {

        final Model bomModel = CqCommonUtils.readPom(basedir.resolve("poms/bom/pom.xml"), charset);

        final Set<Ga> ownManagedGas = new TreeSet<>();
        final Map<String, Set<Ga>> bomGroups = new TreeMap<>();
        final Map<String, Boolean> cqArtifactIds = collectArtifactIds(bomModel, ownManagedGas, bomGroups);

        /*
         * Set Camel dependency versions and install the BOM so that we get correct transitives via
         * DependencyCollector
         */
        final CamelDependencyCollector camelCollector = new CamelDependencyCollector();
        collect(cqArtifactIds, camelCollector, Collections.emptyList());
        updateCamelQuarkusBom(camelCollector.camelProdDeps);
        log.info("Installing camel-quarkus-bom again, now with proper Camel constraints");
        bomInstaller.run();

        final BiConsumer<Artifact, Deque<Gav>> jakartaConsumer = jakartaReportFile != null ? this::analyzeJakarta
                : ((Artifact a, Deque<Gav> stack) -> {
                });
        final DependencyCollector collector = new DependencyCollector(jakartaConsumer);
        collect(cqArtifactIds, collector, readConstraints());
        if (jakartaReportFile != null) {
            try {
                Files.createDirectories(jakartaReportFile.getParent());
                Files.writeString(jakartaReportFile, jakartaReport.stream().collect(Collectors.joining("\n\n")),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Could not write to " + jakartaReportFile, e);
            }
        }

        final Set<Ga> allTransitiveGas = toGas(collector.allTransitives);
        final Set<Ga> prodTransitiveGas = toGas(collector.prodTransitives);
        bomModel.getDependencyManagement().getDependencies().stream()
                .filter(dep -> !"import".equals(dep.getScope()))
                .forEach(dep -> {
                    final Ga depGa = new Ga(dep.getGroupId(), dep.getArtifactId());
                    additionalExtensionDependencies.entrySet().stream()
                            .filter(en -> en.getValue().contains(dep.getGroupId(), dep.getArtifactId(), dep.getVersion()))
                            .map(Entry::getKey) // artifactId
                            .findFirst()
                            .ifPresent(artifactId -> {
                                final Ga extensionGa = new Ga("org.apache.camel.quarkus", artifactId);
                                if (prodTransitiveGas.contains(extensionGa)) {
                                    prodTransitiveGas.add(depGa);
                                    allTransitiveGas.add(depGa);
                                } else if (allTransitiveGas.contains(extensionGa)) {
                                    allTransitiveGas.add(depGa);
                                }
                            });
                });

        final Map<Ga, Set<ComparableVersion>> multiversionedProdArtifacts = findMultiversionedArtifacts(
                collector.prodTransitives);
        if (!multiversionedProdArtifacts.isEmpty()) {
            log.warn("Found dependencies of productized artifacts with multiple versions:");
            multiversionedProdArtifacts.entrySet().forEach(en -> {
                log.warn("- " + en.getKey() + ": " + en.getValue());
            });
        }

        /* Ensure that all camel deps are managed */
        final Set<Ga> nonManagedCamelArtifacts = allTransitiveGas.stream()
                .filter(ga -> "org.apache.camel".equals(ga.getGroupId()))
                .filter(ga -> !ownManagedGas.contains(ga))
                .collect(Collectors.toCollection(TreeSet::new));
        final StringBuilder sb = new StringBuilder(
                "Found non-managed Camel artifacts; consider adding the following to camel-quarkus-bom:");
        if (!nonManagedCamelArtifacts.isEmpty()) {
            nonManagedCamelArtifacts.forEach(ga -> sb.append("\n            <dependency>\n                <groupId>")
                    .append(ga.getGroupId())
                    .append("</groupId>\n                <artifactId>")
                    .append(ga.getArtifactId())
                    .append("</artifactId>\n                <version>")
                    .append(prodTransitiveGas.contains(ga) ? "${camel.version}" : "${camel-community.version}")
                    .append("</version>\n            </dependency>"));
            throw new RuntimeException(sb.toString());
        }

        /*
         * For the sake of consistency in end user apps, we manage some artifacts that are not actually used in our
         * extensions. We need to classify these as prod/non-prod too so that PME does not change the versions were we
         * do not want
         */
        bomModel.getDependencyManagement().getDependencies().stream()
                .filter(dep -> !"import".equals(dep.getScope()))
                .filter(dep -> !allTransitiveGas.contains(new Ga(dep.getGroupId(), dep.getArtifactId())))
                .forEach(dep -> {
                    final Ga depGa = new Ga(dep.getGroupId(), dep.getArtifactId());
                    final Set<Ga> gaSet = bomGroups.get(dep.getVersion());
                    if (prodTransitiveGas.stream().anyMatch(gaSet::contains)) {
                        prodTransitiveGas.add(depGa);
                        log.debug("   - BOM entry mappable to an otherwise productized group: " + depGa);
                    } else if (allTransitiveGas.stream().anyMatch(gaSet::contains)) {
                        /* Still mappable */
                        log.debug("   - BOM entry mappable to an otherwise non-productized group: " + depGa);
                    } else {
                        log.warn(" - BOM entry not mappable to any group: " + depGa
                                + " - is it perhaps supperfluous and should be removed from the BOM? Or needs to get assigne to an extension via <additionalExtensionDependencies>?");
                    }
                    allTransitiveGas.add(depGa);
                });

        write(allTransitiveGas, allDependenciesFile);
        write(prodTransitiveGas, productizedDependenciesFile);
        final Set<Ga> nonProdTransitives = allTransitiveGas.stream()
                .filter(dep -> !prodTransitiveGas.contains(dep))
                .collect(Collectors.toCollection(TreeSet::new));
        write(nonProdTransitives, nonProductizedDependenciesFile);

        log.info("Installing the final version of camel-quarkus-bom again, now with fine grained prod & non-prod versions");
        bomInstaller.run();

    }

    private List<Dependency> readConstraints() {
        final Path path = CqCommonUtils.resolveArtifact(
                repoSession.getLocalRepository().getBasedir().toPath(),
                "org.apache.camel.quarkus", "camel-quarkus-bom", version, "pom",
                repositories, repoSystem, repoSession);
        final Model bomModel = CqCommonUtils.readPom(path, StandardCharsets.UTF_8);

        return bomModel.getDependencyManagement().getDependencies().stream()
                .map(dep -> {
                    return new Dependency(
                            new DefaultArtifact(
                                    dep.getGroupId(),
                                    dep.getArtifactId(),
                                    dep.getType(),
                                    dep.getVersion()),
                            null,
                            false,
                            dep.getExclusions() == null
                                    ? Collections.emptyList()
                                    : dep.getExclusions().stream()
                                            .map(e -> new org.eclipse.aether.graph.Exclusion(e.getGroupId(),
                                                    e.getArtifactId(),
                                                    null, null))
                                            .collect(Collectors.toList()));
                })
                .collect(Collectors.toList());
    }

    static Map<String, Boolean> collectArtifactIds(ModelBase bomModel, Set<Ga> ownManagedGas, Map<String, Set<Ga>> bomGroups) {
        TreeMap<String, Boolean> cqArtifactIds = new TreeMap<>();
        bomModel.getDependencyManagement().getDependencies().stream()
                .peek(dep -> {
                    if (!"import".equals(dep.getScope())) {
                        final Ga ga = new Ga(dep.getGroupId(), dep.getArtifactId());
                        ownManagedGas.add(ga);
                        bomGroups.computeIfAbsent(dep.getVersion(), k -> new TreeSet<>()).add(ga);
                    }
                })
                .filter(dep -> dep.getGroupId().equals("org.apache.camel.quarkus"))
                .forEach(dep -> {
                    switch (dep.getVersion()) {
                    case "${camel-quarkus.version}":
                        cqArtifactIds.put(dep.getArtifactId(), true);
                        break;
                    case "${camel-quarkus-community.version}":
                        cqArtifactIds.put(dep.getArtifactId(), false);
                        break;
                    default:
                        throw new IllegalStateException(
                                "Unexpected version of an artifact with groupId 'org.apache.camel.quarkus': " + dep.getVersion()
                                        + "; expected ${camel-quarkus.version} or ${camel-quarkus-community.version}");
                    }

                });
        /*
         * Remove the runtime artifacts from the set, because their -deployment counterparts will pull the runtime deps
         * anyway
         */
        for (Iterator<String> it = cqArtifactIds.keySet().iterator(); it.hasNext();) {
            final String artifactId = it.next();
            if (!artifactId.endsWith("-deployment") && cqArtifactIds.containsKey(artifactId + "-deployment")) {
                it.remove();
            }
        }

        return Collections.unmodifiableMap(cqArtifactIds);
    }

    void collect(Map<String, Boolean> cqArtifactIds, ProdDependencyCollector collector, List<Dependency> constraints) {
        cqArtifactIds.entrySet().stream()
                .forEach(artifactId -> {

                    final Boolean isProd = artifactId.getValue();
                    final DefaultArtifact artifact = new DefaultArtifact(
                            "org.apache.camel.quarkus",
                            artifactId.getKey(),
                            null,
                            "pom",
                            isProd ? version : camelQuarkusCommunityVersion);
                    final CollectRequest request = new CollectRequest()
                            .setRepositories(repositories)
                            .setRoot(new org.eclipse.aether.graph.Dependency(artifact, null))
                            .setManagedDependencies(constraints);
                    try {
                        final DependencyNode rootNode = repoSystem
                                .collectDependencies(repoSession, request)
                                .getRoot();
                        collector.isProd = isProd;
                        rootNode.accept(collector);
                    } catch (DependencyCollectionException e) {
                        throw new RuntimeException("Could not resolve dependencies", e);
                    }
                });

    }

    void updateCamelQuarkusBom(Set<Ga> prodCamelGas) {

        final Path bomPath = basedir.resolve("poms/bom/pom.xml");
        log.info("Updating Camel versions in " + bomPath);
        new PomTransformer(bomPath, charset, simpleElementWhitespace)
                .transform((Document document, TransformationContext context) -> {

                    context.getContainerElement("project", "dependencyManagement", "dependencies").get()
                            .childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(gavtcs -> gavtcs.getGroupId().equals("org.apache.camel"))
                            .forEach(gavtcs -> {
                                final Ga ga = new Ga(gavtcs.getGroupId(), gavtcs.getArtifactId());
                                final String expectedVersion = prodCamelGas.contains(ga)
                                        ? CamelEdition.PRODUCT.getVersionExpression()
                                        : CamelEdition.COMMUNITY.getVersionExpression();
                                if (!expectedVersion.equals(gavtcs.getVersion())) {
                                    gavtcs.getNode().setVersion(expectedVersion);
                                }
                            });
                });
    }

    static Set<Ga> toGas(Set<Gav> gavs) {
        return gavs.stream()
                .map(Gav::toGa)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static Map<Ga, Set<ComparableVersion>> findMultiversionedArtifacts(Set<Gav> prodTransitives) {
        Map<Ga, Set<ComparableVersion>> result = new TreeMap<>();
        prodTransitives.stream()
                .forEach(gav -> {
                    final Ga key = gav.toGa();
                    Set<ComparableVersion> versions = result.computeIfAbsent(key, k -> new TreeSet<>());
                    versions.add(new ComparableVersion(gav.getVersion()));
                });
        for (Iterator<Map.Entry<Ga, Set<ComparableVersion>>> it = result.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<Ga, Set<ComparableVersion>> en = it.next();
            if (en.getValue().size() <= 1) {
                it.remove();
            }
        }
        return result;
    }

    void write(Set<Ga> deps, Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(
                    path,
                    (deps
                            .stream()
                            .map(Ga::toString)
                            .collect(Collectors.joining("\n")) + "\n").getBytes(charset));
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + path, e);
        }
    }

    private final Map<String, Boolean> jarToJavax = new HashMap<>();
    private final Set<String> jakartaReport = new TreeSet<>();

    private void analyzeJakarta(Artifact artifact, Deque<Gav> stack) {
        final String groupId = artifact.getGroupId();
        if (!groupId.startsWith("org.apache.camel") && !groupId.startsWith("io.quarkus")) {
            if (stack.stream().anyMatch(gav -> "org.apache.camel.quarkus".equals(gav.getGroupId()))
                    && stack.stream().noneMatch(gav -> "io.quarkus".equals(gav.getGroupId()))
                    && stack.stream().noneMatch(gav -> "org.apache.camel".equals(gav.getGroupId()))) {
                /* We are interested only in transitives coming via Camel */
                File file = artifact.getFile();
                if (file == null) {
                    final ArtifactRequest req = new ArtifactRequest().setRepositories(this.repositories).setArtifact(artifact);
                    try {
                        final ArtifactResult resolutionResult = this.repoSystem.resolveArtifact(this.repoSession, req);
                        file = resolutionResult.getArtifact().getFile();
                    } catch (ArtifactResolutionException e) {
                        throw new RuntimeException("Could not resolve " + artifact, e);
                    }
                }
                if (file != null && file.getName().endsWith(".jar") && containsEnryStartingWith(file, "javax/")) {
                    /* Find the last CQ item */
                    final List<Gav> path = new ArrayList<>();
                    for (Iterator<Gav> i = stack.descendingIterator(); i.hasNext();) {
                        final Gav gav = i.next();
                        if (gav.getGroupId().equals("org.apache.camel.quarkus")) {
                            path.clear();
                            /*
                             * keep just the last CQ element of the path
                             * We'll thus reduce some uninteresting duplications in the report
                             */
                        }
                        path.add(gav);
                    }
                    jakartaReport.add(path.stream().map(Gav::toString).collect(Collectors.joining("\n    -> ")));
                }
            }
        }
    }

    private boolean containsEnryStartingWith(File file, String prefix) {
        final String absolutePath = file.getAbsolutePath();
        final Boolean knownToHaveJavax = jarToJavax.get(absolutePath);
        if (knownToHaveJavax != null) {
            return knownToHaveJavax.booleanValue();
        }

        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file), StandardCharsets.UTF_8)) {
            ZipEntry entry = null;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith(prefix)) {
                    jarToJavax.put(absolutePath, true);
                    return true;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + file, e);
        }
        jarToJavax.put(absolutePath, false);
        return false;
    }

    static abstract class ProdDependencyCollector implements DependencyVisitor {
        protected boolean isProd;
    }

    static class CamelDependencyCollector extends ProdDependencyCollector {

        private final Set<Ga> camelProdDeps = new TreeSet<>();

        @Override
        public boolean visitLeave(DependencyNode node) {
            return true;
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            final Artifact a = node.getArtifact();
            if (isProd && a.getGroupId().equals("org.apache.camel")) {
                camelProdDeps.add(new Ga(a.getGroupId(), a.getArtifactId()));
            }
            return true;
        }

    }

    static class DependencyCollector extends ProdDependencyCollector {
        private final Set<Gav> prodTransitives = new TreeSet<>();
        private final Set<Gav> allTransitives = new TreeSet<>();
        private final Deque<Gav> stack = new ArrayDeque<>();
        private final BiConsumer<Artifact, Deque<Gav>> prodArtifactConsumer;

        public DependencyCollector(BiConsumer<Artifact, Deque<Gav>> prodArtifactConsumer) {
            this.prodArtifactConsumer = prodArtifactConsumer;
        }

        @Override
        public boolean visitLeave(DependencyNode node) {
            stack.pop();
            return true;
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            final Artifact a = node.getArtifact();
            final Gav gav = new Gav(a.getGroupId(), a.getArtifactId(), a.getVersion());
            stack.push(gav);
            allTransitives.add(gav);
            if (isProd) {
                prodTransitives.add(gav);
                prodArtifactConsumer.accept(a, stack);
            }
            return true;
        }

    }
}
