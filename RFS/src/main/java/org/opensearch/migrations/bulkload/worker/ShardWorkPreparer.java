package org.opensearch.migrations.bulkload.worker;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.FilterScheme;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import org.opensearch.migrations.reindexer.tracing.IRootDocumentMigrationContext;

import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * This class adds workitemes (leasable mutexes) via the WorkCoordinator so that future
 * runs of the DocumentsRunner can pick one of those items and migrate the documents for
 * that section of work.
 */
@Slf4j
public class ShardWorkPreparer {

    public static final String SHARD_SETUP_WORK_ITEM_ID = "shard_setup";

    public void run(
        ScopedWorkCoordinator scopedWorkCoordinator,
        IndexMetadata.Factory metadataFactory,
        String snapshotName,
        List<String> indexAllowlist,
        IRootDocumentMigrationContext rootContext
    ) throws IOException, InterruptedException {
        // ensure that there IS an index to house the shared state that we're going to be manipulating
        scopedWorkCoordinator.workCoordinator.setup(
            rootContext.getWorkCoordinationContext()::createCoordinationInitializationStateContext
        );

        try (var context = rootContext.createDocsMigrationSetupContext()) {
            setupShardWorkItems(scopedWorkCoordinator, metadataFactory, snapshotName, indexAllowlist, context);
        }
    }

    private void setupShardWorkItems(
        ScopedWorkCoordinator scopedWorkCoordinator,
        IndexMetadata.Factory metadataFactory,
        String snapshotName,
        List<String> indexAllowlist,
        IDocumentMigrationContexts.IShardSetupAttemptContext context
    ) throws IOException, InterruptedException {
        scopedWorkCoordinator.ensurePhaseCompletion(wc -> {
            try {
                return wc.createOrUpdateLeaseForWorkItem(
                    SHARD_SETUP_WORK_ITEM_ID,
                    Duration.ofMinutes(5),
                    context::createWorkAcquisitionContext
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw Lombok.sneakyThrow(e);
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }, new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<Void>() {
            @Override
            public Void onAlreadyCompleted() throws IOException {
                return null;
            }

            @Override
            public Void onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) {
                log.atInfo().setMessage("Acquired work to set the shard workitems").log();
                prepareShardWorkItems(
                    scopedWorkCoordinator.workCoordinator,
                    metadataFactory,
                    snapshotName,
                    indexAllowlist,
                    context
                );
                return null;
            }

            @Override
            public Void onNoAvailableWorkToBeDone() throws IOException {
                return null;
            }
        }, context::createWorkCompletionContext);
    }

    @SneakyThrows
    private static void prepareShardWorkItems(
        IWorkCoordinator workCoordinator,
        IndexMetadata.Factory metadataFactory,
        String snapshotName,
        List<String> indexAllowlist,
        IDocumentMigrationContexts.IShardSetupAttemptContext context
    ) {
        log.info("Setting up the Documents Work Items...");
        SnapshotRepo.Provider repoDataProvider = metadataFactory.getRepoDataProvider();

        BiConsumer<String, Boolean> logger = (indexName, accepted) -> {
            if (Boolean.FALSE.equals(accepted)) {
                log.info("Index " + indexName + " rejected by allowlist");
            }
        };
        repoDataProvider.getIndicesInSnapshot(snapshotName)
            .stream()
            .filter(FilterScheme.filterIndicesByAllowList(indexAllowlist, logger))
            .forEach(index -> {
                IndexMetadata indexMetadata = metadataFactory.fromRepo(snapshotName, index.getName());
                log.info("Index " + indexMetadata.getName() + " has " + indexMetadata.getNumberOfShards() + " shards");
                IntStream.range(0, indexMetadata.getNumberOfShards()).forEach(shardId -> {
                    log.info(
                        "Creating Documents Work Item for index: " + indexMetadata.getName() + ", shard: " + shardId
                    );
                    try (var shardSetupContext = context.createShardWorkItemContext()) {
                        workCoordinator.createUnassignedWorkItem(
                            new IWorkCoordinator.WorkItemAndDuration.WorkItem(indexMetadata.getName(), shardId, 0).toString(),
                            shardSetupContext::createUnassignedWorkItemContext
                        );
                    } catch (IOException e) {
                        throw Lombok.sneakyThrow(e);
                    }
                });
            });

        log.info("Finished setting up the Documents Work Items.");
    }
}
