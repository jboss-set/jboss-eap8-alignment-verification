package org.jboss.set.alignment_verification;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.io.PomIO;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.wagon.WagonTransporterFactory;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.maven.VersionResolverFactory;

/**
 * This mojo generates a dependency tree for all projects submodules and verifies that all discovered dependencies
 * are aligned to versions defined in provided channel.
 */
@Mojo(name = "verify", requiresProject = true, requiresDirectInvocation = true)
public class VerifyMojo extends AbstractMojo {

    private static final String EAP_GROUPID = "org.jboss.eap";
    private static final String EAP_PARENT_ARTIFACTID = "jboss-eap-parent";
    private static final String LEGACY_BOM_ARTIFACTID = "wildfly-legacy-ee-bom";
    /**
     * Path to the channel definition file on a local filesystem.
     * <p>
     * Alternative for the `channelGAV` parameter.
     */
    @Parameter(required = false, property = "channelFile")
    String channelFile;

    /**
     * Comma separated list of module G:As that should not be processed.
     */
    @Parameter(property = "ignoreModules", defaultValue = "")
    List<String> ignoreModules;

    @Inject
    MavenSession mavenSession;

    @Inject
    MavenProject mavenProject;

    @Inject
    PomIO pomIO;

    // @Component(hint = "default")
    @Inject
    DependencyCollectorBuilder dependencyCollectorBuilder;

    @Inject
    ManipulationSession manipulationSession;

    private final List<ProjectRef> ignoredModules = new ArrayList<>();
    private List<Project> pmeProjects;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!mavenSession.getCurrentProject().isExecutionRoot()) {
            // do not perform any work in submodules
            return;
        }

        ignoreModules.forEach(ga -> ignoredModules.add(SimpleProjectRef.parse(ga)));
        List<SimpleProjectRef> projectModules = mavenProject.getCollectedProjects().stream()
                .map(p -> new SimpleProjectRef(p.getGroupId(), p.getArtifactId()))
                .collect(Collectors.toList());

        ChannelSession channelSession = createChannelSession();

        final Map<MavenProject, List<Pair<Artifact, MavenArtifact>>> unalignedArtifacts = new HashMap<>();

        try {
            pmeProjects = pomIO.parseProject(mavenProject.getModel().getPomFile());

            for (MavenProject module : mavenProject.getCollectedProjects()) {
                SimpleProjectRef moduleProjectRef = new SimpleProjectRef(module.getGroupId(), module.getArtifactId());
                if (ignoredModules.contains(moduleProjectRef)) {
                    getLog().info(String.format("Skipping module %s:%s (ignored)",
                            module.getGroupId(), module.getArtifactId()));
                    continue;
                }
                if (isLegacyBom(module)) {
                    getLog().info(String.format("Skipping module %s:%s (is legacy BOM)",
                            module.getGroupId(), module.getArtifactId()));
                    continue;
                }
                if (dependsOnLegacyBom(module)) {
                    getLog().info(String.format("Skipping module %s:%s (uses legacy BOM)",
                            module.getGroupId(), module.getArtifactId()));
                    continue;
                }
                getLog().info(String.format("Processing module %s:%s", module.getGroupId(), module.getArtifactId()));

                ProjectBuildingRequest buildingRequest =
                        new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());
                buildingRequest.setProject(module);
                DependencyNode rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, null);
                CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
                rootNode.accept(visitor);
                visitor.getNodes().forEach(node -> {
                    Artifact artifact = node.getArtifact();
                    SimpleProjectRef artifactAsProjectRef = new SimpleProjectRef(artifact.getGroupId(),
                            artifact.getArtifactId());
                    if (projectModules.contains(artifactAsProjectRef)) {
                        // do not compare dependencies to project submodules, these are expected to have different
                        // versions
                        return;
                    }

                    try {
                        MavenArtifact channelArtifact = channelSession.resolveMavenArtifact(artifact.getGroupId(),
                                artifact.getArtifactId(), artifact.getType(), artifact.getClassifier(),
                                artifact.getVersion());
                        if (!artifact.getVersion().equals(channelArtifact.getVersion())) {
                            List<Pair<Artifact, MavenArtifact>> unalignedArtifactsForModule =
                                    unalignedArtifacts.computeIfAbsent(module, m -> new ArrayList<>());
                            unalignedArtifactsForModule.add(Pair.of(artifact, channelArtifact));
                        }
                    } catch (UnresolvedMavenArtifactException ignored) {
                        // artifact not managed by the channel
                    }
                });
            }

            if (!unalignedArtifacts.isEmpty()) {
                unalignedArtifacts.forEach((module, list) -> {
                    list.forEach(pair -> {
                        Artifact a = pair.getLeft();
                        MavenArtifact channelArtifact = pair.getRight();

                        getLog().error(String.format("Dependency %s:%s:%s on module %s:%s is not aligned to version %s",
                                a.getGroupId(), a.getArtifactId(), a.getVersion(),
                                module.getGroupId(), module.getArtifactId(),
                                channelArtifact.getVersion()));
                    });
                });
                throw new MojoFailureException("Some artifacts were not aligned. See above logs for details.");
            }
        } catch (DependencyCollectorBuilderException e) {
            throw new MojoExecutionException("Can't collect dependency graph", e);
        } catch (ManipulationException e) {
            throw new MojoExecutionException("Can't parse project structure to PME representation", e);
        }
    }

    private boolean isLegacyBom(MavenProject module) {
        return EAP_GROUPID.equals(module.getGroupId()) && LEGACY_BOM_ARTIFACTID.equals(module.getArtifactId());
    }

    private boolean dependsOnLegacyBom(MavenProject module) throws MojoExecutionException {
        // EAP parent POM is considered non-legacy, this also stops the recursion that follows parents
        if (EAP_GROUPID.equals(module.getGroupId()) && EAP_PARENT_ARTIFACTID.equals(module.getArtifactId())) {
            return false;
        }

        // check if the POM or its parent has legacy BOM in managed dependencies
        try {
            Optional<Project> pmeProject = pmeProjects.stream()
                    .filter(p -> p.getGroupId().equals(module.getGroupId())
                            && p.getArtifactId().equals(module.getArtifactId()))
                    .findFirst();
            if (pmeProject.isEmpty()) {
                throw new MojoExecutionException(String.format("Couldn't locate PME project for module %s:%s",
                        module.getGroupId(), module.getArtifactId()));
            }
            Map<ArtifactRef, Dependency> managedDependencies =
                    pmeProject.get().getResolvedManagedDependencies(manipulationSession);
            boolean dependsOnLegacyBom = managedDependencies.entrySet().stream()
                    .anyMatch(e -> EAP_GROUPID.equals(e.getKey().getGroupId())
                            && LEGACY_BOM_ARTIFACTID.equals(e.getKey().getArtifactId())
                            && "import".equals(e.getValue().getScope()));
            return dependsOnLegacyBom || dependsOnLegacyBom(module.getParent());
        } catch (ManipulationException e) {
            throw new MojoExecutionException("Failed to resolve dependencies", e);
        }
    }

    private ChannelSession createChannelSession() throws MojoExecutionException {
        try {
            Path channelFilePath = Path.of(channelFile);
            if (!channelFilePath.isAbsolute()) {
                channelFilePath = Path.of(mavenSession.getExecutionRootDirectory()).resolve(channelFilePath);
            }
            getLog().info("Reading channel file " + channelFilePath);
            Channel channel = ChannelMapper.from(channelFilePath.toUri().toURL());

            final DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
            locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
            locator.addService(TransporterFactory.class, WagonTransporterFactory.class);
            locator.addService(TransporterFactory.class, FileTransporterFactory.class);
            locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
                @Override
                public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                    throw new RuntimeException("Failed to initiate maven repository system");
                }
            });
            RepositorySystem repositorySystem = locator.getService(RepositorySystem.class);

            return new ChannelSession(Collections.singletonList(channel),
                    new VersionResolverFactory(repositorySystem, mavenSession.getRepositorySession(),
                            Collections.emptyList()));
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Could not read channelFile", e);
        }
    }

}
