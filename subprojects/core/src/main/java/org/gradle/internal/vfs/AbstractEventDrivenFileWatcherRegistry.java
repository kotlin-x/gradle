/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.vfs;

import com.google.common.collect.ImmutableSet;
import net.rubygrapefruit.platform.file.FileWatcher;
import net.rubygrapefruit.platform.file.FileWatcherCallback;
import org.gradle.internal.snapshot.CompleteFileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemNode;
import org.gradle.internal.vfs.watch.FileWatcherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.gradle.internal.vfs.watch.FileWatcherRegistry.Type.CREATED;
import static org.gradle.internal.vfs.watch.FileWatcherRegistry.Type.INVALIDATE;
import static org.gradle.internal.vfs.watch.FileWatcherRegistry.Type.MODIFIED;
import static org.gradle.internal.vfs.watch.FileWatcherRegistry.Type.REMOVED;

public abstract class AbstractEventDrivenFileWatcherRegistry implements FileWatcherRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractEventDrivenFileWatcherRegistry.class);

    private final FileWatcher watcher;
    private final AtomicReference<MutableFileWatchingStatistics> fileWatchingStatistics = new AtomicReference<>(new MutableFileWatchingStatistics());
    private final Predicate<String> watchFilter;
    private final Collection<Path> mustWatchDirectories;

    public AbstractEventDrivenFileWatcherRegistry(FileWatcherCreator watcherCreator, Predicate<String> watchFilter, Collection<File> mustWatchDirectories, ChangeHandler handler) {
        this.watcher = createWatcher(watcherCreator, handler);
        this.watchFilter = watchFilter;
        this.mustWatchDirectories = ImmutableSet.copyOf(
            mustWatchDirectories.stream()
                .map(File::toPath)
                .map(Path::toAbsolutePath)
                .collect(Collectors.toList())
        );
    }

    protected Predicate<String> getWatchFilter() {
        return watchFilter;
    }

    protected Collection<Path> getMustWatchDirectories() {
        return mustWatchDirectories;
    }

    public FileWatcher getWatcher() {
        return watcher;
    }

    @Override
    public void nodeRemoved(FileSystemNode removedNode) {
        removedNode.accept(
            (node, parent) -> node.getSnapshot().ifPresent(snapshot -> {
                if (snapshot instanceof CompleteFileSystemLocationSnapshot) {
                    if (!(parent instanceof CompleteFileSystemLocationSnapshot)) {
                        snapshotRemoved((CompleteFileSystemLocationSnapshot) snapshot);
                    }
                }
            }),
            null
        );
    }

    @Override
    public void nodeAdded(FileSystemNode addedNode) {
        addedNode.accept(
            (node, parent) -> node.getSnapshot().ifPresent(snapshot -> {
                if (snapshot instanceof CompleteFileSystemLocationSnapshot) {
                    if (!(parent instanceof CompleteFileSystemLocationSnapshot)) {
                        snapshotAdded((CompleteFileSystemLocationSnapshot) snapshot);
                    }
                }
            }),
            null
        );
    }

    protected abstract void snapshotAdded(CompleteFileSystemLocationSnapshot snapshot);

    protected abstract void snapshotRemoved(CompleteFileSystemLocationSnapshot snapshot);

    private FileWatcher createWatcher(FileWatcherCreator watcherCreator, ChangeHandler handler) {
        return watcherCreator.createWatcher(new FileWatcherCallback() {
            @Override
            public void pathChanged(Type type, String path) {
                handleEvent(type, path, handler);
            }

            @Override
            public void reportError(Throwable ex) {
                LOGGER.error("Error while receiving file changes", ex);
                fileWatchingStatistics.updateAndGet(statistics -> statistics.errorWhileReceivingFileChanges(ex));
                handler.handleLostState();
            }
        });
    }

    private void handleEvent(FileWatcherCallback.Type type, String path, ChangeHandler handler) {
        if (type == FileWatcherCallback.Type.UNKNOWN) {
            fileWatchingStatistics.updateAndGet(MutableFileWatchingStatistics::unknownEventEncountered);
            handler.handleLostState();
        } else {
            fileWatchingStatistics.updateAndGet(MutableFileWatchingStatistics::eventReceived);
            handler.handleChange(convertType(type), Paths.get(path));
        }
    }

    private static Type convertType(FileWatcherCallback.Type type) {
        switch (type) {
            case CREATED:
                return CREATED;
            case MODIFIED:
                return MODIFIED;
            case REMOVED:
                return REMOVED;
            case INVALIDATE:
                return INVALIDATE;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public FileWatchingStatistics getAndResetStatistics() {
        return fileWatchingStatistics.getAndSet(new MutableFileWatchingStatistics());
    }

    @Override
    public void close() throws IOException {
        watcher.close();
    }

    protected interface FileWatcherCreator {
        FileWatcher createWatcher(FileWatcherCallback callback);
    }

    private static class MutableFileWatchingStatistics implements FileWatchingStatistics {
        private boolean unknownEventEncountered;
        private int numberOfReceivedEvents;
        private Throwable errorWhileReceivingFileChanges;

        @Override
        public Optional<Throwable> getErrorWhileReceivingFileChanges() {
            return Optional.ofNullable(errorWhileReceivingFileChanges);
        }

        @Override
        public boolean isUnknownEventEncountered() {
            return unknownEventEncountered;
        }

        @Override
        public int getNumberOfReceivedEvents() {
            return numberOfReceivedEvents;
        }

        public MutableFileWatchingStatistics eventReceived() {
            numberOfReceivedEvents++;
            return this;
        }

        public MutableFileWatchingStatistics errorWhileReceivingFileChanges(Throwable error) {
            if (errorWhileReceivingFileChanges != null) {
                errorWhileReceivingFileChanges = error;
            }
            return this;
        }

        public MutableFileWatchingStatistics unknownEventEncountered() {
            unknownEventEncountered = true;
            return this;
        }
    }
}
