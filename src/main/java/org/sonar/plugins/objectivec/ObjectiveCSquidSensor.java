/*
 * Sonar Objective-C Plugin
 * Copyright (C) 2012 OCTO Technology, Backelite
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.objectivec;

import java.util.Collection;
import java.util.Locale;

import com.google.common.collect.ImmutableList;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.rule.Checks;
import org.sonar.api.checks.AnnotationCheckFactory;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.issue.Issuable;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.PersistenceMode;
import org.sonar.api.measures.RangeDistributionBuilder;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.File;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.Violation;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.objectivec.ObjectiveCAstScanner;
import org.sonar.objectivec.ObjectiveCConfiguration;
import org.sonar.objectivec.api.ObjectiveCGrammar;
import org.sonar.objectivec.api.ObjectiveCMetric;
import org.sonar.objectivec.checks.CheckList;
import org.sonar.plugins.objectivec.core.ObjectiveC;
import org.sonar.squidbridge.AstScanner;
import org.sonar.squidbridge.api.CheckMessage;
import org.sonar.squidbridge.api.SourceCode;
import org.sonar.squidbridge.api.SourceFile;
import org.sonar.squidbridge.api.SourceFunction;
import org.sonar.squidbridge.checks.SquidCheck;
import org.sonar.squidbridge.indexer.QueryByParent;
import org.sonar.squidbridge.indexer.QueryByType;


public class ObjectiveCSquidSensor implements Sensor {

    private final Number[] FUNCTIONS_DISTRIB_BOTTOM_LIMITS = {1, 2, 4, 6, 8, 10, 12, 20, 30};
    private final Number[] FILES_DISTRIB_BOTTOM_LIMITS = {0, 5, 10, 20, 30, 60, 90};

    private final AnnotationCheckFactory annotationCheckFactory;
    private final FileSystem fileSystem;
    private final PathResolver pathResolver;
    private final ResourcePerspectives resourcePerspectives;
    private final Checks<SquidCheck<ObjectiveCGrammar>> checks;
    private final FilePredicate mainFilePredicates;


    private Project project;
    private SensorContext context;
    private AstScanner<ObjectiveCGrammar> scanner;

    public ObjectiveCSquidSensor(RulesProfile profile, FileSystem fileSystem, PathResolver pathResolver, ResourcePerspectives resourcePerspectives, CheckFactory checkFactory) {
        this.annotationCheckFactory = AnnotationCheckFactory.create(profile, CheckList.REPOSITORY_KEY, CheckList.getChecks());
        this.fileSystem = fileSystem;
        this.pathResolver = pathResolver;
        this.resourcePerspectives = resourcePerspectives;
        this.checks = checkFactory.<SquidCheck<ObjectiveCGrammar>>create(CheckList.REPOSITORY_KEY).addAnnotatedChecks(CheckList.getChecks());
        this.mainFilePredicates = fileSystem.predicates().and(fileSystem.predicates().hasLanguage(ObjectiveC.KEY), fileSystem.predicates().hasType(InputFile.Type.MAIN));
    }

    public boolean shouldExecuteOnProject(Project project) {

        return project.isRoot() && fileSystem.hasFiles(fileSystem.predicates().hasLanguage(ObjectiveC.KEY));

    }

    public void analyse(Project project, SensorContext context) {
        this.project = project;
        this.context = context;

        Collection<SquidCheck> squidChecks = annotationCheckFactory.getChecks();
        this.scanner = ObjectiveCAstScanner.create(createConfiguration(), squidChecks.toArray(new SquidCheck[squidChecks.size()]));
        scanner.scanFiles(ImmutableList.copyOf(fileSystem.files(mainFilePredicates)));

        Collection<SourceCode> squidSourceFiles = scanner.getIndex().search(new QueryByType(SourceFile.class));
        save(squidSourceFiles);
    }

    private ObjectiveCConfiguration createConfiguration() {

        return new ObjectiveCConfiguration(fileSystem.encoding());
    }

    private void save(Collection<SourceCode> squidSourceFiles) {

        for (SourceCode squidSourceFile : squidSourceFiles) {
            SourceFile squidFile = (SourceFile) squidSourceFile;

            String relativePath = pathResolver.relativePath(fileSystem.baseDir(), new java.io.File(squidFile.getKey()));
            InputFile inputFile = fileSystem.inputFile(fileSystem.predicates().hasRelativePath(relativePath));

            saveMeasures(inputFile, squidFile);
            saveIssues(inputFile, squidFile);
        }
    }

    private void saveMeasures(InputFile inputFile, SourceFile squidFile) {
        context.saveMeasure(inputFile, CoreMetrics.FILES, squidFile.getDouble(ObjectiveCMetric.FILES));
        context.saveMeasure(inputFile, CoreMetrics.LINES, squidFile.getDouble(ObjectiveCMetric.LINES));
        context.saveMeasure(inputFile, CoreMetrics.NCLOC, squidFile.getDouble(ObjectiveCMetric.LINES_OF_CODE));
        context.saveMeasure(inputFile, CoreMetrics.STATEMENTS, squidFile.getDouble(ObjectiveCMetric.STATEMENTS));
        context.saveMeasure(inputFile, CoreMetrics.COMMENT_LINES, squidFile.getDouble(ObjectiveCMetric.COMMENT_LINES));
    }

    private void saveIssues(InputFile inputFile, SourceFile squidFile) {

        Collection<CheckMessage> messages = squidFile.getCheckMessages();

        Resource resource = context.getResource(org.sonar.api.resources.File.fromIOFile(inputFile.file(), project));

        if (messages != null && resource != null) {
            for (CheckMessage message : messages) {
                RuleKey ruleKey = checks.ruleKey((SquidCheck<ObjectiveCGrammar>) message.getCheck());
                Issuable issuable = resourcePerspectives.as(Issuable.class, resource);

                if (issuable != null) {
                    Issuable.IssueBuilder issueBuilder = issuable.newIssueBuilder()
                            .ruleKey(ruleKey)
                            .line(message.getLine())
                            .message(message.getText(Locale.ENGLISH));

                    if (message.getCost() != null) {
                        issueBuilder.effortToFix(message.getCost());
                    }

                    issuable.addIssue(issueBuilder.build());
                }

            }
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

}
